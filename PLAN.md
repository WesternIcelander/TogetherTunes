# Implementation plan

Step-by-step plan derived from `docs/` (mainly `docs/milestones.md`). Work top to bottom. Tick a box (`[x]`) when the step is done and committed on `main`; add a short note under it if something was decided or deviated from the design. A milestone is finished only when every "Done when" box is ticked.

Legend: `[ ]` todo · `[x]` done · `[~]` in progress / partially done · `[-]` dropped (say why)

---

## Before M0 — decisions and prerequisites

- [x] **Locate the author's HTTP stack.** It is `io.siggi:SiggiHTTP:1.10` (Maven artifact, published to the author's repo; repository settings are in `~/.m2/settings.xml`, e.g. `https://repo.siggi.io`). Sources jar is available. Java 8 target, JPMS module `io.siggi.http`, one dependency (`io.siggi:ZipLib:1.0`).
- [ ] Work out how Maven resolves it: declare the author's repository in `pom.xml` (the `<id>` must match the `<server>`/mirror entry in `~/.m2/settings.xml` if credentials are needed; never commit credentials). It already resolves from the local repository on this machine.
- [x] Settle the open questions in `docs/README.md#open-questions` (all resolved 2026-11-08; see the notes log and the README).
- [ ] Confirm the HTTP stack's capabilities with tests in M0 (Range, endless streaming responses, WebSocket, multi-GB request bodies, per-route hooks). First look at the sources (2026-11-08):
  - **Don't use `handleRequestWithFile`** (nor `VirtualFileSystemResponder`, which calls it): in 1.10 an `OPTIONS` request falls through to the `PUT` branch and can truncate or overwrite the file. Fixed in 1.11.
  - Range: `HTTPResponse.returnFile(...)` parses `Range` and serves partial content. Check open-ended, suffix (`bytes=-N`), `416`, `HEAD` and `Content-Range`.
  - Streaming responses: chunked by default (`HTTPResponse`, `setContentLength(-1)`). The `.ts` output needs a **close-delimited** body (no `Content-Length`, no `Transfer-Encoding`), like VRCDN; check SiggiHTTP can do that, and add it if not. Check how quickly a disconnect surfaces as a write failure.
  - WebSocket: use **`io.siggi:SimpleWebsocket:1.1-SNAPSHOT`**, which fixes both issues we reported against 1.0. SiggiHTTP is now a `provided` dependency (built against 1.10), so we declare SiggiHTTP ourselves. It adds `maxMessageSize` (closes with 1009). The snapshot is only in the local `~/.m2` (not on repo.siggi.io or GitHub yet). Behaviour a blocking `read()` loop needs to know about:
    - `read()` answers pings itself.
    - It sends a keepalive ping after 5 s idle, and the 15 s read timeout only fires if the peer stops responding.
    - permessage-deflate is negotiated by default, and `maxMessageSize` applies after decompression.
    - `send()` throws once the connection is closed, and writes time out after 15 s.
    - `close()` waits up to 5 s for the peer's close reply.
    - `getCloseInfo()` explains why `read()` returned null.
  - Large uploads: `HTTPRequest.getInputStream()` gives the raw body; multipart parser and `TooBigException` exist. Check that a raw multi-GB `PUT` can be streamed to disk without buffering.
  - Virtual threads: `HTTPServerBuilder.setExecutor(Executor)`; pass `Executors.newVirtualThreadPerTaskExecutor()` (or similar).
  - Per-route hooks: routing via `HTTPResponderRegistry`; no hook chain seen, so build one in our `http/` adapter layer.
  - Client IP: **leave SiggiHTTP's handling as is for now.** It trusts `X-Forwarded-For` from loopback, Cloudflare ranges and `server.isIPTrusted(...)`; feed `http.trustedProxies` into the latter. A future SiggiHTTP version will stop the implicit trust and require the app to list trusted ranges; adopt it when it's released.
  - SiggiHTTP has its own `Sessions`; we don't use them (sessions live in our DB per `docs/auth.md`).
- [ ] Check the dev machine has `ffmpeg` (with libx264), `ffprobe` and `yt-dlp`, and note the versions. Check Docker is available for Testcontainers (PostgreSQL). (Node isn't needed: `frontend-maven-plugin` downloads it.)

## M0 — Skeleton

- [ ] Maven project (`pom.xml`): `io.siggi:TogetherTunes`, base package `io.siggi.togethertunes`, `maven.compiler.release` 21, single runnable jar (shade or assembly plugin, with `Main-Class`), Surefire for JUnit 5 (Failsafe for Testcontainers tests if they should run separately); package layout from `docs/architecture.md#code-layout-suggested`; JUnit 5 set up.
- [ ] `.gitignore` for build output, `data/`, IDE files, `node/`, `node_modules/`.
- [ ] Frontend build: TypeScript sources in `web/src`, compiled by `tsc` (strict, ES modules, no bundler) during `mvn package` via `frontend-maven-plugin` with pinned Node and TypeScript versions; compiled output plus static assets packaged so the server can serve them.
- [ ] Config loading: JSON config file (Gson) + environment-variable overrides, `output.profile` defaulting to `720p30`, every key from `docs/architecture.md#configuration`; `http.publicBaseUrl` required; fail fast on bad values.
- [ ] Integrate the HTTP stack: router, request/response adapters, per-route hook chain (auth, CSRF, rate-limit placeholders). Request handlers on virtual threads.
- [ ] Fill gaps in the HTTP stack (in the stack itself, not by adding a framework), each with tests:
  - [ ] Range requests (`bytes=a-b`, open-ended, suffix) → `206` + `Content-Range`; `416` for unsatisfiable; `HEAD`.
  - [ ] Endless streaming responses: close-delimited (for `.ts`) and chunked; fast client-disconnect detection.
  - [ ] WebSocket via SimpleWebsocket 1.1-SNAPSHOT: one `WebSocketAcceptor` with `setMaxMessageSize(65536)` and an origin check matching `publicBaseUrl` (`setAllowedOrigins`; this is the WebSocket CSRF check from `docs/auth.md`). One virtual thread per socket with blocking `read()`; log `getCloseInfo()` when a socket ends.
  - [ ] Browser test of the WebSocket echo in Chrome, Firefox and Safari: connect, send messages, leave idle for over a minute, close tabs, reload. Report anything odd (unexpected disconnects, close codes, exceptions, with `getCloseInfo()` output) to the SimpleWebsocket session or to Siggi.
  - [ ] Switch to the released SimpleWebsocket 1.1 once it's published (the snapshot only resolves on this machine, so CI and the Docker image need the release).
  - [ ] Streaming multi-GB request bodies to disk.
- [ ] Client IP: use SiggiHTTP's resolution, with `http.trustedProxies` (CIDRs, IPv4 and IPv6) fed into `isIPTrusted`; unit-test the CIDR matching. Implicit loopback/Cloudflare trust is accepted for now.
- [ ] Static file serving for `web/` (content types, caching headers, no directory traversal), using `returnFile`, never `handleRequestWithFile` or `VirtualFileSystemResponder`. Test that `OPTIONS`, `PUT` and `DELETE` leave files untouched.
- [ ] DB layer:
  - [ ] `Dialect` interface (upsert, `RETURNING`, case-insensitive search) with SQLite and PostgreSQL implementations.
  - [ ] Connection handling: SQLite WAL / `foreign_keys=ON` / `busy_timeout=5000`, single write connection (or small pool with retry); a small hand-written PostgreSQL pool (no pool library).
  - [ ] UUIDv7 generator; epoch-ms time helper.
  - [ ] Migration runner: `db/migrations/{sqlite,postgres}/NNNN_name.sql`, one transaction per file, `schema_migrations` table.
  - [ ] Initial schema for **both** dialects: every table in `docs/data-model.md`.
  - [ ] Settings storage (`settings` table, typed accessors, defaults from `docs/auth.md#server-settings`).
  - [ ] Secret key handling: `<data>/secret.key` (0600, generated on first start) or env var; AES-GCM helper for `*_enc` columns.
  - [ ] Test that runs all migrations against SQLite and against PostgreSQL (Testcontainers) and checks the two schemas are equivalent.
- [ ] Data dir layout created on startup (`db/`, `media/`, `cards/`, `rooms/`, `tmp/`).
- [ ] Test page in `web/` and a test WebSocket echo endpoint.
- [ ] Fill in the **Commands** section of CLAUDE.md (build, test, run, run against PostgreSQL).

**Done when:**
- [ ] Server starts against SQLite and against PostgreSQL; migrations apply on both.
- [ ] Test page loads.
- [ ] Test WebSocket echoes.
- [ ] A Range request returns a correct `206`.

## M1 — Identity and accounts

- [ ] Actor resolution: every request → user (`sid` cookie / bearer), guest (`gid` cookie or IP key), or anonymous.
- [ ] Setup flow (`/api/setup`): setup page until the first account exists; first account gets `admin`.
- [ ] Local accounts: register (respecting `registration.mode`), login, logout; Argon2id via Bouncy Castle (m=64 MiB, t=3, p=1, encoded string, rehash on login when params change); min length 10.
- [ ] Sessions: 256-bit opaque tokens, SHA-256 at rest, `sid` cookie flags, 30-day sliding expiry; bearer tokens (`/api/auth/token`); `/api/me/sessions` list/revoke (no IP).
- [ ] CSRF: `Origin` must match `publicBaseUrl` for state-changing cookie-authenticated requests (incl. guest cookie) and for WebSocket upgrades.
- [ ] Rate limiting: login per IP and per username with exponential backoff; generic per-IP limiter reusable for `/live/` later. Failures to `audit_log`.
- [ ] Roles and permissions: built-in `admin` (all, immutable) and `user` (default); custom roles; permission union; full permission list from `docs/auth.md#roles-and-permissions`; feature switches checked before permissions.
- [ ] Admin API + pages: users (incl. admin-set temporary password, disable), roles and role permissions, server settings.
- [ ] Guests:
  - [ ] Lazy creation on first identity-requiring action; `POST /api/me/guest`, `PATCH /api/me/guest`, `/api/me` for guests/anonymous.
  - [ ] Cookie mode (`gid`, 1-year sliding) and IP mode (IPv4 full, IPv6 /64; SHA-256 key; `ip_last`).
  - [ ] Generated names: bundled ~300-word adjective and noun lists, uniqueness among active guests, numeric suffix after 5 tries.
  - [ ] Custom names (when allowed): length 2–32, allowed chars, trim/collapse, NFKC + case-insensitive clash check against usernames/display names, optional admin blocklist.
  - [ ] Guest badge in actor serialisation (`isGuest`).
  - [ ] WebSocket `identityTicket` (single-use, 60 s) issuance (consumption lands in M3).
- [ ] `IpAccess` service: the only reader of IP columns, checks `ip.view`; `/api/admin/guests`, `/api/admin/audit`, `/api/admin/ip/sessions/{userId}` with IP only for `ip.view`.
- [ ] DTO IP-leak test: serialises every DTO type and fails on anything that looks like an IP address.
- [ ] Permission-matrix test harness (anonymous / guest / user / admin per endpoint), extended in each later milestone.
- [ ] Minimal web UI: setup, login/register, account page (sessions), admin users/roles/settings.

**Done when:**
- [ ] First user becomes admin.
- [ ] Role permissions combine as a union.
- [ ] A guest gets an identity on first need, in cookie mode and in IP mode.
- [ ] Custom-name rules are enforced.
- [ ] IP-leak test passes across all DTOs.

## M2 — Ingest, libraries, retention

### Media plumbing
- [ ] Tool wrappers: ffprobe / ffmpeg / yt-dlp subprocess runner with timeouts, stderr capture to job log, `-progress pipe:1` parsing.
- [ ] **The profile constant**: one `List<String>` of x264 args (preset veryfast, no B-frames, closed 60-frame GOPs, forced IDR every 2 s), per output profile (1080p30 / 720p30). Used by every encode.
- [ ] Reference card per profile + its avcC as the invariant reference.
- [ ] MP4 box walker to extract avcC (`moov/trak/mdia/minf/stbl/stsd/avc1/avcC`).
- [ ] Java2D card renderer: item card (artwork, title, artist, album; shrink then ellipsize), server filler cards (Paused, Queue is empty, Loading…).
- [ ] Card encoder (`-loop 1 … -t 2`) and server filler cards under `cards/`.
- [ ] AAC silence frame from `anullsrc`, after encoder priming, generated at startup.
- [ ] Index builder: ffprobe packet CSV → `index.bin` (format in `docs/library-and-ingest.md#sample-index-indexbin`), dropping `D` / negative-PTS packets; index reader with unit tests.

### Ingest pipeline
- [ ] DB-backed ingest queue (`ingest_jobs`), worker pool (`ingest.workers`), survives restart, progress + log tail.
- [ ] Job steps: probe → (download) → loudnorm pass 1 → encode → extract `audio.m4a` → card render+encode → index → verify → publish (atomic directory swap).
- [ ] Originals (`docs/library-and-ingest.md#originals-audio-uploads`): audio uploads keep `original.<ext>` with `original_codec`, `original_web` (codec whitelist + duration within 50 ms) and `original_gain_db = min(−14 − I, −1 − TP)` from loudnorm pass 1; originals of video uploads and YouTube downloads are deleted after ingest.
- [ ] Audio-only items: `audio.m4a` + `card.mp4`, no `mezz.mp4`. Video items: `mezz.mp4`, `audio.m4a`, `card.mp4`.
- [ ] Verify step: audio duration within 50 ms of source, avcC byte-identical to reference, keyframes exactly every 2 s.
- [ ] Re-encode jobs when `output.profile` changes: audio uploads from their original, YouTube items by refetching, video uploads from their old mezzanine.
- [ ] Chunked resumable uploads (`/api/uploads…`): chunks to `tmp/`, streaming SHA-256, size limit, `complete` with dedupe (incl. re-ingest into a purged row), ffprobe rejects (no audio, unparseable, too long), metadata from tags/filename, embedded cover.
- [ ] YouTube: URL → 11-char ID normalisation (all forms in the doc), cache lookup (ready / pending+processing attach / purged → refetch same row / failed retry after 10 min / miss → create), `yt-dlp -J` metadata (reject live, too long), download from canonical URL, title/artist/thumbnail rules, optional cookies file, `presentAsAudio` at import.
- [ ] Permissions for adding media (`library.upload`, `library.youtube` + `ytdlp.enabled`); guest paths stubbed until rooms exist; guest upload quota (per guest and per IP).

### Libraries
- [ ] Library entries API (`/api/library…`), search by `q` and `kind`, cursor pagination.
- [ ] Item metadata endpoints (`GET/PATCH/DELETE /api/items/{id}`, `/job`), edit rules (adder while in library, or `library.editAny`), `library.purge`.
- [ ] Playlists + entries (fractional positions, duplicates allowed, move with `beforeEntryId`).
- [ ] Sharing (`/api/shares`), adds to recipient's library, notifications (`seen`).
- [ ] `library.viewAll` endpoints for other users' libraries and playlists.
- [ ] Media serving `/media/{id}/{audio.m4a|mezz.mp4|original|cover.jpg}` with Range via `returnFile` (not `handleRequestWithFile`; correct content type for the original); media access rules incl. `guests.download` and the current/next `Set<itemId>` (stubbed room state until M3).

### Retention
- [ ] `last_referenced_at` maintenance on every unreference event and on creation.
- [ ] Hourly sweeper: select, re-check in a transaction, delete files (and overlay caches), mark `purged`; `retention.unreferencedDays = 0` disables.

### UI
- [ ] Library, item page (direct link), playlists, upload (chunked, resumable) and YouTube import, job status.

**Done when:**
- [ ] An MP3, a FLAC with cover art, 24 fps and 60 fps videos, a portrait video and a YouTube URL all ingest, with byte-identical avcC.
- [ ] The MP3 and FLAC keep their originals (web-playable, with a gain); the video uploads and the YouTube item keep none.
- [ ] A second form of the same YouTube URL (`youtu.be`, `shorts`, with `&t=`) reuses the item without starting yt-dlp.
- [ ] An unreferenced item is purged after the (shortened) retention period, and a purged YouTube item refetches into the same row.

## M3 — Rooms and synchronised web playback

### Server
- [ ] Room CRUD (`/api/rooms…`), slugs, visibility, room members and roles, `rooms.create`, `rooms.moderateAny`, ownership transfer.
- [ ] `RoomState` + single-threaded per-room executor: mutate → persist → bump version → broadcast → notify packager hook (no-op until M4).
- [ ] Timeline: extensible `PlaybackState` (no "else = playing"), position math, re-anchoring, advance task rescheduled on every re-anchor, skipping not-ready entries, resume after restart.
- [ ] Control modes `owner` / `vote` / `open`: queue ordering, suggestions accept/reject, votes (+ optional downvotes), pin, vote-skip threshold, permissions per op.
- [ ] Queue ops: add (item / uploadId / youtubeUrl), addPlaylist (+ shuffle), remove, move, pin, vote, voteSkip, play, pause, seek, next, previous; `requestsPerActor`, `noRepeatWindow`.
- [ ] Room settings (`settings_json`) with defaults from `docs/rooms.md#room-settings-settings_json`.
- [ ] Guests in rooms: effective capability = server AND room; guest bans (IP-mode warning without IP); presence counts (identified vs anonymous).
- [ ] Request scope search (`owner` / `owner+self` / `none`), no library-origin leaks.
- [ ] `room_requests` writing; request-history API (owner rw, moderators ro), filters, add-to-library, trim after `rooms.requestHistoryDays`.
- [ ] Replace the M2 current/next stub with the live `Set<itemId>` maintained by room executors.
- [ ] Retention integration for queue entry states.
- [ ] WebSocket protocol (`docs/api.md#websocket`): auth, time, join/leave/mode, sync, identity ticket, all queue/playback messages, `ack`/`error` with `rid`, `identity_required`; full state vs deltas with `version`; `request.new` to owner/mods; unknown types ignored.
- [ ] `/r/{slug}` redirect to the room page in remote mode.

### Web client
- [ ] Room page: now playing, queue, votes, suggestions, request search, upload/YouTube request, history, presence.
- [ ] Clock sync (8 exchanges, min-RTT, 30 s repeat, EMA, outlier filter).
- [ ] Listen mode: audio items play their original with `originalGainDb` via `GainNode` (fallback to `audio.m4a` if not web-playable, `canPlayType` says no, or hard seeks don't converge); sync loop (hard seek / rate nudge / steady), `preservesPitch`, preload next ~10 s before end, "Click to listen" autoplay overlay.
- [ ] Video ↔ audio-only crossfade switch (two synced elements, 150 ms ramp via Web Audio `GainNode`, stop video download in audio-only).
- [ ] Remote mode: no media loaded, delayed progress bar (`estimatedDelayMs`, ±0.5 s user nudge in localStorage); mode remembered per room.
- [ ] Owner management view: room settings (server-disabled options greyed out), members, guest bans, request history.

**Done when:**
- [ ] Two browsers stay within about 50 ms of each other.
- [ ] Video ↔ audio-only switching has no audible gap in Chrome, Firefox and iOS Safari.
- [ ] Audio-only mode never requests `mezz.mp4`.
- [ ] An audio upload plays from its original, at the same loudness as `audio.m4a` (within about 1 dB).
- [ ] A guest can vote and request according to policy, and gets 403 on a non-current, non-next item when guest downloads are off.

## M4 — Program output (all three outputs)

- [ ] H.264/AAC helpers: AVCC → Annex B (with AUD, SPS/PPS before IDR), ADTS header writer; unit tests.
- [ ] Sources: `VideoItemSource`, `AudioItemSource` (incl. `presentAsAudio`), `FillerSource` (Paused / Idle / Loading). Source abstraction must not assume file-backed or known-duration sources (future live input).
- [ ] Unit builder + timestamp rules: audio-driven boundaries (1920-tick frames), video follows audio, card GOP repetition and truncation, fillers in whole units, clock start at 900000, 33-bit wrap, DTS = PTS.
- [ ] Applying timeline changes at unit boundaries; natural end mid-unit; >3 s drift re-sync at item boundaries; unknown state → Paused.
- [ ] Index loading for current + next only; positional reads.
- [ ] MPEG-TS muxer: PIDs as VRCDN (SDT 0x11, PMT 0x100, video 0x101, audio 0x102), SDT/PAT/PMT at each unit start and PAT/PMT about every 400 ms, RAI on IDR, PES for video (unbounded) and audio (~5 frames), interleave by DTS, PCR on every video PES, continuity counters for packager lifetime, stuffing. Each unit's bytes produced once as an immutable `byte[]`.
- [ ] Pacing thread (one platform thread per active output, LEAD 1000 ms); per-consumer bounded queues, slow consumers disconnected.
- [ ] Packager lifecycle: start on enable or first request; stop 60 s after last consumer unless RTMP enabled.
- [ ] Offline harness: scripted fake timeline (video items, audio items, pause, seek, empty queue) → file, no pacing.
- [ ] `.ts` endpoint: `GET`/`HEAD /live/{token}.ts`, join=next (and room setting join=latest), 4-unit queue, close-delimited body (no `Content-Length`, no `Transfer-Encoding`), `HEAD` → `200` with `Content-Length: 0`, as VRCDN does; cheap HEAD/short GET probes not counted as viewers.
- [ ] HLS segmenter: ring of 8, playlist of 6, real `EXTINF`, correct headers.
- [ ] RTMP pusher: supervised ffmpeg (`-c copy -f flv`), backoff 1/2/5/10/30 s, status with last stderr line.
- [ ] Stream outputs API (`/api/rooms/{id}/outputs…`, rotate), server switches `outputs.*.enabled`, room switches, tokens (128-bit base64url, never fully logged), RTMP key encrypted and shown as last 4 only, per-IP rate limit and per-token connection cap (64).
- [ ] Room UI: URLs with copy buttons, rotate/disable, RTMP status and playback URL, VRChat allowlist note, HLS desync note; `output.status` WS messages; `estimatedDelayMs` defaults.
- [ ] Golden-file test in CI: harness output checked with ffprobe (PTS strictly increasing, audio delta exactly 1920).

**Done when:**
- [ ] `ffmpeg -v error -i out.ts -f null -` is clean on a 30-minute scripted session.
- [ ] RTMP to VRCDN (or local nginx-rtmp) plays in VRChat and recovers from network cuts. *(manual)*
- [ ] `.ts` and HLS each play in VRChat via AVPro on PC and Quest. *(manual)*
- [ ] Skip, seek and pause show up within about 4 s on `.ts`. *(manual)*
- [ ] Close-delimited `.ts` framing (copied from VRCDN) confirmed in VRChat on PC and Quest. *(manual)*

## M5 — QR overlay

- [ ] Overlay renderer: ZXing QR (level M, quiet zone 4) for `{publicBaseUrl}/r/{slug}`, URL text, rounded semi-opaque panel; corner / heightPct / margin settings; `overlayHash`; regenerate on slug, settings or base URL change.
- [ ] Permission gating: server `overlay.enabled` AND room `overlay.enabled` AND (for videos) owner holds `output.overlayVideo`; decision recorded per queue entry.
- [ ] Overlay cards: per-room filler cards and item cards (on current/next), keyed `(itemId, overlayHash, profile)`; packager uses them.
- [ ] Overlay encoder pool (`overlay.workers`), separate from ingest, not persisted.
- [ ] Music-video re-encoder: ffmpeg with profile constant from GOP `g0`; Annex-B reader, NAL splitter, access-unit grouping (`first_mb_in_slice`), SPS/PPS check vs reference (abort on mismatch), drop SPS/PPS/AUD/SEI, `gops.h264` + `gops.idx`, completion `BitSet`.
- [ ] Packager per-GOP substitution (overlay GOP when complete, original otherwise).
- [ ] Scheduling: current (high) / next (normal); cancel when no longer next unless >50% done; busy pool → play without overlay.
- [ ] LRU cache (`overlay.cacheMaxBytes`) for `rooms/*/ov/` and room cards; delete on purge, room delete, `overlayHash` change; discard partials on restart.
- [ ] Room UI for overlay settings.
- [ ] Unit tests: Annex-B splitter, AU grouping; golden-file packager session including substitution.

**Done when:**
- [ ] A QR code on an audio item scans from a phone pointed at a VRChat screen. *(manual)*
- [ ] A music video started from "next" shows the overlay from its first frame.
- [ ] A music video skipped to mid-item gains the overlay within 4 s with no stream glitch (ffmpeg/ffprobe + visual).
- [ ] Without `output.overlayVideo`, music videos play without overlay while cards still show it.

## M6 — OIDC and OAuth

- [ ] Provider admin (`/api/admin/providers`), `client_secret_enc`, `allow_signup`, `claim_mapping_json`; `/api/auth/providers`.
- [ ] Discovery + JWKS caching, refetch on unknown `kid`.
- [ ] Authorization code + PKCE S256, `state`/`nonce` stored server-side.
- [ ] ID-token validation via Nimbus: RS256/ES256 only by default, `iss`, `aud`, `exp`, `iat` ±60 s, `nonce`.
- [ ] Identity resolution: login / link (`intent=link`, never by email) / signup / error; `/api/me/identities`.
- [ ] Plain OAuth2 (Discord, GitHub) via userinfo URL + claim mapping.
- [ ] Guest → account merge (`/api/me/merge-guest`), cookie mode only, in the browser holding the cookie; moves requests, queue entries and votes.
- [ ] Tests: bad `nonce`, bad `aud`, expired token, unknown `kid` (against a local fake IdP).

**Done when:**
- [ ] Login works with at least one OIDC provider and one plain-OAuth provider.
- [ ] Tests cover bad `nonce`, bad `aud`, expired token, unknown `kid`.

## M7 — Polish

- [ ] Admin: failed jobs view + retry, `yt-dlp -U` button, audit log viewer.
- [ ] Transition cards and `gapMs`.
- [ ] Docker image bundling ffmpeg (with TLS for `rtmps://`) and yt-dlp; plain-jar instructions.
- [ ] Deployment notes (HTTPS, reverse proxy `proxy_buffering off` for `/live/`, trusted proxies, VRChat allowlist) and backup notes (SQLite / PostgreSQL, `secret.key`, media dir).
- [ ] Manual VRChat release checklist (from `docs/milestones.md#testing-strategy`).

---

## Notes / decisions log

Record decisions, deviations from `docs/`, and answers to open questions here (newest last), with the date.

- 2026-11-08: Plan created from the design docs. No implementation yet.
- 2026-11-08: HTTP stack is `io.siggi:SiggiHTTP:1.10`. Capability survey in "Before M0".
- 2026-11-08: Build tool is Maven, not Gradle (README open question 1 resolved).
- 2026-11-08: Remaining open questions resolved:
  - Default output profile **720p30**, configurable (`output.profile`).
  - **Audio uploads keep their original**, and the web player uses it (gain-matched to −14 LUFS, with fallback to `audio.m4a`). Video and YouTube originals are discarded. This replaces the former `ingest.keepOriginals` setting.
  - Libraries: Bouncy Castle, Nimbus, ZXing as planned; **Gson** for JSON; no connection-pool library (hand-written pool).
  - `.ts` framing: copy VRCDN. Observed on its stream: close-delimited body, `HEAD` → `200` + `Content-Length: 0`, SDT/PAT/PMT at the start, PAT/PMT about every 0.4 s, PIDs 0x100/0x101/0x102, joins mid-GOP with no burst. We copy all of it except the join point (`join=next` instead of mid-GOP). Details in `docs/program-output.md`.
  - WebSocket: **SimpleWebsocket**.
  - Client IP: leave SiggiHTTP's XFF handling as is; a future SiggiHTTP will require explicit trusted ranges.
  - Frontend in **TypeScript** (tsc via `frontend-maven-plugin`, no bundler).
  - Config file is **JSON**.
  - Coordinates `io.siggi:TogetherTunes`, package `io.siggi.togethertunes`.
- 2026-11-08: SimpleWebsocket 1.1-SNAPSHOT (local `~/.m2` only) fixes our two reports: SiggiHTTP 1.10 as `provided`, and `maxMessageSize`. Use it, and test it in real browsers in M0 before 1.1 is released.
- 2026-11-08: SiggiHTTP 1.10's `handleRequestWithFile` has a bug where `OPTIONS` can truncate or overwrite the file (fixed in 1.11). Never use it or `VirtualFileSystemResponder`; use `returnFile`. Consider moving to 1.11 when it's released.
