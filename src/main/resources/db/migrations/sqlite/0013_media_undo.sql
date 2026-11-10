-- Undo for media edits (docs/library-and-ingest.md#undo): the previous media version of an item
-- is kept for media.undoHours after a switch, and a person who may edit the item can go back to it.
-- media_versions: what each kept version was made with (the publish columns of items, and the
-- edit settings such as trims, as JSON), so an undo can restore them. At most the current and the
-- kept previous version of an item have a row; rows of older versions are deleted with them.
-- media_undo: an item's kept previous version (one step back), when the item switched away from it,
-- and whether that switch was a person's change (an edit or an undo: only those can be undone; a
-- re-encode made by the system keeps the previous version too, without Undo).
CREATE TABLE media_versions (
    item_id        TEXT    NOT NULL REFERENCES items (id),
    version        INTEGER NOT NULL,
    published_at   BIGINT  NOT NULL,
    kind           TEXT    NOT NULL,
    duration_ms    BIGINT  NULL,
    output_profile TEXT    NOT NULL,
    loudness_lufs  REAL NULL,
    encode_preset  TEXT    NULL,
    encode_quality INTEGER NOT NULL DEFAULT 0 CHECK (encode_quality IN (0, 1)),
    edit_json      TEXT    NULL,
    PRIMARY KEY (item_id, version)
);
CREATE TABLE media_undo (
    item_id     TEXT    NOT NULL PRIMARY KEY REFERENCES items (id),
    version     INTEGER NOT NULL,
    switched_at BIGINT  NOT NULL,
    undoable    INTEGER NOT NULL CHECK (undoable IN (0, 1))
);
