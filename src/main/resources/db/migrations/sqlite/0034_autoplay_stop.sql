-- Autoplay stops when nobody is there (docs/ui.md#autoplay-from-playlists, docs/rooms.md#autoplay):
-- set when the room's autoplay stopped topping up the queue, cleared when it resumes. reason is
-- 'nobody' (no web users, and with autoplay.pullListenersCount no stream listeners, for the
-- limit); minutes is the limit that applied. All NULL while autoplay runs.
ALTER TABLE rooms ADD COLUMN autoplay_stopped_at BIGINT NULL;
ALTER TABLE rooms ADD COLUMN autoplay_stopped_reason TEXT NULL;
ALTER TABLE rooms ADD COLUMN autoplay_stopped_minutes INTEGER NULL;
