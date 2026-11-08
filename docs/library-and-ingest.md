# Ingest

How media gets onto the server and into the mezzanine format. Libraries, sharing and retention are in [libraries.md](libraries.md).

## Who can add media

| Path | Users | Guests |
|---|---|---|
| Upload | `library.upload` | `guests.canUpload`, plus the room setting, and only as a request inside a room |
| YouTube | `library.youtube` and `ytdlp.enabled` | `guests.canYoutube`, plus the room setting, and only as a request inside a room |

Guest uploads are subject to `guests.uploadQuota`, counted per guest and per IP. All guest-added items start unreferenced, so retention deletes them unless the room owner adds them to a library.

## Upload

1. `POST /api/uploads` returns an upload id. The client then sends the file in chunks with `PUT /api/uploads/{id}` and `Content-Range`, which makes uploads resumable. Chunks are written to `tmp/`; the SHA-256 is computed as they arrive.
2. On `complete`:
   - If `content_sha256` matches an existing item, reuse that item and discard the upload. If the existing item is `purged`, re-ingest into it.
   - Otherwise run ffprobe, and reject the file if it has no audio, can't be parsed, or is too long. Create the item (`pending`) and an ingest job.
3. Metadata comes from container tags, falling back to the filename. Cover art comes from the embedded picture.

## YouTube caching

The YouTube **video ID** is the cache key. Every YouTube item stores it in `items.youtube_id`, which is unique.

1. **Normalise** the pasted URL to the 11-character video ID before anything else. Accept `youtube.com/watch?v=`, `youtu.be/`, `/shorts/`, `/embed/`, `/live/`, `music.youtube.com`, and `m.` variants; ignore `t=`, `list=` and tracking parameters. Reject anything that doesn't yield an ID.
2. **Look up** `items.youtube_id`:
   - `ready` → use the cached item immediately. No network access at all.
   - `pending` / `processing` → attach the request to the job that's already running. The queue entry shows "processing", and becomes playable when the job finishes.
   - `purged` → start a `refetch` job on the **same row**. History links stay valid.
   - `failed` → retry, if the last attempt was more than 10 minutes ago.
   - not found → create the item and an ingest job.
3. Only on a cache miss: fetch metadata with `yt-dlp -J --no-playlist <url>`. Reject live streams and videos over the duration limit.
4. Download with `yt-dlp --no-playlist -f "bv*[height<=1080]+ba/b" --merge-output-format mkv -o <tmp>/src.%(ext)s https://www.youtube.com/watch?v=<id>`. Always download from the canonical URL built from the ID.
5. Title and artist come from `track`/`artist` when present, otherwise from `title`/`uploader`. Cover art is the best thumbnail.

Because one ID maps to one item, the "audio only" choice is not a separate item. Every video item can be **presented as audio**: an item flag `presentAsAudio` makes the packager use the item's card instead of its video. It's useful for "Topic" uploads, which are just a still image. Anyone who can edit the item's metadata can set it, and the importer can set it at import time.

Operational notes:

- YouTube often blocks datacenter IPs. Support an optional cookies file.
- Keep yt-dlp up to date: an admin button runs `yt-dlp -U`.
- Surface yt-dlp's stderr in the job log.

## The mezzanine profile

Every item is transcoded at ingest to one fixed profile, so the packager can splice any item next to any other with **byte-identical SPS/PPS and audio config**.

| Parameter | Value |
|---|---|
| Video codec | H.264 High, level 4.1 (1080p30) / 4.0 (720p30), yuv420p, SAR 1:1 |
| x264 preset | **veryfast**, for everything: mezzanines, cards and overlay re-encodes |
| Resolution | 1920×1080 or 1280×720 (server-wide). Scale to fit, then pad to the exact size with black |
| Frame rate | Constant 30 fps (the `fps=30` filter converts other rates) |
| GOP | Closed, IDR exactly every 2 s (60 frames), forced IDR at t=0, no scene-cut keyframes |
| B-frames | **None** (`-bf 0`), so PTS = DTS |
| Rate control | CRF 20, capped at `-maxrate 6M -bufsize 12M` (1080p) / `3M`/`6M` (720p) |
| Audio | AAC-LC, 48 kHz, stereo, 192 kb/s |
| Loudness | Two-pass EBU R128 `loudnorm` to −14 LUFS integrated, −1 dBTP |
| Container | MP4 with `+faststart` |

**Why the preset is part of the profile:** the x264 preset changes SPS fields such as the reference frame count. In a test, `medium` and `veryfast` produced different extradata; with identical arguments, the extradata was identical. Every encode that feeds the packager must therefore use exactly the same argument list. Define it **once in code**, as a constant `List<String>`, and use it everywhere.

`veryfast` was chosen so that overlay re-encodes run faster than real time: about 2.8× real time for 1080p30 on 2 cores in testing. The cost is about 10–15% more bitrate than `medium` at the same quality, which the 6 Mb/s cap absorbs.

Reference command (video item; the second loudnorm pass is shown):

```sh
ffmpeg -i src -map 0:v:0 -map 0:a:0 \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30" \
  -c:v libx264 -preset veryfast -profile:v high -level:v 4.1 -pix_fmt yuv420p -bf 0 \
  -g 60 -keyint_min 60 -sc_threshold 0 -force_key_frames "expr:gte(t,n_forced*2)" -x264-params open-gop=0 \
  -crf 20 -maxrate 6M -bufsize 12M \
  -af "loudnorm=I=-14:TP=-1:LRA=11:measured_I=…:measured_TP=…:measured_LRA=…:measured_thresh=…:offset=…:linear=true,aresample=48000" \
  -c:a aac -b:a 192k -ar 48000 -ac 2 \
  -movflags +faststart mezz.mp4
```

Then `audio.m4a` = `ffmpeg -i mezz.mp4 -map 0:a -c copy -movflags +faststart audio.m4a`.

For **audio items**, encode only the audio, straight to `audio.m4a`; there is no `mezz.mp4`. **Every** item gets a `card.mp4` (below), including video items, so that `presentAsAudio` and the transition card work.

**Invariant check (mandatory):** compare each encode's avcC with the server's reference card for the profile, and fail the job if the bytes differ.

## Cards

A card is a still image encoded as one 2 s GOP with the **same encoder arguments** as the mezzanine: one IDR followed by 59 P-frames of about 70 bytes each. A whole card is about 40–100 KB.

```sh
ffmpeg -loop 1 -framerate 30 -i card.png -t 2 <PROFILE_VIDEO_ARGS> -an card.mp4
```

The image is rendered with Java2D at the output resolution:

- **Item card:** artwork, title, artist and album. The text must fit (shrink, then ellipsize).
- **Server filler cards:** "Paused", "Queue is empty", "Loading…".
- **Room cards:** the idle card with the room's name; with the QR overlay enabled, overlaid versions of every card ([overlay.md](overlay.md)).

Don't rely on colour alone to convey anything: use text labels and strong contrast.

## Silence

One pre-encoded **AAC silence frame** (48 kHz stereo), generated at startup from `anullsrc` and taken from after the encoder priming. The packager pairs it with cards whenever the room is paused, idle or loading.

## Sample index (`index.bin`)

At ingest, build an index from ffprobe, so the packager never parses MP4 at playback time:

```sh
ffprobe -v error -select_streams v -show_entries packet=pts,dts,duration,size,pos,flags -of csv=p=0 mezz.mp4
ffprobe -v error -select_streams a -show_entries packet=pts,dts,duration,size,pos,flags -of csv=p=0 audio.m4a
```

(For an audio item, index `audio.m4a` and `card.mp4`.) `pos` is the absolute byte offset of the sample. `flags` contains `K` for a keyframe and `D` for discard.

**AAC priming:** ffmpeg's AAC encoder adds 1024 samples of priming, reported as a first audio packet with `pts=-1024` and flag `D`. Drop every packet with `D` or negative PTS.

Binary format (big-endian):

```
magic "MSIX" | version u16 | profile u16 | file-ref table
track count u8
per track:
  kind u8 (0=video,1=audio) | timescale u32 | file-ref u8 | sample count u32
  per sample: offset u64 | size u32 | pts i64 (track timescale) | duration u32 | flags u8 (bit0=keyframe)
avcC length u16 | avcC bytes
```

Get avcC with a small MP4 box walker (`moov/trak/mdia/minf/stbl/stsd/avc1/avcC`). The audio config is fixed by the profile.

## Ingest job states

`queued → running → done | failed`, with `progress` (from ffmpeg `-progress pipe:1`) and a log tail.

Steps: `probe → (download) → loudness pass 1 → encode → extract audio.m4a → card render+encode → index → verify → publish`.

**Verify:** the audio duration is within 50 ms of the source, the avcC invariant holds, and keyframes fall exactly every 2 s.

If the output profile changes, every item that isn't purged gets a `reencode` job.
