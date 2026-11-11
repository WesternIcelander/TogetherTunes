-- Card fonts (docs/library-and-ingest.md#card-fonts): which fonts drew the item card's text, as
-- CardFonts.key over the title, artist and album (the face chosen for each run of text). An item
-- whose stored key differs from the one the current fonts give (a new preferred card font, a newly
-- downloaded script font for characters that had none) gets a card-only re-encode. NULL: made before
-- fonts were recorded, so it is made again once. Part of what a media version was made with, like
-- card_version, so an undo restores it.
ALTER TABLE items ADD COLUMN card_fonts TEXT NULL;
ALTER TABLE media_versions ADD COLUMN card_fonts TEXT NULL;
