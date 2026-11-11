package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.Hook;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.Settings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Permission checks ({@code docs/auth.md#roles-and-permissions}).
 *
 * <p>A user's permissions are the <b>union</b> of their roles' permissions; the built-in
 * {@value BuiltinRoles#ADMIN} role holds every {@link Permission}. Guests, anonymous actors and
 * disabled accounts hold none (guests act through the guest policy settings instead).
 *
 * <p><b>Feature switches first:</b> a permission that belongs to a {@link Feature} grants nothing
 * while that feature is switched off, to anyone, admins included. {@link #require} checks in this
 * order: feature ({@code 403 feature_disabled}), then authentication ({@code 401
 * not_authenticated}), then the permission ({@code 403 forbidden}).
 *
 * <p>Nothing is cached: every check reads the database, so a role change applies to the next
 * request.
 */
public final class Permissions {
    private final Database db;
    private final Settings settings;

    public Permissions(Database db, Settings settings) {
        this.db = Objects.requireNonNull(db);
        this.settings = Objects.requireNonNull(settings);
    }

    /**
     * The permissions a user holds through their roles, ignoring feature switches (what the admin
     * UI shows). Empty for an unknown or disabled user.
     */
    public Set<Permission> held(String userId) throws SQLException {
        Objects.requireNonNull(userId);
        return db.read(c -> held(c, userId));
    }

    /** {@link #held(String)} for a user actor; empty for guests and anonymous actors. */
    public Set<Permission> held(Actor actor) throws SQLException {
        return actor instanceof Actor.User user ? held(user.userId()) : EnumSet.noneOf(Permission.class);
    }

    static Set<Permission> held(Connection c, String userId) throws SQLException {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.name, r.builtin, rp.permission FROM user_roles ur"
                        + " JOIN users u ON u.id = ur.user_id"
                        + " JOIN roles r ON r.id = ur.role_id"
                        + " LEFT JOIN role_permissions rp ON rp.role_id = r.id"
                        + " WHERE ur.user_id = ? AND u.disabled = 0")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (BuiltinRoles.isAdmin(rs.getString(1), rs.getInt(2) != 0)) {
                        return Permission.all();
                    }
                    String permission = rs.getString(3);
                    if (permission != null) {
                        // Unknown names (e.g. after a downgrade) grant nothing.
                        Permission.fromWireName(permission).ifPresent(out::add);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether {@code actor} is an admin: an enabled user holding the built-in admin role (not
     * merely every permission). For the few admin-only actions, such as Delete record.
     */
    public boolean isAdmin(Actor actor) throws SQLException {
        if (!(actor instanceof Actor.User user)) {
            return false;
        }
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT r.name, r.builtin FROM user_roles ur"
                    + " JOIN users u ON u.id = ur.user_id JOIN roles r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = ? AND u.disabled = 0")) {
                ps.setString(1, user.userId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (BuiltinRoles.isAdmin(rs.getString(1), rs.getInt(2) != 0)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    /**
     * A per-route hook letting only admins ({@link #isAdmin}) through: {@code 401
     * not_authenticated} for guests and anonymous callers, {@code 403 forbidden} for other users.
     */
    public Hook adminHook() {
        return (request, response, next) -> {
            Actor actor = ActorResolver.actor(request);
            if (!(actor instanceof Actor.User)) {
                throw notAuthenticated();
            }
            if (!isAdmin(actor)) {
                throw HttpError.forbidden("Only administrators can do this");
            }
            next.handle(request, response);
        };
    }

    /** Whether a feature is switched on. */
    public boolean enabled(Feature feature) throws SQLException {
        return settings.get(feature.setting());
    }

    /**
     * The permissions that are in effect for {@code actor}: those it holds, minus those whose
     * feature is switched off.
     */
    public Set<Permission> effective(Actor actor) throws SQLException {
        Set<Permission> held = held(actor);
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (Permission p : held) {
            if (p.feature() == null || enabled(p.feature())) {
                out.add(p);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Whether {@code actor} may use {@code permission} now (its feature is on, and it holds it). */
    public boolean allowed(Actor actor, Permission permission) throws SQLException {
        Objects.requireNonNull(permission);
        if (permission.feature() != null && !enabled(permission.feature())) {
            return false;
        }
        return held(actor).contains(permission);
    }

    /** Throws {@code 403 feature_disabled} unless {@code feature} is on. */
    public void requireFeature(Feature feature) throws SQLException {
        if (!enabled(feature)) {
            throw featureDisabled(feature);
        }
    }

    /**
     * Throws unless {@code actor} may use {@code permission}: {@code 403 feature_disabled} if its
     * feature is off (checked first, so admins get it too), {@code 401 not_authenticated} for a
     * guest or anonymous actor, {@code 403 forbidden} if the user lacks the permission.
     */
    public void require(Actor actor, Permission permission) throws SQLException {
        Objects.requireNonNull(permission);
        if (permission.feature() != null) {
            requireFeature(permission.feature());
        }
        if (!(actor instanceof Actor.User user)) {
            throw notAuthenticated();
        }
        if (!held(user.userId()).contains(permission)) {
            throw HttpError.forbidden("Missing permission " + permission.wireName());
        }
    }

    /** A per-route hook that runs {@link #require} for the request's actor. */
    public Hook hook(Permission permission) {
        Objects.requireNonNull(permission);
        return (request, response, next) -> {
            require(ActorResolver.actor(request), permission);
            next.handle(request, response);
        };
    }

    /** A per-route hook that runs {@link #requireFeature}. */
    public Hook hook(Feature feature) {
        Objects.requireNonNull(feature);
        return (request, response, next) -> {
            requireFeature(feature);
            next.handle(request, response);
        };
    }

    static HttpError featureDisabled(Feature feature) {
        return new HttpError(403, "feature_disabled", feature.disabledMessage());
    }

    static HttpError notAuthenticated() {
        return new HttpError(401, "not_authenticated", "Log in first");
    }
}
