-- RTMP stream keys are stored in plain text (Siggi, 2026-11-10: RTMP sends the key in plain text
-- anyway, and the owner can reveal and copy it). SQL can't decrypt the old AES-GCM values, so the
-- server converts rtmp_key_enc into rtmp_key in Java right after the migrations run
-- (output/LegacyRtmpKeys) and sets rtmp_key_enc to NULL. rtmp_key_enc is unused from now on.
ALTER TABLE stream_outputs ADD COLUMN rtmp_key TEXT NULL;
