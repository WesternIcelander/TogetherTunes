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
      content_id TEXT NULL,                 -- 'sha256c8m:<size>:<hex>', chunked SHA-256 of the upload; the pre-check (0012)
      added_by_user_id NULL, added_by_guest_id NULL, added_at,
      status TEXT,                          -- 'pending'|'processing'|'ready'|'failed'|'purged'
      error NULL, loudness_lufs NULL, metadata_json,
      output_profile TEXT,                  -- encoding its files were made for: profile + video bitrate, e.g. '720p30-3000k-v2'
      original_file TEXT NULL,              -- e.g. 'original.flac', 'original.mkv'; the kept source (every item since 2026-11-10)
      original_codec TEXT NULL,             -- ffprobe audio codec/container of the original
      original_web INTEGER,                 -- 1 if browsers can play the original (audio uploads only)
      original_gain_db REAL NULL,           -- gain the web client applies to the original (audio uploads only)
      present_as_audio INTEGER,             -- 1: a video item played with its card (see library-and-ingest.md)
      encode_preset TEXT NULL,              -- x264 preset its files were made with (NULL: before presets were recorded)
      encode_quality INTEGER,               -- 1: its files are the quality encode (library-and-ingest.md#presets)
      media_version INTEGER,                -- current version of its files: media/<id>/v<N>/ (0: the flat layout from before versions); only grows (an undo links the old files into a new version), kept by a purge (library-and-ingest.md#storage-layout-and-media-versions)
      last_referenced_at BIGINT,            -- see libraries.md#retention
      purged_at NULL)

media_versions(item_id FK, version INTEGER,  -- PK (item_id, version): what the current and the kept undo version were made with
      published_at BIGINT, kind, duration_ms, output_profile, loudness_lufs, encode_preset, encode_quality,  -- the items publish columns
      edit_json TEXT NULL)                  -- edit settings as JSON: videoStartMs?, videoEndMs?, originalMs? (library-and-ingest.md#trim)

media_undo(item_id PK FK, version INTEGER,  -- the item's kept previous version, for undo (one step back)
      switched_at BIGINT,                   -- when the item switched away from it; kept for media.undoHours from then
      undoable INTEGER)                     -- 1: that switch was a person's change (edit or undo); 0: a system re-encode (no Undo)

ingest_jobs(id PK, item_id FK, kind TEXT,   -- 'upload' | 'youtube' | 'reencode' | 'refetch' | 'quality' | 'trim'
      state TEXT, attempts, progress REAL, created_at, started_at NULL, finished_at NULL, log TEXT NULL,
      tier TEXT NULL,                       -- preset tier asked for when queued: 'quick' | 'quality' (NULL: decided at start)
      edit_json TEXT NULL)                  -- a 'trim' job's target edit settings (library-and-ingest.md#trim); NULL otherwise

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
      position REAL, pinned INTEGER, played_at NULL,
      via TEXT NULL,                        -- 'library'|'upload'|'youtube'|'playlist'|'history' (NULL: older entries)
      added_by_name TEXT NULL)              -- adder's display name at add time (NULL: older entries)
-- The room's history is the entries with played_at set and state played/skipped
-- (index (room_id, played_at)); trimmed after rooms.requestHistoryDays. room_requests was
-- dropped in 0011 (its via and by_name copied onto the entries).

votes(entry_id FK, voter_user_id NULL, voter_guest_id NULL, value INTEGER, at,
      UNIQUE(entry_id, voter_user_id), UNIQUE(entry_id, voter_guest_id))
skip_votes(entry_id FK, voter_user_id NULL, voter_guest_id NULL, at, same uniques)

stream_outputs(id PK, room_id FK, kind TEXT,      -- 'rtmp' | 'ts' | 'hls'
      token TEXT UNIQUE NULL,               -- path token for ts/hls
      rtmp_url NULL, rtmp_key_enc NULL,     -- rtmp_key_enc: unused since 0007, always NULL
      enabled INTEGER, created_at,
      rtmp_key TEXT NULL,                   -- the RTMP stream key, plain text (0007)
      stopped_reason TEXT NULL,             -- 'idle': the server turned it off (0009)
      stopped_at BIGINT NULL, stopped_idle_minutes INTEGER NULL)
```

`stream_outputs` has a unique index on `(room_id, kind)`: one output of each kind per room (migration 0006). Migration 0007 added `rtmp_key`: RTMP stream keys are stored in plain text (Siggi, 2026-11-10: RTMP sends the key in plain text anyway, and the owner can reveal and copy it). Keys that older versions stored encrypted in `rtmp_key_enc` are decrypted into `rtmp_key` by the server right after the migrations (`output/LegacyRtmpKeys`, every start, a no-op once done), which then sets `rtmp_key_enc` to NULL; a key that can't be decrypted is dropped with a warning naming the output, never the key. The old column stays, unused. Migration 0009 added `stopped_reason`, `stopped_at` and `stopped_idle_minutes`: set when the server's RTMP idle stop turns an output off ([program-output.md](program-output.md#idle-stop)), cleared whenever `enabled` changes again.

## Secrets and IP addresses at rest

- `client_secret_enc` is encrypted with AES-GCM (so was `rtmp_key_enc` before migration 0007; RTMP stream keys are now plain text in `rtmp_key`, see above). The key comes from `<data>/secret.key`, generated on first start with permissions 0600, or from an environment variable.
- Session tokens and guest cookie tokens are stored only as SHA-256 hashes.
- Stream URL tokens are stored in plaintext: they have to be shown to the owner again, and they are bearer capabilities that can be rotated.
- IP addresses (`sessions.ip`, `guests.ip_last`, `audit_log.ip`) are stored in plaintext, so that admins with `ip.view` can see them. Repository classes **never** put them in the general-purpose DTOs. They are read only by a separate `IpAccess` service, which checks `ip.view` (see [auth.md](auth.md#ip-address-policy)).
