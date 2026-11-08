-- Initial schema: every table in docs/data-model.md.
-- Keep equivalent to postgres/0001_initial_schema.sql (same tables, columns, nullability,
-- keys, constraints and indexes). Type mapping: TEXT = TEXT, BIGINT = BIGINT, INTEGER = INTEGER,
-- REAL here = DOUBLE PRECISION there (both 8-byte floats).
-- IDs are UUIDv7 TEXT, times are epoch ms BIGINT, booleans are INTEGER 0/1, enums are TEXT
-- checked in code. Actor pairs (*_user_id / *_guest_id) carry a CHECK that exactly one is set.

-- ------------------------------------------------------------ accounts, roles and auth

CREATE TABLE users (
    id           TEXT    NOT NULL PRIMARY KEY,
    username     TEXT    NOT NULL UNIQUE,
    display_name TEXT    NOT NULL,
    email        TEXT    NULL,
    created_at   BIGINT  NOT NULL,
    disabled     INTEGER NOT NULL DEFAULT 0 CHECK (disabled IN (0, 1))
);

CREATE TABLE password_credentials (
    user_id    TEXT   NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    hash       TEXT   NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE oidc_providers (
    id                 TEXT    NOT NULL PRIMARY KEY,
    slug               TEXT    NOT NULL UNIQUE,
    display_name       TEXT    NOT NULL,
    issuer             TEXT    NOT NULL,
    client_id          TEXT    NOT NULL,
    client_secret_enc  TEXT    NULL,
    scopes             TEXT    NOT NULL,
    enabled            INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    allow_signup       INTEGER NOT NULL DEFAULT 0 CHECK (allow_signup IN (0, 1)),
    claim_mapping_json TEXT    NULL
);

CREATE TABLE user_identities (
    id            TEXT   NOT NULL PRIMARY KEY,
    user_id       TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider_id   TEXT   NOT NULL REFERENCES oidc_providers (id) ON DELETE CASCADE,
    subject       TEXT   NOT NULL,
    email         TEXT   NULL,
    created_at    BIGINT NOT NULL,
    last_login_at BIGINT NULL,
    UNIQUE (provider_id, subject)
);
CREATE INDEX user_identities_user_id ON user_identities (user_id);

CREATE TABLE roles (
    id         TEXT    NOT NULL PRIMARY KEY,
    name       TEXT    NOT NULL UNIQUE,
    builtin    INTEGER NOT NULL DEFAULT 0 CHECK (builtin IN (0, 1)),
    is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0, 1))
);

CREATE TABLE role_permissions (
    role_id    TEXT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission TEXT NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE user_roles (
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX user_roles_role_id ON user_roles (role_id);

CREATE TABLE sessions (
    id           TEXT   NOT NULL PRIMARY KEY,
    user_id      TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   TEXT   NOT NULL UNIQUE,
    kind         TEXT   NOT NULL,
    created_at   BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    expires_at   BIGINT NOT NULL,
    user_agent   TEXT   NULL,
    ip           TEXT   NULL
);
CREATE INDEX sessions_user_id ON sessions (user_id);
CREATE INDEX sessions_expires_at ON sessions (expires_at);

CREATE TABLE guests (
    id           TEXT    NOT NULL PRIMARY KEY,
    kind         TEXT    NOT NULL,
    key_hash     TEXT    NOT NULL UNIQUE,
    ip_last      TEXT    NULL,
    display_name TEXT    NOT NULL,
    name_chosen  INTEGER NOT NULL DEFAULT 0 CHECK (name_chosen IN (0, 1)),
    created_at   BIGINT  NOT NULL,
    last_seen_at BIGINT  NOT NULL,
    banned       INTEGER NOT NULL DEFAULT 0 CHECK (banned IN (0, 1))
);
CREATE INDEX guests_display_name ON guests (display_name);

CREATE TABLE settings (
    key        TEXT NOT NULL PRIMARY KEY,
    value_json TEXT NOT NULL
);

-- No foreign keys: the audit log outlives the users and guests it mentions. Both actor
-- columns may be NULL (e.g. a failed login by nobody in particular).
CREATE TABLE audit_log (
    id             TEXT   NOT NULL PRIMARY KEY,
    at             BIGINT NOT NULL,
    actor_user_id  TEXT   NULL,
    actor_guest_id TEXT   NULL,
    ip             TEXT   NULL,
    action         TEXT   NOT NULL,
    target         TEXT   NULL,
    details_json   TEXT   NULL,
    CHECK (actor_user_id IS NULL OR actor_guest_id IS NULL)
);
CREATE INDEX audit_log_at ON audit_log (at);

-- ------------------------------------------------------------ items, libraries, playlists

CREATE TABLE items (
    id                 TEXT    NOT NULL PRIMARY KEY,
    kind               TEXT    NOT NULL,
    title              TEXT    NOT NULL,
    artist             TEXT    NULL,
    album              TEXT    NULL,
    duration_ms        BIGINT  NULL,
    source             TEXT    NOT NULL,
    youtube_id         TEXT    NULL UNIQUE,
    content_sha256     TEXT    NULL,
    added_by_user_id   TEXT    NULL REFERENCES users (id),
    added_by_guest_id  TEXT    NULL REFERENCES guests (id),
    added_at           BIGINT  NOT NULL,
    status             TEXT    NOT NULL,
    error              TEXT    NULL,
    output_profile     TEXT    NOT NULL,
    loudness_lufs      REAL    NULL,
    metadata_json      TEXT    NULL,
    original_file      TEXT    NULL,
    original_codec     TEXT    NULL,
    original_web       INTEGER NOT NULL DEFAULT 0 CHECK (original_web IN (0, 1)),
    original_gain_db   REAL    NULL,
    last_referenced_at BIGINT  NOT NULL,
    purged_at          BIGINT  NULL,
    CHECK ((added_by_user_id IS NULL) <> (added_by_guest_id IS NULL))
);
CREATE INDEX items_content_sha256 ON items (content_sha256);
CREATE INDEX items_status_last_referenced_at ON items (status, last_referenced_at);
CREATE INDEX items_added_by_user_id ON items (added_by_user_id);
CREATE INDEX items_added_by_guest_id ON items (added_by_guest_id);

CREATE TABLE ingest_jobs (
    id          TEXT    NOT NULL PRIMARY KEY,
    item_id     TEXT    NOT NULL REFERENCES items (id),
    kind        TEXT    NOT NULL,
    state       TEXT    NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    progress    REAL    NOT NULL DEFAULT 0,
    created_at  BIGINT  NOT NULL,
    started_at  BIGINT  NULL,
    finished_at BIGINT  NULL,
    log         TEXT    NULL
);
CREATE INDEX ingest_jobs_item_id ON ingest_jobs (item_id);
CREATE INDEX ingest_jobs_state ON ingest_jobs (state, created_at);

CREATE TABLE library_entries (
    user_id           TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    item_id           TEXT   NOT NULL REFERENCES items (id),
    added_at          BIGINT NOT NULL,
    via               TEXT   NOT NULL,
    shared_by_user_id TEXT   NULL REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, item_id)
);
CREATE INDEX library_entries_item_id ON library_entries (item_id);

CREATE TABLE playlists (
    id          TEXT   NOT NULL PRIMARY KEY,
    owner_id    TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT   NOT NULL,
    description TEXT   NULL,
    created_at  BIGINT NOT NULL,
    updated_at  BIGINT NOT NULL
);
CREATE INDEX playlists_owner_id ON playlists (owner_id);

CREATE TABLE playlist_entries (
    id          TEXT   NOT NULL PRIMARY KEY,
    playlist_id TEXT   NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    item_id     TEXT   NOT NULL REFERENCES items (id),
    position    REAL   NOT NULL,
    added_at    BIGINT NOT NULL
);
CREATE INDEX playlist_entries_playlist_id_position ON playlist_entries (playlist_id, position);
CREATE INDEX playlist_entries_item_id ON playlist_entries (item_id);

CREATE TABLE shares (
    id           TEXT    NOT NULL PRIMARY KEY,
    item_id      TEXT    NOT NULL REFERENCES items (id),
    from_user_id TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    to_user_id   TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message      TEXT    NULL,
    created_at   BIGINT  NOT NULL,
    seen         INTEGER NOT NULL DEFAULT 0 CHECK (seen IN (0, 1))
);
CREATE INDEX shares_to_user_id ON shares (to_user_id, created_at);
CREATE INDEX shares_from_user_id ON shares (from_user_id);
CREATE INDEX shares_item_id ON shares (item_id);

-- ------------------------------------------------------------ rooms

-- current_entry_id references queue_entries, which references rooms: SQLite resolves the
-- forward reference when the constraint is checked.
CREATE TABLE rooms (
    id                 TEXT   NOT NULL PRIMARY KEY,
    slug               TEXT   NOT NULL UNIQUE,
    name               TEXT   NOT NULL,
    owner_id           TEXT   NOT NULL REFERENCES users (id),
    created_at         BIGINT NOT NULL,
    visibility         TEXT   NOT NULL,
    control_mode       TEXT   NOT NULL,
    settings_json      TEXT   NOT NULL,
    playback_state     TEXT   NOT NULL,
    current_entry_id   TEXT   NULL REFERENCES queue_entries (id) ON DELETE SET NULL,
    anchor_server_ms   BIGINT NOT NULL DEFAULT 0,
    anchor_position_ms BIGINT NOT NULL DEFAULT 0,
    version            BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX rooms_owner_id ON rooms (owner_id);
CREATE INDEX rooms_current_entry_id ON rooms (current_entry_id);

CREATE TABLE room_members (
    room_id   TEXT   NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    user_id   TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      TEXT   NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX room_members_user_id ON room_members (user_id);

CREATE TABLE room_guest_bans (
    room_id    TEXT   NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    guest_id   TEXT   NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (room_id, guest_id)
);
CREATE INDEX room_guest_bans_guest_id ON room_guest_bans (guest_id);

CREATE TABLE queue_entries (
    id                TEXT    NOT NULL PRIMARY KEY,
    room_id           TEXT    NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    item_id           TEXT    NOT NULL REFERENCES items (id),
    added_by_user_id  TEXT    NULL REFERENCES users (id),
    added_by_guest_id TEXT    NULL REFERENCES guests (id),
    added_at          BIGINT  NOT NULL,
    state             TEXT    NOT NULL,
    position          REAL    NOT NULL,
    pinned            INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0, 1)),
    played_at         BIGINT  NULL,
    CHECK ((added_by_user_id IS NULL) <> (added_by_guest_id IS NULL))
);
CREATE INDEX queue_entries_room_id_state_position ON queue_entries (room_id, state, position);
CREATE INDEX queue_entries_item_id ON queue_entries (item_id);
CREATE INDEX queue_entries_added_by_user_id ON queue_entries (added_by_user_id);
CREATE INDEX queue_entries_added_by_guest_id ON queue_entries (added_by_guest_id);

CREATE TABLE votes (
    entry_id       TEXT    NOT NULL REFERENCES queue_entries (id) ON DELETE CASCADE,
    voter_user_id  TEXT    NULL REFERENCES users (id) ON DELETE CASCADE,
    voter_guest_id TEXT    NULL REFERENCES guests (id) ON DELETE CASCADE,
    value          INTEGER NOT NULL,
    at             BIGINT  NOT NULL,
    UNIQUE (entry_id, voter_user_id),
    UNIQUE (entry_id, voter_guest_id),
    CHECK ((voter_user_id IS NULL) <> (voter_guest_id IS NULL))
);
CREATE INDEX votes_voter_user_id ON votes (voter_user_id);
CREATE INDEX votes_voter_guest_id ON votes (voter_guest_id);

CREATE TABLE skip_votes (
    entry_id       TEXT   NOT NULL REFERENCES queue_entries (id) ON DELETE CASCADE,
    voter_user_id  TEXT   NULL REFERENCES users (id) ON DELETE CASCADE,
    voter_guest_id TEXT   NULL REFERENCES guests (id) ON DELETE CASCADE,
    at             BIGINT NOT NULL,
    UNIQUE (entry_id, voter_user_id),
    UNIQUE (entry_id, voter_guest_id),
    CHECK ((voter_user_id IS NULL) <> (voter_guest_id IS NULL))
);
CREATE INDEX skip_votes_voter_user_id ON skip_votes (voter_user_id);
CREATE INDEX skip_votes_voter_guest_id ON skip_votes (voter_guest_id);

-- by_name keeps the requester's name, because guests can be renamed or deleted: the actor
-- columns are set NULL when the requester goes away, so at most one of them is set.
CREATE TABLE room_requests (
    id                     TEXT    NOT NULL PRIMARY KEY,
    room_id                TEXT    NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    item_id                TEXT    NOT NULL REFERENCES items (id),
    by_user_id             TEXT    NULL REFERENCES users (id) ON DELETE SET NULL,
    by_guest_id            TEXT    NULL REFERENCES guests (id) ON DELETE SET NULL,
    by_name                TEXT    NOT NULL,
    via                    TEXT    NOT NULL,
    at                     BIGINT  NOT NULL,
    entry_id               TEXT    NULL REFERENCES queue_entries (id) ON DELETE SET NULL,
    added_to_owner_library INTEGER NOT NULL DEFAULT 0 CHECK (added_to_owner_library IN (0, 1)),
    CHECK (by_user_id IS NULL OR by_guest_id IS NULL)
);
CREATE INDEX room_requests_room_id_at ON room_requests (room_id, at);
CREATE INDEX room_requests_item_id ON room_requests (item_id);
CREATE INDEX room_requests_by_user_id ON room_requests (by_user_id);
CREATE INDEX room_requests_by_guest_id ON room_requests (by_guest_id);
CREATE INDEX room_requests_entry_id ON room_requests (entry_id);

CREATE TABLE stream_outputs (
    id           TEXT    NOT NULL PRIMARY KEY,
    room_id      TEXT    NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL,
    token        TEXT    NULL UNIQUE,
    rtmp_url     TEXT    NULL,
    rtmp_key_enc TEXT    NULL,
    enabled      INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at   BIGINT  NOT NULL
);
CREATE INDEX stream_outputs_room_id ON stream_outputs (room_id);
