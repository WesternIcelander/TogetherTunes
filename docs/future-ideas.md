# Future ideas

**Not to be implemented yet.** These ideas are written down so that current work doesn't make them harder. No milestone includes them. Don't build any of them unless a later version of this design moves them into [milestones.md](milestones.md).

## Live input from OBS

**Goal:** a room owner streams from OBS into the room's program output. Music playback pauses while the live input is on air, and the owner can, for example, share their PC screen with viewers in VRChat. When the live input ends, music resumes.

### Sketch

- **Ingest:** a per-room live input with its own secret stream key. OBS pushes to it over RTMP (SRT could be added later). Version 1 could accept the push with an ffmpeg process per active input (`-listen 1 -f flv -i rtmp://…` → MPEG-TS on stdout), and the server demuxes the TS into access units and AAC frames. A native Java RTMP server could replace that later.
- **Timeline:** add a `live` state alongside `playing` / `paused` / `idle`. Going live pauses the music, keeping its position. Ending live, manually or by OBS disconnecting, returns to the previous state. Web listeners in listen mode would either see a "live on stream" notice or, as an extension, get the live feed.
- **Packager:** a `LiveInputSource` produces units from the incoming stream. Its timestamps are rewritten onto the continuous output clock, exactly like any other source, and it switches in and out at keyframe boundaries. If the input stalls or disconnects, the packager falls back to the Paused card, so the program output never stops.
- **Overlay:** with the QR overlay enabled, the live input needs the overlay composited onto it, which means using the transcode mode below.

### Compatibility: passthrough vs. transcode

The packager can only splice by remuxing if the incoming video matches the profile. Two modes:

1. **Passthrough** (cheapest, but fragile). OBS must be configured to match the profile:
   - Output mode: Advanced; encoder: x264; rate control: CBR around 6000 kb/s; keyframe interval: 2 s;
   - CPU preset: veryfast; profile: high; x264 options: `bframes=0`;
   - output resolution: 1920×1080 (or the server's profile); FPS: exactly 30 (not 29.97);
   - audio: 48 kHz, stereo, AAC.

   Even then, OBS's SPS/PPS will probably not be byte-identical to the server's, and with NVENC or other hardware encoders it certainly won't be. Passthrough would therefore have to send the input's own SPS/PPS in-band at the switching IDR. Most decoders accept new parameter sets at an IDR when the resolution is unchanged. For the RTMP output, though, it means sending a new AVC sequence header mid-stream, which some services and players handle poorly. This would need testing per output and per VRChat platform.

2. **Transcode** (robust). Decode the input and re-encode it with the shared profile argument constant: it composites the overlay if needed, normalises the frame rate and resolution, and forces 2 s IDRs. The output is guaranteed to have identical extradata, so splicing is as safe as for any item. It costs about 0.7 of a CPU core at 1080p30 (veryfast), and only while the input is live. It works with any OBS settings, including NVENC.

**Suggested default:** transcode. Passthrough would be an optimisation, used only when the input's SPS/PPS happen to match the reference exactly.

### Open questions

- How do web listeners experience a live segment (notice only, or the live feed through HLS)?
- How much latency does OBS → server → RTMP service → VRChat add, compared with the music path?
- Should non-owners (moderators) be allowed to go live?

### What current work should keep open

- The packager's source abstraction should not assume that sources are files with a known duration.
- The room timeline's state is already an extensible enum (`playback_state`; see [rooms.md](rooms.md#room-state)), so `LIVE` can be added without a schema change.

## Mobile apps (iOS and Android)

**Goal:** native apps for iOS and Android with the same library management and media player features as the web UI (library, playlists, uploads, YouTube import, rooms, room management), plus **offline playback** of playlists the user picks, and joining online rooms without downloading the current item when it is already stored on the device. Planned for after the current plan is complete (noted 2026-11-10).

### Why native

Since 2026-11-10 Safari/WebKit is only best effort for the web client (Siggi's decision after the M3 browser tests): iOS users are meant to use the native app.

The M3 browser tests showed that WebKit (Safari, and every browser on iOS) is the hard platform for synchronised playback: rate changes raise the pitch and stall video, every seek is followed by a ~300 ms hidden clock pause, iOS ignores seeks made before playback starts, and the Web Audio path stutters or goes silent on older devices (iPod touch 7, iOS 15). A WKWebView wrapper would inherit all of that. A native player avoids it:

- **iOS (AVFoundation):** `AVPlayer.setRate(_:time:atHostTime:)` starts at an exact media time at an exact host time, `preroll(atRate:)` and zero-tolerance `seek(to:toleranceBefore:toleranceAfter:)` land exactly, rate nudges keep pitch (`audioTimePitchAlgorithm`), and `AVAudioSession.outputLatency` reports speaker/Bluetooth delay so it can be compensated. Background audio and lock-screen controls come with it.
- **Android (Media3 / ExoPlayer):** exact seeks (`SeekParameters.EXACT`) and pitch-preserving speed changes. Chromium-based browsers on Android already synced flawlessly, so on Android the app is mainly for convenience (background playback, notifications, uploads, offline).

### Sketch

- **Shared UI, native playback.** The screens are the bulk of the work and the same on both platforms, so share them (Kotlin Multiplatform with Compose Multiplatform is the suggested fit, being close to the server's Java; Flutter is the alternative). Playback is a small native module per platform behind one interface: "play this item at this media position at this server time", plus the same clock sync and sync rules as the web client.
- **Same API.** The apps use the existing JSON API, the WebSocket protocol and the chunked resumable uploads, authenticated with bearer tokens (`/api/auth/token`).
- **Offline playlists.** The user marks playlists as available offline; the app downloads their items (by default `audio.m4a` only: small, the same for every item, and what audio-only mode plays; video per playlist as an option) and keeps the playlist in sync. With no connection, it plays from those copies with no server at all.
- **Rooms from local copies.** In a room, the app needs only the timeline over the WebSocket. If the current (or next) item is stored locally and its file version matches, it plays the local copy in sync instead of streaming it, so joining a room costs almost no data.

### When the app ships

- **Drop the web client's iOS 15 syntax baseline** (Siggi, 2026-11-10): once iOS users have the app, the web UI no longer needs to support iOS 15 Safari. Remove the Safari 15.0 limits from CLAUDE.md (Frontend) and the matching checks in `WebBuildTest`, and raise `web/tsconfig.json`'s target. Remind Siggi of this when the app work starts.

### Open questions

- May a device keep playing its copy of an item the server has purged (retention) or the user has lost access to?
- Should offline downloading need its own permission or server switch (it amounts to downloading whole playlists), and how does it relate to `guests.download`?
- Video in offline playlists: per playlist, per item, or never?
- How should the app handle a local copy whose version is outdated: stream the new one at once and replace the copy later on Wi-Fi?

### What current work should keep open

- **File versions:** every media file served from `/media/{id}/…` should have a stable version a client can compare (a content hash in the item data, and a strong `ETag`), changing whenever the bytes change: a profile re-encode, or a purged YouTube item fetched again into the same row. Clients must be able to tell whether a stored copy is current. An outdated copy still plays in sync (all versions share one timeline), so it can be used until replaced. *(Mostly there since M4: `items.media_version` changes with every encode, including a refetch after a purge (it only grows), and `/media/{id}/v/{N}/…` never changes its bytes. An app can store N with its copy; a content hash could still be added.)*
- **Playlist change tracking:** playlists (and libraries) should be syncable incrementally, e.g. a version per playlist or a "changed since" query, so an app need not refetch a 500-entry playlist to learn that nothing changed.
- **Purging is server-side only:** retention deletes the server's files; nothing may assume no copies exist elsewhere. A purged item keeps its id, so a device copy stays linked to it.
- **Bearer-authenticated WebSockets without `Origin`:** keep accepting an upgrade with no `Origin` header and no cookie, authenticated by the first `{type:"auth"}` message ([auth.md](auth.md)); the Origin check exists to protect cookies, which native apps don't send.
- **OIDC / OAuth login for apps (M6):** keep the login flow usable from an app: started in `ASWebAuthenticationSession` (iOS) or Custom Tabs (Android), finished by handing the app a bearer token (e.g. via a registered redirect back into the app and a one-time code exchanged for a token), not only by setting a browser cookie.
- **Item data for local playback:** whatever the web client needs to play a file correctly (e.g. `originalGainDb`, `presentAsAudio`, durations) must stay in the API, so an app can play its own copy the same way.
