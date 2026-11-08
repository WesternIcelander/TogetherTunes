-- An admin-set (temporary) password: the user is asked to choose a new one after logging in
-- (docs/auth.md#local-passwords). Changing one's own password clears the flag.
ALTER TABLE password_credentials
    ADD COLUMN must_change INTEGER NOT NULL DEFAULT 0 CHECK (must_change IN (0, 1));
