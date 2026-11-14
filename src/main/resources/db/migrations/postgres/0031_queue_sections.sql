-- Queue sections (docs/rooms.md#autoplay): 'manual' entries were added by someone (requests, playlists
-- added to the queue, ...); 'autoplay' entries were added by autoplay from the playlists selected in the
-- room, and their added_by_user_id is the person whose playlist it came from. Existing entries are
-- manual.
ALTER TABLE queue_entries ADD COLUMN section TEXT NOT NULL DEFAULT 'manual';
