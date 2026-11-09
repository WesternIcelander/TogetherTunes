# Data model

## Conventions

- **IDs:** UUIDv7, stored as `TEXT` (36 characters) in both dialects. They sort by creation time and need no sequences, so the same code works on SQLite and PostgreSQL. UUIDv7 contains 74 random bits, so item IDs in direct links can't be guessed or enumerated, even though their creation time is visible.
- **Times:** `BIGINT` epoch milliseconds, UTC. No dialect-specific date types.
- **Booleans:** `INTEGER` 0/1. **Enums:** `TEXT`, checked in code.
- **JSON blobs:** `TEXT`, only for data that is never queried (settings, raw metadata).
- **Actors:** anything a user *or* a guest can do (request, vote, add to a queue) records the actor as two nullable columns, `*_user_id` and `*_guest_id`, with a CHECK that exactly one of them is set.
- **Migrations:** numbered SQL files per dialect, `db/migrations/{sqlite,postgres}/NNNN_name.sql`, applied in a transaction and recorded in `schema_migrations`. Keep the two sets equivalent; a test runs both against real databases.
- **SQLite:** `PRAGMA journal_mode=WAL`, `foreign_keys=ON` and `busy_timeout=5000`. Route writes through a single connection, or through a small pool with retry.
- **Data access:** plain JDBC behind small repository classes. A `Dialect` interface covers the few differences (upsert syntax, `RETURNING`, case-insensitive search).

## Tables

### Accounts, roles and auth

```
users(id PK, username UNIQUE, display_name, email NULL, created_at, disabled INTEGER)

password_credentials(user_id PK FK, hash TEXT, updated_at,             -- Argon2id encoded string
      must_change INTEGER)                                           -- 1: temporary password set by an admin

user_identities(id PK, user_id FK, provider_id FK, subject TEXT,
      email NULL, created_at, last_login_at, UNIQUE(provider_id, subject))

oidc_providers(id PK, slug UNIQUE, display_name, issuer, client_id,
      client_secret_enc, scopes, enabled INTEGER, allow_signup INTEGER,
      claim_mapping_json)

roles(id PK, name UNIQUE, builtin INTEGER, is_default INTEGER)         -- builtin: 'admin', 'user'
role_permissions(role_id FK, permission TEXT, PRIMARY KEY(role_id, permission))
user_roles(user_id FK, role_id FK, PRIMARY KEY(user_id, role_id))

sessions(id PK, user_id FK, token_hash UNIQUE, kind TEXT,              -- 'cookie' | 'bearer'
      created_at, last_seen_at, expires_at, user_agent, ip)

guests(id PK, kind TEXT,                    -- 'cookie' | 'ip'
      key_hash TEXT UNIQUE,                 -- SHA-256 of the cookie token, or of the normalised IP key
      ip_last TEXT NULL,                    -- last seen IP (visible to ip.view only)
      display_name, name_chosen INTEGER,    -- 0 = generated adjective+noun
      created_at, last_seen_at, banned INTEGER)

settings(key PK, value_json)                -- server settings, see auth.md
audit_log(id PK, at, actor_user_id NULL, actor_guest_id NULL, ip NULL, action, target, details_json)
```

### Items, libraries, playlists

```
items(id PK, kind TEXT,                     -- 'audio' | 'video'
      title, artist NULL, album NULL, duration_ms,
      source TEXT,                          -- 'upload' | 'youtube'
      youtube_id TEXT NULL UNIQUE,          -- set for youtube; the cache key
      content_sha256 TEXT NULL,             -- of the original upload; dedupe
      added_by_user_id NULL, added_by_guest_id NULL, added_at,
      status TEXT,                          -- 'pending'|'processing'|'ready'|'failed'|'purged'
      error NULL, output_profile TEXT, loudness_lufs NULL, metadata_json,
      original_file TEXT NULL,              -- e.g. 'original.flac'; audio uploads only
      original_codec TEXT NULL,             -- ffprobe codec/container of the original
      original_web INTEGER,                 -- 1 if browsers can play the original
      original_gain_db REAL NULL,           -- gain the web client applies to the original
      present_as_audio INTEGER,             -- 1: a video item played with its card (see library-and-ingest.md)
      last_referenced_at BIGINT,            -- see libraries.md#retention
      purged_at NULL)

ingest_jobs(id PK, item_id FK, kind TEXT,   -- 'upload' | 'youtube' | 'reencode' | 'refetch'
      state TEXT, attempts, progress REAL, created_at, started_at NULL, finished_at NULL, log TEXT NULL)

uploads(id PK, user_id NULL, guest_id NULL,  -- exactly one owner
      room_id NULL,                         -- set when the upload is a room request (M3)
      filename, size BIGINT, created_at, updated_at,
      item_id NULL,                         -- set by `complete`; the bytes are then gone
      deduplicated INTEGER)                 -- 1 if `complete` reused an existing item

guest_upload_starts(id PK, guest_id FK,     -- one row per upload a guest started (guests.uploadQuota)
      ip_key_hash NULL,                     -- SHA-256 of the IP key (IPv4 address / IPv6 /64), like guests.key_hash; no IP stored
      created_at)                           -- counted over the last hour; older rows swept with the uploads

library_entries(user_id FK, item_id FK, added_at,
      via TEXT,                             -- 'upload'|'youtube'|'request'|'share'|'manual'
      shared_by_user_id NULL,
      PRIMARY KEY(user_id, item_id))

playlists(id PK, owner_id FK, name, description NULL, created_at, updated_at)
playlist_entries(id PK, playlist_id FK, item_id FK, position REAL, added_at)

shares(id PK, item_id FK, from_user_id FK, to_user_id FK, message NULL, created_at, seen INTEGER)
```

`purged` items keep their row (title, artist, `youtube_id`), so that history stays readable and a YouTube item can be fetched again. Their files are deleted.

### Rooms

```
rooms(id PK, slug UNIQUE, name, owner_id FK, created_at,
      visibility TEXT,                      -- 'public' | 'unlisted' | 'private'
      control_mode TEXT,                    -- 'owner' | 'vote' | 'open'
      settings_json,                        -- see rooms.md (incl. guest toggles, overlay settings)
      playback_state TEXT,                  -- 'idle' | 'playing' | 'paused' (extensible; see rooms.md)
      current_entry_id NULL, anchor_server_ms, anchor_position_ms,
      version BIGINT)

room_members(room_id FK, user_id FK, role TEXT,   -- 'owner' | 'moderator' | 'member' | 'banned'
      joined_at, PRIMARY KEY(room_id, user_id))
room_guest_bans(room_id FK, guest_id FK, created_at, PRIMARY KEY(room_id, guest_id))

queue_entries(id PK, room_id FK, item_id FK,
      added_by_user_id NULL, added_by_guest_id NULL, added_at,
      state TEXT,                           -- 'queued'|'suggested'|'playing'|'played'|'skipped'|'removed'
      position REAL, pinned INTEGER, played_at NULL)

votes(entry_id FK, voter_user_id NULL, voter_guest_id NULL, value INTEGER, at,
      UNIQUE(entry_id, voter_user_id), UNIQUE(entry_id, voter_guest_id))
skip_votes(entry_id FK, voter_user_id NULL, voter_guest_id NULL, at, same uniques)

room_requests(id PK, room_id FK, item_id FK,
      by_user_id NULL, by_guest_id NULL,
      by_name TEXT,                         -- display name at request time (guests can be renamed/deleted)
      via TEXT,                             -- 'library' | 'upload' | 'youtube'
      at, entry_id NULL,                    -- the queue entry created, if any
      added_to_owner_library INTEGER)

stream_outputs(id PK, room_id FK, kind TEXT,      -- 'rtmp' | 'ts' | 'hls'
      token TEXT UNIQUE NULL,               -- path token for ts/hls
      rtmp_url NULL, rtmp_key_enc NULL, enabled INTEGER, created_at)
```

## Secrets and IP addresses at rest

- `client_secret_enc` and `rtmp_key_enc` are encrypted with AES-GCM. The key comes from `<data>/secret.key`, generated on first start with permissions 0600, or from an environment variable.
- Session tokens and guest cookie tokens are stored only as SHA-256 hashes.
- Stream URL tokens are stored in plaintext: they have to be shown to the owner again, and they are bearer capabilities that can be rotated.
- IP addresses (`sessions.ip`, `guests.ip_last`, `audit_log.ip`) are stored in plaintext, so that admins with `ip.view` can see them. Repository classes **never** put them in the general-purpose DTOs. They are read only by a separate `IpAccess` service, which checks `ip.view` (see [auth.md](auth.md#ip-address-policy)).
