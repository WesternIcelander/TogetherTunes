# Program output (VRChat stream)

A room owner can enable program output: one continuous H.264/AAC stream of the room. It is produced by a **packager** that only remuxes. It reads pre-encoded samples from the media store, rewrites their timestamps, wraps them in MPEG-TS, and fans the bytes out. The packager itself never decodes or encodes. The optional QR overlay for music videos is encoded separately, ahead of playback, and the packager simply uses those GOPs (see [overlay.md](overlay.md)).

## Why remuxing works

Every item and card shares the exact same codec configuration, and every GOP is closed (see [library-and-ingest.md](library-and-ingest.md#the-mezzanine-profile)). So the packager can splice any GOP after any other GOP. The only thing it has to fix is **timestamps**, which must keep increasing smoothly across the whole stream. Simply repeating or concatenating files unchanged would make them jump back to 0 at every splice. The packager never uses discontinuity signalling (TS discontinuity flag, `EXT-X-DISCONTINUITY`), because players handle continuous timestamps more reliably.

## Units

The packager emits the stream as a sequence of **units**. A unit is about 2 s of output that starts with an IDR frame:

```
Unit {
  outStartPts90k                    // 90 kHz output clock
  videoSamples: [(bytes, pts90k, isIdr)]   // 1..60 frames, first is IDR
  audioFrames:  [(bytes, pts90k)]          // AAC frames with pts in [unit start, next unit start)
}
```

Units are built by **sources**:

| Source | Video | Audio |
|---|---|---|
| `VideoItemSource(item, startMs)` | The item's mezzanine GOPs, starting at the keyframe ≤ `startMs`. Each GOP is replaced by the room's overlay GOP when one is complete ([overlay.md](overlay.md#packager-substitution)) | The item's audio from the same media time |
| `AudioItemSource(item, startMs)` | The item's card GOP, repeated. Uses the room's overlay card when the overlay is on. Also used for video items with `presentAsAudio` | The item's audio from the AAC frame at `startMs` |
| `FillerSource(card)` | The card GOP, repeated (the room's overlay version when the overlay is on) | The silence frame, repeated |

Fillers: **Paused** (timeline paused), **Idle** (room idle card; queue empty), **Loading** (next item's index not loaded yet; this should be rare, because the next item is preloaded during the current one).

## Timestamp rules

All output timestamps use the 90 kHz MPEG-TS clock.

- **Video frame duration** = 3000 ticks (30 fps). **AAC frame duration** = 1024/48000 s = **1920 ticks**, exactly.
- **Audio drives item boundaries.** Item *n* starts at `S_n`. Its audio frame *k* (counted from the start position) gets `pts = S_n + k·1920`. The item ends at `E_n = S_n + K·1920`, where *K* is the number of audio frames emitted, and `S_{n+1} = E_n`. The audio therefore has no gaps and no overlaps, at any splice.
- **Video follows audio.** For a video item, frame pts = `S_n + (srcPts − srcStartPts)·90000/timescale`. Here `srcStartPts` is the media time the item starts from: the keyframe's time, and the audio starts at the same media time. Video frames with `pts ≥ E_n` are dropped. If the video is shorter than the audio, the last frame stays on screen until the next IDR.
- **Cards** are emitted as repeated 60-frame GOPs starting at `S_n`. The last GOP is truncated at `E_n`. The next item always begins with a fresh IDR at `S_{n+1}`, so a truncated GOP is harmless.
- **Fillers** last a whole number of 2 s units, and their silence frames continue the 1920-tick cadence.
- The output clock starts at 10 s (900000 ticks), so muxer offsets never go negative. Wrap at 2³³ (about 26.5 h): mask PTS, DTS and PCR to 33 bits; players handle the wrap.
- No B-frames, so **DTS = PTS** for every sample, and PES headers carry PTS only.
- Small A/V offset at a splice: within a video item, A/V sync comes from the original timestamps. At a splice, the video jumps to the next IDR at `S_{n+1}`, and any leftover gap is at most one frame (33 ms) of the previous picture held on screen.

**AAC splice artifact:** AAC frames overlap their neighbours (MDCT). When the decoder moves straight from item A's last frame to item B's first frame, there may be a very short click. This is accepted in v1. Fixes, if it turns out to be audible: start every item from a short fade-in encoded at ingest, or splice only during the transition card's silence (`gapMs > 0`).

## Applying timeline changes

The packager subscribes to the room's timeline. Changes are **applied at the next unit boundary**:

- When building each unit, the packager checks: "Is the source I'm playing still what the timeline says should be playing at this point?" If not, it switches source:
  - new entry, or seek → `VideoItemSource` / `AudioItemSource` at the timeline's current position;
  - `PAUSED` → `FillerSource(Paused)`; `IDLE` → `FillerSource(Idle)`. A state the packager doesn't recognise is treated as `PAUSED`.
- When an item ends naturally, `E_n` usually falls mid-unit. The packager ends the unit there and starts the next unit (the next item, or a filler) at `E_n`. Units can therefore be shorter than 2 s, but never longer, apart from one detail: 2 s is 93.75 audio frames, so a unit's last audio frame usually straddles its 2 s mark. When the next unit comes from a new source, that source starts where the emitted audio ends (`S_{n+1} = E_n`), so the unit lasts up to 1919 ticks (21.3 ms) more than 2 s. No picture may last less than one frame interval at a splice (ffmpeg and frame-rate-based players choke on two frames within 1/30 s): video less than 3000 ticks before `E_n` is dropped (the unit's IDR always stays); a source whose audio would end less than 3000 ticks after its next IDR ends in the unit before instead (that unit then lasts up to 3000 + 1919 ticks, 54.6 ms, more than 2 s); and a source with less than 3000 ticks of audio in all (e.g. a seek to the very end) gives no unit. Found by the offline harness (2026-11-10).
- If the room hasn't advanced yet when the output's item ends, the packager starts the room's next entry from 0 at `E_n` by itself (or the Idle filler if there is none), and adopts the room's advance when it arrives. If the room advances first (the output is a little behind), the output still plays its item to its end: the room tells it the previous entry was played out (not skipped). Implemented in `output/TimelineFollower` with `ProgramCue`.
- **Natural drift:** the program output's position can drift slightly from the timeline (from GOP-aligned seeks and pacing jitter). The packager does not try to correct this mid-item. The next item starts when the previous item's audio actually ends in the output. If the output is more than 3 s behind or ahead of the timeline at an item boundary, the packager re-syncs by starting the next item at the timeline's current position for it. When the output is ahead (its item ended while the timeline still has more than 3 s of it), it plays the Loading filler until the timeline moves on.

## Pacing

The packager thread keeps the output roughly real time:

```
wallAtOutStart ↔ outStartPts mapping fixed when the packager starts
for each unit: wait until wallNow ≥ wallFor(unit.outStartPts) − LEAD (LEAD = 1000 ms), then emit it
```

The output is never more than about 1 unit plus LEAD ahead of real time, so a seek or skip shows up in VRChat within about 2–4 s, plus the player's own buffering.

As implemented (`output/Packager`, `PacingClock`): the thread waits for the *next* unit's start (`nextUnitStart()`) minus LEAD **before** building it, so timeline changes are picked up as late as possible. Pacing uses a monotonic clock; the server-time mapping (`serverMsAt`, used by the timeline follower) is fixed at the same moment. If the thread falls more than 4 s behind (a stall), the mapping moves forward by the lag instead of bursting the missed output; output timestamps are unaffected. Each consumer is either a non-blocking sink (HLS ring) or a `ConsumerQueue` of 4 units (`.ts`, RTMP) that is closed, with its close action ending the connection, when a unit arrives while it is full. A failing build or mux stops the packager and closes its consumers.

## MPEG-TS muxer

Write it in-house; it's small.

- **PIDs** (same as VRCDN): PAT 0x0000, SDT 0x0011, PMT 0x0100, video 0x0101 (`stream_type 0x1B`, H.264), audio 0x0102 (`stream_type 0x0F`, AAC ADTS). PCR is carried on the video PID.
- **SDT, PAT and PMT** are written at the start of every unit, and PAT and PMT are repeated about every 400 ms within a unit (VRCDN repeats them at roughly that interval).
- **Each unit starts with** SDT, PAT, then PMT, then the IDR access unit with `random_access_indicator=1` in the adaptation field. Every unit is therefore a valid place to start, both for `.ts` joins and for HLS segments.
- **Video access unit → PES:** convert AVCC to Annex B. Each NAL has a length prefix (`lengthSizeMinusOne+1` bytes from avcC); replace it with `00 00 00 01`. Prepend an **AUD** NAL (`00 00 00 01 09 F0`) to every access unit. Before every IDR, insert SPS and PPS from avcC. Use one PES per access unit, with `PES_packet_length = 0` (unbounded, allowed for video).
- **Audio → PES:** wrap each AAC frame in a 7-byte **ADTS** header: `syncword 0xFFF, ID=0, layer=0, protection_absent=1, profile=1 (LC), sf_index=3 (48 kHz), channel_config=2, frame_length=7+len, buffer_fullness=0x7FF, raw_blocks=0`. Group about 5 frames (about 107 ms) into one PES, with PTS = the first frame's PTS.
- **Interleave** video and audio PES in DTS order within a unit.
- **PCR:** written in the adaptation field of every video PES start (every 33 ms, under the 100 ms maximum the spec allows). `PCR = DTS − 63000` (700 ms mux delay), base × 300 + extension (extension 0).
- **Continuity counters:** one 4-bit counter per PID, continuous for the **lifetime of the packager**. Every consumer sees one coherent byte stream; a joining client simply starts at a unit boundary.
- Pad the last TS packet of each PES with adaptation-field stuffing; packets are always 188 bytes.

Each unit's TS bytes are produced **once** into an immutable `byte[]`, and that same array is shared by every consumer.

## Consumers

All three outputs are equal features. Each one is enabled server-wide by the admin (`outputs.*.enabled`) and per room by the owner. They are all consumers of the same unit byte stream, so running all three costs barely more than running one.

### Direct MPEG-TS (`.ts`)

`GET /live/{token}.ts` (also `HEAD`):

- Response `200`, `Content-Type: video/mp2t`, `Cache-Control: no-store`, with **no `Content-Length` and no `Transfer-Encoding`**: the body ends when the connection closes. This is what VRCDN does (see below).
- Ignore `Range` and always return `200` from the live edge. `HEAD` returns `200` with `Content-Length: 0` and no body, like VRCDN; it doesn't start the packager or count as a viewer.
- **Join point:** by default, the client receives data from the **next** unit boundary (`join=next`). This keeps every VRChat viewer at the same live edge, so they stay close in sync. The alternative, `join=latest` (send the most recent unit immediately, so playback starts faster), is a room setting. It costs up to 2 s of extra, permanent lag for that viewer.
- **Per-consumer bounded queue:** 4 units (about 8 s). If it overflows, the client is disconnected; it will usually reconnect.
- **Reference — VRCDN's `.ts` output** (observed 2026-11-08 on `stream.vrcdn.live/live/<name>.live.ts`): HTTP/1.1 `200`, `Content-Type: video/mp2t; charset=utf-8`, no `Content-Length`, no `Transfer-Encoding` (close-delimited, despite a `Connection: keep-alive` header). `HEAD` → `200`, `Content-Length: 0`. The body starts with SDT, PAT and PMT, then continues from the current live position **mid-GOP** (no burst, no waiting for a keyframe); data then arrives at the stream's real-time rate. PIDs as above; PAT/PMT about every 0.4 s; PCR on the video PID. We copy all of this except the join point: `join=next` starts a viewer at the next unit boundary instead of mid-GOP. The player can't show anything before that IDR either way, so the viewer sees the same picture at the same time, without the undecodable lead-in.
- **Before testing in VRChat, check:** on PC, VRChat passes URLs through its bundled yt-dlp first. yt-dlp's generic extractor may probe the URL with a HEAD or a short GET, so make sure both of those are handled cheaply and don't count as viewers.

### HLS

- One segment = one unit, kept in a ring of the last **8** segments. Segment URL: `/live/{token}/seg/{mediaSequence}.ts`.
- The playlist (`/live/{token}/index.m3u8`, `Content-Type: application/vnd.apple.mpegurl`, `Cache-Control: no-cache`):

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:<seq of oldest listed>
#EXTINF:2.000,
seg/1234.ts
...
```

- List the 6 most recent segments, and keep 2 more available for slow fetchers. `EXTINF` = the unit's real duration, with 3 decimals.
- Segments are slices of the same TS byte stream, so continuity counters already run continuously across them.
- Players typically start about 3 segments behind the live edge, and different players start at different segments. That means VRChat viewers on HLS can be a few seconds apart from each other. The room page should mention this when HLS is enabled.
- As built (`output/HlsSegmenter`, `LiveHlsApi`): `EXTINF` = the next unit's start − this unit's start, so a unit is listed once its successor exists (about 1 s after its real-time start, as units are emitted LEAD early). Media sequence numbers are the segmenter's own and continue across packager restarts; the first segment after a restart is marked `#EXT-X-DISCONTINUITY` (with `#EXT-X-DISCONTINUITY-SEQUENCE` once it leaves the playlist), and the ring is emptied when the packager stops. Segments: `video/mp2t`, `Content-Length`, `Cache-Control: no-cache`; gone or not yet made → `404`. Each fetch counts as activity and starts the output if needed; a fresh output's first playlist request waits up to 5 s for a segment (else `503`, `Retry-After: 1`). The ring follows a room's packager from the first HLS fetch, and after a restart only if HLS was fetched in the last 60 s.

### RTMP push (VRCDN etc.)

The RTMP service handles distribution to viewers, so this server sends one stream per room regardless of how many people watch. VRChat sync between viewers then depends on the service's playback protocol.


- **v1:** spawn `ffmpeg -hide_banner -loglevel warning -f mpegts -i pipe:0 -c copy -f flv <rtmpUrl>/<key>` and write the unit bytes to its stdin, as one more consumer. (Stream copy from this TS to FLV was verified to work.) Use `rtmps://` if the service needs it, which requires an ffmpeg built with TLS.
- Supervise it: restart with backoff (1, 2, 5, 10, 30 s) when it exits, and show the status in the room UI (connected / retrying / error with the last stderr line).
- **v2 (optional):** a native Java RTMP client (handshake, chunking, AMF0 `connect`/`publish`, FLV tags), to drop the ffmpeg dependency at playback time. With FLV, the AVC and AAC sequence headers must be sent once at the start; they never change, because the profile is fixed.

## Lifecycle

- The packager starts when the owner enables an output, or when the first `.ts`/HLS request arrives for an enabled output.
- It stops 60 s after the last consumer leaves, unless an RTMP push is enabled. A new start resets the output clock, which only affects new connections.
- As built (`output/ProgramOutputs`, `ProgramPipeline`): consumers are leases (`.ts` connections, the RTMP pusher); an HLS fetch or enabling an output counts as activity at that moment; a sweep every 5 s stops an output with no lease, no RTMP push and no activity for 60 s. A packager that stopped by itself is restarted by the next request, or by the sweep while RTMP is enabled. `ProgramOutputs` is the rooms' `PackagerHook`: each committed change is copied on the room's executor and, for rooms with an output, turned into a `ProgramCue` (item kinds from the database) on another thread; an output started before it saw a change reads the room once with a read-only mutation.
- Item indexes are loaded into memory for the current and next entry only. Files are read with positional reads (`FileChannel.read(buf, pos)`).
- **Expected cost per room:** reading about 1.5 MB per 2 s for a 1080p video item (much less for audio items, whose cards are cached in memory), and a few µs of muxing per frame. A single core can run many rooms.

## Testing the packager

1. **Offline harness:** run the packager against a fake timeline (a scripted queue with a mix of video items, audio items, a pause, a seek and an empty-queue period), writing to a file instead of real-time pacing. As built: `output/ScriptedTimeline` (a fake room: queue, pause/play, seek, skip, clear, an unrecognised state, natural ends with `playedOut`, idle when empty, playing again when something is added while idle) and `output/OfflineHarness.run(sources, avcC, timeline, durationMs, out)` (the real `TimelineFollower`, `UnitBuilder` and `TsMuxer`; output clock `START` = script time 0; before each unit it hands over every change up to `LEAD_MS` before that unit is due, as the live packager would). `OfflineHarnessTest` runs a 60 s cycle per minute over real encodes laid out as ingest does and checks it with ffmpeg and ffprobe; `mvn test -Dtest=OfflineHarnessTest -Dtt.harness.minutes=30 -Dtt.harness.out=/path/out.ts` makes (and keeps) a 30-minute session.
2. Check the output with:
   - `ffmpeg -v error -i out.ts -f null -` → must print nothing;
   - `ffprobe -show_packets` → PTS strictly increasing per stream, audio deltas exactly 1920, no gaps at splices;
   - (optional) TSDuck `tsanalyze` → no continuity or PCR errors.
3. Play the file in mpv and VLC. Then serve it live and test in VRChat: AVPro on PC, AVPro on Quest/Android (needs HTTPS), and a Unity-player world (should fail gracefully; Unity can't play live streams).
4. **Sync check:** open the `.ts` in two VRChat clients side by side and measure the offset between them.
