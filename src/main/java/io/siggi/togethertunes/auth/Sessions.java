package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Login sessions ({@code docs/auth.md#sessions}): opaque {@link Tokens} whose SHA-256 is stored
 * in {@code sessions.token_hash}. {@link ActorResolver} looks them up.
 *
 * <p>Kinds: {@code cookie} (the {@code sid} cookie of the web UI) and {@code bearer}
 * ({@code Authorization: Bearer}, from {@code POST /api/auth/token}). Both expire
 * {@link #LIFETIME_MS} after their last use: {@link ActorResolver} calls {@link #touch} when a
 * session is used, at most once per {@link #TOUCH_INTERVAL_MS}, which moves {@code last_seen_at}
 * and {@code expires_at} (and the resolver then re-sends the {@code sid} cookie with a fresh
 * {@code Max-Age}). Expired rows are never resolved; they are deleted when their user next gets a
 * session, and by {@link #deleteExpired} at startup.
 *
 * <p>{@link SessionView}, what users see of their sessions, has no IP field: {@code sessions.ip}
 * is for {@code ip.view} holders only, through {@code IpAccess}.
 */
public final class Sessions {
    /** Sliding lifetime of a session: it expires this long after its last use. */
    public static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;
    /** A session's use is recorded (and its expiry moved) at most this often. */
    public static final long TOUCH_INTERVAL_MS = 60_000;
    /** Default and largest page size of {@link #list}. */
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest {@code user_agent} we store. */
    static final int USER_AGENT_MAX = 512;

    private final Database db;
    private final Clock clock;

    /** A new session and the token that identifies it (only ever handed to the client). */
    public record Created(String sessionId, String token, long expiresAt) {
        @Override
        public String toString() {
            return "Created[sessionId=" + sessionId + ", token=" + Tokens.redact(token) + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * A session as its owner sees it: no token, no hash, no IP.
     *
     * @param kind {@code cookie} or {@code bearer}
     */
    public record SessionView(String id, String kind, long createdAt, long lastSeenAt, long expiresAt,
                              String userAgent) {
    }

    /** One page of {@link #list}; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<SessionView> items, String nextCursor) {
    }

    public Sessions(Database db) {
        this(db, Clock.systemUTC());
    }

    public Sessions(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Creates a session of {@code kind} for {@code userId}.
     *
     * @param ip the client IP, stored for {@code ip.view} holders only (read through IpAccess)
     */
    public Created create(String userId, Actor.Via kind, String userAgent, String ip) throws SQLException {
        return db.write(c -> create(c, userId, kind, userAgent, ip));
    }

    /** {@link #create} in the caller's transaction. */
    public Created create(Connection c, String userId, Actor.Via kind, String userAgent, String ip) throws SQLException {
        String token = Tokens.newToken();
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        long expiresAt = now + LIFETIME_MS;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ? AND expires_at <= ?")) {
            ps.setString(1, userId);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sessions (id, user_id, token_hash, kind, created_at, last_seen_at, expires_at, user_agent, ip)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, Tokens.hash(token));
            ps.setString(4, kind.kind());
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, expiresAt);
            setNullable(ps, 8, truncate(userAgent));
            setNullable(ps, 9, ip);
            ps.executeUpdate();
        }
        return new Created(id, token, expiresAt);
    }

    /**
     * Records a use of a session last seen at {@code lastSeenAt}: unless that was less than
     * {@link #TOUCH_INTERVAL_MS} ago, sets {@code last_seen_at} to now and {@code expires_at} to
     * now + {@link #LIFETIME_MS}.
     *
     * @return the new {@code expires_at}, or 0 if nothing was updated (too soon, a concurrent
     *         request already did it, or the session is gone)
     */
    public long touch(String sessionId, long lastSeenAt) throws SQLException {
        long now = EpochMs.now(clock);
        if (now - lastSeenAt < TOUCH_INTERVAL_MS) {
            return 0;
        }
        long expiresAt = now + LIFETIME_MS;
        return db.write(c -> {
            // Conditional on the value we read, so concurrent requests write once.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sessions SET last_seen_at = ?, expires_at = ? WHERE id = ? AND last_seen_at = ? AND expires_at > ?")) {
                ps.setLong(1, now);
                ps.setLong(2, expiresAt);
                ps.setString(3, sessionId);
                ps.setLong(4, lastSeenAt);
                ps.setLong(5, now);
                return ps.executeUpdate() > 0 ? expiresAt : 0L;
            }
        });
    }

    /**
     * The user's live sessions, newest first.
     *
     * @param cursor   {@code nextCursor} of the previous page, or {@code null} for the first
     * @param limit    page size, 1..{@link #LIST_MAX}
     */
    public Page list(String userId, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit");
        }
        long now = EpochMs.now(clock);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, created_at, last_seen_at, expires_at, user_agent FROM sessions"
                            + " WHERE user_id = ? AND expires_at > ?" + (cursor == null ? "" : " AND id < ?")
                            + " ORDER BY id DESC LIMIT ?")) {
                int i = 1;
                ps.setString(i++, userId);
                ps.setLong(i++, now);
                if (cursor != null) {
                    ps.setString(i++, cursor);
                }
                ps.setInt(i, limit + 1);
                List<SessionView> items = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new SessionView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                                rs.getLong(5), rs.getString(6)));
                    }
                }
                if (items.size() > limit) {
                    items.remove(limit);
                    return new Page(List.copyOf(items), items.get(limit - 1).id());
                }
                return new Page(List.copyOf(items), null);
            }
        });
    }

    /** Deletes the user's session with this id; false if the user has no such session. */
    public boolean revoke(String userId, String sessionId) throws SQLException {
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE id = ? AND user_id = ?")) {
                ps.setString(1, sessionId);
                ps.setString(2, userId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Deletes every session of the user except {@code keepSessionId}; how many were live. */
    public int revokeOthers(String userId, String keepSessionId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            int live;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND id <> ? AND expires_at > ?")) {
                ps.setString(1, userId);
                ps.setString(2, keepSessionId);
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    live = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ? AND id <> ?")) {
                ps.setString(1, userId);
                ps.setString(2, keepSessionId);
                ps.executeUpdate();
            }
            return live;
        });
    }

    /** Deletes every expired session; how many. */
    public int deleteExpired() throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * The {@code sid} cookie header value ({@code docs/auth.md#sessions}): {@code HttpOnly},
     * {@code SameSite=Lax}, {@code Path=/}, and {@code Secure} when {@code secure}.
     */
    public static String cookie(String value, long maxAgeSeconds, boolean secure) {
        return ActorResolver.SESSION_COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
    }

    /** The {@code sid} cookie for a live session token, valid for {@link #LIFETIME_MS}. */
    public static String cookie(String token, boolean secure) {
        return cookie(token, LIFETIME_MS / 1000, secure);
    }

    /** Deletes the session with this id; whether it existed. */
    public boolean delete(String sessionId) throws SQLException {
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Deletes the session identified by {@code token}, if any (a malformed token is ignored). */
    public boolean deleteByToken(String token) throws SQLException {
        if (!Tokens.isWellFormed(token)) {
            return false;
        }
        String hash = Tokens.hash(token);
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
                ps.setString(1, hash);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return null;
        }
        if (userAgent.length() <= USER_AGENT_MAX) {
            return userAgent;
        }
        int end = USER_AGENT_MAX;
        if (Character.isHighSurrogate(userAgent.charAt(end - 1))) {
            end--;
        }
        return userAgent.substring(0, end);
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
