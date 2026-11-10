# Ingest

How media gets onto the server and into the mezzanine format. Libraries, sharing and retention are in [libraries.md](libraries.md).

## Who can add media

| Path | Users | Guests |
|---|---|---|
| Upload | `library.upload` | `guests.canUpload`, plus the room setting, and only as a request inside a room |
| YouTube | `library.youtube` and `ytdlp.enabled` | `guests.canYoutube`, plus the room setting, and only as a request inside a room |

Guest uploads are subject to `guests.uploadQuota`, counted per guest and per IP. All guest-added items start unreferenced, so retention deletes them unless the room owner adds them to a library.

- **Checks, in order** (`AddMediaPolicy`): for YouTube, `ytdlp.enabled` first (`403 feature_disabled`, for everyone). Users then need the permission (`403 forbidden`). Guests and anonymous visitors without a room get `401 not_authenticated`; with a room: `403 guests_disabled`, `403 guest_banned` (server-wide ban), `403 guest_uploads_disabled` / `guest_youtube_disabled` (server setting off), then the room's own rules (`404 room_not_found`, or a `403` from the room). Only an anonymous visitor who passes every check (and the quota) becomes a guest. A guest's later calls on an upload in progress (chunks, status, `complete`) need the server rules again, so switching guest uploads off stops uploads in progress.
- **Quota:** every upload a guest **starts** (`POST /api/uploads`) is a `guest_upload_starts` row, written in the same transaction as the upload. At most `perHour` starts in any rolling hour per guest and per IP key (IPv4 address, IPv6 /64; only its hash is stored), whichever is hit first, else `429 upload_quota_exceeded` with `Retry-After` (until the oldest counted start is an hour old). A start counts even if the upload is later refused, abandoned or deduplicated; refused requests (bad size, over quota) don't count. A file over `maxItemBytes` (or `ingest.maxUploadBytes`) gets `413 payload_too_large`. `perHour: 0` means guests can't upload (`403 guest_uploads_disabled`).
- Until rooms exist (M3), the room half is a stub that answers every room with `404 room_not_found`, so guests can't add media yet. Guests' YouTube links go through the room's `queue.add`, not `POST /api/imports/youtube`.

## Free disk space

Adding media pauses while the data volume runs low (Siggi, 2026-11-10: "we should have an admin configurable free space minimum. If free disk space drops below the minimum, all uploads are blocked."). Server setting `storage.minFreeBytes` (admin settings page, entered in GiB there; default 5 GiB; 0 turns the check off), compared with the usable space of the file system that holds `data.dir` (`FileStore.getUsableSpace()`, measured on each decision with a 2 s cache; the setting is read on each decision, so a change applies at once). Implemented in `storage/DiskSpace`.

- **Refused while below the minimum**, with `507 insufficient_storage` and the message "The server is low on disk space; adding media is paused.":
  - **starting an upload** (`POST /api/uploads`), size-aware: refused when the free space minus the declared size would fall below the minimum (so one big upload can't take the volume under it, and an upload that was let in never trips the check by itself);
  - **chunks** of an upload already started, once the free space is below the minimum. The bytes received so far stay in the part file: the upload can continue once space is free again (the web client stops it with the message, without retrying, and keeps it resumable; Library shows Retry), or the 24 h stale-upload sweep deletes it like any abandoned upload;
  - **new YouTube fetches**: a new video (before yt-dlp runs), a purged item's refetch and a failed item's retry, from every path (`POST /api/imports/youtube`, the room's `queue.add`, adding a purged YouTube item to a library or playlist). A YouTube item that is ready, pending or processing is still handed out, since nothing new is downloaded. *Reading:* Siggi said "uploads"; this takes it as every way of adding new media, because a YouTube fetch writes media just the same. Narrow it to uploads if that's not wanted.
- **Not blocked:** playback, rooms, the program output, `complete` of an upload whose bytes are all there, deleting and purging.
- **Ingest waits:** while below the minimum, the ingest workers take no new job (`IngestQueue.pauseWhile`): queued jobs, profile/bitrate `reencode` jobs and `quality` re-encodes stay `queued` (no attempt counted) and start when the free space is back above the minimum (retention purges, deletes, the admin freeing space or lowering the minimum), checked at every wake and every 10 s poll. A job that is already running carries on; one that runs out of space mid-encode fails cleanly, as before.
- **Admin view:** the settings tab of the admin page shows the free space of the data volume (of its total), the minimum, and a warning while below it (`GET /api/admin/storage`). The server log gets a warning once when the free space crosses below the minimum and an info line when it is back above. If the space can't be measured, nothing is blocked (logged once).

## Upload

1. `POST /api/uploads` returns an upload id (an `uploads` row). The client then sends the file in chunks with `PUT /api/uploads/{id}` and `Content-Range`, which makes uploads resumable. Chunks are appended in order to `tmp/uploads/<id>.part`; a chunk may repeat or overlap bytes already received (they are skipped). The SHA-256 is computed as they arrive (after a restart, once more from the part file). Uploads untouched for 24 h are deleted.
2. On `complete`:
   - If `content_sha256` matches an existing upload item that is ready, pending or processing, reuse that item and discard the upload. If the existing item is `purged` (or `failed`), re-ingest into it (same row and metadata; the response says `deduplicated: true`, since no new item is made).
   - Otherwise run ffprobe, and reject the file if it has no audio, can't be parsed, or is too long (the upload is deleted). Create the item (`pending`) and an ingest job; the file moves to the item's staging directory as `source.<ext>`.
   - Completes are serialised around the hash lookup, so two uploads of the same file make one item. The item, its job, the uploader's library entry and the upload's result are written in one transaction, and `last_referenced_at` becomes now.
3. Metadata comes from what the uploader typed in `complete`, then container tags (`title`, `artist` or `album_artist`, `album`; the first audio stream's tags count too, e.g. Ogg/Opus), falling back to the file name without its extension for the title. Cover art comes from the embedded picture.
4. The upload is kept as the item's original ([below](#originals)), for audio and video files alike. Only an audio upload's original is ever played (by the web client); a video upload's only feeds re-encodes.

## Originals

**Every item keeps its source** as `media/<itemId>/original.<ext>` (Siggi, 2026-11-10: "keep the original so that files can be re-encoded easily without generational loss"): an audio or video upload as uploaded, and a YouTube item's merged download (`src.mkv`, as yt-dlp left it), so a re-encode ([below](#re-encodes)) starts from the source instead of the previous encode, and doesn't depend on YouTube still having the video. `items.original_file` and `original_codec` are set for every item that has one.

- **Who plays it:** only an **audio item that came from an upload**: the web client plays its original instead of `audio.m4a` ([rooms.md](rooms.md#listen-mode)), with the web-playable check and loudness rules below. The original of a video item or of a YouTube item is **never served** (not to the web player, and `GET /media/{id}/original` answers `404 file_not_found` for it); it only feeds re-encodes, so its `original_web` is 0 and `original_gain_db` null. The packager always uses `audio.m4a` (and `mezz.mp4`/`card.mp4`), because it needs the fixed profiles.
- **Storage:** keeping the source roughly **doubles the disk space of a video item** (a 1080p YouTube download is about the size of the 6 Mb/s mezzanine, often more); audio uploads already kept theirs. Plan the media disk for it. The admin page shows the data volume's free space against `storage.minFreeBytes` ([Free disk space](#free-disk-space)); it doesn't break the usage down (originals separately) yet.
- **File name:** the extension comes from the container ffprobe finds, not from the upload's (or download's) name: `mp3`, `flac`, `wav`, `aac` (ADTS), `opus` (Opus in Ogg) or `ogg`, `m4a` (MP4/MOV family). Matroska keeps `.webm`, `.mka` or `.mkv` from the upload's name (else `mka`); any other container keeps a plain upload extension (`[a-z0-9]{1,10}`), else `bin`. **For a video item** the MP4/MOV family is `mp4` (or `mov`/`m4v` from the upload's name) and Matroska `mkv` (or `webm`), so a YouTube download is usually `original.mkv`.
- **Browser-playable check** (audio uploads only): ingest records the original's codec and container (`items.original_codec`, as ffprobe's `<codec_name>/<format_name>`, e.g. `flac/flac` or `aac/mov,mp4,m4a,3gp,3g2,mj2`). It is marked web-playable (`original_web`) only for MP3, AAC (in MP4/M4A or ADTS), FLAC, Opus (in Ogg, or in WebM, i.e. Matroska uploaded as `.webm`) and PCM WAV (`pcm_u8`, `pcm_s16le`, `pcm_s24le`, `pcm_s32le`, `pcm_f32le`), and only if its duration (the container's, as a player shows it) is within 50 ms of `audio.m4a` and its loudness can be matched (below). Anything else (WMA, ALAC, APE, …) is still kept, but the web client uses `audio.m4a`. The client also checks `canPlayType` before using an original.
- **Loudness** (audio uploads only): the original isn't normalised. After the encode, ingest measures the finished `audio.m4a` the same way as the source (loudnorm pass 1) and computes `original_gain_db = min(I_m4a − I, 0 − TP)` (integrated loudness *I* and true peak *TP* of the source, *I_m4a* of `audio.m4a`), so the web client plays it at the loudness `audio.m4a` actually has, using its per-element Web Audio `GainNode`, without its true peak going above 0 dBTP (the browser clips above full scale). Rounded to 0.01 dB; null if pass 1 measured silence (`audio.m4a` is then not normalised either, and the client applies no gain).
  - Why not `−14 − I`: loudnorm's `linear=true` only stays linear if the gain fits under −1 dBTP and the loudness range is at most 11 LU. Otherwise it switches to dynamic mode (compression), and `audio.m4a` ends up wherever that puts it (often not −14 LUFS). Measuring the result covers both.
  - A quiet source with high peaks (e.g. −31 LUFS at −3 dBTP) gets compressed to about −14 LUFS, which a plain gain can't match without clipping. If the 0 dBTP cap leaves the original more than 0.5 dB quieter than `audio.m4a`, it is kept but **not** web-playable (`original_web` 0), so the client plays `audio.m4a`; the job log says why.
- The SHA-256 used for dedupe is the original's hash, so a re-upload of the same file matches.
- Retention and purge delete the original together with the rest of `media/<itemId>/`; a purged YouTube item is refetched as before (and keeps the new download as its original).
- **Keeping it:** an upload's staged file is hard-linked into the new item directory (copied if that fails); a download is moved out of the job's scratch directory. Re-ingesting an item replaces its original with the new source.

## YouTube caching

The YouTube **video ID** is the cache key. Every YouTube item stores it in `items.youtube_id`, which is unique.

1. **Normalise** the pasted URL to the 11-character video ID before anything else. Accept `youtube.com/watch?v=`, `youtu.be/`, `/shorts/`, `/embed/`, `/live/`, `music.youtube.com`, and `m.` variants (also `/v/` and `youtube-nocookie.com/embed/`; with or without `https://`); ignore `t=`, `list=` and tracking parameters. Reject anything that doesn't yield an ID, including a bare ID without a link. The pasted URL is never passed to yt-dlp: every yt-dlp call uses the canonical `https://www.youtube.com/watch?v=<id>`.
2. **Look up** `items.youtube_id`:
   - `ready` → use the cached item immediately. No network access at all.
   - `pending` / `processing` → attach the request to the job that's already running. The queue entry shows "processing", and becomes playable when the job finishes.
   - `purged` → start a `refetch` job on the **same row**. History links stay valid.
   - `failed` → retry (a new `youtube` job on the same row), if the last attempt ended at least 10 minutes ago; until then the import is refused with `422 youtube_failed` and `Retry-After`.
   - not found → create the item and an ingest job.

   Every accepted import bumps `last_referenced_at` and, for a user, adds the item to their library (`via='youtube'`) unless `addToLibrary` is false. Imports of the same ID are serialised, so concurrent requests for a new video run `-J` once and make one item.
3. Only on a cache miss: fetch metadata with `yt-dlp -J --no-playlist <canonical url>`, synchronously in the import request. Reject live streams (`is_live`, or `live_status` `is_live`, `is_upcoming` or `post_live`) and videos over the duration limit; a video yt-dlp can't read is refused with yt-dlp's `ERROR:` message. Nothing is created for a refused import. The new item is a `pending` `video` item with the metadata's duration until ingest measures it; `metadata_json` keeps `{"youtube": {id, uploader, durationSec}}`.
4. Download with `yt-dlp --no-playlist -f "bv*[height<=1080]+ba/b" --merge-output-format mkv -o <tmp>/src.%(ext)s https://www.youtube.com/watch?v=<id>`. Always download from the canonical URL built from the ID. The same call writes the best thumbnail (`--write-thumbnail -o "thumbnail:<tmp>/cover.%(ext)s"`, usually WebP), which becomes `cover.jpg` and the card's artwork.
5. Title and artist come from `track`/`artist` when present (yt-dlp's newer `artists` list counts as `artist`), otherwise from `title`/`uploader` (or `channel`). An uploader named "<name> - Topic" (YouTube's auto-generated music channels) becomes "<name>". The album comes from `album`. Values are cleaned like upload metadata. Cover art is the best thumbnail.

Because one ID maps to one item, the "audio only" choice is not a separate item. Every video item can be **presented as audio**: an item flag `presentAsAudio` (`items.present_as_audio`) makes the packager use the item's card instead of its video. It's useful for "Topic" uploads, which are just a still image. Anyone who can edit the item's metadata can set it, and the importer can set it at import time: when the import creates the item, or brings a purged or failed one back. Importing an item that already exists ignores it (changing it is a metadata edit).

Operational notes:

- YouTube often blocks datacenter IPs. Support an optional cookies file: the `ytdlp.cookiesFile` setting (a Netscape-format cookies file on the server), passed as `--cookies` to every `-J` and download. It is read for each call, so a change applies to the next import. A path that isn't a readable file is logged and ignored. yt-dlp writes refreshed cookies back to the file, so the server should be able to write it.
- Keep yt-dlp up to date: an admin button runs `yt-dlp -U`.
- Surface yt-dlp's stderr in the job log.

## The mezzanine profile

Every item is transcoded at ingest to one fixed profile, so the packager can splice any item next to any other with **byte-identical SPS/PPS and audio config**.

| Parameter | Value |
|---|---|
| Video codec | H.264 High, level 4.1 (1080p30) / 4.0 (720p30), yuv420p, SAR 1:1 |
| x264 preset | Admin's choice per tier ([Presets](#presets)): `ingest.quickPreset` (default **veryfast**) for items a room wants now, `ingest.qualityPreset` (default **slow**) for library items. Cards (and the reference card) always use veryfast. Every preset is pinned to the same SPS/PPS with `ref=4` and a per-preset `chroma-qp-offset` |
| Resolution | 1280×720 (default) or 1920×1080, server-wide (`output.profile`). Scale to fit, then pad to the exact size with black |
| Frame rate | Constant 30 fps (the `fps=30` filter converts other rates) |
| GOP | Closed, IDR exactly every 2 s (60 frames), forced IDR at t=0, no scene-cut keyframes |
| B-frames | **None** (`-bf 0`), so PTS = DTS |
| Rate control | **Constant bitrate** `R` = `output.videoBitrateKbps` (default 3000 at 720p30, 6000 at 1080p30): `-b:v R -minrate R -maxrate R -bufsize R/2` with `-x264-params open-gop=0:nal-hrd=cbr:vbv-init=1`. x264 pads every frame up to the rate with H.264 filler data NAL units (type 12), so every 2 s GOP, a still card included, is `R × 2 s / 8` bytes |
| Audio | AAC-LC, 48 kHz, stereo, 192 kb/s |
| Loudness | Two-pass EBU R128 `loudnorm` to −14 LUFS integrated, −1 dBTP |
| Container | MP4 with `+faststart` |

**Why the preset is part of the profile:** the x264 preset changes parameter-set fields: the SPS's `num_ref_frames` and the PPS's `chroma_qp_index_offset`. In a test, `medium` and `veryfast` with otherwise identical arguments produced different extradata. Every encode that feeds the packager must therefore use exactly the same argument list, defined **once in code** (`EncodeProfile.videoArgs`), **plus** a small per-preset table (`X264Preset`: the preset name and its chroma offset), so that every preset writes the same avcC. See [Presets](#presets).

`veryfast` (the default quick preset, and the preset of every card) runs fast enough that overlay re-encodes run faster than real time: about 2.8× real time for 1080p30 on 2 cores in testing. The cost is about 10–15% more bitrate than `medium` at the same quality, which at a constant bitrate shows as lower quality; hence the quality preset for library items.

## Presets

Added 2026-11-10 (Siggi: "Allow the server admin to choose the preset ... two options -- one for quick encoding, to get a recently requested item to play as quickly as possible, and then another for slower encoding to get better quality, which can start if the room owner (or if someone else on the same server happens to paste the same YouTube link) decides they want to add the item to their library.").

**Settings:** `ingest.quickPreset` (default `veryfast`) and `ingest.qualityPreset` (default `slow`), in the config file (`ingest.*`, env `TOGETHERTUNES_INGEST_QUICK_PRESET` / `..._QUALITY_PRESET`) and in the admin settings, which override the config. Allowed: `veryfast`, `faster`, `fast`, `medium`, `slow`, `slower`, `veryslow`. Not `ultrafast`/`superfast`: they turn off CABAC and the 8×8 transform, which changes the PPS. Changing a preset applies to the next job; it never re-encodes existing items by itself.

**One avcC for all presets** (invariant 1). Measured 2026-11-10 with ffmpeg 6.1.1 (720p30 at 3 Mb/s and 1080p30 at 6 Mb/s, CBR, `-bf 0 -g 60 -profile:v high`, a black still and `testsrc2`): presets differ in the SPS by `num_ref_frames` and in the PPS by `chroma_qp_index_offset` (x264 lowers the configured offset by 2 when psy-rd is active, i.e. subme ≥ 6: preset `fast` and slower). With `ref=4` pinned and `chroma-qp-offset=-2` for veryfast/faster but `0` for fast…veryslow (all end up at −2), **all seven presets give byte-identical SPS and PPS**. `ref=4` is part of the shared constant (it fits level 4.1's DPB at 1080p). Pinning it changed the avcC of every encode, so the encoding id gained an encoder-arguments version, `-v2` (e.g. `720p30-3000k-v2`): every item made before re-encodes once at the next start, against a new reference card in `cards/720p30-3000k-v2/`.

**Don't hard-trust the table:** at startup the server encodes the reference card's black still with each **configured** preset and compares the avcC with the reference. A preset that doesn't match (e.g. another x264 build) is logged and not used: the quick tier falls back to veryfast (which made the reference), the quality tier to the quick tier's preset. An admin who picks such a preset in the settings gets `400 invalid_setting` with an explanation. Results are cached for the life of the server. The per-encode avcC check stays.

**Which preset a job uses** (decided when the job starts):

- A new item (upload, YouTube import, refetch) uses the **quick** preset when it is wanted in a room: the job was queued without a library entry (a request or paste in a room, a guest, a playlist), or a room has the item queued, suggested or playing when the job starts. It uses the **quality** preset directly when it was added straight to a library and no room wants it (`ingest_jobs.tier` records what the caller asked for, because a room's queue entry is only written after the import or upload has queued its job).
- A **quick-encoded** item gets a `quality` job as soon as it is `ready` and in any library: added by the room owner, by anyone pasting the same YouTube link into their library, by an upload dedupe, a share, …, checked when the item's job finishes, when it is added to a library, and at startup (which also retries failed quality jobs). It re-encodes from the item's [original](#originals) (a YouTube item without one downloads again; an item with neither is left as it is, since re-encoding its quick encode would only lose quality).
- Each item records the preset its files were made with (`items.encode_preset`) and whether they are its quality encode (`items.encode_quality`: the preset was the quality preset of the time). A quality encode never gets another one; equal quick and quality presets mean the first encode already is the quality encode, so there is no second job. Audio items have no video to improve: their encode always counts as the quality encode.
- A profile/bitrate `reencode` uses the quality preset for an item that is in a library and not wanted in a room, else the quick preset (a quality job follows if needed).

**Priorities** (queued items first added 2026-11-10, Siggi: "Items that in a queue should also be prioritized."): the claim order is decided at every claim from the rooms' live state, not frozen when a job is queued, so queueing an item later moves its pending job forward.

1. **Jobs of items a room is waiting on**, of any kind, before every job for an item no room wants: a `reencode` or `quality` job of a queued item (a re-encode makes the program output show "Loading…" for that item until it finishes) goes ahead of an unqueued upload. "Waiting on" means any loaded room's current item, its queue, or its suggestions (they would play once accepted). Among them: a room's current item first, then by how soon it plays (its place in its room's play order, the suggestions after the queue; the earliest across rooms). The rooms publish this ranking from their executors with the current/next live set (`LivePlayingItems`, `WantedItems`); at most 500 ranked items go into the claim query. Items in a queue in the database whose room isn't loaded (e.g. nobody opened it since a restart; not `played`/`skipped`/`removed` entries) come after the ranked ones but still before unwanted items.
2. Then the **kind order**: new-item jobs (upload, YouTube, refetch), then `reencode`, then `quality`.
3. Then oldest first.

A `quality` job of a queued item needs no special case: its publish waits while the item is current or next ([Swap while playing](#presets)).

**Give way:** when every worker is busy and the job first in this order comes **strictly before** a running `reencode` or `quality` job (comparing step 1 and whether it is a new-item job, ignoring age), that running job is interrupted and put back in the queue without counting the attempt (it starts over later); of several, the one that comes last goes (the most recently started among equals). So a queued item's job may interrupt a job for an item no room wants, never the reverse; a sooner-playing item's job interrupts a later one's; and among equals a new-item job interrupts a re-encode (as before), while a `reencode` never interrupts a `quality` job. Checked whenever a job is queued and whenever the rooms' ranking changes. A worker that has just claimed a re-encode while a job that comes strictly before it waits puts it back at once. New-item jobs are never interrupted.

**Swap while playing:** a `reencode` or `quality` job publishes like any re-encode (the item stays `ready` with its old files until the directory swap), but it **waits to publish while the item is the current or next entry of any room** (the rooms' live set), polling every 5 s, at most 1 h (then it publishes anyway, e.g. for a room left paused on the item). So a web player never mixes byte ranges of the old and the new `mezz.mp4` under the same URL, and a preloaded next item stays valid. The program output would be safe either way: it reads the files it opened, which the rename leaves readable until it closes them, and every preset has the same avcC. An item played outside a room (the library's item page) may still see the swap.

**Why constant bitrate, and why the bitrate is part of the profile** (2026-11-10, Siggi: "It should be constant bitrate (preferably configurable), and card view must also be at that constant bitrate"): the program output (MPEG-TS, HLS and the RTMP push, which copies the stream into FLV) carries a steady `R` whether an item or a card is showing. Filler NALs are the standard H.264 padding; the packager passes them through unchanged (AVCC → Annex B keeps every NAL except in-band AUD/SPS/PPS), `-c copy` to FLV keeps them, and pacing stays by timestamp. `nal-hrd=cbr` writes the HRD parameters (rate and buffer size) into the SPS's VUI, so **another bitrate is another avcC**: the reference card, the cards directory, `items.output_profile` and `index.bin` are all per *encoding* = profile + bitrate, named like `720p30-3000k-v2` (the `-v2` is the encoder-arguments version, [Presets](#presets)), and changing `output.videoBitrateKbps` re-encodes the library exactly like a profile change ([Re-encodes](#re-encodes)). Allowed range 500–25000 kb/s (25 Mb/s is level 4.0's High-profile MaxBR). Audio stays AAC 192 kb/s.

**Buffer size**, measured 2026-11-10 with ffmpeg 6.1.1 at 720p30, 3 Mb/s, bytes per 2 s GOP against 750 000: with `-bufsize` = 0.5 s and `vbv-init=1` (the buffer starts full), a still card came out +0.1 %, `testsrc2` within ±0.01 %, a noisy fractal source −1…+2 %. Without `vbv-init=1` (x264 starts 90 % full) a card was −2.4 %; with a 1 s buffer the noisy source spread −2…+6 % and `testsrc2` −5…+10 %; with 2 s (the old cap's `2×`), −10…+18 %. Half a second still lets an IDR take up to half a second's worth of bits (~187 KB at 3 Mb/s). `vbv-init` isn't in the SPS (it only changes the buffering-period SEI), so it doesn't affect the avcC.

Reference command (video item at 1080p30 at the default 6000 kb/s with the default quality preset `slow`; the second loudnorm pass is shown; with `veryfast` or `faster` use `chroma-qp-offset=-2`). At 720p30 use `scale=1280:720`/`pad=1280:720`, `-level:v 4.0` and the 720p rate (default `3000k`, buffer `1500k`):

```sh
ffmpeg -i src -map 0:v:0 -map 0:a:0 \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease:force_divisible_by=2:out_color_matrix=bt709:out_range=tv,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30" \
  -c:v libx264 -preset slow -profile:v high -level:v 4.1 -pix_fmt yuv420p \
  -color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 -chroma_sample_location left -bf 0 \
  -g 60 -keyint_min 60 -sc_threshold 0 -force_key_frames "expr:gte(t,n_forced*2)" -x264-params open-gop=0:nal-hrd=cbr:vbv-init=1:ref=4:chroma-qp-offset=0 \
  -b:v 6000k -minrate 6000k -maxrate 6000k -bufsize 3000k \
  -af "loudnorm=I=-14:TP=-1:LRA=11:measured_I=…:measured_TP=…:measured_LRA=…:measured_thresh=…:offset=…:linear=true,aresample=48000" \
  -c:a aac -b:a 192k -ar 48000 -ac 2 \
  -movflags +faststart mezz.mp4
```

**The colour description is part of the profile:** x264 copies the source's colour tags (range, primaries, transfer, matrix) and chroma sample location into the SPS's VUI. Without the fixed `-color_*`/`-chroma_sample_location` options, a BT.709-tagged source (every YouTube download), a full-range or BT.601 source, or Motion JPEG (centre-sited chroma) would each produce a different avcC and fail the invariant check. The scale filter converts the pixels to limited-range BT.709 to match. HDR sources are not tone-mapped (they come out washed out, but valid).

Then `audio.m4a` = `ffmpeg -i mezz.mp4 -map 0:a -c copy -movflags +faststart audio.m4a`.

For **audio items**, encode only the audio, straight to `audio.m4a`; there is no `mezz.mp4`. **Every** item gets a `card.mp4` (below), including video items, so that `presentAsAudio` and the transition card work.

**Invariant check (mandatory):** compare each encode's avcC with the server's reference card for the profile, and fail the job if the bytes differ.

## Cards

A card is a still image encoded as one 2 s GOP with the **same encoder arguments** as the mezzanine: one IDR followed by 59 P-frames. The picture data is small (P-frames of about 70 bytes), but CBR pads every frame with filler NALs, so a card is `R × 2 s / 8` bytes like any other GOP: about 750 KB at 3 Mb/s, 1.5 MB at 6 Mb/s, almost all of it filler. Card view therefore runs at the same constant bitrate as items.

```sh
ffmpeg -loop 1 -framerate 30 -i card.png -t 2 -vf "<scale/pad/setsar=1/fps filter>" <PROFILE_VIDEO_ARGS> -an card.mp4
```

The filter is the mezzanine's scale filter. Every card's avcC is checked against the reference card before the card is moved into place. `setsar=1` matters: a PNG without a `pHYs` chunk (Java2D writes none) has an unknown aspect ratio, x264 then omits it from the SPS, and the avcC differs from the mezzanines'.

**Reference card:** at startup the server makes sure `cards/<encoding>/reference.mp4` and `reference.avcC` exist (a black card), e.g. `cards/720p30-3000k-v2/`. The stored avcC is the invariant reference for that profile and bitrate. Directories of encodings no longer configured are left alone (they are small; delete them by hand). Each start also encodes a fresh card; if its avcC differs (e.g. after an ffmpeg/x264 upgrade), the server warns, keeps the stored reference, and new encodes fail the check until the library is re-encoded.

The image is rendered with Java2D at the output resolution:

- **Item card:** artwork, title, artist and album. The text must fit (shrink, then ellipsize).
- **Server filler cards:** "Paused", "Queue is empty", "Loading…". Stored as `cards/<encoding>/paused.mp4`, `idle.mp4` and `loading.mp4`, each with a `.key` fingerprint (pixels, encoder arguments incl. the bitrate, reference avcC); startup re-encodes only the ones whose fingerprint or avcC no longer matches.
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
magic "MSIX" | version u16 | profile u16 | video bitrate u32 (kb/s) | file-ref table
track count u8
per track:
  kind u8 (0=video,1=audio) | timescale u32 | file-ref u8 | sample count u32
  per sample: offset u64 | size u32 | pts i64 (track timescale) | duration u32 | flags u8 (bit0=keyframe)
avcC length u16 | avcC bytes
```

- `version` is 2. `profile` codes: `1` = 720p30, `2` = 1080p30 (never renumbered); with the bitrate they name the encoding the files were made for. Version 1 (before CBR, no bitrate field) is rejected, so such an item counts as not ready until its re-encode has run.
- The file-ref table is `file count u8`, then per file `name length u16 | name` (UTF-8): a plain file name in the item directory, e.g. `mezz.mp4`, `card.mp4`, `audio.m4a`. A track's `file-ref` is its position in this table.
- Samples are in file order, which is also PTS order (no B-frames); PTS strictly increases within a track. `index.bin` is written to a temp file and renamed into place.
- The builder asks ffprobe for the stream time base in the same call (`-show_entries stream=time_base:packet=… -of csv`); the time base must be `1/N`, and `N` is the track timescale.

Get avcC with a small MP4 box walker (`moov/trak/mdia/minf/stbl/stsd/avc1/avcC`). The audio config is fixed by the profile.

## Ingest job states

`queued → running → done | failed`, with `progress` (from ffmpeg `-progress pipe:1`) and a log tail.

Steps: `probe → (download) → loudness pass 1 → encode → extract audio.m4a → card render+encode → index → verify → publish`.

- **Source:** an upload job reads `<data>/tmp/ingest/<itemId>/source[.<ext>]`, where the upload code puts the file; an optional `cover.<ext>` next to it (e.g. a YouTube thumbnail) is used as artwork instead of the embedded picture. A `youtube`/`refetch` job downloads the canonical URL into its scratch directory `<data>/tmp/jobs/<jobId>/` first. Either becomes the item's `original.<ext>` ([Originals](#originals)). The staging directory is deleted after a successful publish and kept when the job fails, so it can be retried.
- **Probe:** a real video stream makes a video item; attached pictures and single still images in an image codec (MJPEG, PNG, …) are cover art. An image codec with more than one frame (`nb_frames`, or Matroska's `NUMBER_OF_FRAMES` tag; if unknown, a `duration`/`DURATION` tag over 0.5 s) is video, e.g. Motion JPEG from a camera. No audio, or longer than `ingest.maxDurationSeconds`, fails the job.
- **Loudness pass 1:** `loudnorm=I=-14:TP=-1:LRA=11:print_format=json,aresample=48000` (the resampler only fixes ffmpeg's progress `out_time`). Silence measures `-inf`; it is encoded without normalisation and `loudness_lufs` stays null.
- **Encode:** the pass-2 filter ends with `aresample=48000:first_pts=0`, so audio that starts after the file start is padded with silence and every `audio.m4a` starts at 0 like the video (ffmpeg's `fps` filter already fills video from 0). Metadata and chapters are dropped.
- **Cover:** the staged cover, else the embedded picture, is converted to `cover.jpg` (at most 1200 px per side) and drawn on the card; without one the card gets the placeholder. A broken cover never fails the job.
- **Publish:** everything is built in `<data>/media/.ingest-<jobId>/` and swapped in with two renames (old `media/<itemId>` → `media/.old-<itemId>-<jobId>`, new → `media/<itemId>`, then the old one is deleted). At startup, before the queue starts, `.ingest-*`, `tmp/jobs/*` and the `.purge-*` directories a purge left behind (in `media/` and `tmp/ingest/`) are deleted and a `.old-*` directory whose item directory is missing (crash between the renames) is put back. Then `items.kind`, `duration_ms` (of `audio.m4a`), `output_profile`, `loudness_lufs` (measured *I*) are written and `purged_at` cleared; the queue sets `ready`.

**Verify:** the audio ends within 50 ms of where the source's audio ends (its offset from the file start plus its duration; for Matroska/WebM, which has no stream duration, ffprobe reads the audio packets of the last 30 s; if the encode doesn't match that but is within 50 ms of the length loudness pass 1 actually decoded, its last `out_time`, it passes too, because some decoders such as WMA output more than the container declares), the avcC invariant holds, and keyframes fall exactly every 2 s (sample *i* is an IDR iff *i* mod 60 = 0, at PTS exactly *k*·2 s from 0).

### Encode jobs page

Added 2026-11-10 (Siggi: "Also allow non-admins to see encodes for items that appear in their own library."). Two views of the same rows, built on `ingest_jobs` joined with `items` (`JobList`, `JobsApi`; API in [api.md](api.md#admin)):

- **Admin** (`/admin/`, Jobs tab; permission `admin.jobs`, [auth.md](auth.md#roles-and-permissions)): every job. **Retry** a failed job: a new job of the same kind and tier for the item (an upload retries from its staging directory, which a failed job keeps; a YouTube job downloads again). Only the item's latest job can be retried (`409 job_superseded`), and not once the item is purged (`409 item_purged`; a YouTube item comes back by adding it to a library). **Cancel** a queued job (running jobs can't be cancelled): it becomes `failed` with a log line saying so. A new item's ingest (`upload`, `youtube`, `refetch`) then leaves the item **`failed`** with the error "Cancelled by an administrator", retryable from the Failed list; a `reencode` or `quality` job is only dropped and the item stays `ready` (both are queued again at the next start while the item still needs them, like failed ones). Both actions are audited (`job.retried`, `job.cancelled`; target the job id, details `itemId` and `kind`). The job log is fetched per job (`GET /api/admin/jobs/{id}`), never in the lists.
- **User** (`/encodes/`, linked from the nav and the Library page; any signed-in user, guests and anonymous visitors get `401`): only jobs of items **in the caller's library**, from any library entry (uploaded, imported, added, shared with them; *reading:* PLAN said "not shares from others", but Siggi's words are "items that appear in their own library", and a shared item does; flip `JobList.IN_LIBRARY` to `via <> 'share'` if shares should be left out). No logs, no actions, nothing about who added an item or whose libraries hold it, no IP.
- **Lists** (`?state=`): `active` (default): running jobs (longest running first), then the queue in the order the workers take it right now ([Priorities](#presets): the same `ORDER BY` as the claim, `IngestJobs.claimOrder`, so queued items' jobs show first), each queued job with its 1-based `position` in the whole queue (in a user's list too, so it shows how many jobs are ahead without naming them), plus `paused` while low disk space holds the queue back. `failed`: failed jobs that are still their item's latest job and whose item isn't purged (a failure a later job replaced is history), latest first. `done`: jobs that finished in the last 24 h, latest first (folded away on the page). Offset cursors.
- **Live updates:** a short poll (every 3 s while the page is visible and the list shown). There is no suitable push channel: the only WebSocket is a room's.

### Re-encodes

If the output profile or the video bitrate changes, every item that isn't purged gets a `reencode` job (items from before CBR stored the bare profile name, e.g. `720p30`, in `output_profile`, so they re-encode once to CBR). Every item re-encodes from its kept [original](#originals). Items ingested before originals were kept for every item (2026-11-10) have none for video: their YouTube items are fetched again, their video uploads re-encode from their old mezzanine (one extra generation of loss).

- **When:** at startup, before the workers start, and when an admin changes the encoding ([below](#changing-the-encoding-at-runtime)), every `ready` item whose `output_profile` differs from the current encoding (e.g. `720p30-3000k-v2`) gets a `reencode` job unless it already has an unfinished job. Pending, processing and failed items are skipped (their next ingest uses the current profile anyway), and so are purged ones. A re-encode that failed is therefore tried again at the next start.
- **Order:** among jobs for items no room wants, workers take `reencode` jobs only when no new-item job is queued, and they give way to one that arrives while every worker is busy, so a library re-encode doesn't hold up new uploads and imports; `quality` jobs come after `reencode` jobs. A re-encode of an item a room is waiting on comes before all of those ([Priorities](#presets)).
- **Sources:** an item with its original file re-encodes from it (loudness measured again, the original and its `original_*` columns kept), YouTube items included: no download. Without one (made before every item kept its original), a YouTube item downloads again and keeps that download as its original; if the download fails (e.g. the video is gone), it falls back to its old files. Anything else (an older video upload, an audio item without its original) re-encodes from its old `mezz.mp4`, or `audio.m4a` for an audio item, and its `original_*` columns are cleared. Old files were already normalised, so they are encoded without loudnorm and the stored `loudness_lufs` is kept. The item's `cover.jpg` is reused for the card. `ingest.maxDurationSeconds` doesn't apply to re-encodes.
- **Preset:** the quality preset for an item in a library that no room wants now, else the quick preset ([Presets](#presets)).
- **While it runs** the item stays `ready` with its old files, which the web player can keep using; the new files are published only once the item isn't current or next in any room. Their `index.bin` and avcC belong to the old profile or bitrate, so the packager must not splice them: it checks the index's profile code and bitrate (and the avcC) and treats such an item as not ready (Loading card, or skip). A job for an item that is no longer `ready`, or already encoded for the current profile, ends without doing anything.

### Changing the encoding at runtime

`output.profile` and `output.videoBitrateKbps` are admin settings (the config file gives their defaults, [auth.md](auth.md#server-settings)), so the encoding can change while the server runs (added 2026-11-10). Nothing needs a restart:

- **Ask first:** before saving either setting, the admin page calls `GET /api/admin/encoding` with the new values and confirms: "Change the output encoding from A to B? N items will re-encode …". Saving the same encoding again does nothing.
- **Cards first:** after the save, the server makes (or checks) the new encoding's reference card and filler cards in `cards/<new id>/` (seconds) while everything keeps using the old ones (`ingest.OutputAssets`). If the reference card can't be made, the old encoding stays in use and the server log says why. The old encoding's cards stay on disk, so switching back is quick.
- **Then swap:** the new encoding becomes current at once for new ingest jobs (each job reads it when it starts), new items' `output_profile`, and the x264 preset check (presets are checked again against the new reference card). Every `ready` item of another encoding gets a `reencode` job, as at startup ([Re-encodes](#re-encodes)), and the workers are woken. A job already running when the switch happened finishes for the old encoding; when it finishes, its item gets a `reencode` job right away (`IngestJobs.currentEncoding`).
- **Program outputs:** a running output can't change its SPS or cards midway (invariants 1 and 3), so every running output restarts on the new encoding right after the swap (`ProgramOutputs.restartRunning`): its packager is replaced like after a server restart, `.ts` connections end (players reconnect), the RTMP push reconnects, HLS clients see a new stream. From then on, an item whose files are still of the old encoding counts as not ready (Loading card, as for any re-encode in progress) until its `reencode` job has published the new files. Web players are not affected (they play `mezz.mp4`/`audio.m4a` directly).
- **Other settings:** `ingest.workers` resizes the worker pool at once (growing starts workers, shrinking lets running jobs finish and the extra workers stop before their next job); `ingest.maxUploadBytes` and `ingest.maxDurationSeconds` apply to the next upload or import; `overlay.workers` and `overlay.cacheMaxBytes` are stored for the overlay encoder (M5).
