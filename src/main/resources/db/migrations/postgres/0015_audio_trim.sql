-- Audio trim (docs/library-and-ingest.md#audio-trim). audio_only_ms: how long a video item's
-- audio-only file (audio-only.m4a, made from its audio trim and played only in audio-only rooms)
-- is; NULL when it has none (then its audio-only file is audio.m4a, as long as duration_ms).
-- media_versions also keeps original_web / original_gain_db per version: a trimmed audio upload
-- plays its audio.m4a instead of its original, and an undo puts back whether the original is played.
ALTER TABLE items ADD COLUMN audio_only_ms BIGINT NULL;
ALTER TABLE media_versions ADD COLUMN audio_only_ms BIGINT NULL;
ALTER TABLE media_versions ADD COLUMN original_web INTEGER NOT NULL DEFAULT 0 CHECK (original_web IN (0, 1));
ALTER TABLE media_versions ADD COLUMN original_gain_db DOUBLE PRECISION NULL;
UPDATE media_versions SET original_web = (SELECT i.original_web FROM items i WHERE i.id = media_versions.item_id),
    original_gain_db = (SELECT i.original_gain_db FROM items i WHERE i.id = media_versions.item_id);
