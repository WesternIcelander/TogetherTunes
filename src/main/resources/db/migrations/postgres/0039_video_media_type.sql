-- Media type 'video' (docs/media-types.md): a video that is neither a music video nor a movie or
-- show (a clip); it plays like a music video. The CHECK of items.media_type (0023, named by
-- PostgreSQL's default items_media_type_check) gains it.
ALTER TABLE items DROP CONSTRAINT items_media_type_check;
ALTER TABLE items ADD CONSTRAINT items_media_type_check
    CHECK (media_type IN ('audio', 'uncategorized', 'music_video', 'movie', 'show', 'video'));
