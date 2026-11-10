-- Upload pre-check by content ID (docs/library-and-ingest.md#content-id): the chunked SHA-256 of an
-- upload item's file ("sha256c8m:<size>:<hex>"), which browsers can compute with crypto.subtle
-- piece by piece. Set when an upload completes; a background job backfills it from the kept
-- original for older upload items. NULL for YouTube items and for purged uploads without a file.
ALTER TABLE items ADD COLUMN content_id TEXT NULL;
CREATE INDEX items_content_id ON items (content_id);
