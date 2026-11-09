package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.UploadMetadata;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rooms as records ({@code docs/rooms.md}, {@code docs/api.md#rooms}): creating, listing,
 * reading, changing and deleting rooms, their slugs and visibility, and the room roles
 * ({@code room_members}): {@code owner} (exactly one, transferable), {@code moderator},
 * {@code member}, {@code banned}. The live room state (queue, timeline, presence) is not here.
 *
 * <h2>Who sees a room</h2>
 * <ul>
 *   <li>{@code public}: listed in {@link #list} for everyone; readable by everyone.</li>
 *   <li>{@code unlisted}: readable by everyone who has the link (id or slug); listed only for its
 *       owner, moderators and members.</li>
 *   <li>{@code private}: invited users only (owner, moderators, members), and users with
 *       {@code rooms.moderateAny}. No guests. Everyone else gets {@code 404 room_not_found}, so
 *       the room's existence doesn't leak.</li>
 *   <li>A user {@code banned} from a room gets {@code 403 banned_from_room} for a public or
 *       unlisted room ({@code 404} for a private one) and doesn't see it listed.</li>
 * </ul>
 *
 * <h2>Effective role</h2>
 * The owner is {@code rooms.owner_id} (mirrored by a {@code room_members} row with role
 * {@code owner}). Other users have the role of their {@code room_members} row, if any. A user
 * holding {@code rooms.moderateAny} is a {@code moderator} in every room they don't own, even
 * if the room banned them. Guests and anonymous visitors have no room role (guest bans are
 * {@code room_guest_bans}, handled with the guest rules).
 *
 * <h2>Who changes what</h2>
 * <ul>
 *   <li>Changing a room ({@link #update}): the owner only.</li>
 *   <li>Deleting a room: the owner, or {@code rooms.moderateAny} (to remove abusive rooms).</li>
 *   <li>Listing members: the owner and moderators.</li>
 *   <li>Setting a role ({@link #setRole}): the owner sets {@code moderator}, {@code member} or
 *       {@code banned} for anyone but themselves, and {@code owner} transfers the room (the old
 *       owner becomes a moderator). Moderators set {@code member} or {@code banned} for users
 *       who are neither the owner nor a moderator. Nobody changes their own role.</li>
 *   <li>Removing a member row ({@link #removeMember}, which also lifts a ban): the owner for
 *       anyone but themselves, moderators for members and banned users, and every user for
 *       themselves (leaving) unless they are the owner or banned.</li>
 * </ul>
 *
 * <h2>Slugs</h2>
 * 3–{@value #SLUG_MAX} characters of {@code a-z}, {@code 0-9} and {@code -}, starting and
 * ending with a letter or digit, never shaped like a UUID (so {@code /api/rooms/{idOrSlug}} is
 * unambiguous). Input is trimmed and lower-cased. Without an explicit slug, one is derived from
 * the name (accents removed, other characters become {@code -}), with {@code -2}, {@code -3}, …
 * appended while taken. Changing the slug frees the old one at once.
 *
 * <p>Changes are audited ({@code room.*}); the client IP goes only to {@code audit_log.ip}.
 */
public final class Rooms {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest room name, in code points (after cleaning). */
    public static final int NAME_MAX = 100;
    public static final int SLUG_MIN = 3;
    public static final int SLUG_MAX = 40;
    /** Most rooms one user can own. */
    public static final int MAX_OWNED = 100;

    public static final String AUDIT_CREATED = "room.created";
    public static final String AUDIT_UPDATED = "room.updated";
    public static final String AUDIT_DELETED = "room.deleted";
    public static final String AUDIT_ROLE_SET = "room.member_role";
    public static final String AUDIT_MEMBER_REMOVED = "room.member_removed";
    public static final String AUDIT_OWNER_TRANSFERRED = "room.owner_transferred";

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{" + (SLUG_MIN - 2) + "," + (SLUG_MAX - 2)
            + "}[a-z0-9]");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Length of the name-derived part of a generated slug, leaving room for a suffix. */
    private static final int GENERATED_BASE_MAX = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Room visibility ({@code rooms.visibility}). */
    public enum Visibility {
        PUBLIC, UNLISTED, PRIVATE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The visibility with this wire name, or {@code null}. */
        public static Visibility parse(String s) {
            for (Visibility v : values()) {
                if (v.wire().equals(s)) {
                    return v;
                }
            }
            return null;
        }
    }

    /** Who controls the queue ({@code rooms.control_mode}, {@code docs/rooms.md#control-modes}). */
    public enum ControlMode {
        OWNER, VOTE, OPEN;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The mode with this wire name, or {@code null}. */
        public static ControlMode parse(String s) {
            for (ControlMode m : values()) {
                if (m.wire().equals(s)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** A role in a room ({@code room_members.role}), highest first. */
    public enum Role {
        OWNER, MODERATOR, MEMBER, BANNED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The role with this wire name, or {@code null}. */
        public static Role parse(String s) {
            for (Role r : values()) {
                if (r.wire().equals(s)) {
                    return r;
                }
            }
            return null;
        }

        /** Owner or moderator. */
        public boolean moderates() {
            return this == OWNER || this == MODERATOR;
        }
    }

    /**
     * A room as clients see it. {@code you}: the caller's effective role ({@code null} for none)
     * and what they may do with the room itself.
     */
    public record RoomView(String id, String slug, String name, ActorRef owner, String visibility, String controlMode,
                           long createdAt, You you) {
    }

    /**
     * The caller's relation to a room: {@code role} is {@code owner}, {@code moderator},
     * {@code member}, {@code banned} or {@code null}; {@code canEdit}: may change the room;
     * {@code canDelete}: may delete it; {@code canModerate}: owner or moderator.
     */
    public record You(String role, boolean canEdit, boolean canDelete, boolean canModerate) {
    }

    /** One page of rooms; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<RoomView> items, String nextCursor) {
    }

    /** One room member (a user; guests are never members). */
    public record Member(ActorRef user, String role, long joinedAt) {
    }

    /** One page of members; {@code nextCursor} is {@code null} on the last page. */
    public record MemberPage(List<Member> items, String nextCursor) {
    }

    /** A change to a room. {@code null} fields stay as they are. */
    public record Edit(String name, String slug, Visibility visibility, ControlMode controlMode) {
    }

    /** {@link #setRole}'s result: the member row, and whether it was created. */
    public record RoleSet(Member member, boolean created) {
    }

    /** A {@code rooms} row with the owner's display name. */
    public record Room(String id, String slug, String name, String ownerId, String ownerName, Visibility visibility,
                       ControlMode controlMode, long createdAt) {
    }

    /** A room and the caller's effective role in it ({@code null}: none). */
    public record Access(Room room, Role role) {
    }

    private final Database db;
    private final Permissions permissions;
    private final Clock clock;

    public Rooms(Database db, Permissions permissions, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ validation

    /**
     * A cleaned room name (like upload metadata: control characters and runs of whitespace become
     * one space, trimmed).
     *
     * @throws HttpError {@code 400 invalid_name} (blank), {@code 400 name_too_long}
     */
    public static String name(String raw) {
        String name = raw == null ? null : UploadMetadata.clean(raw);
        if (name == null) {
            throw new HttpError(400, "invalid_name", "The name can't be empty");
        }
        if (raw.codePointCount(0, raw.length()) > UploadMetadata.MAX_CHARS
                || name.codePointCount(0, name.length()) > NAME_MAX) {
            throw new HttpError(400, "name_too_long", "At most " + NAME_MAX + " characters");
        }
        return name;
    }

    /**
     * A slug from user input: trimmed, lower-cased, then checked.
     *
     * @throws HttpError {@code 400 invalid_slug}
     */
    public static String slug(String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(s).matches() || UUID_SHAPE.matcher(s).matches()) {
            throw new HttpError(400, "invalid_slug", SLUG_MIN + "-" + SLUG_MAX
                    + " characters of a-z, 0-9 and -, starting and ending with a letter or digit");
        }
        return s;
    }

    /** The name-derived base of a generated slug (always a valid slug). */
    static String slugBase(String name) {
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < folded.length() && b.length() < GENERATED_BASE_MAX; i++) {
            char ch = folded.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (dash && !b.isEmpty()) {
                    b.append('-');
                }
                dash = false;
                b.append(ch);
            } else {
                dash = true;
            }
        }
        String s = b.toString();
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() < SLUG_MIN || UUID_SHAPE.matcher(s).matches()) {
            return "room";
        }
        return s;
    }

    private static HttpError roomNotFound() {
        return new HttpError(404, "room_not_found", "No such room");
    }

    // ------------------------------------------------------------------ reading

    private static final String ROOM = "SELECT r.id, r.slug, r.name, r.owner_id, u.display_name, r.visibility,"
            + " r.control_mode, r.created_at FROM rooms r JOIN users u ON u.id = r.owner_id";

    private static Room room(ResultSet rs) throws SQLException {
        return new Room(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Objects.requireNonNull(Visibility.parse(rs.getString(6)), "visibility"),
                Objects.requireNonNull(ControlMode.parse(rs.getString(7)), "control_mode"), rs.getLong(8));
    }

    /** The room with this id or slug, or {@code null}. Locks the row on PostgreSQL if {@code lock}. */
    private Room find(Connection c, String idOrSlug, boolean lock) throws SQLException {
        if (idOrSlug == null || idOrSlug.isEmpty()) {
            return null;
        }
        boolean byId = UUID_SHAPE.matcher(idOrSlug).matches();
        // SQLite has no FOR UPDATE; its writes are serialised on one connection anyway.
        String sql = ROOM + (byId ? " WHERE r.id = ?" : " WHERE r.slug = ?")
                + (lock && db.dialect() instanceof PostgresDialect ? " FOR UPDATE OF r" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, idOrSlug.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? room(rs) : null;
            }
        }
    }

    /** The stored {@code room_members} role of a user, or {@code null}. */
    private static Role memberRole(Connection c, String roomId, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT role FROM room_members WHERE room_id = ? AND user_id = ?")) {
            ps.setString(1, roomId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Role.parse(rs.getString(1)) : null;
            }
        }
    }

    /** The effective role, given the stored one and whether the caller holds {@code rooms.moderateAny}. */
    private static Role effective(Room room, String userId, Role stored, boolean moderateAny) {
        if (userId == null) {
            return null;
        }
        if (room.ownerId().equals(userId)) {
            return Role.OWNER;
        }
        if (moderateAny) {
            return Role.MODERATOR;
        }
        return stored == Role.OWNER ? Role.MODERATOR : stored; // a stale owner row: never trust it
    }

    private static String userId(Actor actor) {
        return actor instanceof Actor.User u ? u.userId() : null;
    }

    private boolean moderatesAny(Actor actor) throws SQLException {
        return actor instanceof Actor.User && permissions.allowed(actor, Permission.ROOMS_MODERATE_ANY);
    }

    /**
     * Throws unless the caller may see the room: {@code 404 room_not_found} for a private room
     * they aren't invited to, {@code 403 banned_from_room} for a banned user.
     */
    private static void requireVisible(Access a) {
        boolean invited = a.role() != null && a.role() != Role.BANNED;
        if (a.room().visibility() == Visibility.PRIVATE && !invited) {
            throw roomNotFound();
        }
        if (a.role() == Role.BANNED) {
            throw new HttpError(403, "banned_from_room", "You are banned from this room");
        }
    }

    private Access access(Connection c, Actor actor, String idOrSlug, boolean lock, boolean moderateAny)
            throws SQLException {
        Room room = find(c, idOrSlug, lock);
        if (room == null) {
            throw roomNotFound();
        }
        String uid = userId(actor);
        Role stored = uid == null ? null : memberRole(c, room.id(), uid);
        Access a = new Access(room, effective(room, uid, stored, moderateAny));
        requireVisible(a);
        return a;
    }

    /**
     * The room (by id or slug) and the caller's effective role, if the caller may see it.
     *
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room}
     */
    public Access access(Actor actor, String idOrSlug) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        return db.read(c -> access(c, actor, idOrSlug, false, moderateAny));
    }

    /**
     * The id of the room with this id or slug, or {@code null} if there is none. No visibility
     * check: use it only to pick the room's executor, then check access there.
     */
    public String idOf(String idOrSlug) throws SQLException {
        return db.read(c -> {
            Room r = find(c, idOrSlug, false);
            return r == null ? null : r.id();
        });
    }

    /** The {@code rooms} row with this id, or {@code null}. No visibility check. */
    public Room record(String roomId) throws SQLException {
        if (roomId == null || !UUID_SHAPE.matcher(roomId).matches()) {
            return null;
        }
        return db.read(c -> find(c, roomId, false));
    }

    private static You you(Room room, Role role, boolean moderateAny) {
        return new You(role == null ? null : role.wire(), role == Role.OWNER, role == Role.OWNER || moderateAny,
                role != null && role.moderates());
    }

    private static RoomView view(Room room, Role role, boolean moderateAny) {
        return new RoomView(room.id(), room.slug(), room.name(), ActorRef.user(room.ownerId(), room.ownerName()),
                room.visibility().wire(), room.controlMode().wire(), room.createdAt(), you(room, role, moderateAny));
    }

    /** The room as the caller sees it. @throws HttpError as {@link #access(Actor, String)} */
    public RoomView get(Actor actor, String idOrSlug) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny);
            return view(a.room(), a.role(), moderateAny);
        });
    }

    /**
     * Public rooms, plus every room the caller owns, moderates or is a member of (any
     * visibility); never one that banned the caller. Newest first (then by id, descending).
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(Actor actor, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        long cursorAt = 0;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.indexOf('_');
            try {
                cursorAt = Long.parseLong(cursor.substring(0, Math.max(sep, 0)));
            } catch (NumberFormatException e) {
                sep = -1;
            }
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        String sql = "SELECT r.id, r.slug, r.name, r.owner_id, u.display_name, r.visibility, r.control_mode,"
                + " r.created_at, m.role FROM rooms r JOIN users u ON u.id = r.owner_id"
                + " LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = ?"
                + " WHERE (r.owner_id = ? OR m.role IN ('owner', 'moderator', 'member')"
                + " OR (r.visibility = 'public' AND (m.role IS NULL OR m.role <> 'banned')))"
                + (cursorId != null ? " AND (r.created_at < ? OR (r.created_at = ? AND r.id < ?))" : "")
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT " + (limit + 1);
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            List<RoomView> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uid == null ? "" : uid);
                ps.setString(2, uid == null ? "" : uid);
                if (afterId != null) {
                    ps.setLong(3, at);
                    ps.setLong(4, at);
                    ps.setString(5, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Room room = room(rs);
                        Role role = effective(room, uid, Role.parse(rs.getString(9)), moderateAny);
                        items.add(view(room, role, moderateAny));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                RoomView last = items.get(limit - 1);
                next = last.createdAt() + "_" + last.id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    // ------------------------------------------------------------------ creating and changing

    private static boolean slugTaken(Connection c, String slug, String exceptRoomId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM rooms WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && !rs.getString(1).equals(exceptRoomId);
            }
        }
    }

    /** A free slug derived from {@code name}: the base, then base-2, base-3, …, then a random suffix. */
    private static String generateSlug(Connection c, String name) throws SQLException {
        String base = slugBase(name);
        if (!slugTaken(c, base, null)) {
            return base;
        }
        for (int i = 2; i <= 50; i++) {
            String s = base + "-" + i;
            if (!slugTaken(c, s, null)) {
                return s;
            }
        }
        while (true) {
            StringBuilder b = new StringBuilder(base).append('-');
            for (int i = 0; i < 6; i++) {
                b.append(Character.forDigit(RANDOM.nextInt(36), 36));
            }
            if (!slugTaken(c, b.toString(), null)) {
                return b.toString();
            }
        }
    }

    /** Advisory lock id serialising slug choices on PostgreSQL (arbitrary, unique in this app). */
    private static final long SLUG_LOCK = 0x5454_524f_4f4d_5331L;

    /**
     * Serialises transactions that pick a slug, so a derived slug is never chosen twice at once.
     * PostgreSQL: a transaction-scoped advisory lock. SQLite serialises writers anyway. A unique
     * violation is still handled ({@link #isUniqueViolation}) as a last resort.
     */
    private void lockSlugs(Connection c) throws SQLException {
        if (db.dialect() instanceof PostgresDialect) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, SLUG_LOCK);
                ps.executeQuery().close();
            }
        }
    }

    /** Whether {@code e} is a unique-constraint violation (a concurrent insert of the same slug). */
    static boolean isUniqueViolation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState())) {
                    return true;
                }
                String m = sql.getMessage();
                if (m != null && m.contains("UNIQUE constraint failed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates a room owned by {@code owner} (the caller checks {@code rooms.create}).
     *
     * @param name        raw; see {@link #name}
     * @param slug        raw, or {@code null} to derive one from the name; see {@link #slug}
     * @param visibility  {@code null}: {@code unlisted}
     * @param controlMode {@code null}: {@code vote}
     * @throws HttpError {@code 409 slug_taken}, {@code 409 too_many_rooms}, and the validation
     *                   errors
     */
    public RoomView create(Actor.User owner, String name, String slug, Visibility visibility, ControlMode controlMode,
                           String ip) throws SQLException {
        String cleanName = name(name);
        String explicitSlug = slug == null ? null : slug(slug);
        Visibility v = visibility == null ? Visibility.UNLISTED : visibility;
        ControlMode m = controlMode == null ? ControlMode.VOTE : controlMode;
        boolean moderateAny = moderatesAny(owner);
        for (int attempt = 1; ; attempt++) {
            try {
                return db.write(c -> {
                    lockSlugs(c);
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM rooms WHERE owner_id = ?")) {
                        ps.setString(1, owner.userId());
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            if (rs.getInt(1) >= MAX_OWNED) {
                                throw new HttpError(409, "too_many_rooms", "You can own at most " + MAX_OWNED + " rooms");
                            }
                        }
                    }
                    String s;
                    if (explicitSlug != null) {
                        if (slugTaken(c, explicitSlug, null)) {
                            throw slugTakenError();
                        }
                        s = explicitSlug;
                    } else {
                        s = generateSlug(c, cleanName);
                    }
                    String id = UuidV7.newId();
                    long now = EpochMs.now(clock);
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO rooms (id, slug, name, owner_id,"
                            + " created_at, visibility, control_mode, settings_json, playback_state, anchor_server_ms,"
                            + " anchor_position_ms, version) VALUES (?, ?, ?, ?, ?, ?, ?, '{}', 'idle', 0, 0, 0)")) {
                        ps.setString(1, id);
                        ps.setString(2, s);
                        ps.setString(3, cleanName);
                        ps.setString(4, owner.userId());
                        ps.setLong(5, now);
                        ps.setString(6, v.wire());
                        ps.setString(7, m.wire());
                        ps.executeUpdate();
                    }
                    putMember(c, id, owner.userId(), Role.OWNER, now);
                    String ownerName;
                    try (PreparedStatement ps = c.prepareStatement("SELECT display_name FROM users WHERE id = ?")) {
                        ps.setString(1, owner.userId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new HttpError(401, "not_authenticated", "Log in first");
                            }
                            ownerName = rs.getString(1);
                        }
                    }
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("slug", s);
                    details.put("visibility", v.wire());
                    details.put("controlMode", m.wire());
                    AuditLog.write(c, now, owner.userId(), null, ip, AUDIT_CREATED, id, details);
                    Room room = new Room(id, s, cleanName, owner.userId(), ownerName, v, m, now);
                    return view(room, Role.OWNER, moderateAny);
                });
            } catch (SQLException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                if (explicitSlug != null) {
                    throw slugTakenError();
                }
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    private static HttpError slugTakenError() {
        return new HttpError(409, "slug_taken", "Another room already uses this link");
    }

    /**
     * Changes a room's name, slug, visibility or control mode. The owner only.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room} (can't see it),
     *                   {@code 403 forbidden} (not the owner), {@code 409 slug_taken}, and the
     *                   validation errors
     */
    public RoomView update(Actor actor, String idOrSlug, Edit edit, String ip) throws SQLException {
        Objects.requireNonNull(edit);
        String newName = edit.name() == null ? null : name(edit.name());
        String newSlug = edit.slug() == null ? null : slug(edit.slug());
        boolean moderateAny = moderatesAny(actor);
        try {
            return db.write(c -> {
                Access a = access(c, actor, idOrSlug, true, moderateAny);
                if (a.role() != Role.OWNER) {
                    throw HttpError.forbidden("Only the room's owner can change it");
                }
                Room r = a.room();
                Map<String, Object> details = new LinkedHashMap<>();
                String name = r.name();
                String slug = r.slug();
                Visibility visibility = r.visibility();
                ControlMode mode = r.controlMode();
                if (newName != null && !newName.equals(name)) {
                    name = newName;
                    details.put("name", name);
                }
                if (newSlug != null && !newSlug.equals(slug)) {
                    lockSlugs(c);
                    if (slugTaken(c, newSlug, r.id())) {
                        throw slugTakenError();
                    }
                    details.put("oldSlug", slug);
                    slug = newSlug;
                    details.put("slug", slug);
                }
                if (edit.visibility() != null && edit.visibility() != visibility) {
                    visibility = edit.visibility();
                    details.put("visibility", visibility.wire());
                }
                if (edit.controlMode() != null && edit.controlMode() != mode) {
                    mode = edit.controlMode();
                    details.put("controlMode", mode.wire());
                }
                Room updated = new Room(r.id(), slug, name, r.ownerId(), r.ownerName(), visibility, mode, r.createdAt());
                if (!details.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET name = ?, slug = ?, visibility = ?,"
                            + " control_mode = ? WHERE id = ?")) {
                        ps.setString(1, name);
                        ps.setString(2, slug);
                        ps.setString(3, visibility.wire());
                        ps.setString(4, mode.wire());
                        ps.setString(5, r.id());
                        ps.executeUpdate();
                    }
                    AuditLog.write(c, EpochMs.now(clock), userId(actor), null, ip, AUDIT_UPDATED, r.id(), details);
                }
                return view(updated, Role.OWNER, moderateAny);
            });
        } catch (SQLException e) {
            if (newSlug != null && isUniqueViolation(e)) {
                throw slugTakenError();
            }
            throw e;
        }
    }

    /**
     * Deletes a room with its members, bans, queue, votes, requests and outputs. The owner, or
     * {@code rooms.moderateAny}. Every item queued, suggested or playing in it gets
     * {@code last_referenced_at} = now (an unreference event).
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}, {@code 403
     *                   forbidden}
     */
    public void delete(Actor actor, String idOrSlug, String ip) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            if (a.role() != Role.OWNER && !moderateAny) {
                throw HttpError.forbidden("Only the room's owner can delete it");
            }
            String roomId = a.room().id();
            long now = EpochMs.now(clock);
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_referenced_at = ? WHERE id IN"
                    + " (SELECT item_id FROM queue_entries WHERE room_id = ? AND state IN ('queued', 'suggested',"
                    + " 'playing')) AND last_referenced_at < ?")) {
                ps.setLong(1, now);
                ps.setString(2, roomId);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET current_entry_id = NULL WHERE id = ?")) {
                ps.setString(1, roomId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                ps.executeUpdate();
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("slug", a.room().slug());
            details.put("name", a.room().name());
            details.put("ownerId", a.room().ownerId());
            AuditLog.write(c, now, userId(actor), null, ip, AUDIT_DELETED, roomId, details);
            return null;
        });
    }

    // ------------------------------------------------------------------ members

    private static final String RANK = "CASE m.role WHEN 'owner' THEN 0 WHEN 'moderator' THEN 1"
            + " WHEN 'member' THEN 2 ELSE 3 END";

    /**
     * The room's members, owner first, then moderators, members and banned users (each group by
     * {@code joined_at}, then user id). Owner and moderators only.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}, {@code 403
     *                   forbidden}, {@code 400 invalid_cursor}
     */
    public MemberPage members(Actor actor, String idOrSlug, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        int cRank = 0;
        long cAt = 0;
        String cId = null;
        if (cursor != null) {
            String[] parts = cursor.split("_", 3);
            try {
                if (parts.length != 3 || parts[2].isEmpty()) {
                    throw new NumberFormatException();
                }
                cRank = Integer.parseInt(parts[0]);
                cAt = Long.parseLong(parts[1]);
                cId = parts[2];
            } catch (NumberFormatException e) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
        }
        boolean moderateAny = moderatesAny(actor);
        int rank = cRank;
        long at = cAt;
        String afterId = cId;
        String sql = "SELECT m.user_id, u.display_name, m.role, m.joined_at, " + RANK
                + " FROM room_members m JOIN users u ON u.id = m.user_id WHERE m.room_id = ?"
                + (afterId != null ? " AND (" + RANK + " > ? OR (" + RANK + " = ? AND (m.joined_at > ?"
                + " OR (m.joined_at = ? AND m.user_id > ?))))" : "")
                + " ORDER BY " + RANK + ", m.joined_at, m.user_id LIMIT " + (limit + 1);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny);
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators see the member list");
            }
            List<Member> items = new ArrayList<>();
            List<Integer> ranks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a.room().id());
                if (afterId != null) {
                    ps.setInt(2, rank);
                    ps.setInt(3, rank);
                    ps.setLong(4, at);
                    ps.setLong(5, at);
                    ps.setString(6, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new Member(ActorRef.user(rs.getString(1), rs.getString(2)), rs.getString(3),
                                rs.getLong(4)));
                        ranks.add(rs.getInt(5));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Member last = items.get(limit - 1);
                next = ranks.get(limit - 1) + "_" + last.joinedAt() + "_" + last.user().id();
            }
            return new MemberPage(List.copyOf(items), next);
        });
    }

    /** Inserts or updates a member row; {@code joined_at} is kept for an existing row. @return whether it was created */
    private static boolean putMember(Connection c, String roomId, String userId, Role role, long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE room_members SET role = ? WHERE room_id = ? AND user_id = ?")) {
            ps.setString(1, role.wire());
            ps.setString(2, roomId);
            ps.setString(3, userId);
            if (ps.executeUpdate() > 0) {
                return false;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO room_members (room_id, user_id, role, joined_at)"
                + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, roomId);
            ps.setString(2, userId);
            ps.setString(3, role.wire());
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        return true;
    }

    private static Member member(Connection c, String roomId, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT m.user_id, u.display_name, m.role, m.joined_at"
                + " FROM room_members m JOIN users u ON u.id = m.user_id WHERE m.room_id = ? AND m.user_id = ?")) {
            ps.setString(1, roomId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Member(ActorRef.user(rs.getString(1), rs.getString(2)), rs.getString(3),
                        rs.getLong(4)) : null;
            }
        }
    }

    /**
     * Sets a user's role in a room. {@link Role#OWNER} transfers the room: {@code rooms.owner_id}
     * becomes the target, who must be an enabled account, and the old owner becomes a moderator.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room} (can't see the
     *                   room), {@code 404 user_not_found}, {@code 403 forbidden} (not allowed to
     *                   give that role to that user), {@code 409 cannot_change_own_role},
     *                   {@code 409 user_disabled} (transfer to a disabled account)
     */
    public RoleSet setRole(Actor actor, String idOrSlug, String targetUserId, Role role, String ip)
            throws SQLException {
        Objects.requireNonNull(role);
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            Room room = a.room();
            Boolean disabled = userDisabled(c, targetUserId);
            if (disabled == null) {
                throw new HttpError(404, "user_not_found", "No such user");
            }
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators change room roles");
            }
            if (targetUserId.equals(uid)) {
                throw new HttpError(409, "cannot_change_own_role", "You can't change your own role in a room"
                        + (a.role() == Role.OWNER ? "; transfer the room to someone else instead" : ""));
            }
            Role current = room.ownerId().equals(targetUserId) ? Role.OWNER : memberRole(c, room.id(), targetUserId);
            if (a.role() != Role.OWNER) {
                if (role == Role.OWNER || role == Role.MODERATOR || current == Role.OWNER || current == Role.MODERATOR) {
                    throw HttpError.forbidden("Moderators can only make users members or ban them");
                }
            }
            long now = EpochMs.now(clock);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("userId", targetUserId);
            details.put("role", role.wire());
            if (current != null) {
                details.put("previousRole", current.wire());
            }
            boolean created;
            if (role == Role.OWNER) {
                if (disabled) {
                    throw new HttpError(409, "user_disabled", "That account is disabled");
                }
                created = putMember(c, room.id(), targetUserId, Role.OWNER, now);
                putMember(c, room.id(), room.ownerId(), Role.MODERATOR, now);
                try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET owner_id = ? WHERE id = ?")) {
                    ps.setString(1, targetUserId);
                    ps.setString(2, room.id());
                    ps.executeUpdate();
                }
                details.put("previousOwnerId", room.ownerId());
                AuditLog.write(c, now, uid, null, ip, AUDIT_OWNER_TRANSFERRED, room.id(), details);
            } else {
                created = putMember(c, room.id(), targetUserId, role, now);
                if (current != role) {
                    AuditLog.write(c, now, uid, null, ip, AUDIT_ROLE_SET, room.id(), details);
                }
            }
            return new RoleSet(member(c, room.id(), targetUserId), created);
        });
    }

    /** {@code users.disabled} for a user, or {@code null} if there is no such user. */
    private static Boolean userDisabled(Connection c, String userId) throws SQLException {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT disabled FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) != 0 : null;
            }
        }
    }

    /**
     * Removes a user's member row (their role, or their ban).
     *
     * @return {@code false} if the user had no row
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}, {@code 403
     *                   forbidden}, {@code 409 owner_cannot_leave}
     */
    public boolean removeMember(Actor actor, String idOrSlug, String targetUserId, String ip) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            Room room = a.room();
            boolean self = targetUserId.equals(uid);
            Role current = room.ownerId().equals(targetUserId) ? Role.OWNER : memberRole(c, room.id(), targetUserId);
            if (current == Role.OWNER) {
                if (self) {
                    throw new HttpError(409, "owner_cannot_leave", "Transfer the room to someone else first");
                }
                throw HttpError.forbidden("The owner can't be removed");
            }
            if (!self) {
                boolean allowed = a.role() == Role.OWNER
                        || (a.role() == Role.MODERATOR && current != Role.MODERATOR);
                if (!allowed) {
                    throw HttpError.forbidden("Not allowed to remove this member");
                }
            }
            if (current == null) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?")) {
                ps.setString(1, room.id());
                ps.setString(2, targetUserId);
                ps.executeUpdate();
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("userId", targetUserId);
            details.put("previousRole", current.wire());
            AuditLog.write(c, EpochMs.now(clock), uid, null, ip, AUDIT_MEMBER_REMOVED, room.id(), details);
            return true;
        });
    }
}
