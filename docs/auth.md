# Identity, authentication and authorization

Every request resolves to exactly one **actor**:

| Actor | How it's identified | What it can do |
|---|---|---|
| **User** | Session cookie or bearer token | What their roles and room roles allow |
| **Guest** | Guest cookie, or IP address (server setting) | What the guest policy and room settings allow |
| **Anonymous** | Nothing | Listen in public or unlisted rooms; read public pages. Becomes a guest the moment it does something that needs an identity |

## Accounts

A **user** can have any number of **identities**: one local password credential, and zero or more OIDC identities keyed by `(provider, subject)`.

- **Registration mode** (a setting): `open` | `invite` | `closed`. OIDC providers have their own `allow_signup` flag.
- **First user:** the first account created gets the `admin` role. Until it exists, the server shows a setup page.
- **Linking:** never link accounts automatically by email. A logged-in user links a provider explicitly from their account page (`intent=link`).
- **Guest upgrade:** when a guest registers or logs in, the server offers to **merge** the guest into the account. Merging moves their requests, queue entries and votes to the account. Merging is offered only in the browser that holds the guest cookie, and only in cookie mode, because in IP mode several people can share one guest identity.

### Local passwords

- Hash with **Argon2id**, using Bouncy Castle's `Argon2BytesGenerator`. Parameters: m=64 MiB, t=3, p=1. Store the full encoded string, so the parameters can be raised later and old hashes rehashed on login.
- Minimum length 10; no composition rules. Login is rate-limited per IP (IPv6 by /64) and per username, with exponential backoff: per username 5 free failures, per IP 20, then each further failure locks that key for 1 s, 2 s, 4 s … up to 15 min; a key without failures for an hour starts over, and a successful login clears its username's count. A locked key gets `429 rate_limited` with `Retry-After`, even with the right password. Failures are written to `audit_log` (`auth.login_failed`), and the first refusal of each lock as `auth.login_throttled`. The account routes (setup, register, login/token) also have a per-IP request limit.
- Reset: an admin sets a temporary password (`password_credentials.must_change`); the user is logged out everywhere and, after logging in with it, is asked to choose a new one (`POST /api/me/password`, which needs the current password and logs out the other sessions). Admins can also create accounts with a temporary password. There is no email in v1.

### OIDC

Implement a minimal relying party; no framework.

1. **Discovery:** fetch `{issuer}/.well-known/openid-configuration` and the JWKS, and cache both. Refetch the JWKS when a token has an unknown `kid`. (`OidcDiscovery`: the document's `issuer` must match exactly and its endpoints follow the provider URL rules; only public signing keys are kept; discovery is cached 24 h and the JWKS 1 h, an unknown-`kid` refetch happens at most every 10 s per issuer, and a cached copy keeps being used while the provider is unreachable.)
2. **Authorization request:** authorization code flow with **PKCE (S256)**, plus random `state` and `nonce`, stored server-side and keyed by `state`. Redirect URI: `{publicBaseUrl}/auth/oidc/{slug}/callback`.
3. **Callback:** exchange the code, then validate the ID token:
   - signature against JWKS (RS256 and ES256; reject `none` and HS* unless explicitly configured),
   - `iss`, `aud`, `exp` and `iat` (±60 s), and `nonce`.
   Use Nimbus JOSE+JWT, unless you'd rather hand-roll it.
4. **Resolve the identity** by `(provider_id, sub)`: log in, link (when the intent is `link`), sign up (when `allow_signup` is on), or show an error.
5. **Plain OAuth2 providers** (Discord, GitHub): use `claim_mapping_json`, which holds the userinfo URL and JSON paths for subject, username and display name.

### Login providers

Any number of providers can be enabled at once, each with its own login button; a user can link several. Admins manage them at `/api/admin/providers` (*admin.providers*).

- **kind** `oidc` or `oauth2`, fixed at creation. **slug** (lower-case letters, digits, `-`, at most 32) is in the login URLs and the redirect URI `{publicBaseUrl}/auth/oidc/{slug}/callback`.
- **issuer:** required for OIDC (compared exactly with `iss`); for OAuth it only names the provider (default: the authorization endpoint's origin). Provider URLs must be `https` (`http` only for a loopback host).
- **client secret:** optional (public clients), write-only, stored encrypted with the server key (`client_secret_enc`, context `oidc_providers.client_secret_enc:<id>`).
- **scopes:** space-separated; OIDC must include `openid` (default `openid profile email`).
- **claim_mapping_json:** OIDC: optional claim names `username`, `displayName`, `email` (defaults `preferred_username`, `name`, `email`; the subject is always `sub`). OAuth: `authorizationEndpoint`, `tokenEndpoint`, `userinfoUrl` and `subject` (required), `username`, `displayName`, `email` (optional). Paths are dot-separated names; a number indexes an array (`data.0.id`).
- **enabled** (default on), **allow_signup** (default off).
- While a provider has identities, what gives its subjects their meaning can't change (OIDC: the issuer; OAuth: the userinfo URL and subject path), and it can't be deleted; disable it instead (`409 provider_has_identities`).

## Sessions

- **Web:** an opaque 256-bit token in cookie `sid` with `HttpOnly; Secure; SameSite=Lax; Path=/`. The DB stores only its SHA-256 hash. Sliding expiry of 30 days.
- **API / app:** the same tokens, of kind `bearer`, sent as `Authorization: Bearer …`. Both kinds slide: a use moves `expires_at` (written at most once a minute), and a slid `sid` cookie is re-sent with a fresh `Max-Age`.
- **CSRF:** for cookie-authenticated requests that change state (including guest-cookie requests), require the `Origin` header to match `publicBaseUrl`. In practice, for every method but GET, HEAD and OPTIONS, unless the request has an `Authorization: Bearer` header: an `Origin` that is present must be the origin of `publicBaseUrl` (so cross-site writes are refused even without cookies, which also covers login CSRF and IP-mode guests, whose IP is an ambient credential too), and a request carrying a `sid` or `gid` cookie must send it. Refusals are `403 origin_not_allowed`.
- **WebSocket:** authenticated by cookie (`sid` or `gid`) during the upgrade, with the same Origin check (a present `Origin` must match; none is accepted only without `sid`/`gid`). Bearer clients send `{type:"auth"}` as the first message.

## Roles and permissions

Users get permissions only through **roles**. There are two built-in roles:

- `admin` holds every permission and can't be edited.
- `user` is the default role, given to every new account.

Admins can create more roles (e.g. "trusted", "moderator") and assign several to a user. A user's permissions are the union of their roles' permissions.

| Permission | Meaning |
|---|---|
| `admin.settings`, `admin.users`, `admin.roles`, `admin.providers` | Administration |
| `admin.jobs` | See every encode job with its log on the admin page's Jobs tab; retry failed jobs and cancel queued ones ([library-and-ingest.md](library-and-ingest.md#encode-jobs-page)). Any user sees the jobs of their own library's items without it |
| `ip.view` | See IP addresses anywhere: guests, sessions, audit log, room member lists |
| `library.upload` | Upload files |
| `library.youtube` | Import from YouTube |
| `library.viewAll` | Browse and search other users' libraries and playlists |
| `library.editAny` | Edit the metadata of any item |
| `library.purge` | Delete any item from the server immediately |
| `rooms.create` | Create rooms |
| `rooms.moderateAny` | Act as moderator in any room |
| `output.overlayVideo` | Rooms owned by this user may re-encode music videos to add the QR overlay ([overlay.md](overlay.md)) |

Server-wide feature switches (below) are checked **before** permissions: a disabled feature is off for everyone, admins included, until it is re-enabled.

Details:

- `admin` has no stored permission rows; it holds every permission by definition, including ones added in later versions. It can be granted and taken away, but the last enabled user holding it can't lose it.
- `user` can't be renamed or deleted, but its permissions and its default flag can be changed. It starts with `library.upload`, `library.youtube` and `rooms.create`.
- Any role except `admin` can be marked default (`roles.is_default`); new accounts get every default role.
- Role names are 1–32 letters, digits, spaces, `.`, `_` or `-`, start with a letter or digit, and are unique ignoring case.
- **No escalation through the admin pages:** granting or taking away a role, creating or deleting a role, and changing a role's permissions or default flag need the acting user to hold every permission involved; only holders of `admin` can grant or take away `admin`. A user can't manage (disable, reset the password of, change the roles of) another user who holds `admin` or any permission the actor lacks. Users can't disable themselves.
- Feature switches that gate a permission: `ytdlp.enabled` gates `library.youtube`, `overlay.enabled` gates `output.overlayVideo`. The `outputs.*.enabled` switches gate output kinds directly. A permission check fails with `403 feature_disabled` if the feature is off, then `401 not_authenticated` for guests and anonymous visitors, then `403 forbidden` if the user lacks the permission. Guests never hold permissions; what they may do comes from the guest policy.

## Guests

### Identity assignment

A guest identity is created **lazily**: the first time an anonymous actor does something that needs one (vote, request, upload, YouTube import, suggest, vote-skip).

- **Cookie mode** (`guests.identifyBy = cookie`): generate a 256-bit token, set it in cookie `gid` (`HttpOnly; Secure; SameSite=Lax`, 1-year sliding expiry), and store its SHA-256 hash as `key_hash`.
- **IP mode** (`guests.identifyBy = ip`): the key is the client IP. IPv4 uses the full address. IPv6 uses the **/64 prefix**, because privacy extensions rotate the interface ID. Store the SHA-256 of the normalised key as `key_hash`, and the raw IP in `ip_last`.
  - Consequence: everyone behind one NAT or CGNAT address shares one guest, so they share one vote and one name. The admin UI should state this next to the setting.
  - The client IP is taken from `X-Forwarded-For` **only** when the connecting peer is in `http.trustedProxies`.
- Changing the mode doesn't migrate existing guests; new identities simply use the new mode.

### Names

- By default the server generates a name: `Adjective Noun`, from bundled word lists of about 300 entries each, title-cased, e.g. "Quiet Walrus". If the name is already used (ignoring case) by an active guest (one seen in the last 30 days), it draws again; after 5 tries it appends the smallest free number from 2 ("Quiet Walrus 2").
- If `guests.allowCustomNames` is on, the guest can choose a name when their identity is created, and can change it later.
  - Stored NFKC-normalised, whitespace trimmed and collapsed. Length 2–32 code points; letters of any script (with up to 3 combining marks each), digits, spaces and the punctuation ``.,'’-_!?&():;#+~``; at least one letter or digit. Otherwise `400 invalid_name`.
  - Rejected with `409 name_taken` if it matches (case-insensitively, after NFKC normalisation, ignoring extra spaces) any registered username or display name, disabled accounts included, so guests can't impersonate accounts.
  - Rejected with `400 name_blocked` if it contains an entry of the admin-editable blocklist `guests.nameBlocklist`, compared case-insensitively after NFKC with spaces and punctuation removed (so "b.a.d" matches "bad"; substring match, so entries should be specific).
- Guests are always shown with a **guest badge** in the UI. Ordinary users can't tell guests apart by anything except name and badge: no IP, and no IP-derived hints.

### Guest policy (server settings)

| Setting | Default | Meaning |
|---|---|---|
| `guests.enabled` | true | Off: logged-out users can only listen |
| `guests.identifyBy` | `cookie` | `cookie` \| `ip` |
| `guests.allowCustomNames` | false | |
| `guests.nameBlocklist` | `[]` | Words custom guest names must not contain (see [Names](#names)) |
| `guests.canVote` | true | Upper bound; each room can still switch it off |
| `guests.canRequestLibrary` | true | Request from the room owner's library |
| `guests.canUpload` | false | Upload files as requests; only on while `guests.canRequestLibrary` is (below) |
| `guests.canYoutube` | false | YouTube links as requests |
| `guests.uploadQuota` | 3 per hour, 500 MiB per item | Per guest **and** per IP, whichever is hit first |
| `guests.download` | false | Download any media item; see [libraries.md](libraries.md#media-access-rules) |

Room owners control the same features for their own room, but only within these limits (see [rooms.md](rooms.md#guests-in-rooms)).

**Uploads need library requests** (2026-11-10, [rooms.md](rooms.md#uploads-need-library-requests)): `guests.canUpload` on needs `guests.canRequestLibrary` on. On the admin page, turning guest uploads on while guest library requests are off turns those on too, and turning guest library requests off while guest uploads are on turns uploads off too, with a notice; `PATCH /api/admin/settings` applies the same companion change and lists it in `"adjusted"` (both the wrong way in one patch: `400 invalid_setting`). A stored state that breaks it reads (and shows) as uploads off.

## IP address policy

IP addresses are **never** exposed to an actor that lacks `ip.view`. This holds everywhere: REST responses, WebSocket messages, exports, error messages, and the user's own session list.

- Only the `IpAccess` service reads IP columns, and it checks `ip.view` on every call. (Recording a guest's changed IP compares `ip_last` with the request's IP in SQL, which yields only "same or not"; a source-scan test fails if any other class selects an IP column.) General DTOs (`UserView`, `GuestView`, `SessionView`, `AuditEntryView`) have no IP field.
- Responses that include IPs for permitted viewers are separate endpoints or fields under `/api/admin/…`, and are built by `IpAccess`.
- Logs: application logs may contain IPs, since only server operators can read them. Anything shown in the UI goes through `IpAccess`.
- Test requirement: a test serialises every DTO type and fails if it finds a string that looks like an IP address.

## Server settings

These are all stored in the `settings` table and edited in the admin UI. Some are also config keys ([architecture.md](architecture.md#configuration)): there the config file (or its env var) only gives the default, and a value saved in the admin UI overrides it; resetting the setting goes back to the config's value.

| Group | Settings |
|---|---|
| Accounts | `registration.mode`, default roles |
| Guests | see the table above |
| Library | `ytdlp.enabled`, `ytdlp.cookiesFile`, `retention.unreferencedDays` (default 14; 0 = never delete), `ingest.maxDurationSeconds`, `ingest.quickPreset` / `ingest.qualityPreset` (x264 presets, defaults from the config; a preset that doesn't give the reference avcC is refused, [library-and-ingest.md](library-and-ingest.md#presets)), `ingest.workers` (1..256, default from the config; resizes the ingest pool live: growing starts workers at once, shrinking lets running jobs finish), `ingest.maxUploadBytes` (bytes, shown in GiB; default from the config; applies to new uploads) |
| Storage | `storage.minFreeBytes` (bytes; the admin page shows and takes GiB; default 5 GiB, 0 = no check): adding media (uploads, YouTube fetches) is refused with `507 insufficient_storage` and queued ingest jobs wait while the data volume has less free space ([library-and-ingest.md](library-and-ingest.md#free-disk-space)) |
| Output encoding | `output.profile` (`720p30` \| `1080p30`) and `output.videoBitrateKbps` (500..25000, or unset = the profile's default: 3000 at 720p30, 6000 at 1080p30), defaults from the config (a configured bitrate equal to its profile's default counts as unset, so switching the profile alone gives that profile's default). Changing either one **re-encodes every item**: the admin page first asks, saying how many items will re-encode (`GET /api/admin/encoding`), and the change then applies without a restart ([library-and-ingest.md](library-and-ingest.md#changing-the-encoding-at-runtime)). The bitrate is the encodes' cap and normal rooms' stream rate. `output.audioOnlyBitrateKbps` (500..25000, default from the config, 800): the constant stream rate of audio-only rooms' program output (cards only); changing it re-encodes nothing and applies to outputs started afterwards |
| Media | `media.undoHours` (default 48, 0..8760; 0 = no undo; shown in the Library group of the admin page): how long the previous media version of an item is kept after an edit or re-encode, so whoever may edit the item can undo the change ([library-and-ingest.md](library-and-ingest.md#undo)) |
| Rooms | `rooms.requestHistoryDays` (default 90; 0 = never trim): how long rooms keep their history (the entries that played; the key kept its name from the request history it replaced) |
| Outputs | `outputs.rtmp.enabled`, `outputs.ts.enabled`, `outputs.hls.enabled`, `outputs.mp3.enabled` (all default true); `outputs.mp3.bitrateKbps` (default 128, 64..320): the MP3 output's bitrate, for encodes started after a change ([program-output.md](program-output.md#mp3-internet-radio-players)); `outputs.forceAutoOff` (default false): every room's outputs turn off automatically, whatever the room's `outputs.autoOff` says; `outputs.maxAutoOffMinutes` (default 60, 1..1440): the longest any output keeps running with an empty queue or (`.ts`/HLS/MP3) without listeners; the room setting `outputs.autoOffMinutes` applies up to it ([program-output.md](program-output.md#auto-turn-off)). Before 2026-11-10 the cap was `outputs.rtmp.maxIdleStopMinutes` (RTMP only; migration 0017 renamed stored values) |
| Overlay | `overlay.enabled` (default false). Re-encoding music videos also needs the room owner to hold `output.overlayVideo`. `overlay.workers` (1..256) and `overlay.cacheMaxBytes` (bytes, shown in GiB; 0 keeps nothing), defaults from the config. `overlay.allowQualityPreset` (default false): when on, the next entry's overlay re-encode (made ahead) uses `ingest.qualityPreset`; the playing entry's catch-up always uses `ingest.quickPreset` (read when a job starts) |
| Fonts (the admin page's Fonts tab) | `cards.font` (unset = the bundled DejaVu Sans; `system:<family>` an installed font family, `upload:<id>` an uploaded font; anything else, or a family/upload that doesn't exist, is refused with `400 invalid_setting`; a value that stops being available falls back to the bundled font), `fonts.autoDownload` (default true): download a missing script font when a card needs it, `fonts.cjkVariant` (`jp` \| `kr` \| `sc` \| `tc`, default `jp`): how Han text without kana, hangul or bopomofo is drawn. Changing `cards.font` or `fonts.cjkVariant` makes the filler cards again and queues card-only re-encodes of the items whose cards change ([library-and-ingest.md](library-and-ingest.md#card-fonts)) |

## Stream URLs

VRChat players (and internet-radio players) can't send cookies or headers, so `.ts`, HLS and MP3 URLs identify the stream in the **path**. Public and unlisted rooms use the room's **slug** (not a secret: anyone who can find the room can find its streams); **private rooms** use a capability **token** (Siggi, 2026-11-10; [program-output.md](program-output.md#stream-urls)):

```
{publicBaseUrl}/live/{slug or token}.ts
{publicBaseUrl}/live/{slug or token}/index.m3u8
{publicBaseUrl}/live/{slug or token}.mp3
```

- Token: 128 random bits, base64url, one per `stream_outputs` row, never shaped like a slug. Only a private room's tokens open anything; the owner can rotate them (private rooms only) or disable the output. A room that becomes private gets fresh tokens. Never log a token in full.
- These endpoints need no session. Rate-limit them per IP, and cap concurrent connections per URL (default 64). As built (`output/LiveLimits`): per IP (IPv6 by /64) a burst of 60 requests, then 10/s, checked before the token lookup; 64 concurrent connections per token (`.ts` and MP3 streams, HLS requests in flight); both answer `429`.
- RTMP stream keys are entered by the owner and stored in plain text (`stream_outputs.rtmp_key`; Siggi, 2026-11-10: RTMP itself sends the key in plain text). The API sends the full key to the room's **owner** only (`rtmpKey`), who can reveal and copy it on the room page. Moderators get nothing about the key (no `rtmpKey`, `hasRtmpKey` or last 4; Siggi: "Moderators don't need to see the key period. Only the room owner.") and not the ingest URL either (a service may put credentials in it): the key and URL give access to the owner's account at the RTMP service. The push status detail they see has every URL replaced by `(ingest URL)` and no key hint (`RoomOutputs.moderatorDetail`). Keys stored encrypted by older versions (AES-GCM, context `stream_outputs.rtmp_key_enc:<output id>`) are converted once at startup (`output/LegacyRtmpKeys`). Keys are still never logged (invariant 6): not in the server log, the audit log, or ffmpeg's status detail (masked to `…` + last 4 for the owner). URLs in ffmpeg's lines and the logged target have their user info, query and fragment masked (`rtmp://***@host/app?…`, `RtmpPusher.maskUrlCredentials`). Tokens and keys never go into the audit log (`room.output_created/updated/deleted/token_rotated` name the output, its kind and which fields changed); the RTMP URL is left out too.
