-- Personal data removed from audio uploads (docs/library-and-ingest.md#looking-details-up-online,
-- "Removing personal data from uploads"): whether the kept original of an audio upload (.m4a,
-- .mp3, .flac) was cleaned with TrackSplitter. 1 cleaned; 0 the cleaning failed (the original is
-- never served); NULL not cleaned yet (made before this, or TrackSplitter wasn't installed): the
-- one-time background pass cleans those; until then an .m4a original isn't served.
ALTER TABLE items ADD COLUMN original_cleaned INTEGER NULL CHECK (original_cleaned IN (0, 1));
