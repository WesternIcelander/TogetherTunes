# The new web UI (M9)

Added 2026-11-11 from Siggi's drawing [`drawings/ui_main.png`](../drawings/ui_main.png) and answers. This replaces the provisional UI (CLAUDE.md, "The web UI is provisional"). **More drawings are coming** (see "Not designed yet" at the end); M9's UI steps are written once those exist. The backend pieces below can be built first.

## Layout (desktop)

- **Header:** logo + "TogetherTunes", a search box, the account button.
- **Left sidebar**, tabs **Library** and **Rooms**:
  - **Library:** All My Songs, Movies/Shows, Liked Songs, **Uncategorized Videos** (the user's own library items still uncategorized; only shown while there are any), then the user's playlists. At the bottom, a small section listing the playlists **other users** selected for autoplay in the current room.
  - **Rooms:** "New Room +", then the rooms the user owns or can access, each with its listener count; a search box filters the list, and entering an unlisted room's exact slug (or its URL) shows that room at the top.
- **Middle:** the open view, e.g. a playlist: cover, name, song count and total time, **+ Add from link**, and a table (#, cover, title/artist, album, date added, time). Multi-select rows, then **Add to Queue** plays them in order.
- **Right panel**, tabs **Queue**, **Recents**, **Room**:
  - **Queue:** Now Playing (heart, ×), **Requests** (each with a vote count and the ↑ vote button, ×), **From Playlist** (the upcoming autoplay songs; ↑ turns one into a request, × removes it). Below: the **video panel**, which plays the music video while one plays and the user isn't in Audio Only; it collapses for audio items.
  - **Recents:** Now Playing, then **Earlier** (the room's history), each with a heart.
  - **Room:** the people in the room. Right-click a person for the moderation menu (below).
- **Player bar:** cover, name, artist; previous / play-pause / next; heart (likes the current song); seek bar with times; volume; a menu button with **Audio Only**, **Requests-only mode**, **Now Playing View**, **Fullscreen**.
  - Without permission to control the room, only previous / play-pause / next are replaced, by a **mute** button and a **heart**. Volume and the menu stay. Right-clicking mute offers **Mute player** and **Mute only until end of song**.
- **Now Playing View** fills the window to its edges. Videos are letterboxed by default. Audio items (or Audio Only) show a view like the stream's item card.

## Alone and in rooms

- A user doesn't have to be in a room: **alone**, they play their own music with their own play status (current song, position, paused or playing) and queue. The alone state lives **on the server** (per user), so it follows the user to another device.
- **Recents while alone** shows the user's own play history; songs that played in a room say which room.
- **Joining a room** while alone saves the alone play status and queue.
- **Creating a room** moves the current song and queue into the new room.
- **Leaving a room** to be alone (leaving, or the room being deleted, without joining another one) asks: **fork** the room's current play session and queue into the alone state, or **restore** the saved alone state.

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
- **Ban from server:** admins only; disables the user's account.
- (Future ideas, not in M9: ban a whole home server from the room, or from the server; see [future-ideas.md](future-ideas.md).)

## Library rules

- **Liked Songs** is a built-in playlist per user. A heart adds the song to it.
- **An item in a playlist is always in the library of the playlist's owner** (this replaces "adding to a playlist doesn't add it to your library", [libraries.md](libraries.md#playlists)). So adding to a playlist asks for a type for an uncategorized video ([media-types.md](media-types.md)).
- **+ Add from link** adds to the open playlist and to the library.
- **Drag and drop** into a playlist:
  - **Files** from the file browser are uploaded. One video: ask for its type (the user may leave it uncategorized). Several videos: one type for all of them, or leave them uncategorized. The SHA-256 pre-check ([library-and-ingest.md#pre-check](library-and-ingest.md#pre-check)) and the upload run in the background while the type dialog is open.
  - **A link** opens the Add Link dialog, where the type is chosen.
  - A YouTube link whose item already exists on the server uses that item (and asks for a type only if it's uncategorized, with the option to leave it).
- **Search** covers the whole server, with items in the user's library ranked first.

## Requests-only mode

- **Remote mode** is renamed **Requests-only mode**: the page plays nothing and is used for requests and votes (e.g. for people hearing the room through a stream).
- The room owner can **force** everyone into it (e.g. a party where the room plays on one speaker). Then the room's pages play nothing. **Authenticated users are never refused media.** Only **unauthenticated** visitors (guests, anonymous) get `403` for the room's media when they have no other reason to access the file.

## Not designed yet

See the M9 notes in PLAN.md for the list of screens and decisions still missing.
