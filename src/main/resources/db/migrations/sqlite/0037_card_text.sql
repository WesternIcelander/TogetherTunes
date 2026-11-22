-- Card text (docs/library-and-ingest.md#cards): which text the item card draws, as
-- CardRenderer.textKey over the title, artist and album (a SHA-256 of the cleaned text). An item
-- whose stored key differs from the one its current text gives (an edit while a job held the item,
-- an undo back to a card drawn with older text) gets a card-only re-encode. NULL: made before the
-- text was recorded (taken as matching, except by a re-encode queued for an edit). Part of what a
-- media version was made with, like card_fonts, so an undo restores it.
ALTER TABLE items ADD COLUMN card_text TEXT NULL;
ALTER TABLE media_versions ADD COLUMN card_text TEXT NULL;
