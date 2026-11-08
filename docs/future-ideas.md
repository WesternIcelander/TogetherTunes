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
