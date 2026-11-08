# Libraries, playlists, sharing and retention

## Items vs. libraries

An **item** is a media object stored once on the server: its files, metadata and ingest state. A **library** is a per-user set of references to items (`library_entries`). The same item can be in any number of libraries; it is never copied.

- **Adding** an item by upload or YouTube puts it in the adding user's library (`via='upload'|'youtube'`). The exception is a request made inside a room, where "also add to my library" is a checkbox, on by default for logged-in users. Guests have no library.
- **Dedupe:** re-adding content that already exists reuses the existing item:
  - YouTube imports match on `youtube_id` (see [library-and-ingest.md](library-and-ingest.md#youtube-caching));
  - uploads match on the SHA-256 of the uploaded file.
  A match only adds a library entry or queue entry. No download, no transcode.
- **Removing** an item from your library only deletes your entry. The item itself is deleted by retention, or by someone with `library.purge`.
- **Metadata** (title, artist, album, cover) belongs to the item and is therefore shared. It can be edited by the user who added the item, while it is in their library, and by anyone with `library.editAny`. Per-user metadata overrides are out of scope for v1.

## Playlists

- Owned by one user, private, and made of ordered `playlist_entries` (fractional `position`). An item can appear in a playlist more than once.
- Adding an item to a playlist does **not** add it to your library, but it does protect the item from retention (see below).
- In a room, the owner and moderators can enqueue a whole playlist, or shuffle it into the queue. In `vote` mode, playlist entries are added as normal queued entries with zero votes.
- Sharing playlists is out of scope for v1; sharing works per item.

## Sharing

- A user can share an item with another account, picked by username, with an optional message.
- The share adds the item to the recipient's library immediately (`via='share'`, `shared_by_user_id` set), and they get a notification. They can remove it like any other entry.
- The recipient's library is not visible to the sender. The sender only sees "shared with X", in their own share history.
- Users can't share with guests. To give a guest an item, send them the item's direct link.

## Visibility

| Who | Can see |
|---|---|
| Any user | Their own library and playlists; items shared with them |
| `library.viewAll` | Every user's library and playlists (read-only), searchable by user |
| Room members (users and guests) | Inside a room, the **request search scope** chosen by the room owner (below) |
| Anyone | Any item's page, **by direct link** (`/items/{id}`), whether or not it's in their library |

Item pages can be opened from a direct link, but there is no global listing or search across all items. Without `library.viewAll`, the only ways to discover an item are your own library, a room's request search, a share, or being given the link.

**Room request search scope** (room setting `requestScope`): which items room members can find when requesting.

- `owner` (default): the room owner's library and playlists.
- `owner+self`: also the requesting user's own library.
- `none`: no library search; requests come only from uploads and YouTube links, if those are enabled.

Search results return only item metadata. They don't say which library an item came from, so nothing about a library's contents leaks beyond what the scope allows.

## Media access rules

`GET /media/{itemId}/{file}` (`audio.m4a`, `mezz.mp4`, `original`, `cover.jpg`) is decided as follows:

1. **Logged-in user:** allowed. A direct link is enough; the item doesn't have to be in their library.
2. **Guest or anonymous:**
   - If `guests.download` is on: allowed.
   - Otherwise allowed **only if** the item is the **current** entry, or the **next** entry, in the queue of **any** room right now. The next entry is allowed so clients can preload it.
   - Otherwise: `403`.
3. Cover images are always allowed when metadata is visible. They are needed to render request search results and now-playing info.

The check happens once, when the request starts. A Range response that is already streaming isn't cut off when the item stops being current; the client's following Range requests will be refused.

"Next" is computed from live room state, so in `vote` mode it can change between the preload and playback. A refused preload simply falls back to loading at play time.

To keep the check fast, maintain an in-memory `Set<itemId>` of all rooms' current and next items, updated by the room executors.

## Retention

Items that nobody keeps are deleted, so that requests from guests don't fill the disk.

An item is **referenced** if any of these is true:

- it is in a library (`library_entries`);
- it is in a playlist (`playlist_entries`);
- it is queued, suggested or playing in any room (`queue_entries.state in ('queued','suggested','playing')`);
- an ingest job for it is running.

`items.last_referenced_at` is updated whenever an item **stops** being referenced: a library entry removed, a playlist entry removed, or a queue entry finished. It is also updated when the item is first created. A periodic sweeper (hourly) then:

1. selects items with `status='ready'` that are not referenced and have `last_referenced_at < now − retention.unreferencedDays`;
2. re-checks that they are still unreferenced, inside a transaction;
3. deletes `media/<itemId>/` and any overlay caches for it;
4. sets `status='purged'` and `purged_at`, keeping the row and its metadata.

With `retention.unreferencedDays = 0`, nothing is ever deleted automatically.

**Purged items:**

- History and request lists still show their title and artist, marked as "media expired".
- Requesting, or adding to a library, a purged **YouTube** item fetches it again (an ingest job of kind `refetch`) using the stored `youtube_id`. To the requester it behaves as if the video were being requested for the first time.
- A purged **upload** can't be restored. It has to be uploaded again; the content hash then matches the old row, which is reused.

## Room owner's request history

Every request in a room creates a `room_requests` row, whether it came from the library, an upload or YouTube, and whether the requester is a user or a guest. The row records who made the request (display name at the time), how, when, and the resulting queue entry.

The room owner (and moderators, read-only) sees the request history in the room's management view:

- filter by source (upload, YouTube, library) and by requester;
- **"Add to my library"** on each row, or on a multi-selection. This adds the item to the **owner's** library and thereby protects it from retention;
- the history is kept for `rooms.requestHistoryDays` (default 90) and then trimmed.

Requests are shown even after the item has been purged. That lets the owner see what was requested while they were away, and re-fetch YouTube items.
