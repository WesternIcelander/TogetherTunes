# Libraries, playlists and retention

## Items vs. libraries

An **item** is a media object stored once on the server: its files, metadata and ingest state. A **library** is a per-user set of references to items (`library_entries`). The same item can be in any number of libraries; it is never copied.

- **Adding** an item by upload or YouTube puts it in the adding user's library (`via='upload'|'youtube'`). Guests have no library. (Before M9 a request made inside a room had an "also add to my library" checkbox; it is gone.) **In the new UI** (Siggi, 2026-11-14) there is no checkbox: a signed-in user's request **by link or by dropping a file or link** (Request Link, drops on the queue or room view) always adds the item to their library as well; a video whose type isn't known yet goes in as `uncategorized` (no classify question, it shows under Uncategorized Videos). Requesting an item that already exists by its id (from a list or search) doesn't add it. On a **guest merge** the items of the guest's link and file requests are added to the account's library the same way (not only the items the guest fetched first). As built (2026-11-14): `RoomQueue.add` calls `Library.addRequested` after the queue entry is written (users only, not for `itemId`/`historyEntryId` requests): an entry with `via` `youtube`/`upload` unless one exists, no classification (the item keeps its type, an `uncategorized` video stays so), the item counts as referenced, a new entry queues the quality re-encode like any library add; a failure is logged and the request stands. The import or upload itself runs without the library add, so a new item's job keeps the quick tier (the room wants it now).
- **Dedupe:** re-adding content that already exists reuses the existing item:
  - YouTube imports match on `youtube_id` (see [library-and-ingest.md](library-and-ingest.md#youtube-caching));
  - uploads match on the SHA-256 of the uploaded file.
  A match only adds a library entry or queue entry. No download, no transcode.
- **Removing** an item from your library only deletes your entry. The item itself is deleted by retention, or by someone with `library.purge`.
- **Metadata** (title, artist, album, cover) belongs to the item and is therefore shared. It can be edited by the user who added the item (`items.added_by_user_id`), while it is in their library, and by anyone with `library.editAny`. A guest who added an item can't edit it (guests have no library). Per-user metadata overrides are out of scope for v1. The API edits title, artist, album and `presentAsAudio`; replacing the cover isn't implemented yet. **Open (M4):** an edit doesn't re-render the item's `card.mp4` yet, so the card keeps the old text until the item is re-encoded; the card (and, for audio items, `index.bin`, which indexes the card as the video track) must be rebuilt before the packager shows cards.
- **Purging** (`library.purge`, or the retention sweeper) keeps the row and its metadata and sets `status='purged'`. Queued ingest jobs of the item are cancelled (`failed`); while a job is running the purge is refused (`409 item_busy`). Inside the purge transaction `media/<itemId>/` and the item's upload staging directory are renamed aside (`.purge-<name>-<uuid>`), then deleted after the commit; leftovers of a crash are deleted at the next start. Library and playlist entries stay (the item shows as "media expired").

## Playlists

- Owned by one user, private, and made of ordered `playlist_entries` (fractional `position`). An item can appear in a playlist more than once (not in Liked Songs).
- Ordering: an entry added at the end gets the last position + 1 (rounded down to a whole number first); one added or moved before another entry gets the midpoint between that entry and its predecessor (or that entry's position − 1 at the front). When two neighbours are too close for a midpoint (about 50 insertions into one gap), the playlist is renumbered 1..n in the same transaction. **Several entries move in one call** (`POST /api/playlists/{id}/entries/move`: the entries, in the given order, before one entry or at the end; before one of the moved entries means before the next one that stays): they are spread evenly over the gap between their new neighbours, or the playlist is renumbered when the gap is too small. Entry changes lock the playlist row first (`FOR UPDATE` on PostgreSQL), so concurrent edits of one playlist can't pick the same position. Clients only see the order, never the positions.
- **An item in a playlist is always in the library of the playlist's owner** (M9, [ui.md](ui.md#library-rules); this replaced "adding to a playlist doesn't add it to your library"). Adding to a playlist adds the item to the owner's library first, with the same rules as `PUT /api/library/{itemId}`: an `uncategorized` video not yet in that library needs a type (`409 needs_classification`, [media-types.md](media-types.md#media-types)); the insert transaction puts the library entry back if it was removed meanwhile. **Removing an item from the library removes it from all of that user's playlists** (the UI asks first, naming them: `GET /api/library/{itemId}/playlists`). Removing an entry from a playlist, or deleting a playlist, leaves the library alone. Migration `0028_playlists_v2` added the library entries that were missing (`via 'manual'`).
- Adding follows the same item rules as adding to a library: a purged or failed upload can't be added; a purged or failed YouTube item is fetched again. Limits: 1000 playlists per user (Liked Songs not counted), 10 000 entries per playlist.
- **Liked Songs** is a built-in playlist per user (`playlists.builtin = 'likes'`), an ordinary playlist row otherwise (its entries are read, moved and removed like any other's, and it can be selected for autoplay): made on demand (the first like, or opening it), never renamed or deleted (`409 builtin_playlist`), each item at most once, a new like goes to the top. The heart is `PUT`/`DELETE /api/me/likes/{itemId}`; guests can't like (`401`). Unliking keeps the item in the library.
- **Playlist order:** each user orders their own playlists (`playlists.position`, `PUT /api/playlists/order` moves some of them before another or to the end). A new playlist goes to the top; Liked Songs is always listed first and can't be moved. Renaming or changing a playlist doesn't move it (`updatedAt` still changes).
- In a room, the owner and moderators can enqueue a whole playlist, or shuffle it into the queue. In `vote` mode, playlist entries are added as normal queued entries with zero votes.
- Sharing playlists is out of scope for v1 (items aren't shared either: see below).

## Sharing (removed)

Sharing items with another account (`/api/shares`, a share adding the item to the recipient's library with a notification) was **removed in M9** (Siggi, 2026-11-14, [ui.md](ui.md#existing-features-in-the-new-ui)): people copy an item's link and send it however they like; anyone with the link can open the item page and add it to their library. Migration `0027_remove_sharing` turned the entries shares had made into ordinary library entries (`via='manual'`; `shared_by_user_id` dropped) and dropped the `shares` table (the senders' history and unseen notifications).

## Visibility

| Who | Can see |
|---|---|
| Any user | Their own library and playlists |
| `library.viewAll` | Every user's library and playlists (read-only), searchable by user (`/api/users?q=` finds users by username or display name; their library can be searched like your own) |
| Room members (users and guests) | Inside a room: the **room owner's** library and playlists plus **their own** library, or with "Search the whole server" every item on the server (below) |
| Anyone who may open a room | The playlists selected **for autoplay** in that room (name and entries, through `GET /api/rooms/{id}/autoplay/playlists/{playlistId}`, only while selected; [rooms.md](rooms.md#autoplay)); nothing else of their owners' |
| Any user | With the search (`GET /api/search`, or `GET /api/items/search`): every item on the server |
| Anyone | Any item's page, **by direct link** (`/items/{id}`), whether or not it's in their library |

**Any item on the server is fair game** (Siggi, 2026-11-10: "If an item is available on the server, it's fair game. [...] Only thing that should be private about the entire server's library is which users libraries an item is in."). The only private thing is **which users' libraries (and playlists) an item is in**. Item metadata (title, artist, album, kind, length, status) is not private.

**Room request search** (`GET /api/rooms/{id}/search`): always the room owner's library and playlists **and** the requester's own library (guests have none: the owner's only), each item once. A **"Search the whole server"** tickbox (`all=1`; unchecked by default, remembered per browser) widens it to every item on the server that isn't purged: all users' libraries and playlists, and items in no library yet (e.g. recent guest requests awaiting retention). Room setting `libraryRequests` off turns the search off for everyone but the owner and moderators (`403 library_requests_off`).

**Library page** (provisional UI, removed at the end of M9): its search had the same **"Search the whole server"** tickbox for signed-in users (`GET /api/items/search`, still served), with the same results and privacy rules. The web UI's room view uses the room request search (without the tickbox) only for visitors who aren't signed in; signed-in users use the header's search below.

**Search in the new UI** (M9, `GET /api/search`): the whole server as above, with the items in the searcher's own library ranked first (each part newest first by item id). Only the searcher's own library affects the order, so it reveals nothing about anyone else's. Guests and anonymous visitors search only from a room where they may request from the library (`roomId`; the request search's rules), and find what they may request there.

**Requests by item id** (`queue.add` with `itemId`): any item that exists and isn't a purged or failed upload, for anyone allowed to request from the library at all; there is no scope check (the former room setting `requestScope` and `403 not_in_request_scope` are gone; stored values are ignored). The other request rules stay: `requestsPerActor`, `noRepeatWindow`, guest capabilities (`guests.canRequestLibrary`), control mode (owner mode → suggestion), `libraryRequests`, and the item's own rules (unknown `404 item_not_found`, failed upload `409 item_failed`, purged upload `409 media_expired`; a purged or failed YouTube item is fetched again).

As implemented (`library/ItemSearch`, used by `rooms/RequestSearch` and `GET /api/items/search`): one search implementation. A result is only item metadata (`Library.ItemView`), plus `inYourLibrary` on `/api/items/search`; no `addedAt`, `via` or "in X's library" hints. Results are ordered by item id (newest item first: the item's creation, the same for everyone), never by when a library got it, and the whole-server mode has no grouping, so neither the order nor the paging reveals library membership. Excluded: failed and purged uploads; in whole-server mode also purged YouTube items (failed and purged YouTube items in the owner's or requester's library are listed, since requesting one fetches it again). The item page (`GET /api/items/{id}`) shows the item's metadata, its first `addedAt`, and the viewer's own `you.inLibrary`/`canEdit`/`canPurge`/`canRetry`; it carries no adder and nothing about other users' libraries (`items.added_by_user_id` is only used server-side for the edit right, which requires the item to be in the adder's own library).

## Media access rules

Every media route (`GET /media/{itemId}/{file}` and `GET /media/{itemId}/v/{N}/{file}` with `audio.m4a`, `audio-only.m4a`, `mezz.mp4`, `original`, `cover.jpg`, and the view report `POST /api/items/{id}/view`) is decided as follows:

1. **Logged-in user:** allowed. A direct link is enough; the item doesn't have to be in their library.
2. **Guest or anonymous** (**guests never download**, M9: the former `guests.download` setting is gone): allowed **only if** the item is the **current** entry, or the **next** entry, in the queue of a room **they are in** right now. The next entry is allowed so clients can preload it. A room that **forces requests-only mode** (`requestsOnly`, [ui.md](ui.md#requests-only-mode)) doesn't count: its pages play nothing, so being in it is no reason to fetch (another room they are in that plays the item still is). Otherwise: `403 forbidden`.
3. Cover images are always allowed when metadata is visible. They are needed to render request search results and now-playing info.

**"A room they are in"** means a room one of their WebSocket connections has joined (in listen or remote, i.e. requests-only, mode); leaving the room, joining another or closing the socket ends it. A guest is recognised by its guest id. An anonymous visitor has no identity, so it is recognised by its **IP key** (IPv4 address, IPv6 /64, as for IP-mode guests): an anonymous media request counts if an anonymous connection from the same IP key is in the room. A guest's media request also counts through its IP key (it could drop its cookie and ask anonymously anyway). These keys live only in memory (`rooms.RoomViewers`) and are never sent anywhere. Accepted consequence: people behind one NAT share an anonymous visitor's access, as they share an IP-mode guest.

The check happens once, when the request starts. A Range response that is already streaming isn't cut off when the item stops being current; the client's following Range requests will be refused.

"Next" is computed from live room state, so in `vote` mode it can change between the preload and playback. A refused preload simply falls back to loading at play time.

To keep the check fast, the room executors maintain in memory each room's current and next items (`rooms.LivePlayingItems`), and the room socket maintains which rooms each guest and anonymous IP key is in (`rooms.RoomViewers`); the check looks up the caller's rooms and asks whether one of them lists the item and doesn't force requests-only mode (each executor publishes that flag with its items, so a settings change applies at once).

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
