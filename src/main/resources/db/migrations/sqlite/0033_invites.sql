-- Invites (docs/auth.md#invites): single-use codes for registration in registration.mode 'invite'.
-- Only the SHA-256 of the code is stored (like sessions); the code itself is shown once, at creation.
-- An invite is pending while used_by_user_id, used_at and revoked_at are NULL and expires_at is in
-- the future. created_by_user_id NULL: its creator's account is gone.
CREATE TABLE invites (
    id                 TEXT   NOT NULL PRIMARY KEY,
    code_hash          TEXT   NOT NULL UNIQUE,
    created_by_user_id TEXT   NULL REFERENCES users (id) ON DELETE SET NULL,
    created_at         BIGINT NOT NULL,
    expires_at         BIGINT NOT NULL,
    note               TEXT   NULL,
    used_by_user_id    TEXT   NULL REFERENCES users (id) ON DELETE SET NULL,
    used_at            BIGINT NULL,
    revoked_at         BIGINT NULL
);
CREATE INDEX invites_created_by_user_id ON invites (created_by_user_id);
CREATE INDEX invites_used_by_user_id ON invites (used_by_user_id);
