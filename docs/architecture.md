# Architecture

## Components

```
                   ┌───────────────────────── server process (Java 21) ─────────────────────────┐
 browser / app ──► │ HTTP layer (own stack) ── REST API ── Auth ── Library ── Rooms             │
   (WebSocket) ──► │        │                                    │          │                    │
                   │        │ static files (vanilla TS UI)       │          ├─ RoomState (memory)│
                   │        │ media file serving (Range)         │          └─ Packager (opt.)   │
 VRChat player ──► │        └─ /live/{slug}.ts, .m3u8 ◄─────────┼──────────── │                 │
                   │                                            │             └─► ffmpeg (RTMP) ─┼──► VRCDN etc.
                   │  IngestQueue ── workers ──► ffmpeg/ffprobe/yt-dlp (subprocesses)            │
                   │  DB (JDBC: SQLite | PostgreSQL)      Media store (filesystem)               │
                   └────────────────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|---|---|
| **HTTP layer** | The author's own HTTP server. Routes requests, serves static files and media with Range support, handles WebSocket upgrades and streaming responses that never end. |
| **Auth** | Local accounts, OIDC login, sessions, permission checks. See [auth.md](auth.md). |
| **Library** | Item metadata, search, uploads, yt-dlp imports, and the ingest job queue. See [library-and-ingest.md](library-and-ingest.md). |
| **Rooms** | Room state in memory (queue, timeline, votes, members), persisted to the DB, and broadcast over WebSocket. See [rooms.md](rooms.md). |
| **Packager** | One per room with program output enabled. Builds the continuous TS stream and fans it out to `.ts` listeners, the HLS segmenter and the RTMP pusher. See [program-output.md](program-output.md). |
| **Ingest workers** | A bounded pool that runs ffprobe, ffmpeg and yt-dlp as subprocesses. |
| **Overlay encoder** | A separate bounded pool that renders per-room overlay cards and, where permitted, re-encodes music videos with the QR overlay. See [overlay.md](overlay.md). |
| **Retention sweeper** | A periodic job that deletes items no library or playlist references, after the retention period. See [libraries.md](libraries.md#retention). |
| **Identity** | Resolves every request to a user, a guest, or anonymous. Applies the IP-redaction policy to every response. See [auth.md](auth.md). |

## Process and threading

- One JVM process. Use virtual threads for request handling and for each streaming connection (`.ts` listeners, WebSockets).
- **RoomState** is guarded by a per-room lock, or owned by a single-threaded per-room executor (preferred: simpler to reason about). Every mutation goes through that executor, which then:
  1. persists the change,
  2. bumps `room.version`,
  3. broadcasts the new state (or a delta) to the WebSocket members,
  4. notifies the packager, if there is one.
- **Packager** runs one platform thread per active room output, paced to the wall clock (see program-output.md). It never blocks on a slow consumer: each consumer has its own bounded buffer, and a consumer that falls too far behind is disconnected.
- **Ingest** runs a fixed pool (default: CPU cores / 2, minimum 1) taking jobs from a DB-backed queue, so jobs survive restarts.
- **Overlay encodes** use their own pool (default 1). They are time-critical: they must finish before, or soon after, the item starts playing. Ingest jobs must therefore never delay them. Overlay jobs are not persisted; they are recreated from room state after a restart.

## Storage layout

```
<data>/
  db/                     sqlite file (when using SQLite)
  media/<itemId>/
    original.<ext>        the item's source (upload or YouTube download) — feeds re-encodes; played by the web client for audio uploads only
    v<N>/                 one encode of the item (media version N; items.media_version names the current one)
      mezz.mp4            video+audio (music video) — faststart, playable in browsers
      audio.m4a           audio only (every item) — packager audio; web audio-only mode for video items
      card.mp4            2 s still-card GOP (audio-only items; also used as transition card)
      index.bin           packager sample index (see library-and-ingest.md)
      cover.jpg           artwork (embedded, from YouTube thumbnail, or generated)
  cards/<encoding>/       (e.g. cards/720p30-3000k-v3/) reference.mp4 + reference.avcC; server-wide filler cards paused/idle/loading.mp4 (+ .key fingerprints)
  rooms/<roomId>/
    overlay.png           rendered QR + link overlay (regenerated when the room's slug or overlay settings change)
    overlay.json          overlay.png's spec, position in the frame and overlayHash
    cards/<key>.mp4       per-room cards with the overlay: filler-<id>-…mp4 and item-<itemId>-v<N>-…mp4 (+ .key fingerprints; overlay.md#cards-cheap-path)
    ov/<itemId>/<version>-<overlayHash>-<encoding>-g<g0>/
                          one overlay re-encode of a music video: gops.h264 + gops.idx + the overlay.png it used (see overlay.md)
  tmp/                    in-progress uploads and ingest scratch
  fonts/                  downloaded script fonts (<file>.ttf/.otf from the font manifest) + their OFL licence texts; uploads/<id>.ttf|otf (library-and-ingest.md#card-fonts)
```

A version directory is immutable once published. A re-encode or re-ingest publishes a new `v<N+1>/` and then switches `items.media_version` to it, without waiting for plays to end; the old version is deleted once nobody can still be playing it ([library-and-ingest.md](library-and-ingest.md#storage-layout-and-media-versions)). Items published before versions existed keep their files directly in `media/<itemId>/`: that flat layout is **version 0**, replaced by `v1/` at their next encode.

## Configuration

A single JSON config file, with environment-variable overrides. Keys marked *admin* are also [server settings](auth.md#server-settings): the config (or env var) gives the default, a value saved in the admin UI overrides it and takes effect without a restart. The rest are config-only, because they're needed before the database is open or decide where things are: `http.*`, `data.dir`, `db.url`, `tools.*`.

| Key | Default | Notes |
|---|---|---|
| `http.bind`, `http.port` | `0.0.0.0`, `8080` | TLS is terminated by a reverse proxy, or by the own stack if it supports TLS |
| `http.publicBaseUrl` | — | **Required.** Used to build stream URLs and OIDC redirect URIs |
| `http.insecureBaseUrl` | unset (off) | Optional `http://` address of the same server (env `TOGETHERTUNES_HTTP_INSECURE_BASE_URL`; anything but `http://` stops startup with exit code 1). When set and `http.publicBaseUrl` is `https://`, plain HTTP serves only stream URLs (public and unlisted rooms' slug URLs, private rooms' token URLs) and redirects everything else ([Plain HTTP](#plain-http)). No effect (a startup warning) while `http.publicBaseUrl` is itself `http://` |
| `data.dir` | `./data` | |
| `db.url` | `jdbc:sqlite:<data>/db/media.db` | Or `jdbc:postgresql://…` |
| `tools.ffmpeg`, `tools.ffprobe`, `tools.ytdlp` | resolved from PATH | |
| `output.profile` | `720p30` | *admin*. `720p30` \| `1080p30`. Changing it re-encodes every item ([library-and-ingest.md](library-and-ingest.md#re-encodes)) |
| `output.videoBitrateKbps` | 3000 (720p30), 6000 (1080p30) | *admin* (unset = the profile's default). Video bitrate cap of every encode (mezzanines and cards), and the constant rate the packager pads normal rooms' program output to, 500–25000. Changing it re-encodes every item like a profile change ([library-and-ingest.md](library-and-ingest.md#the-mezzanine-profile)) |
| `output.audioOnlyBitrateKbps` | 800 | *admin*. Constant video stream rate of audio-only rooms' program output (cards only, so it may be below the cap), 500–25000. Re-encodes nothing ([program-output.md](program-output.md#constant-rate-padding)) |
| `ingest.workers` | cores/2 | *admin*. 1..256 |
| `ingest.maxUploadBytes` | 4 GiB | *admin* |
| `ingest.maxDurationSeconds` | 1800 | *admin*. Applies to both uploads and yt-dlp |
| `ingest.quickPreset` | veryfast | *admin*. x264 preset for items a room wants now; default of the admin setting of the same name ([library-and-ingest.md](library-and-ingest.md#presets)) |
| `ingest.qualityPreset` | slow | *admin*. x264 preset for library items (quality re-encodes) |
| `overlay.workers` | 1 | *admin*. Concurrent overlay re-encodes |
| `overlay.cacheMaxBytes` | 20 GiB | *admin*. Least-recently-used eviction of `rooms/*/ov/` |
| `http.trustedProxies` | none | CIDRs whose `X-Forwarded-For` is trusted. Required for correct guest IPs behind a reverse proxy |

Runtime settings live in the DB and are edited in the admin UI: registration mode, OIDC providers, roles, guest policy, the outputs and overlay features, retention days, whether guests may download media, and the *admin* config keys above. They are listed in [auth.md](auth.md#server-settings).

## Deployment

- **Reachability:** web users, and VRChat clients using the `.ts` or HLS outputs, fetch **directly** from the server, so it must be publicly reachable over **HTTPS**. VRChat on Android/Quest refuses plain HTTP. If the host has no public IPv4 address (for example behind CGNAT), run the server on a VPS, or reverse-proxy or tunnel from one. RTMP push only needs outbound connectivity, because viewers fetch the stream from the RTMP service, not from this server. Plain-HTTP deployments are not a supported use case for the upload **pre-check**: it is on only when `http.publicBaseUrl` is `https://` (browsers hash with `crypto.subtle`, which needs a secure context); over HTTP uploads always send every byte ([library-and-ingest.md](library-and-ingest.md#pre-check)).
- **VRChat allowlist:** a self-hosted domain is not on VRChat's video allowlist. Viewers must enable "Allow Untrusted URLs". In public and group-public instances, the world must also list the domain in its "Video Player Allowed Domains". The UI that shows stream URLs says so generically ("Some players only play URLs from an allowlist…", no product names); the VRChat specifics are in [user-guide.md](user-guide.md#in-vrchat).
- **Packaging:** ship a Docker image that bundles ffmpeg and yt-dlp, plus a plain jar for running without Docker.
- **Runtime requirements:** Java 21; ffmpeg, ffprobe and yt-dlp; and for the cards (Java2D text, also headless) the **FreeType library, fontconfig and at least one installed font** (Debian/Ubuntu `libfreetype6 fontconfig fonts-dejavu-core`, Alpine `freetype fontconfig font-dejavu`, Fedora `freetype fontconfig dejavu-sans-fonts`). Card text uses the bundled DejaVu Sans, but Java's font manager needs all three anyway (verified in containers, [library-and-ingest.md](library-and-ingest.md#card-fonts)); without them the server exits at startup with code 3 and says what to install. Script fonts (CJK, Thai, Indic, …) are downloaded on demand from GitHub; offline servers upload fonts or install system fonts instead.
- **Reverse proxy:** turn off response buffering for `/live/` paths (in nginx: `proxy_buffering off`). Otherwise `.ts` streams stall. The proxy must send `X-Forwarded-Proto` (`https` or `http`) and be in `http.trustedProxies` (loopback always is): that is how the server knows a request came over plain HTTP.

### Plain HTTP

(Siggi, 2026-11-10, found in the manual MP3 check: the trucking games' audio library has no TLS and, given an `https://` URL, connects to port 80 over plain HTTP.) Some players can't use HTTPS at all. With `http.insecureBaseUrl` set (and the reverse proxy forwarding port 80 to the server as well), a request that arrived over plain HTTP (the connection itself, or `X-Forwarded-Proto: http` from a trusted proxy; an untrusted client's `X-Forwarded-Proto` is ignored) is handled like this (`http/PlainHttpRedirect`, a `Router` filter that runs before routing, `output/PlainHttpStreams` for the exemption):

- **Served as normal:** `GET`/`HEAD` of a **stream URL**: `/live/{key}.mp3`, `/live/{key}.ts`, `/live/{key}/index.m3u8` and `/live/{key}/seg/{file}`, where the key is the slug of a public or unlisted room or **the token of a private room's output** (`PlainHttpStreams.from`; whether the output is on, and whether the token belongs to that kind, is left to the stream handler, with the same token check, rate limits and connection caps as over HTTPS).
- **`301`** to the same path and query on `http.publicBaseUrl` (`Location`, empty body) for everything else: pages, `/api/*`, `/media/*`, WebSocket upgrades, unknown paths, unknown or revoked keys, and other methods on stream URLs.
- **Private rooms' tokens over plain HTTP** (Siggi, 2026-11-10, superseding the first version, which redirected them: "Token traveling unencrypted in this case isn't a huge deal. Let it travel unencrypted, but add a small warning in the UI that it has previously traveled in cleartext."): the first time a valid token reaches a stream endpoint over plain HTTP, `stream_outputs.token_cleartext_at` is set (`output/CleartextTokens`, `StreamOutputs.markTokenCleartext`; logged with room and output id, never the token). A new token (Rotate, or the room becoming private again) clears it. While it is set, the outputs panel warns on that output ("This URL has been used over unencrypted HTTP (since <date>), so others on the network could have seen it. Rotate it if that matters."). The flag is recorded whenever a token arrives over plain HTTP, also without `http.insecureBaseUrl` (e.g. a local `http://` setup), since it is true then too.
- The outputs API gives every pull output an extra `insecureUrl` (the same slug or token URL on `http.insecureBaseUrl`), shown under the normal URL with Copy ("For players that can't use HTTPS"), and `tokenCleartextAt` for a private room's output while the flag is set.
- **Unset (default):** plain HTTP behaves as before, with no redirect. Decided 2026-11-10: the server can't tell a deliberate plain-HTTP setup from a proxy that only forwards HTTPS, and redirecting would add nothing for the latter (its port 80 never reaches the server), so redirects come only with the key. With an `http://` `http.publicBaseUrl` (local testing) nothing is ever redirected, key or not.

## HTTP stack requirements

The author's HTTP stack is **SiggiHTTP** (`io.siggi:SiggiHTTP:1.10`); WebSocket framing comes from **SimpleWebsocket** (`io.siggi:SimpleWebsocket`), which sits on SiggiHTTP's connection upgrade. The design assumes the stack can do the following. Each needs to be confirmed, or added, in milestone 0.

1. **Range requests** (`bytes=a-b`, open-ended, suffix): `206` with `Content-Range`; `HEAD` support.
2. **Streaming responses with no end**: write and flush incrementally, using either `Transfer-Encoding: chunked` or a body delimited by closing the connection. The handler must find out quickly when the client disconnects (the write fails).
3. **WebSocket** (RFC 6455): text frames, ping/pong, close handling. *Fallback if this isn't available:* Server-Sent Events for server→client messages plus REST POSTs for client→server. The message schema in [api.md](api.md) works over either transport.
4. **Request bodies of several GB** streamed to disk, for uploads. Chunked or resumable upload is described in api.md.
5. **Per-route hooks** for authentication, CSRF checks and rate limiting.

## Browser baseline

The web UI must work on **iOS 15 / Safari 15.0** (iOS 15 devices that can't update, e.g. iPhone X and iPod touch 7th gen) as well as current Chrome, Firefox and Safari. Older iOS is out of scope.

- **Allowed** (Safari 15.0 has them): ES modules, class fields, static fields and methods, private `#` fields and methods, `#x in obj`, top-level await, optional chaining, `??` / `??=`, `replaceAll`, `Promise.any`, `Error` `cause`.
- **Not allowed** without a fallback: `Array/String.prototype.at`, `Object.hasOwn`, `structuredClone`, `findLast` / `findLastIndex` (Safari 15.4), class static blocks (16.4), and anything newer. For DOM APIs, check caniuse/MDN.
- **How it's enforced:** `web/tsconfig.json` uses `target` ES2022 (class fields and `#` members stay native) with `lib` ES2021 + DOM, so tsc rejects the ES2022+ library calls. tsc can't catch static blocks (valid ES2022 syntax) or DOM-lib additions such as `structuredClone`, so `WebBuildTest` scans the compiled `web/js/**/*.js` and fails on class static blocks, `.at(`, `Object.hasOwn`, `structuredClone` and `findLast`. When a new problem feature turns up, add it to that list.

## Code layout (suggested)

```
server/
  http/        adapters onto the own HTTP stack, router, static/media file handler
  auth/        local accounts, OIDC client, sessions, permission checks
  db/          JDBC helpers, dialect, migrations (sqlite/ and postgres/)
  library/     items, search, uploads, yt-dlp import
  ingest/      job queue, ffprobe/ffmpeg wrappers, card renderer (Java2D), index builder
  rooms/       RoomState, executor, queue/vote logic, WebSocket protocol
  media/       mp4 box walker, sample index reader, H.264/AAC helpers (AVCC→Annex B, ADTS)
  output/      packager, TS muxer, .ts endpoint, HLS segmenter, RTMP pusher
  overlay/     QR/overlay renderer, overlay card encoder, music-video overlay re-encoder
  retention/   unreferenced-item sweeper
web/           vanilla TypeScript UI (compiled by tsc into static files during the Maven build)
docs/          this design
```
