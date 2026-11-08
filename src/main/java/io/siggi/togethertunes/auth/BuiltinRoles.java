package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * The two built-in roles ({@code docs/auth.md#roles-and-permissions}): {@value #ADMIN} (every
 * permission, immutable) and {@value #USER} (the default role of new accounts). Their rows are
 * created on startup by {@link #ensure}, with UUIDv7 ids like every other row, so a role is
 * always looked up by name.
 *
 * <p>{@value #ADMIN} has no {@code role_permissions} rows: it holds every {@link Permission} by
 * definition ({@link Permissions}). {@value #USER} starts with {@link #USER_DEFAULT_PERMISSIONS}
 * when its row is first created; admins may change them later (an existing row is never
 * touched again).
 */
public final class BuiltinRoles {
    /** The administrator role, given to the first account. */
    public static final String ADMIN = "admin";
    /** The default role, given to every new account. */
    public static final String USER = "user";

    /** The permissions the {@value #USER} role starts with. */
    public static final Set<Permission> USER_DEFAULT_PERMISSIONS = Set.of(
            Permission.LIBRARY_UPLOAD, Permission.LIBRARY_YOUTUBE, Permission.ROOMS_CREATE);

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
            String userId = UuidV7.newId();
            if (insert(ps, userId, USER, true)) {
                try (PreparedStatement perm = c.prepareStatement(
                        "INSERT INTO role_permissions (role_id, permission) VALUES (?, ?)")) {
                    for (Permission p : USER_DEFAULT_PERMISSIONS) {
                        perm.setString(1, userId);
                        perm.setString(2, p.wireName());
                        perm.executeUpdate();
                    }
                }
            }
        }
    }

    /** Whether a {@code roles} row is the built-in admin role. */
    static boolean isAdmin(String name, boolean builtin) {
        return builtin && ADMIN.equals(name);
    }

    /**
     * Locks the {@value #ADMIN} role row until the transaction ends (a row lock on PostgreSQL;
     * SQLite has a single writer anyway). Serialises account creation, role name changes and
     * admin grants, so concurrent requests can't both pass a "first account" or "last admin"
     * check.
     */
    static void lockAdminRow(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE roles SET builtin = 1 WHERE name = ? AND builtin = 1")) {
            ps.setString(1, ADMIN);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("The built-in admin role is missing (BuiltinRoles.ensure not run)");
            }
        }
    }

    private static void insert(PreparedStatement ps, String name, boolean isDefault) throws SQLException {
        insert(ps, UuidV7.newId(), name, isDefault);
    }

    /** Returns whether the row was inserted (false if it already existed). */
    private static boolean insert(PreparedStatement ps, String id, String name, boolean isDefault) throws SQLException {
        ps.setString(1, id);
        ps.setString(2, name);
        ps.setInt(3, 1);
        ps.setInt(4, isDefault ? 1 : 0);
        return ps.executeUpdate() == 1;
    }
}
