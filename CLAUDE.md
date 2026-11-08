# CLAUDE.md

A self-hosted, Jellyfin-like media server built around shared listening rooms, with a remux-based program output for VRChat video players (RTMP push, MPEG-TS and HLS). The design is in `docs/`. Read `docs/README.md` first, then the doc for the area you're working on.

## Ground rules

- **Do not implement anything from `docs/future-ideas.md`.** Only avoid design choices that would block those ideas later.
- **Implement milestone by milestone**, following `docs/milestones.md`. Don't start the next milestone until the current milestone's "Done when" criteria pass.
- **Java 21**, Maven. Use virtual threads for request handlers and streaming connections.
- **Project coordinates:** Maven `io.siggi:TogetherTunes`, Java base package `io.siggi.togethertunes`. (An older predecessor with the same name exists, but never had an artifact ID; this is a fresh project.)
- **No web framework.** HTTP goes through the author's own HTTP stack, `io.siggi:SiggiHTTP:1.10`, with `io.siggi:SimpleWebsocket` for WebSocket (see `docs/architecture.md#http-stack-requirements`). If it's missing a capability, add it there; don't bring in Netty, Jetty, Spring or similar.
  - **Never call `HTTPResponse.handleRequestWithFile` in SiggiHTTP 1.10**, directly or through `VirtualFileSystemResponder`. A bug lets an `OPTIONS` request truncate or overwrite the file; it's fixed in 1.11. Serve files with `returnFile(file, contentType)` (which handles Range) after checking the method yourself.
- **Database:** plain JDBC, and every schema change needs both `db/migrations/sqlite/` and `db/migrations/postgres/` versions. Store times as epoch ms in `BIGINT` and IDs as UUIDv7 `TEXT`.
- **Frontend:** vanilla TypeScript, no framework, no bundler. `tsc` compiles it to ES modules during the Maven build (`frontend-maven-plugin`, so no system Node is needed), and the output is served as static files.
- **Config:** one JSON file (parsed with Gson) plus environment-variable overrides. Default output profile is 720p30.
- **Media tools** (ffmpeg, ffprobe, yt-dlp) are called as subprocesses: from ingest, from the overlay encoder, and from the v1 RTMP pusher. The packager itself never decodes or encodes.
- **Keep dependencies minimal.** Pre-approved: SiggiHTTP, SimpleWebsocket, SQLite JDBC, PostgreSQL JDBC, Bouncy Castle (Argon2id), Nimbus JOSE+JWT (OIDC), ZXing core (QR), Gson, JUnit and Testcontainers; at build time, TypeScript via `frontend-maven-plugin`. No connection-pool library: write a small pool. Ask before adding anything else.

## Invariants that must not be broken

1. All x264 encodes that feed the packager (mezzanines, cards, overlay re-encodes) use **one shared argument constant**, preset veryfast included. Their H.264 extradata must be byte-identical; ingest and the overlay encoder verify this.
2. Every video GOP is closed, has **no B-frames**, and starts with an IDR **every 2 s exactly**.
3. Program-output timestamps increase smoothly forever. Audio frames are exactly 1920 ticks (90 kHz) apart. Never emit a discontinuity.
4. The room timeline is the only source of truth. The program output follows it and never feeds back into it.
5. **IP addresses are never exposed** to an actor without `ip.view`, in any response, message or export. Only `IpAccess` reads IP columns.
6. Stream URL tokens and RTMP keys are capabilities. Never log them in full.
7. One YouTube video ID maps to exactly one item row, forever (purged items are refetched into the same row).

## Repository layout and git workflow

Two branches, each checked out in its own worktree:

- **`main`**: the code. Checked out at the repository root.
- **`plan`**: the design docs (`docs/`), this `CLAUDE.md` and `PLAN.md`. Checked out at `plan/` inside the root (a git worktree). The root's `CLAUDE.md` and `PLAN.md` are symlinks into it. `plan/`, `CLAUDE.md` and `PLAN.md` are listed in `.git/info/exclude`, so never add them to `main`.

`PLAN.md` holds the implementation steps as checkboxes. Work through it in order.

- **When an implementation step is completed:** commit the code on `main` (from the repository root) and push `main`.
- **Then update the notes:** tick the step in `PLAN.md`, add any decisions or deviations to its notes log, and update `docs/` or this file if the design changed. Commit on `plan` (run git from `plan/`) and push `plan`.
- On the first push of each branch, set its upstream: `git push -u origin main` / `git push -u origin plan`.

## Commands

(Fill in once M0 exists: build, test, run, run against PostgreSQL.)
