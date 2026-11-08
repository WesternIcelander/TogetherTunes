package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link Users} (admin user management) and {@link Accounts#changePassword} against a migrated
 * database, in both dialects.
 */
abstract class UsersContract {
    static final long NOW = 1_800_000_000_000L;
    static final String PASSWORD = "0123456789";

    protected Database db;
    protected Users users;
    protected Accounts accounts;
    protected Roles roles;
    protected AuthFixtures fixtures;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        users = new Users(db, AccountsContract.CHEAP, clock);
        accounts = new Accounts(db, AccountsContract.CHEAP, clock);
        roles = new Roles(db);
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

    private Accounts.Account account(String username) throws SQLException {
        return accounts.createLocal(username, null, PASSWORD, false, null, AuthApi.AUDIT_REGISTER);
    }

    private String session(String userId) throws SQLException {
        String[] id = new String[1];
        fixtures.session(userId, "cookie", NOW + 86_400_000L, id);
        return id[0];
    }

    private List<String> sessionIds(String userId) throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM sessions WHERE user_id = ? ORDER BY id")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    private long auditCount(String action) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    // ------------------------------------------------------------------ listing

    @Test
    void listsByUsernameWithRolesAndPages() throws SQLException {
        Accounts.Account admin = account("zed"); // first account: admin
        account("Bob");
        accounts.createLocal("alice", "Wonder_Land", PASSWORD, false, null, AuthApi.AUDIT_REGISTER);
        fixtures.user("carol", true); // no password, disabled

        Users.Page all = users.list(null, null, 50);
        assertEquals(List.of("alice", "Bob", "carol", "zed"), all.items().stream().map(Users.UserView::username).toList());
        assertNull(all.nextCursor());
        Users.UserView zed = all.items().get(3);
        assertEquals(admin.id(), zed.id());
        assertEquals(List.of("admin", "user"), zed.roles().stream().map(Users.RoleRef::name).toList());
        assertTrue(zed.hasPassword());
        assertFalse(zed.mustChangePassword());
        Users.UserView carol = all.items().get(2);
        assertTrue(carol.disabled());
        assertFalse(carol.hasPassword());
        assertEquals(List.of(), carol.roles());

        Users.Page first = users.list(null, null, 3);
        assertEquals(3, first.items().size());
        assertEquals("carol", first.nextCursor());
        Users.Page second = users.list(null, first.nextCursor(), 3);
        assertEquals(List.of("zed"), second.items().stream().map(Users.UserView::username).toList());
        assertNull(second.nextCursor());

        // Search: username or display name, ignoring case; LIKE wildcards are literal.
        assertEquals(List.of("Bob"), users.list("BO", null, 50).items().stream().map(Users.UserView::username).toList());
        assertEquals(List.of("alice"), users.list("wonder", null, 50).items().stream().map(Users.UserView::username).toList());
        assertEquals(List.of("alice"), users.list("r_l", null, 50).items().stream().map(Users.UserView::username).toList());
        assertEquals(List.of(), users.list("%", null, 50).items());

        assertError(404, "user_not_found", () -> users.get("nope"));
    }

    @Test
    void displayName() throws SQLException {
        Accounts.Account a = account("alice");
        assertEquals("Alice W", users.setDisplayName(a.id(), "  Alice  W ").displayName());
        assertEquals("alice", users.setDisplayName(a.id(), " ").displayName());
        assertError(400, "invalid_display_name", () -> users.setDisplayName(a.id(), "bad\u0000"));
        assertError(404, "user_not_found", () -> users.setDisplayName("nope", "x"));
    }

    // ------------------------------------------------------------------ disabling

    @Test
    void disablingLogsOutAndBlocksLogin() throws SQLException {
        Accounts.Account admin = account("admin1");
        Accounts.Account bob = account("bob");
        session(bob.id());
        session(bob.id());
        Users.UserView v = users.setDisabled(admin.id(), bob.id(), true);
        assertTrue(v.disabled());
        assertEquals(List.of(), sessionIds(bob.id()));
        assertError(403, "account_disabled", () -> accounts.login("bob", PASSWORD, "192.0.2.1"));
        assertNull(accounts.findEnabled(bob.id()));
        // Idempotent.
        assertTrue(users.setDisabled(admin.id(), bob.id(), true).disabled());
        assertFalse(users.setDisabled(admin.id(), bob.id(), false).disabled());
        assertEquals(bob.id(), accounts.login("bob", PASSWORD, "192.0.2.1").id());
    }

    @Test
    void cannotDisableSelfOrTheLastAdmin() throws SQLException {
        Accounts.Account admin = account("admin1");
        Accounts.Account other = account("admin2");
        assertError(409, "cannot_disable_self", () -> users.setDisabled(admin.id(), admin.id(), true));
        String adminRole = roles.list().get(0).id();
        String userRole = roles.list().get(1).id();
        roles.setUserRoles(other.id(), List.of(adminRole, userRole));
        // Two admins: one can go.
        users.setDisabled(admin.id(), other.id(), true);
        // Now admin1 is the last enabled admin; someone else (e.g. a holder of admin.users) can't disable it.
        assertError(409, "last_admin", () -> users.setDisabled(other.id(), admin.id(), true));
        assertFalse(users.get(admin.id()).disabled());
    }

    // ------------------------------------------------------------------ temporary passwords

    @Test
    void temporaryPassword() throws SQLException {
        Accounts.Account admin = account("admin1");
        Accounts.Account bob = account("bob");
        session(bob.id());
        String keep = session(admin.id());
        session(admin.id());

        assertEquals(1, users.setTemporaryPassword(bob.id(), "temporary-1", null));
        assertEquals(List.of(), sessionIds(bob.id()));
        assertError(401, "invalid_credentials", () -> accounts.login("bob", PASSWORD, "192.0.2.1"));
        Accounts.Account logged = accounts.login("bob", "temporary-1", "192.0.2.1");
        assertTrue(logged.mustChangePassword());
        assertTrue(users.get(bob.id()).mustChangePassword());
        assertTrue(accounts.findEnabled(bob.id()).mustChangePassword());

        // An admin resetting their own password keeps the session they act from.
        assertEquals(1, users.setTemporaryPassword(admin.id(), "temporary-2", keep));
        assertEquals(List.of(keep), sessionIds(admin.id()));

        // An account without a password gets one.
        String carol = fixtures.user("carol", false);
        assertFalse(users.get(carol).hasPassword());
        users.setTemporaryPassword(carol, "temporary-3", null);
        assertTrue(users.get(carol).hasPassword());
        assertTrue(accounts.login("carol", "temporary-3", "192.0.2.1").mustChangePassword());

        assertError(400, "invalid_password", () -> users.setTemporaryPassword(bob.id(), "short", null));
        assertError(404, "user_not_found", () -> users.setTemporaryPassword("nope", "temporary-1", null));
    }

    @Test
    void changingOwnPassword() throws SQLException {
        account("admin1");
        Accounts.Account bob = account("bob");
        users.setTemporaryPassword(bob.id(), "temporary-1", null);
        String current = session(bob.id());
        session(bob.id());

        assertError(403, "wrong_password", () -> accounts.changePassword(bob.id(), current, "wrong-password", "new-password-1", "192.0.2.1"));
        assertEquals(1, auditCount(Accounts.AUDIT_PASSWORD_CHANGE_FAILED));
        assertError(400, "invalid_password", () -> accounts.changePassword(bob.id(), current, "temporary-1", "short", "192.0.2.1"));
        assertError(400, "password_unchanged", () -> accounts.changePassword(bob.id(), current, "temporary-1", "temporary-1", "192.0.2.1"));

        accounts.changePassword(bob.id(), current, "temporary-1", "new-password-1", "192.0.2.1");
        assertEquals(List.of(current), sessionIds(bob.id()));
        assertFalse(users.get(bob.id()).mustChangePassword());
        assertEquals(1, auditCount(Accounts.AUDIT_PASSWORD_CHANGED));
        assertError(401, "invalid_credentials", () -> accounts.login("bob", "temporary-1", "192.0.2.1"));
        assertFalse(accounts.login("bob", "new-password-1", "192.0.2.1").mustChangePassword());

        String carol = fixtures.user("carol", false);
        assertError(409, "no_password", () -> accounts.changePassword(carol, "x", "x", "new-password-1", "192.0.2.1"));
    }

    @Test
    void changingOwnPasswordIsThrottled() throws SQLException {
        account("admin1");
        Accounts.Account bob = account("bob");
        String current = session(bob.id());
        for (int i = 0; i < 5; i++) {
            assertError(403, "wrong_password", () -> accounts.changePassword(bob.id(), current, "wrong-password", "new-password-1", "192.0.2.1"));
        }
        // The 6th failure locks the username; then even the right password is refused.
        assertError(403, "wrong_password", () -> accounts.changePassword(bob.id(), current, "wrong-password", "new-password-1", "192.0.2.1"));
        assertError(429, "rate_limited", () -> accounts.changePassword(bob.id(), current, PASSWORD, "new-password-1", "192.0.2.1"));
    }
}
