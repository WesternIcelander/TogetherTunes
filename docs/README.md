# Media Server — Design

A self-hosted media server, loosely comparable to Jellyfin, centred on **shared listening rooms**. Users join a room and hear the same music at the same time. The room's output is usually played in a **VRChat video player**, and otherwise in a web browser or a mobile app.

This folder is the design. It is written so an implementer (human or Claude Code) can build the system milestone by milestone. Start with [milestones.md](milestones.md).

## Goals

- **Libraries and playlists.** Media items are stored once on the server. Each user has a private library of references to them, and can make playlists and share items with other accounts. Nobody can browse another user's library without a role permission, but anyone with a direct link to an item can open it.
- **Adding media.** Upload a file, or paste a YouTube URL (fetched with yt-dlp and cached by video ID). Admins decide who may do each, including logged-out users.
- **Rooms.** A user creates a room and others join it. The owner controls the queue, or lets listeners vote on songs or edit the queue directly. Every request ends up in the owner's request history, where the owner can add it to their library.
- **Guests.** Logged-out users can vote and request (from a library, by upload or by YouTube URL) where the admin and the room owner allow it. They get a generated name (adjective + noun) or, if the admin allows, a name of their choice. They are identified by a persistent cookie or by IP address (admin setting).
- **Synchronised web/app playback.** Web and app listeners play the files locally, kept in sync with the room's server timeline. They can switch between video and audio-only without a break in the audio, or use **remote mode**, which plays nothing locally (for people who hear the room in VRChat).
- **Program output for VRChat.** The server generates a continuous H.264/AAC stream of the room. It can be delivered by **RTMP push** (e.g. to VRCDN), as a **direct MPEG-TS URL**, or as **HLS**. The three are equal features: the admin enables or disables each one server-wide, and room owners switch on whichever are allowed.
- **QR overlay.** The program output can show a QR code and a link to the room's request/vote page. Audio items and filler screens get the overlay cheaply, through per-room cards. Music videos get it only by re-encoding the video, which the admin allows per role.
- **Low CPU at playback time.** All encoding happens at ingest. The only exception is the optional overlay re-encode of music videos, which runs about 3× faster than real time on 2 cores. The program output itself is produced by **remuxing**: pre-encoded GOPs are spliced together and their timestamps rewritten.
- **Retention.** Items that are in nobody's library or playlist are deleted after a configurable period (default 14 days).
- **Accounts.** Built-in username/password accounts, plus OIDC/OAuth login. Permissions are granted through roles. IP addresses are visible only to roles that have the `ip.view` permission.

## Non-goals (v1)

- An always-on 24/7 channel that isn't tied to a room. The program-output design supports adding it later.
- Crossfades between tracks: hard cuts only, with an optional transition card.
- Overlays that change continuously. The QR overlay is static for each room.
- A native mobile app. The API is designed so one can be built against it later.
- Video-library features beyond music videos: series, seasons, subtitles, multi-audio.
- DRM, and transcoding per client at playback time.
- Live input from OBS (screen sharing in the program output). This is a future idea; see [future-ideas.md](future-ideas.md).

## Decisions

| Topic | Decision |
|---|---|
| Backend | Java (21+, virtual threads) on the **author's own HTTP stack**, not a framework |
| Database | **SQLite and PostgreSQL** both supported, via plain JDBC; SQLite is the default |
| Web frontend | **Vanilla JS/TS**, no framework, served by the backend as static files |
| Media tools | `ffmpeg` / `ffprobe` / `yt-dlp` as subprocesses: at ingest, for overlay encodes, and for the v1 RTMP pusher |
| Codec profile | H.264 (x264 preset **veryfast**, no B-frames, closed 2 s GOPs) + AAC-LC 48 kHz stereo. Identical for every item, card and overlay encode |
| VRChat delivery | RTMP push, MPEG-TS and HLS, all built from one shared TS byte stream; each enabled or disabled independently |
| Web sync | Server clock + timeline anchor; clients play the files locally and correct drift |
| Web audio-only switch | Two media elements synced to the timeline and swapped with a short crossfade, rather than HLS (reasons in [rooms.md](rooms.md#audio-only-switching)) |

## Documents

| File | Contents |
|---|---|
| [architecture.md](architecture.md) | Components, threading, storage layout, configuration, deployment, requirements on the HTTP stack |
| [data-model.md](data-model.md) | Tables, ID and time conventions, notes on the two SQL dialects |
| [auth.md](auth.md) | Accounts, OIDC, sessions, roles and permissions, guests, IP policy, tokens on stream URLs |
| [libraries.md](libraries.md) | Items vs. libraries, playlists, sharing, visibility, media access rules, retention |
| [library-and-ingest.md](library-and-ingest.md) | Upload and yt-dlp import, YouTube caching, the mezzanine encoding profile, cards, sample index |
| [rooms.md](rooms.md) | Room state, timeline model, queue modes and voting, guests in rooms, request history, clock sync, web playback modes |
| [program-output.md](program-output.md) | The packager: splicing, timestamps, MPEG-TS muxer, RTMP / `.ts` / HLS outputs |
| [overlay.md](overlay.md) | QR overlay: per-room cards, re-encoding music videos, permissions |
| [api.md](api.md) | REST endpoints and WebSocket messages |
| [milestones.md](milestones.md) | Build order, with acceptance criteria and testing strategy |
| [future-ideas.md](future-ideas.md) | Ideas that are **not** to be implemented yet (e.g. live input from OBS) |

## Glossary

- **Item**: one media object stored on the server (a track or a music video). Items are shared: many users' libraries can point to the same item.
- **Library entry**: a user's reference to an item.
- **Mezzanine**: the normalised encoding of an item. Every mezzanine uses the same codec parameters, so any two can be spliced together.
- **GOP**: group of pictures — one IDR keyframe followed by P-frames. Every GOP is a closed 2 s unit.
- **Card**: a still image encoded as one 2 s GOP. Used as the "video" for audio-only items, for the paused/idle screen and for transitions.
- **Timeline**: the server's authoritative record of what a room is playing and from where.
- **Program output**: the continuous audio/video stream generated for a room.
- **Packager**: the component that builds the program output from mezzanine samples by remuxing only.
- **Guest**: a logged-out user who has been given an identity (a name), so they can vote or request.

## Open questions

These are to be settled during implementation; the docs note the default they assume.

1. Build tool: Gradle or Maven. **Decided: Maven.**
2. Does the author's HTTP stack support WebSocket and long-lived streaming responses? Both are required; see [architecture.md](architecture.md#http-stack-requirements).
3. Default output resolution: 1080p30 or 720p30. Assumed: 1080p30, configurable per server. Changing it requires re-transcoding the library.
4. Third-party libraries for Argon2id, JWT verification and QR codes (Bouncy Castle, Nimbus JOSE, ZXing core), or hand-written versions. Assumed: use the libraries.
5. Whether to keep original uploads after ingest. Assumed: configurable, off by default.
6. Whether VRChat players handle a chunked or a close-delimited `.ts` response better. To be tested in milestone 4.
