-- Letterbox bars trimmed off covers (docs/library-and-ingest.md#cards, "Letterbox bars"): every
-- cover made from now on is trimmed when it is made, so new rows start checked (1). Existing items
-- start at 0: the one-time background pass (ingest/CoverBarsPass) checks each ready item's cover,
-- gives a cover with bars a cropped one (card-only re-encode) and sets 1.
ALTER TABLE items ADD COLUMN cover_bars_checked INTEGER NOT NULL DEFAULT 1 CHECK (cover_bars_checked IN (0, 1));
UPDATE items SET cover_bars_checked = 0;
