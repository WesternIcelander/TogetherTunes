-- One history per room (docs/rooms.md#history): the play history (queue_entries that started
-- playing) replaces the request history. Each entry now records how it was added (via: library,
-- upload, youtube, playlist or history; NULL for entries made before this migration that weren't
-- requests) and the adder's display name at that time (added_by_name; NULL: show the current
-- name). Requests carry both over from room_requests, which is dropped.
ALTER TABLE queue_entries ADD COLUMN via TEXT NULL;
ALTER TABLE queue_entries ADD COLUMN added_by_name TEXT NULL;
UPDATE queue_entries
SET via = (SELECT r.via FROM room_requests r WHERE r.entry_id = queue_entries.id ORDER BY r.at LIMIT 1),
    added_by_name = (SELECT r.by_name FROM room_requests r WHERE r.entry_id = queue_entries.id ORDER BY r.at LIMIT 1)
WHERE EXISTS (SELECT 1 FROM room_requests r WHERE r.entry_id = queue_entries.id);
DROP TABLE room_requests;
CREATE INDEX queue_entries_room_id_played_at ON queue_entries (room_id, played_at);
