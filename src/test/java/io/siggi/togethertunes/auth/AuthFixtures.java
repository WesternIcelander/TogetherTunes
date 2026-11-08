package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Inserts users, sessions and guests straight into a migrated database, for auth tests. */
final class AuthFixtures {
    private static final long DAY = 86_400_000L;

    private final Database db;
    private final long now;

    AuthFixtures(Database db, long now) {
        this.db = db;
        this.now = now;
    }

    String user(String username, boolean disabled) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, username);
                ps.setString(3, username);
                ps.setLong(4, now - DAY);
                ps.setInt(5, disabled ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /** A session row; returns its token. */
    String session(String userId, String kind, long expiresAt, String[] idOut) throws SQLException {
        String token = Tokens.newToken();
        String id = UuidV7.newId();
        if (idOut != null) {
            idOut[0] = id;
        }
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sessions (id, user_id, token_hash, kind, created_at, last_seen_at, expires_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, userId);
                ps.setString(3, Tokens.hash(token));
                ps.setString(4, kind);
                ps.setLong(5, now - DAY);
                ps.setLong(6, now - DAY);
                ps.setLong(7, expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
        return token;
    }

    String guest(String kind, String keyHash, boolean banned) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at, banned)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, keyHash);
                ps.setString(4, "Quiet Walrus " + id.substring(30));
                ps.setLong(5, now - DAY);
                ps.setLong(6, now - DAY);
                ps.setInt(7, banned ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }
}
