-- Room bans and timeouts (docs/rooms.md#bans-and-timeouts, docs/ui.md#moderation-menu-right-click-a-person-in-the-room-tab).
-- One moderation action on a person (the subject: 'u:<userId>' or 'g:<guestId>') writes one row per
-- target it covers: the account ('user', target = user id) or the guest ('guest', target = guest id),
-- and the IP key the person came from ('ip', target = SHA-256 of the normalised IP key, as
-- guests.key_hash; never the address). expires_at NULL is a ban until lifted; otherwise a timeout
-- until then. Lifting removes every row of the subject. target_kind leaves room for other targets
-- later (e.g. a home server, docs/future-ideas.md#home-server-bans).
-- Replaces room_members.role = 'banned' and room_guest_bans, which are moved here.
CREATE TABLE room_bans (
    room_id            TEXT   NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    subject            TEXT   NOT NULL,
    target_kind        TEXT   NOT NULL,
    target             TEXT   NOT NULL,
    expires_at         BIGINT NULL,
    created_by_user_id TEXT   NULL REFERENCES users (id) ON DELETE SET NULL,
    created_at         BIGINT NOT NULL,
    PRIMARY KEY (room_id, subject, target_kind, target)
);
CREATE INDEX room_bans_room_id_target ON room_bans (room_id, target_kind, target);
CREATE INDEX room_bans_created_by_user_id ON room_bans (created_by_user_id);

INSERT INTO room_bans (room_id, subject, target_kind, target, expires_at, created_by_user_id, created_at)
SELECT room_id, 'u:' || user_id, 'user', user_id, NULL, NULL, joined_at FROM room_members WHERE role = 'banned';
DELETE FROM room_members WHERE role = 'banned';

INSERT INTO room_bans (room_id, subject, target_kind, target, expires_at, created_by_user_id, created_at)
SELECT room_id, 'g:' || guest_id, 'guest', guest_id, NULL, NULL, created_at FROM room_guest_bans;
-- An IP-mode guest is its IP key: its key_hash is the hash of that key.
INSERT INTO room_bans (room_id, subject, target_kind, target, expires_at, created_by_user_id, created_at)
SELECT b.room_id, 'g:' || b.guest_id, 'ip', g.key_hash, NULL, NULL, b.created_at
FROM room_guest_bans b JOIN guests g ON g.id = b.guest_id WHERE g.kind = 'ip';
DROP TABLE room_guest_bans;
