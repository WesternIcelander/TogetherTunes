package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The <b>only</b> reader of IP columns ({@code sessions.ip}, {@code guests.ip_last},
 * {@code audit_log.ip}; invariant 5, {@code docs/auth.md#ip-address-policy}). Every method checks
 * {@link Permission#IP_VIEW} for the acting {@link Actor} itself, so a caller can't forget to.
 *
 * <p>General DTOs ({@link Guests.GuestView}, {@link Sessions.SessionView},
 * {@link AuditEntries.AuditEntryView}, {@link Users.UserView}) have no IP field. Responses that
 * show IPs to {@code ip.view} holders add them from here: the {@code ipsOf...} methods return an
 * empty map to an actor without {@code ip.view} (so the {@code ip} field is simply left out), and
 * {@link #sessions} refuses ({@code 401}/{@code 403}).
 *
 * <p>Writing IPs (inserts, {@link Guests#touch}) happens elsewhere; so does the one comparison
 * {@link #GUEST_IP_UNCHANGED} that lets {@link ActorResolver} notice a changed guest IP, which
 * yields only a boolean, never the stored address. {@code IpAccessGuardTest} fails if any other
 * main-source class selects an IP column.
 */
public final class IpAccess {
    /**
     * A {@code SELECT} expression: {@code 1} if {@code guests.ip_last} equals the bound parameter,
     * else {@code 0} (also when either is {@code NULL}). Reveals nothing beyond "same IP as this
     * request", which the caller already knows.
     */
    public static final String GUEST_IP_UNCHANGED = "CASE WHEN ip_last = ? THEN 1 ELSE 0 END";

    /** IN-lists are split into chunks of this size (well under SQLite's parameter limit). */
    private static final int CHUNK = 500;

    private final Database db;
    private final Permissions permissions;
    private final Clock clock;

    /**
     * A user's live session with its IP, for {@code ip.view} holders only.
     *
     * @param ip the IP the session was created from, or {@code null} if unknown
     */
    public record SessionIp(String id, String kind, long createdAt, long lastSeenAt, long expiresAt,
                            String userAgent, String ip) {
    }

    public IpAccess(Database db, Permissions permissions) {
        this(db, permissions, Clock.systemUTC());
    }

    public IpAccess(Database db, Permissions permissions, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.permissions = Objects.requireNonNull(permissions);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Whether {@code actor} may see IP addresses ({@code ip.view}). */
    public boolean mayView(Actor actor) throws SQLException {
        return permissions.allowed(actor, Permission.IP_VIEW);
    }

    /**
     * The last IP of each of these guests ({@code guests.ip_last}), by guest id; guests without one
     * are left out. Empty if {@code actor} lacks {@code ip.view}.
     */
    public Map<String, String> ipsOfGuests(Actor actor, Collection<String> guestIds) throws SQLException {
        return byId(actor, "SELECT id, ip_last FROM guests WHERE ip_last IS NOT NULL AND id IN ", guestIds);
    }

    /**
     * The client IP of each of these audit rows ({@code audit_log.ip}), by row id; rows without one
     * are left out. Empty if {@code actor} lacks {@code ip.view}.
     */
    public Map<String, String> ipsOfAuditEntries(Actor actor, Collection<String> auditIds) throws SQLException {
        return byId(actor, "SELECT id, ip FROM audit_log WHERE ip IS NOT NULL AND id IN ", auditIds);
    }

    /**
     * A user's live (unexpired) sessions with their IPs, newest first. Not paginated: a user has
     * few live sessions. An unknown user has none.
     *
     * @throws io.siggi.togethertunes.http.HttpError 401 {@code not_authenticated} or 403
     *                                                {@code forbidden} without {@code ip.view}
     */
    public List<SessionIp> sessions(Actor actor, String userId) throws SQLException {
        permissions.require(actor, Permission.IP_VIEW);
        Objects.requireNonNull(userId);
        long now = EpochMs.now(clock);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, created_at, last_seen_at, expires_at, user_agent, ip FROM sessions"
                            + " WHERE user_id = ? AND expires_at > ? ORDER BY id DESC")) {
                ps.setString(1, userId);
                ps.setLong(2, now);
                List<SessionIp> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SessionIp(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                                rs.getLong(5), rs.getString(6), rs.getString(7)));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    private Map<String, String> byId(Actor actor, String sqlPrefix, Collection<String> ids) throws SQLException {
        if (ids.isEmpty() || !mayView(actor)) {
            return Map.of();
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        return db.read(c -> {
            Map<String, String> out = new HashMap<>();
            for (int from = 0; from < distinct.size(); from += CHUNK) {
                query(c, sqlPrefix, distinct.subList(from, Math.min(distinct.size(), from + CHUNK)), out);
            }
            return Map.copyOf(out);
        });
    }

    private static void query(Connection c, String sqlPrefix, List<String> ids, Map<String, String> out)
            throws SQLException {
        StringBuilder sql = new StringBuilder(sqlPrefix).append('(');
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
    }
}
