package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * The two built-in roles ({@code docs/auth.md#roles-and-permissions}): {@value #ADMIN} (every
 * permission, immutable) and {@value #USER} (the default role of new accounts). Their rows are
 * created on startup by {@link #ensure}, with UUIDv7 ids like every other row, so a role is
 * always looked up by name.
 */
public final class BuiltinRoles {
    /** The administrator role, given to the first account. */
    public static final String ADMIN = "admin";
    /** The default role, given to every new account. */
    public static final String USER = "user";

    private BuiltinRoles() {
    }

    /** Creates the built-in role rows that are missing. Idempotent; safe with concurrent starts. */
    public static void ensure(Database db) throws SQLException {
        db.write(c -> {
            ensure(c, db);
            return null;
        });
    }

    static void ensure(Connection c, Database db) throws SQLException {
        String sql = db.dialect().insertOrIgnore("roles", List.of("id", "name", "builtin", "is_default"), List.of("name"));
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            insert(ps, ADMIN, false);
            insert(ps, USER, true);
        }
    }

    private static void insert(PreparedStatement ps, String name, boolean isDefault) throws SQLException {
        ps.setString(1, UuidV7.newId());
        ps.setString(2, name);
        ps.setInt(3, 1);
        ps.setInt(4, isDefault ? 1 : 0);
        ps.executeUpdate();
    }
}
