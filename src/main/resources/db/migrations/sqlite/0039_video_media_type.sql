-- Media type 'video' (docs/media-types.md): a video that is neither a music video nor a movie or
-- show (a clip); it plays like a music video. SQLite can't change a column's CHECK in place, so
-- the column is rebuilt: a new one with the wider CHECK, the values copied, the old one dropped
-- and the new one renamed (media_type has no index, trigger or view that refers to it).
ALTER TABLE items ADD COLUMN media_type_new TEXT NOT NULL DEFAULT 'uncategorized'
    CHECK (media_type_new IN ('audio', 'uncategorized', 'music_video', 'movie', 'show', 'video'));
UPDATE items SET media_type_new = media_type;
ALTER TABLE items DROP COLUMN media_type;
ALTER TABLE items RENAME COLUMN media_type_new TO media_type;
