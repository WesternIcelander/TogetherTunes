-- Movies and shows skip the music loudness normalisation (docs/media-types.md#loudness): it is
-- applied in the encode, so an item records whether its current files were encoded with it
-- (1: loudnorm to -14 LUFS, or would have been but the source measured silence; 0: encoded without
-- it because the item is a movie or show). A type change into or out of movie/show while this
-- doesn't match queues a system re-encode of the audio (docs/library-and-ingest.md#loudness-and-media-types).
-- Every existing encode was normalised. Media versions record it too, so an undo restores it.
ALTER TABLE items ADD COLUMN loudness_normalized INTEGER NOT NULL DEFAULT 1;
ALTER TABLE media_versions ADD COLUMN loudness_normalized INTEGER NOT NULL DEFAULT 1;
