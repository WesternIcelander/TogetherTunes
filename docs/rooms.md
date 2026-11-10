# Rooms

## Room state

Each room has authoritative state that lives in memory, inside the room's single-threaded executor. Every change is persisted and broadcast with an incremented `version`.

```
Room {
  id, slug, name, ownerId, visibility, controlMode, settings
  queue: List<QueueEntry>        // upcoming entries, in play order (plus suggestions in owner mode)
  history: last N played entries (the full history is read from the database)
  timeline: { state, entryId | null, anchorServerMs, anchorPositionMs }
  members: online connections (actor: user|guest|anonymous, mode: listen|remote)
  version: long
}
```

`state` is an enum, `PlaybackState { IDLE, PLAYING, PAUSED }`. `IDLE` means `entryId` is null. The enum is meant to be extended (for example with `LIVE`, see [future-ideas.md](future-ideas.md)). Code that switches on it must handle unknown values explicitly, never with an `else` that assumes "playing". Clients treat an unknown state like `PAUSED`, which freezes the position.

**Current position** = `state == PLAYING ? anchorPositionMs + (serverNow − anchorServerMs) : anchorPositionMs`.

Every timeline change (play, pause, seek, skip, advance) **re-anchors** the timeline: `anchorServerMs = now`, `anchorPositionMs = newPosition`. Clients always work the position out from the latest anchor.

**Advancing:** the executor schedules a task for when the current entry ends. That task moves to the next entry, chosen according to the control mode, or sets `state = IDLE` (and `entryId = null`) if the queue is empty. The task is rescheduled on every re-anchor.

**Entries that aren't ready:** an entry whose item is still being ingested (for example a YouTube request that is still downloading) stays in the queue, marked "processing", and is **skipped over** when choosing the next entry. It becomes eligible as soon as its item is ready: every status change of an item (ingest done or failed, a re-ingest starting, a purge) is announced to each room that has it queued, suggested or current, which refreshes the entry for its clients and, if the room is idle, starts the next eligible entry. A failed entry stays in the queue, marked failed, and is skipped; a current entry whose item failed or was purged is skipped.

**"Ready" means playable by the program output too** (added 2026-11-10, Siggi: "when the current item finishes, if the next item in the queue is not ready yet, it should remain in the queue, but skip over to find the next item that can be played"). Rooms and the packager share one notion of playable (`ingest.PlayableFiles`): the item is `ready` with a known duration **and** its files are usable by the program output: its `output_profile` is the output encoding in use, and the `index.bin` of its current media version reads and passes the packager's own checks (encoding, reference avcC, an audio track, an audio item's card track), the files it names exist, and a video item has its `card.mp4`. A `ready` item that fails this (files of an older encoding waiting for their re-encode, a missing or unreadable `index.bin`) is treated like one still processing: it stays in the queue (or among the suggestions) **shown as "processing"** (with its re-encode job's progress, if any), is skipped over when choosing the next entry, and isn't the "next" entry the program output preloads, so the stream never sits on "Loading…" for the length of an item it can't open. A positive result is cached per item, media version and encoding (published versions never change); a failure is checked again whenever the room chooses and logged once. When a re-encode publishes, its job's completion tells the rooms like a status change, so an idle room starts the entry at once; an admin's encoding switch makes every loaded room re-read its entries. The current entry is not skipped when its files become unusable mid-play (e.g. the encoding was switched): the program output shows Loading for it, as before, and web players (which play `mezz.mp4`/`audio.m4a` directly, by the item's own `ready` status and URLs) keep playing. The same rules hold for `next` (skip) as for a natural end, and `previous` doesn't go back to such an entry.

**Retrying a failed YouTube entry** (added 2026-11-10, Siggi: "If a YouTube yt-dlp item fails to ingest, there should be a retry button both on its page and where it's listed in the queue."): a queued entry or suggestion whose YouTube item failed has a **Retry** button next to its "Failed: <short reason>" badge (`queue.retry`, [api.md](api.md#websocket)). It shows for the owner and moderators, and for whoever requested the entry while they may still add YouTube links in the room (a guest only while the server's and the room's `guests.canYoutube` allow it). Retrying queues a new download for the item ([library-and-ingest.md](library-and-ingest.md#retrying-a-failed-youtube-item)): the entry **stays where it is** and turns back into "Waiting to download…", and once ready it is eligible again like any entry that wasn't ready. Failed uploads have no Retry button: their file isn't kept on failure, so the uploader uploads it again.

**What a waiting entry shows** (added 2026-11-10, Siggi: "Having additional feedback can alleviate issues with patience."): while an entry's item isn't ready, the room tells its sockets what the item's ingest job is doing, with the room state but outside its versions (the `ingest` message, [api.md](api.md#websocket)): "Waiting to download" (queued, with its place in the ingest queue: "Waiting to download (3rd in line)"; uploads say "Waiting to encode"), "Downloading 45 %", "Encoding 30 %" (probe, loudness measurement and encode), "Finishing…" (audio extraction, card, index, verify, publish), or "Failed: <short reason>". The job's step and the step's own progress are live from the ingest queue ([library-and-ingest.md](library-and-ingest.md#ingest-job-states)). A room looks it up at most once a second, only while it has such an entry, and sends it only when it changed. The Library page, the item page and the jobs page use the same words.

**Restart:** the timeline is persisted. On startup, a room that was playing resumes from its computed position, or from the start of the entry if the server was down for longer than the entry's remaining time.

## Control modes

| `controlMode` | Who adds to the queue | Order | Who can skip |
|---|---|---|---|
| `owner` | Owner and moderators. Others **suggest**, and the owner accepts or rejects | Manual | Owner and moderators |
| `vote` | Members with request rights | By votes (descending), ties broken by time added. Owner and moderators can pin an entry to the top | Owner and moderators, or the vote-skip threshold |
| `open` | Members with request rights | Manual; anyone with request rights can reorder or remove | Anyone with request rights |

Details (as implemented):

- "Who can skip" also covers `play`, `pause`, `seek` and `previous`.
- Votes and vote-skip exist only in `vote` mode. Pinned entries play first, in the order they were pinned; owner and moderators can unpin.
- Vote-skip needs `ceil(voteSkipThreshold × present identified members)` skip votes (at least 1). Each voter counts once per entry; skip votes reset when the entry changes, and a drop in presence can reach the threshold.
- Changing the control mode keeps the current play order: queue positions are rewritten to it and pins are cleared. Suggestions left from `owner` mode stay, and the owner and moderators can still accept or reject them in any mode. An accepted suggestion joins the end of the queue.

## Room settings (`settings_json`)

| Setting | Default | Notes |
|---|---|---|
| `libraryRequests` | true | Off: only the owner and moderators can search libraries and request items by id; YouTube links still work; uploads need it, so `uploadRequests` turns off too (see [libraries.md](libraries.md#visibility)) |
| `uploadRequests` | true | Off: only the owner and moderators can request by upload. Only on while `libraryRequests` is ([below](#uploads-need-library-requests)) |
| `requestsPerActor` | 3 | Max queued entries per user or guest at a time; owner and moderators exempt |
| `allowDownvotes` | false | Vote mode only |
| `voteSkipThreshold` | 0.5 | Fraction of present identified members (listen + remote), or `off` |
| `noRepeatWindow` | 10 | Refuse an item that was played in the last N entries |
| `transitionCard`, `gapMs` | off, 0 | |
| `guests.*` | see below | |
| `outputs.rtmp`, `outputs.ts`, `outputs.hls` | off | Each one available only if the server enables it |
| `overlay.*` | off | See [overlay.md](overlay.md) |
| `estimatedDelayMs` | per output | Used by remote mode; the owner can override it |

As implemented (`RoomSettings`): on the wire and in storage, dotted names are nested objects (`{"guests": {"canVote": true}}`). The room object (`GET /api/rooms…`) carries every setting with its value; `settings_json` stores only the values that differ from the defaults, so a changed default reaches rooms that never set it. Stored JSON is read leniently (unknown or invalid values fall back to the default). Only the owner changes settings, with `PATCH /api/rooms/{id} {"settings": {…}}`, a merge patch: nested objects change only what they name, `null` resets a setting or a whole group; strict (`400 unknown_setting` / `invalid_setting` / `invalid_settings`), all or nothing; the audit entry names the changed settings, not their values. The complete list:

| Setting | Default | Values |
|---|---|---|
| `libraryRequests` | true | |
| `uploadRequests` | true | only on while `libraryRequests` is |
| `requestsPerActor` | 3 | 1–1000 |
| `allowDownvotes` | false | |
| `voteSkipThreshold` | 0.5 | (0, 1], or `"off"` |
| `noRepeatWindow` | 10 | 0 (off)–1000 |
| `transitionCard` | false | |
| `gapMs` | 0 | 0–10000 |
| `guests.canVote`, `guests.canRequestLibrary` | true | |
| `guests.canUpload`, `guests.canYoutube` | false | |
| `overlay.enabled` | false | |
| `overlay.corner` | `br` | `br` \| `bl` \| `tr` \| `tl` |
| `overlay.heightPct` | 22 | 5–50 (% of the frame height) |
| `overlay.margin` | 3 | 0–20 (% of the frame height) |
| `outputs.rtmp.playbackUrl` | `""` (none) | http(s), rtmp(s) or rtsp(t) URL with a host, at most 2048 characters |
| `outputs.rtmp.idleStopMinutes` | 10 | 1..1440 (whole minutes); the RTMP push is turned off after the room was idle (nothing playing, nothing queued) this long, and stays off until the owner turns it on ([program-output.md](program-output.md#idle-stop)). The server's `outputs.rtmp.maxIdleStopMinutes` caps it: a higher value is saved but acts as the maximum (clamped) |
| `outputs.ts.join` | `next` | `next` \| `latest` |
| `estimatedDelayMs.rtmp` / `.ts` / `.hls` | 5000 / 2000 / 8000 | 0–120000 |

Whether an output is on is **not** in `settings_json`: it is the `stream_outputs` row and its `enabled` flag (`/api/rooms/{id}/outputs`), so there is one source of truth. Server switches (`guests.*`, `overlay.enabled`, `outputs.*.enabled`) are not checked when a room setting is saved; the effective value is server AND room, computed where it is used.

Roles in a room: `owner` (exactly one; transferable), `moderator`, `member`, `banned`. Users with `rooms.moderateAny` act as moderators everywhere (even in a room that banned them). Visibility: `public` (listed), `unlisted` (anyone with the link), `private` (invited users only; **no guests**). New rooms default to `unlisted` and `vote`.

Who does what with the room itself (see [api.md](api.md#rooms) for the details): only the **owner** changes its name, slug, visibility and control mode, and hands it over (`PUT …/members/{userId}` with role `owner`; the old owner becomes a moderator). The owner or `rooms.moderateAny` deletes it. Owner and moderators see the member list; the owner gives any role, moderators only make users members or ban them (never touching the owner or other moderators). Nobody changes their own role, but anyone except the owner and banned users can leave. `rooms.owner_id` is the owner; `room_members` mirrors it with an `owner` row.

## Guests in rooms

Guests can join `public` and `unlisted` rooms. Just listening needs no identity. Each guest capability is effective only when the **server** setting and the **room** setting are both on:

| Room setting | Server setting it depends on |
|---|---|
| `guests.canVote` (default on) | `guests.canVote` |
| `guests.canRequestLibrary` (default on) | `guests.canRequestLibrary` |
| `guests.canUpload` (default off) | `guests.canUpload` |
| `guests.canYoutube` (default off) | `guests.canYoutube` |

In the room settings UI, options the server has disabled are shown greyed out, with "disabled by the server admin".

As implemented (web, `web/src/roommanage.ts` + `roomsettings.ts`): the **management view** is its own page, `/rooms/{idOrSlug}/manage`, linked as "Manage room" from the room page for the owner and moderators. Tabs: **Settings** (name, link name, visibility, control mode and every room setting, grouped; the owner saves only what changed, as one `PATCH`; moderators see them read-only; a setting whose server switch is off (`GET /api/rooms/{id}/server-switches`) is greyed out and disabled with "… disabled by the server admin" and is left out of the patch: the guest capabilities by `guests.enabled` / `guests.*`, every `overlay.*` setting by `overlay.enabled`, `outputs.<kind>.*` and `estimatedDelayMs.<kind>` by `outputs.<kind>.enabled`; vote-skip is a percentage, empty = off; "Delete room" for the owner or `rooms.moderateAny`), **Members** (role select as the viewer may give roles, "Make owner" for the owner, Remove / Lift ban, Leave for oneself, add by username with `POST /api/rooms/{id}/members`), and **Guest bans** (list with the IP-mode note, and the address for `ip.view` only; Unban). Guests are banned from the room page's [history](#history) ("Ban guest", after `GET …/guest-bans/{guestId}` and a confirmation that repeats the IP-mode warning), where moderators see who added what. (The **Request history** tab was removed on 2026-11-10: one history per room.)

- The first action that needs an identity creates the guest (see [auth.md](auth.md#guests)). With custom names allowed, the UI asks for a name first, pre-filled with a generated one.
- Owners and moderators can **ban a guest** from the room (`room_guest_bans`). In IP mode this bans everyone sharing that IP or /64. The UI warns about this, but doesn't show the IP unless the viewer has `ip.view`.
- Guests count towards vote-skip thresholds and presence once they have an identity. Anonymous listeners are counted separately ("+4 anonymous").

As implemented (`RoomGuests`, `RoomPresence`, `PresenceOps`):

- With `guests.enabled` off, or for a guest banned server-wide, no capability is effective. `guests.canYoutube` also needs `ytdlp.enabled`. An anonymous visitor is shown what it would get as a guest.
- A request needs the capability of its path: by item id `canRequestLibrary`, by upload `canUpload`, by link `canYoutube` (`403 guest_library_requests_disabled` / `guest_uploads_disabled` / `guest_youtube_disabled`). "Request rights" (remove own entries, `open`-mode moves and playback) means any of the three. Users get upload/link rights from `library.upload` / `library.youtube`.
- A guest banned from a room is treated like a banned user: `403 banned_from_room`, the room isn't listed for it, and it can't upload or add links for it. Banning removes its connections from presence (the WebSocket layer closes them). Its queue entries and votes stay, as for a banned user; moderators can remove entries.
- Presence counts each identified actor once, however many sockets it has: `listening` if any of them listens, else `remote`. `anonymous` counts sockets. A change of the counts is broadcast with a new `version`; a drop in identified members re-checks the vote-skip threshold. Presence is in memory only (kept when the executor reloads its state, empty after a restart).

## Requests

A **request** is any attempt to add an item to a room's queue (or to suggest one) by someone other than the owner or a moderator acting in owner mode. Requests come from four sources:

- **Library:** search the room owner's library and playlists and the requester's own library, or with "Search the whole server" every item on the server ([libraries.md](libraries.md#visibility)); any item on the server may be requested by id. Room setting **`libraryRequests`** (default on; Siggi, 2026-11-10: "We should allow disabling library requests, in which case you cannot add anything from any library, unless it's a YouTube item you already know the link for which you paste in the YouTube link section.") off: for everyone but the owner and moderators the room page hides the search and the history's Add to Queue, and the server refuses the search, `queue.add` by item id and by history entry (`403 library_requests_off`). Pasting a YouTube link still works even when the video is already on the server. Uploads are **not** unaffected any more (2026-11-10): they need library requests ([below](#uploads-need-library-requests)). `room.you.canRequestLibrary` tells the client (false while off, except for the owner and moderators).

### Uploads need library requests

(Siggi, 2026-11-10: "prevent a state where upload is on but library searching is off. If the user attempts to turn upload on while library searching is off, turn library searching on as well and inform the user. Same with turning library searching off while upload is on, turn upload off as well and inform the user.") The [upload pre-check](library-and-ingest.md#pre-check) gives anyone who knows a file's content ID the existing item without uploading it, so an upload could stand in for a library request. Hence:

- Room setting **`uploadRequests`** (default on): whether people other than the owner and moderators may request by upload in this room (users still need `library.upload`, guests the effective `guests.canUpload`). Off → `403 upload_requests_off` on `queue.add {uploadId}` and, for guests, already on `POST /api/uploads {roomId}`; `you.canUpload` is false then (owner and moderators excepted).
- **Invariant:** `uploadRequests` on ⇒ `libraryRequests` on, and `guests.canUpload` on ⇒ `guests.canRequestLibrary` on (the same holds for the server's guest settings, [auth.md](auth.md#guest-policy-server-settings)).
- **Manage room:** turning an upload switch on while its library switch is off turns that on too; turning a library switch off while its upload switch is on turns that off too; either way a notice says what else changed ("Uploads need library requests: turned "Allow library requests" on too.").
- **Server:** `PATCH /api/rooms/{id} {settings}` applies the same companion change and lists it in the response (`"adjusted": ["libraryRequests"]`); a patch turning an upload switch on and its library switch off at once is `400 invalid_setting`. Stored settings that break the rule (older rows) read as upload off.
- **At request time** the effective checks enforce it too: guest upload capability = server AND room, each already coupled, so no combination of server and room switches lets someone upload where library requests are off.
- **Upload:** the file is ingested into a new item (or deduplicated). The queue entry is "processing" until the item is ready.
- **YouTube:** the link is normalised to a video ID and served from the cache when possible ([library-and-ingest.md](library-and-ingest.md#youtube-caching)). See [Adding a YouTube link](#adding-a-youtube-link) for the feedback while a new one is looked up.
- **History:** "Add to Queue" on a row of the room's [history](#history).

The owner and moderators get `request.new` for each request. Items that nobody adds to a library are deleted by retention after the configured period.

## History

Each room has **one history**: every entry that started playing there and has ended (played or skipped), newest first, kept for `rooms.requestHistoryDays` (default 90, 0 = forever; the hourly retention run deletes older entries, never a room's current one). It replaced the owner's request history (Siggi, 2026-11-10). Everyone who may open the room sees it (the room page, below the queue), so it shows nothing the queue doesn't: per row the item (title and artist; a purged item keeps its row, marked expired), when it played, who added it (the display name they had then; guests marked as guests; never an IP address), how (from a library, by upload, by YouTube link, from a playlist, from the history) and whether it was skipped.

- **Add to Library** on every row whose item isn't in the **viewer's** library (signed-in users; guests have no library), through `PUT /api/library/{itemId}`, so retention protection and the quality re-encode follow. Rows already in their library say "In your library". A purged YouTube item is fetched again; a purged or failed upload can't be restored and has no button.
- **Add to Queue:** the owner and moderators on every row, exempt from the request rules. Everyone else only where they may request from the library (a guest needs `guests.canRequestLibrary`), with the usual rules: `requestsPerActor`, `owner` mode turns it into a suggestion, and **`noRepeatWindow`**: for an item that started playing within the last N entries (the current one included) the button is disabled with "Played within the last N songs", and the server refuses it (`409 recently_played`). Like any request by item id it has no scope check; with `libraryRequests` off it is hidden (and refused) for everyone but the owner and moderators.
- Moderators also get **Ban guest** on a guest's row (after a confirmation with the IP-mode warning); this used to live in the request history.

As implemented (`rooms/RoomHistory`, `web/src/history.ts` + `room.ts`): the history is `queue_entries` with `played_at` set and state `played`/`skipped`; entries record `via` and `added_by_name` (migration `0011`; older entries show the current name and no `via`). `GET /api/rooms/{id}/history` serves it in pages of 50 with per-viewer `inYourLibrary` and `recentlyPlayed` ([api.md](api.md#rooms)); the room state still carries the last 20 rows, and the page loads the first page again whenever that changes ("Show more" for older ones). `queue.add {historyEntryId}` adds an entry's item again (an entry of this room that has started playing; `404 history_entry_not_found` otherwise). An entry that `previous` plays again moves to the top when it ends again.

## Outputs

The room owner turns each of the three outputs on or off independently: RTMP push, direct `.ts` and HLS. An output can only be turned on if the server enables it. They are equal features, built from the same packager stream ([program-output.md](program-output.md)).

- **RTMP:** the owner enters the ingest URL and stream key (e.g. from VRCDN). The room page shows push status. The URL that viewers paste into VRChat comes from the RTMP service, so the owner can save it in the room settings (`outputs.rtmp.playbackUrl`), and the room page then shows it.
- **RTMP idle stop:** once the room has been idle (nothing playing, nothing queued) for `outputs.rtmp.idleStopMinutes` (at most the server's `outputs.rtmp.maxIdleStopMinutes`), the server turns the RTMP output off, as if the owner had pressed Turn off, and it stays off until the owner turns it on ([program-output.md](program-output.md#idle-stop)). The owner's outputs panel then says "Stopped after N minutes with an empty queue. Turn on to start again." (from `stopped` in `GET …/outputs`); moderators see it as off, like any off output.
- **`.ts` and HLS:** the room page shows the tokenised URLs, with copy buttons, and buttons to rotate the token or disable the output.
- As built (API, `output/StreamOutputsApi` + `StreamOutputControl`): at most one output of each kind per room (unique index `stream_outputs (room_id, kind)`). The owner creates, changes, rotates and deletes them; moderators can see them (URLs included). An output is active when its row is enabled AND the server switch is on; the switches are checked when an output is created or turned on, and enforced continuously: a reconcile runs after every API change, after the admin saves settings, at startup and every 10 s. It starts one RTMP push per room with an active RTMP output (restarted when the URL or key changes, stopped when it goes off, its room is deleted or `outputs.rtmp.enabled` goes off) and ends `.ts` connections whose token no longer opens their room (rotated, disabled, deleted, server switch off); HLS checks its token on every request. Turning an output on starts the room's program output at once; turning off the last active one stops it at once.
- The page also notes the VRChat allowlist requirement for self-hosted URLs.
- As built (web, `web/src/outputspanel.ts` + `outputs.ts`): a **Stream outputs** panel on the room page. Everyone sees "Watch on the stream" with the RTMP viewer URL (`outputs.rtmp.playbackUrl`) and a copy button while the RTMP output is active. The owner and moderators also see each output from `GET …/outputs` (reloaded when the set of active outputs in the room state changes): `.ts` and HLS with their URL in a read-only field and a **Copy** button (clipboard API, else select + `execCommand("copy")`, else "copy it yourself"); RTMP with live status from the room state (`output.status`), failures in a row and the next retry, the last ffmpeg line, for the owner only the target URL and the key (in a read-only password field with **Reveal**, which toggles to text and becomes "Hide", and **Copy**, with the same fallback chain; moderators see neither, and their status detail has no URLs or key hint), and the viewer URL (or a pointer to Manage room → Settings → Outputs to save one). The owner gets **Turn on** (create, or `PATCH enabled`), **Turn off**, **Rotate URL** (`.ts`/HLS, with a confirmation that the old URL stops at once), **Remove**, and an RTMP form (ingest URL, stream key as a password field with the placeholder "Stream key (if needed)"; when editing, nothing is prefilled, empty keeps the key and a checkbox removes it). Moderators see everything read-only. Outputs whose server switch is off show "disabled by the server admin". Notes under the outputs: the generic **player allowlist** note (`ALLOWLIST_NOTE`; the VRChat specifics are in [user-guide.md](user-guide.md)) whenever the room has a `.ts` or HLS output, and the **HLS desync** note while HLS is on (see [program-output.md](program-output.md#hls)). Remote mode's delay follows the active output (RTMP before `.ts` before HLS, skipping an RTMP push in `error`) and its `estimatedDelayMs`.

## Clock sync (web and app)

The client estimates `offset = serverClock − clientClock` over the WebSocket:

1. The client sends `{type:"time", t0}`. Use a monotonic clock: `performance.timeOrigin + performance.now()`.
2. The server replies with `{type:"time", t0, ts: serverNowMs}`.
3. When the reply arrives at `t1`: `rtt = t1 − t0`, `offset = ts − (t0 + t1)/2`.
4. Do 8 exchanges on connect, keep the sample with the lowest RTT, and repeat every 30 s. Smooth with an EMA, ignoring samples whose RTT is above 2× the minimum.

As implemented (web, `web/src/clocksync.ts`): the 8 exchanges of a round go one after the other (2 s reply timeout); a round runs on every (re)connect, every 30 s, and when the page becomes visible again. EMA weight 0.3 over the rounds' best samples. The minimum RTT is taken over the last 10 rounds, and a round passes when its best RTT is at most `max(2 × min, min + 5 ms)`. A sample more than 1 s away from the smoothed offset replaces it (the client's clock stepped, e.g. after sleep). Until the first round the client uses its own wall clock.

## Web and app playback

Each connection is in one of two modes, chosen with a toggle and remembered per room in localStorage. Visiting through the QR link (`/r/{slug}`) defaults to remote mode.

### Listen mode

The client plays the media itself, in sync with the timeline. There is a **video / audio-only** toggle:

- Audio items: the **original** file (`originalUrl`) in an `<audio>` element, with `originalGainDb` applied (as the element's `volume` on the direct path, through its `GainNode` with Web Audio; see "Direct path or Web Audio" below), when the item has a web-playable original and `canPlayType` agrees, and, on the direct path, the gain isn't positive. Otherwise `audio.m4a`. The cover art is shown.
- Video items with `presentAsAudio`: `audio.m4a` in an `<audio>` element, with the cover art shown.
- Video items in video mode: `mezz.mp4` in a `<video>` element.
- Video items in audio-only mode: `audio.m4a` only. The video is never requested, so it uses no bandwidth.

Sync loop (every 250 ms, on every state message, and on the elements' `playing` / `seeked` events):

```
target = position(timeline, serverNow = clientNow + offset)
drift  = el.currentTime*1000 − target
|drift| > 750 ms → el.currentTime = (target + lead)/1000          (hard seek, both modes)
rate mode (Chromium, Firefox):
  rate 1 and |drift| > 40 ms → rate 1 ∓ 3 % (∓ 5 % if |drift| > 300 ms), sign against the drift
  nudging → keep that rate until |drift| ≤ 10 ms or the drift changed sign, then rate 1
seek mode (WebKit: Safari and every iOS/iPadOS browser):
  rate always 1; |drift| > 60 ms (80 ms after a seek settled within ±60 ms)
    → seek to target + lead, at most every 2.5 s
  (doubling up to 8 s only while seeks scatter; a seek that misses by the same amount
   every time is a wrong lead, which the lead learns, so it keeps the 2.5 s)
no correction while a seek's landing settles (~1.4 s after `seeked`), except a hard seek
```

Why two modes (found in the first manual test, 2026-11-10): in Safari the pitch rises with `playbackRate` although `preservesPitch`/`webkitPreservesPitch` is set (the elements go through `createMediaElementSource`), and every `playbackRate` change briefly stalls the video. So WebKit never nudges the rate, and the rate mode changes it only twice per correction instead of every tick. The room page's `?sync=rate|seek` overrides the choice for testing.

**Seek lead:** a seek takes time, so a playing element lands late. In WebKit the seek itself is quick (`seeked` after 60–100 ms for buffered data, reporting the new position) but the media clock then **stands still for another ~300 ms** (pipeline refill) before playing on at normal speed; found in the diagnostics of the second manual test (2026-11-10: iPad Pro iPadOS 26.1 and iPhone X iOS 15.1 both read ~0 ms drift at `seeked` and settled 280–340 ms behind). So a landing is judged on the **settled** drift (`SeekLanding`): drift samples from 0.8 s after `seeked` on, at least 200 ms apart; when the last three lie within 20 ms, their median (stable); if they haven't come to rest 3 s after `seeked`, the median of the last three (not stable, counts as scatter). Each element learns how far ahead to aim from these (first sample as is, then halfway towards each new one, limited to −200…2000 ms), but only from seeks into **buffered** data (`el.buffered`): a seek to unbuffered data (start, an owner seek far away) also waits for the download (1.4 s seen on the iPhone) and would spoil the lead; before the first real sample its settled drift is only a first guess, which the first real sample replaces. A drift above 750 ms at or after `seeked` is a lost seek (iOS dropped it), not a latency. A landing that settles more than 150 ms off is followed at once by another seek with the corrected lead (at most two in a row), so a fresh element converges in two seeks. Scatter: the delay of a seek is lead used − settled drift; when the last three delays spread more than 100 ms (or the drift didn't come to rest), the seek-mode cooldown doubles, up to 8 s. The 80 ms threshold once converged: seven identical correction seeks settled within 33 ms (iPad) / 39 ms (iPhone) of each other, so a seek is good to about ±20 ms and can't reliably improve a drift below about twice the spread; rate-1 playback then held its offset to ±1 ms for 30 s, so a converged element seeks only every few minutes at most (each WebKit seek is a ~300 ms audible stall).

**Settled:** drift is judged only while the element plays, isn't seeking and has `readyState ≥ HAVE_FUTURE_DATA` *or advances* (iOS may report less while playing; before this, such an element was never corrected). A seek the browser dropped (iOS resets a seek made before playback starts) simply shows as drift and is done again; a seek still pending after 5 s reloads the element (a fresh media pipeline, then the usual start seek and `play()`; re-seeking didn't help on the iPod touch 7, report `f552z8`), and the "Click to listen" gesture doesn't seek an element whose start seek is still pending. **End of an item:** no seek (correction, hard, resume) and no start once the seek's aim (target + lead) is within the last 1 s of the file; the element plays out and the next entry starts on time (a seek aimed past the end used to be clamped to the last 50 ms, end the element at once, and be redone every tick). The loop catches its own errors (recorded for diagnostics) and keeps ticking.

If an original keeps needing hard seeks (drift still above 750 ms after two hard seeks within 10 s; typical for VBR MP3s without a seek table), the client switches that item to `audio.m4a` for the rest of the session.

Set `preservesPitch = true`. Preload the next entry about 10 s before the current one ends (in the mode the client will use for it), and show a "Click to listen" overlay until the browser allows autoplay.

As implemented (web, `web/src/listen.ts` + `web/src/listensync.ts`): a fixed pool of two `<audio>` and two `<video playsinline>` elements is reused for every entry (one plays, one holds the preloaded next entry), so the "Click to listen" click can touch every element once (iOS asks per element). Where the page can set `volume` (desktop browsers, Android), Auto plays straight from the elements and the gain is the element's `volume`; elsewhere (iOS) each element is routed through its own `GainNode` (`createMediaElementSource`, once per element). Either way the playing one gets `10^(originalGainDb/20)` for an original and 1 otherwise, the preloaded one 0. The overlay shows while the timeline is playing and the `AudioContext` isn't `running` or `play()` was refused (`NotAllowedError`); clicking resumes the context and plays. The original is used only if the item view carries `originalUrl` and `canPlayType(originalType)` isn't `""`; an original whose element fires `error`, or that needs a third hard seek within 10 s of two others (counted only while the element is playing and has data, reset on every re-anchor and reload), is played from `audio.m4a` for the rest of the page's life. Drift is judged only while the element plays with `readyState ≥ HAVE_FUTURE_DATA` and isn't seeking; a paused or not-yet-started element is first moved to the target without counting as a hard seek; when the timeline is paused the element is paused and kept within 100 ms of the position. The next entry (first `ready` queue entry in play order) is preloaded once ≤ 10 s remain and kept while it stays next. Video items without `presentAsAudio` play `mezz.mp4` in the visible `<video>`, or `audio.m4a` in audio-only mode (below). `window.togetherTunesPlayer.debug()` shows source, gain, rate, drift, sync mode, the element's state, seek lead and seek counts, and preloads for manual checks. **Diagnostics without a console:** the page keeps rings in memory from load on (`web/src/diagnostics.ts`: the last 200 sync-loop decisions with drift, action, rate, `readyState`, `seeking`, `paused`; the last 150 player events such as seeks and their landings, rate changes, `play()` failures and media events; the last 50 page errors, `error` and `unhandledrejection`, and errors the loop caught); "Send diagnostics" in listen mode posts them with the user agent, `debug()` and the clock state to `POST /api/diagnostics`, which logs one line `Client diagnostics from <user|guest id|anonymous>: {json}` (256 KiB limit, rate-limited per IP, no IPs; URL parameters like `token=` are masked). The page shows a short `ref` to find the line.

**Audio health and the Web Audio fallback** (`web/src/audiohealth.ts`; from the iPod touch 7 report `dfh3l7`, where the sound stuttered and then went mute while sync stayed perfect; this matches WebKit bug 221334, "Audio passed through WebAudio is delayed and glitchy on Safari", unfixed, on A10/A11-class iPhones): each element's `GainNode` feeds an `AnalyserNode` (in the chain to the destination), read every 40 ms while the element plays (2048-sample windows, so they overlap). Once a second a health sample goes into a fourth ring (300 samples = 5 minutes, compact keys): per element `out: [peak dB, RMS dB, windows, gaps, silent windows]` (null dB = exact silence; a gap is a run of ≥ 128 exact zeros inside a window that has signal), the context state and `ck` (audio-clock ms per 1000 ms of wall clock), the active element's position, drift, `readyState`, gain, `wk` (per-second deltas of `webkitAudioDecodedByteCount`, `webkitVideoDecodedByteCount`, `webkitDecodedFrameCount`, `webkitDroppedFrameCount`) and `vq` (`getVideoPlaybackQuality` totals). Events also record `visibilitychange`/`pagehide`/`pageshow`, every `load`/`clear`/`reload`/`handoff` per element, and context resumes; `debug()` and the `player` event show `sampleRate`, `baseLatency` and `outputLatency`. **Mute detection:** an element that should be heard (playing and advancing, not seeking or muted, gain > 0, context running) whose meter saw only exact zeros for 10 s in a row (seconds with fewer than 15 windows don't count, nor do seconds where the element decoded no audio, where the engine counts that), after it had been heard once, trips it: the overlay shows **"Tap to restore sound"**, and that tap empties and drops the four elements, closes the context and builds a fresh pool without Web Audio inside the gesture (the current entry restarts with the usual start seek), for the rest of the page's life (`debug().webAudioOff: "mute"`). **`?webaudio=0`** on the room URL starts that way (A/B test; `webAudioOff: "url"`). Without Web Audio the gain is the element's `volume` (at most 1, so on the direct path an original that needs a positive gain plays `audio.m4a`); where `volume` is read-only (iOS) originals play without `originalGainDb` and the audio-only switch can't crossfade. **Stuck seek on a routed element:** the entry moves to the other element of its kind (a preload there is dropped and redone later) instead of `load()` on the stuck one; without Web Audio, or during a switch, it reloads as before. **Interruptions:** a context that had been running and reports `suspended`/`interrupted` is asked to resume (at most every 5 s; also when the page becomes visible again).

**Sample-rate mismatch and the "Web Audio" setting** (`WebAudioCheck` and `webAudioPlan` in `audiohealth.ts`; from the iPod touch 7 report `uhmxlu`, where the context reported 44100 Hz but its clock ran 1086–1091 ms per wall second, i.e. the hardware pulled 48 kHz, with 5–11 dropouts a second while sync was perfect): once a second the check looks at `ck` and the playing element's gaps. **Clock:** off by more than 20 ms per 1000 (2 %) in 3 samples in a row while the context runs (samples outside 500–2000, stalls and catch-ups, are ignored) → verdict `clock` with the implied rate (`sampleRate × median ratio`, snapped to a standard rate within 1.5 %). **Gaps:** ≥ 3 gaps a second for 3 s in a row while the element should sound, in seconds where at most 20 % of the windows were exact silence (a click track's sparse clicks aren't dropouts), and only with the clock measured fine → verdict `gaps`. **ok** after 5 clean clock samples. **What happens:** on `clock` (once per page) the player (a) closes the context, makes `new AudioContext({ sampleRate: implied })` (Safari 14.1+; if the option throws, a plain context) and moves playback onto a fresh pool routed through it (an element can only have one `MediaElementAudioSourceNode`); the current entry starts on the new element at the timeline's position, as after a stuck-seek handoff; then it checks again. If that fails (`clock` again, or the new context isn't running 4 s later, since WebKit may want a gesture for it), or on `gaps`, **Auto** (b) rebuilds the pool without Web Audio and remembers it; **On** keeps Web Audio (event `webaudio-kept`). Spare elements (4 per kind) are touched in every "Click to listen" gesture, so iOS lets a pool rebuilt without a gesture play; if it still refuses, the overlay asks for a tap. **Setting:** "Web Audio" next to "Audio only" in listen mode, per browser (`localStorage` `togethertunes.webAudio`: absent = Auto, `on`, `off`); Off shows what it costs (no crossfade on the audio-only switch, no loudness adjustment for originals where the browser can't set the volume). Precedence: `?webaudio=0/1` (testing) > setting > Auto (direct where the volume can be set, see below) > Auto's memory (`togethertunes.webAudioAuto`: `{rate}` after a confirmed rate fix, so the next visit creates the context at that rate at once; `{direct: true}` after (b) or a "Tap to restore sound" under Auto, so the next visit starts direct; forgotten after 30 days or when the setting is changed). The note next to the setting shows "Sound: direct (no volume control)" without Web Audio where the volume can't be set, "Sound: direct" when the direct path is forced (Off, `?webaudio=0`) where it can, nothing for Auto's usual path, and "Sound: Web Audio at 48 kHz" after a rate fix. Events: `webaudio-check` (verdict), `rebuild`/`rebuilt`, `rate-fix-start`, `webaudio-kept`, `webaudio-plan`; health samples carry `wa` (the verdict); `debug()` shows `webAudioPlan`, `webAudioCheck`, `rateFix`, `reserve`; the report adds `webAudioSetting` and `webAudioMemory`.

**Direct path or Web Audio (Auto)** (`volumeSettable` and `webAudioPlan` in `audiohealth.ts`, `DIRECT_MAX_GAIN_DB` in `listensync.ts`; from Siggi's Mac Safari 26.1 test, 2026-11-10): with Web Audio, **Mac Safari played the sound 0.5–1 s behind the picture** while the elements were in sync (report `arqrbq`: drift +36 ms, context running at 48 kHz, clock ratio 1.000, no gaps, `outputLatency` reported as **0**); with the setting Off the sound was on time (`7oik3f`). WebKit's `createMediaElementSource` path adds a large output delay the page can't see, so it can't be measured or compensated. Web Audio is only needed for what `volume` can't do, so **Auto plays straight from the elements wherever the page can set `HTMLMediaElement.volume`** (desktop Safari, Chrome, Firefox, Android): the gain and the 150 ms crossfade are the element's `volume` (stepped every 15 ms for a ramp). This is detected at runtime, not from the user agent: the room page sets `volume = 0.5` on a spare `<audio>` and reads it back (iOS/iPadOS ignore the write and always read 1; a throwing setter counts as not settable). Only where the volume can't be set (iOS/iPadOS) does Auto use Web Audio, with the sample-rate check and fallback above. On and `?webaudio=1` still force Web Audio, Off and `?webaudio=0` the direct path. **Positive gains on the direct path:** `volume` can't exceed 1, so an original whose `originalGainDb` is above 0 dB couldn't be brought up to `audio.m4a`'s loudness; it plays `audio.m4a` (loudness-normalised) instead, and a negative gain is applied as `volume`. The choice shows as `originalSkippedGainDb` in `debug()` and in the `load` event (with `gainDb` and `original`); `debug().webAudioPlan` carries `why: "volume"` and `volumeSettable`, `webAudioOff` is `"volume"`, and the diagnostics report has `volumeSettable`. Whether iOS's Web Audio path has the same hidden delay is still to be checked on the devices; if it does, the choice there (no volume control on iOS, or an estimated offset) is open.

### Audio-only switching

Switching between video and audio-only mode must not interrupt the audio.

**Why not HLS:** in principle HLS supports this, using a demuxed audio rendition plus an audio-only variant, and native Safari can switch between them. But browsers other than Safari play HLS through Media Source Extensions, which can't easily drop the video SourceBuffer mid-playback. hls.js therefore **removes audio-only variants** from a playlist that also contains video variants. A custom MSE player could do it, but it adds a lot of complexity for one feature.

**What to do instead:** the web client already syncs plain files to the timeline, so it switches by crossfading between two synced elements:

1. **Video → audio-only:** create an `<audio>` element for `audio.m4a`, at volume 0, and let the sync loop bring it to the timeline position.
2. When its drift has stayed under 40 ms for 3 consecutive ticks (or after a 3 s timeout), ramp the audio element's volume from 0 to 1 and the video element's from 1 to 0 over 150 ms.
3. Pause the video element and clear its `src` to stop the download. Show the cover art in its place.
4. **Audio-only → video:** the same steps in reverse. The video element starts muted and hidden; when it is synced, show it and crossfade.

Both elements play the same samples from the same loudness-normalised encode, and are aligned to within about 40 ms, so a 150 ms crossfade is inaudible. On iOS, `volume` can't be set from script, so use a Web Audio `GainNode` per element (via `MediaElementAudioSourceNode`) for the ramps; where it can be set (desktop, Android), ramp `volume` and leave Web Audio out (it delays the sound in Mac Safari, see above).

As implemented (web, `ListenPlayer` + `SwitchGate` in `listensync.ts`): the toggle is an "Audio only" checkbox above the player in listen mode, remembered per browser (`localStorage` `togethertunes.audioOnly`, all rooms). The two elements come from the same pool of 2 `<audio>` + 2 `<video>`. The steady ticks are counted at most every 200 ms (the loop also ticks on bursts of state and load events), and the 3 s timeout crossfades only if the incoming element is actually playing, so a slow or failing load keeps the old element audible instead of leaving a gap. The ramps are stepped `volume` (every 15 ms) on the direct path, the usual one on desktop and Android, and `linearRampToValueAtTime` on each element's `GainNode` with Web Audio (iOS). Right after the ramp the outgoing element is emptied (`src` removed, `load()`), which stops a video download. When nothing can be heard (timeline paused, autoplay blocked or context suspended, outgoing element not playing) the switch happens at once. Toggling back mid-switch swaps the roles. A video stays in view until its fade-out starts and comes into view when its fade-in starts; in audio-only mode the cover art in the now-playing panel stands in for it.

### Remote mode

For users who hear the room in VRChat. The client:

- loads **no media** at all;
- shows now-playing info, the queue, votes and request controls;
- shows a progress bar delayed by `estimatedDelayMs` for the room's active output. Defaults: `.ts` 2 s, HLS 8 s, RTMP 5 s; the owner can override them. Each user can nudge the delay by ±0.5 s, stored locally.

Remote members count as present for vote-skip and presence. The UI shows them separately ("3 listening, 5 in VRChat").

As implemented (web, `web/src/remote.ts` + `room.ts`): the mode is a link-style toggle in the room header ("I'm listening through the stream (remote mode)" / "Listen here instead"), sent to the server as `{type:"mode"}` and remembered per room (`localStorage` `togethertunes.mode.<roomId>`, explicit choices only). `?mode=` in the URL wins over the remembered mode; toggling removes it from the address (`history.replaceState`) so a reload keeps the choice. Remote mode creates no player at all (no `AudioContext`, no media elements); switching from listen to remote stops and empties the player's elements. Only cover art (`cover.jpg`) is loaded. **Active output:** the most preferred of RTMP, `.ts`, HLS among `state.outputs` that isn't `error`/`disabled`/`stopped`/`off` (all failing: still the most preferred); its delay is the output's own `estimatedDelayMs`, else the room setting `estimatedDelayMs.<kind>`, else the default. With no outputs listed (M3) RTMP is assumed. **Nudge:** −0.5 s / +0.5 s buttons and Reset, stored per room (`togethertunes.remoteNudge.<roomId>`, whole 0.5 s steps); output delay + nudge stays within 0–120 s. **Delayed view:** every timeline change (another entry, anchor or state) is recorded with the server time it happened (its `anchorServerMs` if that is at most 10 s before arrival, else the arrival time); the now-playing panel shows the entry and position that were in effect at `serverNow − delay`, so a skip appears there `delay` later. The state received on joining counts as always in effect. Controls (skip, pause, vote, seek) act on the live room at once.

### Playback across pages (web)

Navigating to another page of the site doesn't interrupt listen-mode playback, unless it goes to another room. As implemented (`web/src/shell.ts`, rules in `router.ts`, hooks in `nav.ts`): every page is still its own HTML document with one module, so deep links and reloads work as before. While the room page plays in listen mode, it handles same-site navigations itself: links (and pages' own `navigate()` / `reloadPage()` calls, and plain GET form submits) to the front page, `/rooms/`, `/library/`, `/playlists/`, `/account/`, `/admin/`, `/items/{id}` and `/rooms/{ref}/manage` fetch the page's HTML, put its body (minus the nav and scripts) into `#shell-page`, hide the room view (`#room-body`; media elements, WebSocket and clock sync keep running), take over the title, `history.pushState` the URL and import the page's module again under a fresh `?mount=N` (a new module instance starts like on a page load; the previous page's `pageSignal()` is aborted, which ends its `window` listeners and silences its late failures). A link to the playing room shows its view again. Back/forward are handled for the entries the page pushed (scroll positions in `history.state`, focus to the new page's `h1`). A **mini player** at the bottom of the other pages shows the room (link back), the item, play state, "Audio only" and Leave (stops and loads the shown page normally). The browser handles: another room (leaves this one), login/register/password/setup, logout, the API and media, downloads, other sites, modified clicks and `target`s; in remote mode the shell stands aside. After a full load that stopped playback (a reload while another page was shown) the page says so in a bar with a link back to the room (`sessionStorage`, shown once). The room page closes its socket on `pagehide` and reloads if restored from the back/forward cache: Chrome otherwise kept the cached page's socket open, so the room still counted the user as listening after they went to another room.

## Program output and the timeline

The program output ([program-output.md](program-output.md)) **follows** the timeline and is allowed to lag it:

- It applies changes at the next GOP boundary, so there is up to 2 s of extra delay on skip, seek or pause.
- Seeks into a music video start at the keyframe at or before the target. Seeks into an audio item are exact to the AAC frame.
- The program output never feeds back into the timeline.

## Queue operations

These are performed by the executor; each one checks the actor's permissions and the mode's rules.

- `add(itemId | uploadId | youtubeUrl | historyEntryId)`: checks `requestsPerActor`, `noRepeatWindow`, the guest capabilities and, by item id or history entry, `libraryRequests` (no scope check: any item on the server). In `owner` mode, a request from anyone else becomes a suggestion.
- `addPlaylist(playlistId, shuffle)`: owner and moderators only.
- `remove(entryId)`: owner and moderators; the requester while the entry is still queued; or anyone with request rights in `open` mode.
- `retry(entryId)`: a failed YouTube entry (queued or suggested) is fetched again; owner and moderators, and the requester while they may add YouTube links here ([Retrying](#room-state)).
- `move(entryId, beforeEntryId)`: `owner` and `open` modes only. `pin(entryId)`: in `vote` mode.
- `vote(entryId, ±1 | 0)`, `voteSkip()`.
- `play()`, `pause()`, `seek(ms)`, `next()`, `previous()`.

Details (as implemented):

- `requestsPerActor` counts the actor's waiting entries (queued and suggested, not the one playing); `noRepeatWindow` refuses an item that started playing in the last N entries, the current one included. The owner and moderators are exempt from both.
- Uploads and YouTube links are turned into items before the entry is made, outside the room's executor; the cheap checks (identity, request rights, `requestsPerActor`) run first, so a refused request starts no import. A YouTube request doesn't add the item to the requester's library.
- `remove` works on queued entries and suggestions; the current entry is skipped with `next` instead.
- `previous`: more than 3 s into the current entry restarts it. Otherwise the last played (or skipped) entry plays again from the start, and the current entry goes back to the front of the queue (in `vote` mode as the top pin). A paused room stays paused; an idle room replays the last entry.

### Adding a YouTube link

Added 2026-11-10 (Siggi: "When I paste a YouTube link in, it says \"Looking up the video\" but does not seem to update its status until it's added to the queue."). A new video's `yt-dlp -J` lookup runs inside the request and can take 15 s or more, so the page shows what it is doing and how long it has been at it: "Looking up the video… 6 s" until the first stage, then the stage's text with the seconds so far ("Reading the video page… 9 s"), counted by the client every second so it visibly moves even when yt-dlp is quiet. The stages come from yt-dlp's own progress lines (`-J --verbose` prints them to stderr; `YoutubeLookupStage`):

| Stage | Text | yt-dlp line |
|---|---|---|
| `contacting` | Contacting YouTube… | `[youtube] Extracting URL: …` |
| `page` | Reading the video page… | `[youtube] <id>: Downloading webpage` |
| `details` | Getting the video details… | `[youtube] <id>: Downloading … player API JSON` (and player / client config downloads) |
| `formats` | Checking formats… | `[youtube] <id>: Downloading m3u8 information`, `[info] <id>: Downloading 1 format(s): …` |

Stages only move forward and each is reported once; any other line (`[debug]`, warnings, unknown wording) is ignored, so a wording change in yt-dlp means fewer stages shown, never a failed lookup. In a room they go to the requesting socket only (`request.progress`, tied to the request's `rid`); the Library page gets them by streaming `POST /api/imports/youtube` ([api.md](api.md#adding-media)). A video the server already knows answers at once, without stages. Once queued, the entry shows its ingest progress ([What a waiting entry shows](#room-state)).

## Broadcasting

On every change, broadcast a full `state` (under about 200 queue entries) or typed deltas, with `version`. A client that sees a gap in `version` sends `{type:"sync"}`.

Actors in messages are `{kind:"user"|"guest", id, name, isGuest}`. They **never** include IP addresses or anything derived from them.
