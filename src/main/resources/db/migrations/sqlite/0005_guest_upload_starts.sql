-- The guest upload quota (guests.uploadQuota, docs/auth.md#guest-policy-server-settings): one row
-- per upload a guest started, counted per guest and per IP over the last hour, whichever limit is
-- hit first. ip_key_hash is the SHA-256 of the normalised IP key (the IPv4 address or the IPv6
-- /64), like guests.key_hash, so no IP address is stored here. The rows outlive the uploads they
-- count (a refused or abandoned upload still counts); the upload sweep deletes rows older than an
-- hour.
CREATE TABLE guest_upload_starts (
    id          TEXT   NOT NULL PRIMARY KEY,
    guest_id    TEXT   NOT NULL REFERENCES guests (id) ON DELETE CASCADE,
    ip_key_hash TEXT   NULL,
    created_at  BIGINT NOT NULL
);
CREATE INDEX guest_upload_starts_guest ON guest_upload_starts (guest_id, created_at);
CREATE INDEX guest_upload_starts_ip ON guest_upload_starts (ip_key_hash, created_at);
CREATE INDEX guest_upload_starts_created_at ON guest_upload_starts (created_at);
