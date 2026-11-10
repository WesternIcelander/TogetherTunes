# CLAUDE.md

A self-hosted, Jellyfin-like media server built around shared listening rooms, with a remux-based program output for VRChat video players (RTMP push, MPEG-TS and HLS). The design is in `docs/`. Read `docs/README.md` first, then the doc for the area you're working on.

## Ground rules

- **Do not implement anything from `docs/future-ideas.md`.** Only avoid design choices that would block those ideas later.
- **Implement milestone by milestone**, following `PLAN.md` (derived from `docs/milestones.md`). Don't start the next milestone until the current milestone's gate step is ticked.
- **Java 21**, Maven. Use virtual threads for request handlers and streaming connections.
- **Project coordinates:** Maven `io.siggi:TogetherTunes`, Java base package `io.siggi.togethertunes`. (An older predecessor with the same name exists, but never had an artifact ID; this is a fresh project.)
- **No web framework.** HTTP goes through the author's own HTTP stack, `io.siggi:SiggiHTTP:1.11-SNAPSHOT`, with `io.siggi:SimpleWebsocket:1.1-SNAPSHOT` for WebSocket (see `docs/architecture.md#http-stack-requirements`). Don't bring in Netty, Jetty, Spring or similar. If either library is missing a capability or has a bug, see "Upstream libraries" below.
  - **Don't call `HTTPResponse.handleRequestWithFile`**, directly or through `VirtualFileSystemResponder`. In 1.10 an `OPTIONS` request could truncate or overwrite the file (fixed in 1.11), and we don't need it. Serve files with `returnFile(file, contentType)` (which handles Range) after checking the method yourself.
- **Database:** plain JDBC, and every schema change needs both `db/migrations/sqlite/` and `db/migrations/postgres/` versions. Store times as epoch ms in `BIGINT` and IDs as UUIDv7 `TEXT`.
- **Frontend:** vanilla TypeScript, no framework, no bundler. `tsc` compiles it to ES modules during the Maven build (`frontend-maven-plugin`, so no system Node is needed), and the output is served as static files. **Browser baseline: iOS 15 / Safari 15.0**, so iOS 15 devices that can't update keep working (Siggi's iPhone X and iPod touch 7), plus current Chrome, Firefox and Safari. Older iOS (e.g. 6) is out of scope. Don't use JavaScript or DOM features newer than that (e.g. `Array.prototype.at`, `Object.hasOwn`, `structuredClone` and `findLast` need 15.4, class static blocks 16.4) unless there's a fallback; check caniuse/MDN when unsure. iOS test devices: iPad Pro M1 (iPadOS 26.1), iPhone X (iOS 15.1) and iPod touch 7th gen (iOS 15.1, can't update to 16). Nobody has a 15.0 device, so 15.0 itself is untested; it differs from 15.1 only in minor fixes.
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

## Upstream libraries (SiggiHTTP, SimpleWebsocket)

SiggiHTTP and SimpleWebsocket are separate projects, each with its own Claude session (named `SiggiHTTP` and `SimpleWebsocket`; find them with `ListAgents`).

- **Never modify them directly**: not their source, not copies or patches in this repo, and no workarounds that reimplement them here.
- **Report bugs and missing capabilities** to that session with `SendMessage`: what's wrong, how to reproduce it, and what TogetherTunes needs. That session fixes it, or tells Siggi if it can't, and then runs `mvn install`, so the fixed `1.11-SNAPSHOT` / `1.1-SNAPSHOT` lands in the local `~/.m2`.
- While waiting for a fix, leave the affected PLAN.md step unchecked with an indented note saying what was reported and to whom, and stop (in a `run-plan` run, return ok=false). Continue once the new snapshot is installed.

## PLAN.md conventions

- Every `- [ ]` line in `PLAN.md` is one step. Do only the step you were given.
- `[x]`: done, the tests for what changed pass, committed and pushed.
- **Tests while iterating:** a step runs only the tests that cover what it changed, not the full suite: e.g. `mvn package -DskipTests` (compiles the Java and the TypeScript) plus the affected test classes (`mvn test -Dtest='ListenJsTest,RoomSocketTest'`), and the affected integration tests (`-Dit.test=...`) only when the change touches what they cover (database, migrations, ingest/ffmpeg, PostgreSQL). Name the tests you ran in the step's notes. The full `mvn verify` runs at the milestone gate.
- `[?]`: **implemented, awaiting manual verification.** Used for steps marked *(manual)* (real browsers, phones, VRChat, real OIDC providers). Do everything an agent can (test pages, scripts, written test instructions under the step), then mark it `[?]`; this counts as success for the run. Siggi flips it to `[x]` after checking.
- If a step can't be finished, leave it `[ ]`, add an indented note explaining why, and stop.
- **Gates:** each milestone ends with a gate step. It first checks the milestone's steps: if any isn't `[x]` (apart from steps PLAN.md explicitly says don't block the gate), it lists what's left and stops **without running the tests**. Only when all are done does it run the full `mvn verify`, and it ticks itself only if that passes (a failure is noted under the gate and stops the run). A ticked gate doesn't stop the run: it continues with the next milestone. A gate that can't be ticked stops the run for review.
- **When a run stops** (a gate can't be ticked yet, the run hit its step limit, a step failed, or it is waiting on upstream), the session that started the run sends Siggi a push notification (`PushNotification`) saying which milestone or step it stopped at and why.

## Repository layout and git workflow

Two branches, each checked out in its own worktree:

- **`main`**: the code. Checked out at the repository root.
- **`plan`**: the design docs (`docs/`), this `CLAUDE.md` and `PLAN.md`. Checked out at `plan/` inside the root (a git worktree). The root's `CLAUDE.md` and `PLAN.md` are symlinks into it. `plan/`, `CLAUDE.md` and `PLAN.md` are listed in `.git/info/exclude`, so never add them to `main`.

`PLAN.md` holds the implementation steps as checkboxes. Work through it in order.

- **When an implementation step is completed:** commit the code on `main` (from the repository root) and push `main`.
- **Then update the notes:** tick the step in `PLAN.md`, add any decisions or deviations to its notes log, and update `docs/` or this file if the design changed. Commit on `plan` (run git from `plan/`) and push `plan`.
- **Push after every commit**, on both branches. The SSH agent provides the GitHub key. On the first push of each branch, set its upstream: `git push -u origin main` / `git push -u origin plan`.

## Commands

All from the repository root. The first build downloads Node into `web/node/` (`frontend-maven-plugin`); `tsc` compiles `web/src/` into the jar on every build.

- **Build + all tests:** `mvn verify`. It runs unit tests (`*Test`, Surefire) and integration tests (`*IT`, Failsafe; Testcontainers starts `postgres:16-alpine`, so Docker must be running). `-DskipITs` skips the `*IT` tests (no Docker needed).
- **Unit tests only:** `mvn test`. One class: `mvn test -Dtest=ConfigLoaderTest`. One integration test: `mvn verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PostgresMigrationIT`.
- **Build the jar without tests:** `mvn package -DskipTests` produces `target/TogetherTunes-1.0-SNAPSHOT.jar` (shaded, includes the web UI; ignore `original-*.jar`).
- **Run (SQLite):** `cp config.example.json config.json`, set `http.publicBaseUrl` (e.g. `http://localhost:8080` for local testing), then `java -jar target/TogetherTunes-1.0-SNAPSHOT.jar`. Without `--config <file>` (or `TOGETHERTUNES_CONFIG`) it reads `./config.json` if present. `config.json` and `data/` are git-ignored. The default database is `<data.dir>/db/media.db`. On first start it creates the data dir layout and `<data.dir>/secret.key` and applies migrations. Every config key can be overridden by an env var (`http.publicBaseUrl` → `TOGETHERTUNES_HTTP_PUBLIC_BASE_URL`); `--help` prints usage. Test pages: `/test/ws-echo.html` (interactive) and `/test/ws-auto.html` (unattended WebSocket browser test; the report also goes to the server log).
- **Run against PostgreSQL:** start a database, e.g.
  `docker run -d --rm --name tt-pg -e POSTGRES_USER=togethertunes -e POSTGRES_PASSWORD=togethertunes -e POSTGRES_DB=togethertunes -p 5432:5432 postgres:16-alpine`,
  then set `db.url` (in `config.json`, or as an env var):
  `TOGETHERTUNES_DB_URL='jdbc:postgresql://localhost:5432/togethertunes?user=togethertunes&password=togethertunes' java -jar target/TogetherTunes-1.0-SNAPSHOT.jar`.
  Credentials go in the URL. Media, cards and `secret.key` still live in `data.dir`. `docker stop tt-pg` stops the database and deletes its data (`--rm`).
- **Stop what you start.** A server or database you start for a smoke test (`java -jar ...`, `docker run ...`) must be stopped before you finish the step, also when the step fails: note its PID and `kill` it (and `docker stop` containers). Leftover servers keep ports busy and confuse later runs.
- **Exit codes:** 1 bad config, 2 bad command line, 3 startup failure (data dir, secret key, database, port in use).
