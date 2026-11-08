package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * User accounts as the admin pages see them: listing, display names, disabling, and admin-set
 * temporary passwords ({@code docs/auth.md#local-passwords}). Account creation stays in
 * {@link Accounts}, role assignment in {@link Roles}.
 *
 * <ul>
 *   <li><b>Disabling</b> a user deletes all their sessions (they are logged out everywhere) and
 *       keeps them from logging in ({@code 403 account_disabled}). Users can't disable themselves
 *       ({@code 409 cannot_disable_self}), and the last enabled user holding
 *       {@value BuiltinRoles#ADMIN} can't be disabled ({@code 409 last_admin}).</li>
 *   <li><b>Temporary password:</b> replaces the user's local password (creating one if the account
 *       had none), marks it {@code must_change}, and deletes all their sessions except
 *       {@code keepSessionId} (the admin's own, when admins reset their own password). The user
 *       logs in with it and is asked to choose a new one ({@link Accounts#changePassword}).</li>
 * </ul>
 *
 * <p>{@link UserView} has no IP field ({@code docs/auth.md#ip-address-policy}). Auditing is left to
 * the admin API, which knows the acting user.
 */
public final class Users {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    private final Database db;
    private final Passwords passwords;
    private final Clock clock;

    /** A role as listed with a user. */
    public record RoleRef(String id, String name) {
    }

    /**
     * A user for the admin pages.
     *
     * @param email              {@code null} if unknown
     * @param hasPassword        whether the account has a local password
     * @param mustChangePassword whether that password is a temporary one set by an admin
     * @param roles              in {@link Roles#list} order
     */
    public record UserView(String id, String username, String displayName, String email, long createdAt,
                           boolean disabled, boolean hasPassword, boolean mustChangePassword, List<RoleRef> roles) {
        public UserView {
            roles = List.copyOf(roles);
        }
    }

    /** A page of users; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<UserView> items, String nextCursor) {
    }

    public Users(Database db, Passwords passwords, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Users ordered by username (ignoring case). {@code query} (optional) matches part of the
     * username or display name, ignoring case. {@code cursor} is the previous page's
     * {@code nextCursor}.
     */
    public Page list(String query, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
        List<String> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String pattern = Dialect.containsPattern(query.strip());
            sql.append(" AND (").append(d.containsIgnoreCase("username"))
                    .append(" OR ").append(d.containsIgnoreCase("display_name"))
                    .append(")");
            params.add(pattern);
            params.add(pattern);
        }
        if (cursor != null) {
            sql.append(" AND lower(u.username) > ?");
            params.add(cursor);
        }
        sql.append(" ORDER BY lower(u.username), u.id LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<UserView> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(row(rs, List.of()));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                next = items.get(limit - 1).username().toLowerCase(Locale.ROOT);
            }
            return new Page(withRoles(c, items), next);
        });
    }

    /** One user; {@code 404 user_not_found} if there is none with this id. */
    public UserView get(String id) throws SQLException {
        return db.read(c -> find(c, id));
    }

    /** Changes a user's display name (blank: the username). */
    public UserView setDisplayName(String id, String displayName) throws SQLException {
        return db.write(c -> {
            UserView user = find(c, id);
            String name = Accounts.validDisplayName(displayName, user.username());
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET display_name = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return find(c, id);
        });
    }

    /**
     * Disables or re-enables a user (see the class comment).
     *
     * @param actorUserId the acting user, who can't disable themselves
     */
    public UserView setDisabled(String actorUserId, String id, boolean disabled) throws SQLException {
        return db.write(c -> {
            BuiltinRoles.lockAdminRow(c);
            UserView user = find(c, id);
            if (user.disabled() == disabled) {
                return user;
            }
            if (disabled) {
                if (id.equals(actorUserId)) {
                    throw new HttpError(409, "cannot_disable_self", "You can't disable your own account");
                }
                if (isLastEnabledAdmin(c, id)) {
                    throw new HttpError(409, "last_admin", "The last administrator can't be disabled");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = ? WHERE id = ?")) {
                ps.setInt(1, disabled ? 1 : 0);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            if (disabled) {
                deleteSessions(c, id, null);
            }
            return find(c, id);
        });
    }

    /**
     * Sets a temporary password (see the class comment); {@code 400 invalid_password} if it breaks
     * {@link Passwords#problem}.
     *
     * @param keepSessionId a session of the user not to delete, or {@code null}
     * @return how many of the user's sessions were deleted
     */
    public int setTemporaryPassword(String id, String password, String keepSessionId) throws SQLException {
        String problem = Passwords.problem(password);
        if (problem != null) {
            throw new HttpError(400, "invalid_password", problem);
        }
        get(id); // 404 before the slow hash
        String hash = passwords.hash(password);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            find(c, id);
            String sql = db.dialect().upsert("password_credentials", List.of("user_id", "hash", "updated_at", "must_change"),
                    List.of("user_id"), List.of("hash", "updated_at", "must_change"));
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, hash);
                ps.setLong(3, now);
                ps.setInt(4, 1);
                ps.executeUpdate();
            }
            return deleteSessions(c, id, keepSessionId);
        });
    }

    // ------------------------------------------------------------- internals

    private static final String SELECT = "SELECT u.id, u.username, u.display_name, u.email, u.created_at, u.disabled,"
            + " p.user_id, p.must_change FROM users u LEFT JOIN password_credentials p ON p.user_id = u.id";

    private static UserView row(ResultSet rs, List<RoleRef> roles) throws SQLException {
        return new UserView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getInt(6) != 0, rs.getString(7) != null, rs.getInt(8) != 0, roles);
    }

    private static UserView find(Connection c, String id) throws SQLException {
        if (id != null) {
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE u.id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return withRoles(c, List.of(row(rs, List.of()))).get(0);
                    }
                }
            }
        }
        throw new HttpError(404, "user_not_found", "No such user");
    }

    /** The users with their roles filled in, in {@link Roles#list} order. */
    private static List<UserView> withRoles(Connection c, List<UserView> users) throws SQLException {
        if (users.isEmpty()) {
            return users;
        }
        Map<String, List<RoleRef>> byUser = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ur.user_id, ur.role_id FROM user_roles ur WHERE ur.user_id IN (");
        for (int i = 0; i < users.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        Map<String, Roles.Role> roles = new HashMap<>();
        List<Roles.Role> ordered = Roles.all(c);
        for (Roles.Role r : ordered) {
            roles.put(r.id(), r);
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < users.size(); i++) {
                ps.setString(i + 1, users.get(i).id());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Roles.Role r = roles.get(rs.getString(2));
                    if (r != null) {
                        byUser.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new RoleRef(r.id(), r.name()));
                    }
                }
            }
        }
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i).id(), i);
        }
        List<UserView> out = new ArrayList<>();
        for (UserView u : users) {
            List<RoleRef> list = new ArrayList<>(byUser.getOrDefault(u.id(), Collections.emptyList()));
            list.sort((a, b) -> Integer.compare(rank.get(a.id()), rank.get(b.id())));
            out.add(new UserView(u.id(), u.username(), u.displayName(), u.email(), u.createdAt(), u.disabled(),
                    u.hasPassword(), u.mustChangePassword(), list));
        }
        return out;
    }

    /** Whether {@code userId} is enabled, holds admin, and no other enabled user does. */
    private static boolean isLastEnabledAdmin(Connection c, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id JOIN users u ON u.id = ur.user_id"
                        + " WHERE r.builtin = 1 AND r.name = ? AND u.disabled = 0")) {
            ps.setString(1, BuiltinRoles.ADMIN);
            boolean holds = false;
            boolean other = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1).equals(userId)) {
                        holds = true;
                    } else {
                        other = true;
                    }
                }
            }
            return holds && !other;
        }
    }

    /** Deletes the user's sessions except {@code keepSessionId}; how many. */
    private static int deleteSessions(Connection c, String userId, String keepSessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(keepSessionId == null
                ? "DELETE FROM sessions WHERE user_id = ?" : "DELETE FROM sessions WHERE user_id = ? AND id <> ?")) {
            ps.setString(1, userId);
            if (keepSessionId != null) {
                ps.setString(2, keepSessionId);
            }
            return ps.executeUpdate();
        }
    }
}
