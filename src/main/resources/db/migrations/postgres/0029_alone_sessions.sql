-- Alone state (docs/ui.md#alone-and-in-rooms, docs/rooms.md#alone): each user's own play session
-- is a private pseudo-room run by the room engine, marked by rooms.alone_user_id (at most one per
-- user; only that user can open it; never listed). alone_sessions records where the user is:
-- room_id is the room they joined (NULL: alone), resume_playing whether the alone room was playing
-- when they joined one (restore plays it again), and, for a room deleted while they were in it,
-- deleted_room_name and fork_json (what a fork copies: the room's current item, position, play
-- state and queue at the moment it was deleted).
ALTER TABLE rooms ADD COLUMN alone_user_id TEXT NULL REFERENCES users (id);
CREATE UNIQUE INDEX rooms_alone_user_id ON rooms (alone_user_id);
CREATE TABLE alone_sessions (
    user_id           TEXT    NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    room_id           TEXT    NULL REFERENCES rooms (id) ON DELETE SET NULL,
    resume_playing    INTEGER NOT NULL DEFAULT 0 CHECK (resume_playing IN (0, 1)),
    deleted_room_name TEXT    NULL,
    fork_json         TEXT    NULL,
    updated_at        BIGINT  NOT NULL
);
CREATE INDEX alone_sessions_room_id ON alone_sessions (room_id);
-- A user's own play history (GET /api/me/history): one row per entry that started playing in their
-- alone room (room_id and room_name NULL) or in a room while they were in it (room_name kept when
-- the room is deleted).
CREATE TABLE play_history (
    id        TEXT   NOT NULL PRIMARY KEY,
    user_id   TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    item_id   TEXT   NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    room_id   TEXT   NULL REFERENCES rooms (id) ON DELETE SET NULL,
    room_name TEXT   NULL,
    played_at BIGINT NOT NULL
);
CREATE INDEX play_history_user_id_played_at ON play_history (user_id, played_at);
CREATE INDEX play_history_item_id ON play_history (item_id);
CREATE INDEX play_history_room_id ON play_history (room_id);
