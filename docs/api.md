# API

JSON over HTTPS. A request is made by a user (`sid` cookie or `Authorization: Bearer`), a guest (`gid` cookie, or IP in IP mode), or anonymously; see [auth.md](auth.md).

- Errors: `{ "error": { "code": "snake_case", "message": "…" } }`, with the appropriate HTTP status.
- List endpoints use cursor pagination: `?cursor=…&limit=…` → `{ items, nextCursor }`.
- **No response contains an IP address**, except the endpoints marked *ip.view*.

Actors are serialised as `{ "kind": "user"|"guest", "id", "name", "isGuest" }` (`name`: a user's display name or a guest's name; `isGuest` drives the guest badge). Nothing else about an actor (no IP, no guest mode or ban flag) is in this form.

## Auth, account, guest identity

| Method | Path | Notes |
|---|---|---|
| GET / POST | `/api/setup` | First admin |
| POST | `/api/auth/login`, `/api/auth/logout`, `/api/auth/register` | |
| POST | `/api/auth/token` | Create a bearer token, from `{username, password}` or for the already logged-in user |
| GET | `/api/auth/providers` | |
| GET | `/auth/oidc/{slug}/start?intent=login\|link`, `/auth/oidc/{slug}/callback` | |
| GET / PATCH | `/api/me` | User profile, or the current guest's identity (`{kind:"guest", name, nameChosen}`), or `{kind:"anonymous"}`. For a user or guest it includes the actor fields (`kind, id, name, isGuest`) |
| POST | `/api/me/guest` | Create the guest identity now, with an optional `{name}` if custom names are allowed. Normally this happens implicitly on the first action that needs it. Returns the guest (`201` if created, `200` if it already existed) with a fresh `identityTicket` (see [WebSocket](#websocket)) |
| PATCH | `/api/me/guest` | Rename (custom names only) |
| POST | `/api/me/password` | `{currentPassword, newPassword}`: change your own password (replaces a temporary one; logs out your other sessions) |
| POST | `/api/me/merge-guest` | After login: merge the guest held in this browser's cookie into the account |
| GET / DELETE | `/api/me/sessions[/{id}]` | No IP field. `DELETE /api/me/sessions` revokes all sessions except the current one |
| GET / DELETE | `/api/me/identities[/{id}]` | |

## Items, libraries, playlists, sharing

| Method | Path | Notes |
|---|---|---|
| GET | `/api/library?q=&kind=&cursor=&limit=` | The caller's own library (users only; guests have none: `401`), most recently added first → `{items:[{item:{id, kind, title, artist?, album?, durationMs?, source, youtubeId?, status, presentAsAudio}, addedAt, via, sharedBy?: actor}], nextCursor?}`. `q`: every whitespace-separated word must appear (case-insensitive substring) in the title, artist or album (≤ 200 chars, first 10 words); `kind`: `audio`\|`video` (the stored kind); `limit` 1–200, default 50. `400 invalid_query` / `invalid_kind` / `invalid_cursor` / `invalid_limit` |
| GET | `/api/users?q=&cursor=&limit=` | `library.viewAll` (`401` guests/anonymous, `403 forbidden`): users to browse, by username (case-insensitive) → `{items:[{id, username, displayName, disabled, libraryCount, playlistCount}], nextCursor?}`. `q` matches part of the username or display name (≤ 100 chars); disabled accounts are listed (marked). `limit` 1–200, default 50. `400 invalid_query` / `invalid_cursor` / `invalid_limit` |
| GET | `/api/users/{id}/library?q=&kind=&cursor=&limit=`, `/api/users/{id}/playlists?cursor=&limit=`, `/api/users/{id}/playlists/{playlistId}` | `library.viewAll`, read-only: that user's library, playlists and one playlist with its entries, exactly as `GET /api/library`, `GET /api/playlists` and `GET /api/playlists/{id}` show them to the owner (same parameters and errors). `404 user_not_found` (also for a guest's id); a playlist of someone else is `404 playlist_not_found`. Viewing doesn't count as a reference for retention |
| PUT / DELETE | `/api/library/{itemId}` | Add an item to (`via:"manual"`; `201` entry, or `200` entry if already there, unchanged), or remove it from (`204`, `404 not_in_library`), the caller's library. `404 item_not_found`. A purged or failed upload can't be added (`409 media_expired` / `item_failed`); a purged or failed YouTube item is fetched again like an import of its link (needs `library.youtube`, so `403 feature_disabled` when `ytdlp.enabled` is off; `422 youtube_failed` within 10 min of a failure). Removing sets the item's `last_referenced_at` to now |
| GET | `/api/items/{id}` | Item metadata, by direct link (any actor) → `{id, kind, title, artist?, album?, durationMs?, source, youtubeId?, status, presentAsAudio, addedAt, error?, you:{inLibrary, canEdit, canPurge}}`. `error` only for a failed item; nothing about who added it or whose libraries it is in. `404 item_not_found` |
| PATCH | `/api/items/{id}` | `{title?, artist?, album?, presentAsAudio?}` → the item. The adder while it is in their library, or `library.editAny` (`401` guests/anonymous, `403 forbidden`). Cleaned like upload metadata; `artist`/`album` `null` or blank clears them. `400 invalid_title` (missing/blank/not a string) / `invalid_artist` / `invalid_album` / `invalid_present_as_audio` / `metadata_too_long` (> 300 characters) / `unknown_field` / `not_a_video` (`presentAsAudio: true` on an audio item). Audited (`item.updated`) |
| DELETE | `/api/items/{id}` | Purge immediately: `library.purge` → `204` (also for an already purged item). Queued ingest jobs are cancelled; `409 item_busy` while a job runs. Audited (`item.purged`) |
| GET | `/api/items/{id}/job` | Ingest status: the latest job `{id, kind, state, progress, attempts, createdAt, startedAt?, finishedAt?, log?}` (any actor; `log` only for those who may edit the item). `404 item_not_found` / `job_not_found` |
| GET | `/media/{id}/{audio.m4a\|mezz.mp4\|original\|cover.jpg}` | Range-capable (`ETag`, `304`), `Cache-Control: private, no-cache`. `original` exists only for audio uploads; its content type follows its extension. Guest rules in [libraries.md](libraries.md#media-access-rules) (`403 forbidden`, checked before the item is looked up). `404 item_not_found`, `not_found` (unknown file name), `file_not_found` (not ingested, purged, no such file) |
| GET / POST | `/api/playlists` | The caller's playlists (users only: `401` for guests/anonymous). `GET ?cursor=&limit=` → `{items:[playlist], nextCursor?}`, most recently changed first (`updatedAt`, which every rename and entry change bumps); `limit` 1–200, default 50. `POST {name, description?}` → `201 playlist`; `409 too_many_playlists` (1000 per user). A playlist is `{id, name, description?, entryCount, createdAt, updatedAt}`. `name`: cleaned like item metadata, 1–100 characters (`400 invalid_name` / `name_too_long`); `description`: line breaks kept, ≤ 2000 characters (`400 invalid_description` / `description_too_long`); `400 unknown_field` |
| GET / PATCH / DELETE | `/api/playlists/{id}` | `GET` → the playlist plus `entries:[{id, item:{…as in the library…}, addedAt}]` in order (all of them; at most 10 000). `PATCH {name?, description?}` → the playlist (`description` `null`/blank removes it). `DELETE` → `204`; every item in it gets `last_referenced_at` = now. Someone else's playlist is `404 playlist_not_found` |
| POST / PATCH / DELETE | `/api/playlists/{id}/entries[/{entryId}]` | `POST {itemId, beforeEntryId?}` → `201 entry`, before that entry or at the end; the same item may be added again (a new entry). Doesn't add the item to the library, but references it (`last_referenced_at` = now). Item rules as for `PUT /api/library/{itemId}`: `404 item_not_found`, `409 media_expired` / `item_failed` (purged / failed upload), a purged or failed YouTube item is fetched again (`library.youtube`; `403 feature_disabled`, `422 youtube_failed`). `409 playlist_full`. `PATCH {beforeEntryId: id\|null}` → the entry, moved before that entry or to the end (`null`). `409 before_entry_not_found` (not an entry of this playlist), `404 entry_not_found`. `DELETE` → `204` (the item's `last_referenced_at` = now). Positions are server-side only |
| POST | `/api/shares` | `{itemId, toUsername, message?}` → `201 share` (the sender's view). Users only (`401` guests/anonymous); any user may share any item. Adds the item to the recipient's library (`via:"share"`, `sharedBy`; an existing entry is left as it is) and references it (`last_referenced_at` = now). `toUsername` is case-insensitive; an unknown or disabled user is `404 user_not_found`; `400 cannot_share_with_self`. `message`: line breaks kept, ≤ 500 characters (`400 invalid_message` / `message_too_long`). Item rules as for `PUT /api/library/{itemId}` (`404 item_not_found`, `409 media_expired` / `item_failed`, a purged or failed YouTube item is fetched again: `403 feature_disabled`, `422 youtube_failed`). `400 invalid_item_id` / `invalid_username` / `unknown_field`. `429 too_many_shares` with `Retry-After` over 100 shares per sender per rolling hour |
| GET | `/api/shares?direction=in\|out&cursor=&limit=` | Received (`in`, the default) or sent (`out`) shares, newest first → `{items:[share], nextCursor?, unseenCount}`. A share is `{id, item:{…as in the library…}, from: actor, to: actor, message?, createdAt, seen?}`; `seen` only in the recipient's view (the sender never learns whether it was seen, nor whether the item is still in the recipient's library). `unseenCount`: the caller's received shares not yet seen (the notification badge). `limit` 1–200, default 50. `400 invalid_direction` / `invalid_cursor` / `invalid_limit` |
| POST | `/api/shares/seen` | `{ids:[shareId…]}` (≤ 200) or `{all:true}` → `{unseenCount}`: marks received shares as seen. Ids that aren't the caller's received shares are ignored. `400 invalid_ids` / `invalid_all` / `unknown_field` |

## Adding media

| Method | Path | Notes |
|---|---|---|
| POST | `/api/uploads` | `{filename, size, roomId?}` → `201 {uploadId, chunkSize, size}`. With `roomId`, the upload is a request in that room and the room's guest rules apply; guests need a `roomId` (else `401`). `413` over `ingest.maxUploadBytes` (guests: or the quota's item size), `429 upload_quota_exceeded` with `Retry-After` for guests over `guests.uploadQuota`; guest refusals per [library-and-ingest.md](library-and-ingest.md#who-can-add-media) |
| PUT | `/api/uploads/{id}` | Chunk with `Content-Range: bytes start-end/size` → `{receivedBytes, size}`; idempotent (bytes already received are skipped); a chunk must start at or before `receivedBytes` (else `409 upload_offset_mismatch`) |
| GET | `/api/uploads/{id}` | `{receivedBytes, size, itemId?}` (`itemId` once completed) |
| POST | `/api/uploads/{id}/complete` | `{title?, artist?, addToLibrary?}` → `{itemId, deduplicated}`; repeatable. `422 unreadable_media` / `no_audio` / `too_long` (the upload is deleted) |
| POST | `/api/imports/youtube` | `{url, presentAsAudio?, addToLibrary?}` → `{itemId, cached: true\|false, status}` (`cached`: the item was already ready; `status`: `ready`, `pending` or `processing`). `400 invalid_youtube_url`, `422 youtube_live` / `too_long` / `youtube_unavailable` (yt-dlp's error), `422 youtube_failed` with `Retry-After` (failed less than 10 min ago), `503 ytdlp_unavailable`, `504 youtube_timeout` |

Inside a room, requests made by upload or YouTube go through the WebSocket `queue.add` message, which references an `uploadId` or a `youtubeUrl`. That is what writes the request and queue entry.

## Rooms

| Method | Path | Notes |
|---|---|---|
| GET | `/api/rooms?cursor=&limit=` | Anyone. Public rooms, plus every room the caller owns, moderates or is a member of (never one that banned them), newest first → `{items:[room], nextCursor?}`; `limit` 1–200, default 50. A room is `{id, slug, name, owner: actor, visibility, controlMode, settings, createdAt, you:{role?, canEdit, canDelete, canModerate}}` (`role`: the caller's effective room role; `rooms.moderateAny` makes a user `moderator` in every room they don't own). `400 invalid_cursor` / `invalid_limit` |
| POST | `/api/rooms` | `rooms.create` (`401` guests/anonymous, `403 forbidden`). `{name, slug?, visibility?, controlMode?}` → `201 room`; defaults: a slug derived from the name (accents folded, `-2`, `-3`, … while taken), `unlisted`, `vote`. `name` cleaned like item metadata, 1–100 characters; `slug` 3–40 of `a-z 0-9 -`, starting and ending with a letter or digit, never UUID-shaped, lower-cased on input. `400 invalid_name` / `name_too_long` / `invalid_slug` / `invalid_visibility` / `invalid_control_mode` / `unknown_field`, `409 slug_taken` / `too_many_rooms` (100 owned). Audited (`room.created`) |
| GET / PATCH / DELETE | `/api/rooms/{idOrSlug}` | Every `{id}` in the room paths below also takes the slug. `GET` → the room: anyone for `public`/`unlisted`; a `private` room is `404 room_not_found` to everyone but its owner, moderators, members and `rooms.moderateAny`; a banned user gets `403 banned_from_room` (`404` for a private room). `PATCH {name?, slug?, visibility?, controlMode?, settings?}` → the room: the **owner only**; the old slug stops working at once. `settings` is a merge patch of the room settings ([rooms.md](rooms.md#room-settings-settings_json); `400 invalid_settings` / `unknown_setting` / `invalid_setting`). `DELETE` → `204`: the owner, or `rooms.moderateAny`; items queued, suggested or playing there get `last_referenced_at` = now. Guests/anonymous: `401`. Audited (`room.updated`, `room.deleted`) |
| GET | `/r/{slug}` | Short link (the QR target) → room page in remote mode |
| GET | `/api/rooms/{id}/search?q=` | Request search within `requestScope` |
| GET | `/api/rooms/{id}/requests?source=&cursor=` | Request history: owner (read/write), moderators (read-only) |
| POST | `/api/rooms/{id}/requests/add-to-library` | `{requestIds:[…]}` → adds to the owner's library |
| GET / PUT / DELETE | `/api/rooms/{id}/members[/{userId}]` | Users only (`401`). `GET ?cursor=&limit=` → `{items:[{user: actor, role, joinedAt}], nextCursor?}`, owner first, then moderators, members, banned: owner and moderators (`403 forbidden`). `PUT {role: owner\|moderator\|member\|banned}` → the member (`201` if the user had no row, else `200`; `joinedAt` is kept): the owner sets any role for anyone but themselves, moderators only `member`/`banned` for users who aren't the owner or a moderator. `role: owner` **transfers the room** (owner only; the target must be an enabled account, `409 user_disabled`; the old owner becomes a moderator). `DELETE` → `204`, removes the role or lifts the ban: the owner (anyone but themselves), moderators (members and banned users), or the user themselves to leave (not the owner: `409 owner_cannot_leave`; not while banned). `404 user_not_found` / `member_not_found`, `409 cannot_change_own_role`, `400 invalid_role`. Audited (`room.member_role`, `room.member_removed`, `room.owner_transferred`) |
| GET / PUT / DELETE | `/api/rooms/{id}/guest-bans[/{guestId}]` | Users only (`401`); the owner and moderators (`rooms.moderateAny` too), else `403 forbidden`. `GET ?cursor=&limit=` → `{items:[ban], nextCursor?}`, newest first. `GET …/{guestId}` → that guest's ban, also when not banned (so the UI can warn before banning). `PUT …/{guestId}` (no body) → the ban (`201` if new, `200` if already banned). `DELETE …/{guestId}` → `204`, `404 ban_not_found`. A ban is `{guest: actor, identifiedBy: "cookie"\|"ip", banned, bannedAt?, warning?, ip?}`: `warning` for an IP-mode guest (the ban covers everyone sharing that IPv4 address or IPv6 /64), `ip` (the guest's last IP) only for *ip.view*. A ban removes the guest from the room's presence (its sockets are kicked); a banned guest gets `403 banned_from_room` and doesn't see the room listed. `404 guest_not_found`. Audited (`room.guest_banned`, `room.guest_unbanned`) |
| GET / POST / PATCH / DELETE | `/api/rooms/{id}/outputs[/{outputId}]` | `{kind: rtmp\|ts\|hls, rtmpUrl?, rtmpKey?, playbackUrl?, enabled}`. Refused if the server disables that kind |
| POST | `/api/rooms/{id}/outputs/{outputId}/rotate` | New token (ts/hls) |

## Admin

| Path | Notes |
|---|---|
| `/api/admin/settings` | All server settings ([auth.md](auth.md#server-settings)). `GET` lists `{key, group, type, value, default, options?}`; `PATCH {key: value}` is all-or-nothing, `null` resets to the default |
| `/api/admin/roles[/{id}]`, `/api/admin/roles/{id}/permissions` | `GET` (also with *admin.users*) / `POST`; `GET` / `PATCH {name?, isDefault?}` / `DELETE`; `GET` / `PUT {permissions}` |
| `/api/admin/users[/{id}]`, `/api/admin/users/{id}/roles`, `/api/admin/users/{id}/password` | `GET ?q=` / `POST {username, password, displayName?, roleIds?}` (temporary password); `GET` / `PATCH {displayName?, disabled?}`; `GET` / `PUT {roleIds}`; `POST {password}` sets a temporary password. No escalation: see [auth.md](auth.md#roles-and-permissions) |
| `/api/admin/providers` | |
| `/api/admin/jobs`, `/api/admin/ytdlp/update` | |
| `/api/admin/guests?q=&cursor=&limit=` | *admin.users*. `GET` → `{items:[{id, name, kind, nameChosen, banned, createdAt, lastSeenAt, ip?}], nextCursor?}`, most recently seen first; `q` matches part of the name. `ip` (last IP) only for *ip.view* |
| `/api/admin/audit?action=&actor=&cursor=&limit=` | *admin.users*. `GET` → `{items:[{id, at, actor?, action, target?, details?, ip?}], nextCursor?}`, newest first; `action` exact, `actor` a user or guest id. `ip` only for *ip.view* |
| `/api/admin/ip/sessions/{userId}` | *ip.view* (alone is enough). `GET` → `{user: actor, items:[{id, kind, createdAt, lastSeenAt, expiresAt, userAgent?, ip?}]}`: the user's live sessions; `404 user_not_found` |

## Live (no session; token in path)

`GET/HEAD /live/{token}.ts` · `GET /live/{token}/index.m3u8` · `GET /live/{token}/seg/{seq}.ts`

## WebSocket

`GET /ws` (upgrade; `sid` or `gid` cookie, or anonymous). One room per socket. Requests carry `"rid"`, and are answered with `ack` or `error`.

Actions that need an identity (votes, requests, suggestions, vote-skip) are refused from an anonymous socket with error code `identity_required`. The client then calls `POST /api/me/guest`. That sets the `gid` cookie in cookie mode, and returns a single-use `identityTicket` valid for 60 s. The client sends `{type:"identity", ticket}` on the open socket and retries the action. The ticket is needed because a socket that's already open can't see a cookie set after its upgrade.

### Client → server

```jsonc
{ "type": "auth", "token": "…" }
{ "type": "time", "t0": 123456.7 }
{ "type": "join", "room": "slug", "mode": "listen" }    // or "remote"
{ "type": "mode", "mode": "remote" }
{ "type": "leave" }  { "type": "sync" }  { "type": "identity", "ticket": "…" }

{ "type": "queue.add", "rid": 1, "itemId": "…" }         // or "uploadId": "…", or "youtubeUrl": "…"
{ "type": "queue.addPlaylist", "rid": 2, "playlistId": "…", "shuffle": true }
{ "type": "queue.remove", "rid": 3, "entryId": "…" }
{ "type": "queue.move", "rid": 4, "entryId": "…", "beforeEntryId": "…|null" }
{ "type": "queue.pin", "rid": 5, "entryId": "…" }
{ "type": "vote", "rid": 6, "entryId": "…", "value": 1 }
{ "type": "suggest.accept"|"suggest.reject", "rid": 7, "entryId": "…" }
{ "type": "play" } { "type": "pause" } { "type": "seek", "positionMs": 61000 }
{ "type": "next" } { "type": "previous" } { "type": "voteSkip" }
```

### Server → client

```jsonc
{ "type": "time", "t0": 123456.7, "ts": 1762530000123 }
{ "type": "identity", "actor": { "kind": "guest", "id": "…", "name": "Quiet Walrus", "isGuest": true } }

{ "type": "state", "version": 42,
  "room": { "id", "name", "controlMode", "settings", "you": { "role", "canVote", "canRequest", "canUpload", "canYoutube", "canSkip" } },
  "timeline": { "state": "idle|playing|paused", "entryId", "anchorServerMs", "anchorPositionMs" },
  "current": { "entryId", "item": { "id", "kind", "presentAsAudio", "title", "artist", "durationMs", "coverUrl", "audioUrl", "videoUrl",
                                     "originalUrl", "originalGainDb" } },   // original*: web-playable audio uploads only
  "queue": [ { "entryId", "status": "ready|processing", "item": {…}, "addedBy": {actor}, "votes": 3, "yourVote": 1 } ],
  "suggestions": [ … ],
  "history": [ … ],
  "presence": { "listening": 3, "remote": 5, "anonymous": 4 },
  "outputs": [ { "kind": "rtmp", "status": "connected", "estimatedDelayMs": 5000 } ],
  "overlay": { "enabled": true, "videoOverlay": true } }

{ "type": "timeline", "version": 43, "timeline": {…}, "current": {…} }
{ "type": "queue", "version": 44, "op": "add|remove|move|votes|status", … }
{ "type": "presence", … }
{ "type": "output.status", "kind": "rtmp", "status": "connected|retrying|error", "detail": "…" }
{ "type": "request.new", "request": { "id", "item": {…}, "by": {actor}, "via": "youtube" } }   // owner/mods
{ "type": "kicked", "reason": "…" }
```

Clients should treat unknown message types and unknown fields as no-ops.
