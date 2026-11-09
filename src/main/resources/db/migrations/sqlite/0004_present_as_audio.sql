-- items.present_as_audio (docs/library-and-ingest.md#youtube-caching): a video item that the
-- packager and the web player present like an audio item, with its card instead of its video
-- (e.g. YouTube "Topic" uploads, which are a still image). Set by the importer at import time or
-- later by anyone who may edit the item's metadata.
ALTER TABLE items ADD COLUMN present_as_audio INTEGER NOT NULL DEFAULT 0 CHECK (present_as_audio IN (0, 1));
