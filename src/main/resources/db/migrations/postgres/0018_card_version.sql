-- Skip-frame cards (docs/library-and-ingest.md#cards): the card format an item's card.mp4 was
-- made in (CardEncoder.CARD_VERSION: 1 = the whole GOP encoded by x264, 2 = an x264 IDR plus
-- written P_Skip frames). Items whose card is older get a card-only re-encode at startup; the
-- column is part of what a media version was made with, so an undo restores it.
ALTER TABLE items ADD COLUMN card_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE media_versions ADD COLUMN card_version INTEGER NOT NULL DEFAULT 1;
