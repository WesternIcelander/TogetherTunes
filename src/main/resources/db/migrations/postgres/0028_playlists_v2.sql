-- Playlists for the new UI (docs/libraries.md#playlists, docs/ui.md#library-rules).
-- builtin: NULL for an ordinary playlist; 'likes' for the user's Liked Songs, a built-in playlist
-- made on demand (at most one per user), which can't be renamed or deleted.
-- position: the user's own order of their playlists (smallest first; the built-in one is always
-- listed first whatever its position). Existing playlists keep the order they were listed in
-- (most recently changed first).
ALTER TABLE playlists ADD COLUMN builtin TEXT NULL CHECK (builtin IN ('likes'));
ALTER TABLE playlists ADD COLUMN position BIGINT NOT NULL DEFAULT 0;
UPDATE playlists SET position = (SELECT COUNT(*) FROM playlists p2 WHERE p2.owner_id = playlists.owner_id
    AND (p2.updated_at > playlists.updated_at OR (p2.updated_at = playlists.updated_at AND p2.id >= playlists.id)));
CREATE UNIQUE INDEX playlists_owner_id_builtin ON playlists (owner_id, builtin);
-- An item in a playlist is always in the library of the playlist's owner: add the missing library
-- entries (via 'manual', added when the item first went into one of that user's playlists).
INSERT INTO library_entries (user_id, item_id, added_at, via)
SELECT p.owner_id, pe.item_id, MIN(pe.added_at), 'manual'
FROM playlist_entries pe JOIN playlists p ON p.id = pe.playlist_id
WHERE NOT EXISTS (SELECT 1 FROM library_entries le WHERE le.user_id = p.owner_id AND le.item_id = pe.item_id)
GROUP BY p.owner_id, pe.item_id;
