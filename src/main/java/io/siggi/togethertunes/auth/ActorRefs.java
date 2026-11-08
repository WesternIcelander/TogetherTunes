package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Looks up the {@link ActorRef} of a user or guest id. Reads only ids and display names (never an
 * IP column). Rows that join {@code users}/{@code guests} themselves can use
 * {@link ActorRef#fromColumns} instead.
 */
public final class ActorRefs {
    private final Database db;

    public ActorRefs(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    /** The actor of a request; {@code null} for anonymous, or if the user or guest no longer exists. */
    public ActorRef of(Actor actor) throws SQLException {
        return switch (actor) {
            case Actor.User user -> user(user.userId());
            case Actor.Guest guest -> guest(guest.guestId());
            case Actor.Anonymous ignored -> null;
        };
    }

    /** A user (disabled ones too, since history still shows them); {@code null} if unknown. */
    public ActorRef user(String userId) throws SQLException {
        return db.read(c -> user(c, userId));
    }

    /** A guest (banned ones too); {@code null} if unknown. */
    public ActorRef guest(String guestId) throws SQLException {
        return db.read(c -> guest(c, guestId));
    }

    /** {@link #user(String)} inside a running transaction. */
    public static ActorRef user(Connection c, String userId) throws SQLException {
        String name = name(c, "SELECT display_name FROM users WHERE id = ?", userId);
        return name == null ? null : ActorRef.user(userId, name);
    }

    /** {@link #guest(String)} inside a running transaction. */
    public static ActorRef guest(Connection c, String guestId) throws SQLException {
        String name = name(c, "SELECT display_name FROM guests WHERE id = ?", guestId);
        return name == null ? null : ActorRef.guest(guestId, name);
    }

    private static String name(Connection c, String sql, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
