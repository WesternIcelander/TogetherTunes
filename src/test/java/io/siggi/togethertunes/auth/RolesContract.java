package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link Roles} and {@link Permissions} against a migrated database, in both dialects. */
abstract class RolesContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    protected Roles roles;
    protected Settings settings;
    protected Permissions permissions;
    protected Accounts accounts;
    protected AuthFixtures fixtures;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        roles = new Roles(db);
        settings = new Settings(db);
        permissions = new Permissions(db, settings);
        accounts = new Accounts(db, AccountsContract.CHEAP, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        fixtures = new AuthFixtures(db, NOW);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static void assertError(int status, String code, Executable call) {
        HttpError e = assertThrows(HttpError.class, call);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
    }

    private Roles.Role role(String name) throws SQLException {
        return roles.list().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private Actor.User actor(String userId) {
        return new Actor.User(userId, "session", Actor.Via.COOKIE);
    }

    private Accounts.Account account(String username) throws SQLException {
        return accounts.createLocal(username, null, "0123456789", false, null, AuthApi.AUDIT_REGISTER);
    }

    // ------------------------------------------------------------------ built-in roles

    @Test
    void builtinRoles() throws SQLException {
        List<Roles.Role> all = roles.list();
        assertEquals(List.of("admin", "user"), all.stream().map(Roles.Role::name).toList());
        Roles.Role admin = all.get(0);
        assertTrue(admin.isAdmin());
        assertTrue(admin.builtin());
        assertFalse(admin.isDefault());
        assertFalse(admin.editable());
        assertEquals(List.of(Permission.values()), admin.permissions());
        Roles.Role user = all.get(1);
        assertFalse(user.isAdmin());
        assertTrue(user.builtin());
        assertTrue(user.isDefault());
        assertTrue(user.editable());
        assertEquals(List.of(Permission.LIBRARY_UPLOAD, Permission.LIBRARY_YOUTUBE, Permission.ROOMS_CREATE),
                user.permissions());
        assertEquals(admin, roles.get(admin.id()));
    }

    @Test
    void userRoleDefaultsAreSeededOnlyOnce() throws SQLException {
        Roles.Role user = role("user");
        roles.setPermissions(user.id(), Set.of(Permission.ROOMS_CREATE));
        BuiltinRoles.ensure(db);
        assertEquals(List.of(Permission.ROOMS_CREATE), roles.get(user.id()).permissions());
    }

    @Test
    void adminRoleIsImmutable() throws SQLException {
        String admin = role("admin").id();
        assertError(409, "role_immutable", () -> roles.rename(admin, "boss"));
        assertError(409, "role_immutable", () -> roles.delete(admin));
        assertError(409, "role_immutable", () -> roles.setPermissions(admin, Set.of()));
        assertError(409, "role_immutable", () -> roles.setDefault(admin, true));
        assertEquals(role("admin"), roles.get(admin));
    }

    @Test
    void userRoleCanBeEditedButNotRenamedOrDeleted() throws SQLException {
        String user = role("user").id();
        assertError(409, "role_builtin", () -> roles.rename(user, "member"));
        assertError(409, "role_builtin", () -> roles.delete(user));
        assertEquals(List.of(Permission.IP_VIEW), roles.setPermissions(user, Set.of(Permission.IP_VIEW)).permissions());
        assertFalse(roles.setDefault(user, false).isDefault());
        assertEquals("user", roles.get(user).name());
    }

    // ------------------------------------------------------------------ custom roles

    @Test
    void customRoleLifecycle() throws SQLException {
        Roles.Role mod = roles.create("  Room   Moderator ", List.of(Permission.ROOMS_MODERATE_ANY, Permission.IP_VIEW), false);
        assertEquals("Room Moderator", mod.name());
        assertFalse(mod.builtin());
        assertFalse(mod.isDefault());
        assertEquals(List.of(Permission.IP_VIEW, Permission.ROOMS_MODERATE_ANY), mod.permissions());
        roles.create("trusted", List.of(), true);
        assertEquals(List.of("admin", "user", "Room Moderator", "trusted"),
                roles.list().stream().map(Roles.Role::name).toList());

        assertEquals("Moderator", roles.rename(mod.id(), "Moderator").name());
        assertEquals(List.of(Permission.LIBRARY_PURGE),
                roles.setPermissions(mod.id(), EnumSet.of(Permission.LIBRARY_PURGE)).permissions());
        assertTrue(roles.setDefault(mod.id(), true).isDefault());

        String user = fixtures.user("someone", false);
        roles.setUserRoles(user, List.of(mod.id()));
        roles.delete(mod.id());
        assertError(404, "role_not_found", () -> roles.get(mod.id()));
        assertEquals(List.of(), roles.userRoles(user));
        assertEquals(Set.of(), permissions.held(user));
    }

    @Test
    void roleNamesAreValidatedAndUniqueIgnoringCase() throws SQLException {
        assertError(400, "invalid_role_name", () -> roles.create(null, List.of(), false));
        assertError(400, "invalid_role_name", () -> roles.create("   ", List.of(), false));
        assertError(400, "invalid_role_name", () -> roles.create("-dash", List.of(), false));
        assertError(400, "invalid_role_name", () -> roles.create("a/b", List.of(), false));
        assertError(400, "invalid_role_name", () -> roles.create("x".repeat(Roles.NAME_MAX + 1), List.of(), false));
        assertEquals("x".repeat(Roles.NAME_MAX), roles.create("x".repeat(Roles.NAME_MAX), List.of(), false).name());
        assertEquals("Größe 2.0_beta-1", roles.create("Größe 2.0_beta-1", List.of(), false).name());

        assertError(409, "role_name_taken", () -> roles.create("ADMIN", List.of(), false));
        assertError(409, "role_name_taken", () -> roles.create("User", List.of(), false));
        Roles.Role a = roles.create("Trusted", List.of(), false);
        assertError(409, "role_name_taken", () -> roles.create("trusted", List.of(), false));
        Roles.Role b = roles.create("Other", List.of(), false);
        assertError(409, "role_name_taken", () -> roles.rename(b.id(), "TRUSTED"));
        assertEquals("trusted", roles.rename(a.id(), "trusted").name()); // its own name, other case
    }

    @Test
    void unknownRolesAndUsers() throws SQLException {
        assertError(404, "role_not_found", () -> roles.get("nope"));
        assertError(404, "role_not_found", () -> roles.rename("nope", "x"));
        assertError(404, "role_not_found", () -> roles.setPermissions("nope", Set.of()));
        assertError(404, "role_not_found", () -> roles.setDefault("nope", true));
        assertError(404, "role_not_found", () -> roles.delete("nope"));
        assertError(404, "user_not_found", () -> roles.userRoles("nope"));
        assertError(404, "user_not_found", () -> roles.setUserRoles("nope", List.of()));
        String user = fixtures.user("someone", false);
        assertError(400, "unknown_role", () -> roles.setUserRoles(user, List.of("nope")));
    }

    @Test
    void parsePermissions() {
        assertEquals(EnumSet.of(Permission.IP_VIEW, Permission.LIBRARY_VIEW_ALL),
                Roles.parsePermissions(List.of("ip.view", "library.viewAll", "ip.view")));
        assertError(400, "unknown_permission", () -> Roles.parsePermissions(List.of("library.viewall")));
        assertError(400, "unknown_permission", () -> Roles.parsePermissions(List.of("everything")));
    }

    @Test
    void newAccountsGetTheDefaultRoles() throws SQLException {
        account("first"); // admin
        Roles.Role trusted = roles.create("trusted", List.of(Permission.LIBRARY_VIEW_ALL), true);
        Accounts.Account second = account("second");
        assertEquals(List.of("trusted", "user"), second.roles());
        assertEquals(List.of("user", "trusted"), roles.userRoles(second.id()).stream().map(Roles.Role::name).toList());
        roles.setDefault(role("user").id(), false);
        roles.setDefault(trusted.id(), false);
        assertEquals(List.of(), account("third").roles());
    }

    // ------------------------------------------------------------------ assignment

    @Test
    void setUserRolesReplacesTheSet() throws SQLException {
        account("first");
        String user = account("second").id();
        Roles.Role a = roles.create("a", List.of(), false);
        Roles.Role b = roles.create("b", List.of(), false);
        assertEquals(List.of("a", "b"), roles.setUserRoles(user, List.of(b.id(), a.id(), b.id()))
                .stream().map(Roles.Role::name).toList());
        assertEquals(List.of("admin", "user", "b"), roles.setUserRoles(user,
                List.of(b.id(), role("admin").id(), role("user").id())).stream().map(Roles.Role::name).toList());
        assertEquals(List.of("admin", "user", "b"), roles.userRoles(user).stream().map(Roles.Role::name).toList());
        assertEquals(List.of(), roles.setUserRoles(user, List.of()));
    }

    @Test
    void theLastAdminKeepsTheAdminRole() throws SQLException {
        Accounts.Account first = account("first");
        String admin = role("admin").id();
        String user = role("user").id();
        assertError(409, "last_admin", () -> roles.setUserRoles(first.id(), List.of(user)));
        assertTrue(permissions.held(first.id()).contains(Permission.ADMIN_ROLES));

        Accounts.Account second = account("second");
        roles.setUserRoles(second.id(), List.of(admin, user));
        // A disabled admin doesn't count.
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, second.id());
                ps.executeUpdate();
            }
            return null;
        });
        assertError(409, "last_admin", () -> roles.setUserRoles(first.id(), List.of(user)));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 0 WHERE id = ?")) {
                ps.setString(1, second.id());
                ps.executeUpdate();
            }
            return null;
        });
        roles.setUserRoles(first.id(), List.of(user));
        assertFalse(permissions.held(first.id()).contains(Permission.ADMIN_ROLES));
        assertError(409, "last_admin", () -> roles.setUserRoles(second.id(), List.of()));
        // Keeping it while changing other roles is fine.
        roles.setUserRoles(second.id(), List.of(admin));
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void permissionsAreTheUnionOfTheRoles() throws SQLException {
        String user = fixtures.user("someone", false);
        assertEquals(Set.of(), permissions.held(user));
        Roles.Role a = roles.create("a", List.of(Permission.LIBRARY_UPLOAD, Permission.IP_VIEW), false);
        Roles.Role b = roles.create("b", List.of(Permission.IP_VIEW, Permission.ROOMS_CREATE), false);
        Roles.Role empty = roles.create("empty", List.of(), false);
        roles.setUserRoles(user, List.of(a.id()));
        assertEquals(EnumSet.of(Permission.LIBRARY_UPLOAD, Permission.IP_VIEW), permissions.held(user));
        roles.setUserRoles(user, List.of(a.id(), b.id(), empty.id()));
        assertEquals(EnumSet.of(Permission.LIBRARY_UPLOAD, Permission.IP_VIEW, Permission.ROOMS_CREATE),
                permissions.held(user));
        // Changes apply at once (no cache).
        roles.setPermissions(b.id(), List.of(Permission.LIBRARY_PURGE));
        assertEquals(EnumSet.of(Permission.LIBRARY_UPLOAD, Permission.IP_VIEW, Permission.LIBRARY_PURGE),
                permissions.held(user));
        roles.setUserRoles(user, List.of(empty.id()));
        assertEquals(Set.of(), permissions.held(user));
    }

    @Test
    void adminHoldsEverything() throws SQLException {
        Accounts.Account admin = account("first");
        assertEquals(EnumSet.allOf(Permission.class), permissions.held(admin.id()));
        for (Permission p : Permission.values()) {
            if (p != Permission.OUTPUT_OVERLAY_VIDEO) { // overlay.enabled defaults to false
                permissions.require(actor(admin.id()), p);
            }
        }
    }

    @Test
    void defaultUserPermissions() throws SQLException {
        account("first");
        Accounts.Account user = account("second");
        assertEquals(EnumSet.of(Permission.LIBRARY_UPLOAD, Permission.LIBRARY_YOUTUBE, Permission.ROOMS_CREATE),
                permissions.held(user.id()));
        permissions.require(actor(user.id()), Permission.ROOMS_CREATE);
        assertError(403, "forbidden", () -> permissions.require(actor(user.id()), Permission.IP_VIEW));
        assertError(403, "forbidden", () -> permissions.require(actor(user.id()), Permission.ADMIN_SETTINGS));
    }

    @Test
    void disabledAndUnknownUsersHoldNothing() throws SQLException {
        String disabled = fixtures.user("gone", true);
        roles.setUserRoles(disabled, List.of(role("admin").id()));
        assertEquals(Set.of(), permissions.held(disabled));
        assertEquals(Set.of(), permissions.held("nobody"));
        assertError(403, "forbidden", () -> permissions.require(actor(disabled), Permission.ROOMS_CREATE));
    }

    @Test
    void guestsAndAnonymousHoldNothing() throws SQLException {
        Actor guest = new Actor.Guest("g", GuestIdentity.COOKIE, false);
        assertEquals(Set.of(), permissions.held(guest));
        assertEquals(Set.of(), permissions.effective(Actor.ANONYMOUS));
        assertFalse(permissions.allowed(guest, Permission.ROOMS_CREATE));
        assertError(401, "not_authenticated", () -> permissions.require(guest, Permission.ROOMS_CREATE));
        assertError(401, "not_authenticated", () -> permissions.require(Actor.ANONYMOUS, Permission.LIBRARY_UPLOAD));
    }

    @Test
    void unknownStoredPermissionsGrantNothing() throws SQLException {
        String user = fixtures.user("someone", false);
        Roles.Role r = roles.create("r", List.of(Permission.IP_VIEW), false);
        roles.setUserRoles(user, List.of(r.id()));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO role_permissions (role_id, permission) VALUES (?, ?)")) {
                ps.setString(1, r.id());
                ps.setString(2, "from.the.future");
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(EnumSet.of(Permission.IP_VIEW), permissions.held(user));
        assertEquals(List.of(Permission.IP_VIEW), roles.get(r.id()).permissions());
    }

    @Test
    void featureSwitchesComeBeforePermissions() throws SQLException {
        Accounts.Account admin = account("first");
        Accounts.Account user = account("second");
        Actor.User a = actor(admin.id());
        Actor.User u = actor(user.id());
        Actor.User nobody = actor(fixtures.user("nobody", false));

        // overlay.enabled is off by default: nobody may use output.overlayVideo, admins included.
        assertFalse(permissions.enabled(Feature.OVERLAY));
        assertTrue(permissions.held(a).contains(Permission.OUTPUT_OVERLAY_VIDEO));
        assertFalse(permissions.effective(a).contains(Permission.OUTPUT_OVERLAY_VIDEO));
        assertFalse(permissions.allowed(a, Permission.OUTPUT_OVERLAY_VIDEO));
        assertError(403, "feature_disabled", () -> permissions.require(a, Permission.OUTPUT_OVERLAY_VIDEO));
        // The feature is checked first, also for those who lack the permission or an account.
        assertError(403, "feature_disabled", () -> permissions.require(u, Permission.OUTPUT_OVERLAY_VIDEO));
        assertError(403, "feature_disabled", () -> permissions.require(Actor.ANONYMOUS, Permission.OUTPUT_OVERLAY_VIDEO));
        settings.set(Settings.OVERLAY_ENABLED, true);
        permissions.require(a, Permission.OUTPUT_OVERLAY_VIDEO);
        assertTrue(permissions.allowed(a, Permission.OUTPUT_OVERLAY_VIDEO));
        assertError(403, "forbidden", () -> permissions.require(u, Permission.OUTPUT_OVERLAY_VIDEO));
        assertError(401, "not_authenticated", () -> permissions.require(Actor.ANONYMOUS, Permission.OUTPUT_OVERLAY_VIDEO));

        // ytdlp.enabled gates library.youtube.
        assertTrue(permissions.allowed(u, Permission.LIBRARY_YOUTUBE));
        settings.set(Settings.YTDLP_ENABLED, false);
        assertFalse(permissions.allowed(u, Permission.LIBRARY_YOUTUBE));
        assertFalse(permissions.allowed(a, Permission.LIBRARY_YOUTUBE));
        assertFalse(permissions.effective(u).contains(Permission.LIBRARY_YOUTUBE));
        assertTrue(permissions.held(u).contains(Permission.LIBRARY_YOUTUBE));
        assertError(403, "feature_disabled", () -> permissions.require(a, Permission.LIBRARY_YOUTUBE));
        assertError(403, "feature_disabled", () -> permissions.require(nobody, Permission.LIBRARY_YOUTUBE));
        settings.set(Settings.YTDLP_ENABLED, true);
        permissions.require(u, Permission.LIBRARY_YOUTUBE);

        // Output switches have no permission of their own.
        for (Feature f : List.of(Feature.OUTPUT_RTMP, Feature.OUTPUT_TS, Feature.OUTPUT_HLS, Feature.OUTPUT_MP3)) {
            assertTrue(permissions.enabled(f));
            permissions.requireFeature(f);
            settings.set(f.setting(), false);
            assertError(403, "feature_disabled", () -> permissions.requireFeature(f));
        }
        // Permissions without a feature are unaffected by every switch being off.
        permissions.require(u, Permission.ROOMS_CREATE);
    }
}
