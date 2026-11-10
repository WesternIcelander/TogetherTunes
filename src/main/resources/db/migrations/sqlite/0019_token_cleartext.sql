-- Private rooms' stream tokens over plain HTTP (docs/program-output.md#plain-http): when a valid
-- token of a private room's .ts/HLS/MP3 output first arrives over plain HTTP, the time is recorded
-- so the outputs panel can warn that the URL has travelled unencrypted. A new token (Rotate, or the
-- room becoming private again) clears it. Epoch ms; NULL: never seen over plain HTTP.
ALTER TABLE stream_outputs ADD COLUMN token_cleartext_at BIGINT NULL;
