# The new web UI (M9)

Added 2026-11-11 from Siggi's drawing [`drawings/ui_main.png`](../drawings/ui_main.png) and answers. This replaces the provisional UI (CLAUDE.md, "The web UI is provisional"). **More drawings are coming** (see "Not designed yet" at the end); M9's UI steps are written once those exist. The backend pieces below can be built first.

## Single-page app

(Siggi, 2026-11-14.)

- **One document.** Every UI URL (`/`, playlists, rooms, settings, admin, …) gets the same HTML shell from the server; client-side routing (History API) decides what to show. Moving around never loads another document, so a room keeps playing without a break.
- **Logging in doesn't reload either.** After login the page fetches the new identity and re-identifies itself (API state, the room socket: reconnect or re-authenticate it), and playback continues through the switch. A guest who logs in inside a room is offered the guest merge ([auth.md](auth.md)) without leaving the page.
- **Logins on another origin** (OIDC / plain OAuth) open in a **popup window** (opened directly from the click, so popup blockers allow it). The callback page in the popup tells the opener (same-origin `postMessage`, or a `BroadcastChannel`) that the login finished, or why it failed, and closes itself. If the popup is blocked, the page says so and offers the full-page redirect as a fallback (which does reload).
- **Logging out reloads the document**, so nothing from the logged-in state lingers.

**As built** (M9 SPA step, 2026-11-14): the server sends `web/static/index.html` for every UI path (`StaticFiles.isAppPath`: not under `/api/`, `/media/`, `/live/`, `/auth/`, `/r/`, `/ws`, `/test/`, `/js/`, `/css/`, not file-like; [api.md](api.md)). It holds the header nav, `<main id="app">` and one `<template id="view-…">` per view; `/js/app.js` starts the shell (`web/src/shell.ts`). The router (`web/src/router.ts`, DOM-free, `router.test.mjs`) maps paths to views: `/` home, `/login`, `/register`, `/password`, `/setup`, `/rooms/`, `/rooms/{ref}`, `/rooms/{ref}/manage`, `/library/`, `/playlists/`, `/playlists/{id}` (playlist deep link; `?id=` still works), `/encodes/`, `/account/`, `/admin/`, `/items/{id}`, anything else the Not found view; the old spellings (`/login.html`, `/library` without the slash, …) are aliases, replaced in the address bar. A view = its template cloned into `#app` plus its module imported under a fresh `?mount=N` (a new module instance per visit); the previous view's `pageSignal()` (nav.ts) is aborted and its element removed. Links, `navigate()` / `reloadPage()` and plain GET forms go through `decide()`; back/forward through popstate (scroll positions in `history.state`). The room view keeps playing while other views are shown (see [rooms.md](rooms.md#playback-across-pages-web)). For now (until the "Login without reload" step), **while a room plays**, a link to login/register/password/setup still loads a new document (leaving the room; another room's link switches in place since the "Alone and switching rooms" step), and a successful login navigates in place without re-identifying an open room socket (none is open then).

## Layout (desktop)

- **Header:** logo + "TogetherTunes", a search box, the account button.
- **Left sidebar**, tabs **Library** and **Rooms**:
  - **Library:** All My Songs, Movies/Shows, Liked Songs, **+ New Playlist** (just below Liked Songs), **Uncategorized Videos** (the user's own library items still uncategorized; only shown while there are any), then the user's playlists. At the bottom, a small section listing the playlists **other users** selected for autoplay in the current room.
  - **Rooms:** "New Room +", then the rooms the user owns or can access, each with its listener count; a search box filters the list, and entering an unlisted room's exact slug (or its URL) shows that room at the top.
- **Middle:** the open view, e.g. a playlist: cover, name, song count and total time, **+ Add from link**, and a table (#, cover, title/artist, album, date added, time). Multi-select rows, then **Add to Queue** plays them in order.
- **Right panel**, tabs **Queue**, **Recents**, **Room**:
  - **Queue:** Now Playing (heart, ×), **Requests** (each with a vote count and the ↑ vote button, ×), **From Playlist** (the upcoming autoplay songs; ↑ turns one into a request, × removes it). Below: the **video panel**, which plays the music video while one plays and the user isn't in Audio Only; it collapses for audio items.
  - **Recents:** Now Playing, then **Earlier** (the room's history), each with a heart.
  - **Room:** the people in the room. Right-click a person for the moderation menu (below).
- **Player bar:** cover, name, artist; previous / play-pause / next; heart (likes the current song); seek bar with times; volume; a menu button with **Audio Only**, **Requests-only mode**, **Now Playing View**, **Fullscreen**.
  - Without permission to control the room, only previous / play-pause / next are replaced, by a **mute** button and a **heart**. Volume and the menu stay. Right-clicking mute offers **Mute player** and **Mute only until end of song**.
- **Now Playing View** fills the window to its edges. Videos are letterboxed by default. Audio items (or Audio Only) show a view like the stream's item card.

**As built** (M9 desktop layout step, 2026-11-14): the frame lives in `index.html` around `<main id="app">` and is drawn by `web/src/frame.ts` (rules in the DOM-free `panels.ts`, `panels.test.mjs`). The middle lists are one router view, `list` (`libview.ts`): **All My Songs** `/songs` (`/` sends signed-in users there), **Movies/Shows** `/shows`, **Liked Songs** `/liked`, **Uncategorized Videos** `/uncategorized`, **search results** `/search?q=` (the header's search box) and a **playlist** `/playlists/{id}` (another person's autoplay playlist with `?room={roomId}`); the old library page (uploads) stays at `/library/` ("Manage library" in the account menu) and the old playlist editor at `/playlists/?id=` ("Edit playlist") until the later UI steps replace them. The header's account button is a menu (Account, Manage library, Playlists, Rooms, Encodes, Administration, Log out). The sidebar shows for signed-in users; the right panel and the player bar follow **the room the room view has open** (shown, or playing in the background as before): the frame has no socket of its own, it reads that view's room state and sends through its socket (`nav.ts` `ShellRoom`), so until the "Alone" step the player bar without a room says "Not in a room". The old mini player is gone (the player bar, and **Leave** next to the room name at the top of the right panel, replace it). The video panel holds the player's `#room-player` (moved out of the room view); it shows only while a video is visible. Volume (squared slider, remembered per browser) and mute are the listener's own gain after the player's meters (`ListenPlayer.setVolume` / `setMuted`, a master gain with Web Audio, the element volume without), so muting never looks like a silent Web Audio path. **Mute only until end of song** unmutes when another entry (or nothing) becomes current. The **Room** tab lists the people present (`presence.people`, [api.md](api.md#server--client)). Autoplay dots in the sidebar show the user's own selections in the current room (read-only until the interactions step); **New Playlist** asks for a name (`prompt`) and opens the new playlist. Double-click, multi-select, right-click menus and drag and drop come with the next steps.

## Room view (desktop)

From [`drawings/room_view.png`](../drawings/room_view.png) (2026-11-14):

- Same header. The left sidebar is hidden behind a **☰ button** at the top left.
- **Video area** large at the top left.
- Below it: **Now Playing** (heart, ×), **Requests** (votes ↑, ×), **From Playlist** (↑, ×), and beside them a **Request Link** box (URL field + Request button).
- On the right: the **people** in the room, each with a **⋯** button that opens the moderation menu, and below them **Earlier** (the room's history, with hearts).
- **Who gets it:** unauthenticated visitors (guests, anonymous) have no library and only ever get the room view, when they open a room by its link. Logged-in users enter it by **double-clicking Queue, Recents or Room** at the top of the right panel, and leave it by opening the left sidebar and clicking a playlist.
- The **right panel is hidden** in the room view. The **☰** button reveals the left sidebar, which **pushes the page aside** (no overlay).
- The **player bar stays** at the bottom (not drawn).
- **During an audio item** the video area shows the cover and title like the stream's item card, and the title line under the video collapses away.

**As built** (M9 room view step, 2026-11-14): the room view is the `room` router view (`/rooms/{ref}`) in a layout mode: while it is shown the body has the class `room-view`, the right panel is hidden, the left sidebar hides behind **☰** (`#app-sidebar-toggle`, signed-in users only; `sidebar-open` shows it in the flex row, so it pushes the page aside), and the frame **moves its video panel** (`#app-video`, with the player's `#room-player`) into the view's video area (`#rv-video-slot`) and back to the right panel when another view is shown or the view goes away (`Frame.release`, called by the shell before it hides or removes a view, in the same task, so the media keep playing). In the video area the panel always shows: the video, or a card (cover, title, artist; "Nothing is playing") for audio items, requests-only mode and Audio Only; the **title line** under it (`#rv-title`) shows only while a video plays; **vote to skip** sits below it. The frame draws **Now Playing / Requests / From Playlist** (`#rv-queue`, the same rows as the Queue tab), the **people** with **Manage Room** above them and a **⋯** per person (`#rv-people`) and **Earlier** (`#rv-earlier`, the room state's recent history, with hearts) from the room state; the room view's module draws the header, the **quick output buttons** (`#rv-outputs`, `outputs.ts quickOutputs`: **Turn on** for the owner where the server allows an output that is off (a new RTMP push needs its target, so that one stays in Manage Room), **Copy link** for the owner and moderators for one that is on; the RTMP one copies the viewer URL), the **Request Link** box (`queue.add {youtubeUrl}`, for people with `you.canYoutube`) and below it **Join as guest** (anonymous visitors where guests may do anything in the room) or the guest's name with an **✎** edit button (where the admin allows custom names). Logged-in users enter it by **double-clicking** Queue, Recents or Room in the right panel; clicking a playlist in the ☰ sidebar leaves it (a room playing in listen mode keeps playing, as before). Unauthenticated visitors only ever have the room view (no sidebar, no ☰, no right panel). The **⋯ moderation menu** (the same in the Room tab) looks up the person's room role (`GET …/members`) when it opens; items by `panels.ts personActions`: **Add / Remove Moderator** (owner, users), **Timeout…** (a typed length: `10` = minutes, `30s`, `2h`, `1d`, `1h 30m`; `parseDurationInput`, 1 s to 10 years), **Ban** (`PUT …/bans/users|guests/{id}`; never the owner, a moderator only by the owner, no timeout for moderators), **Ban from server** (`admin.users`, users: disables the account). **"Click to enable sound"** (`#room-listen-overlay`, now in the frame's video panel) is a translucent layer over the video area only; outside the room view the video panel shows (with the card) while it is up. The old room page's other parts (queue editor with pin and move, search / upload / playlist requests, the stream outputs panel, the full history, Web Audio and diagnostics) stay below in a collapsed **More** section until the later UI steps give them their places.

## Mobile (narrow windows)

From [`drawings/mobile_view.png`](../drawings/mobile_view.png) (2026-11-14):

- One column. A **top bar** with a back button (e.g. "‹ Library") and the page title; the title is hidden from the top bar while the page is scrolled to the top (the page's own large title shows instead) and appears once it scrolls away.
- Lists drop the extra columns: number, cover, title and artist.
- A **bottom tab bar**: **Library**, **Now Playing**, **Room** (sub-tabs **Rooms** | **This Room**), **Search**, **Account**.
- A **mini now-playing bar** just above the tabs.
- **Now Playing** tab: the video (or the card view for audio) at the top, then sub-tabs **Up Next** | **Recents** below it; scrolling down moves the video up off the screen.
- **Library** tab: the same list as the desktop sidebar.
- Switches to this layout below about **800 px** wide (a phone always, an iPad in landscape gets the desktop layout).

**As built** (M9 mobile layout step, 2026-11-14): below 800 px (`@media (max-width: 799.98px)` in main.css, `MOBILE_QUERY` in the DOM-free `web/src/mobile.ts`, `mobile.test.mjs`) the frame (`frame.ts`) turns the header into the **top bar**: **‹ back** (`mobileBack`: a list → Library, the room view → Rooms, the account menu's pages → Account, an item page or Manage Room → the browser's Back) and the page's title, faded in once the page's own large title (the view's `h1`, or the pane's title) has scrolled under the bar; on the Search tab the bar holds the search box. **Bottom tabs** (`#m-tabs`, signed-in users): **Library**, **Now Playing**, **Room**, **Search**, **Account**. Library, Now Playing, Rooms and Account are **panes** the frame shows instead of the view (the view stays underneath, hidden; a room keeps playing): the sidebar's Library or Rooms list, the right panel with the video panel on top (always shown there, the card for audio items) and **Up Next | Recents** below (the Queue tab is labelled Up Next; the people are on This Room), and an account menu (the header menu's links and Log out). Each pane is a history entry of the view's own URL with `mPane` in `history.state`, so the browser's Back closes it and a navigation from it (a playlist, a room) replaces it. The Room tab goes to the room view of the room open here (**This Room**) or, without one or from the room view, the room list; its sub-tabs **Rooms | This Room** sit above both. The player bar becomes the **mini now-playing bar** above the tabs (cover, title, play/pause or mute, heart, ⋯; tapping the song opens Now Playing; the ⋯ menu also has Mute / Mute only until end of song). Lists show only #, cover, title and artist, plus a **⋯** per row (Add to Queue in the current room, Open, Select; the same menu on right-click and **long press**). **Select** (narrow windows) turns on the Select mode: checkboxes, a tap toggles a row, and a bar above the mini player with **Add to Queue** (the selected songs in the list's order, one `queue.add` each). In the Library pane, with a room open, a **long press** on a playlist toggles it for autoplay there and its **⋯** has Use / Stop using for autoplay, **Use only this one for autoplay** and Open. ⋯ menus (rows, playlists, people) open as a sheet at the bottom of the screen. Long press (`touch.ts onLongPress`): touch or pen held 500 ms within 10 px, or the `contextmenu` event; the click that ends it is swallowed. Unauthenticated visitors have no tabs: the room view in one column with the mini bar.

## Interaction rules (all layouts)

- **Requesting:** double-click (double-tap) an item in a playlist or in search results to request it in the current room.
- **Alone**, double-click does what the user chose in their **user settings** (stored on the server per user; default **Play immediately**):
  - **Play immediately:** the item starts playing now.
  - **Queue it:** the item is added to the bottom of the **manually added** section of the queue. Manually added items always come before the queue's other items (e.g. autoplay songs).
- **Touch:** everything on a right-click menu is also on a **⋯** button (people, songs, the mute button). **Long press** a playlist toggles it for autoplay; its ⋯ menu has **Use only this one for autoplay** (the double-click's job).
- **Multi-select:** select several items (Shift/Ctrl/Cmd-click on desktop; a **Select** mode with checkboxes on touch) for **Add to Queue** and similar actions.

**As built** (M9 interactions step, 2026-11-14; rules in the DOM-free `web/src/interactions.ts`, `interactions.test.mjs`): **double-click** a song row in any list (playlist, Liked Songs, All My Songs, search, …) requests it in the room open here; in the user's alone room it plays it now (added, moved before the first manually added entry, then `next`) or queues it, by `doubleClickAlone`. A single click on a song's title opens its item page after 300 ms (a double-click cancels that). **Desktop multi-select:** click selects a row, Ctrl/Cmd-click toggles one, Shift-click selects a range; a bar ("N songs selected", Add to Queue in list order, Cancel; Escape clears) shows while something is selected. **Right-click menus** (each also on a ⋯ and long press): songs (Add to Queue, or alone Play now / Add to Queue; Open; Copy link; Remove from playlist / from Liked Songs / from library…; Select), playlists in the sidebar (Use / Stop using for autoplay, Use only this one, Open, Rename…, Delete…; Liked Songs without the last two), queue entries (vote, **Pin to top** / Unpin in vote mode for the owner and moderators, Request it, Retry, Remove, Accept / Reject / Withdraw for suggestions, Skip for Now Playing, Open; a ⋯ on every entry), people (the moderation menu) and mute. **Autoplay selection:** with a room open, the dot before each of the user's playlists (Liked Songs included, once it exists) is a toggle button; Ctrl/Cmd-click toggles, double-click makes it the only one, a plain click opens it; on touch a long press toggles it.

## Alone and in rooms

- A user doesn't have to be in a room: **alone**, they play their own music with their own play status (current song, position, paused or playing) and queue. The alone state lives **on the server** (per user), so it follows the user to another device. **Guests can't be alone:** they only exist inside rooms (they have no library).
- **Recents while alone** shows the user's own play history; songs that played in a room say which room.
- **Joining a room** while alone saves the alone play status and queue.
- **Creating a room** moves the current song and queue into the new room.
- **Leaving a room** to be alone (leaving, or the room being deleted, without joining another one) asks: **fork** the room's current play session and queue into the alone state, or **restore** the saved alone state.

**As built** (M9 alone and switching rooms step, 2026-11-14; rules in the DOM-free `web/src/alone.ts`, `alone.test.mjs`): the alone state plays through the ordinary room view and player. Whenever a signed-in user has no room view open, the shell (`shell.ts`) starts one **in the background** (hidden from the start; `nav.ts viewStart` gives it its own path and signal) for the play session's room: the room they are in, otherwise their alone room. The right panel then says **On your own** (no Leave, no Manage Room), and **Recents** shows Now Playing and the user's play history (`GET /api/me/history`), songs from a room with "in <room>" (linked while it exists). Following another room's link **switches rooms in place** (no new document; the old room view goes) and joins it (`POST /api/me/play-session/join`, which saves the alone state). **New Room** sends `moveAloneSession: true`. **Leave** asks fork or restore in a small dialog (Cancel keeps the room), then the alone room plays; a room deleted while the user is in it asks the same (no Cancel). The Rooms tab and the `/rooms/` page list rooms with their listener counts and a search box (an unlisted room's exact link name or link comes first).

## Autoplay from playlists

- Autoplay belongs to the **current room**. The owner and the users the owner allows (room setting: **owner only**, **moderators**, or **every member**) pick which playlists autoplay draws from; each person selects among **their own** playlists in the Library tab. Single click opens a playlist; **double-click** makes it that person's only autoplay playlist (it clears only **their own** other selections, never other people's); **Ctrl-click** (Cmd-click on macOS) toggles one. Any number can be selected, including zero (then that person contributes nothing). The dot in front of a playlist marks it as selected.
- Other people's playlists are invisible, **except** those selected for autoplay in the room (listed at the bottom of the Library tab).
- **Always shuffled.** With several playlists, a room setting picks **fully random** (one pool) or **take turns** (between the **people** who selected playlists; each turn picks from that person's selection).
- **Liked Songs** can be selected for autoplay like any other playlist (it is an ordinary playlist row in the database).
- **Upcoming songs:** a room setting gives how many "From Playlist" songs to show; when fewer are upcoming, autoplay adds more to fill it up.
- **Recently played** songs get weight 0 right after playing in the room, and their weight **ramps up gradually** to full until their last play is X songs ago, where X is a room setting in percent of the songs available to autoplay (default 50 %).
- **Requests vs. autoplay:** a room setting: requests always play first, or alternate between a request and an autoplay song.
- **Removing:** × deletes an entry from the queue: the owner, a moderator, or the user who added it. For an autoplay entry, the person whose playlist it came from counts as the one who added it.
- **Votes:** the number next to ↑ is the entry's votes; ↑ is the vote button.

## Moderation menu (right-click a person in the Room tab)

- **Add / Remove Moderator**.
- **Timeout:** for a length the moderator types in (any duration).
- **Ban:** until unbanned.
- A timeout or ban puts the room into a **read-only state** for that person: they can still be in the room and listen, but can't request, vote, like into the room's queue or otherwise change anything in it.
- **Room bans follow the IP address** (Siggi, 2026-11-14; the IP key as elsewhere: IPv4 address or IPv6 /64, stored like `guests.key_hash`, never shown without `ip.view`):
  - Banning a **guest** bans their IP address in the room.
  - Banning a **logged-in user** bans their account **and** their current IP address in the room.
  - A logged-in user who isn't banned but comes from a banned IP address is **treated as banned**, unless the room has added them as a **member**.
  - Unbanning lifts the account and the IP address together. Timeouts work the same way.
- **Ban from server:** admins only; disables the user's account.
- As implemented: [rooms.md](rooms.md#bans-and-timeouts) (`/api/rooms/{id}/bans/...`; Ban from server is `PATCH /api/admin/users/{id} {disabled: true}`, shown to holders of *admin.users*).
- (Future ideas, not in M9: ban a whole home server from the room, or from the server; see [future-ideas.md](future-ideas.md).)

## Library rules

- **Liked Songs** is a built-in playlist per user. A heart adds the song to it. **Guests can't heart songs** (no heart shown for them).
- **Guest merge** (when a guest logs in or registers in the browser holding the guest cookie, [auth.md](auth.md)) also moves **the items the guest added** to the account (`added_by`, so they can still edit them) and adds them to the account's library.
- **An item in a playlist is always in the library of the playlist's owner** (this replaces "adding to a playlist doesn't add it to your library", [libraries.md](libraries.md#playlists)). So adding to a playlist asks for a type for an uncategorized video ([media-types.md](media-types.md)).
- **+ Add from link** adds to the open playlist and to the library.
- **Drag and drop** into a playlist:
  - **Files** from the file browser are uploaded. One video: ask for its type (the user may leave it uncategorized). Several videos: one type for all of them, or leave them uncategorized. The SHA-256 pre-check ([library-and-ingest.md#pre-check](library-and-ingest.md#pre-check)) and the upload run in the background while the type dialog is open.
  - **A link** opens the Add Link dialog, where the type is chosen.
  - A YouTube link whose item already exists on the server uses that item (and asks for a type only if it's uncategorized, with the option to leave it).
- **Search** covers the whole server, with items in the user's library ranked first.
- **Playlist order:** users drag their playlists up and down in the sidebar to reorder them (stored per user on the server). The built-in entries above them (All My Songs, Movies/Shows, Liked Songs, New Playlist, Uncategorized Videos) stay where they are.
- **Dragging items into a playlist** (drop on it in the sidebar, or into an open playlist at a position) works from other playlists, the queue, Recents and search results, as well as the file browser and links (above).
- **Reordering by dragging** (multi-select drags all selected items together):
  - **Playlists:** items in a playlist can be dragged into a new order.
  - **Queue:** entries can be dragged into a new order, and between the **manually added** (Requests) section and the **From Playlist** section, **when the room isn't in vote mode** (vote mode orders by votes). Who may do it follows the room's control mode, like other queue edits; alone, the user's own queue always allows it. Dragging an autoplay entry into the manually added section makes it a manual entry; dragging a manual entry into From Playlist makes it part of the autoplay section.
  - **Recents** can't be reordered, but items can be dragged **out** of it (to a playlist, the queue, …), with multi-select.
  - **Dragging out never needs queue rights:** someone who can't edit the queue can still drag items from the queue or Recents to their own playlists (and anywhere else they may add to). Only reordering or changing the queue itself needs the rights.
- As built (M9 drag and drop step, 2026-11-14; desktop only, touch screens don't start HTML drags): rules in the DOM-free `web/src/dragdrop.ts` (`dragdrop.test.mjs`), the page side in `dnd.ts` (draggable rows with a payload, drop zones with a line before the row a drop lands at, or the whole target highlighted; files or links dropped outside a zone are swallowed so the page never navigates away). **Playlist view** (own playlists and Liked Songs): rows drag out (the selected rows together), and the view takes drops at a position: its own entries move (`POST …/entries/move`), songs from elsewhere are added there (`POST …/entries` with `beforeEntryId`, each in order), files are uploaded into it, a link opens Add from link (`addlink.ts`, `beforeEntryId`). Other lists (All My Songs, search results, Uncategorized, someone else's playlist) only drag out. **Sidebar:** the user's playlists drag up and down (`PUT /api/playlists/order`; Liked Songs and the links above stay put); each playlist (and Liked Songs) takes songs (added at the end), files and links. **Queue** (right panel and room view): the Requests and From Playlist sections take queue entries (`queue.move` with `section`, shown and allowed only with the rights: owner mode owner/moderators, open mode anyone who may request, alone always, never vote mode or read-only) and songs from elsewhere (`queue.add`, the room's request rules); Now Playing and suggestions drag out only. **Recents** rows drag out. Queue and Recents rows have their own multi-select (Shift / Ctrl / Cmd-click; a plain click or Escape clears it), and a drag from a selected row carries the selected rows of that list. **Files** (`filedrop.ts`): every file starts uploading at once (`uploads.ts`: pre-check hashing on https, then chunks, one file at a time); for videos (by MIME type, else extension) the type question opens at the same time: one video gets the full form, several get one type for all (show is left out: an episode number per file can't be shared); both offer **Leave uncategorized**; an upload waits for the answer only before `complete` (new `Upload.beforeComplete` hook), Cancel stops them all. Each finished item is added to the playlist at the drop position (which also puts it in the library). A box at the bottom right shows each file's state.

## Requests-only mode

- **Remote mode** is renamed **Requests-only mode**: the page plays nothing and is used for requests and votes (e.g. for people hearing the room through a stream).
- The room owner can **force** everyone into it (e.g. a party where the room plays on one speaker). Then the room's pages play nothing. **Authenticated users are never refused media.** Only **unauthenticated** visitors (guests, anonymous) get `403` for the room's media when they have no other reason to access the file.
- As built (M9 backend step): the room setting `requestsOnly` (off by default; Manage Room → Playback, "Requests-only room"). **API names stay `remote`** (`?mode=remote`, the socket's `mode`, `presence.remote`); only user-visible text says requests-only. "No other reason" = another room they are in, not forcing it, where the item is current or next. Details in [rooms.md](rooms.md#remote-mode) and [libraries.md](libraries.md#media-access-rules).

## Existing features in the new UI

(Siggi, 2026-11-14, answering what the drawings left out.)

**Rooms**

- **Suggestions** (owner control mode): pending suggestions show **above the queue** for people who may edit the queue (with Accept / Reject), and **below the queue** for everyone else.
- **Vote to skip:** a button below the video in the room view, and below the Now Playing item in Queue / Recents.
- **Pin to top** (vote mode): in the entry's right-click menu (⋯ on touch).
- **Entries that aren't ready:** a third line under the title and artist shows the status (downloading, encoding, …). A failed one shows **Failed** in red with a **Retry** button.
- **Add a whole playlist to the queue:** in the playlist view, "Add All:" with two buttons, **In Order** and **Shuffled**.
- **Guests:** under the request section, **Join as guest**; once joined, the guest's assigned name with an **edit** button (where the admin allows choosing a name).
- **Sound blocked by the browser:** a translucent dark layer over the **video area only**, with "Click to enable sound" in the middle. Clicking anywhere else on the page works normally and doesn't start sound.
- **Manage Room** button above the people list, in the room view and in the right sidebar's Room tab. The management page holds members and roles, moderators, handing over, leaving, bans and Unban, visibility, link name, deleting the room, and all room settings including the **stream outputs** (URLs, private-token rotation, the plain-HTTP warning).
- **Quick output buttons:** for the owner and moderators, in the room view **above the Request Link box**: an output the server allows but the room has off gets a **Turn on** button; one that is on gets **Copy link**.
- **Upload requests** have no place on phones for now (drag and drop is desktop only); revisit later.

**Library**

- **Add from link** also on **All My Songs** (adds to the library only). Uploading from phones: later.
- **Song ⋯ button** beside each song in playlists: remove from library, remove from playlist, copy link, open the item page (and the actions above where they apply).
- **Rename a playlist:** right-click → Rename (long press on touch opens a menu with Rename). Deleting likewise.
- **Sharing songs with another user is dropped**: people copy the link and send it however they like. The share API and UI go; existing shared library entries stay as ordinary library entries.
- **Browsing other users' libraries** (`library.viewAll`): no place yet; decided later.
- **Guests never download.** The `guests.download` setting goes. A guest (or anonymous visitor) gets media only while they have a reason to: in a room where that item is playing or up next. Otherwise `403`.

**Accounts**

- **User settings** has tabs: preferences (double-click behaviour, **Web Audio** (only here), **debug mode**), account (password, linked logins), and **sessions** (devices with Revoke, API tokens).
- **Debug mode** shows a **Send diagnostics** button at the top of the page.
- **Invites:** invite-only registration needs a UI to create and hand out invites (admins, and non-admins whose role has the new permission `users.invite`).

**Routing**

- Every page URL serves the same document, **except `/test/`**, which keeps its own pages. (Built: see [Single-page app](#single-page-app).)

## Not designed yet

Until Siggi draws them (2026-11-14), screens without a drawing **follow the existing design**: same header, sidebars, lists, ⋯ menus and dialogs as the drawn screens, and the mobile rules above. Missing so far: room settings, item page, search results, Movies/Shows, the classify dialog, login/signup/setup and the guest name prompt, account and user settings, New Room, the song ⋯ menu, admin pages, loading/failed/empty states, colours and theme.
