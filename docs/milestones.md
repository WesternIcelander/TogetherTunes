# Milestones

Build in this order. Each milestone should end with its "Done when" criteria passing, tests included, before the next one starts.

## M0 — Skeleton

- Maven project, Java 21, one runnable jar. Config loading (file + environment overrides).
- Integrate the author's HTTP stack. Confirm, or add, everything listed under [HTTP stack requirements](architecture.md#http-stack-requirements).
- DB layer: `Dialect` interface, a migration runner, the initial schema for SQLite **and** PostgreSQL. Settings storage.
- Static file serving for `web/`, and the TypeScript build (tsc) as part of the Maven build. Trusted-proxy handling for the client IP.

**Done when:** the server starts against both databases, migrations apply, a test page loads, a test WebSocket echoes, and a Range request returns a correct `206`.

## M1 — Identity and accounts

- Setup flow, local registration and login, Argon2id, sessions, CSRF Origin check, rate limiting.
- Roles and permissions, and admin pages for users and roles.
- Guests: lazy identity creation, cookie and IP modes (with IPv6 /64), generated names, custom names with validation, guest badge.
- `IpAccess` and the DTO IP-leak test.

**Done when:** the first user becomes admin; role permissions combine as a union; a guest gets an identity on first need in both modes; custom-name rules are enforced; and the IP-leak test passes across all DTOs.

## M2 — Ingest, libraries, retention

- Chunked uploads with SHA-256 dedupe; the ingest queue and workers; the mezzanine profile constant (veryfast); loudnorm; `audio.m4a`; cards for every item; filler cards and the silence frame; the index builder and the avcC invariant check.
- YouTube: URL→ID normalisation, cache lookup (ready / in-progress / purged → refetch), yt-dlp download.
- Libraries, playlists, sharing, `library.viewAll`, direct-link item pages.
- Media access rules: the guest download setting, and the current/next exception (backed by a stub of room state until M3).
- The retention sweeper and the purged state.
- UI: library, playlists, upload/import, job status.

**Done when:**
- an MP3, a FLAC with cover art, 24 fps and 60 fps videos, a portrait video and a YouTube URL all ingest, with byte-identical avcC;
- the MP3 and FLAC keep their originals (web-playable, with a gain), while the video uploads and the YouTube item keep none;
- pasting a second form of the same YouTube URL (`youtu.be`, `shorts`, with `&t=`) reuses the item without starting yt-dlp;
- an unreferenced item is purged after the (shortened, for the test) retention period, and a purged YouTube item refetches into the same row.

## M3 — Rooms and synchronised web playback

- RoomState and its executor; control modes; votes, vote-skip, suggestions; skipping entries that aren't ready; persistence and resume after restart.
- Guests in rooms (effective capabilities = server AND room), guest bans; request scope search; `room_requests` and the owner's request-history page with "Add to my library".
- The WebSocket protocol and clock sync.
- Web client: listen mode with the sync loop, preloading, and the autoplay overlay; the **video ↔ audio-only crossfade switch**; remote mode, including `/r/{slug}` opening in remote mode.

**Done when:**
- two browsers stay within about 50 ms of each other;
- switching video ↔ audio-only has no audible gap, tested in Chrome, Firefox and iOS Safari;
- audio-only mode never requests `mezz.mp4`;
- an audio upload plays from its original, at the same loudness as `audio.m4a` (within about 1 dB);
- a guest can vote and request according to policy, and gets 403 on a non-current, non-next item when guest downloads are off.

## M4 — Program output (all three outputs)

- Sources, units, the timestamp rules, pacing; the MPEG-TS muxer.
- RTMP push (supervised ffmpeg), the `.ts` endpoint, and the HLS segmenter: all consumers of the same unit stream.
- Server and room switches for each output; the room UI for URLs, tokens, keys and status.
- The offline harness and validation from [program-output.md](program-output.md#testing-the-packager).

**Done when:**
- `ffmpeg -v error -i out.ts -f null -` is clean on a 30-minute scripted session;
- RTMP to VRCDN (or a local nginx-rtmp) plays in VRChat, and recovers from network cuts;
- `.ts` and HLS each play in VRChat via AVPro on PC and Quest;
- skip, seek and pause show up within about 4 s on `.ts`.

Confirm that the close-delimited `.ts` framing copied from VRCDN plays in VRChat on PC and Quest.

## M5 — QR overlay

- Overlay renderer (ZXing), room overlay settings, `/r/{slug}`.
- Overlay cards (fillers and item cards) per room.
- The music-video overlay re-encoder: Annex-B reader, GOP store, the SPS check, the packager's per-GOP substitution, scheduling (current/next), the LRU cache.
- Permission gating: `overlay.enabled` AND the room setting AND, for videos, `output.overlayVideo`.

**Done when:**
- a QR code on an audio item scans from a phone pointed at a VRChat screen;
- a music video started from "next" shows the overlay from its first frame;
- a music video skipped to mid-item gains the overlay within 4 s, with no glitch in the stream (verified with ffmpeg/ffprobe and visually);
- without `output.overlayVideo`, music videos play without the overlay while cards still show it.

## M6 — OIDC and OAuth

- Discovery, PKCE, ID-token validation, linking, signup control, provider admin, OAuth2 claim mapping, guest → account merge.

**Done when:** login works with at least one OIDC provider and one plain-OAuth provider. Tests cover a bad `nonce`, a bad `aud`, an expired token and an unknown `kid`.

## M7 — Polish

- Admin: failed jobs, yt-dlp update, audit log; transition cards and `gapMs`; a Docker image with ffmpeg and yt-dlp; deployment and backup notes.

## M8 — Media types, auto-delete and custom cards

- Media types (`audio`, `uncategorized`, `music_video`, `movie`, `show` with show name, season, episode), classify on library add, still-image detection, no loudness normalisation for movies and shows; room overlay modes (`off`, `always`, `idleOnly`; never on movies and shows); views, auto-delete of movies and shows, pins; removing library entries after media is deleted, admin delete of an item record; Next episode; custom card images. See [media-types.md](media-types.md).

**Done when:** a still-image video ingests as audio with its video undoable; movies and shows never show the QR code and are deleted by the timers unless pinned; custom card images play in the stream with the overlay by the room's mode.

## Testing strategy

- **Unit:** the timeline math, queue ordering per mode, guest capability resolution, the media access rules, the retention predicate, YouTube URL normalisation, the index reader, ADTS, AVCC→Annex B, the Annex-B access-unit splitter, the TS packetiser, the HLS playlist.
- **Golden files:** a packager session (including overlay substitution) written to `.ts` and checked with ffprobe in CI.
- **DB:** repository tests run against SQLite and against PostgreSQL.
- **Security:** the DTO IP-leak test; permission matrix tests for every endpoint (anonymous / guest / user / admin).
- **Manual (VRChat):** a checklist per release covering RTMP, `.ts` and HLS on PC and Quest, the overlay QR scan, join while playing, skip, pause, and going idle.
