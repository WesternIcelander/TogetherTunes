-- Autoplay from playlists (docs/ui.md#autoplay-from-playlists): per room, the playlists each person
-- selected for autoplay. user_id is the playlist's owner (people only select their own playlists);
-- it is stored so a person's selections can be listed and pruned without a join. A row goes with its
-- room, its playlist or its user, and when its user loses the right to select in the room (the
-- room's autoplay.selectors setting, a role change, leaving or being removed).
CREATE TABLE room_autoplay_selections (
    room_id     TEXT   NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    playlist_id TEXT   NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
    user_id     TEXT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    selected_at BIGINT NOT NULL,
    PRIMARY KEY (room_id, playlist_id)
);
CREATE INDEX room_autoplay_selections_room_id_user_id ON room_autoplay_selections (room_id, user_id);
CREATE INDEX room_autoplay_selections_playlist_id ON room_autoplay_selections (playlist_id);
CREATE INDEX room_autoplay_selections_user_id ON room_autoplay_selections (user_id);
