# Implementation plan

Step-by-step plan derived from `docs/` (mainly `docs/milestones.md`). Work top to bottom. Every `- [ ]` line is one step; everything else is context. See CLAUDE.md for how to mark steps and what to do after each one.

Legend: `[ ]` todo · `[x]` done · `[?]` implemented, awaiting manual verification by a person · `[-]` dropped (say why)

Steps marked *(manual)* need a person (real browsers, phones, VRChat, real OIDC providers). Do everything an agent can (test pages, scripts, instructions written under the step), then mark the step `[?]`.

Each milestone ends with a **gate** step. The gate always stops the run so that Siggi can review the milestone.

---

## Before M0 — prerequisites

- [x] Locate the author's HTTP stack: `io.siggi:SiggiHTTP` (Maven artifact on the author's repo; repository settings in `~/.m2/settings.xml`, e.g. `https://repo.siggi.io`). Java 8 target, JPMS module `io.siggi.http`, depends on `io.siggi:ZipLib:1.0`.
- [x] Settle the open questions in `docs/README.md#open-questions` (all resolved 2026-11-08; see the notes log and the README).
- [x] Set up dependency resolution in `pom.xml`: `io.siggi:SiggiHTTP:1.11-SNAPSHOT` and `io.siggi:SimpleWebsocket:1.1-SNAPSHOT` resolve only from the local `~/.m2` (their sessions `mvn install` new builds there); declare the author's repository too, for released versions (the `<id>` must match the `<server>`/mirror entry in `~/.m2/settings.xml` if credentials are needed; never commit credentials).
- [x] Check the dev machine has `ffmpeg` (with libx264), `ffprobe` and `yt-dlp`, and note the versions here. Check Docker is available for Testcontainers (PostgreSQL). (Node isn't needed: `frontend-maven-plugin` downloads it.)
  - Checked 2026-11-08 (macOS arm64): **ffmpeg 6.1.1** and **ffprobe 6.1.1** (Homebrew, `/opt/homebrew/bin`, built with `--enable-libx264 --enable-gnutls`; a 720p30 `libx264 -preset veryfast -bf 0 -g 60 -flags +cgop` test encode works and ffprobe reports `has_b_frames=0`). **yt-dlp 2026.03.17** (`/usr/local/bin`; metadata/format selection on a YouTube video works, but it's 8 months old, so run `yt-dlp -U` if YouTube extraction breaks). **Docker 29.9.2** (Docker Desktop 4.98.0, linux/arm64), socket at `/var/run/docker.sock` -> `~/.docker/run/docker.sock`, so Testcontainers needs no extra config; `postgres:16-alpine` (PostgreSQL 16.15) pulls and runs. Also present: Java 21.0.3, Maven 3.9.3.

### Notes on SiggiHTTP and SimpleWebsocket (from reading their sources, 2026-11-08)

We build against **SiggiHTTP 1.11-SNAPSHOT** and **SimpleWebsocket 1.1-SNAPSHOT**. Both have their own Claude sessions that fix bugs and reinstall the snapshots into `~/.m2`. The notes below were made against SiggiHTTP 1.10 and are confirmed by the M0 steps. If something is missing or buggy, follow CLAUDE.md ("Upstream libraries"): report it, don't change those projects.

- **Don't use `HTTPResponse.handleRequestWithFile`** (nor `VirtualFileSystemResponder`, which calls it): in 1.10 an `OPTIONS` request falls through to the `PUT` branch and can truncate or overwrite the file. It's fixed in 1.11, but we don't need it: use `returnFile(file, contentType)`.
- Range: `returnFile(...)` parses `Range` and serves partial content. Check open-ended, suffix (`bytes=-N`), `416`, `HEAD` and `Content-Range`.
- Streaming responses: chunked by default (`setContentLength(-1)`). The `.ts` output needs a **close-delimited** body (no `Content-Length`, no `Transfer-Encoding`), like VRCDN. Check how quickly a disconnect surfaces as a write failure.
- WebSocket (SimpleWebsocket 1.1-SNAPSHOT): SiggiHTTP is a `provided` dependency of it, so we declare SiggiHTTP ourselves. Relevant behaviour for a blocking `read()` loop:
  - `read()` answers pings itself.
  - Keepalive ping after 5 s idle; the 15 s read timeout only fires if the peer stops responding.
  - permessage-deflate negotiated by default; `maxMessageSize` (default 1 MiB, closes with 1009) applies after decompression.
  - `send()` throws once closed; writes time out after 15 s.
  - `close()` waits up to 5 s for the peer's close reply.
  - `getCloseInfo()` explains why `read()` returned null.
  - `WebSocketAcceptor` holds shared settings (`setMaxMessageSize`, `setAllowedOrigins`, …).
- Large uploads: `HTTPRequest.getInputStream()` gives the raw body.
- Virtual threads: `HTTPServerBuilder.setExecutor(Executor)`.
- Per-route hooks: none in SiggiHTTP; build a hook chain in our `http/` adapter layer.
- Client IP: leave SiggiHTTP's handling as is. It trusts `X-Forwarded-For` from loopback, Cloudflare ranges and `server.isIPTrusted(...)`; feed `http.trustedProxies` into the latter. A future SiggiHTTP will require the app to list trusted ranges explicitly.
- SiggiHTTP's own `Sessions` are not used (sessions live in our DB per `docs/auth.md`).

## M0 — Skeleton

- [x] Maven project (`pom.xml`): `io.siggi:TogetherTunes`, base package `io.siggi.togethertunes`, `maven.compiler.release` 21, single runnable jar (shade or assembly plugin, with `Main-Class`), Surefire for JUnit 5 (Failsafe for Testcontainers tests if they should run separately); package layout from `docs/architecture.md#code-layout-suggested`.
- [x] `.gitignore` for build output, `data/`, IDE files, `node/`, `node_modules/`.
- [x] Frontend build: TypeScript sources in `web/src`, compiled by `tsc` (strict, ES modules, no bundler) during `mvn package` via `frontend-maven-plugin` with pinned Node and TypeScript versions; compiled output plus static assets packaged so the server can serve them.
- [x] Config loading: JSON config file (Gson) + environment-variable overrides, `output.profile` defaulting to `720p30`, every key from `docs/architecture.md#configuration`; `http.publicBaseUrl` required; fail fast on bad values.
- [x] Integrate SiggiHTTP: router, request/response adapters, per-route hook chain (auth, CSRF, rate-limit placeholders). Request handlers on virtual threads.

HTTP stack checks, each with tests:

- [x] Range requests through `returnFile` (`bytes=a-b`, open-ended, suffix) → `206` + `Content-Range`; `416` for unsatisfiable; `HEAD`.
- [x] Endless streaming responses: close-delimited (for `.ts`) and chunked; fast client-disconnect detection.
  - Unblocked 2026-11-08: SiggiHTTP 1.11-SNAPSHOT `4be3e04` (or later) adds `HTTPResponse.closeDelimited()` and no longer logs a client leaving mid-body (`0ac2a37`). Use it: `setContentType(...)`, `closeDelimited()` before the headers are sent, `setBuffered(false)`, then `write` + `flush` per chunk. It drops Content-Length and Transfer-Encoding, sends `Connection: close`, closes the connection when the response ends (also when `close()` is called from another thread after `openResponse()`), and works for HEAD. Don't use the old `Content-Length`/`deleteHeader`/`keepAlive(false)` workaround. Earlier findings: chunked endless streams work (`contentLength(-1)`, `setBuffered(false)`, `flush()` per write); a disconnect surfaces as a write `IOException` within about 30 ms on loopback. Still to do: a streaming helper in `http/Response` plus tests (chunked, close-delimited with raw-socket header checks, HEAD, disconnect detection time, no SiggiHTTP error logged when the client leaves). Aborting a blocked response from another thread is still an open question in SiggiHTTP (only needed in M4).
- [x] Streaming multi-GB request bodies to disk.
- [x] WebSocket via SimpleWebsocket 1.1-SNAPSHOT: one `WebSocketAcceptor` with `setMaxMessageSize(65536)` and an origin check matching `publicBaseUrl` (`setAllowedOrigins`; this is the WebSocket CSRF check from `docs/auth.md`). One virtual thread per socket with blocking `read()`; log `getCloseInfo()` when a socket ends.
- [x] Client IP: use SiggiHTTP's resolution, with `http.trustedProxies` (CIDRs, IPv4 and IPv6) fed into `isIPTrusted`; unit-test the CIDR matching.
- [x] Static file serving for `web/` (content types, caching headers, no directory traversal), using `returnFile`, never `handleRequestWithFile` or `VirtualFileSystemResponder`. Test that `OPTIONS`, `PUT` and `DELETE` leave files untouched.

Database:

- [x] `Dialect` interface (upsert, `RETURNING`, case-insensitive search) with SQLite and PostgreSQL implementations.
- [x] Connection handling: SQLite WAL / `foreign_keys=ON` / `busy_timeout=5000`, single write connection (or small pool with retry); a small hand-written PostgreSQL pool (no pool library).
- [x] UUIDv7 generator; epoch-ms time helper.
- [x] Migration runner: `db/migrations/{sqlite,postgres}/NNNN_name.sql`, one transaction per file, `schema_migrations` table.
- [x] Initial schema for **both** dialects: every table in `docs/data-model.md`.
- [x] Settings storage (`settings` table, typed accessors, defaults from `docs/auth.md#server-settings`).
- [x] Secret key handling: `<data>/secret.key` (0600, generated on first start) or env var; AES-GCM helper for `*_enc` columns.
- [x] Test that runs all migrations against SQLite and against PostgreSQL (Testcontainers) and checks the two schemas are equivalent.

Rest of the skeleton:

- [x] Data dir layout created on startup (`db/`, `media/`, `cards/`, `rooms/`, `tmp/`).
- [ ] Test page in `web/` and a test WebSocket echo endpoint.
- [ ] Fill in the **Commands** section of CLAUDE.md (build, test, run, run against PostgreSQL).
- [ ] *(manual)* Browser test of the WebSocket echo in Chrome, Firefox and Safari: connect, send messages, leave idle for over a minute, close tabs, reload. Write the test steps under this item. Report anything odd (unexpected disconnects, close codes, exceptions, with `getCloseInfo()` output) to the SimpleWebsocket session.

**Done when:**

- [ ] Server starts against SQLite and against PostgreSQL; migrations apply on both.
- [ ] Test page loads.
- [ ] Test WebSocket echoes.
- [ ] A Range request returns a correct `206`.
- [ ] **Gate M0:** check every step in M0. If all are `[x]`, tick this gate. Otherwise list the ones that aren't (especially `[?]`) under it and leave it unchecked. Either way, return ok=false so the run stops for review.

## M1 — Identity and accounts

- [ ] Actor resolution: every request → user (`sid` cookie / bearer), guest (`gid` cookie or IP key), or anonymous.
- [ ] Setup flow (`/api/setup`): setup page until the first account exists; first account gets `admin`.
- [ ] Local accounts: register (respecting `registration.mode`), login, logout; Argon2id via Bouncy Castle (m=64 MiB, t=3, p=1, encoded string, rehash on login when params change); min length 10.
- [ ] Sessions: 256-bit opaque tokens, SHA-256 at rest, `sid` cookie flags, 30-day sliding expiry; bearer tokens (`/api/auth/token`); `/api/me/sessions` list/revoke (no IP).
- [ ] CSRF: `Origin` must match `publicBaseUrl` for state-changing cookie-authenticated requests (incl. guest cookie) and for WebSocket upgrades.
- [ ] Rate limiting: login per IP and per username with exponential backoff; generic per-IP limiter reusable for `/live/` later. Failures to `audit_log`.
- [ ] Roles and permissions: built-in `admin` (all, immutable) and `user` (default); custom roles; permission union; full permission list from `docs/auth.md#roles-and-permissions`; feature switches checked before permissions.
- [ ] Admin API + pages: users (incl. admin-set temporary password, disable), roles and role permissions, server settings.

Guests:

- [ ] Lazy guest creation on first identity-requiring action; `POST /api/me/guest`, `PATCH /api/me/guest`, `/api/me` for guests/anonymous.
- [ ] Guest cookie mode (`gid`, 1-year sliding) and IP mode (IPv4 full, IPv6 /64; SHA-256 key; `ip_last`).
- [ ] Generated guest names: bundled ~300-word adjective and noun lists, uniqueness among active guests, numeric suffix after 5 tries.
- [ ] Custom guest names (when allowed): length 2–32, allowed chars, trim/collapse, NFKC + case-insensitive clash check against usernames/display names, optional admin blocklist.
- [ ] Guest badge in actor serialisation (`isGuest`).
- [ ] WebSocket `identityTicket` (single-use, 60 s) issuance (consumption lands in M3).

IP policy and tests:

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
- [ ] **Gate M1:** check every step in M1. If all are `[x]`, tick this gate. Otherwise list the ones that aren't under it and leave it unchecked. Either way, return ok=false so the run stops for review.

## M2 — Ingest, libraries, retention

Media plumbing:

- [ ] Tool wrappers: ffprobe / ffmpeg / yt-dlp subprocess runner with timeouts, stderr capture to job log, `-progress pipe:1` parsing.
- [ ] **The profile constant**: one `List<String>` of x264 args (preset veryfast, no B-frames, closed 60-frame GOPs, forced IDR every 2 s), per output profile (720p30 / 1080p30). Used by every encode.
- [ ] Reference card per profile + its avcC as the invariant reference.
- [ ] MP4 box walker to extract avcC (`moov/trak/mdia/minf/stbl/stsd/avc1/avcC`).
- [ ] Java2D card renderer: item card (artwork, title, artist, album; shrink then ellipsize), server filler cards (Paused, Queue is empty, Loading…).
- [ ] Card encoder (`-loop 1 … -t 2`) and server filler cards under `cards/`.
- [ ] AAC silence frame from `anullsrc`, after encoder priming, generated at startup.
- [ ] Index builder: ffprobe packet CSV → `index.bin` (format in `docs/library-and-ingest.md#sample-index-indexbin`), dropping `D` / negative-PTS packets; index reader with unit tests.

Ingest pipeline:

- [ ] DB-backed ingest queue (`ingest_jobs`), worker pool (`ingest.workers`), survives restart, progress + log tail.
- [ ] Job steps: probe → (download) → loudnorm pass 1 → encode → extract `audio.m4a` → card render+encode → index → verify → publish (atomic directory swap).
- [ ] Originals (`docs/library-and-ingest.md#originals-audio-uploads`): audio uploads keep `original.<ext>` with `original_codec`, `original_web` (codec whitelist + duration within 50 ms) and `original_gain_db = min(−14 − I, −1 − TP)` from loudnorm pass 1; originals of video uploads and YouTube downloads are deleted after ingest.
- [ ] Audio-only items: `audio.m4a` + `card.mp4`, no `mezz.mp4`. Video items: `mezz.mp4`, `audio.m4a`, `card.mp4`.
- [ ] Verify step: audio duration within 50 ms of source, avcC byte-identical to reference, keyframes exactly every 2 s.
- [ ] Re-encode jobs when `output.profile` changes: audio uploads from their original, YouTube items by refetching, video uploads from their old mezzanine.
- [ ] Chunked resumable uploads (`/api/uploads…`): chunks to `tmp/`, streaming SHA-256, size limit, `complete` with dedupe (incl. re-ingest into a purged row), ffprobe rejects (no audio, unparseable, too long), metadata from tags/filename, embedded cover.
- [ ] YouTube: URL → 11-char ID normalisation (all forms in the doc), cache lookup (ready / pending+processing attach / purged → refetch same row / failed retry after 10 min / miss → create), `yt-dlp -J` metadata (reject live, too long), download from canonical URL, title/artist/thumbnail rules, optional cookies file, `presentAsAudio` at import.
- [ ] Permissions for adding media (`library.upload`, `library.youtube` + `ytdlp.enabled`); guest paths stubbed until rooms exist; guest upload quota (per guest and per IP).

Libraries:

- [ ] Library entries API (`/api/library…`), search by `q` and `kind`, cursor pagination.
- [ ] Item metadata endpoints (`GET/PATCH/DELETE /api/items/{id}`, `/job`), edit rules (adder while in library, or `library.editAny`), `library.purge`.
- [ ] Playlists + entries (fractional positions, duplicates allowed, move with `beforeEntryId`).
- [ ] Sharing (`/api/shares`), adds to recipient's library, notifications (`seen`).
- [ ] `library.viewAll` endpoints for other users' libraries and playlists.
- [ ] Media serving `/media/{id}/{audio.m4a|mezz.mp4|original|cover.jpg}` with Range via `returnFile` (not `handleRequestWithFile`; correct content type for the original); media access rules incl. `guests.download` and the current/next `Set<itemId>` (stubbed room state until M3).

Retention:

- [ ] `last_referenced_at` maintenance on every unreference event and on creation.
- [ ] Hourly sweeper: select, re-check in a transaction, delete files (and overlay caches), mark `purged`; `retention.unreferencedDays = 0` disables.

UI:

- [ ] Library, item page (direct link), playlists, upload (chunked, resumable) and YouTube import, job status.

**Done when:**

- [ ] An MP3, a FLAC with cover art, 24 fps and 60 fps videos, a portrait video and a YouTube URL all ingest, with byte-identical avcC.
- [ ] The MP3 and FLAC keep their originals (web-playable, with a gain); the video uploads and the YouTube item keep none.
- [ ] A second form of the same YouTube URL (`youtu.be`, `shorts`, with `&t=`) reuses the item without starting yt-dlp.
- [ ] An unreferenced item is purged after the (shortened) retention period, and a purged YouTube item refetches into the same row.
- [ ] **Gate M2:** check every step in M2. If all are `[x]`, tick this gate. Otherwise list the ones that aren't under it and leave it unchecked. Either way, return ok=false so the run stops for review.

## M3 — Rooms and synchronised web playback

Server:

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

Web client:

- [ ] Room page: now playing, queue, votes, suggestions, request search, upload/YouTube request, history, presence.
- [ ] Clock sync (8 exchanges, min-RTT, 30 s repeat, EMA, outlier filter).
- [ ] Listen mode: audio items play their original with `originalGainDb` via `GainNode` (fallback to `audio.m4a` if not web-playable, `canPlayType` says no, or hard seeks don't converge); sync loop (hard seek / rate nudge / steady), `preservesPitch`, preload next ~10 s before end, "Click to listen" autoplay overlay.
- [ ] Video ↔ audio-only crossfade switch (two synced elements, 150 ms ramp via Web Audio `GainNode`, stop video download in audio-only).
- [ ] Remote mode: no media loaded, delayed progress bar (`estimatedDelayMs`, ±0.5 s user nudge in localStorage); mode remembered per room.
- [ ] Owner management view: room settings (server-disabled options greyed out), members, guest bans, request history.

**Done when:**

- [ ] *(manual)* Two browsers stay within about 50 ms of each other.
- [ ] *(manual)* Video ↔ audio-only switching has no audible gap in Chrome, Firefox and iOS Safari.
- [ ] *(manual)* Audio-only mode never requests `mezz.mp4` (check in the browser's network panel).
- [ ] An audio upload plays from its original, at the same loudness as `audio.m4a` (within about 1 dB).
- [ ] A guest can vote and request according to policy, and gets 403 on a non-current, non-next item when guest downloads are off.
- [ ] **Gate M3:** check every step in M3. If all are `[x]`, tick this gate. Otherwise list the ones that aren't (especially `[?]`) under it and leave it unchecked. Either way, return ok=false so the run stops for review.

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
- [ ] *(manual)* RTMP to VRCDN (or local nginx-rtmp) plays in VRChat and recovers from network cuts.
- [ ] *(manual)* `.ts` and HLS each play in VRChat via AVPro on PC and Quest.
- [ ] *(manual)* Skip, seek and pause show up within about 4 s on `.ts`.
- [ ] *(manual)* Close-delimited `.ts` framing (copied from VRCDN) confirmed in VRChat on PC and Quest.
- [ ] **Gate M4:** check every step in M4. If all are `[x]`, tick this gate. Otherwise list the ones that aren't (especially `[?]`) under it and leave it unchecked. Either way, return ok=false so the run stops for review.

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

- [ ] *(manual)* A QR code on an audio item scans from a phone pointed at a VRChat screen.
- [ ] A music video started from "next" shows the overlay from its first frame (verify with the harness and frame extraction).
- [ ] A music video skipped to mid-item gains the overlay within 4 s with no stream glitch (ffmpeg/ffprobe).
- [ ] *(manual)* Visual check of the mid-item overlay switch: no visible glitch.
- [ ] Without `output.overlayVideo`, music videos play without overlay while cards still show it.
- [ ] **Gate M5:** check every step in M5. If all are `[x]`, tick this gate. Otherwise list the ones that aren't (especially `[?]`) under it and leave it unchecked. Either way, return ok=false so the run stops for review.

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

- [ ] *(manual)* Login works with at least one real OIDC provider and one plain-OAuth provider.
- [ ] Tests cover bad `nonce`, bad `aud`, expired token, unknown `kid`.
- [ ] **Gate M6:** check every step in M6. If all are `[x]`, tick this gate. Otherwise list the ones that aren't (especially `[?]`) under it and leave it unchecked. Either way, return ok=false so the run stops for review.

## M7 — Polish

- [ ] Admin: failed jobs view + retry, `yt-dlp -U` button, audit log viewer.
- [ ] Transition cards and `gapMs`.
- [ ] Docker image bundling ffmpeg (with TLS for `rtmps://`) and yt-dlp; plain-jar instructions.
- [ ] Deployment notes (HTTPS, reverse proxy `proxy_buffering off` for `/live/`, trusted proxies, VRChat allowlist) and backup notes (SQLite / PostgreSQL, `secret.key`, media dir).
- [ ] Manual VRChat release checklist (from `docs/milestones.md#testing-strategy`), written as a document in the repo.
- [ ] **Gate M7:** check every step in M7. If all are `[x]`, tick this gate. Otherwise list the ones that aren't under it and leave it unchecked. Either way, return ok=false so the run stops for review.

## Before the first release

A first release isn't planned yet. These steps wait on upstream releases; if the release isn't out yet, leave the step unchecked with a note and return ok=false.

- [ ] Switch from `io.siggi:SimpleWebsocket:1.1-SNAPSHOT` to the released 1.1 (snapshots only resolve on this machine, so CI and the Docker image need releases).
- [ ] Switch from `io.siggi:SiggiHTTP:1.11-SNAPSHOT` to the released 1.11 (also check whether trusted proxy ranges must now be listed explicitly).

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
- 2026-11-08: SiggiHTTP 1.10's `handleRequestWithFile` has a bug where `OPTIONS` can truncate or overwrite the file (fixed in 1.11). Never use it or `VirtualFileSystemResponder`; use `returnFile`.
- 2026-11-08: Build against **SiggiHTTP 1.11-SNAPSHOT** and **SimpleWebsocket 1.1-SNAPSHOT** from the local `~/.m2`. Their Claude sessions fix reported bugs and `mvn install` new snapshots there.
- 2026-11-08: Plan restructured for the `run-plan` workflow: every `- [ ]` is one step, manual checks are marked `[?]`, and each milestone ends with a gate that stops the run. Upstream-release steps moved to "Before the first release".
- 2026-11-08: Dependency resolution in `pom.xml`: the author's repository is declared with id `siggi-io` (`https://repo.siggi.io`, same id as the repository in the `siggi-io` profile of `~/.m2/settings.xml`), releases only, snapshots disabled. Anonymous read works, so no `<server>` credentials are needed. No snapshot repository is declared, so the two snapshots resolve from the local `~/.m2` only (checked: with an empty local repo they fail to resolve, while SiggiHTTP 1.10 and ZipLib 1.0 download from `siggi-io`). Placeholder project version `1.0-SNAPSHOT`; the M0 step fills in the rest of the pom.
- 2026-11-08: Dev machine tools checked: ffmpeg/ffprobe 6.1.1 with libx264, yt-dlp 2026.03.17, Docker 29.9.2 (Testcontainers works with the default socket). Versions noted under the prerequisites step.
- 2026-11-08: Maven project set up. Shade plugin builds one runnable jar, `target/TogetherTunes-<version>.jar` (`Main-Class: io.siggi.togethertunes.Main`; the unshaded jar stays as `original-…`). The app runs on the **classpath, not as a JPMS module** (SiggiHTTP's `module-info.class` is dropped while shading). Unit tests (`*Test`) run in Surefire (`mvn test`); integration tests (Testcontainers, ffmpeg) are named `*IT` and run in Failsafe during `mvn verify` (`-DskipITs` skips them). JUnit 5.13.4 via BOM. Packages under `io.siggi.togethertunes`: `http`, `auth`, `db`, `library`, `ingest`, `rooms`, `media`, `output`, `overlay`, `retention` (from `docs/architecture.md#code-layout-suggested`; the doc's `server/` level is the base package itself).
- 2026-11-08: Frontend build set up. `frontend-maven-plugin` 2.0.2 installs **Node v24.22.2** (LTS; npm is the bundled one) into `web/node/`, then runs `npm ci` and `npm run build` in `web/` during `generate-resources`. **TypeScript 7.0.2** (the native port; per-platform binaries come as optional deps, all recorded in `web/package-lock.json`) is pinned exactly. `web/tsconfig.json`: `strict` plus `noUncheckedIndexedAccess`, `noUnusedLocals/Parameters`, `noImplicitReturns`, `verbatimModuleSyntax`, `noEmitOnError`; target ES2022, `module` ES2022, source maps with inline sources. No bundler, so relative imports name the compiled file (`./x.js`). Layout: TS in `web/src/` compiles to `target/classes/web/js/`; static assets in `web/static/` are copied to `target/classes/web/` (Maven `<resources>` with `targetPath` `web`), so the jar holds the whole UI under `/web/` (`/web/index.html`, `/web/js/main.js`, ...). The static-file step decides how the server serves it (`returnFile` needs a `File`, so e.g. extract `/web/` from the jar on startup, or serve `target/classes/web/` in dev). `cd web && npm run watch` recompiles on change (with the downloaded Node on `PATH`). `WebBuildTest` checks the packaged output.
- 2026-11-08: Config loading done (`io.siggi.togethertunes.config`: `ConfigLoader`, `Config` record, `OutputProfile` enum with 720p30/1080p30 and their size and fps; **Gson 2.13.2**). Config file: `--config <file>` (or `-c`, `--config=`), else `TOGETHERTUNES_CONFIG`, else `./config.json` if it exists; the file is optional, so a config made only of env vars works. JSON keys can be nested or dotted, numbers may be strings, `null` means default, unknown or duplicate keys are errors (strict Gson reader). Env overrides: `TOGETHERTUNES_` + key with dots as `_` and camelCase as snake case (`http.publicBaseUrl` → `TOGETHERTUNES_HTTP_PUBLIC_BASE_URL`); blank counts as unset; `http.trustedProxies` comma-separated; unknown `TOGETHERTUNES_*` vars are only warnings (so later steps can add e.g. a secret-key variable). Validation collects every problem into one `ConfigException`; `Main` prints them and exits 1. Details: `publicBaseUrl` must be absolute http(s) with a host, no user info/query/fragment, and is stored lowercased without a trailing slash (a path prefix is allowed); `trustedProxies` are IPv4/IPv6 literals or CIDRs (no DNS, host bits must be zero, bare address = /32 or /128), stored normalised; sizes accept bytes or `KiB/MiB/GiB/TiB` (`4 GiB`); `data.dir` resolves against the working directory, not the config file's directory; `db.url` must be `jdbc:sqlite:` or `jdbc:postgresql:`; workers 1–256. Tools: a path must be an executable file, a bare name is resolved on `PATH` to an absolute path; a configured name not on `PATH` is an error, a missing default only a warning. `config.example.json` at the repo root; `/config.json` is git-ignored for local dev.
- 2026-11-08: SiggiHTTP integrated (`io.siggi.togethertunes.http`). `WebServer` owns the `ServerSocket` and accept loop (SiggiHTTP's own `listen()` is deprecated and uses platform threads) and hands each socket to `HTTPServer.handle`, built with `HTTPServerBuilder.setExecutor(newThreadPerTaskExecutor(virtual))`, so every connection and request handler runs on a virtual thread (tested: 200 concurrently blocked handlers). One `Router` is registered at `/` and answers everything itself: patterns with literal segments, `{name}` (one non-empty segment) and a trailing `{name...}` (rest, may be empty but needs the slash); paths are matched after SiggiHTTP's percent-decoding; among routes matching path *and* method the most specific wins (literal > param > rest, segment by segment), so a literal POST route doesn't hide a GET param route; no match → `404`, path matches but method doesn't → `405` + `Allow`, `OPTIONS` → `204` + `Allow`, `HEAD` runs the GET route. Errors use the `docs/api.md` body: `HttpError` (status, snake_case code, message) from handlers/hooks; other exceptions are logged and become `500 internal_error` with no details; a handler that writes nothing gets `404` (like SiggiHTTP's own rule, e.g. `returnFile` on a missing file); after the response is committed exceptions go to SiggiHTTP, which closes the connection. `Request` adapter: path params, query, headers, cookies, `clientIp()` (only for rate limits/guest IP mode/audit, never in responses), `body()`, `readJson` (requires `application/json`, 1 MiB default limit → 413, bad JSON → 400 `invalid_json`), typed attributes for hooks to pass data (e.g. the actor). `Response` writes through to SiggiHTTP (`json`, `text`, `empty`, `error`, `detach()` = `openResponse()`, `raw()` for `returnFile`/streaming). Hook chain: `Hook.handle(req, res, next)`, router-wide hooks (`Hooks.authenticate()`, `Hooks.csrf()`, set in `Main.router`) then the route's own (e.g. `Hooks.rateLimit("login")`), composed once at registration; all three are pass-through placeholders until M1. JSON via a shared `Json.GSON`; logging via `System.Logger`. `Main` now starts the server and blocks (exit 3 if it can't bind); SiggiHTTP's tmp dir is `java.io.tmpdir` until the data-dir step. Note: on `WebServer.close()` idle keep-alive handlers are interrupted and SiggiHTTP prints a harmless "Socket is closed" stack trace for each (seen in tests; not reported upstream).
- 2026-11-08: Range step: SiggiHTTP's `returnFile` mishandled suffix ranges, end positions past the file end and unsatisfiable ranges (no 416). Reported; fixed in SiggiHTTP 1.11-SNAPSHOT `e68dd28`, which TogetherTunes now needs. `RangeRequestTest` (7 tests) passes.
- 2026-11-08: Streaming responses done (needs SiggiHTTP 1.11-SNAPSHOT `4be3e04`+ for `closeDelimited()`). `Response.stream([status,] contentType, StreamBody.Framing.{CHUNKED|CLOSE_DELIMITED})` sets status, type and `Cache-Control: no-store`, picks the framing, turns buffering off and returns a `StreamBody` (an `OutputStream` that flushes every write). For `HEAD` it sends the headers at once and `headOnly()` is true (writes are discarded), so producers must check it and return. A client leaving surfaces as a write `IOException` in about 10–15 ms on loopback (writing every 5 ms); handlers can let it propagate, and SiggiHTTP ends the connection without logging. A detached close-delimited stream can be ended with `StreamBody.close()` from another thread. `StreamingResponseTest` (9 tests, raw sockets) covers header framing, chunk framing, keep-alive after a finite chunked body, HEAD, the detached case, detection time and that nothing is logged on stderr. Aborting a writer blocked on a stalled client from another thread is still open (M4).
- 2026-11-08: Request bodies streamed to disk. `Request.copyBody(out, maxBytes)` streams the raw body (Content-Length or chunked) with a 64 KiB buffer and returns the byte count; wrap `out` in a `DigestOutputStream` for the upload SHA-256. `Request.bodyToFile(path, position, maxBytes)` writes at an offset through a `FileChannel` (CREATE, WRITE, never truncates) for resumable `Content-Range` chunks; the caller validates the offset. Limits: a declared `Content-Length` over `maxBytes` → 413 before reading (and before the file is created); a chunked body → 413 once it passes the limit (bytes up to the limit stay written). A body that ends early (client gone, 60 s SiggiHTTP read timeout, fewer bytes than `Content-Length`) → 400 `incomplete_body`, not logged; received bytes stay in the file. Write errors (disk full) propagate → 500. `Request.contentLength()` added. `WebServer` now calls `setIgnoringMultipartFormData(true)` (SiggiHTTP would otherwise spool any multipart body to the tmp dir on every path before routing) and `setUploadLimit(0)` (its default 2 GB limit refused bigger `Expect: 100-continue` bodies with a plain-text 413; handlers own limits now, and SiggiHTTP still sends `100 Continue`). `RequestBodyTest` (10 tests, raw sockets): Content-Length and chunked bodies to file, keep-alive reuse, offsets, a 4 GiB + 12345 B body (no disk; about 1.3 s on loopback), early 413, Expect over 2 GiB, chunked over limit, truncated bodies, multipart unparsed. Reported to the SiggiHTTP session (non-blocking): `SubInputStream` reports a truncated Content-Length body as a normal EOF; `copyBody` checks the count itself.
- 2026-11-08: WebSocket support done (`io.siggi.togethertunes.http.WebSockets`, one instance per server holding the one `WebSocketAcceptor`). Settings: `setMaxMessageSize(65536)` **and `setMaxPayloadLength(65536)`**: SimpleWebsocket's per-frame limit defaults to 16 KiB, and browsers send each message as a single frame, so without it any 16–64 KiB message would close the socket with 1009 "Frame too big". Origin: `setAllowedOrigins(origin of publicBaseUrl)` (scheme, host, non-default port; a path prefix is dropped), case-insensitive; requests **without** `Origin` are accepted (`setAllowMissingOrigin(true)`, the default), since only browsers send it and only browsers attach a victim's cookies; bearer clients authenticate in their first message. `webSockets.handler(endpoint)` gives a GET route handler: a non-upgrade request → `426 upgrade_required` with `Upgrade: websocket`; a disallowed origin is checked before `accept()` (`isOriginAllowed`) → `403 origin_not_allowed` with the normal JSON error body, logged at INFO; an unsupported `Sec-WebSocket-Version` gets SimpleWebsocket's own 426. After the upgrade the request's handler thread (already a virtual thread) stays with the socket, renamed `ws-<n>`, and runs the blocking `read()` loop; `Endpoint.open(request, ws)` returns a `Session` (`onMessage` for text/binary only; pongs and close frames are skipped; `onClose(info)`). An exception from `open`/`onMessage` is logged and closes with 1011; an `IOException` just ends the socket. When a socket ends it's closed (1001 if the thread was interrupted, e.g. server shutdown, else 1000) and one INFO line is logged: `ws-<n> on <path> closed after <s> s: <getCloseInfo()>`. Route hooks run before the upgrade, so M1 auth can still reject with a plain HTTP error. Nothing is routed yet (the echo endpoint and `/ws` come in later steps). `WebSocketsTest` (15 tests): echo text/binary, origin variants (case, wrong scheme/port/host, `null`, suffix trick) → 403 with no socket opened, missing origin accepted, 426 for plain GET and for version 8, a 64 KiB incompressible message echoes and 64 KiB + 1 closes with 1009, two sockets on distinct virtual threads, close info logged (code and reason), peer vanishing → unclean, handler exception → 1011, server shutdown ends open sockets. Note for clients built on SimpleWebsocket (tests, later tools): their 16 KiB frame default applies to messages *from* the server too.
- 2026-11-08: Client IP step: SiggiHTTP's `isIPTrusted` matched only part of each range, never matched /0, and threw on IPv6 scope IDs. Reported; fixed in 1.11-SNAPSHOT `2a07447`, which TogetherTunes now needs. Our code clears SiggiHTTP's default private-range trust (10/8, 172.16/12, 192.168/16) and trusts only `http.trustedProxies`; loopback and Cloudflare stay trusted inside SiggiHTTP until its 2.0. A test that expected 203.0.113.5 outside 128.0.0.0/1 was wrong (it only passed because of the bug) and was corrected.
- 2026-11-08: Static file serving done (`io.siggi.togethertunes.http.StaticFiles`, main `eb6cdeb`). The web UI is served at the **site root** (`/` = `index.html`, `/js/main.js`, `/css/main.css`, matching the URLs in `index.html`) as the catch-all route `GET /{path...}`, so every more specific route (API, media, live, ws) wins. Only a GET route is registered: the router answers `OPTIONS` itself (`204`, `Allow: GET, HEAD, OPTIONS`) and `PUT`/`DELETE`/`POST`/`PATCH` with `405`; the handler re-checks the method before `returnFile`. Side effect of a catch-all GET: a non-GET request to any unrouted path (e.g. `POST /api/unknown`) gets `405` instead of `404`. Files go through `returnFile(file, contentType)` (Range, ETag, Last-Modified, 304). Content types by extension (`charset=utf-8` on text, `text/javascript` for `.js`, `application/json` for `.map`, `application/octet-stream` for unknown) plus `X-Content-Type-Options: nosniff`. Caching: `Cache-Control: no-cache` on every file, since names carry no content hash (no bundler); revalidation costs a 304. Add hashed/versioned names and long `max-age` later if it matters. Traversal: `.`/`..`/empty segments, backslashes, NUL and dot-files are refused, then the normalised path and the real path (symlinks resolved) must stay inside the root; anything else is a 404 (`returnFile` itself would happily serve a directory or follow `..`). A directory serves its `index.html`; without the trailing slash it redirects `308` to `path/`; no listings. In the jar, `StaticFiles.fromClasspath` extracts `/web/` into a fresh `web-*` directory under `java.io.tmpdir` (entry mtimes kept, so ETags are stable for a build) and deletes it on shutdown; from `target/classes` (IDE, tests) it serves in place. A hard kill leaves the extracted copy behind; the data-dir step should move it to `<data>/tmp` and clear stale copies on startup. `StaticFilesTest` (15 tests, raw sockets): content types, index/redirect, 304 by ETag and date, HEAD and Range, traversal (`..`, `%2e%2e`, `%5c`, `//`, `%00`, symlinks out, dot-files), `OPTIONS` (with and without a body), `PUT`, `DELETE`, `POST`, `PATCH` on existing, new and traversal paths leave the whole tree byte- and mtime-identical, the handler refuses non-GET methods even if mounted for them, and extraction from a jar. Smoke-tested with the packaged jar.
- 2026-11-08: `Dialect` done (`io.siggi.togethertunes.db`, main `69afe8b`). Sealed interface with `SqliteDialect` and `PostgresDialect` singletons; `Dialect.forJdbcUrl(url)`; `name()` is `sqlite`/`postgres` (the migrations subdirectory). Both databases accept the same upsert and `RETURNING` syntax (SQLite 3.24+/3.35+; sqlite-jdbc bundles a newer one, checked by a test), so those builders are shared defaults: `upsert(table, insertColumns, conflictColumns, updateColumns)` → `INSERT ... VALUES (?, ...) ON CONFLICT (...) DO UPDATE SET c = excluded.c` (no update columns → `DO NOTHING`; `insertOrIgnore` is that), `returning(sql, cols...)` appends `RETURNING ...` (run with `executeQuery`; an ignored insert returns no row). Builders accept only plain identifiers (`[A-Za-z_][A-Za-z0-9_]*`), so they can't inject SQL. Case-insensitive search: `containsIgnoreCase(col)` → `<lower(col)> LIKE ? ESCAPE '\'`, bind `Dialect.containsPattern(term)` (folded, `%`/`_`/`\` escaped, wrapped in `%`); `equalsIgnoreCase(col)` → `<lower(col)> = ?`, bind `Dialect.foldCase(value)` (`toLowerCase(Locale.ROOT)`; no accent stripping). SQLite's own `lower()`/`LIKE` fold ASCII only, so `SqliteDialect.initConnection` registers a deterministic Java function `tt_lower()` on the connection; **the connection-handling step must call `dialect.initConnection(conn)` on every new connection** (PostgreSQL's is a no-op). PostgreSQL uses `lower()`, which is Unicode-aware for a UTF-8 database with a non-`C` ctype (true for the official image; document this for PostgreSQL deployments). New dependencies (pre-approved): `org.xerial:sqlite-jdbc` 3.54.1.0 (compile scope, for `org.sqlite.Function`), `org.postgresql:postgresql` 42.7.14, Testcontainers 2.0.5 (`testcontainers-postgresql`, test scope, via BOM). Tests: `DialectTest` (SQL generation, rejects bad identifiers), `DialectContract` run by `SqliteDialectTest` (in-memory) and `PostgresDialectIT` (`postgres:16-alpine`): upserts (single/composite key, set to NULL, ignore), `RETURNING` on upsert/ignored insert/UPDATE/DELETE, search with ASCII, Ö/Ó/Greek, literal wildcards, NULLs. Testcontainers prints an SLF4J "no binding" warning in ITs (harmless; no logging backend added).
- 2026-11-08: Connection handling done (`io.siggi.togethertunes.db.Database` + `ConnectionPool`, main `59d04c2`). All DB work goes through `db.read(c -> ...)` / `db.write(c -> ...)` (`SqlWork<T>`): one transaction per call on a pooled connection, commit when the callback returns, rollback when it throws; the callback must not close the connection or touch auto-commit/read-only. A nested `read`/`write` on the same thread joins the outer transaction (a nested read inside a write sees its uncommitted rows); `write` inside `read` throws `IllegalStateException`. **SQLite:** every connection gets `journal_mode=WAL` (set by the writer; a WARNING is logged if the file system refuses it), `foreign_keys=ON`, `busy_timeout=5000` and `dialect.initConnection` (`tt_lower`). Chose the **single write connection** (a pool of one, so in-process writers queue instead of hitting `SQLITE_BUSY`, no retry loop needed), with `BEGIN IMMEDIATE` so a lock held by another process is waited for up front; reads use up to 4 **read-only** connections that run concurrently with the writer and see the last commit. Keep write callbacks short (no network/file I/O inside). `jdbc:sqlite::memory:` (and `file::memory:`, `mode=memory`) uses the one connection for reads too. Missing parent directories of the SQLite file are created. Gotcha: `SQLiteConfig(Properties)` keeps and returns the very `Properties` object it's given, so every config gets its own copy. **PostgreSQL:** one pool (default 10) for reads and writes; `read` runs `READ ONLY` (SQLState 25006 on a write); `ApplicationName=TogetherTunes`; credentials in the URL (`?user=&password=`) or the `Properties` passed to `Database.open(url, props, settings)`. **Pool:** bounded by a fair `Semaphore` (borrow timeout 30 s → `SQLTransientConnectionException`), lazy opening, LIFO reuse, `isValid` check for connections idle > 10 s, 30 min max lifetime, connections that fail with SQLState `08*`/`57P*` or end up closed are discarded; `ReentrantLock`/`Semaphore` only (no `synchronized`, so waiting virtual threads don't pin). `Database.open` opens one connection right away (fail fast). `Main` opens the DB from `db.url` after loading the config (exit 3 with the driver's message if it fails) and closes it on shutdown (the SQLite writer closes last, which checkpoints and removes the WAL). Pool sizes aren't config keys yet (`Database.PoolSettings`). Tests: `ConnectionPoolTest` (9), `DatabaseContract` run by `SqliteDatabaseTest` (17, temp file: pragmas on writer and readers, single writer, read-modify-write serialised over 100 threads, readers not blocked by an open write and not seeing it, another process's lock waited for, in-memory mode, close removes WAL) and `PostgresDatabaseIT` (15, `postgres:16-alpine`: read-only tx, server-side connection count ≤ pool size via `pg_stat_activity`, reuse, recovery after `pg_terminate_backend`, connection killed mid-transaction is discarded, bad credentials fail at open). Smoke-tested with the jar: `data/db/media.db` created on start.
- 2026-11-08: UUIDv7 and time helper done (`io.siggi.togethertunes.db`, main `1f496ab`). `UuidV7.newId()` returns a canonical lowercase 36-char string (shared generator: `Clock.systemUTC()` + `SecureRandom`; `new UuidV7(clock, random)` for tests). RFC 9562 layout (48-bit ms, ver 7, 12-bit rand_a, variant 10, 62-bit rand_b). Monotonic per generator (RFC method 2): a new millisecond gets 74 fresh random bits; same or earlier clock reading reuses the last timestamp and adds a random step of 1..2^32 to the 74 bits, so IDs strictly increase (also across a backwards clock step) and a neighbour can't be guessed by +1; on overflow the timestamp borrows the next millisecond. Lock is a `ReentrantLock`. Lowercase canonical strings sort like the 128-bit values, so `ORDER BY id` on the `TEXT` column is creation order (tested in SQLite). `UuidV7.isValid(s)` accepts only canonical lowercase v7 strings (use it on IDs from URLs/bodies; invalid = not found); `timestampMs(...)` reads the embedded time. `EpochMs`: `now()`, `now(Clock)` (services that need controllable time take a `java.time.Clock`), `of(Instant)`/`toInstant`, saturating `plus(ms, Duration)`, `format` (ISO-8601 UTC for logs), and nullable `BIGINT` JDBC `get(rs, col)`/`set(ps, i, Long)`. Tests: `UuidV7Test` (12), `EpochMsTest` (4).
- 2026-11-08: Migration runner done (`io.siggi.togethertunes.db.MigrationRunner`, `Migration`, `SqlScript`; main `3ed4201`). Files live in **`src/main/resources/db/migrations/{sqlite,postgres}/`** (classpath `db/migrations/<dialect.name()>/`; currently only `.gitkeep`, the initial-schema step adds `0001_...`). Names must match `NNNN_name.sql` (4–9 digits, version ≥ 1, lower-case `[a-z0-9]` words joined by single underscores); any other non-hidden file in the directory is an error, hidden files are ignored. Loading works from `target/classes` and from the shaded jar (entries are listed through the jar, so directory entries aren't required). `MigrationRunner.migrate(db)` creates `schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at BIGINT NOT NULL)`, then applies pending files in version order, **each in its own `db.write` transaction together with its `schema_migrations` row**, so a failure rolls back that file completely (DDL included, on both databases) and earlier files stay applied; the error names the file and the failing statement. Checksum = SHA-256 of the file with line endings normalised to `\n`. Startup fails (`MigrationException`, `Main` exits 3) when an applied file was edited (checksum mismatch), an applied version has no file (database from a newer build), a new file is numbered below the highest applied version, two files share a version, or a file contains transaction control (`BEGIN`/`COMMIT`/`END`/`ROLLBACK`/`SAVEPOINT`/`RELEASE`/`VACUUM`). Also don't use statements that can't run in a transaction (PostgreSQL `CREATE INDEX CONCURRENTLY`, SQLite `PRAGMA foreign_keys`). Scripts are split into statements by `SqlScript` (JDBC drivers don't reliably run several per `execute`): `;` outside `'...'` (incl. `E'...'`), `"..."`, `` `...` ``, `--`/`/* */` comments and `$$`/`$tag$` dollar quotes; SQLite `CREATE TRIGGER ... BEGIN ... END` bodies (with `CASE ... END`) stay whole. Concurrent runners on one PostgreSQL database serialise on `pg_advisory_xact_lock` in every migration transaction and re-check the version inside it, so each file is applied once (tested with 4 runners); SQLite writes are already exclusive. `Main` runs `MigrationRunner.migrate(database)` right after opening the database. Tests: `SqlScriptTest` (7), `MigrationLoadingTest` (8: names, directory, classpath dir, jar without directory entries, SQLite/PostgreSQL file sets have identical names), `MigrationContract` run by `SqliteMigrationTest` (14, incl. trigger bodies, history kept across reopen) and `PostgresMigrationIT` (14, incl. plpgsql/`DO` dollar quoting and concurrent runners).
- 2026-11-08: Initial schema done (main `302230d`): `db/migrations/{sqlite,postgres}/0001_initial_schema.sql`, every table in `docs/data-model.md`. Column types: `TEXT`, `BIGINT`, `INTEGER`, and `REAL` (SQLite) = `DOUBLE PRECISION` (PostgreSQL) for `position`, `progress`, `loudness_lufs`, `original_gain_db` (PostgreSQL `REAL` is only 4 bytes, too coarse for fractional queue positions). All PKs `NOT NULL` (SQLite would otherwise allow NULL in a TEXT PK). Columns the doc doesn't mark NULL are `NOT NULL`, with these deliberate exceptions/choices: `items.duration_ms` and `items.metadata_json` nullable (unknown before probing), `oidc_providers.client_secret_enc` and `claim_mapping_json` nullable (public clients / pure OIDC), `sessions.user_agent`/`ip`, `audit_log.target`/`details_json`, `user_identities.last_login_at` nullable. Booleans have `CHECK (x IN (0, 1))` and defaults (`enabled` 1, everything else 0); counters/anchors/`version` default 0. Enums are plain `TEXT` (checked in code, per the doc). Actor CHECK `(a IS NULL) <> (b IS NULL)` on `items`, `queue_entries`, `votes`, `skip_votes`. Deviations: `room_requests` uses **at most one** actor (its user/guest FKs are `ON DELETE SET NULL`, `by_name` keeps the name, as the doc's comment implies); `audit_log` has no FKs and both actor columns may be NULL (anonymous failed logins; the log outlives users/guests). FK deletes: rows owned by a room (members, guest bans, queue entries and their votes, requests, outputs) cascade with the room; rows owned by a user (credentials, identities, sessions, user_roles, library entries, playlists, shares, memberships, votes) cascade with the user; `items` are never cascaded (rows are kept forever, invariant 7), so FKs to items are `NO ACTION`; `rooms.current_entry_id` and `room_requests.entry_id` are `SET NULL`; `items.added_by_*`, `queue_entries.added_by_*` and `rooms.owner_id` are `NO ACTION` (deleting users/guests isn't designed yet; guest merge moves those rows first). On PostgreSQL the circular `rooms.current_entry_id` FK is added with `ALTER TABLE` after `queue_entries` (named `rooms_current_entry_id_fkey`, same as an inline one would be); SQLite declares it inline. `username` uniqueness is plain (case-sensitive) `UNIQUE`; case-insensitive/NFKC clash checks happen in code. Indexes on FK and lookup columns (sessions by user/expiry, items by sha256 and (status, last_referenced_at), queue by (room, state, position), requests by (room, at), shares by (to_user, created_at), etc.). Tests: `InitialSchemaContract` run by `SqliteInitialSchemaTest` and `PostgresInitialSchemaIT` (7 each): exact table/column sets vs the doc, actor checks, FK enforcement, `youtube_id` unique, room deletion cascades and keeps items, current entry / request entry set NULL, deleted guest keeps request history, double precision of positions. The full cross-dialect equivalence test is still its own step.
- 2026-11-08: Settings storage done (new package `io.siggi.togethertunes.settings`, main `07aa7ad`). `Settings(db)` / `Settings(db, config)` over the `settings (key, value_json)` table. Each setting is a `SettingKey<T>` constant on `Settings` (name, Java type, default, JSON codec with validation); `Settings.KEYS` lists all 18 in doc order. Typed accessors (`registrationMode()`, `guestsEnabled()`, `guestsUploadQuota()`, `retentionUnreferencedDays()`, `outputsHlsEnabled()`, ...), plus generic `get(key)`, `set(key, value)` (upsert; validates, `IllegalArgumentException` with a readable message), `reset(key)` (deletes the row, so the setting follows its default), `all()` (every effective value in one query) and `setJson(name, jsonText)` for the admin API (strict JSON, unknown names rejected). No cache: every read hits the DB, so changes are seen at once and calls inside `db.read`/`db.write` join that transaction (a rolled-back `set` leaves nothing). A stored row that is malformed or fails validation logs a WARNING and reads as the default; rows with unknown keys are ignored. Stored forms: booleans/ints as JSON literals, enums as their lowercase wire name (`"open"`, `"cookie"`), `guests.uploadQuota` as `{"perHour":3,"maxItemBytes":524288000}` (record `UploadQuota`; per guest and per IP is the enforcing code's job), `ytdlp.cookiesFile` as a string or `null` (empty string = null). **Defaults the docs didn't give, chosen here:** `registration.mode` = `closed` (safe default; the first-user setup page works regardless; there is no invite table yet, so `invite` needs design before it does anything), `ytdlp.enabled` = `true` (users still need `library.youtube`), `ytdlp.cookiesFile` = none. Documented ones: guests per the guest-policy table, `retention.unreferencedDays` 14 (0..36500, 0 = never), all three outputs on, `overlay.enabled` off. `ingest.maxDurationSeconds` is both a config key and a setting: `Settings(db, config)` takes the config value as the setting's default, and the admin UI can override it (`Main` should construct it that way once something uses it). **Default roles are not a setting**: they are `roles.is_default` (already in the schema). Tests: `SettingsContract` (8) run by `SqliteSettingsTest` and `PostgresSettingsIT`: documented defaults, round trips and stored JSON, upsert/reset, validation on set and via `setJson`, bad/unknown stored rows, rollback of a `set` inside an outer transaction, config-supplied default.
- 2026-11-08: Secret key handling done (new package `io.siggi.togethertunes.secrets`, main `91319b4`). `ServerSecret.load(dataDir, env)`: env var **`TOGETHERTUNES_SECRET_KEY`** (base64 of 32 bytes, standard or URL-safe, padding optional) wins and then no file is read or created; otherwise `<data>/secret.key` (one line of base64), generated on first start with mode 0600 (data dir created if needed; written to a temp file and hard-linked into place, rename fallback, so no half-written key and concurrent starts agree on one key). A malformed key (env or file, wrong length, empty file) is a startup error (exit 3), never silently replaced; a file readable by group/others is only a warning. `SecretCipher` (AES-256-GCM): `encrypt(plaintext, context)` / `decrypt(stored, context)`, null passes through; stored form is URL-safe unpadded base64 of `0x01 version || 12-byte random nonce || ciphertext+16-byte tag`; the context, by convention `"<table>.<column>:<row id>"` (e.g. `oidc_providers.client_secret_enc:<id>`), plus the version byte is the AAD, so a ciphertext moved to another row/column fails. Wrong key/context/tampering → `SecretDecryptionException` (message never contains the value). No key rotation yet (the version byte leaves room). `Main` loads the key right after config and prints its source; the cipher isn't passed anywhere yet (TODO for the provider admin / stream outputs steps). `ConfigLoader` knows the variable (`SECRET_KEY_ENV`) so it isn't warned about as unknown. Tests: `SecretCipherTest` (10), `ServerSecretTest` (10, incl. 16 concurrent first starts).
- 2026-11-08: Cross-dialect schema equivalence test done (main `bdb2efe`). `SchemaEquivalenceIT` runs `MigrationRunner.migrate` on a fresh SQLite file and on `postgres:16-alpine`, reads both catalogs into a dialect-neutral `SchemaSnapshot` (test code; SQLite via `sqlite_master` and `pragma_table_info`/`foreign_key_list`/`index_list`/`index_info`, PostgreSQL via `pg_class`/`pg_attribute`/`pg_attrdef`/`pg_constraint`/`pg_index`/`pg_trigger` in `current_schema()`) and fails with one `only in SQLite:` / `only in PostgreSQL:` line per difference. Compared: tables (incl. `schema_migrations`), views, triggers; columns with normalised type (`REAL` = `DOUBLE PRECISION` = `double`), nullability (PK columns count as NOT NULL, since SQLite reports `INTEGER PRIMARY KEY` as nullable) and default (PostgreSQL casts stripped); primary keys in order; foreign keys with referenced columns and ON DELETE/ON UPDATE; unique constraints and unique indexes by column list (names differ between dialects); non-unique indexes by name and columns; partial-index flag; and for each CHECK constraint only the set of columns it uses (PostgreSQL rewrites the expressions, e.g. `IN (0, 1)` → `= ANY (ARRAY[0, 1])`; `InitialSchemaContract` tests their behaviour). Not compared: collations, `NULLS NOT DISTINCT`, the CHECK expressions themselves. A second test proves a nullability difference is reported; also checked by hand that changing an FK action, dropping a CHECK and dropping an index in the PostgreSQL migration each fail the test.
- 2026-11-08: Data dir layout: `DataDir.create` (root package) makes `<data>/` plus `db/`, `media/`, `cards/`, `rooms/`, `tmp/` before the secret key is loaded, follows symlinks (e.g. `media/` on another disk) and fails startup (exit 3) if any is not a writable directory. The HTTP server's temp files and the extracted web UI (`tmp/web-*`, deleted on shutdown) now live in `<data>/tmp` instead of `java.io.tmpdir`. Stale `tmp/web-*` dirs after a crash aren't swept yet; leave that to the M2 tmp/upload cleanup.
