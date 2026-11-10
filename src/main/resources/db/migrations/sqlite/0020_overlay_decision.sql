-- The QR overlay's permission decision per queue entry (docs/overlay.md#permissions): recorded once,
-- when the program output first plans the entry's overlay, so a later change of the owner's roles or
-- of the server's or room's overlay switch takes effect from the next entry. 'off': no overlay;
-- 'cards': on cards only (music videos play plain); 'video': on cards and re-encoded music videos.
-- NULL: not decided yet.
ALTER TABLE queue_entries ADD COLUMN overlay TEXT NULL;
