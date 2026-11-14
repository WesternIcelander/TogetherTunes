-- Per-user preferences (docs/ui.md#existing-features-in-the-new-ui, docs/api.md: GET/PATCH /api/me/settings):
-- doubleClickAlone, webAudio, debug. One row per user who changed something; a user without a
-- row, and any value missing or not understood, reads as the default. settings_json holds only
-- the keys the user set.
CREATE TABLE user_settings (
    user_id       TEXT   NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    settings_json TEXT   NOT NULL,
    updated_at    BIGINT NOT NULL
);
