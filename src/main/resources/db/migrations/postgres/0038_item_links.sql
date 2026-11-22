-- Linked media (docs/library-and-ingest.md#linked-media): an item's music videos, lyric videos and
-- external links, in the editor's order (position, smallest first). kind 'music_video' and
-- 'lyric_video' point to another item (target_item_id), 'external' to an http(s) URL with an
-- optional label. At most 20 per item and no link to the item itself or the same target / URL twice
-- (the API checks the count; the rest is also enforced here). No ON DELETE on the item references:
-- a delete record removes the rows on both sides explicitly. added_by: the user who saved the link
-- (NULL once that account is gone).
CREATE TABLE item_links (
    id             TEXT    NOT NULL PRIMARY KEY,
    item_id        TEXT    NOT NULL REFERENCES items (id),
    position       INTEGER NOT NULL,
    kind           TEXT    NOT NULL CHECK (kind IN ('music_video', 'lyric_video', 'external')),
    target_item_id TEXT    NULL REFERENCES items (id),
    url            TEXT    NULL,
    label          TEXT    NULL,
    added_by       TEXT    NULL REFERENCES users (id) ON DELETE SET NULL,
    added_at       BIGINT  NOT NULL,
    CHECK ((kind = 'external' AND url IS NOT NULL AND target_item_id IS NULL AND (label IS NULL OR length(label) <= 100))
        OR (kind <> 'external' AND url IS NULL AND label IS NULL AND target_item_id IS NOT NULL
            AND target_item_id <> item_id))
);
CREATE INDEX item_links_item_id_position ON item_links (item_id, position);
CREATE INDEX item_links_target_item_id ON item_links (target_item_id);
CREATE UNIQUE INDEX item_links_item_id_target ON item_links (item_id, target_item_id);
CREATE UNIQUE INDEX item_links_item_id_url ON item_links (item_id, url);
CREATE INDEX item_links_added_by ON item_links (added_by);
