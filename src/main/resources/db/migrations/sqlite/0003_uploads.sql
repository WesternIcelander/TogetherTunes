-- Chunked, resumable uploads (docs/library-and-ingest.md#upload, docs/api.md#adding-media). The
-- bytes live in <data>/tmp/uploads/<id>.part until `complete` hands them to ingest; the row then
-- keeps the result (item_id, deduplicated), so a repeated `complete` gets the same answer and a
-- room request (M3) can refer to the upload. Rows untouched for a day are swept with their file.
CREATE TABLE uploads (
    id           TEXT    NOT NULL PRIMARY KEY,
    user_id      TEXT    NULL REFERENCES users (id) ON DELETE CASCADE,
    guest_id     TEXT    NULL REFERENCES guests (id) ON DELETE CASCADE,
    room_id      TEXT    NULL REFERENCES rooms (id) ON DELETE CASCADE,
    filename     TEXT    NOT NULL,
    size         BIGINT  NOT NULL,
    created_at   BIGINT  NOT NULL,
    updated_at   BIGINT  NOT NULL,
    item_id      TEXT    NULL REFERENCES items (id),
    deduplicated INTEGER NOT NULL DEFAULT 0 CHECK (deduplicated IN (0, 1)),
    CHECK ((user_id IS NULL) <> (guest_id IS NULL))
);
CREATE INDEX uploads_user_id ON uploads (user_id);
CREATE INDEX uploads_guest_id ON uploads (guest_id);
CREATE INDEX uploads_room_id ON uploads (room_id);
CREATE INDEX uploads_item_id ON uploads (item_id);
CREATE INDEX uploads_updated_at ON uploads (updated_at);
