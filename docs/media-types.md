# Media types, auto-delete and custom cards (M8)

Added 2026-11-11 from Siggi's decisions. Users paste YouTube links that aren't music videos: songs uploaded as a still image, and videos that aren't music at all (clips, movies, show episodes). Items get a **media type**, which decides how they are shown, whether they get the QR overlay, and whether their media is deleted automatically.

## Media types

Every item has exactly one media type, stored **on the item** (items are shared, so everyone sees the same type):

| Type | Meaning | QR overlay | Auto-delete |
|---|---|---|---|
| `audio` | Music without a picture: audio uploads, and videos detected as a still image | Cards, by the room's overlay mode | No |
| `uncategorized` | A video nobody has classified yet. **The default for every video** | Like a music video, by the room's overlay mode | No |
| `music_video` | A music video | By the room's overlay mode (re-encode needs `output.overlayVideo`, as before) | No |
| `movie` | A movie | **Never** | Yes |
| `show` | A show episode, with **show name, season number and episode number** | **Never** | Yes |

- `items.kind` (`audio` | `video`) stays: it says what files the item has. The media type says what the item is. An `audio`-type item may have video files (a still-image video kept for undo, or one an editor set to audio); it plays like an audio item, with its card. This takes over the job of `present_as_audio` (the M8 schema step folds that flag into the media type, and the API keeps `presentAsAudio` as a derived field until the UI stops using it).
- **Migration:** every existing audio item becomes `audio`; every existing video item becomes `uncategorized` (no production data exists yet; Siggi, 2026-11-11). Video items with `present_as_audio = 1` become `audio`.
- **Who sets it:** whoever adds an `uncategorized` video **to their library** must classify it first (the add asks for a type; the API refuses the add without one). After that, it is edited like other item metadata: by the item's adder while it is in their library, or with `library.editAny`.
- **Suggestion:** the classify prompt preselects a type from what is known: yt-dlp's `series` / `season_number` / `episode_number` → `show` (prefilled); yt-dlp's `track` / `artist` (YouTube's music metadata) → `music_video`; an upload's file name like `S01E02` → `show`. It's only a preselection; the person confirms.
- **Requests from a pasted link** (not through anyone's library) just play as `uncategorized` and follow the room's overlay mode.
- **Shows:** show name (required for `show`), season number (optional, ≥ 0) and episode number (required, ≥ 0). Libraries group show items by show name, then season, then episode. Cleared when the type changes away from `show`.

## Still-image detection

At ingest, every video source (upload or YouTube) is checked for being a still image (ffmpeg's `freezedetect` or equivalent on the decode ingest already does, with a tolerance for compression noise). If the picture is still for (nearly) the whole duration:

- The item becomes type `audio`. Its published media version is an audio item's (audio files and a card), with the still frame as its cover when it has no better artwork.
- The video version is kept as the item's **undo** version, for `media.undoHours` (default 48; [library-and-ingest.md#undo](library-and-ingest.md#undo)), not a separate setting. Undo (or changing the type to a video type within the window) brings the video back. With `media.undoHours` = 0 the video is deleted right away.
- **Uploads** show a warning on the item: it was detected as a still image, and the video part will be deleted at *time* unless undone. (A YouTube item can always become a video again later, by refetching it; an upload can't once the window is over.)
- Visualizers and slow pans or zooms that aren't caught stay video; whoever adds them to their library classifies them (`audio` is one of the choices).

## Loudness

`movie` and `show` items skip the music loudness normalisation (it would flatten quiet dialogue against loud scenes). Music, `uncategorized` and `audio` keep it.

## QR overlay modes

The room setting `overlay.enabled` becomes `overlay.mode`:

| Mode | What gets the overlay |
|---|---|
| `off` | Nothing |
| `always` | Every card (item cards, paused, queue empty, loading, transition) and music/uncategorized videos (re-encoded only when the owner holds `output.overlayVideo`) |
| `idleOnly` | Only the **queue is empty** card. For rooms used for short videos, where the QR code shouldn't cover the content |

`movie` and `show` items **never** get the overlay, in any mode. Existing rooms migrate `overlay.enabled: true` → `always`, false → `off`. The server switch `overlay.enabled` stays as it is.

## Views, auto-delete and pins

- **A view** is any playback at all, however short: an entry of the item becoming current in a room, or a client reporting playback outside a room through the API (`POST /api/items/{id}/view`, by anyone who may play the item; the report is what counts, never a `GET` of the media file). Each view sets `items.last_viewed_at`.
- **Auto-delete** applies to `movie` and `show` items only, with two server-wide admin settings (not per room):
  - **Never viewed:** media deleted `retention.videoUnviewedDays` (default 14; 0 = off) after the item was added.
  - **Viewed:** media deleted `retention.videoAfterViewHours` (default 72; 0 = off) after its most recent view.
  The delete is the usual purge (`Items.purge`: files gone, row `purged`); a YouTube item comes back by refetch when someone plays or adds it again, an upload is gone for good.
- **Pins:** a pinned item is never deleted automatically (neither by these timers nor by the unreferenced-items retention). Admins can pin anything; other users need the role permission `library.pin`. The pin is on the item.

## After media is deleted

- When an item's media has been deleted for any reason (auto-delete, unreferenced retention, or someone's Purge Media), its **library and playlist entries are removed** `retention.purgedEntryDays` after the purge (admin setting; default 30; **0 = right away**, at the purge; **-1 = keep them forever**). Room history keeps its "media expired" rows (trimmed by `rooms.requestHistoryDays` as before).
- **Delete record (admin):** an admin can delete an item's record entirely, as if it had never existed: the item row, its media versions, library, playlist, queue and history entries, jobs and files. Only the audit log keeps a row saying an admin deleted it (with its title). For YouTube items this ends the "one row per video ID" rule for that ID: adding the link again creates a new item (CLAUDE.md invariant 7).

## Next episode

In a room, the owner can press **Next episode** on a `show` item (the current entry, or the last one played): it queues the item with the same show name and the next episode, ordered by (season, episode): the next episode number in the same season, otherwise the lowest episode of the next season that exists. It looks in the owner's library. If there's none, it says so.

## Custom card images

The cards a room's stream outputs show between items can be replaced by images:

- **Screens:** paused, queue empty (idle), loading/next-up, and the transition gap's blank card. Item cards (artwork, title, artist) can't be replaced.
- **Server-wide:** an admin uploads one image per screen. **Per room:** owners whose role has `rooms.customCards` can upload their own per screen, which overrides the server's.
- The image is used **exactly as given**: no text is drawn on it. The QR overlay is still drawn on top, according to the room's overlay mode.
- **Spec:** 16:9 and at least the output resolution (1280×720 at 720p30, 1920×1080 at 1080p30). An image that isn't gets a clear warning (what's wrong, what is expected) but is accepted: it's scaled to fit the frame without cropping or stretching, with black bars. The warning is re-evaluated when the output resolution changes.
