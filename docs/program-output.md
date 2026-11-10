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

A unit runs from one of its source's IDRs to the next, so a video whose **first GOP is short** (a smart trim's, invariant 2: 1 to 60 frames, then every 2 s; [library-and-ingest.md](library-and-ingest.md#trim)) gives a **short first unit** when played from its start, like the short last unit where an item ends. Nothing else changes: its audio frames are those whose PTS falls in its video span (the last one may straddle the boundary, as always), the next unit continues the 1920-tick cadence, timestamps stay continuous (invariant 3), the constant-rate padding pads it to its own length (frame-based), and HLS lists it as a shorter segment. A join or seek mid-item starts at the keyframe at or before the position from the index, wherever the keyframes are (`VideoItemSource`).

Units are built by **sources**:

| Source | Video | Audio |
|---|---|---|
| `VideoItemSource(item, startMs)` | The item's mezzanine GOPs, starting at the keyframe ≤ `startMs`. Each GOP is replaced by the room's overlay GOP when one is complete ([overlay.md](overlay.md#packager-substitution)) | The item's audio from the same media time |
| `AudioItemSource(item, startMs)` | The item's card GOP, repeated. Uses the room's overlay card when the overlay is on. Also used for video items with `presentAsAudio` | The item's audio from the AAC frame at `startMs` |
| `FillerSource(card)` | The card GOP, repeated (the room's overlay version when the overlay is on) | The silence frame, repeated |

Fillers: **Paused** (timeline paused), **Idle** (room idle card; queue empty), **Loading** (next item's index not loaded yet; this should be rare, because the next item is preloaded during the current one). An item whose files the packager can't use (an older encoding, an unreadable `index.bin`) is never chosen by the room in the first place: rooms and packager share one notion of playable ([rooms.md](rooms.md#room-state), `ingest.PlayableFiles`), so the room skips it like an item still processing instead of the output showing Loading for its whole length. Loading for a whole item now only happens when the current entry's files become unusable mid-play (an encoding switch) or the files break after the room checked them.

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
- **Skips land at 0:00** (added 2026-11-10): an abrupt start (skip, `previous`, start from idle) while the output runs is put in the future by the room, at the output's first unit it hasn't begun to build (`Packager.nextItemStartServerMs`: the next unit's start, or the one after it while a unit is being built or due within 250 ms; [rooms.md](rooms.md#room-state)). At boundaries more than half a unit (`TimelineFollower.START_EARLY_MS`, 1 s) before that start, the follower keeps playing what it plays (the skipped item, or the filler, e.g. Idle); if the skipped item ends by itself in the gap, the Loading filler bridges it. At the first boundary at most 1 s before the start (normally the one the room aimed at; the switch point lies up to one audio frame past it) the entry starts from position 0: its first picture and first AAC frame, at the same server time web listeners start it. A cue that is a natural advance (`playedOut` set) is never treated as a delayed start. A position less than 100 ms past an anchor at 0 is played from 0.
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

## Constant-rate padding

Added 2026-11-10 (Siggi chose packager-side padding over keeping `nal-hrd=cbr`: "For the pre-rendered card that gets repeated every 2 seconds, the on-disk format shouldn't be padded with junk data, the padding should ideally be added before it gets sent out as a stream."). The encodes are only **capped** at `output.videoBitrateKbps` = `R` ([library-and-ingest.md](library-and-ingest.md#the-mezzanine-profile)); the packager brings the program output's video up to a constant **stream rate `T`** while it sends, by inserting **H.264 filler data NAL units** (type 12, `nal_ref_idc` 0, 0xFF payload, RBSP trailing bits). This adds padding only; nothing is decoded or encoded.

- **Model** (`output/CbrPadder`, one per output, between the unit builder and the muxer): after each access unit, the bytes sent so far are compared with `T × (end of that frame − first frame) / 8`; a frame ends at the next frame's PTS, the unit's last frame at the unit's boundary. When the output is behind, one filler NAL unit of the missing size goes into that access unit **after its slices** (valid Annex B order; split into several where a 1- or 2-byte NAL length prefix can't hold it). A frame bigger than its share (an IDR) puts the output ahead; the following frames pay it back by getting less padding. Sizes count the AVCC samples; the muxer's Annex B form adds the same few bytes every GOP (AUD per frame, SPS/PPS per IDR), so the TS is constant-rate as well.
- **`T ≥ R` is always met:** every encode obeys the cap with a 0.5 s VBV buffer, so the output never runs more than that buffer ahead of `T`, and is never behind after a frame. Below `R` it can't be met for items (the output runs ahead whenever an encode does); only card-only output (audio-only rooms) uses a lower `T`. Normal rooms: `T` = `R` (the output encoding's cap at the output's start). [Audio-only rooms](#audio-only-rooms): `T` = `output.audioOnlyBitrateKbps` (default 800, read at the output's start); same avcC, so only the padding target differs.
- **Changing `T` mid-stream** (an audio-only room switched on or off): the padder changes its rate at the start of a unit (`CbrPadder.bitrateBps(rate, atPts)`): the bytes due so far stay due, and from there they grow at the new rate, so an output that was ahead at the change (an encode's VBV burst at the normal rate) pays that back in the first unit at the new rate (measured 2026-11-10: up to about 17 KB less in the first card unit at 800 kb/s, then every unit within ±0.6 %).
- **Measured** (`CbrPaddingTest`, 2026-11-10, ffmpeg 6.1.1): a mixed session at 720p30, `T` = `R` = 3 Mb/s (video items to their end, an audio item's card, pause, seeks, idle; still cards): every full 2 s unit within ±0.8 % of `T × 2 s / 8`, apart from the unit after an item's short last GOP (−2.3 %, paying back that GOP's IDR: the short unit itself carries the IDR's excess, +15 % of its 0.3 s); the padding for the up to one audio frame between a unit's boundary and a new source's start lands in the next unit (±0.5 % between the two). Cards only at `T` = 800 kb/s: every unit within ±0.6 %. The same stream remuxed with `-c copy` to FLV (as the RTMP pusher sends it) keeps every filler NAL and the same per-unit rates.
- **Every output gets it:** `.ts`, HLS and the RTMP push all carry the padded stream (one shared byte stream).
- **MPEG-TS null packets** for a constant mux rate (the TS's own bitrate, audio and tables included): not added. RTMP drops them, and the video rate is what players buffer by.

## Audio-only rooms

Added 2026-11-10 ([rooms.md](rooms.md#audio-only-rooms)). An entry that plays audio-only (`ProgramCue.Entry.audioOnly`, fixed when the entry starts playing) plays through `AudioItemSource`, as `presentAsAudio` does: the item's **card** repeated as video, with its **audio-only file**: for a video with an audio trim `audio-only.m4a` through its own index `index-audio-only.bin` (card + that audio, a slot of its own in `ProgramMedia`, so the same item can be loaded in both modes as current and next), else `card.mp4` with the audio of `index.bin` (`audio.m4a`). `mezz.mp4` isn't read. Fillers follow the room's setting (`ProgramCue.audioOnly`).

The stream stays one stream: same avcC and the same timestamps rules, only the padding target changes ([constant-rate padding](#constant-rate-padding)): the follower reports whether the unit it just built is audio-only output (`TimelineFollower.audioOnlyRate()`: the playing entry's mode, or for a filler the room's setting), and the packager pads that unit to `output.audioOnlyBitrateKbps`, every other unit to `R`. So a switch of the setting while a video plays takes effect at the video's end: the video plays on at `R`, the next item comes as a card at the lower rate (and back). Tested in `AudioOnlyRoomOutputTest` (real encodes: cards with each item's audio-only file at a constant 800 kb/s; a switch while a video plays; cards of a detailed photo cover at a constant 800 kb/s, and the same cards spliced between videos in a normal room decoding cleanly).

**Cards must stay well below the audio-only rate**, since the padder only adds: cards are [skip-frame cards](library-and-ingest.md#cards) (an x264 IDR plus 59 written `P_Skip` frames of a few bytes each), about 110 kb/s of video for a detailed cover at 720p. Cards encoded whole by x264 measured 800–870 kb/s for real cover art (the encoder kept refining the still), which ran an audio-only stream over its 800 kb/s target.

## MPEG-TS muxer

Write it in-house; it's small.

- **PIDs** (same as VRCDN): PAT 0x0000, SDT 0x0011, PMT 0x0100, video 0x0101 (`stream_type 0x1B`, H.264), audio 0x0102 (`stream_type 0x0F`, AAC ADTS). PCR is carried on the video PID.
- **SDT, PAT and PMT** are written at the start of every unit, and PAT and PMT are repeated about every 400 ms within a unit (VRCDN repeats them at roughly that interval).
- **Each unit starts with** SDT, PAT, then PMT, then the IDR access unit with `random_access_indicator=1` in the adaptation field. Every unit is therefore a valid place to start, both for `.ts` joins and for HLS segments.
- **Video access unit → PES:** convert AVCC to Annex B. Each NAL has a length prefix (`lengthSizeMinusOne+1` bytes from avcC); replace it with `00 00 00 01`. Prepend an **AUD** NAL (`00 00 00 01 09 F0`) to every access unit. Before every IDR, insert SPS and PPS from avcC. Every other NAL passes through unchanged, in particular the **filler data NALs (type 12)** the packager adds for the constant rate ([Constant-rate padding](#constant-rate-padding)); `-c copy` keeps them in the RTMP push's FLV. Use one PES per access unit, with `PES_packet_length = 0` (unbounded, allowed for video).
- **Audio → PES:** wrap each AAC frame in a 7-byte **ADTS** header: `syncword 0xFFF, ID=0, layer=0, protection_absent=1, profile=1 (LC), sf_index=3 (48 kHz), channel_config=2, frame_length=7+len, buffer_fullness=0x7FF, raw_blocks=0`. Group about 5 frames (about 107 ms) into one PES, with PTS = the first frame's PTS.
- **Interleave** video and audio PES in DTS order within a unit.
- **PCR:** written in the adaptation field of every video PES start (every 33 ms, under the 100 ms maximum the spec allows). `PCR = DTS − 63000` (700 ms mux delay), base × 300 + extension (extension 0).
- **Continuity counters:** one 4-bit counter per PID, continuous for the **lifetime of the packager**. Every consumer sees one coherent byte stream; a joining client simply starts at a unit boundary.
- Pad the last TS packet of each PES with adaptation-field stuffing; packets are always 188 bytes.

Each unit's TS bytes are produced **once** into an immutable `byte[]`, and that same array is shared by every consumer.

## Consumers

All four outputs (RTMP push, `.ts`, HLS, MP3) are equal features. Each one is enabled server-wide by the admin (`outputs.*.enabled`) and per room by the owner. They are all consumers of the same unit byte stream, so running the three video outputs costs barely more than running one; MP3 adds one audio encode per room with listeners.

### Stream URLs

(Siggi, 2026-11-10: "we should have it be based on the room slug instead of it being random token"; private rooms keep tokens.) The pull outputs' URLs carry a **key**: `/live/{key}.ts`, `/live/{key}/index.m3u8` (and its segments), `/live/{key}.mp3`.

- **Public and unlisted rooms:** the key is the room's **slug** (`/live/club.ts`). Slugs aren't secrets.
- **Private rooms:** the key is a random **token** per output (128 bits, base64url, 22 characters), as before, with Rotate. Tokens are capabilities (invariant 6).
- A key resolves (`StreamOutputs.KEY_MATCH`) as the token of a private room's output, or the slug of a public or unlisted room; anything else is `404`. Slugs and tokens can't collide: slugs are `a-z0-9-`, and a new token is redrawn until it has an upper-case letter or `_` (`StreamOutputs.newToken`).
- **Changes apply at once** (`StreamOutputControl.roomEdited`, called after `PATCH /api/rooms/{room}` changed the slug or visibility): to private, slug URLs stop, open connections on them end and every output gets a **fresh token** (old tokens from an earlier private phase never come back); to public/unlisted, the slug URLs start and token URLs stop. A slug change ends connections on the old slug (no redirect; VRChat players don't follow them reliably). The room page's outputs panel reloads its URLs when the slug or visibility changes. The periodic reconcile catches anything else within 10 s.
- Rate limits and the per-URL connection cap (`LiveLimits`, keyed by the URL's key) stay as they were. Rotate is only offered (and only accepted) for private rooms (`409 no_token` otherwise).
- **Plain HTTP:** with `http.insecureBaseUrl` set, stream URLs (slug and token) are also served over plain HTTP and the outputs API offers them as `insecureUrl`. A private room's token then travels unencrypted: its first plain-HTTP use is recorded (`stream_outputs.token_cleartext_at`, cleared by Rotate) and the outputs panel warns about it (`tokenCleartextAt`). See [architecture.md](architecture.md#plain-http).

### Direct MPEG-TS (`.ts`)

`GET /live/{key}.ts` (also `HEAD`; key: [stream URLs](#stream-urls)):

- Response `200`, `Content-Type: video/mp2t`, `Cache-Control: no-store`, with **no `Content-Length` and no `Transfer-Encoding`**: the body ends when the connection closes. This is what VRCDN does (see below).
- Ignore `Range` and always return `200` from the live edge. `HEAD` returns `200` with `Content-Length: 0` and no body, like VRCDN; it doesn't start the packager or count as a viewer.
- **Join point:** by default, the client receives data from the **next** unit boundary (`join=next`). This keeps every VRChat viewer at the same live edge, so they stay close in sync. The alternative, `join=latest` (send the most recent unit immediately, so playback starts faster), is a room setting. It costs up to 2 s of extra, permanent lag for that viewer.
- **Per-consumer bounded queue:** 4 units (about 8 s). If it overflows, the client is disconnected; it will usually reconnect.
- **Reference — VRCDN's `.ts` output** (observed 2026-11-08 on `stream.vrcdn.live/live/<name>.live.ts`): HTTP/1.1 `200`, `Content-Type: video/mp2t; charset=utf-8`, no `Content-Length`, no `Transfer-Encoding` (close-delimited, despite a `Connection: keep-alive` header). `HEAD` → `200`, `Content-Length: 0`. The body starts with SDT, PAT and PMT, then continues from the current live position **mid-GOP** (no burst, no waiting for a keyframe); data then arrives at the stream's real-time rate. PIDs as above; PAT/PMT about every 0.4 s; PCR on the video PID. We copy all of this except the join point: `join=next` starts a viewer at the next unit boundary instead of mid-GOP. The player can't show anything before that IDR either way, so the viewer sees the same picture at the same time, without the undecodable lead-in.
- **Before testing in VRChat, check:** on PC, VRChat passes URLs through its bundled yt-dlp first. yt-dlp's generic extractor may probe the URL with a HEAD or a short GET, so make sure both of those are handled cheaply and don't count as viewers.

### HLS

- One segment = one unit, kept in a ring of the last **8** segments. Segment URL: `/live/{key}/seg/{mediaSequence}.ts`.
- The playlist (`/live/{key}/index.m3u8`, `Content-Type: application/vnd.apple.mpegurl`, `Cache-Control: no-cache`):

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
- Players typically start about 3 segments behind the live edge, and different players start at different segments. That means VRChat viewers on HLS can be a few seconds apart from each other. The room page mentions this (in generic words) when HLS is enabled; see also [user-guide.md](user-guide.md#hls-and-staying-in-sync).
- As built (`output/HlsSegmenter`, `LiveHlsApi`): `EXTINF` = the next unit's start − this unit's start, so a unit is listed once its successor exists (about 1 s after its real-time start, as units are emitted LEAD early). Media sequence numbers are the segmenter's own and continue across packager restarts; the first segment after a restart is marked `#EXT-X-DISCONTINUITY` (with `#EXT-X-DISCONTINUITY-SEQUENCE` once it leaves the playlist), and the ring is emptied when the packager stops. Segments: `video/mp2t`, `Content-Length`, `Cache-Control: no-cache`; gone or not yet made → `404`. Each fetch counts as activity and starts the output if needed; a fresh output's first playlist request waits up to 5 s for a segment (else `503`, `Retry-After: 1`). The ring follows a room's packager from the first HLS fetch, and after a restart only if HLS was fetched in the last 60 s.

### RTMP push (VRCDN etc.)

The RTMP service handles distribution to viewers, so this server sends one stream per room regardless of how many people watch. VRChat sync between viewers then depends on the service's playback protocol.


- **v1:** spawn `ffmpeg -hide_banner -loglevel warning -f mpegts -i pipe:0 -c copy -f flv <rtmpUrl>/<key>` and write the unit bytes to its stdin, as one more consumer. (Stream copy from this TS to FLV was verified to work.) Use `rtmps://` if the service needs it, which requires an ffmpeg built with TLS.
- Supervise it: restart with backoff (1, 2, 5, 10, 30 s) when it exits, and show the status in the room UI (connected / retrying / error with the last stderr line).
- As built (`output/RtmpPusher`, `RtmpPushers`): ffmpeg also gets `-nostats -progress pipe:1 -analyzeduration 1000000 -flvflags no_duration_filesize` (connected = its first progress report, which comes once the RTMP output is open; 1 s probing so it connects on the first unit). Each attempt holds a lease with a `join=latest` 4-unit queue; the push pins the output running. States `CONNECTING`, `CONNECTED`, `RETRYING`, `ERROR` (5 failures in a row, i.e. waiting 30 s, or ffmpeg / the output can't be started; still retried), `STOPPED`; the detail is ffmpeg's last stderr line with the key masked (`…` + last 4) and URL credentials (user info, query, fragment) masked, in the log too; moderators see it without URLs or key hint (owner-only target and key, see [auth.md](auth.md)). An attempt connected for 60 s resets the backoff. A queue overflow (ffmpeg not taking the stream) kills ffmpeg; a packager restart ends its input cleanly. The key is on ffmpeg's command line, visible to local users who can list processes.
- **Known limitation: the stream key in transit** (Siggi, 2026-11-10). With a plain `rtmp://` ingest URL, RTMP sends the stream key (and the ingest URL, which may carry credentials) **unencrypted** between this server and the RTMP service; anyone on that network path can read it. That is the service's protocol choice and not fixable here. Mitigation: use an `rtmps://` URL when the service offers one (accepted by `StreamOutputs`; the bundled ffmpeg has the `rtmps` protocol). Decision: no warning in the UI; this is documented only (here and in the user guide's RTMP section). Related: the key is stored in plain text by design (owner-only, Reveal/Copy), since RTMP doesn't protect it anyway.
- **Auto turn-off:** see [below](#auto-turn-off).
- **v2 (optional):** a native Java RTMP client (handshake, chunking, AMF0 `connect`/`publish`, FLV tags), to drop the ffmpeg dependency at playback time. With FLV, the AVC and AAC sequence headers must be sent once at the start; they never change, because the profile is fixed.

### MP3 (internet-radio players)

An audio-only MP3 stream in the Shoutcast/Icecast style, for internet-radio players (added 2026-11-10 for Euro Truck Simulator 2 and American Truck Simulator radio; the UI says only "internet-radio players", see [user-guide.md](user-guide.md#internet-radio-mp3)). MP3 needs an audio encode and the packager never encodes, so this is a **supervised ffmpeg per room**, like the v1 RTMP pusher.

- `GET /live/{key}.mp3` (also `HEAD`): `200`, `Content-Type: audio/mpeg`, `Cache-Control: no-store`, close-delimited like `.ts` (no `Content-Length`, no `Transfer-Encoding`), `icy-name` (the room name as printable ASCII, accents dropped), `icy-br`, `icy-sr: 44100`, `icy-pub: 0`. `HEAD` sends the same headers and no body, and never starts the encoder.
- **ICY metadata:** when the client sends `Icy-MetaData: 1`, the response has `icy-metaint: 16000` and after every 16000 bytes of audio one metadata block (length byte N, then 16·N bytes): `StreamTitle='Artist - Title';` (just the title without an artist; empty for fillers: paused, idle, loading), NUL-padded. A block repeats the title only when it changed since the last block on that connection (the first block always has it); otherwise it is the byte 0. The title is made safe: control characters become spaces, `'` becomes `’`, at most 250 characters. Clients that don't ask get plain MP3.
- **Title timing:** every unit carries the item it plays (`TsUnit.itemId`); when it changes, the encoder notes the title at that unit's start position (ms since its first unit), and the frames from that output position on carry the new title. So the title changes as the item reaches the stream, not when the timeline moved.
- **Encoder** (`output/Mp3Encoder`, `Mp3Encoders`): one per room, started by the first listener, holding a lease on the program output with a `join=latest` queue (like the RTMP pusher), stopped 15 s after its last listener left (a player that reconnects at once finds it running); the program output then stops at the usual 60 s. Command: `ffmpeg -analyzeduration 1000000 -f mpegts -i pipe:0 -map 0:a:0 -vn -c:a libmp3lame -b:a <rate>k -ar 44100 -ac 2 -id3v2_version 0 -write_xing 0 -flush_packets 1 -f mp3 pipe:1`: no ID3 tag, no Xing header, so the stream starts with an audio frame. Its output is cut into whole frames (`output/Mp3Frames`), so every listener starts on a frame boundary. Rate: server setting `outputs.mp3.bitrateKbps` (default 128, 64..320), read when each encode starts; 44.1 kHz stereo CBR. The ffmpeg in the Docker image needs libmp3lame.
- **Listeners:** each has a bounded queue of 10 s of audio; one whose queue overflows is disconnected (as `.ts`). A new listener first gets the last ~2 s of audio at once (so a player's buffer fills at once; the encoder's output comes in bursts, a unit at a time), then everything from the current position on: no wait for an item start.
- **Supervision:** when ffmpeg exits, the program output restarts or ffmpeg falls behind, the attempt ends and the next one starts after 1, 2, 5, 10, then every 30 s (an attempt that produced audio for 60 s resets the backoff). Listeners stay connected and hear a gap, not a disconnect. While it has no listeners, the encoder starts no new attempt (so it doesn't restart a stopped program output).
- **Limits and revocation:** as `.ts` (`LiveLimits`: per-IP rate limit, 64 connections per URL); rotate, a slug or visibility change, turning it off, deleting it or the server switch `outputs.mp3.enabled` ending ends open connections (`LiveMp3Api.revalidate`, run with the `.ts` one by every reconcile). Without ffmpeg, `GET` is `503 output_unavailable` and the room page shows the output as `error`.
- **Remote mode delay:** room setting `estimatedDelayMs.mp3`, default 3000 ms (a guess: units go to ffmpeg 1 s early and the encode adds little, so the delay is mostly the player's buffer; not measured in the games yet).
- The `/live/{file}` route is shared with `.ts` (`LiveTsApi` hands `*.mp3` to `LiveMp3Api`).
- **HTTPS:** the earlier assumption that current game versions play `https://` was wrong (manual check 2026-11-10): some internet-radio players have no TLS and ignore the scheme, connecting to port 80 over plain HTTP even when given an `https://` URL. Such players need the stream over plain HTTP: set `http.insecureBaseUrl` and have the reverse proxy forward port 80 too ([architecture.md](architecture.md#plain-http)); the outputs panel then shows the `http://` URL as well. Slug and token URLs are both served that way (a token used over HTTP gets a warning in the panel; rotate it if that matters).

## Lifecycle

- The packager starts when the owner enables an output, or when the first `.ts`/HLS/MP3 request arrives for an enabled output.
- It stops 60 s after the last consumer leaves, unless an RTMP push is enabled. A new start resets the output clock, which only affects new connections.
- As built (`output/ProgramOutputs`, `ProgramPipeline`): consumers are leases (`.ts` connections, the RTMP pusher); an HLS fetch or enabling an output counts as activity at that moment; a sweep every 5 s stops an output with no lease, no RTMP push and no activity for 60 s. A packager that stopped by itself is restarted by the next request, or by the sweep while RTMP is enabled. `ProgramOutputs` is the rooms' `PackagerHook`: each committed change is copied on the room's executor and, for rooms with an output, turned into a `ProgramCue` (item kinds from the database) on another thread; an output started before it saw a change reads the room once with a read-only mutation.
- Every output (with the room's `outputs.autoOff` on) is turned off after the queue was empty, or (pull outputs) nobody listened, for the limit ([auto turn-off](#auto-turn-off)).
- When an admin changes the output encoding, every running output restarts on the new one at once (`restartRunning`; [library-and-ingest.md](library-and-ingest.md#changing-the-encoding-at-runtime)): each start reads the current encoding for its sources, cards and muxer avcC, so one output never mixes two encodings.
- Item indexes are loaded into memory for the current and next entry only. Files are read with positional reads (`FileChannel.read(buf, pos)`).
- **Expected cost per room:** reading about 1.5 MB per 2 s for a 1080p video item (much less for audio items, whose cards are cached in memory), and a few µs of muxing per frame. A single core can run many rooms.

## Auto turn-off

(Generalised 2026-11-10 from the RTMP-only idle stop. Siggi: "Have an option to automatically turn off after 10 minutes, or if it should remain always-on. Room owner can set it. Admin can force the auto-turn-off setting on." Then: the 10 minutes start from an empty queue **or** no listeners, whichever comes first.)

- **Settings:** room `outputs.autoOff` (default **on**; off = always on) and `outputs.autoOffMinutes` (default 10, 1..1440; Manage room → Settings → Outputs). Server `outputs.forceAutoOff` (default off): when on, every room behaves as `outputs.autoOff = on` (the room's switch is shown greyed, "Required by the server admin"). Server `outputs.maxAutoOffMinutes` (default 60, 1..1440) caps the room's minutes for every output: **limit** = `min(room, server)`, a higher room value is saved but acts as the maximum (Manage room says so). Migration 0017 moved the old `outputs.rtmp.idleStopMinutes` (room) and `outputs.rtmp.maxIdleStopMinutes` (server) values to the new names.
- While auto turn-off applies, an active output is turned off once **either**
  - (a) the room has been **idle** for the limit: nothing playing and nothing queued (the timeline is idle and the room has no `queued` entry, i.e. the output shows the Idle card; paused with an item is not idle, suggestions don't count). Per room, applies to every output. Reason `idle`.
  - (b) for `.ts`, HLS and MP3, **nobody has listened** for the limit: no open `.ts`/MP3 `GET` connection and no HLS playlist or segment request. Each output has its own clock. Reason `no_listeners`. RTMP has no listeners of its own, so only (a) applies to it.
- Both clocks start no earlier than the moment the output became active (turned on, its server switch turned on, or the server started), so turning an output on again gives a fresh timer even if the room is still idle, and an output nobody uses turns off too. Any activity (an item queued or playing; a listener) resets the respective clock.
- Turning off works exactly like the owner pressing Turn off (`enabled = false`; pushes stop, open connections end, with no other active output the program output stops; URL, key and token stay), records why (`stream_outputs.stopped_reason = 'idle' | 'no_listeners'`, `stopped_at`, `stopped_idle_minutes`) and writes the `room.output_updated` audit entry with no actor (the system), `reason` and `idleMinutes`. It stays off until the owner turns it on; a new request or play doesn't restart it (a request to a turned-off output is `404`). Turning it on (or off) clears the recorded reason. The owner sees why on the room page ("Off: queue empty for 10 minutes" / "Off: no listeners for 10 minutes", with Turn on).
- As built: `output/OutputAutoOff` gets each room's idle state from `ProgramOutputs` (an `IdleListener` called on the room's executor after every committed change and every snapshot read, so it only records a timestamp; invariant 4: nothing feeds back into the timeline), the active outputs from every `StreamOutputControl` reconcile (`sync`) and listening from `LiveTsApi`/`LiveMp3Api` (connection opened/closed) and `LiveHlsApi` (each request) through `OutputListeners`; `StreamOutputControl` runs the check on its 10 s timer before each reconcile, so an output turns off up to about 10 s after its limit.

## Testing the packager

1. **Offline harness:** run the packager against a fake timeline (a scripted queue with a mix of video items, audio items, a pause, a seek and an empty-queue period), writing to a file instead of real-time pacing. As built: `output/ScriptedTimeline` (a fake room: queue, pause/play, seek, skip, clear, an unrecognised state, natural ends with `playedOut`, idle when empty, playing again when something is added while idle) and `output/OfflineHarness.run(sources, avcC, timeline, durationMs, out)` (the real `TimelineFollower`, `UnitBuilder` and `TsMuxer`; output clock `START` = script time 0; before each unit it hands over every change up to `LEAD_MS` before that unit is due, as the live packager would). `OfflineHarnessTest` runs a 60 s cycle per minute over real encodes laid out as ingest does and checks it with ffmpeg and ffprobe; `mvn test -Dtest=OfflineHarnessTest -Dtt.harness.minutes=30 -Dtt.harness.out=/path/out.ts` makes (and keeps) a 30-minute session. **Golden file:** `PackagerGoldenFileTest` runs two cycles, applies the checks below plus "an IDR at every unit start and nowhere else", and compares the session's structure (ffprobe's program and stream description, unit and frame counts, the harness log with every unit's PTS range and source) with `src/test/resources/golden/packager-session.txt`; no bitstream bytes are stored, so it doesn't depend on the x264 build. `-Dtt.golden.update=true` regenerates it. Without ffmpeg/ffprobe the harness tests are skipped, except under `CI=true` or `-Dtt.requireMediaTools=true`, where they fail.
2. Check the output with:
   - `ffmpeg -v error -i out.ts -f null -` → must print nothing;
   - `ffprobe -show_packets` → PTS strictly increasing per stream, audio deltas exactly 1920, no gaps at splices;
   - (optional) TSDuck `tsanalyze` → no continuity or PCR errors.
3. Play the file in mpv and VLC. Then serve it live and test in VRChat: AVPro on PC, AVPro on Quest/Android (needs HTTPS), and a Unity-player world (should fail gracefully; Unity can't play live streams).
4. **Sync check:** open the `.ts` in two VRChat clients side by side and measure the offset between them.
