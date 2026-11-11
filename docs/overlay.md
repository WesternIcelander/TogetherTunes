# QR overlay

The program output can show a QR code and a short link that lead to the room's request and vote page. Viewers in VRChat scan it with a phone, and the page opens in **remote mode** (see [rooms.md](rooms.md#remote-mode)).

The overlay is **static per room**. It is baked into video frames and never changes while an item plays. It appears only in the program output (RTMP, `.ts`, HLS); web listeners don't see it.

## Permissions

| Level | Control |
|---|---|
| Server | `overlay.enabled` (default off). When it's off, no room can use the overlay |
| Room owner | Room setting `overlay.enabled`, plus position and size. Only available when the server allows it |
| Music videos | Re-encoded with the overlay **only** if the room owner holds `output.overlayVideo` through one of their roles. Otherwise music videos play from the plain mezzanine, with no overlay; cards still show it |

The decision is recorded per queue entry when the overlay job is planned, so a change of role or setting takes effect from the next entry. As implemented (`overlay/OverlayDecisions`): the first `decide(entryId)` (when the program output plans the entry's overlay, as it becomes next or current) evaluates the server switch, the room switch and the owner's roles as they are then and writes the result to `queue_entries.overlay` (`off`, `cards` or `video`; `video` includes cards; migration 0020); every later call, also after a restart, returns the recorded value. The decision says what is allowed: whether an entry plays as a video at all (not presented as audio, not audio-only) is the caller's part, and only then does `video` matter. A disabled owner holds no permissions, so their rooms get `cards` at most.

## The overlay image

`rooms/<roomId>/overlay.png` (RGBA, at the output resolution's scale) is rendered with Java2D. It contains:

- A QR code for `{publicBaseUrl}/r/{slug}` (short path; it redirects to the room page in remote mode). Error correction level **M**, 4-module quiet zone, black on white. Generated with ZXing core (`QRCodeWriter`), or a hand-written encoder.
- A text line **"Requests at {url}"** (the same short URL, without `https://`), in a large, high-contrast font, for people who would rather type it. It goes on the side of the QR code that faces the middle of the frame: **below** the QR code in the bottom corners (`bl`, `br`), **above** it in the top corners (`tl`, `tr`), aligned to the same edge as the QR code (left for `bl`/`tl`, right for `br`/`tr`). The text is part of the overlay image and of `overlayHash`.
- A rounded, semi-opaque dark **backdrop behind the text only**, hugging it (padding and corner radius scaled with the text size), so the text stays readable on any video. The QR code's white square (with its quiet zone) stands on its own, with no panel around it, and a small transparent gap separates it from the backdrop, so nothing darkens the picture where there is no content (Siggi, 2026-11-11; the first version had one panel behind both, which darkened a large empty area).

Room settings:

- `overlay.corner`: `br` (default), `bl`, `tr` or `tl`.
- `overlay.heightPct`: default 22% of the frame height (5–50). It is the side of the QR code's white square, quiet zone included, rounded down to whole pixels per module (at least 2, so the smallest setting still survives scaling and compression). A VRChat screen is often viewed small and at an angle, so the QR code must be big. The text line is sized from the QR code (16% of its side, at least 3% of the frame height) and shrunk to fit the frame width if the URL is long; it is never cut.
- `overlay.margin`: default 3% of the frame height (0–20): the distance of the overlay's outer edges (the QR square's and the text backdrop's) from the two frame edges of its corner.

`overlay.png` is one ARGB image holding the QR square and the text with its backdrop, side by side as described, transparent everywhere else (around the QR square, in the gap, outside the backdrop's rounded corners); its position in the frame (even `x`, `y`, and even width and height, for 4:2:0 chroma) is stored next to it in `rooms/<roomId>/overlay.json`, together with the spec it was rendered from (render version, frame size, corner, size, margin, URL) and its `overlayHash`. Cards draw the image at that position, and the music-video encode uses it as `overlay=<x>:<y>`. Code: `overlay/OverlayRenderer` (drawing, hash) and `overlay/Overlays` (the stored images).

Test that the code scans from a phone pointed at the stream on a screen at a typical viewing distance before choosing the defaults. Any player works for that (e.g. VLC playing the `.ts` or HLS URL); it doesn't have to be VRChat.

The image is regenerated when the slug, the overlay settings or `publicBaseUrl` change (also the output profile, or a new `OverlayRenderer.RENDER_VERSION` when the drawing code changes): whoever needs the overlay asks `Overlays.current(room)`, which compares the stored spec with the current one and re-renders on a mismatch (so a new `publicBaseUrl` after a restart takes effect on first use), and a room edit that changes the slug or overlay settings re-renders at once while the overlay is on. Its content hash (`overlayHash`: SHA-256 over the frame size, position and ARGB pixels, 16 hex digits) is part of every cache key below, so stale renders are never used; settings that produce the same pixels keep the same hash and caches.

## Room UI

As implemented (web `roommanage.ts` + `roomsettings.ts`, server `overlay/OverlayPreviewApi`): the overlay settings are in the management view's **Settings** tab, group "QR overlay" (greyed out while the server's `overlay.enabled` is off, like every server-gated setting, see the management view in [rooms.md](rooms.md#room-settings-settings_json)). While the server allows the overlay, the group also shows:

- A note that the QR code leads to the room's request page and shows only in the stream outputs (RTMP, `.ts`, HLS), not to people listening on the room page.
- Whether music videos get it: `GET /api/rooms/{id}/server-switches` returns `overlay: {enabled, videoOverlay}`, `videoOverlay` = the server switch is on and the **room owner** holds `output.overlayVideo` (the same check as `OverlayDecisions`). Otherwise the note says music videos play without it while cards still show it.
- A **preview**: `GET /api/rooms/{id}/overlay/preview.png?corner=&heightPct=&margin=` (owner and moderators) renders the room's idle card (with its name) at the output size with the overlay drawn on it as the stream shows it (`Overlays.preview`, nothing stored or cached, so it works with the overlay off). Each parameter is optional and replaces the saved setting, so the page shows unsaved form values (refreshed 400 ms after corner, size or margin change; an invalid value is left out and the saved one used); the QR code uses the saved link name. Values are checked like the room settings (`400 invalid_setting`).

The room `state` doesn't carry an `overlay` object (the [api.md](api.md) sketch had `overlay: {enabled, videoOverlay}`): nothing on the room page uses it, and a server switch or role change wouldn't be pushed to the sockets anyway; the management view reads it from `server-switches`.

## Cards (cheap path)

Cards are still images, so adding the overlay just means compositing `overlay.png` onto the card image in Java2D, then encoding one 2 s GOP as usual. That takes about 0.1–0.3 s of CPU per card.

- **Per-room filler cards** (paused, idle, loading): rendered when the overlay is enabled or the room changes, and cached in `rooms/<roomId>/cards/`.
- **Item cards** (audio items, and video items presented as audio): rendered when an entry becomes **current or next**. Key: `(itemId, overlayHash, profile)`.
- These are allowed whenever the room's overlay is on. No `output.overlayVideo` is needed, because the cost is negligible.

The card cache is evicted by LRU, together with the music-video overlay cache.

As implemented (`overlay/OverlayCards`, used by `output/ProgramMedia`):

- The card image (`CardRenderer`: the server filler render, or the item card rendered from the item's title, artist, album and `cover.jpg` of its current media version) gets the overlay panel drawn at its `overlay.json` position, and is encoded with `CardEncoder.encode` (a skip-frame card, avcC checked against the reference). Files: `rooms/<roomId>/cards/filler-<id>-<overlayHash>-<encoding>-c<cardVersion>.mp4` and `item-<itemId>-v<mediaVersion>-<overlayHash>-<encoding>-c<cardVersion>.mp4` (the encoding id names the profile and bitrate). A `.key` fingerprint next to each (card format, encoder arguments, reference avcC, composited pixels) re-encodes a card whose picture changed under the same name (an edited title or cover) and reuses an unchanged one across restarts.
- **Filler cards** follow the room as it is now (server and room `overlay.enabled`); the output looks them up when the cue changes (at most once a second) and every 5 s while a filler plays. **Item cards** follow the entry's recorded decision (`cards` or `video`), asked as the entry becomes current or next (or when it starts playing).
- Everything is made in the background. The packager never waits for an overlaid card: a source shows the plain card until the overlaid one is loaded and switches at the next 2 s GOP boundary (`CardGop.repeat(Supplier)`; same avcC, closed GOPs), so the overlay can appear up to 2 s into an item or filler. A failed card is logged, the plain card plays, and it is retried after 5 s.
- Every card made or handed out is counted in the overlay cache (`overlay/OverlayCache`, see [Scheduling and caching](#scheduling-and-caching)): least recently used ones are evicted beyond `overlay.cacheMaxBytes`, cards of another overlayHash go as soon as the room's hash changes, an item's go when it is purged. Evicting one is always safe (outputs keep loaded cards in memory; one asked for again is made again).

## Music videos (re-encode path)

A music video's frames are different from each other, so the overlay can only be added by decoding, compositing and re-encoding the video track. Audio is never re-encoded; the packager keeps taking it from the item's original samples.

### The encode

```sh
ffmpeg -ss <g0*2> -i media/<itemId>/mezz.mp4 -i rooms/<roomId>/overlay.png \
  -filter_complex "[0:v][1:v]overlay=<x>:<y>[v]" -map "[v]" -fps_mode passthrough \
  <PROFILE_VIDEO_ARGS> -an -f h264 pipe:1
```

- `PROFILE_VIDEO_ARGS` is the same constant used for mezzanines (with the quick preset, veryfast by default, so overlays stay faster than real time; every preset gives the same avcC, [library-and-ingest.md](library-and-ingest.md#presets); no B-frames, 60-frame closed GOPs, forced keyframes, bitrate capped at `output.videoBitrateKbps` like every encode, unpadded; the packager pads the output to its constant rate while sending, [program-output.md](program-output.md#constant-rate-padding)). Because the input is the mezzanine itself, frame *i* of the output corresponds exactly to frame *i* of the original. Keyframes therefore land on the same frames, and the extradata is identical. This was verified: a veryfast overlay encode had byte-identical SPS/PPS to the plain mezzanine, with 300 frames and 5 IDRs for 10 s.
- `g0` is the GOP index to start from: 0 when the job starts before the item plays, or `floor(position / 2 s)` when it starts mid-item. Input seeking to an exact keyframe time makes the first output frame frame `60·g0`. A mezzanine with a short first GOP (a smart trim's, *f* < 60 frames; invariant 2) has its keyframes at frames 0 and *f* + 60·*k*, not 60·*g*: the GOP positions come from its index (or `firstGopFrames`), and the overlay encode must use the same first-GOP keyframe expression (`EncodeProfile.videoArgs(…, firstGopFrames)`) when it starts at frame 0, so its keyframes land on the mezzanine's.
- **Speed:** about 2.9× real time for 1080p30 on 2 cores (roughly 0.7 cores of CPU per second of video), and much less at 720p.

### Reading the output (Java)

1. Read the Annex-B stream from ffmpeg's stdout. Split it into NAL units on start codes.
2. Group the NALs into access units. A new access unit starts at a slice NAL (type 1 or 5) whose `first_mb_in_slice` is 0, which is when the first bit of the slice header is `1`. x264 encodes one slice per frame by default.
3. Check that the first SPS/PPS are byte-identical to the profile reference. If they aren't, abort the job and use the plain video.
4. Drop SPS, PPS, AUD and SEI NALs. The packager inserts its own AUD, and its own SPS/PPS before every IDR.
5. Append each access unit to `rooms/<roomId>/ov/<itemId>/gops.h264`, and an entry `(frameIndex, offset, size, isIdr)` to `gops.idx`.
6. Mark GOP *g* **complete** once its 60 frames have been written (or the stream has ended), and publish that to the packager through an in-memory `BitSet` per job.

As implemented (`overlay/OverlayVideoEncoder`, `overlay/OverlayGops`, `media/AnnexBReader`, `media/AccessUnitGrouper`):

- **Seeking:** for `g0 > 0` the command starts with `-seek_timestamp 1 -ss <t>us`, where `t` is the GOP's keyframe time from the item's index (`pts`, so any edit-list shift is already in it), rounded down to the microsecond: ffmpeg drops the decoded frames before `t`, and the previous frame is a whole frame earlier. The filter chain ends with `EncodeProfile.COLOUR_FILTER` like every chain feeding x264 (invariant 1). The keyframe expression is the mezzanine's own first-GOP length from frame 0, the normal 2 s one from a later GOP (the output's time starts at 0 there).
- **The GOPs are the mezzanine's:** GOP *g* runs from its *g*-th keyframe in the index to the next (a smart trim's short first GOP is GOP 0), and frame numbers are the mezzanine's sample numbers. Output frame *i* must be mezzanine frame `start + i`, an IDR exactly where the mezzanine has a keyframe, and the count must match to the end; any difference (or an SPS/PPS that isn't the reference's, or none before the first frame) aborts the job before the affected GOP is published, and the item plays without the overlay.
- **`gops.h264` holds AVCC samples**, not Annex B: each frame's remaining NAL units with length prefixes of the reference avcC's size, exactly like a `mezz.mp4` sample, so the packager substitutes a frame one for one (its muxer adds AUD, SPS and PPS as for any sample). `gops.idx` is a 12-byte header (`TTOV`, version, the mezzanine's frame count) and a 17-byte big-endian entry per frame (frame, offset, size, flags with 1 = IDR); a frame's bytes are written before its entry, and entries are flushed when a GOP completes.
- **`OverlayGops`** is the job's state for the packager: `isComplete(g)` (the `BitSet`), and each frame's offset and size, stored before its GOP is published. `OverlayGops.load` reads a finished encode back (complete GOPs only; it stops at a gap, a wrong IDR flag, bytes missing from `gops.h264` or a torn last entry).
- ffmpeg's stdout is read on the tool's reader thread (`ToolCall.stdoutStream`); cancelling the job interrupts the worker, which stops ffmpeg.

### Packager substitution

For each GOP of a video item, the packager uses the overlay GOP if it's complete, and the original GOP otherwise. Both have identical SPS, closed GOPs and the same frame timing, so switching at GOP boundaries is seamless. In practice:

- Job started when the entry became **next**: usually the whole item is ready before it plays.
- Job started **mid-item** (a skip, a seek, or the next entry changed late): the first GOP or two play without the overlay, then the overlay appears and stays, because the encoder outruns playback.

As implemented (`output/VideoItemSource`, `output/VideoOverlay`, `overlay/OverlayGops.gop`):

- The source asks for the room's re-encode at **every keyframe** of the mezzanine and plays that whole GOP from it if the GOP is complete (`OverlayGops.gop(keyframe)` returns its frames, else null), the original GOP otherwise. Switching never happens inside a GOP. PTS come from the mezzanine's index either way; audio is always the item's.
- An overlay GOP is read whole at its start (one GOP of `gops.h264`), so a read failure falls back to the original GOP cleanly. Each overlay GOP is checked: the re-encode's frame count must be the mezzanine's, the GOP must have as many frames as the mezzanine's GOP, with an IDR first and nowhere else. Any failure (or a read error) drops the overlay for the rest of that play (logged once) and the original plays.
- `ProgramMedia` gets the re-encode from the room's `OverlayCards.video(entry, itemDir, index)` when an entry played as video starts playing (on the packager thread, so it must not block): a supplier that gives the job's `OverlayGops` once there is one. The version directory and index are those the play reads, so the job is keyed on the media version.

### Scheduling and caching

- Queue a job when an entry becomes **current** (high priority) or **next** (normal priority), if the conditions above hold. In `vote` mode, "next" can change; cancel the job for an entry that stops being next, unless it's more than 50% done.
- Pool size `overlay.workers` (default 1). If the pool is busy, the item plays without the overlay rather than waiting. As implemented (`OverlayPool`): jobs in memory only, keyed (one job per key), high before normal priority, no preemption of running jobs; cancel interrupts a running job; `busy()` counts running plus queued jobs against the worker count; the admin setting resizes the pool at once.
- Cache key: `(roomId, itemId, overlayHash, profile)`. Completed encodes are kept, because songs repeat. They're evicted by LRU when `overlay.cacheMaxBytes` is exceeded, and deleted when the item is purged, the room is deleted, or `overlayHash` changes.
- On restart, partial encodes are discarded and recreated as needed.

As implemented (`overlay/OverlayVideoJobs`, one per server; each program output gets a session through `OverlayCards.forRoom`, and `output/ProgramMedia` feeds it):

- **Next (normal priority):** `ProgramMedia` hands the cue's next entry to the session once its files are loaded (`videoNext`, at every cue and after the load), if it plays as video. The job starts at GOP 0.
- **Current (high priority):** when an entry starts playing as video, its source gets a `VideoOverlay` from the session; at the first GOP it asks for, the play's start is known: a play from GOP 0 wants an encode from GOP 0, a play from GOP *g* > 0 one from *g* + 1 (GOP *g* is already being sent, so encoding it would be wasted). An existing encode is shared when it started at or before that GOP and is finished or at most 2 GOPs behind it (it outruns playback); a queued one is raised to high priority (`OverlayPool.raise`). So an entry that was next normally just keeps its job, now at high priority.
- **Decision:** only entries whose recorded decision (`OverlayDecisions.decide`) is `video` get a job. The decision, the room and its overlay image are looked up once per entry, off the packager thread; a failed lookup is retried after 5 s.
- **No longer wanted:** `ProgramMedia` reports the entries it still wants (the cue's current and next, and the one playing; `videoWanted`, sent when that set changes, and empty when the output closes). A job no output wants any more is cancelled unless its progress (frames written from its start GOP) is more than 50%; then it runs to the end and is kept.
- **Busy pool:** if `OverlayPool.busy()`, nothing is queued and the item plays without the overlay. The wants without a job try again whenever a job ends, at every cue (next), and every 5 s at a GOP start (the playing entry, then from the GOP after the one playing), so the overlay appears later when a worker frees up. Running jobs are never pre-empted.
- **Preset:** chosen when the job starts, from the priority it has then: `ingest.quickPreset`, except a normal-priority job (the next entry) uses `ingest.qualityPreset` when the admin setting `overlay.allowQualityPreset` is on (default off). A quality job raised to high priority after it started keeps its preset; if it falls behind playback, the original GOPs play until the overlay GOPs are complete (no glitch), and once it is more than 2 GOPs behind the playing GOP, the play's retry starts a quick job from the next GOP if a worker is free.
- **Reading:** the `VideoOverlay` gives a GOP from any encode of the same room, item, media version, overlay hash and encoding in which that GOP is complete (else null, and the original GOP plays); it never waits.
- **Failures:** an encode whose output doesn't match the reference or the mezzanine (`MismatchException`) isn't tried again for that item, version, overlay and encoding while the server runs; any other failure is retried after 5 s.
- **Files:** each job writes to its own directory `rooms/<roomId>/ov/<itemId>/<version>-<overlayHash>-<encoding>-g<g0>/` (`gops.h264`, `gops.idx` and the `overlay.png` it was made with, written from the rendered panel so a re-render meanwhile can't mix up hash and pixels). A new job for a directory only starts after the previous one there has ended and is no longer read. A finished encode gets a `done` marker file; a cancelled or failed one's directory is deleted when its job ends. Finished encodes stay in memory for reuse (the last 64) and on disk: when an entry is planned, the finished encodes of its item, version, overlay and encoding from earlier runs are read back (`OverlayGops.load`; one that can't be read back completely is deleted and redone).
- **Cache** (`overlay/OverlayCache`, one per server): counts the finished re-encode directories and the room cards (`rooms/*/cards/`) together against `overlay.cacheMaxBytes` (admin setting, re-read at every trim; a save trims at once).
  - **LRU:** the last use (a GOP played from an encode, a card handed to an output) is kept in memory and written to disk at most once a minute as the modification time of the `done` marker or the card, so the order survives a restart. A trim (after every new encode or card, when an entry stops being wanted, after a settings save) evicts least recently used first until the total fits, skipping entries used in the last 60 s (a card just made is about to be loaded) and re-encodes **in use** (running, or assigned to an entry that is current, next or playing: `OverlayVideoJobs.evict` refuses them). With 0 nothing is kept beyond that.
  - **overlayHash change:** `Overlays` tells the cache a room's hash when it becomes known in a run or changes; entries of that room with another hash are stale and deleted at the next trim whatever the size, as soon as nothing uses them (also one an old-hash job finishes later).
  - **Purge:** `Items.purge` → `OverlayVideoJobs.stopItem` (cancels and waits for the item's jobs, forgets its encodes) → the cache deletes the item's re-encodes and `item-<itemId>-…` cards in every room.
  - **Room delete:** `DELETE /api/rooms/{room}` → (off the request thread) `stopRoom` (cancels its jobs, no new ones), `Overlays.forget`, then `rooms/<roomId>/` is deleted.
  - **Restart:** before the outputs start, the scan deletes re-encode directories without the `done` marker (partial: the server stopped mid-encode), dot-prefixed temporary files, cards without a fingerprint (it is written last) and fingerprints without a card, and the directories of rooms that no longer exist; the rest is counted and trimmed.
