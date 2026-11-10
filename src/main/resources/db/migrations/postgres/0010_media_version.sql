-- Versioned media files (docs/library-and-ingest.md#storage-layout): every encode publishes into
-- its own directory media/<itemId>/v<N>/, and items.media_version names the current one. 0 is the
-- flat layout of items published before versions existed (the files directly in media/<itemId>/).
-- Versions only grow (a purge keeps the number), so a versioned URL never names other bytes.
ALTER TABLE items ADD COLUMN media_version INTEGER NOT NULL DEFAULT 0;
