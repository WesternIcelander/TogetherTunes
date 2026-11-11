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
- A rounded, semi-opaque dark panel behind the QR code and the text, so the overlay stays readable on any video.

Room settings:

- `overlay.corner`: `br` (default), `bl`, `tr` or `tl`.
- `overlay.heightPct`: default 22% of the frame height (5–50). It is the side of the QR code's white square, quiet zone included, rounded down to whole pixels per module (at least 2, so the smallest setting still survives scaling and compression). A VRChat screen is often viewed small and at an angle, so the QR code must be big. The text line is sized from the QR code (16% of its side, at least 3% of the frame height) and shrunk to fit the frame width if the URL is long; it is never cut.
- `overlay.margin`: default 3% of the frame height (0–20): the panel's distance from the two frame edges of its corner.

`overlay.png` holds only the panel (transparent outside its rounded corners); its position in the frame (even `x`, `y`, and even width and height, for 4:2:0 chroma) is stored next to it in `rooms/<roomId>/overlay.json`, together with the spec it was rendered from (render version, frame size, corner, size, margin, URL) and its `overlayHash`. Cards draw the panel at that position, and the music-video encode uses it as `overlay=<x>:<y>`. Code: `overlay/OverlayRenderer` (drawing, hash) and `overlay/Overlays` (the stored images).

Test that the code scans from a phone pointed at the stream on a screen at a typical viewing distance before choosing the defaults. Any player works for that (e.g. VLC playing the `.ts` or HLS URL); it doesn't have to be VRChat.

The image is regenerated when the slug, the overlay settings or `publicBaseUrl` change (also the output profile, or a new `OverlayRenderer.RENDER_VERSION` when the drawing code changes): whoever needs the overlay asks `Overlays.current(room)`, which compares the stored spec with the current one and re-renders on a mismatch (so a new `publicBaseUrl` after a restart takes effect on first use), and a room edit that changes the slug or overlay settings re-renders at once while the overlay is on. Its content hash (`overlayHash`: SHA-256 over the frame size, position and ARGB pixels, 16 hex digits) is part of every cache key below, so stale renders are never used; settings that produce the same pixels keep the same hash and caches.

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
- Old cards (another overlayHash or media version) stay on disk until the LRU cache step's clean-up.

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

### Packager substitution

For each GOP of a video item, the packager uses the overlay GOP if it's complete, and the original GOP otherwise. Both have identical SPS, closed GOPs and the same frame timing, so switching at GOP boundaries is seamless. In practice:

- Job started when the entry became **next**: usually the whole item is ready before it plays.
- Job started **mid-item** (a skip, a seek, or the next entry changed late): the first GOP or two play without the overlay, then the overlay appears and stays, because the encoder outruns playback.

### Scheduling and caching

- Queue a job when an entry becomes **current** (high priority) or **next** (normal priority), if the conditions above hold. In `vote` mode, "next" can change; cancel the job for an entry that stops being next, unless it's more than 50% done.
- Pool size `overlay.workers` (default 1). If the pool is busy, the item plays without the overlay rather than waiting. As implemented (`OverlayPool`): jobs in memory only, keyed (one job per key), high before normal priority, no preemption of running jobs; cancel interrupts a running job; `busy()` counts running plus queued jobs against the worker count; the admin setting resizes the pool at once.
- Cache key: `(roomId, itemId, overlayHash, profile)`. Completed encodes are kept, because songs repeat. They're evicted by LRU when `overlay.cacheMaxBytes` is exceeded, and deleted when the item is purged, the room is deleted, or `overlayHash` changes.
- On restart, partial encodes are discarded and recreated as needed.
