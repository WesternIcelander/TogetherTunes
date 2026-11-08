package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Roles and role assignment ({@code docs/auth.md#roles-and-permissions}): the service behind the
 * admin role pages.
 *
 * <ul>
 *   <li>{@value BuiltinRoles#ADMIN} holds every permission and can't be edited at all: not
 *       renamed, not deleted, no permission or default-flag changes ({@code 409 role_immutable}).
 *       It can still be granted to and taken from users.</li>
 *   <li>{@value BuiltinRoles#USER} can't be renamed or deleted ({@code 409 role_builtin}); its
 *       permissions and its default flag can be changed.</li>
 *   <li>Custom roles can be created, renamed, given any permissions, made default and deleted
 *       (deleting takes the role from its users).</li>
 *   <li>Default roles ({@code is_default}) are granted to every new account.</li>
 *   <li>The last enabled user holding {@value BuiltinRoles#ADMIN} can't lose it
 *       ({@code 409 last_admin}), so the server always keeps an administrator.</li>
 * </ul>
 *
 * <p><b>Role names:</b> surrounding whitespace removed, inner whitespace collapsed to one space,
 * 1–{@value #NAME_MAX} characters from letters, digits, space, {@code . _ -}, starting with a letter
 * or digit; unique case-insensitively ({@code 409 role_name_taken}). Changes that need these
 * checks lock the admin role row ({@link BuiltinRoles#lockAdminRow}), so they are serialised.
 *
 * <p>Errors are {@link HttpError}s with messages meant for the admin UI. Auditing is left to the
 * callers (the admin API), which know the acting user and request.
 */
public final class Roles {
    public static final int NAME_MAX = 32;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._-]*");

    private final Database db;

    /**
     * A role.
     *
     * @param permissions the permissions it grants, in {@link Permission} order (every one for
     *                    {@value BuiltinRoles#ADMIN})
     */
    public record Role(String id, String name, boolean builtin, boolean isDefault, List<Permission> permissions) {
        public Role {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            permissions = List.copyOf(permissions);
        }

        /** Whether this is the built-in {@value BuiltinRoles#ADMIN} role. */
        public boolean isAdmin() {
            return BuiltinRoles.isAdmin(name, builtin);
        }

        /** Whether its permissions and default flag can be changed (all but admin). */
        public boolean editable() {
            return !isAdmin();
        }
    }

    public Roles(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    // ------------------------------------------------------------- reading

    /** Every role: {@value BuiltinRoles#ADMIN}, {@value BuiltinRoles#USER}, then the rest by name. */
    public List<Role> list() throws SQLException {
        return db.read(Roles::all);
    }

    /** One role; {@code 404 role_not_found} if there is none with this id. */
    public Role get(String id) throws SQLException {
        return db.read(c -> find(c, id));
    }

    /** A user's roles, in {@link #list} order; {@code 404 user_not_found} for an unknown user. */
    public List<Role> userRoles(String userId) throws SQLException {
        return db.read(c -> {
            requireUser(c, userId);
            Set<String> ids = userRoleIds(c, userId);
            List<Role> out = new ArrayList<>();
            for (Role r : all(c)) {
                if (ids.contains(r.id())) {
                    out.add(r);
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------- changing roles

    /** Creates a custom role. */
    public Role create(String name, Collection<Permission> permissions, boolean isDefault) throws SQLException {
        String clean = normalizeName(name);
        Set<Permission> perms = copy(permissions);
        return db.write(c -> {
            BuiltinRoles.lockAdminRow(c);
            requireNameFree(c, clean, null);
            String id = UuidV7.newId();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO roles (id, name, builtin, is_default) VALUES (?, ?, 0, ?)")) {
                ps.setString(1, id);
                ps.setString(2, clean);
                ps.setInt(3, isDefault ? 1 : 0);
                ps.executeUpdate();
            }
            writePermissions(c, id, perms);
            return find(c, id);
        });
    }

    /** Renames a custom role. */
    public Role rename(String id, String name) throws SQLException {
        String clean = normalizeName(name);
        return db.write(c -> {
            BuiltinRoles.lockAdminRow(c);
            Role role = find(c, id);
            requireMutable(role);
            if (role.builtin()) {
                throw new HttpError(409, "role_builtin", "A built-in role can't be renamed");
            }
            requireNameFree(c, clean, id);
            try (PreparedStatement ps = c.prepareStatement("UPDATE roles SET name = ? WHERE id = ?")) {
                ps.setString(1, clean);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return find(c, id);
        });
    }

    /** Sets whether new accounts get this role. */
    public Role setDefault(String id, boolean isDefault) throws SQLException {
        return db.write(c -> {
            requireMutable(find(c, id));
            try (PreparedStatement ps = c.prepareStatement("UPDATE roles SET is_default = ? WHERE id = ?")) {
                ps.setInt(1, isDefault ? 1 : 0);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return find(c, id);
        });
    }

    /** Replaces the role's permissions. */
    public Role setPermissions(String id, Collection<Permission> permissions) throws SQLException {
        Set<Permission> perms = copy(permissions);
        return db.write(c -> {
            requireMutable(find(c, id));
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM role_permissions WHERE role_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            writePermissions(c, id, perms);
            return find(c, id);
        });
    }

    /** Deletes a custom role; its users lose it. */
    public void delete(String id) throws SQLException {
        db.write(c -> {
            Role role = find(c, id);
            requireMutable(role);
            if (role.builtin()) {
                throw new HttpError(409, "role_builtin", "A built-in role can't be deleted");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM roles WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------- assigning roles

    /**
     * Replaces a user's roles with {@code roleIds} (duplicates ignored). {@code 404
     * user_not_found}, {@code 400 unknown_role} for an id that isn't a role, {@code 409
     * last_admin} if this would leave no enabled user holding {@value BuiltinRoles#ADMIN}.
     *
     * @return the user's roles afterwards, in {@link #list} order
     */
    public List<Role> setUserRoles(String userId, Collection<String> roleIds) throws SQLException {
        Set<String> wanted = new LinkedHashSet<>(roleIds);
        return db.write(c -> {
            BuiltinRoles.lockAdminRow(c);
            requireUser(c, userId);
            List<Role> all = all(c);
            Map<String, Role> byId = new HashMap<>();
            for (Role r : all) {
                byId.put(r.id(), r);
            }
            for (String id : wanted) {
                if (id == null || !byId.containsKey(id)) {
                    throw new HttpError(400, "unknown_role", "No such role: " + id);
                }
            }
            Role admin = all.stream().filter(Role::isAdmin).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The built-in admin role is missing"));
            Set<String> current = userRoleIds(c, userId);
            if (current.contains(admin.id()) && !wanted.contains(admin.id()) && !otherEnabledAdminExists(c, admin.id(), userId)) {
                throw new HttpError(409, "last_admin", "The last administrator can't lose the admin role");
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM user_roles WHERE user_id = ? AND role_id = ?")) {
                for (String id : current) {
                    if (!wanted.contains(id)) {
                        del.setString(1, userId);
                        del.setString(2, id);
                        del.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)")) {
                for (String id : wanted) {
                    if (!current.contains(id)) {
                        ins.setString(1, userId);
                        ins.setString(2, id);
                        ins.executeUpdate();
                    }
                }
            }
            List<Role> out = new ArrayList<>();
            for (Role r : all) {
                if (wanted.contains(r.id())) {
                    out.add(r);
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------- parsing

    /**
     * Parses permission wire names (as sent by the admin API); {@code 400 unknown_permission}
     * for a name that isn't a {@link Permission}.
     */
    public static Set<Permission> parsePermissions(Collection<String> names) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String name : names) {
            out.add(Permission.fromWireName(name).orElseThrow(
                    () -> new HttpError(400, "unknown_permission", "Unknown permission: " + name)));
        }
        return out;
    }

    /**
     * The canonical form of a role name; {@code 400 invalid_role_name} if it breaks the rules
     * (see the class comment).
     */
    public static String normalizeName(String name) {
        if (name == null) {
            throw new HttpError(400, "invalid_role_name", "A role needs a name");
        }
        String clean = name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > NAME_MAX || !NAME.matcher(clean).matches()) {
            throw new HttpError(400, "invalid_role_name", "Role names are 1–" + NAME_MAX
                    + " letters, digits, spaces, '.', '_' or '-', starting with a letter or digit");
        }
        return clean;
    }

    // ------------------------------------------------------------- internals

    private static Set<Permission> copy(Collection<Permission> permissions) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (Permission p : permissions) {
            out.add(Objects.requireNonNull(p));
        }
        return out;
    }

    private static void requireMutable(Role role) {
        if (role.isAdmin()) {
            throw new HttpError(409, "role_immutable", "The admin role holds every permission and can't be changed");
        }
    }

    private static void requireNameFree(Connection c, String name, String exceptId) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name FROM roles");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!rs.getString(1).equals(exceptId) && rs.getString(2).toLowerCase(Locale.ROOT).equals(lower)) {
                    throw new HttpError(409, "role_name_taken", "A role with this name already exists");
                }
            }
        }
    }

    private static void requireUser(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new HttpError(404, "user_not_found", "No such user");
                }
            }
        }
    }

    private static Set<String> userRoleIds(Connection c, String userId) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT role_id FROM user_roles WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static boolean otherEnabledAdminExists(Connection c, String adminRoleId, String exceptUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM user_roles ur JOIN users u ON u.id = ur.user_id"
                        + " WHERE ur.role_id = ? AND ur.user_id <> ? AND u.disabled = 0 LIMIT 1")) {
            ps.setString(1, adminRoleId);
            ps.setString(2, exceptUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void writePermissions(Connection c, String roleId, Set<Permission> perms) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO role_permissions (role_id, permission) VALUES (?, ?)")) {
            for (Permission p : perms) {
                ps.setString(1, roleId);
                ps.setString(2, p.wireName());
                ps.executeUpdate();
            }
        }
    }

    private static Role find(Connection c, String id) throws SQLException {
        if (id != null) {
            for (Role r : all(c)) {
                if (r.id().equals(id)) {
                    return r;
                }
            }
        }
        throw new HttpError(404, "role_not_found", "No such role");
    }

    static List<Role> all(Connection c) throws SQLException {
        Map<String, Set<Permission>> perms = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT role_id, permission FROM role_permissions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String roleId = rs.getString(1);
                // Unknown names (e.g. after a downgrade) are ignored.
                Permission.fromWireName(rs.getString(2)).ifPresent(p ->
                        perms.computeIfAbsent(roleId, k -> EnumSet.noneOf(Permission.class)).add(p));
            }
        }
        List<Role> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, name, builtin, is_default FROM roles");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                String name = rs.getString(2);
                boolean builtin = rs.getInt(3) != 0;
                Set<Permission> p = BuiltinRoles.isAdmin(name, builtin) ? Permission.all()
                        : perms.getOrDefault(id, Collections.emptySet());
                out.add(new Role(id, name, builtin, rs.getInt(4) != 0, new ArrayList<>(p)));
            }
        }
        out.sort(ORDER);
        return out;
    }

    private static int rank(Role r) {
        if (r.isAdmin()) {
            return 0;
        }
        return r.builtin() && BuiltinRoles.USER.equals(r.name()) ? 1 : 2;
    }

    private static final Comparator<Role> ORDER = Comparator.comparingInt(Roles::rank)
            .thenComparing(r -> r.name().toLowerCase(Locale.ROOT))
            .thenComparing(Role::name);
}
