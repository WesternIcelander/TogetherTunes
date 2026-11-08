# Architecture

## Components

```
                   ┌───────────────────────── server process (Java 21) ─────────────────────────┐
 browser / app ──► │ HTTP layer (own stack) ── REST API ── Auth ── Library ── Rooms             │
   (WebSocket) ──► │        │                                    │          │                    │
                   │        │ static files (vanilla TS UI)       │          ├─ RoomState (memory)│
                   │        │ media file serving (Range)         │          └─ Packager (opt.)   │
 VRChat player ──► │        └─ /live/{token}.ts, .m3u8 ◄─────────┼──────────── │                 │
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
    mezz.mp4              video+audio (music video) — faststart, playable in browsers
    audio.m4a             audio only (every item) — packager audio; web audio-only mode for video items
    card.mp4              2 s still-card GOP (audio-only items; also used as transition card)
    index.bin             packager sample index (see library-and-ingest.md)
    cover.jpg             artwork (embedded, from YouTube thumbnail, or generated)
    original.<ext>        original file of an audio upload (audio uploads only) — played by the web client
  cards/                  server-wide filler cards (paused, idle, "loading") per output profile
  rooms/<roomId>/
    overlay.png           rendered QR + link overlay (regenerated when the room's slug or overlay settings change)
    cards/<key>.mp4       per-room cards: idle (room name), filler cards and item cards with the overlay
    ov/<itemId>/          overlay re-encode of a music video: gops.h264 + gops.idx (see overlay.md)
  tmp/                    in-progress uploads and ingest scratch
```

Everything under `media/<itemId>/` is immutable once the item is `ready`. A re-ingest writes a new directory and swaps it in atomically.

## Configuration

A single JSON config file, with environment-variable overrides. Keys:

| Key | Default | Notes |
|---|---|---|
| `http.bind`, `http.port` | `0.0.0.0`, `8080` | TLS is terminated by a reverse proxy, or by the own stack if it supports TLS |
| `http.publicBaseUrl` | — | **Required.** Used to build stream URLs and OIDC redirect URIs |
| `data.dir` | `./data` | |
| `db.url` | `jdbc:sqlite:<data>/db/media.db` | Or `jdbc:postgresql://…` |
| `tools.ffmpeg`, `tools.ffprobe`, `tools.ytdlp` | resolved from PATH | |
| `output.profile` | `720p30` | `720p30` \| `1080p30`. Changing it marks every item for re-ingest |
| `ingest.workers` | cores/2 | |
| `ingest.maxUploadBytes` | 4 GiB | |
| `ingest.maxDurationSeconds` | 1800 | Applies to both uploads and yt-dlp |
| `overlay.workers` | 1 | Concurrent overlay re-encodes |
| `overlay.cacheMaxBytes` | 20 GiB | Least-recently-used eviction of `rooms/*/ov/` |
| `http.trustedProxies` | none | CIDRs whose `X-Forwarded-For` is trusted. Required for correct guest IPs behind a reverse proxy |

Runtime settings live in the DB and are edited in the admin UI: registration mode, OIDC providers, roles, guest policy, the outputs and overlay features, retention days, and whether guests may download media. They are listed in [auth.md](auth.md#server-settings).

## Deployment

- **Reachability:** web users, and VRChat clients using the `.ts` or HLS outputs, fetch **directly** from the server, so it must be publicly reachable over **HTTPS**. VRChat on Android/Quest refuses plain HTTP. If the host has no public IPv4 address (for example behind CGNAT), run the server on a VPS, or reverse-proxy or tunnel from one. RTMP push only needs outbound connectivity, because viewers fetch the stream from the RTMP service, not from this server.
- **VRChat allowlist:** a self-hosted domain is not on VRChat's video allowlist. Viewers must enable "Allow Untrusted URLs". In public and group-public instances, the world must also list the domain in its "Video Player Allowed Domains". The UI that shows stream URLs should say so.
- **Packaging:** ship a Docker image that bundles ffmpeg and yt-dlp, plus a plain jar for running without Docker.
- **Reverse proxy:** turn off response buffering for `/live/` paths (in nginx: `proxy_buffering off`). Otherwise `.ts` streams stall.

## HTTP stack requirements

The author's HTTP stack is **SiggiHTTP** (`io.siggi:SiggiHTTP:1.10`); WebSocket framing comes from **SimpleWebsocket** (`io.siggi:SimpleWebsocket`), which sits on SiggiHTTP's connection upgrade. The design assumes the stack can do the following. Each needs to be confirmed, or added, in milestone 0.

1. **Range requests** (`bytes=a-b`, open-ended, suffix): `206` with `Content-Range`; `HEAD` support.
2. **Streaming responses with no end**: write and flush incrementally, using either `Transfer-Encoding: chunked` or a body delimited by closing the connection. The handler must find out quickly when the client disconnects (the write fails).
3. **WebSocket** (RFC 6455): text frames, ping/pong, close handling. *Fallback if this isn't available:* Server-Sent Events for server→client messages plus REST POSTs for client→server. The message schema in [api.md](api.md) works over either transport.
4. **Request bodies of several GB** streamed to disk, for uploads. Chunked or resumable upload is described in api.md.
5. **Per-route hooks** for authentication, CSRF checks and rate limiting.

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
