-- Song details (docs/library-and-ingest.md#song-details): the usual music tags besides title,
-- artist and album, named after TrackSplitter's JSON. All optional; text is cleaned like upload
-- metadata. artists_json: a JSON array of the track's artists, only when there is more than one
-- (artist stays the one string lists and cards show). track/disc numbers and totals are whole
-- numbers >= 1; isrc is 12 upper-case letters and digits; release_date is YYYY, YYYY-MM or
-- YYYY-MM-DD; barcode 8 to 14 digits. Not part of media_versions (details aren't versioned).
ALTER TABLE items ADD COLUMN artists_json TEXT NULL;
ALTER TABLE items ADD COLUMN album_artist TEXT NULL;
ALTER TABLE items ADD COLUMN track_no INTEGER NULL CHECK (track_no >= 1);
ALTER TABLE items ADD COLUMN track_total INTEGER NULL CHECK (track_total >= 1);
ALTER TABLE items ADD COLUMN disc_no INTEGER NULL CHECK (disc_no >= 1);
ALTER TABLE items ADD COLUMN disc_total INTEGER NULL CHECK (disc_total >= 1);
ALTER TABLE items ADD COLUMN isrc TEXT NULL;
ALTER TABLE items ADD COLUMN release_date TEXT NULL;
ALTER TABLE items ADD COLUMN genre TEXT NULL;
ALTER TABLE items ADD COLUMN copyright TEXT NULL;
ALTER TABLE items ADD COLUMN label TEXT NULL;
ALTER TABLE items ADD COLUMN barcode TEXT NULL;
