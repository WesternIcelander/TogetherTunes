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
| GET | `/api/library?q=&kind=&cursor=` | The caller's own library |
| GET | `/api/users/{id}/library`, `/api/users/{id}/playlists` | `library.viewAll` only |
| PUT / DELETE | `/api/library/{itemId}` | Add an item to, or remove it from, the caller's library |
| GET | `/api/items/{id}` | Item metadata, by direct link (any actor) |
| PATCH | `/api/items/{id}` | Metadata, `presentAsAudio`: the adder or `library.editAny` |
| DELETE | `/api/items/{id}` | Purge immediately: `library.purge` |
| GET | `/api/items/{id}/job` | Ingest status |
| GET | `/media/{id}/{audio.m4a\|mezz.mp4\|original\|cover.jpg}` | Range-capable. `original` exists only for audio uploads. Guest rules in [libraries.md](libraries.md#media-access-rules) |
| GET / POST | `/api/playlists` | The caller's playlists |
| GET / PATCH / DELETE | `/api/playlists/{id}` | |
| POST / PATCH / DELETE | `/api/playlists/{id}/entries[/{entryId}]` | Add, move (`beforeEntryId`), remove |
| POST | `/api/shares` | `{itemId, toUsername, message?}` |
| GET | `/api/shares?direction=in\|out` | |

## Adding media

| Method | Path | Notes |
|---|---|---|
| POST | `/api/uploads` | `{filename, size, roomId?}` → `{uploadId, chunkSize}`. With `roomId`, the upload is a request in that room and the room's guest rules apply |
| PUT | `/api/uploads/{id}` | Chunk with `Content-Range`; idempotent |
| GET | `/api/uploads/{id}` | `{receivedBytes}` |
| POST | `/api/uploads/{id}/complete` | `{title?, artist?, addToLibrary?}` → `{itemId, deduplicated}` |
| POST | `/api/imports/youtube` | `{url, presentAsAudio?, addToLibrary?}` → `{itemId, cached: true\|false}` |

Inside a room, requests made by upload or YouTube go through the WebSocket `queue.add` message, which references an `uploadId` or a `youtubeUrl`. That is what writes the request and queue entry.

## Rooms

| Method | Path | Notes |
|---|---|---|
| GET | `/api/rooms` | Public rooms, plus the caller's own |
| POST | `/api/rooms` | `rooms.create` |
| GET / PATCH / DELETE | `/api/rooms/{idOrSlug}` | |
| GET | `/r/{slug}` | Short link (the QR target) → room page in remote mode |
| GET | `/api/rooms/{id}/search?q=` | Request search within `requestScope` |
| GET | `/api/rooms/{id}/requests?source=&cursor=` | Request history: owner (read/write), moderators (read-only) |
| POST | `/api/rooms/{id}/requests/add-to-library` | `{requestIds:[…]}` → adds to the owner's library |
| GET / PUT / DELETE | `/api/rooms/{id}/members[/{userId}]` | |
| PUT / DELETE | `/api/rooms/{id}/guest-bans/{guestId}` | |
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
| `/api/admin/guests?q=` | Guests: name, created, last seen. The `ip` field is included only for *ip.view* |
| `/api/admin/audit` | The `ip` field is included only for *ip.view* |
| `/api/admin/ip/sessions/{userId}` | *ip.view* |

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
