package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.auth.GuestKeys;
import io.siggi.togethertunes.auth.IpAccess;
import io.siggi.togethertunes.auth.Tokens;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Room bans and timeouts ({@code docs/rooms.md#bans-and-timeouts}, table {@code room_bans}).
 *
 * <p>One moderation action on a <b>subject</b> (a person: {@code u:<userId>} or {@code g:<guestId>})
 * writes one row per <b>target</b> it covers: the account ({@value #USER}, target = user id) or the
 * guest ({@value #GUEST}, target = guest id), and the IP key the person came from ({@value #IP},
 * target = {@link GuestKeys#ipKeyHash}, the hash {@code guests.key_hash} uses; never the address).
 * {@code expires_at} {@code NULL} is a <b>ban</b> until lifted, otherwise a <b>timeout</b> until then.
 * A new action on a subject replaces its rows; lifting removes them all ("unban lifts both").
 * {@code target_kind} leaves room for other targets (a home server, {@code
 * docs/future-ideas.md#home-server-bans}).
 *
 * <p>Who it applies to ({@link #find}): a user by their account row and, unless the room has a
 * member row for them (member, moderator or owner), by the IP key of the request or socket; a
 * guest by its guest row and the IP key; an anonymous visitor by the IP key. The owner, moderators
 * and {@code rooms.moderateAny} are never restricted. A restricted person is <b>read-only</b> in
 * the room: they may open it and listen, nothing else ({@link Restriction#error}).
 *
 * <p>IP key hashes stay on the server: no response, message, audit row or log line carries one.
 */
public final class RoomBans {
    /** A user's account ({@code target} = user id). */
    public static final String USER = "user";
    /** A guest ({@code target} = guest id). */
    public static final String GUEST = "guest";
    /** An IP key ({@code target} = {@link GuestKeys#ipKeyHash}). */
    public static final String IP = "ip";

    /** Shortest timeout. */
    public static final long MIN_TIMEOUT_MS = 1000;
    /** Longest timeout (about ten years; longer is a ban). */
    public static final long MAX_TIMEOUT_MS = 3650L * 24 * 3600 * 1000;

    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    private RoomBans() {
    }

    /**
     * An active ban ({@code until} {@code null}) or timeout (until {@code until}, epoch ms).
     */
    public record Restriction(Long until) {
        public static final Restriction BAN = new Restriction(null);

        public boolean ban() {
            return until == null;
        }

        /** {@code ban} or {@code timeout}. */
        public String kind() {
            return ban() ? "ban" : "timeout";
        }

        /** The answer to anything but listening: {@code 403 banned_from_room} / {@code 403 timed_out}. */
        public HttpError error() {
            return ban() ? new HttpError(403, "banned_from_room", "You are banned from this room: you can listen,"
                    + " but not change anything")
                    : new HttpError(403, "timed_out", "You are timed out in this room: you can listen, but not"
                    + " change anything until the timeout ends");
        }

        /** The stronger of two (either may be {@code null}): a ban, else the later timeout. */
        static Restriction stronger(Restriction a, Restriction b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            if (a.ban() || b.ban()) {
                return BAN;
            }
            return a.until() >= b.until() ? a : b;
        }
    }

    /** {@code u:<userId>}. */
    public static String userSubject(String userId) {
        return "u:" + Objects.requireNonNull(userId);
    }

    /** {@code g:<guestId>}. */
    public static String guestSubject(String guestId) {
        return "g:" + Objects.requireNonNull(guestId);
    }

    /** The IP key hash of a client IP, or {@code null} if it isn't an IP address. */
    public static String ipKeyHash(String clientIp) {
        return clientIp == null ? null : GuestKeys.ipKeyHash(clientIp);
    }

    // ------------------------------------------------------------------ reading

    /**
     * The restriction that applies to someone in a room now, or {@code null}: the strongest active
     * row whose target is {@code userId}'s account, {@code guestId}, or {@code ipKeyHash} (each may
     * be {@code null}; the caller leaves {@code ipKeyHash} out for a user with a member row).
     */
    static Restriction find(Connection c, String roomId, String userId, String guestId, String ipKeyHash, long now)
            throws SQLException {
        if (userId == null && guestId == null && ipKeyHash == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT expires_at FROM room_bans WHERE room_id = ?"
                + " AND (expires_at IS NULL OR expires_at > ?)"
                + " AND ((target_kind = ? AND target = ?) OR (target_kind = ? AND target = ?)"
                + " OR (target_kind = ? AND target = ?))")) {
            ps.setString(1, roomId);
            ps.setLong(2, now);
            ps.setString(3, USER);
            ps.setString(4, userId == null ? "" : userId);
            ps.setString(5, GUEST);
            ps.setString(6, guestId == null ? "" : guestId);
            ps.setString(7, IP);
            ps.setString(8, ipKeyHash == null ? "" : ipKeyHash);
            return strongest(ps);
        }
    }

    /** The active restriction a moderation action put on {@code subject} (any of its rows), or {@code null}. */
    static Restriction ofSubject(Connection c, String roomId, String subject, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT expires_at FROM room_bans WHERE room_id = ?"
                + " AND subject = ? AND (expires_at IS NULL OR expires_at > ?)")) {
            ps.setString(1, roomId);
            ps.setString(2, subject);
            ps.setLong(3, now);
            return strongest(ps);
        }
    }

    /** When the active restriction of {@code subject} was put in place, or {@code null}. */
    static Long createdAt(Connection c, String roomId, String subject, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(created_at) FROM room_bans WHERE room_id = ?"
                + " AND subject = ? AND (expires_at IS NULL OR expires_at > ?)")) {
            ps.setString(1, roomId);
            ps.setString(2, subject);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long at = rs.getLong(1);
                    return rs.wasNull() ? null : at;
                }
                return null;
            }
        }
    }

    private static Restriction strongest(PreparedStatement ps) throws SQLException {
        Restriction out = null;
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long until = rs.getLong(1);
                out = Restriction.stronger(out, rs.wasNull() ? Restriction.BAN : new Restriction(until));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Puts a ban ({@code expiresAt} {@code null}) or timeout on {@code subject}: replaces its rows by
     * one for its account or guest ({@code targetKind}/{@code target}) and, if known, one for its IP
     * key. Expired rows of the room go at the same time.
     */
    static void put(Connection c, String roomId, String subject, String targetKind, String target, String ipKeyHash,
                    Long expiresAt, String byUserId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_bans WHERE room_id = ?"
                + " AND (subject = ? OR (expires_at IS NOT NULL AND expires_at <= ?))")) {
            ps.setString(1, roomId);
            ps.setString(2, subject);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        insert(c, roomId, subject, targetKind, target, expiresAt, byUserId, now);
        if (ipKeyHash != null) {
            insert(c, roomId, subject, IP, ipKeyHash, expiresAt, byUserId, now);
        }
    }

    private static void insert(Connection c, String roomId, String subject, String targetKind, String target,
                               Long expiresAt, String byUserId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO room_bans (room_id, subject, target_kind, target,"
                + " expires_at, created_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, roomId);
            ps.setString(2, subject);
            ps.setString(3, targetKind);
            ps.setString(4, target);
            if (expiresAt == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, expiresAt);
            }
            if (byUserId == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, byUserId);
            }
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    /**
     * Lifts what was put on {@code subject}: every row (account or guest and IP), or with
     * {@code bansOnly} only a ban (a timeout stays).
     *
     * @return whether an active restriction was lifted
     */
    static boolean lift(Connection c, String roomId, String subject, boolean bansOnly, long now) throws SQLException {
        Restriction before = ofSubject(c, roomId, subject, now);
        if (before == null || bansOnly && !before.ban()) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_bans WHERE room_id = ? AND subject = ?"
                + (bansOnly ? " AND expires_at IS NULL" : ""))) {
            ps.setString(1, roomId);
            ps.setString(2, subject);
            ps.executeUpdate();
        }
        return true;
    }

    // ------------------------------------------------------------------ listing

    /**
     * One restricted person, as the owner and moderators see it.
     *
     * @param person       the user or guest (an actor ref: id and name, never an address)
     * @param kind         {@code ban} or {@code timeout}
     * @param until        a timeout's end (epoch ms), {@code null} for a ban
     * @param at           when it was put in place
     * @param addressCovered    whether the person's IP address is covered too (the address itself is
     *                     never shown; {@code ip.view} holders get a guest's last IP separately)
     * @param identifiedBy a guest's {@code cookie} or {@code ip}; {@code null} for a user
     */
    public record Entry(ActorRef person, String kind, Long until, long at, boolean addressCovered, String identifiedBy) {
    }

    /** One page of entries; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Entry> items, String nextCursor) {
    }

    /** Name shown for a restricted person whose account or guest no longer exists. */
    static final String GONE = "(deleted)";

    /**
     * The room's active bans and timeouts, newest first (then by subject, descending).
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}
     * @throws HttpError {@code 400 invalid_cursor}
     */
    static Page list(Connection c, String roomId, String cursor, int limit, long now) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        long cursorAt = 0;
        String cursorSubject = null;
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
            cursorSubject = cursor.substring(sep + 1);
        }
        String sql = "SELECT b.subject, b.target_kind, b.target, b.expires_at, b.created_at, u.display_name,"
                + " g.display_name, g.kind, (SELECT COUNT(*) FROM room_bans o WHERE o.room_id = b.room_id"
                + " AND o.subject = b.subject AND o.target_kind = ?)"
                + " FROM room_bans b LEFT JOIN users u ON b.target_kind = ? AND u.id = b.target"
                + " LEFT JOIN guests g ON b.target_kind = ? AND g.id = b.target"
                + " WHERE b.room_id = ? AND b.target_kind IN (?, ?) AND (b.expires_at IS NULL OR b.expires_at > ?)"
                + (cursorSubject != null ? " AND (b.created_at < ? OR (b.created_at = ? AND b.subject < ?))" : "")
                + " ORDER BY b.created_at DESC, b.subject DESC LIMIT " + (limit + 1);
        List<Entry> items = new ArrayList<>();
        List<String> subjects = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 1;
            ps.setString(n++, IP);
            ps.setString(n++, USER);
            ps.setString(n++, GUEST);
            ps.setString(n++, roomId);
            ps.setString(n++, USER);
            ps.setString(n++, GUEST);
            ps.setLong(n++, now);
            if (cursorSubject != null) {
                ps.setLong(n++, cursorAt);
                ps.setLong(n++, cursorAt);
                ps.setString(n, cursorSubject);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    subjects.add(rs.getString(1));
                    items.add(entry(rs));
                }
            }
        }
        String next = null;
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
            next = items.get(limit - 1).at() + "_" + subjects.get(limit - 1);
        }
        return new Page(List.copyOf(items), next);
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        String kind = rs.getString(2);
        String target = rs.getString(3);
        long until = rs.getLong(4);
        Long untilOrNull = rs.wasNull() ? null : until;
        long at = rs.getLong(5);
        boolean ip = rs.getLong(9) > 0;
        if (USER.equals(kind)) {
            String name = rs.getString(6);
            return new Entry(ActorRef.user(target, name == null ? GONE : name), untilOrNull == null ? "ban" : "timeout",
                    untilOrNull, at, ip, null);
        }
        String name = rs.getString(7);
        return new Entry(ActorRef.guest(target, name == null ? GONE : name), untilOrNull == null ? "ban" : "timeout",
                untilOrNull, at, ip, rs.getString(8));
    }

    /** The active restriction of one person as an {@link Entry}, or {@code null}. */
    static Entry entry(Connection c, String roomId, String targetKind, String target, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT b.subject, b.target_kind, b.target, b.expires_at,"
                + " b.created_at, u.display_name, g.display_name, g.kind, (SELECT COUNT(*) FROM room_bans o"
                + " WHERE o.room_id = b.room_id AND o.subject = b.subject AND o.target_kind = ?)"
                + " FROM room_bans b LEFT JOIN users u ON b.target_kind = ? AND u.id = b.target"
                + " LEFT JOIN guests g ON b.target_kind = ? AND g.id = b.target"
                + " WHERE b.room_id = ? AND b.subject = ? AND b.target_kind = ? AND b.target = ?"
                + " AND (b.expires_at IS NULL OR b.expires_at > ?)")) {
            ps.setString(1, IP);
            ps.setString(2, USER);
            ps.setString(3, GUEST);
            ps.setString(4, roomId);
            ps.setString(5, USER.equals(targetKind) ? userSubject(target) : guestSubject(target));
            ps.setString(6, targetKind);
            ps.setString(7, target);
            ps.setLong(8, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs) : null;
            }
        }
    }

    // ------------------------------------------------------------------ current IP

    /**
     * Where a person is connecting from right now, as an IP key hash: their newest connection in a
     * room ({@link RoomViewers#currentIpKey}), else the last IP the database knows ({@link
     * IpAccess#ipKeyHashOfUser} / {@link IpAccess#ipKeyHashOfGuest}); {@code null} if unknown.
     */
    static final class CurrentIps {
        private final IpAccess ipAccess;
        private final RoomViewers live;

        CurrentIps(IpAccess ipAccess, RoomViewers live) {
            this.ipAccess = Objects.requireNonNull(ipAccess);
            this.live = live;
        }

        String ofUser(String userId) throws SQLException {
            String key = live == null ? null : live.currentIpKey(userSubject(userId));
            return key != null ? Tokens.hash(key) : ipAccess.ipKeyHashOfUser(userId);
        }

        String ofGuest(String guestId) throws SQLException {
            String key = live == null ? null : live.currentIpKey(guestSubject(guestId));
            return key != null ? Tokens.hash(key) : ipAccess.ipKeyHashOfGuest(guestId);
        }
    }

    /**
     * A timeout's length from the API, as its end.
     *
     * @throws HttpError {@code 400 invalid_duration} outside {@value #MIN_TIMEOUT_MS}..{@value #MAX_TIMEOUT_MS} ms
     */
    public static long timeoutEnd(long now, long durationMs) {
        if (durationMs < MIN_TIMEOUT_MS || durationMs > MAX_TIMEOUT_MS) {
            throw new HttpError(400, "invalid_duration", "A timeout lasts from 1 second to 3650 days");
        }
        return now + durationMs;
    }
}
