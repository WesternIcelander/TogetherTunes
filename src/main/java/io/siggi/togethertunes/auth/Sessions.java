package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.Objects;

/**
 * Login sessions ({@code docs/auth.md#sessions}): opaque {@link Tokens} whose SHA-256 is stored
 * in {@code sessions.token_hash}. {@link ActorResolver} looks them up.
 *
 * <p>So far this creates {@code cookie} sessions (login, registration) and deletes sessions
 * (logout). Sliding expiry, bearer tokens and the session list come with the Sessions step.
 */
public final class Sessions {
    /** Lifetime of a session; the Sessions step makes it slide. */
    public static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;
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
