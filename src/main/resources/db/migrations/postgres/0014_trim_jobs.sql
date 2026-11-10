-- Video trim (docs/library-and-ingest.md#trim): a trim job carries the edit settings it applies
-- ({"videoStartMs": …, "videoEndMs": …}, JSON); NULL for every other kind of job.
ALTER TABLE ingest_jobs ADD COLUMN edit_json TEXT NULL;
