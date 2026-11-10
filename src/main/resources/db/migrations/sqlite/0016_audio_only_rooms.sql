-- Audio-only rooms (docs/rooms.md#audio-only-rooms). The room setting audioOnly lives in
-- settings_json; current_audio_only records whether the current entry plays audio-only: an entry
-- keeps the mode it started with until it ends, so a change of the setting applies from the next
-- item, also across a restart.
ALTER TABLE rooms ADD COLUMN current_audio_only INTEGER NOT NULL DEFAULT 0 CHECK (current_audio_only IN (0, 1));
