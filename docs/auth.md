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
- Minimum length 10; no composition rules. Login is rate-limited per IP and per username, with exponential backoff. Failures are written to `audit_log`.
- Reset: an admin sets a temporary password. There is no email in v1.

### OIDC

Implement a minimal relying party; no framework.

1. **Discovery:** fetch `{issuer}/.well-known/openid-configuration` and the JWKS, and cache both. Refetch the JWKS when a token has an unknown `kid`.
2. **Authorization request:** authorization code flow with **PKCE (S256)**, plus random `state` and `nonce`, stored server-side and keyed by `state`. Redirect URI: `{publicBaseUrl}/auth/oidc/{slug}/callback`.
3. **Callback:** exchange the code, then validate the ID token:
   - signature against JWKS (RS256 and ES256; reject `none` and HS* unless explicitly configured),
   - `iss`, `aud`, `exp` and `iat` (±60 s), and `nonce`.
   Use Nimbus JOSE+JWT, unless you'd rather hand-roll it.
4. **Resolve the identity** by `(provider_id, sub)`: log in, link (when the intent is `link`), sign up (when `allow_signup` is on), or show an error.
5. **Plain OAuth2 providers** (Discord, GitHub): use `claim_mapping_json`, which holds the userinfo URL and JSON paths for subject, username and display name.

## Sessions

- **Web:** an opaque 256-bit token in cookie `sid` with `HttpOnly; Secure; SameSite=Lax; Path=/`. The DB stores only its SHA-256 hash. Sliding expiry of 30 days.
- **API / app:** the same tokens, of kind `bearer`, sent as `Authorization: Bearer …`. Both kinds slide: a use moves `expires_at` (written at most once a minute), and a slid `sid` cookie is re-sent with a fresh `Max-Age`.
- **CSRF:** for cookie-authenticated requests that change state (including guest-cookie requests), require the `Origin` header to match `publicBaseUrl`.
- **WebSocket:** authenticated by cookie (`sid` or `gid`) during the upgrade, with the same Origin check. Bearer clients send `{type:"auth"}` as the first message.

## Roles and permissions

Users get permissions only through **roles**. There are two built-in roles:

- `admin` holds every permission and can't be edited.
- `user` is the default role, given to every new account.

Admins can create more roles (e.g. "trusted", "moderator") and assign several to a user. A user's permissions are the union of their roles' permissions.

| Permission | Meaning |
|---|---|
| `admin.settings`, `admin.users`, `admin.roles`, `admin.providers` | Administration |
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

## Guests

### Identity assignment

A guest identity is created **lazily**: the first time an anonymous actor does something that needs one (vote, request, upload, YouTube import, suggest, vote-skip).

- **Cookie mode** (`guests.identifyBy = cookie`): generate a 256-bit token, set it in cookie `gid` (`HttpOnly; Secure; SameSite=Lax`, 1-year sliding expiry), and store its SHA-256 hash as `key_hash`.
- **IP mode** (`guests.identifyBy = ip`): the key is the client IP. IPv4 uses the full address. IPv6 uses the **/64 prefix**, because privacy extensions rotate the interface ID. Store the SHA-256 of the normalised key as `key_hash`, and the raw IP in `ip_last`.
  - Consequence: everyone behind one NAT or CGNAT address shares one guest, so they share one vote and one name. The admin UI should state this next to the setting.
  - The client IP is taken from `X-Forwarded-For` **only** when the connecting peer is in `http.trustedProxies`.
- Changing the mode doesn't migrate existing guests; new identities simply use the new mode.

### Names

- By default the server generates a name: `Adjective Noun`, from bundled word lists of about 300 entries each, title-cased, e.g. "Quiet Walrus". If the name is already used by an active guest, it draws again; after 5 tries it appends a number.
- If `guests.allowCustomNames` is on, the guest can choose a name when their identity is created, and can change it later.
  - Length 2–32; letters, digits, spaces and basic punctuation; whitespace trimmed and collapsed.
  - Rejected if it matches (case-insensitively, after NFKC normalisation) any registered username or display name, so guests can't impersonate accounts.
  - Optionally checked against an admin-editable blocklist.
- Guests are always shown with a **guest badge** in the UI. Ordinary users can't tell guests apart by anything except name and badge: no IP, and no IP-derived hints.

### Guest policy (server settings)

| Setting | Default | Meaning |
|---|---|---|
| `guests.enabled` | true | Off: logged-out users can only listen |
| `guests.identifyBy` | `cookie` | `cookie` \| `ip` |
| `guests.allowCustomNames` | false | |
| `guests.canVote` | true | Upper bound; each room can still switch it off |
| `guests.canRequestLibrary` | true | Request from the room owner's library |
| `guests.canUpload` | false | Upload files as requests |
| `guests.canYoutube` | false | YouTube links as requests |
| `guests.uploadQuota` | 3 per hour, 500 MiB per item | Per guest **and** per IP, whichever is hit first |
| `guests.download` | false | Download any media item; see [libraries.md](libraries.md#media-access-rules) |

Room owners control the same features for their own room, but only within these limits (see [rooms.md](rooms.md#guests-in-rooms)).

## IP address policy

IP addresses are **never** exposed to an actor that lacks `ip.view`. This holds everywhere: REST responses, WebSocket messages, exports, error messages, and the user's own session list.

- Only the `IpAccess` service reads IP columns, and it checks `ip.view` on every call. General DTOs (`UserView`, `GuestView`, `SessionView`, `AuditEntryView`) have no IP field.
- Responses that include IPs for permitted viewers are separate endpoints or fields under `/api/admin/…`, and are built by `IpAccess`.
- Logs: application logs may contain IPs, since only server operators can read them. Anything shown in the UI goes through `IpAccess`.
- Test requirement: a test serialises every DTO type and fails if it finds a string that looks like an IP address.

## Server settings

These are all stored in the `settings` table and edited in the admin UI.

| Group | Settings |
|---|---|
| Accounts | `registration.mode`, default roles |
| Guests | see the table above |
| Library | `ytdlp.enabled`, `ytdlp.cookiesFile`, `retention.unreferencedDays` (default 14; 0 = never delete), `ingest.maxDurationSeconds` |
| Outputs | `outputs.rtmp.enabled`, `outputs.ts.enabled`, `outputs.hls.enabled` (all default true) |
| Overlay | `overlay.enabled` (default false). Re-encoding music videos also needs the room owner to hold `output.overlayVideo` |

## Stream URL tokens

VRChat players can't send cookies or headers, so `.ts` and HLS URLs carry a capability token in the **path**:

```
{publicBaseUrl}/live/{token}.ts
{publicBaseUrl}/live/{token}/index.m3u8
```

- Token: 128 random bits, base64url, one per `stream_outputs` row. The owner can rotate it or disable it. Never log it in full.
- These endpoints need no session. Rate-limit them per IP, and cap concurrent connections per token (default 64).
- RTMP stream keys are entered by the owner, stored encrypted, and never sent back in full (only the last 4 characters are shown).
