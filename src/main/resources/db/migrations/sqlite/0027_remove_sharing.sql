-- Item sharing is gone (docs/ui.md#existing-features-in-the-new-ui): people send an item's link
-- instead. Entries a share made stay as ordinary library entries (via 'manual', no sender), and
-- the shares (the senders' history and the recipients' notifications) are dropped.
UPDATE library_entries SET via = 'manual' WHERE via = 'share';
ALTER TABLE library_entries DROP COLUMN shared_by_user_id;
DROP TABLE shares;
-- guests.download is gone too: a guest or anonymous visitor only gets the media of what a room
-- they are in is playing or plays next. A stored value would be ignored anyway; drop it.
DELETE FROM settings WHERE key = 'guests.download';
