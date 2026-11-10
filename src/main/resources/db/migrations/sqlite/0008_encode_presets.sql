-- x264 presets (docs/library-and-ingest.md#presets): an item is encoded with the quick preset
-- when a room wants it now, and gets a quality re-encode once it is in a library.
-- items.encode_preset: the x264 preset its current files were made with (NULL: made before
-- presets were recorded, i.e. veryfast). items.encode_quality: 1 when those files are the
-- quality encode (made with the quality preset of the time), so it never gets another one.
-- ingest_jobs.tier: the tier asked for when the job was queued ('quick' | 'quality'; NULL: decided
-- when the job starts).
ALTER TABLE items ADD COLUMN encode_preset TEXT NULL;
ALTER TABLE items ADD COLUMN encode_quality INTEGER NOT NULL DEFAULT 0 CHECK (encode_quality IN (0, 1));
ALTER TABLE ingest_jobs ADD COLUMN tier TEXT NULL;
