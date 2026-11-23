-- Library import (docs/library-and-ingest.md#library-import): an admin imports a music library export
-- (Library.xml plus its media folder) into a user's library. One row per import: who started it, for
-- which user, the source ({"type": "path", "path"} or {"type": "upload", "uploadId"}), the options,
-- the state, the counts and the problems (JSON, at most 1000), the playlists to make (JSON) and an
-- error. One row per track: its file inside the source, its details from Library.xml (JSON), its
-- state and the item it became, so an import resumes after a restart. item_id has no foreign key: a
-- delete record doesn't need to know about imports.
CREATE TABLE library_imports (
    id                 TEXT    NOT NULL PRIMARY KEY,
    user_id            TEXT    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    started_by_user_id TEXT    NULL REFERENCES users (id) ON DELETE SET NULL,
    source_json        TEXT    NOT NULL,
    smart_playlists    INTEGER NOT NULL DEFAULT 0 CHECK (smart_playlists IN (0, 1)),
    state              TEXT    NOT NULL CHECK (state IN ('checking', 'ready', 'importing', 'paused', 'done',
                                                         'failed', 'cancelled')),
    counts_json        TEXT    NOT NULL,
    problems_json      TEXT    NOT NULL,
    playlists_json     TEXT    NOT NULL,
    error              TEXT    NULL,
    created_at         BIGINT  NOT NULL,
    updated_at         BIGINT  NOT NULL,
    started_at         BIGINT  NULL,
    finished_at        BIGINT  NULL
);
CREATE INDEX library_imports_user_id_created_at ON library_imports (user_id, created_at);
CREATE INDEX library_imports_state ON library_imports (state);
CREATE INDEX library_imports_started_by_user_id ON library_imports (started_by_user_id);
CREATE TABLE library_import_tracks (
    import_id    TEXT    NOT NULL REFERENCES library_imports (id) ON DELETE CASCADE,
    position     INTEGER NOT NULL,
    track_id     TEXT    NOT NULL,
    path         TEXT    NULL,
    size         BIGINT  NULL,
    content_id   TEXT    NULL,
    details_json TEXT    NOT NULL,
    state        TEXT    NOT NULL CHECK (state IN ('pending', 'imported', 'already', 'skipped', 'missing')),
    reason       TEXT    NULL,
    item_id      TEXT    NULL,
    PRIMARY KEY (import_id, position)
);
-- The Playlist Persistent ID of the export a playlist was imported from: a re-import of the same
-- export replaces its entries instead of making another playlist.
ALTER TABLE playlists ADD COLUMN import_persistent_id TEXT NULL;
CREATE INDEX playlists_owner_id_import_persistent_id ON playlists (owner_id, import_persistent_id);
