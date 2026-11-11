# Libraries, playlists, sharing and retention

## Items vs. libraries

An **item** is a media object stored once on the server: its files, metadata and ingest state. A **library** is a per-user set of references to items (`library_entries`). The same item can be in any number of libraries; it is never copied.

- **Adding** an item by upload or YouTube puts it in the adding user's library (`via='upload'|'youtube'`). The exception is a request made inside a room, where "also add to my library" is a checkbox, on by default for logged-in users. Guests have no library.
- **Dedupe:** re-adding content that already exists reuses the existing item:
  - YouTube imports match on `youtube_id` (see [library-and-ingest.md](library-and-ingest.md#youtube-caching));
  - uploads match on the SHA-256 of the uploaded file.
  A match only adds a library entry or queue entry. No download, no transcode.
- **Removing** an item from your library only deletes your entry. The item itself is deleted by retention, or by someone with `library.purge`.
- **Metadata** (title, artist, album, cover) belongs to the item and is therefore shared. It can be edited by the user who added the item (`items.added_by_user_id`), while it is in their library, and by anyone with `library.editAny`. A guest who added an item can't edit it (guests have no library). Per-user metadata overrides are out of scope for v1. The API edits title, artist, album and `presentAsAudio`; replacing the cover isn't implemented yet. **Open (M4):** an edit doesn't re-render the item's `card.mp4` yet, so the card keeps the old text until the item is re-encoded; the card (and, for audio items, `index.bin`, which indexes the card as the video track) must be rebuilt before the packager shows cards.
- **Purging** (`library.purge`, or the retention sweeper) keeps the row and its metadata and sets `status='purged'`. Queued ingest jobs of the item are cancelled (`failed`); while a job is running the purge is refused (`409 item_busy`). Inside the purge transaction `media/<itemId>/` and the item's upload staging directory are renamed aside (`.purge-<name>-<uuid>`), then deleted after the commit; leftovers of a crash are deleted at the next start. Library and playlist entries stay (the item shows as "media expired").

## Playlists

- Owned by one user, private, and made of ordered `playlist_entries` (fractional `position`). An item can appear in a playlist more than once.
- Ordering: an entry added at the end gets the last position + 1 (rounded down to a whole number first); one added or moved before another entry gets the midpoint between that entry and its predecessor (or that entry's position − 1 at the front). When two neighbours are too close for a midpoint (about 50 insertions into one gap), the playlist is renumbered 1..n in the same transaction. Entry changes lock the playlist row first (`FOR UPDATE` on PostgreSQL), so concurrent edits of one playlist can't pick the same position. Clients only see the order, never the positions.
- Adding follows the same item rules as adding to a library: a purged or failed upload can't be added; a purged or failed YouTube item is fetched again. Limits: 1000 playlists per user, 10 000 entries per playlist.
- Adding an item to a playlist does **not** add it to your library, but it does protect the item from retention (see below).
- In a room, the owner and moderators can enqueue a whole playlist, or shuffle it into the queue. In `vote` mode, playlist entries are added as normal queued entries with zero votes.
- Sharing playlists is out of scope for v1; sharing works per item.

## Sharing

- A user can share an item with another account, picked by username, with an optional message.
- The share adds the item to the recipient's library immediately (`via='share'`, `shared_by_user_id` set), and they get a notification. They can remove it like any other entry.
- The recipient's library is not visible to the sender. The sender only sees "shared with X", in their own share history.
- Users can't share with guests. To give a guest an item, send them the item's direct link.
- The notification is the share itself: it stays unseen until the recipient marks it seen (`POST /api/shares/seen`); `GET /api/shares` returns the unseen count for a badge. (Pushing it live over the WebSocket can come with M3's socket; nothing else is needed for that.) Sharing an item the recipient already has makes a new notification but leaves their entry as it is.
- Item rules are those of adding to a library: a purged or failed upload can't be shared; a purged or failed YouTube item is fetched again. A share references the item for retention. A sender can make at most 100 shares per rolling hour, so shares can't be used to flood someone with notifications.

## Visibility

| Who | Can see |
|---|---|
| Any user | Their own library and playlists; items shared with them |
| `library.viewAll` | Every user's library and playlists (read-only), searchable by user (`/api/users?q=` finds users by username or display name; their library can be searched like your own) |
| Room members (users and guests) | Inside a room: the **room owner's** library and playlists plus **their own** library, or with "Search the whole server" every item on the server (below) |
| Any user | On the Library page, with "Search the whole server": every item on the server |
| Anyone | Any item's page, **by direct link** (`/items/{id}`), whether or not it's in their library |

**Any item on the server is fair game** (Siggi, 2026-11-10: "If an item is available on the server, it's fair game. [...] Only thing that should be private about the entire server's library is which users libraries an item is in."). The only private thing is **which users' libraries (and playlists) an item is in**. Item metadata (title, artist, album, kind, length, status) is not private.

**Room request search** (`GET /api/rooms/{id}/search`): always the room owner's library and playlists **and** the requester's own library (guests have none: the owner's only), each item once. A **"Search the whole server"** tickbox (`all=1`; unchecked by default, remembered per browser) widens it to every item on the server that isn't purged: all users' libraries and playlists, and items in no library yet (e.g. recent guest requests awaiting retention). Room setting `libraryRequests` off turns the search off for everyone but the owner and moderators (`403 library_requests_off`).

**Library page:** the search has the same **"Search the whole server"** tickbox for signed-in users (`GET /api/items/search`), with the same results and privacy rules; each result says whether it is in the viewer's own library ("In your library") or offers **Add to Library**. Unchecked, the search is the user's own library.

**Requests by item id** (`queue.add` with `itemId`): any item that exists and isn't a purged or failed upload, for anyone allowed to request from the library at all; there is no scope check (the former room setting `requestScope` and `403 not_in_request_scope` are gone; stored values are ignored). The other request rules stay: `requestsPerActor`, `noRepeatWindow`, guest capabilities (`guests.canRequestLibrary`), control mode (owner mode → suggestion), `libraryRequests`, and the item's own rules (unknown `404 item_not_found`, failed upload `409 item_failed`, purged upload `409 media_expired`; a purged or failed YouTube item is fetched again).

As implemented (`library/ItemSearch`, used by `rooms/RequestSearch` and `GET /api/items/search`): one search implementation. A result is only item metadata (`Library.ItemView`), plus `inYourLibrary` on `/api/items/search`; no `addedAt`, `via`, `sharedBy` or "in X's library" hints. Results are ordered by item id (newest item first: the item's creation, the same for everyone), never by when a library got it, and the whole-server mode has no grouping, so neither the order nor the paging reveals library membership. Excluded: failed and purged uploads; in whole-server mode also purged YouTube items (failed and purged YouTube items in the owner's or requester's library are listed, since requesting one fetches it again). The item page (`GET /api/items/{id}`) shows the item's metadata, its first `addedAt`, and the viewer's own `you.inLibrary`/`canEdit`/`canPurge`/`canRetry`; it carries no adder and nothing about other users' libraries (`items.added_by_user_id` is only used server-side for the edit right, which requires the item to be in the adder's own library).

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

What counts per room: the current entry's item (playing or paused); the first queued entry in play order; and the first queued entry that is ready to play, if that's a different one (the first may still be ingesting and would be skipped over). Suggestions don't count. Each executor publishes its room's items after every change and withdraws them when it closes, so only loaded rooms count: every playing room is loaded at server start, and any room someone opens is loaded.

## Retention

Items that nobody keeps are deleted, so that requests from guests don't fill the disk.

An item is **referenced** if any of these is true:

- it is in a library (`library_entries`);
- it is in a playlist (`playlist_entries`);
- it is queued, suggested or playing in any room (`queue_entries.state in ('queued','suggested','playing')`);
- an ingest job for it is running.

`items.last_referenced_at` is updated whenever an item **stops** being referenced: a library entry removed, a playlist entry removed (or its playlist deleted), a queue entry finished, or an ingest job finished or failed. It is also updated when the item is first created, and whenever it gains a reference. It never moves backwards; `retention/References` is the one place that writes it. For queue entries, every state change goes through `RoomStore.setEntryState`, which touches the entry's item: played, skipped, removed, a rejected suggestion, and also an entry that becomes current or queued again (accepting a suggestion, "previous"); adding an entry touches its item too, and deleting a room touches every item queued, suggested or playing in it. A periodic sweeper (hourly) then:

1. selects items with `status='ready'` that are not referenced and have `last_referenced_at < now − retention.unreferencedDays`;
2. re-checks that they are still unreferenced, inside a transaction;
3. deletes `media/<itemId>/` and any overlay caches for it;
4. sets `status='purged'` and `purged_at`, keeping the row and its metadata.

With `retention.unreferencedDays = 0`, unreferenced items are never deleted. The setting is read at each sweep; the first sweep runs a minute after start. **Pinned items** (`items.pinned_at`) are never selected.

The same sweep also runs **auto-delete of movies and shows** ([media-types.md](media-types.md#views-auto-delete-and-pins)): `ready`, unpinned `movie`/`show` items never viewed and added more than `retention.videoUnviewedDays` ago, or last viewed more than `retention.videoAfterViewHours` ago, are purged the same way (re-check in the purge transaction, audit `reason: "auto-delete"` with both settings). For them library and playlist entries don't count as references; an entry queued, suggested or playing in a room and a running ingest job still protect them. A refetch (a purged YouTube movie or show fetched again) starts both timers over: views from before it don't count, and the never-viewed timer runs from when the refetch finished.

Implementation (`retention/RetentionSweeper`): steps 3 and 4 are one manual-style purge (`Items.purge`), so the order is the purge's: mark and move the files aside in the transaction, delete them after the commit (a crash never leaves a `ready` item without files). The re-check runs the selection predicate again for the item after its row is locked (`FOR UPDATE` on PostgreSQL), and an item that gained a reference or was touched since the selection is skipped. Queued ingest jobs don't protect an item (they are cancelled, as in any purge); a running one does. Each purge writes an `item.purged` audit row without an actor, with `reason: "retention"`. Accepted race: an add that read the item as `ready` just before the sweep may commit its entry just after it, which then shows "media expired".

Last, the sweep removes the **library and playlist entries of purged items** (purged for any reason: retention, auto-delete, Purge Media) whose `purged_at` is at least `retention.purgedEntryDays` old (default 30; `-1` = never; at `0` `Items.purge` already removes them in the purge transaction, and the sweep catches items purged before). Playlists that lose entries get `updated_at = now`; room history (finished queue entries) keeps its "media expired" rows. A purged YouTube item fetched again is `ready`, so its entries stay ([media-types.md](media-types.md#after-media-is-deleted)).

**Purged items:**

- The room's history still shows their title and artist, marked as "media expired".
- Requesting, or adding to a library, a purged **YouTube** item fetches it again (an ingest job of kind `refetch`) using the stored `youtube_id`. To the requester it behaves as if the video were being requested for the first time.
- A purged **upload** can't be restored. It has to be uploaded again; the content hash then matches the old row, which is reused.

## Keeping requested items (the room's history)

There is no separate request history (Siggi, 2026-11-10: "We should have just one history -- it should be play history"). Every room has one **history** of what played there ([rooms.md](rooms.md#history)), visible to everyone in the room, and anyone signed in can **Add to Library** from it, which protects the item from retention. That is how a room owner keeps what was requested while they were away: they add it from the history like anyone else.

- Items that were requested but **never played** (removed from the queue, or rejected suggestions) are not listed anywhere; nobody can keep them from the history, and retention deletes them after the usual period unless someone has them in a library.
- History entries are kept for `rooms.requestHistoryDays` (default 90; the name stayed from the request history so stored values stay valid) and then trimmed.
- Purged items stay listed (title and artist), marked as expired. Adding a purged YouTube item to a library or the queue fetches it again; a purged upload can't be restored, so it has no buttons.
- The old `room_requests` table is gone (migration `0011`): its rows' `via` and requester name were copied onto their queue entries. Library entries added from it keep `via: "request"`.
