# QR overlay

The program output can show a QR code and a short link that lead to the room's request and vote page. Viewers in VRChat scan it with a phone, and the page opens in **remote mode** (see [rooms.md](rooms.md#remote-mode)).

The overlay is **static per room**. It is baked into video frames and never changes while an item plays. It appears only in the program output (RTMP, `.ts`, HLS); web listeners don't see it.

## Permissions

| Level | Control |
|---|---|
| Server | `overlay.enabled` (default off). When it's off, no room can use the overlay |
| Room owner | Room setting `overlay.enabled`, plus position and size. Only available when the server allows it |
| Music videos | Re-encoded with the overlay **only** if the room owner holds `output.overlayVideo` through one of their roles. Otherwise music videos play from the plain mezzanine, with no overlay; cards still show it |

The decision is recorded per queue entry when the overlay job is planned, so a change of role or setting takes effect from the next entry.

## The overlay image

`rooms/<roomId>/overlay.png` (RGBA, at the output resolution's scale) is rendered with Java2D. It contains:

- A QR code for `{publicBaseUrl}/r/{slug}` (short path; it redirects to the room page in remote mode). Error correction level **M**, 4-module quiet zone, black on white. Generated with ZXing core (`QRCodeWriter`), or a hand-written encoder.
- A text line **"Requests at {url}"** (the same short URL, without `https://`), in a large, high-contrast font, for people who would rather type it. It goes on the side of the QR code that faces the middle of the frame: **below** the QR code in the bottom corners (`bl`, `br`), **above** it in the top corners (`tl`, `tr`), aligned to the same edge as the QR code (left for `bl`/`tl`, right for `br`/`tr`). The text is part of the overlay image and of `overlayHash`.
- A rounded, semi-opaque dark panel behind the QR code and the text, so the overlay stays readable on any video.

Room settings:

- `overlay.corner`: `br` (default), `bl`, `tr` or `tl`.
- `overlay.heightPct`: default 22% of the frame height. A VRChat screen is often viewed small and at an angle, so the QR code must be big.
- `overlay.margin`.

Test that the code scans from a phone pointed at the stream on a screen at a typical viewing distance before choosing the defaults. Any player works for that (e.g. VLC playing the `.ts` or HLS URL); it doesn't have to be VRChat.

The image is regenerated when the slug, the overlay settings or `publicBaseUrl` change. Its content hash (`overlayHash`) is part of every cache key below, so stale renders are never used.

## Cards (cheap path)

Cards are still images, so adding the overlay just means compositing `overlay.png` onto the card image in Java2D, then encoding one 2 s GOP as usual. That takes about 0.1–0.3 s of CPU per card.

- **Per-room filler cards** (paused, idle, loading): rendered when the overlay is enabled or the room changes, and cached in `rooms/<roomId>/cards/`.
- **Item cards** (audio items, and video items presented as audio): rendered when an entry becomes **current or next**. Key: `(itemId, overlayHash, profile)`.
- These are allowed whenever the room's overlay is on. No `output.overlayVideo` is needed, because the cost is negligible.

The card cache is evicted by LRU, together with the music-video overlay cache.

## Music videos (re-encode path)

A music video's frames are different from each other, so the overlay can only be added by decoding, compositing and re-encoding the video track. Audio is never re-encoded; the packager keeps taking it from the item's original samples.

### The encode

```sh
ffmpeg -ss <g0*2> -i media/<itemId>/mezz.mp4 -i rooms/<roomId>/overlay.png \
  -filter_complex "[0:v][1:v]overlay=<x>:<y>[v]" -map "[v]" -fps_mode passthrough \
  <PROFILE_VIDEO_ARGS> -an -f h264 pipe:1
```

- `PROFILE_VIDEO_ARGS` is the same constant used for mezzanines (with the quick preset, veryfast by default, so overlays stay faster than real time; every preset gives the same avcC, [library-and-ingest.md](library-and-ingest.md#presets); no B-frames, 60-frame closed GOPs, forced keyframes, CBR at `output.videoBitrateKbps` with filler NAL padding, so overlaid items and cards keep the output's constant bitrate). Because the input is the mezzanine itself, frame *i* of the output corresponds exactly to frame *i* of the original. Keyframes therefore land on the same frames, and the extradata is identical. This was verified: a veryfast overlay encode had byte-identical SPS/PPS to the plain mezzanine, with 300 frames and 5 IDRs for 10 s.
- `g0` is the GOP index to start from: 0 when the job starts before the item plays, or `floor(position / 2 s)` when it starts mid-item. Input seeking to an exact keyframe time makes the first output frame frame `60·g0`.
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
- Pool size `overlay.workers` (default 1). If the pool is busy, the item plays without the overlay rather than waiting.
- Cache key: `(roomId, itemId, overlayHash, profile)`. Completed encodes are kept, because songs repeat. They're evicted by LRU when `overlay.cacheMaxBytes` is exceeded, and deleted when the item is purged, the room is deleted, or `overlayHash` changes.
- On restart, partial encodes are discarded and recreated as needed.
