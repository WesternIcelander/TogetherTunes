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
4. For an audio file, the upload is kept as the item's original ([below](#originals-audio-uploads)). For a video file, it is deleted once ingest has finished.

## Originals (audio uploads)

An **audio item that came from an upload** keeps its original file as `media/<itemId>/original.<ext>`, and the web client plays it instead of `audio.m4a` ([rooms.md](rooms.md#listen-mode)). The packager always uses `audio.m4a`, because it needs the fixed AAC profile. Originals of video uploads and of YouTube downloads are deleted after ingest.

- **File name:** the extension comes from the container ffprobe finds, not from the upload's name: `mp3`, `flac`, `wav`, `aac` (ADTS), `opus` (Opus in Ogg) or `ogg`, `m4a` (MP4/MOV family). Matroska keeps `.webm`, `.mka` or `.mkv` from the upload's name (else `mka`); any other container keeps a plain upload extension (`[a-z0-9]{1,10}`), else `bin`.
- **Browser-playable check:** ingest records the original's codec and container (`items.original_codec`, as ffprobe's `<codec_name>/<format_name>`, e.g. `flac/flac` or `aac/mov,mp4,m4a,3gp,3g2,mj2`). It is marked web-playable (`original_web`) only for MP3, AAC (in MP4/M4A or ADTS), FLAC, Opus (in Ogg, or in WebM, i.e. Matroska uploaded as `.webm`) and PCM WAV (`pcm_u8`, `pcm_s16le`, `pcm_s24le`, `pcm_s32le`, `pcm_f32le`), and only if its duration (the container's, as a player shows it) is within 50 ms of `audio.m4a`. Anything else (WMA, ALAC, APE, …) is still kept, but the web client uses `audio.m4a`. The client also checks `canPlayType` before using an original.
- **Loudness:** the original isn't normalised. Ingest computes `original_gain_db = min(−14 − I, −1 − TP)` from the first loudnorm pass (integrated loudness *I* and true peak *TP* of the source), so the web client can play it at the same loudness as `audio.m4a` without clipping, using its per-element Web Audio `GainNode`. Rounded to 0.01 dB; null if pass 1 measured silence (the client then applies no gain).
- The SHA-256 used for dedupe is the original's hash, so a re-upload of the same file matches.
- Retention deletes the original together with the rest of `media/<itemId>/`.

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
| Resolution | 1280×720 (default) or 1920×1080, server-wide (`output.profile`). Scale to fit, then pad to the exact size with black |
| Frame rate | Constant 30 fps (the `fps=30` filter converts other rates) |
| GOP | Closed, IDR exactly every 2 s (60 frames), forced IDR at t=0, no scene-cut keyframes |
| B-frames | **None** (`-bf 0`), so PTS = DTS |
| Rate control | CRF 20, capped at `-maxrate 6M -bufsize 12M` (1080p) / `3M`/`6M` (720p) |
| Audio | AAC-LC, 48 kHz, stereo, 192 kb/s |
| Loudness | Two-pass EBU R128 `loudnorm` to −14 LUFS integrated, −1 dBTP |
| Container | MP4 with `+faststart` |

**Why the preset is part of the profile:** the x264 preset changes SPS fields such as the reference frame count. In a test, `medium` and `veryfast` produced different extradata; with identical arguments, the extradata was identical. Every encode that feeds the packager must therefore use exactly the same argument list. Define it **once in code**, as a constant `List<String>`, and use it everywhere.

`veryfast` was chosen so that overlay re-encodes run faster than real time: about 2.8× real time for 1080p30 on 2 cores in testing. The cost is about 10–15% more bitrate than `medium` at the same quality, which the 6 Mb/s cap absorbs.

Reference command (video item at 1080p30; the second loudnorm pass is shown). At 720p30 use `scale=1280:720`/`pad=1280:720`, `-level:v 4.0`, `-maxrate 3M -bufsize 6M`:

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
ffmpeg -loop 1 -framerate 30 -i card.png -t 2 -vf "<scale/pad/setsar=1/fps filter>" <PROFILE_VIDEO_ARGS> -an card.mp4
```

The filter is the mezzanine's scale filter. Every card's avcC is checked against the reference card before the card is moved into place. `setsar=1` matters: a PNG without a `pHYs` chunk (Java2D writes none) has an unknown aspect ratio, x264 then omits it from the SPS, and the avcC differs from the mezzanines'.

**Reference card:** at startup the server makes sure `cards/<profile>/reference.mp4` and `reference.avcC` exist (a black card). The stored avcC is the invariant reference for that profile. Each start also encodes a fresh card; if its avcC differs (e.g. after an ffmpeg/x264 upgrade), the server warns, keeps the stored reference, and new encodes fail the check until the library is re-encoded.

The image is rendered with Java2D at the output resolution:

- **Item card:** artwork, title, artist and album. The text must fit (shrink, then ellipsize).
- **Server filler cards:** "Paused", "Queue is empty", "Loading…". Stored as `cards/<profile>/paused.mp4`, `idle.mp4` and `loading.mp4`, each with a `.key` fingerprint (pixels, encoder arguments, reference avcC); startup re-encodes only the ones whose fingerprint or avcC no longer matches.
- **Room cards:** the idle card with the room's name; with the QR overlay enabled, overlaid versions of every card ([overlay.md](overlay.md)).

Don't rely on colour alone to convey anything: use text labels and strong contrast.

## Silence

One pre-encoded **AAC silence frame** (48 kHz stereo), generated at startup from `anullsrc` and taken from after the encoder priming. The packager pairs it with cards whenever the room is paused, idle or loading.

It is encoded with the same AAC arguments as every other encode (a shared constant next to the x264 lists): 16 frames to ADTS, in memory. The priming packet, the first real frame (its MDCT window overlaps the priming) and the last frame (flush) are dropped, and the frames in between must be byte-identical; that steady-state frame is kept, without its ADTS header. Repeated, it decodes to exact digital silence.

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

- `version` is 1. `profile` codes: `1` = 720p30, `2` = 1080p30 (never renumbered).
- The file-ref table is `file count u8`, then per file `name length u16 | name` (UTF-8): a plain file name in the item directory, e.g. `mezz.mp4`, `card.mp4`, `audio.m4a`. A track's `file-ref` is its position in this table.
- Samples are in file order, which is also PTS order (no B-frames); PTS strictly increases within a track. `index.bin` is written to a temp file and renamed into place.
- The builder asks ffprobe for the stream time base in the same call (`-show_entries stream=time_base:packet=… -of csv`); the time base must be `1/N`, and `N` is the track timescale.

Get avcC with a small MP4 box walker (`moov/trak/mdia/minf/stbl/stsd/avc1/avcC`). The audio config is fixed by the profile.

## Ingest job states

`queued → running → done | failed`, with `progress` (from ffmpeg `-progress pipe:1`) and a log tail.

Steps: `probe → (download) → loudness pass 1 → encode → extract audio.m4a → card render+encode → index → verify → publish`.

- **Source:** an upload job reads `<data>/tmp/ingest/<itemId>/source[.<ext>]`, where the upload code puts the file; an optional `cover.<ext>` next to it (e.g. a YouTube thumbnail) is used as artwork instead of the embedded picture. A `youtube`/`refetch` job downloads the canonical URL into its scratch directory `<data>/tmp/jobs/<jobId>/` first. The staging directory is deleted after a successful publish and kept when the job fails, so it can be retried.
- **Probe:** a real video stream makes a video item; attached pictures and still-image codecs are cover art. No audio, or longer than `ingest.maxDurationSeconds`, fails the job.
- **Loudness pass 1:** `loudnorm=I=-14:TP=-1:LRA=11:print_format=json,aresample=48000` (the resampler only fixes ffmpeg's progress `out_time`). Silence measures `-inf`; it is encoded without normalisation and `loudness_lufs` stays null.
- **Encode:** the pass-2 filter ends with `aresample=48000:first_pts=0`, so audio that starts after the file start is padded with silence and every `audio.m4a` starts at 0 like the video (ffmpeg's `fps` filter already fills video from 0). Metadata and chapters are dropped.
- **Cover:** the staged cover, else the embedded picture, is converted to `cover.jpg` (at most 1200 px per side) and drawn on the card; without one the card gets the placeholder. A broken cover never fails the job.
- **Publish:** everything is built in `<data>/media/.ingest-<jobId>/` and swapped in with two renames (old `media/<itemId>` → `media/.old-<itemId>-<jobId>`, new → `media/<itemId>`, then the old one is deleted). At startup, before the queue starts, `.ingest-*` and `tmp/jobs/*` are deleted and a `.old-*` directory whose item directory is missing (crash between the renames) is put back. Then `items.kind`, `duration_ms` (of `audio.m4a`), `output_profile`, `loudness_lufs` (measured *I*) are written and `purged_at` cleared; the queue sets `ready`.

**Verify:** the audio ends within 50 ms of where the source's audio ends (its offset from the file start plus its duration; for Matroska/WebM, which has no stream duration, ffprobe reads the audio packets of the last 30 s), the avcC invariant holds, and keyframes fall exactly every 2 s (sample *i* is an IDR iff *i* mod 60 = 0, at PTS exactly *k*·2 s from 0).

If the output profile changes, every item that isn't purged gets a `reencode` job. Audio uploads re-encode from their original; YouTube items are fetched again; video uploads re-encode from their old mezzanine (one extra generation of loss, since their originals weren't kept).
