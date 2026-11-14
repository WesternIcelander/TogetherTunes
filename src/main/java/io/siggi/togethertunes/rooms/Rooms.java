package io.siggi.togethertunes.rooms;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.UploadMetadata;
import io.siggi.togethertunes.retention.References;
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
 *   <li>A user {@code banned} from a room may still open a public or unlisted room and listen,
 *       read-only ({@link RoomBans}; {@code 404} for a private one), and doesn't see it listed.</li>
 * </ul>
 *
 * <h2>Effective role</h2>
 * The owner is {@code rooms.owner_id} (mirrored by a {@code room_members} row with role
 * {@code owner}). Other users have the role of their {@code room_members} row, if any. A user
 * holding {@code rooms.moderateAny} is a {@code moderator} in every room they don't own, even
 * if the room banned them. Guests and anonymous visitors have no room role. Bans and timeouts
 * ({@code room_bans}, {@link RoomBans}: of users here, of guests in {@link RoomGuests}) make the
 * person read-only ({@link Access#restriction}); they cover the person's IP address too, so a
 * user who isn't a member, a guest or an anonymous visitor from that address is read-only as well.
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
     * A room as clients see it. {@code settings}: every room setting with its value
     * ({@link RoomSettings#toJson()}). {@code you}: the caller's effective role ({@code null} for none)
     * and what they may do with the room itself.
     */
    public record RoomView(String id, String slug, String name, ActorRef owner, String visibility, String controlMode,
                           JsonObject settings, long createdAt, You you, Boolean alone, Integer listeners) {
        public RoomView(String id, String slug, String name, ActorRef owner, String visibility, String controlMode,
                        JsonObject settings, long createdAt, You you, Boolean alone) {
            this(id, slug, name, owner, visibility, controlMode, settings, createdAt, you, alone, null);
        }

        public RoomView(String id, String slug, String name, ActorRef owner, String visibility, String controlMode,
                        JsonObject settings, long createdAt, You you) {
            this(id, slug, name, owner, visibility, controlMode, settings, createdAt, you, null, null);
        }

        /**
         * This view with its listener count ({@code GET /api/rooms}): everyone in the room right
         * now, identified people once each plus anonymous connections ({@link RoomPresence#counts}).
         */
        public RoomView withListeners(int listeners) {
            return new RoomView(id, slug, name, owner, visibility, controlMode, settings, createdAt, you, alone,
                    listeners);
        }
    }

    /**
     * The caller's relation to a room: {@code role} is {@code owner}, {@code moderator},
     * {@code member}, {@code banned} or {@code null}; {@code canEdit}: may change the room;
     * {@code canDelete}: may delete it; {@code canModerate}: owner or moderator.
     */
    public record You(String role, boolean canEdit, boolean canDelete, boolean canModerate, Restricted restriction) {
        public You(String role, boolean canEdit, boolean canDelete, boolean canModerate) {
            this(role, canEdit, canDelete, canModerate, null);
        }
    }

    /**
     * The caller's ban or timeout in a room ({@link RoomBans}): {@code kind} {@code ban} or
     * {@code timeout}, {@code until} a timeout's end (epoch ms). They may listen, nothing else.
     */
    public record Restricted(String kind, Long until) {
        static Restricted of(RoomBans.Restriction r) {
            return r == null ? null : new Restricted(r.kind(), r.until());
        }
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

    /**
     * A change to a room. {@code null} fields stay as they are; {@code settings} is a merge patch
     * ({@link RoomSettings#patch}).
     */
    public record Edit(String name, String slug, Visibility visibility, ControlMode controlMode, JsonObject settings) {
        public Edit(String name, String slug, Visibility visibility, ControlMode controlMode) {
            this(name, slug, visibility, controlMode, null);
        }
    }

    /**
     * {@link #setRole}'s result: the member row, whether it was created, and whether a ban was put
     * in place or lifted (then callers broadcast {@link RoomEvent.RestrictionsChanged} too).
     */
    public record RoleSet(Member member, boolean created, boolean restrictionsChanged) {
        public RoleSet(Member member, boolean created) {
            this(member, created, false);
        }
    }

    /**
     * A {@code rooms} row with the owner's display name. {@code alone}: the owner's alone room
     * ({@code rooms.alone_user_id}, {@link AloneSessions}).
     */
    public record Room(String id, String slug, String name, String ownerId, String ownerName, Visibility visibility,
                       ControlMode controlMode, RoomSettings settings, long createdAt, boolean alone) {
        public Room(String id, String slug, String name, String ownerId, String ownerName, Visibility visibility,
                    ControlMode controlMode, RoomSettings settings, long createdAt) {
            this(id, slug, name, ownerId, ownerName, visibility, controlMode, settings, createdAt, false);
        }
    }

    /**
     * A room and the caller's effective role in it ({@code null}: none), and the ban or timeout that
     * makes the caller read-only there ({@code null}: none; never for the owner and moderators).
     * A user banned from the room has the role {@link Role#BANNED}.
     */
    public record Access(Room room, Role role, RoomBans.Restriction restriction) {
        public Access(Room room, Role role) {
            this(room, role, null);
        }

        /** @throws HttpError {@code 403 banned_from_room} / {@code timed_out} while restricted */
        public void requireUnrestricted() {
            if (restriction != null) {
                throw restriction.error();
            }
        }
    }

    private final Database db;
    private final Permissions permissions;
    private final Clock clock;

    private volatile RoomBans.CurrentIps currentIps;

    public Rooms(Database db, Permissions permissions, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
        this.currentIps = new RoomBans.CurrentIps(new io.siggi.togethertunes.auth.IpAccess(db, permissions, clock),
                null);
    }

    /**
     * Where people connect from right now ({@link RoomViewers}), so a ban covers a user's current IP
     * address even when it differs from their last session's. Without it, the database's last known
     * address is used.
     */
    public void liveIps(RoomViewers viewers) {
        this.currentIps = new RoomBans.CurrentIps(new io.siggi.togethertunes.auth.IpAccess(db, permissions, clock),
                Objects.requireNonNull(viewers));
    }

    Database db() {
        return db;
    }

    Clock clock() {
        return clock;
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
            + " r.control_mode, r.created_at, r.settings_json, r.alone_user_id FROM rooms r"
            + " JOIN users u ON u.id = r.owner_id";

    private static Room room(ResultSet rs) throws SQLException {
        return new Room(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Objects.requireNonNull(Visibility.parse(rs.getString(6)), "visibility"),
                Objects.requireNonNull(ControlMode.parse(rs.getString(7)), "control_mode"),
                RoomSettings.parse(rs.getString(9)), rs.getLong(8), rs.getString(10) != null);
    }

    private static HttpError aloneRoom() {
        return new HttpError(409, "alone_room", "This is your own play session, not a room");
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
    static Role memberRole(Connection c, String roomId, String userId) throws SQLException {
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

    boolean moderatesAny(Actor actor) throws SQLException {
        return actor instanceof Actor.User && permissions.allowed(actor, Permission.ROOMS_MODERATE_ANY);
    }

    /**
     * Throws unless the caller may see the room: {@code 404 room_not_found} for a private room
     * they aren't invited to (a banned user isn't). A ban or timeout doesn't hide a public or
     * unlisted room: the person may open it and listen, read-only ({@link RoomBans}).
     */
    private static void requireVisible(Access a, String userId) {
        if (a.room().alone() && !a.room().ownerId().equals(userId)) {
            throw roomNotFound(); // an alone room is its user's alone, whatever their permissions
        }
        boolean invited = a.role() != null && a.role() != Role.BANNED;
        if (a.room().visibility() == Visibility.PRIVATE && !invited) {
            throw roomNotFound();
        }
    }

    Access access(Connection c, Actor actor, String idOrSlug, boolean lock, boolean moderateAny)
            throws SQLException {
        return access(c, actor, idOrSlug, lock, moderateAny, null);
    }

    /**
     * The room and the caller's effective role and restriction. {@code ipKeyHash}: the IP key hash
     * of the request or socket ({@link RoomBans#ipKeyHash}), or {@code null} where it isn't known
     * (then only the account's or guest's own ban or timeout counts).
     */
    Access access(Connection c, Actor actor, String idOrSlug, boolean lock, boolean moderateAny, String ipKeyHash)
            throws SQLException {
        Room room = find(c, idOrSlug, lock);
        if (room == null) {
            throw roomNotFound();
        }
        String uid = userId(actor);
        String gid = actor instanceof Actor.Guest g ? g.guestId() : null;
        Role stored = uid == null ? null : memberRole(c, room.id(), uid);
        Role role = effective(room, uid, stored, moderateAny);
        RoomBans.Restriction restriction = null;
        if (!room.alone() && (role == null || !role.moderates())) {
            // A user from a banned IP address is treated as banned unless the room made them a member.
            String ip = uid != null && stored != null ? null : ipKeyHash;
            restriction = RoomBans.find(c, room.id(), uid, gid, ip, EpochMs.now(clock));
            if (restriction != null && restriction.ban() && uid != null) {
                role = Role.BANNED;
            } else if (role == Role.BANNED) {
                restriction = RoomBans.Restriction.BAN; // a member row from before room_bans (never written now)
            }
        }
        Access a = new Access(room, role, restriction);
        requireVisible(a, uid);
        return a;
    }

    /**
     * The room (by id or slug) and the caller's effective role, if the caller may see it.
     *
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room}
     */
    public Access access(Actor actor, String idOrSlug) throws SQLException {
        return access(actor, idOrSlug, null);
    }

    /**
     * {@link #access(Actor, String)} for a request or socket from {@code clientIp}, so a ban or
     * timeout of its IP address counts too ({@link RoomBans}).
     */
    public Access access(Actor actor, String idOrSlug, String clientIp) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String ipKeyHash = RoomBans.ipKeyHash(clientIp);
        return db.read(c -> access(c, actor, idOrSlug, false, moderateAny, ipKeyHash));
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

    private static You you(Room room, Role role, boolean moderateAny, RoomBans.Restriction restriction) {
        return new You(role == null ? null : role.wire(), role == Role.OWNER, role == Role.OWNER || moderateAny,
                role != null && role.moderates(), Restricted.of(restriction));
    }

    private static RoomView view(Room room, Role role, boolean moderateAny) {
        return view(room, role, moderateAny, null);
    }

    private static RoomView view(Room room, Role role, boolean moderateAny, RoomBans.Restriction restriction) {
        return new RoomView(room.id(), room.slug(), room.name(), ActorRef.user(room.ownerId(), room.ownerName()),
                room.visibility().wire(), room.controlMode().wire(), room.settings().toJson(), room.createdAt(),
                you(room, role, moderateAny, restriction), room.alone() ? Boolean.TRUE : null);
    }

    /** The room as the caller sees it. @throws HttpError as {@link #access(Actor, String)} */
    public RoomView get(Actor actor, String idOrSlug) throws SQLException {
        return get(actor, idOrSlug, null);
    }

    /**
     * The room as the caller sees it from {@code clientIp} ({@code you.restriction} also by IP).
     * @throws HttpError as {@link #access(Actor, String)}
     */
    public RoomView get(Actor actor, String idOrSlug, String clientIp) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String ipKeyHash = RoomBans.ipKeyHash(clientIp);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny, ipKeyHash);
            return view(a.room(), a.role(), moderateAny, a.restriction());
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
        return list(actor, null, cursor, limit);
    }

    /**
     * The key an exact room search names, or {@code null}: the query itself if it is a slug or a
     * room id, or the room segment of a room URL ({@code …/rooms/{key}…} or the short link
     * {@code …/r/{slug}}, with or without scheme and host; query and fragment ignored).
     */
    static String exactKey(String query) {
        if (query == null) {
            return null;
        }
        String s = query.strip();
        if (s.isEmpty() || s.length() > 2048) {
            return null;
        }
        String key = s;
        if (s.indexOf('/') >= 0) {
            int scheme = s.indexOf("://");
            String path = s;
            if (scheme >= 0) {
                int slash = s.indexOf('/', scheme + 3);
                path = slash < 0 ? "" : s.substring(slash);
            }
            int cut = path.length();
            for (char ch : new char[]{'?', '#'}) {
                int i = path.indexOf(ch);
                if (i >= 0 && i < cut) {
                    cut = i;
                }
            }
            String[] segments = path.substring(0, cut).split("/");
            key = null;
            for (int i = 0; i + 1 < segments.length; i++) {
                if (segments[i].equals("rooms") || segments[i].equals("r")) {
                    key = segments[i + 1];
                    break;
                }
            }
            if (key == null) {
                return null;
            }
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (UUID_SHAPE.matcher(lower).matches()) {
            return lower;
        }
        try {
            return slug(lower);
        } catch (HttpError e) {
            return null;
        }
    }

    /**
     * {@link #list(Actor, String, int)} filtered by {@code query}: every word of it
     * ({@code Library.terms}) must occur in the room's name or slug, ignoring case. Besides, if the
     * query is a room's exact slug or id, or a room URL ({@link #exactKey}), and the caller may open
     * that room ({@link #access(Actor, String)}: so also an unlisted room, never a private room they
     * aren't invited to nor one that banned them), that room comes first on the first page, in
     * addition to the {@code limit} others, and is left out of the rest of the list.
     *
     * @throws HttpError {@code 400 invalid_query} (over {@value io.siggi.togethertunes.library.Library#QUERY_MAX_CHARS}
     *                   characters), {@code 400 invalid_cursor}
     */
    public Page list(Actor actor, String query, String cursor, int limit) throws SQLException {
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
        List<String> terms = io.siggi.togethertunes.library.Library.terms(query);
        String exactKey = exactKey(query);
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        String gid = actor instanceof Actor.Guest g ? g.guestId() : null;
        Dialect d = db.dialect();
        StringBuilder filter = new StringBuilder();
        String like = " LIKE ? ESCAPE '" + Dialect.LIKE_ESCAPE + "'";
        for (int i = 0; i < terms.size(); i++) {
            filter.append(" AND (").append(d.lower("r.name")).append(like).append(" OR r.slug").append(like)
                    .append(")");
        }
        String sql = "SELECT r.id, r.slug, r.name, r.owner_id, u.display_name, r.visibility, r.control_mode,"
                + " r.created_at, r.settings_json, r.alone_user_id, m.role FROM rooms r JOIN users u ON u.id = r.owner_id"
                + " LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = ?"
                + " WHERE r.alone_user_id IS NULL AND (r.owner_id = ? OR m.role IN ('owner', 'moderator', 'member')"
                + " OR (r.visibility = 'public' AND NOT EXISTS (SELECT 1 FROM room_bans b WHERE b.room_id = r.id"
                + " AND b.expires_at IS NULL AND ((b.target_kind = 'user' AND b.target = ?)"
                + " OR (b.target_kind = 'guest' AND b.target = ?)))))"
                + filter + " AND r.id <> ?"
                + (cursorId != null ? " AND (r.created_at < ? OR (r.created_at = ? AND r.id < ?))" : "")
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT " + (limit + 1);
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            RoomView exact = null;
            if (exactKey != null) {
                try {
                    Access a = access(c, actor, exactKey, false, moderateAny);
                    if (!a.room().alone()) {
                        exact = view(a.room(), a.role(), moderateAny);
                    }
                } catch (HttpError e) {
                    // not a room the caller may open: no exact match
                }
            }
            List<RoomView> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int n = 1;
                ps.setString(n++, uid == null ? "" : uid);
                ps.setString(n++, uid == null ? "" : uid);
                ps.setString(n++, uid == null ? "" : uid);
                ps.setString(n++, gid == null ? "" : gid);
                for (String term : terms) {
                    String pattern = Dialect.containsPattern(term);
                    ps.setString(n++, pattern);
                    ps.setString(n++, pattern);
                }
                ps.setString(n++, exact == null ? "" : exact.id());
                if (afterId != null) {
                    ps.setLong(n++, at);
                    ps.setLong(n++, at);
                    ps.setString(n, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Room room = room(rs);
                        Role role = effective(room, uid, Role.parse(rs.getString(11)), moderateAny);
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
            if (exact != null && afterId == null) {
                items.add(0, exact);
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
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM rooms WHERE owner_id = ? AND alone_user_id IS NULL")) {
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
                    Room room = new Room(id, s, cleanName, owner.userId(), ownerName, v, m, RoomSettings.DEFAULTS, now);
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

    /** The name an alone room has (never shown as a room: clients show "alone"). */
    public static final String ALONE_NAME = "Alone";

    /**
     * The id of the user's alone room ({@link AloneSessions}), made on first use: a {@code private}
     * room in {@code owner} mode owned by the user, with {@code alone_user_id} set and the slug
     * {@code ~<userId>} (not a valid room slug, so it never collides with one). Not counted in
     * {@link #MAX_OWNED}, never listed, and only its user can open it.
     *
     * @throws HttpError {@code 401 not_authenticated} if the user is gone
     */
    public String aloneRoom(String userId) throws SQLException {
        Objects.requireNonNull(userId);
        for (int attempt = 1; ; attempt++) {
            String existing = db.read(c -> aloneRoomOf(c, userId));
            if (existing != null) {
                return existing;
            }
            try {
                return db.write(c -> {
                    String again = aloneRoomOf(c, userId);
                    if (again != null) {
                        return again;
                    }
                    if (userDisabled(c, userId) == null) {
                        throw new HttpError(401, "not_authenticated", "Log in first");
                    }
                    String id = UuidV7.newId();
                    long now = EpochMs.now(clock);
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO rooms (id, slug, name, owner_id,"
                            + " created_at, visibility, control_mode, settings_json, playback_state, anchor_server_ms,"
                            + " anchor_position_ms, version, alone_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, '{}', 'idle', 0, 0,"
                            + " 0, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, "~" + userId);
                        ps.setString(3, ALONE_NAME);
                        ps.setString(4, userId);
                        ps.setLong(5, now);
                        ps.setString(6, Visibility.PRIVATE.wire());
                        ps.setString(7, ControlMode.OWNER.wire());
                        ps.setString(8, userId);
                        ps.executeUpdate();
                    }
                    putMember(c, id, userId, Role.OWNER, now);
                    return id;
                });
            } catch (SQLException e) {
                if (!isUniqueViolation(e) || attempt >= 3) {
                    throw e; // a concurrent first use made it: read it again
                }
            }
        }
    }

    /** The user's alone room's id, or {@code null}. */
    static String aloneRoomOf(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM rooms WHERE alone_user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static HttpError slugTakenError() {
        return new HttpError(409, "slug_taken", "Another room already uses this link");
    }

    /**
     * Changes a room's name, slug, visibility, control mode or settings. The owner only. The
     * audit entry names the settings that changed, never their values.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room} (can't see it),
     *                   {@code 403 forbidden} (not the owner), {@code 409 slug_taken}, and the
     *                   validation errors
     */
    public RoomView update(Actor actor, String idOrSlug, Edit edit, String ip) throws SQLException {
        return updateAdjusting(actor, idOrSlug, edit, ip).view();
    }

    /**
     * {@link #updateAdjusting}'s result.
     *
     * @param adjusted settings changed on top of the edit to keep uploads coupled to library
     *                 requests ({@link RoomSettings#patchAdjusting}); empty if none
     */
    public record Updated(RoomView view, List<String> adjusted) {
    }

    /** {@link #update}, also reporting the settings it adjusted ({@link RoomSettings.Patched#adjusted}). */
    public Updated updateAdjusting(Actor actor, String idOrSlug, Edit edit, String ip) throws SQLException {
        Objects.requireNonNull(edit);
        List<String> adjusted = new ArrayList<>();
        String newName = edit.name() == null ? null : name(edit.name());
        String newSlug = edit.slug() == null ? null : slug(edit.slug());
        boolean moderateAny = moderatesAny(actor);
        try {
            return db.write(c -> {
                Access a = access(c, actor, idOrSlug, true, moderateAny);
                if (a.role() != Role.OWNER) {
                    throw HttpError.forbidden("Only the room's owner can change it");
                }
                if (a.room().alone()) {
                    throw aloneRoom();
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
                RoomSettings settings = r.settings();
                if (edit.settings() != null) {
                    RoomSettings.Patched p = settings.patchAdjusting(edit.settings());
                    RoomSettings patched = p.settings();
                    adjusted.addAll(p.adjusted());
                    List<String> changed = patched.changedFrom(settings);
                    if (!changed.isEmpty()) {
                        settings = patched;
                        details.put("settings", changed);
                    }
                }
                Room updated = new Room(r.id(), slug, name, r.ownerId(), r.ownerName(), visibility, mode, settings,
                        r.createdAt());
                if (!details.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET name = ?, slug = ?, visibility = ?,"
                            + " control_mode = ?, settings_json = ? WHERE id = ?")) {
                        ps.setString(1, name);
                        ps.setString(2, slug);
                        ps.setString(3, visibility.wire());
                        ps.setString(4, mode.wire());
                        ps.setString(5, settings.toStoredJson());
                        ps.setString(6, r.id());
                        ps.executeUpdate();
                    }
                    AuditLog.write(c, EpochMs.now(clock), userId(actor), null, ip, AUDIT_UPDATED, r.id(), details);
                    if (details.get("settings") instanceof List<?> changed && changed.contains(AutoplaySelections.SELECTORS)) {
                        AutoplaySelections.prune(c, r.id());
                    }
                }
                return new Updated(view(updated, Role.OWNER, moderateAny), List.copyOf(adjusted));
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
     * @return the deleted room's id
     * @throws HttpError {@code 404 room_not_found} / {@code 403 banned_from_room}, {@code 403
     *                   forbidden}
     */
    public String delete(Actor actor, String idOrSlug, String ip) throws SQLException {
        return delete(actor, idOrSlug, ip, null);
    }

    /** Work done in a room's delete transaction just before the row goes ({@link #delete(Actor, String, String, BeforeDelete)}). */
    @FunctionalInterface
    public interface BeforeDelete {
        void run(Connection c, String roomId, long now) throws SQLException;
    }

    /**
     * {@link #delete(Actor, String, String)}, running {@code before} (if not {@code null}) in the
     * same transaction once every check passed, before the row is deleted (the alone sessions
     * keep what a fork needs, {@link AloneSessions#roomDeleting}). An alone room can't be deleted
     * ({@code 409 alone_room}).
     */
    public String delete(Actor actor, String idOrSlug, String ip, BeforeDelete before) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            if (a.role() != Role.OWNER && !moderateAny) {
                throw HttpError.forbidden("Only the room's owner can delete it");
            }
            if (a.room().alone()) {
                throw aloneRoom();
            }
            String roomId = a.room().id();
            long now = EpochMs.now(clock);
            if (before != null) {
                before.run(c, roomId, now);
            }
            References.touchRoomQueue(c, roomId, now);
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
            return roomId;
        });
    }

    // ------------------------------------------------------------------ members

    private static final String RANK = "CASE m.role WHEN 'owner' THEN 0 WHEN 'moderator' THEN 1"
            + " WHEN 'member' THEN 2 ELSE 3 END";
    /**
     * The member rows plus the users banned from the room ({@code room_bans}, role {@code banned},
     * {@code joined_at} = when they were banned): {@code uid, name, role, at, rnk}. Binds the room
     * id twice.
     */
    private static final String MEMBERS_AND_BANNED = "SELECT m.user_id AS uid, u.display_name AS name,"
            + " m.role AS role, m.joined_at AS at, " + RANK + " AS rnk"
            + " FROM room_members m JOIN users u ON u.id = m.user_id WHERE m.room_id = ?"
            + " UNION ALL SELECT b.target, u.display_name, 'banned', b.created_at, 3"
            + " FROM room_bans b JOIN users u ON u.id = b.target WHERE b.room_id = ?"
            + " AND b.target_kind = 'user' AND b.expires_at IS NULL";

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
        String sql = "SELECT x.uid, x.name, x.role, x.at, x.rnk FROM (" + MEMBERS_AND_BANNED + ") x"
                + (afterId != null ? " WHERE (x.rnk > ? OR (x.rnk = ? AND (x.at > ?"
                + " OR (x.at = ? AND x.uid > ?))))" : "")
                + " ORDER BY x.rnk, x.at, x.uid LIMIT " + (limit + 1);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny);
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators see the member list");
            }
            List<Member> items = new ArrayList<>();
            List<Integer> ranks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a.room().id());
                ps.setString(2, a.room().id());
                if (afterId != null) {
                    ps.setInt(3, rank);
                    ps.setInt(4, rank);
                    ps.setLong(5, at);
                    ps.setLong(6, at);
                    ps.setString(7, afterId);
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
        try (PreparedStatement ps = c.prepareStatement("SELECT x.uid, x.name, x.role, x.at FROM ("
                + MEMBERS_AND_BANNED + ") x WHERE x.uid = ? ORDER BY x.rnk")) {
            ps.setString(1, roomId);
            ps.setString(2, roomId);
            ps.setString(3, userId);
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
        // Looked up before the write (it may read the database): the address a ban covers too.
        String ipKeyHash = role == Role.BANNED && targetUserId != null ? currentIps.ofUser(targetUserId) : null;
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            Room room = a.room();
            if (room.alone()) {
                throw aloneRoom();
            }
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
            long now = EpochMs.now(clock);
            Role current = currentRole(c, room, targetUserId, now);
            if (a.role() != Role.OWNER) {
                if (role == Role.OWNER || role == Role.MODERATOR || current == Role.OWNER || current == Role.MODERATOR) {
                    throw HttpError.forbidden("Moderators can only make users members or ban them");
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("userId", targetUserId);
            details.put("role", role.wire());
            if (current != null) {
                details.put("previousRole", current.wire());
            }
            boolean created;
            boolean restrictionsChanged = false;
            if (role == Role.OWNER) {
                if (disabled) {
                    throw new HttpError(409, "user_disabled", "That account is disabled");
                }
                created = putMember(c, room.id(), targetUserId, Role.OWNER, now);
                restrictionsChanged = RoomBans.lift(c, room.id(), RoomBans.userSubject(targetUserId), false, now);
                putMember(c, room.id(), room.ownerId(), Role.MODERATOR, now);
                try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET owner_id = ? WHERE id = ?")) {
                    ps.setString(1, targetUserId);
                    ps.setString(2, room.id());
                    ps.executeUpdate();
                }
                details.put("previousOwnerId", room.ownerId());
                AuditLog.write(c, now, uid, null, ip, AUDIT_OWNER_TRANSFERRED, room.id(), details);
            } else if (role == Role.BANNED) {
                created = current == null;
                if (current != Role.BANNED) {
                    ban(c, room.id(), targetUserId, ipKeyHash, null, uid, now);
                    restrictionsChanged = true;
                    details.put("addressCovered", ipKeyHash != null);
                    AuditLog.write(c, now, uid, null, ip, AUDIT_ROLE_SET, room.id(), details);
                }
            } else {
                // Making a banned user a member or moderator lifts the ban (the account and the address).
                restrictionsChanged = RoomBans.lift(c, room.id(), RoomBans.userSubject(targetUserId), true, now);
                created = putMember(c, room.id(), targetUserId, role, now);
                if (current != role) {
                    AuditLog.write(c, now, uid, null, ip, AUDIT_ROLE_SET, room.id(), details);
                }
            }
            AutoplaySelections.prune(c, room.id()); // a demoted, banned or former owner may have lost the right
            return new RoleSet(member(c, room.id(), targetUserId), created, restrictionsChanged);
        });
    }

    /** A user's role for the member rules: owner, their member row's role, else banned if a ban is on them. */
    private static Role currentRole(Connection c, Room room, String userId, long now) throws SQLException {
        if (room.ownerId().equals(userId)) {
            return Role.OWNER;
        }
        Role stored = memberRole(c, room.id(), userId);
        if (stored != null && stored != Role.BANNED) {
            return stored;
        }
        RoomBans.Restriction r = RoomBans.ofSubject(c, room.id(), RoomBans.userSubject(userId), now);
        return r != null && r.ban() ? Role.BANNED : null;
    }

    /**
     * Bans ({@code until} {@code null}) or times out a user: the account and, if known, their
     * current IP address. A ban also ends their role in the room (as {@code banned} did before).
     */
    private static void ban(Connection c, String roomId, String userId, String ipKeyHash, Long until, String byUserId,
                            long now) throws SQLException {
        if (until == null) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?")) {
                ps.setString(1, roomId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
        }
        RoomBans.put(c, roomId, RoomBans.userSubject(userId), RoomBans.USER, userId, ipKeyHash, until, byUserId, now);
    }

    /** {@link #restrictUser}'s result: the restriction now on the user, and whether it is new. */
    public record Restricting(RoomBans.Entry entry, boolean created) {
    }

    public static final String AUDIT_RESTRICTED = "room.user_restricted";
    public static final String AUDIT_LIFTED = "room.user_restriction_lifted";

    /**
     * Bans ({@code durationMs} {@code null}) or times out a user in a room
     * ({@code docs/rooms.md#bans-and-timeouts}): their account and the IP address they connect from
     * now. The owner and moderators; nobody restricts themselves or the owner, moderators don't
     * restrict moderators, and a moderator can only be banned (by the owner, which ends their role),
     * not timed out ({@code 409 target_moderates}). A new ban or timeout replaces the previous one.
     * Callers broadcast {@link RoomEvent.RestrictionsChanged}.
     *
     * @throws HttpError {@code 404 room_not_found} / {@code user_not_found}, {@code 403 forbidden},
     *                   {@code 409 cannot_restrict_self} / {@code target_moderates}, {@code 400
     *                   invalid_duration}, {@code 409 alone_room}
     */
    public Restricting restrictUser(Actor actor, String idOrSlug, String targetUserId, Long durationMs, String ip)
            throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        String ipKeyHash = targetUserId == null ? null : currentIps.ofUser(targetUserId);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            Room room = a.room();
            if (room.alone()) {
                throw aloneRoom();
            }
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators ban or time out people");
            }
            if (userDisabled(c, targetUserId) == null) {
                throw new HttpError(404, "user_not_found", "No such user");
            }
            if (targetUserId.equals(uid)) {
                throw new HttpError(409, "cannot_restrict_self", "You can't ban or time out yourself");
            }
            long now = EpochMs.now(clock);
            Role current = currentRole(c, room, targetUserId, now);
            if (current == Role.OWNER) {
                throw HttpError.forbidden("The owner can't be banned or timed out");
            }
            if (current == Role.MODERATOR) {
                if (a.role() != Role.OWNER) {
                    throw HttpError.forbidden("Moderators can't ban or time out moderators");
                }
                if (durationMs != null) {
                    throw new HttpError(409, "target_moderates", "Remove their moderator role first");
                }
            }
            Long until = durationMs == null ? null : RoomBans.timeoutEnd(now, durationMs);
            boolean created = RoomBans.ofSubject(c, room.id(), RoomBans.userSubject(targetUserId), now) == null;
            ban(c, room.id(), targetUserId, ipKeyHash, until, uid, now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("userId", targetUserId);
            details.put("kind", until == null ? "ban" : "timeout");
            if (until != null) {
                details.put("until", until);
            }
            if (current != null) {
                details.put("previousRole", current.wire());
            }
            details.put("addressCovered", ipKeyHash != null);
            AuditLog.write(c, now, uid, null, ip, AUDIT_RESTRICTED, room.id(), details);
            AutoplaySelections.prune(c, room.id()); // a ban ends the role, and with it the right to select
            return new Restricting(RoomBans.entry(c, room.id(), RoomBans.USER, targetUserId, now), created);
        });
    }

    /**
     * Lifts a user's ban or timeout in a room (the account and the address together). The owner and
     * moderators. Callers broadcast {@link RoomEvent.RestrictionsChanged}.
     *
     * @return whether there was one
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden}
     */
    public boolean liftUser(Actor actor, String idOrSlug, String targetUserId, String ip) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators lift bans and timeouts");
            }
            long now = EpochMs.now(clock);
            if (targetUserId == null || !RoomBans.lift(c, a.room().id(), RoomBans.userSubject(targetUserId), false,
                    now)) {
                return false;
            }
            AuditLog.write(c, now, uid, null, ip, AUDIT_LIFTED, a.room().id(), Map.of("userId", targetUserId));
            return true;
        });
    }

    /**
     * The active ban or timeout of one user in the room, or {@code null}. The owner and moderators.
     *
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden}
     */
    public RoomBans.Entry restrictionOfUser(Actor actor, String idOrSlug, String targetUserId) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny);
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators see bans and timeouts");
            }
            return targetUserId == null ? null
                    : RoomBans.entry(c, a.room().id(), RoomBans.USER, targetUserId, EpochMs.now(clock));
        });
    }

    /**
     * The room's active bans and timeouts (users and guests), newest first. The owner and moderators.
     *
     * @throws HttpError {@code 404 room_not_found}, {@code 403 forbidden}, {@code 400 invalid_cursor}
     */
    public RoomBans.Page restrictions(Actor actor, String idOrSlug, String cursor, int limit) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        return db.read(c -> {
            Access a = access(c, actor, idOrSlug, false, moderateAny);
            if (a.role() == null || !a.role().moderates()) {
                throw HttpError.forbidden("Only the owner and moderators see bans and timeouts");
            }
            return RoomBans.list(c, a.room().id(), cursor, limit, EpochMs.now(clock));
        });
    }

    /**
     * The id of the user with this username (case-insensitive, disabled accounts too, so
     * {@link #setRole} gives its usual answers), or {@code null} if there is none. For adding a
     * member by name from the room's management view.
     */
    public String userIdByUsername(String username) throws SQLException {
        if (username == null) {
            return null;
        }
        String name = username.strip();
        if (name.isEmpty() || name.length() > 100) {
            return null;
        }
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE "
                    + db.dialect().equalsIgnoreCase("username"))) {
                ps.setString(1, Dialect.foldCase(name));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
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
        return removeMemberRole(actor, idOrSlug, targetUserId, ip) != null;
    }

    /**
     * {@link #removeMember}, returning the role the user had ({@link Role#BANNED}: a ban was lifted),
     * or {@code null} if they had none.
     */
    Role removeMemberRole(Actor actor, String idOrSlug, String targetUserId, String ip) throws SQLException {
        boolean moderateAny = moderatesAny(actor);
        String uid = userId(actor);
        return db.write(c -> {
            Access a = access(c, actor, idOrSlug, true, moderateAny);
            Room room = a.room();
            boolean self = targetUserId.equals(uid);
            long now = EpochMs.now(clock);
            Role current = currentRole(c, room, targetUserId, now);
            if (self && current == Role.BANNED) {
                throw new HttpError(403, "banned_from_room", "You are banned from this room");
            }
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
                return null;
            }
            if (current == Role.BANNED) {
                // Lifting a ban lifts the account and the address together.
                RoomBans.lift(c, room.id(), RoomBans.userSubject(targetUserId), true, now);
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM room_members WHERE room_id = ? AND user_id = ?")) {
                    ps.setString(1, room.id());
                    ps.setString(2, targetUserId);
                    ps.executeUpdate();
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("userId", targetUserId);
            details.put("previousRole", current.wire());
            AuditLog.write(c, now, uid, null, ip, AUDIT_MEMBER_REMOVED, room.id(), details);
            AutoplaySelections.prune(c, room.id()); // left (or removed) for good: their selections go
            return current;
        });
    }
}
