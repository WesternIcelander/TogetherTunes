-- Media types (docs/media-types.md): every item has exactly one media type, which says what it is
-- (items.kind still says what files it has). 'audio': music without a picture (audio items, and
-- video items played like audio items, with their card: this replaces present_as_audio);
-- 'uncategorized': a video nobody has classified yet (the default for every video);
-- 'music_video', 'movie', 'show'. A show episode has a show name (required), a season number
-- (optional) and an episode number (required); all three are NULL for every other type.
-- last_viewed_at: the item's most recent view (any playback); pinned_at / pinned_by_user_id: a
-- pinned item is never deleted automatically.
ALTER TABLE items ADD COLUMN media_type TEXT NOT NULL DEFAULT 'uncategorized'
    CHECK (media_type IN ('audio', 'uncategorized', 'music_video', 'movie', 'show'));
ALTER TABLE items ADD COLUMN show_name TEXT NULL;
ALTER TABLE items ADD COLUMN season_number INTEGER NULL CHECK (season_number >= 0);
ALTER TABLE items ADD COLUMN episode_number INTEGER NULL CHECK (episode_number >= 0);
ALTER TABLE items ADD COLUMN last_viewed_at BIGINT NULL;
ALTER TABLE items ADD COLUMN pinned_at BIGINT NULL;
ALTER TABLE items ADD COLUMN pinned_by_user_id TEXT NULL REFERENCES users (id) ON DELETE SET NULL;
-- Existing items: audio items and video items presented as audio become 'audio', every other
-- video 'uncategorized' (the default).
UPDATE items SET media_type = 'audio' WHERE kind = 'audio' OR present_as_audio = 1;
ALTER TABLE items DROP COLUMN present_as_audio;
-- What a media version was made with includes the media type, so an undo can bring back a video
-- that was published as 'audio' (a still image) and the other way round. NULL only in rows that
-- can't say; existing rows take the item's type, or 'audio' for an audio version.
ALTER TABLE media_versions ADD COLUMN media_type TEXT NULL;
UPDATE media_versions SET media_type = CASE WHEN kind = 'audio' THEN 'audio'
    ELSE (SELECT i.media_type FROM items i WHERE i.id = media_versions.item_id) END;
