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

1. `POST /api/uploads` returns an upload id (an `uploads` row), or, on an https server, finishes at once when the file's content ID is already known ([Pre-check](#pre-check)). The client then sends the file in chunks with `PUT /api/uploads/{id}` and `Content-Range`, which makes uploads resumable. Chunks are appended in order to `tmp/uploads/<id>.part`; a chunk may repeat or overlap bytes already received (they are skipped). The SHA-256 is computed as they arrive (after a restart, once more from the part file). Uploads untouched for 24 h are deleted.
2. On `complete`:
   - If `content_sha256` (or the [content ID](#content-id)) matches an existing upload item that is ready, pending or processing, reuse that item and discard the upload. If the existing item is `purged` (or `failed`), re-ingest into it (same row and metadata; the response says `deduplicated: true`, since no new item is made).
   - Otherwise run ffprobe, and reject the file if it has no audio, can't be parsed, or is too long (the upload is deleted). Create the item (`pending`) and an ingest job; the file moves to the item's staging directory as `source.<ext>`.
   - Completes are serialised around the hash lookup, so two uploads of the same file make one item. The item, its job, the uploader's library entry and the upload's result are written in one transaction, and `last_referenced_at` becomes now.
3. Metadata comes from what the uploader typed in `complete`, then container tags (`title`, `artist` or `album_artist`, `album`; the first audio stream's tags count too, e.g. Ogg/Opus), falling back to the file name without its extension for the title. Cover art comes from the embedded picture.
4. The upload is kept as the item's original ([below](#originals)), for audio and video files alike. Only an audio upload's original is ever played (by the web client); a video upload's only feeds re-encodes.

### Content ID

(Siggi, 2026-11-10: "Let's add the hash checking … No hand-written SHA-256.") Besides `content_sha256`, every upload item records a **content ID** (`items.content_id`, migration 0012) that a browser can compute with `crypto.subtle.digest` alone. `crypto.subtle` hashes only a whole buffer, which doesn't work for multi-GB files, so the content ID is a **chunked SHA-256**:

- cut the file into consecutive **8 MiB pieces** (8 388 608 bytes; the last one shorter, never empty);
- SHA-256 each piece; SHA-256 the concatenation of those 32-byte digests;
- write it with the file size as **`sha256c8m:<size>:<hex>`** (decimal size, lower-case hex), e.g. `sha256c8m:3:4f8b42c2…` for the bytes `abc`.

The browser reads the `File` piece by piece with `slice()` (`web/src/contentid.ts`; bounded memory, "Checking the file… 40 %"); the server computes the same ID (`library/ContentId`, Java `MessageDigest`, no extra dependency) while the chunks arrive, next to the plain SHA-256, and stores both when an upload completes (a re-ingest into a purged/failed row, or a dedupe onto an item without one, fills it in). Dedupe on completion matches either. **Backfill:** at startup a low-priority background thread (`ContentIdBackfill`) records the ID of every upload item that lacks one and still has its kept original (`media/<itemId>/original.<ext>`, the uploaded bytes), if the file's SHA-256 matches `content_sha256`; purged items have no file and keep only `content_sha256`. YouTube items have no content ID.

### Pre-check

`POST /api/uploads` takes an optional **`contentId`** (and `addToLibrary`, as `complete` does). If it names an **upload item that is ready, pending or processing** (and the size in the ID is the declared `size`), the server answers **`200 {uploadId, chunkSize, size, deduplicated: true, itemId, status}`** without any bytes, and does exactly what completing that upload would have done: the uploader's library entry (`via='upload'`, unless `addToLibrary` is false; users only), `last_referenced_at` = now, and the upload row is recorded as completed with that item, so `queue.add {uploadId}` in a room adds it with the same request rules (queue entry or suggestion) as any upload. It goes through the same permission, size, free-space and quota checks as a real upload start: **a guest's pre-check counts as an upload start** against `guests.uploadQuota`. A purged or failed match, no match, a malformed ID or a wrong size → a normal upload (`201`); the server hashes the bytes on completion as always, so a wrong claimed ID only means no shortcut. The reply never says whose libraries hold the item.

Why uploads are coupled to library requests: with a pre-check, anyone may *claim* to hold a file by sending its content ID and get the existing item without uploading it, which would get around `libraryRequests` off. So uploads are only allowed where library requests are: `uploadRequests` on needs `libraryRequests` on in a room, and `guests.canUpload` on needs `guests.canRequestLibrary` on, in the room and on the server ([rooms.md](rooms.md#uploads-need-library-requests), [auth.md](auth.md#guest-policy-server-settings)).

**HTTPS only** (Siggi: "The server is set up with HTTPS or the feature is disabled. If someone asks me for help with this on their instance, I'll just tell them HTTP non-TLS is not a supported use case."): the server honours `contentId` only when `http.publicBaseUrl` is `https://` (`Main.uploadPrecheck`); otherwise it ignores it and every upload is a normal one. The client sends one only from an `https:` page in a secure context with `crypto.subtle` (`precheckAvailable()`; pages are served from the base URL's origin, which the CSRF check requires anyway, so an https page means an https base URL). There is **no fallback hasher**: over plain HTTP (even `http://localhost`, which browsers count as a secure context) uploads simply send all bytes. Deployment notes must say so.

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
3. Only on a cache miss: fetch metadata with `yt-dlp -J --no-playlist --no-warnings --verbose <canonical url>`, synchronously in the import request (`--verbose` only makes `-J` print its progress lines to stderr, which the lookup's [live stages](rooms.md#adding-a-youtube-link) follow; stdout stays the JSON, and the error message is taken from yt-dlp's last `ERROR:` line even when a traceback follows it). Reject live streams (`is_live`, or `live_status` `is_live`, `is_upcoming` or `post_live`) and videos over the duration limit; a video yt-dlp can't read is refused with yt-dlp's `ERROR:` message. Nothing is created for a refused import. The new item is a `pending` `video` item with the metadata's duration until ingest measures it; `metadata_json` keeps `{"youtube": {id, uploader, durationSec}}`.
4. Download with `yt-dlp --no-playlist -f "bv*[height<=1080]+ba/b" --merge-output-format mkv -o <tmp>/src.%(ext)s https://www.youtube.com/watch?v=<id>`. Always download from the canonical URL built from the ID. The same call writes the best thumbnail (`--write-thumbnail -o "thumbnail:<tmp>/cover.%(ext)s"`, usually WebP), which becomes `cover.jpg` and the card's artwork.
5. Title and artist come from `track`/`artist` when present (yt-dlp's newer `artists` list counts as `artist`), otherwise from `title`/`uploader` (or `channel`). An uploader named "<name> - Topic" (YouTube's auto-generated music channels) becomes "<name>". The album comes from `album`. Values are cleaned like upload metadata. Cover art is the best thumbnail.

Because one ID maps to one item, the "audio only" choice is not a separate item. Every video item can be **presented as audio**: an item flag `presentAsAudio` (`items.present_as_audio`) makes the packager use the item's card instead of its video. It's useful for "Topic" uploads, which are just a still image. Anyone who can edit the item's metadata can set it, and the importer can set it at import time: when the import creates the item, or brings a purged or failed one back. Importing an item that already exists ignores it (changing it is a metadata edit).

### Retrying a failed YouTube item

Added 2026-11-10 (Siggi: "If a YouTube yt-dlp item fails to ingest, there should be a retry button both on its page and where it's listed in the queue."). YouTube failures are often temporary (network, throttling, an outdated yt-dlp), so a failed YouTube item can be fetched again on request instead of waiting the 10 minutes an import of its link waits: **Retry** on the item page (`POST /api/items/{id}/retry`) and on its queue entry or suggestion in a room (`queue.retry`, [rooms.md](rooms.md#room-state)); API in [api.md](api.md#items-libraries-playlists-sharing). `YoutubeImports.retry`:

- **What it does:** a new `youtube` job on the same row (a `refetch` if the failed job was one), which makes the item `pending` again; the preset tier is decided when the job starts (quick while a room waits for it). The rooms that have it hear about the status change, so its entries show "Waiting to download…" again. It runs under the same per-video lock as imports.
- **Refused:** not a YouTube item (`409 not_youtube`: **uploads aren't covered**, a failed upload's file isn't kept, so the uploader uploads it again; the item page says so instead of showing a button), purged (`409 item_purged`; adding it to a library fetches it again), a job already queued or running (`409 ingest_in_progress`, e.g. a second click), not failed (`409 item_not_failed`), `ytdlp.enabled` off (`403 feature_disabled`, for everyone), below the free-space minimum (`507 insufficient_storage`).
- **Rate limit:** after a retry, the next retry of the same item waits **30 s** (`429 retry_too_soon` with `Retry-After`), whoever asks, so a button can't hammer YouTube. In memory only (a restart forgets it).
- **Who:** on the item page, users who may edit the item (the adder while it is in their library, or `library.editAny`) and holders of `admin.jobs` (`you.canRetry`); in a room, the owner and moderators and the entry's requester while they may add YouTube links there.
- **Audited** as `item.ingest_retried` (target the item; details `jobId`, `kind`, and `roomId` for a retry from a room). The admin jobs page's own Retry stays as it is (`job.retried`).

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
| Rate control | **Capped bitrate** `R` = `output.videoBitrateKbps` (default 3000 at 720p30, 6000 at 1080p30): `-b:v R -maxrate R -bufsize R/2` with `-x264-params open-gop=0:ref=4:…` (no `nal-hrd`, no `-minrate`). A VBV-constrained average, not CBR: no HRD parameters in the SPS (the bitrate is not part of the avcC) and no filler on disk. The packager pads the program output to a constant rate while it sends ([program-output.md](program-output.md#constant-rate-padding)) |
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

A `quality` job of a queued item needs no special case: it publishes a new media version at once, even while the item is current or next ([Swap while playing](#presets)).

**Give way:** when every worker is busy and the job first in this order comes **strictly before** a running `reencode` or `quality` job (comparing step 1 and whether it is a new-item job, ignoring age), that running job is interrupted and put back in the queue without counting the attempt (it starts over later); of several, the one that comes last goes (the most recently started among equals). So a queued item's job may interrupt a job for an item no room wants, never the reverse; a sooner-playing item's job interrupts a later one's; and among equals a new-item job interrupts a re-encode (as before), while a `reencode` never interrupts a `quality` job. Checked whenever a job is queued and whenever the rooms' ranking changes. A worker that has just claimed a re-encode while a job that comes strictly before it waits puts it back at once. New-item jobs are never interrupted.

**Swap while playing** (versioned since 2026-11-10, Siggi: "Do it"): a `reencode` or `quality` job publishes a **new media version** ([Storage layout and media versions](#storage-layout-and-media-versions)) and **never waits** for the item to stop playing. Until then (2026-11-10) it waited, up to 1 h, while the item was current or next in a room, so a web player wouldn't mix byte ranges of the old and the new `mezz.mp4` under one URL; that wait once deadlocked a room. Now the web client is given versioned URLs, so a player that started on the old version keeps fetching it until it finishes, a preloaded next item stays valid, and new plays use the new version. The program output keeps reading the files it opened, and every preset has the same avcC.

**Why constant bitrate, and why the bitrate is part of the encoding** (2026-11-10, Siggi: "It should be constant bitrate (preferably configurable), and card view must also be at that constant bitrate"): the program output (MPEG-TS, HLS and the RTMP push, which copies the stream into FLV) carries a steady rate whether an item or a card is showing. Until 2026-11-10 the encodes themselves were CBR (`nal-hrd=cbr`, x264 padding every frame with filler NALs), which put the HRD parameters (rate and buffer size) into the SPS, so another bitrate was another avcC and every card on disk was padded to `R × 2 s / 8` bytes. Since then (Siggi chose packager-side padding: "For the pre-rendered card that gets repeated every 2 seconds, the on-disk format shouldn't be padded with junk data, the padding should ideally be added before it gets sent out as a stream."), encodes are only **capped** at `R`, nothing on disk is padded, and the packager adds the filler NALs while sending ([program-output.md](program-output.md#constant-rate-padding)). **Every bitrate now gives the same avcC.** The bitrate is still part of the *encoding* (the reference card, the cards directory, `items.output_profile` and `index.bin`, named like `720p30-3000k-v3`; `-v3` is the encoder-arguments version), because the cap is what the encodes are made for: changing `output.videoBitrateKbps` re-encodes the library exactly like a profile change ([Re-encodes](#re-encodes)), since a higher cap only brings more quality with new encodes. It is also the program output's stream rate for normal rooms (stream rate = cap; a stream rate above the cap would only add padding, so it isn't a separate setting). Audio-only rooms use `output.audioOnlyBitrateKbps` (default 800) instead: they show only cards, which need far less than the cap. Allowed range 500–25000 kb/s for both (25 Mb/s is level 4.0's High-profile MaxBR). Audio stays AAC 192 kb/s. The switch to `-v3` re-encoded every item (from its original) and every card once, as the presets step's `-v2` did.

**Buffer size** (the cap's tightness, measured again 2026-11-10 without HRD, ffmpeg 6.1.1, 720p30, 3 Mb/s): the VBV buffer bounds how far an encode can run ahead of `R × t / 8` at any point, which is what the packager's padding relies on (an IDR's excess is paid back by the following frames' smaller padding). With `-bufsize` = 0.5 s (187.5 KB) the largest run-ahead was 23 KB for `testsrc2`, 30 KB for a noisy fractal and 145 KB for a cellular automaton, and the 2 s GOPs came out at 70…93 % of the cap. With 1 s, `testsrc2` ran ahead by 100 KB and single GOPs reached 106 %; with 0.25 s the GOPs fell to 57…87 % (less quality for the same cap). So the buffer stays at R/2. `vbv-init=1` was dropped: without HRD it only shifts how much of the buffer the first IDR may use, and the padding doesn't need it. Still cards come out tiny (a few KB per 2 s GOP: `smptebars` 4 KB, `rgbtestsrc` 9 KB) instead of 750 KB of mostly filler.

Reference command (video item at 1080p30 at the default 6000 kb/s with the default quality preset `slow`; the second loudnorm pass is shown; with `veryfast` or `faster` use `chroma-qp-offset=-2`). At 720p30 use `scale=1280:720`/`pad=1280:720`, `-level:v 4.0` and the 720p rate (default `3000k`, buffer `1500k`):

```sh
ffmpeg -i src -map 0:v:0 -map 0:a:0 \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease:force_divisible_by=2:out_color_matrix=bt709:out_range=tv,pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30" \
  -c:v libx264 -preset slow -profile:v high -level:v 4.1 -pix_fmt yuv420p \
  -color_range tv -colorspace bt709 -color_primaries bt709 -color_trc bt709 -chroma_sample_location left -bf 0 \
  -g 60 -keyint_min 60 -sc_threshold 0 -force_key_frames "expr:gte(t,n_forced*2)" -x264-params open-gop=0:ref=4:chroma-qp-offset=0 \
  -b:v 6000k -maxrate 6000k -bufsize 3000k \
  -af "loudnorm=I=-14:TP=-1:LRA=11:measured_I=…:measured_TP=…:measured_LRA=…:measured_thresh=…:offset=…:linear=true,aresample=48000" \
  -c:a aac -b:a 192k -ar 48000 -ac 2 \
  -movflags +faststart mezz.mp4
```

**The colour description is part of the profile:** x264 copies the source's colour tags (range, primaries, transfer, matrix) and chroma sample location into the SPS's VUI. Without the fixed `-color_*`/`-chroma_sample_location` options, a BT.709-tagged source (every YouTube download), a full-range or BT.601 source, or Motion JPEG (centre-sited chroma) would each produce a different avcC and fail the invariant check. The scale filter converts the pixels to limited-range BT.709 to match. HDR sources are not tone-mapped (they come out washed out, but valid).

Then `audio.m4a` = `ffmpeg -i mezz.mp4 -map 0:a -c copy -movflags +faststart audio.m4a`.

For **audio items**, encode only the audio, straight to `audio.m4a`; there is no `mezz.mp4`. **Every** item gets a `card.mp4` (below), including video items, so that `presentAsAudio` and the transition card work.

**Invariant check (mandatory):** compare each encode's avcC with the server's reference card for the profile, and fail the job if the bytes differ.

## Cards

A card is a still image encoded as one 2 s GOP with the **same encoder arguments** as the mezzanine: one IDR followed by 59 P-frames. The picture data is small (a few KB per card; P-frames of about 70 bytes), and it is stored **unpadded**: the packager pads the stream to the constant rate while it sends ([program-output.md](program-output.md#constant-rate-padding)), so card view still runs at the same constant rate as items. (Until encoder args `-v3`, CBR padded every card to `R × 2 s / 8` bytes on disk, about 750 KB at 3 Mb/s, almost all filler.)

```sh
ffmpeg -loop 1 -framerate 30 -i card.png -t 2 -vf "<scale/pad/setsar=1/fps filter>" <PROFILE_VIDEO_ARGS> -an card.mp4
```

The filter is the mezzanine's scale filter. Every card's avcC is checked against the reference card before the card is moved into place. `setsar=1` matters: a PNG without a `pHYs` chunk (Java2D writes none) has an unknown aspect ratio, x264 then omits it from the SPS, and the avcC differs from the mezzanines'.

**Reference card:** at startup the server makes sure `cards/<encoding>/reference.mp4` and `reference.avcC` exist (a black card), e.g. `cards/720p30-3000k-v3/`. The stored avcC is the invariant reference for that encoding (since `-v3` the same for every bitrate of a profile). Directories of encodings no longer configured are left alone (they are small; delete them by hand). Each start also encodes a fresh card; if its avcC differs (e.g. after an ffmpeg/x264 upgrade), the server warns, keeps the stored reference, and new encodes fail the check until the library is re-encoded.

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

**Steps for people** (added 2026-11-10): a running job also names the step it is in, kept in memory only (`IngestQueue.Context.stage`, `IngestQueue.liveStep`): `download` (the yt-dlp download), `encode` (probe, loudness pass 1 and the encode), `finish` (everything after the encode), each with the step's own progress (0..1, from the part of the job's progress it covers). The jobs lists (`step`, `stepProgress`), `GET /api/items/{id}/job` and the rooms' `ingest` messages carry it; the pages show it as "Downloading 45 %", "Encoding 30 %", "Finishing…" ([rooms.md](rooms.md#room-state)).

- **Source:** an upload job reads `<data>/tmp/ingest/<itemId>/source[.<ext>]`, where the upload code puts the file; an optional `cover.<ext>` next to it (e.g. a YouTube thumbnail) is used as artwork instead of the embedded picture. A `youtube`/`refetch` job downloads the canonical URL into its scratch directory `<data>/tmp/jobs/<jobId>/` first. Either becomes the item's `original.<ext>` ([Originals](#originals)). The staging directory is deleted after a successful publish and kept when the job fails, so it can be retried.
- **Probe:** a real video stream makes a video item; attached pictures and single still images in an image codec (MJPEG, PNG, …) are cover art. An image codec with more than one frame (`nb_frames`, or Matroska's `NUMBER_OF_FRAMES` tag; if unknown, a `duration`/`DURATION` tag over 0.5 s) is video, e.g. Motion JPEG from a camera. No audio, or longer than `ingest.maxDurationSeconds`, fails the job.
- **Loudness pass 1:** `loudnorm=I=-14:TP=-1:LRA=11:print_format=json,aresample=48000` (the resampler only fixes ffmpeg's progress `out_time`). Silence measures `-inf`; it is encoded without normalisation and `loudness_lufs` stays null.
- **Encode:** the pass-2 filter ends with `aresample=48000:first_pts=0`, so audio that starts after the file start is padded with silence and every `audio.m4a` starts at 0 like the video (ffmpeg's `fps` filter already fills video from 0). Metadata and chapters are dropped.
- **Cover:** the staged cover, else the embedded picture, is converted to `cover.jpg` (at most 1200 px per side) and drawn on the card; without one the card gets the placeholder. A broken cover never fails the job.
- **Publish:** everything is built in `<data>/media/.ingest-<jobId>/`. A newly kept original moves to `media/<itemId>/original.<ext>`, the rest is renamed to the item's next version directory `media/<itemId>/v<N>/`, and then one update switches the row: `media_version = N` together with `items.kind`, `duration_ms` (of `audio.m4a`), `output_profile`, `loudness_lufs` (measured *I*), the `original_*` columns, and `purged_at` cleared; the queue sets `ready`. It never waits for a play to end ([Storage layout and media versions](#storage-layout-and-media-versions)). If the switch fails, the new version directory is deleted again. At startup, before the queue starts, `.ingest-*`, `tmp/jobs/*` and the `.purge-*` directories a purge left behind (in `media/` and `tmp/ingest/`) are deleted, a `.old-*` directory (from the directory-swap publish before versions) whose item directory is missing is put back, and every media version but the current one is deleted.

**Verify:** the audio ends within 50 ms of where the source's audio ends (its offset from the file start plus its duration; for Matroska/WebM, which has no stream duration, ffprobe reads the audio packets of the last 30 s; if the encode doesn't match that but is within 50 ms of the length loudness pass 1 actually decoded, its last `out_time`, it passes too, because some decoders such as WMA output more than the container declares), the avcC invariant holds, and keyframes fall exactly every 2 s (sample *i* is an IDR iff *i* mod 60 = 0, at PTS exactly *k*·2 s from 0).

### Storage layout and media versions

Added 2026-11-10 (Siggi: "Do it", after asking why a re-encode can't replace files in use). Every encode of an item publishes into **its own directory** `media/<itemId>/v<N>/` (`mezz.mp4`, `audio.m4a`, `card.mp4`, `index.bin`, `cover.jpg`), and `items.media_version` names the current one ([data-model.md](data-model.md)). The kept original stays at `media/<itemId>/original.<ext>`, outside the versions (one per item).

- **Version 0 is the flat layout:** items published before versions existed have their files directly in `media/<itemId>/`. They are **not moved** (so nothing has to survive a restart halfway); their next encode becomes `v1/`, and the flat files are then deleted like any old version, by name, leaving the original.
- **Numbers only grow:** a publish takes `max(media_version, highest v* directory present) + 1`, and a purge keeps `media_version`, so a refetch after a purge gets a new number too and a versioned URL never names other bytes.
- **Publish:** the new version's files are in place before the database switch; the switch is one row update, so it is atomic from the database's point of view. New plays (web and program output) use the new version from then on: the program output asks for the item's current version at every load (`ProgramMedia.ItemDirs`), and keeps the files of an item it already has open.
- **URLs:** the web client gets versioned URLs, `/media/{id}/v/{N}/{file}` ([api.md](api.md#items-libraries-playlists-sharing)), in the room state (`coverUrl`, `audioUrl`, `videoUrl`: listen mode and the room page) and on the item page (`mediaVersion`); the Library page plays nothing. A play started on version N stays on version N; the item page keeps a player that is playing on its version. Versioned responses are cacheable as `immutable`. The unversioned `/media/{id}/{file}` keeps working and serves the current version. `original` isn't versioned.
- **Clean-up** (`MediaVersions`): a publish hands the version it replaced to a sweeper (checked every minute), which deletes it once the item **isn't current or next in any room** and at least **the item's duration plus 5 minutes** have passed since the switch, so a play that started on the old version just before can finish on it, and (since undo) once the [undo](#undo) window is over for the item's kept previous version. Pending deletions live in memory: **at startup** every version except the current one (and the kept undo version within its window) is deleted (no player survives a restart), including a `v*` directory a crash left between its rename and the database switch. Directories of purged or unknown items are left to the purge. Purge and retention delete the whole `media/<itemId>/` (every version and the original) as before. The free-space minimum counts old versions as the disk use they are until they are deleted; nothing else is special about them.
- **Overlay caches (M5)** key on the media version too, so a re-encoded item never splices overlay GOPs made from the old version.

### Undo

Added 2026-11-10 (Siggi: "For undo, in case a mistake is made, keep the previous encode for 48 hours (configurable in admin settings) so users can click an undo button to go back."). Every edit (the trims) and every re-encode publishes a new media version; the version an item switched away from is **kept for `media.undoHours`** (admin setting, default 48, 0 = no undo; [auth.md](auth.md#server-settings)) on top of the play-based clean-up above (`MediaVersions`).

- **One step back:** an item keeps at most one previous version for undo. When a newer switch retires another version, the one kept before only waits for its plays (duration + 5 min, not current/next) and is then deleted. Keeping more would cost a whole encode's disk space per step, so one step it is.
- **In the database**, so it survives a restart: `media_undo` names an item's kept version, when the item switched away from it and whether that switch was a person's change; `media_versions` holds what the current and the kept version were made with: the `items` publish columns (`kind`, `duration_ms`, `output_profile`, `loudness_lufs`, `encode_preset`, `encode_quality`) and the **edit settings** as JSON (`edit_json`: the trims, `videoStartMs`/`videoEndMs`/`audioStartMs`/`audioEndMs`, once the trim steps exist). The current edit settings are the current version's. Versions published before undo existed get their row from the `items` columns when they are first kept. The **startup clean-up** keeps the kept version while it is within the window and hands it to the sweeper; past the window (or with its files gone) it is deleted and its rows forgotten.
- **Undo** (`POST /api/items/{id}/undo`, [api.md](api.md#items-libraries-playlists-sharing)): no re-encode. The kept version's files are **hard-linked** into a new version directory `v<N+1>/` (copied if links fail), so version numbers still only grow and versioned URLs stay immutable; then one transaction switches `media_version` to it, puts back the kept version's `items` columns and edit settings, and makes the version undone the kept one (switched away from now, a person's change), so **an undo can be undone** within the window ("redo"). The rooms are told (the item's files changed), like after a publish. Audited (`item.media_undone`, with the version numbers).
- **Who:** whoever may edit the item (the adder while it is in their library, or `library.editAny`). The item page shows **Undo the change from 14:32** (`undo.changedAt` on the item, only for them) while a change can be undone.
- **When not:** undo off (`media.undoHours` 0), past the window, the kept version's files gone, the item not `ready`, or the kept version made for another output encoding (`output_profile`; the program output can't splice it) → `409 nothing_to_undo`; an ingest job of the item queued or running → `409 item_busy`.
- **Re-encodes made by the system** (encoding changes, quality re-encodes) keep the previous version for the window too, but **don't offer Undo**: undoing an encoding re-encode would bring back files the program output can't use, and undoing a quality re-encode would only bring back the quick encode. So only a person's change is undoable (`media_undo.undoable`); a system re-encode that follows an edit replaces the edit's undo (one step back). A re-encode keeps the current edit settings.
- **Disk space:** kept versions count toward the free-space minimum ([Free disk space](#free-disk-space)) like any file. While the data volume is below the minimum, the sweeper deletes kept versions whose plays are over **early, oldest first**, until the shortfall is covered, with a log line ("Deleted media version N of item … early (kept for undo, but the free disk space is below the minimum)").
- Purge and retention forget the item's undo rows with its files.

### Encode jobs page

Added 2026-11-10 (Siggi: "Also allow non-admins to see encodes for items that appear in their own library."). Two views of the same rows, built on `ingest_jobs` joined with `items` (`JobList`, `JobsApi`; API in [api.md](api.md#admin)):

- **Admin** (`/admin/`, Jobs tab; permission `admin.jobs`, [auth.md](auth.md#roles-and-permissions)): every job. **Retry** a failed job: a new job of the same kind and tier for the item (an upload retries from its staging directory, which a failed job keeps; a YouTube job downloads again). Only the item's latest job can be retried (`409 job_superseded`), and not once the item is purged (`409 item_purged`; a YouTube item comes back by adding it to a library). **Cancel** a queued job (running jobs can't be cancelled): it becomes `failed` with a log line saying so. A new item's ingest (`upload`, `youtube`, `refetch`) then leaves the item **`failed`** with the error "Cancelled by an administrator", retryable from the Failed list; a `reencode` or `quality` job is only dropped and the item stays `ready` (both are queued again at the next start while the item still needs them, like failed ones). Both actions are audited (`job.retried`, `job.cancelled`; target the job id, details `itemId` and `kind`). The job log is fetched per job (`GET /api/admin/jobs/{id}`), never in the lists.
- **User** (`/encodes/`, linked from the nav and the Library page; any signed-in user, guests and anonymous visitors get `401`): only jobs of items **in the caller's library**, from any library entry (uploaded, imported, added, shared with them; *reading:* PLAN said "not shares from others", but Siggi's words are "items that appear in their own library", and a shared item does; flip `JobList.IN_LIBRARY` to `via <> 'share'` if shares should be left out). No logs, no actions, nothing about who added an item or whose libraries hold it, no IP.
- **Lists** (`?state=`): `active` (default): running jobs (longest running first), then the queue in the order the workers take it right now ([Priorities](#presets): the same `ORDER BY` as the claim, `IngestJobs.claimOrder`, so queued items' jobs show first), each queued job with its 1-based `position` in the whole queue (in a user's list too, so it shows how many jobs are ahead without naming them), plus `paused` while low disk space holds the queue back. `failed`: failed jobs that are still their item's latest job and whose item isn't purged (a failure a later job replaced is history), latest first. `done`: jobs that finished in the last 24 h, latest first (folded away on the page). Offset cursors.
- **Live updates:** a short poll (every 3 s while the page is visible and the list shown). There is no suitable push channel: the only WebSocket is a room's.

### Re-encodes

If the output profile or the video bitrate changes, every item that isn't purged gets a `reencode` job (items from before CBR stored the bare profile name, e.g. `720p30`, in `output_profile`, so they re-encode once to CBR). Every item re-encodes from its kept [original](#originals). Items ingested before originals were kept for every item (2026-11-10) have none for video: their YouTube items are fetched again, their video uploads re-encode from their old mezzanine (one extra generation of loss).

- **When:** at startup, before the workers start, and when an admin changes the encoding ([below](#changing-the-encoding-at-runtime)), every `ready` item whose `output_profile` differs from the current encoding (e.g. `720p30-3000k-v3`) gets a `reencode` job unless it already has an unfinished job. Pending, processing and failed items are skipped (their next ingest uses the current profile anyway), and so are purged ones. A re-encode that failed is therefore tried again at the next start.
- **Order:** among jobs for items no room wants, workers take `reencode` jobs only when no new-item job is queued, and they give way to one that arrives while every worker is busy, so a library re-encode doesn't hold up new uploads and imports; `quality` jobs come after `reencode` jobs. A re-encode of an item a room is waiting on comes before all of those ([Priorities](#presets)).
- **Sources:** an item with its original file re-encodes from it (loudness measured again, the original and its `original_*` columns kept), YouTube items included: no download. Without one (made before every item kept its original), a YouTube item downloads again and keeps that download as its original; if the download fails (e.g. the video is gone), it falls back to its old files. Anything else (an older video upload, an audio item without its original) re-encodes from its old `mezz.mp4`, or `audio.m4a` for an audio item, and its `original_*` columns are cleared. Old files were already normalised, so they are encoded without loudnorm and the stored `loudness_lufs` is kept. The item's `cover.jpg` is reused for the card. `ingest.maxDurationSeconds` doesn't apply to re-encodes.
- **Preset:** the quality preset for an item in a library that no room wants now, else the quick preset ([Presets](#presets)).
- **While it runs** the item stays `ready` with its old files, which the web player can keep using; the new files are published as a new media version as soon as they are done ([Storage layout and media versions](#storage-layout-and-media-versions)), even while the item plays. The old files' `index.bin` and avcC belong to the old profile or bitrate, so the packager must not splice them: it checks the index's profile code and bitrate (and the avcC) and treats such an item as not ready, and the rooms make the same check before choosing an entry, so such an item is skipped like one still processing ([rooms.md](rooms.md#room-state)); the job's completion tells the rooms, which pick the item up once its new files are published. A job for an item that is no longer `ready`, or already encoded for the current profile, ends without doing anything.

### Changing the encoding at runtime

`output.profile` and `output.videoBitrateKbps` are admin settings (the config file gives their defaults, [auth.md](auth.md#server-settings)), so the encoding can change while the server runs (added 2026-11-10). Nothing needs a restart:

- **Ask first:** before saving either setting, the admin page calls `GET /api/admin/encoding` with the new values and confirms: "Change the output encoding from A to B? N items will re-encode …". Saving the same encoding again does nothing.
- **Cards first:** after the save, the server makes (or checks) the new encoding's reference card and filler cards in `cards/<new id>/` (seconds) while everything keeps using the old ones (`ingest.OutputAssets`). If the reference card can't be made, the old encoding stays in use and the server log says why. The old encoding's cards stay on disk, so switching back is quick.
- **Then swap:** the new encoding becomes current at once for new ingest jobs (each job reads it when it starts), new items' `output_profile`, and the x264 preset check (presets are checked again against the new reference card). Every `ready` item of another encoding gets a `reencode` job, as at startup ([Re-encodes](#re-encodes)), and the workers are woken. A job already running when the switch happened finishes for the old encoding; when it finishes, its item gets a `reencode` job right away (`IngestJobs.currentEncoding`).
- **Program outputs:** a running output can't change its SPS or cards midway (invariants 1 and 3), so every running output restarts on the new encoding right after the swap (`ProgramOutputs.restartRunning`): its packager is replaced like after a server restart, `.ts` connections end (players reconnect), the RTMP push reconnects, HLS clients see a new stream. From then on, an item whose files are still of the old encoding counts as not ready until its `reencode` job has published the new files: the rooms (told of the switch) skip it like an item still processing; only an entry already current at the switch plays out on the Loading card. Web players are not affected (they play `mezz.mp4`/`audio.m4a` directly).
- **Other settings:** `ingest.workers` resizes the worker pool at once (growing starts workers, shrinking lets running jobs finish and the extra workers stop before their next job); `ingest.maxUploadBytes` and `ingest.maxDurationSeconds` apply to the next upload or import; `overlay.workers` and `overlay.cacheMaxBytes` are stored for the overlay encoder (M5).
