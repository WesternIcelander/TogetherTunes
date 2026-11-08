package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Accounts} and {@link BuiltinRoles} against a migrated database, in both dialects. */
abstract class AccountsContract {
    static final long NOW = 1_800_000_000_000L;
    static final Passwords CHEAP = new Passwords(new Passwords.Params(64, 1, 1));

    protected Database db;
    protected Accounts accounts;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        accounts = new Accounts(db, CHEAP, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private List<List<String>> rows(String sql, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<List<String>> out = new ArrayList<>();
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= n; i++) {
                            row.add(rs.getString(i));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    private Accounts.Account setup(String username) throws SQLException {
        return accounts.createLocal(username, null, "0123456789", true, "192.0.2.1", SetupApi.AUDIT_ACTION);
    }

    private static void assertError(int status, String code, HttpError e) {
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code());
    }

    @Test
    void builtinRolesAreCreatedOnce() throws SQLException {
        BuiltinRoles.ensure(db);
        BuiltinRoles.ensure(db);
        assertEquals(List.of(List.of("admin", "1", "0"), List.of("user", "1", "1")),
                rows("SELECT name, builtin, is_default FROM roles ORDER BY name"));
    }

    @Test
    void firstAccountBecomesAdmin() throws SQLException {
        assertFalse(accounts.anyExists());
        Accounts.Account admin = accounts.createLocal(" Siggi ", "  Siggi   the  Admin ", "correct horse", true,
                "192.0.2.1", SetupApi.AUDIT_ACTION);
        assertTrue(accounts.anyExists());
        assertEquals("Siggi", admin.username());
        assertEquals("Siggi the Admin", admin.displayName());
        assertEquals(List.of("admin", "user"), admin.roles());
        assertEquals(List.of(List.of(admin.id(), "Siggi", "Siggi the Admin", String.valueOf(NOW), "0")),
                rows("SELECT id, username, display_name, created_at, disabled FROM users"));
        assertEquals(List.of(List.of("admin"), List.of("user")), rows(
                "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.name",
                admin.id()));
        String hash = rows("SELECT hash FROM password_credentials WHERE user_id = ?", admin.id()).get(0).get(0);
        assertTrue(hash.startsWith("$argon2id$"), hash);
        assertTrue(CHEAP.verify("correct horse", hash));
        List<List<String>> audit = rows("SELECT actor_user_id, actor_guest_id, ip, action, target, details_json, at FROM audit_log");
        assertEquals(1, audit.size());
        assertEquals(admin.id(), audit.get(0).get(0));
        assertEquals(null, audit.get(0).get(1));
        assertEquals("192.0.2.1", audit.get(0).get(2));
        assertEquals("account.setup", audit.get(0).get(3));
        assertEquals(admin.id(), audit.get(0).get(4));
        assertEquals("{\"username\":\"Siggi\",\"roles\":[\"admin\",\"user\"]}", audit.get(0).get(5));
        assertEquals(String.valueOf(NOW), audit.get(0).get(6));
    }

    @Test
    void setupOnlyOnce() throws SQLException {
        setup("first");
        assertError(409, "setup_complete", assertThrows(HttpError.class, () -> setup("second")));
        assertEquals(1, rows("SELECT id FROM users").size());
        assertEquals(1, rows("SELECT id FROM audit_log").size());
    }

    @Test
    void laterAccountsGetOnlyTheDefaultRoles() throws SQLException {
        setup("first");
        Accounts.Account second = accounts.createLocal("second", "Second", "0123456789", false, null, "account.register");
        assertEquals(List.of("user"), second.roles());
        assertEquals(1, rows("SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE r.name = 'admin'").size());
    }

    @Test
    void firstAccountIsAdminWhicheverWayItIsCreated() throws SQLException {
        Accounts.Account first = accounts.createLocal("viaregister", null, "0123456789", false, null, "account.register");
        assertEquals(List.of("admin", "user"), first.roles());
        assertEquals("viaregister", first.displayName());
    }

    @Test
    void usernamesAreUniqueIgnoringCase() throws SQLException {
        setup("Siggi");
        assertError(409, "username_taken", assertThrows(HttpError.class,
                () -> accounts.createLocal("siggi", null, "0123456789", false, null, "account.register")));
        assertError(409, "username_taken", assertThrows(HttpError.class,
                () -> accounts.createLocal("SIGGI", null, "0123456789", false, null, "account.register")));
    }

    @Test
    void validationErrorsCreateNothing() {
        for (String bad : new String[] {null, "", "ab", "x".repeat(33), "_lead", ".lead", "has space", "ümlaut", "a/b", "a@b"}) {
            assertError(400, "invalid_username", assertThrows(HttpError.class, () -> setup(bad)));
        }
        assertError(400, "invalid_password", assertThrows(HttpError.class,
                () -> accounts.createLocal("admin", null, "short", true, null, SetupApi.AUDIT_ACTION)));
        assertError(400, "invalid_password", assertThrows(HttpError.class,
                () -> accounts.createLocal("admin", null, null, true, null, SetupApi.AUDIT_ACTION)));
        assertError(400, "invalid_display_name", assertThrows(HttpError.class,
                () -> accounts.createLocal("admin", "x".repeat(65), "0123456789", true, null, SetupApi.AUDIT_ACTION)));
        assertError(400, "invalid_display_name", assertThrows(HttpError.class,
                () -> accounts.createLocal("admin", "bell\u0007", "0123456789", true, null, SetupApi.AUDIT_ACTION)));
        assertError(400, "invalid_display_name", assertThrows(HttpError.class,
                () -> accounts.createLocal("admin", "rtl‮override", "0123456789", true, null, SetupApi.AUDIT_ACTION)));
        assertThrows(HttpError.class, () -> setup("ab"));
        assertDoesNotExist();
    }

    private void assertDoesNotExist() {
        try {
            assertFalse(accounts.anyExists());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void usernameAndDisplayNameEdgeCases() throws SQLException {
        assertEquals("a.b_c-9", Accounts.validUsername("a.b_c-9"));
        assertEquals("abc", Accounts.validUsername("  abc\t"));
        assertEquals("9lives", Accounts.validUsername("9lives"));
        assertEquals("x".repeat(32), Accounts.validUsername("x".repeat(32)));
        assertEquals("fallback", Accounts.validDisplayName("   ", "fallback"));
        assertEquals("👩‍💻 Dev", Accounts.validDisplayName("👩‍💻   Dev", "f"));
        assertEquals("🎵".repeat(64), Accounts.validDisplayName("🎵".repeat(64), "f"));
    }

    @Test
    void concurrentSetupMakesExactlyOneAdmin() throws Exception {
        accounts.afterFirstCheck = AccountsContract::widenRaceWindow;
        int n = 6;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                String name = "admin" + i;
                Callable<String> task = () -> {
                    go.await();
                    try {
                        // onlyFirst=false: both racers would pass the cheap pre-check; only the lock decides
                        return String.join(",", accounts.createLocal(name, null, "0123456789", false, null,
                                "account.register").roles());
                    } catch (HttpError e) {
                        return e.code();
                    }
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertEquals(1, outcomes.stream().filter("admin,user"::equals).count(), outcomes.toString());
            assertEquals(n - 1, outcomes.stream().filter("user"::equals).count(), outcomes.toString());
        }
        assertEquals(1, rows("SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE r.name = 'admin'").size());
    }

    @Test
    void concurrentSetupCallsCreateOneAccount() throws Exception {
        accounts.afterFirstCheck = AccountsContract::widenRaceWindow;
        int n = 6;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                String name = "admin" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return String.join(",", setup(name).roles());
                    } catch (HttpError e) {
                        return e.code();
                    }
                }));
            }
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
            assertEquals(1, outcomes.stream().filter("admin,user"::equals).count(), outcomes.toString());
            assertEquals(n - 1, outcomes.stream().filter("setup_complete"::equals).count(), outcomes.toString());
        }
        assertEquals(1, rows("SELECT id FROM users").size());
    }

    /** Without the account-creation lock, every racer would now have seen an empty users table. */
    private static void widenRaceWindow() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void missingBuiltinRolesFailLoudly() throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM roles")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertThrows(IllegalStateException.class, () -> setup("admin"));
        assertFalse(accounts.anyExists());
    }

    // ------------------------------------------------------------------ login

    private Accounts.Account register(String username, String password) throws SQLException {
        return accounts.createLocal(username, null, password, false, null, AuthApi.AUDIT_REGISTER);
    }

    private List<List<String>> loginAudit() throws SQLException {
        return rows("SELECT action, actor_user_id, ip, target, details_json FROM audit_log"
                + " WHERE action LIKE 'auth.%' ORDER BY id");
    }

    @Test
    void loginChecksThePasswordIgnoringUsernameCase() throws SQLException {
        Accounts.Account admin = setup("Siggi");
        Accounts.Account in = accounts.login("  siggi ", "0123456789", "192.0.2.7");
        assertEquals(admin, in);
        assertEquals(List.of(List.of("auth.login", admin.id(), "192.0.2.7", admin.id(), "{\"method\":\"password\"}")),
                loginAudit());
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameAnswer() throws SQLException {
        Accounts.Account admin = setup("siggi");
        HttpError wrong = assertThrows(HttpError.class, () -> accounts.login("siggi", "0123456788", "192.0.2.7"));
        HttpError unknown = assertThrows(HttpError.class, () -> accounts.login("nobody", "0123456789", "192.0.2.8"));
        assertError(401, "invalid_credentials", wrong);
        assertError(401, "invalid_credentials", unknown);
        assertEquals(wrong.getMessage(), unknown.getMessage());
        for (String[] bad : new String[][] {{null, "0123456789"}, {"siggi", null}, {"", ""}, {"x".repeat(1000), "0123456789"},
                {"siggi", "y".repeat(5000)}}) {
            assertError(401, "invalid_credentials", assertThrows(HttpError.class, () -> accounts.login(bad[0], bad[1], null)));
        }
        List<List<String>> audit = loginAudit();
        assertEquals(7, audit.size());
        assertEquals(java.util.Arrays.asList("auth.login_failed", null, "192.0.2.7", admin.id(),
                "{\"username\":\"siggi\",\"reason\":\"wrong_password\"}"), audit.get(0));
        assertEquals(java.util.Arrays.asList("auth.login_failed", null, "192.0.2.8", null,
                "{\"username\":\"nobody\",\"reason\":\"unknown_user\"}"), audit.get(1));
        assertTrue(audit.get(5).get(4).length() < 100, "long usernames are shortened in the audit log");
    }

    @Test
    void loginBacksOffPerUsernameAndPerIp() throws SQLException {
        setup("siggi");
        io.siggi.togethertunes.TestClock clock = new io.siggi.togethertunes.TestClock(NOW);
        Accounts throttled = new Accounts(db, CHEAP, clock, new LoginThrottle(clock,
                new LoginThrottle.Policy(4, 1_000, 8_000, 60_000, 100),
                new LoginThrottle.Policy(2, 1_000, 8_000, 60_000, 100)));
        // Per username: two free failures, the third locks for 1 s, even for the right password.
        for (int i = 0; i < 3; i++) {
            String ip = "192.0.2." + (10 + i);
            assertError(401, "invalid_credentials", assertThrows(HttpError.class, () -> throttled.login("Siggi", "wrong-password", ip)));
        }
        HttpError locked = assertThrows(HttpError.class, () -> throttled.login("siggi", "0123456789", "192.0.2.20"));
        assertError(429, "rate_limited", locked);
        assertEquals(1, locked.retryAfterSeconds());
        clock.advance(500);
        assertError(429, "rate_limited", assertThrows(HttpError.class, () -> throttled.login("siggi", "0123456789", "192.0.2.21")));
        List<List<String>> refusals = rows("SELECT actor_user_id, ip, target, details_json, at FROM audit_log"
                + " WHERE action = 'auth.login_throttled'");
        assertEquals(List.of(java.util.Arrays.asList(null, "192.0.2.20", null,
                "{\"username\":\"siggi\",\"scope\":\"username\",\"retryAfterMs\":1000}", String.valueOf(NOW))),
                refusals, "only the first refusal of a lock is audited");
        assertEquals(3, rows("SELECT id FROM audit_log WHERE action = 'auth.login_failed'").size(),
                "refused attempts are not failures");
        clock.advance(500);
        throttled.login("siggi", "0123456789", "192.0.2.22");

        // Per IP: four free failures across usernames, the fifth locks the IP for 1 s.
        for (int i = 0; i < 5; i++) {
            String name = "nobody" + i;
            assertError(401, "invalid_credentials", assertThrows(HttpError.class, () -> throttled.login(name, "0123456789", "198.51.100.1")));
        }
        assertError(429, "rate_limited", assertThrows(HttpError.class, () -> throttled.login("siggi", "0123456789", "198.51.100.1")));
        assertTrue(rows("SELECT details_json FROM audit_log WHERE action = 'auth.login_throttled' ORDER BY id")
                .get(1).get(0).contains("\"scope\":\"ip\""));
        clock.advance(1_000);
        assertEquals("siggi", throttled.login("siggi", "0123456789", "198.51.100.1").username());
    }

    @Test
    void accountWithoutLocalPasswordCannotLogIn() throws SQLException {
        Accounts.Account admin = setup("siggi");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM password_credentials")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertError(401, "invalid_credentials", assertThrows(HttpError.class, () -> accounts.login("siggi", "0123456789", null)));
        assertTrue(loginAudit().get(0).get(4).contains("no_password"));
        assertEquals(admin.id(), loginAudit().get(0).get(3));
    }

    @Test
    void disabledAccountIsRefusedOnlyWithTheRightPassword() throws SQLException {
        setup("siggi");
        Accounts.Account user = register("someone", "0123456789");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, user.id());
                ps.executeUpdate();
            }
            return null;
        });
        assertError(401, "invalid_credentials", assertThrows(HttpError.class, () -> accounts.login("someone", "wrong-password", null)));
        assertError(403, "account_disabled", assertThrows(HttpError.class, () -> accounts.login("someone", "0123456789", null)));
        assertEquals(0, rows("SELECT id FROM audit_log WHERE action = 'auth.login'").size());
    }

    @Test
    void loginRehashesWhenTheParametersChange() throws SQLException {
        Accounts.Account admin = setup("siggi");
        String oldHash = rows("SELECT hash FROM password_credentials").get(0).get(0);
        assertTrue(oldHash.startsWith("$argon2id$v=19$m=64,t=1,p=1$"), oldHash);

        // Same parameters: the hash stays.
        accounts.login("siggi", "0123456789", null);
        assertEquals(oldHash, rows("SELECT hash FROM password_credentials").get(0).get(0));

        Passwords stronger = new Passwords(new Passwords.Params(128, 2, 1));
        Accounts upgraded = new Accounts(db, stronger, Clock.fixed(Instant.ofEpochMilli(NOW + 1000), ZoneOffset.UTC));
        // A failed login doesn't touch it.
        assertThrows(HttpError.class, () -> upgraded.login("siggi", "wrong-password", null));
        assertEquals(oldHash, rows("SELECT hash FROM password_credentials").get(0).get(0));

        assertEquals(admin.id(), upgraded.login("siggi", "0123456789", null).id());
        List<String> row = rows("SELECT hash, updated_at FROM password_credentials").get(0);
        assertTrue(row.get(0).startsWith("$argon2id$v=19$m=128,t=2,p=1$"), row.get(0));
        assertEquals(String.valueOf(NOW + 1000), row.get(1));
        assertTrue(stronger.verify("0123456789", row.get(0)));
        assertFalse(stronger.needsRehash(row.get(0)));
        assertTrue(loginAudit().get(loginAudit().size() - 1).get(4).contains("\"rehashed\":true"));

        // The old parameters still verify the new hash (they're read from the hash itself).
        assertEquals(admin.id(), accounts.login("siggi", "0123456789", null).id());
    }

    @Test
    void defaultParametersAreTheDocumentedOnes() {
        assertEquals(new Passwords.Params(65536, 3, 1), Passwords.DEFAULT.params());
        assertEquals(10, Passwords.MIN_LENGTH);
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void createdSessionsResolveAndDeletedOnesDont() throws SQLException {
        Accounts.Account admin = setup("siggi");
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        Sessions sessions = new Sessions(db, clock);
        Sessions.Created created = sessions.create(admin.id(), Actor.Via.COOKIE, "UA/" + "x".repeat(600), "192.0.2.9");
        assertTrue(Tokens.isWellFormed(created.token()));
        assertFalse(created.toString().contains(created.token()), "tokens are never printed in full");
        assertEquals(NOW + Sessions.LIFETIME_MS, created.expiresAt());
        List<String> row = rows("SELECT id, user_id, token_hash, kind, created_at, last_seen_at, expires_at, user_agent, ip"
                + " FROM sessions").get(0);
        assertEquals(List.of(created.sessionId(), admin.id(), Tokens.hash(created.token()), "cookie", String.valueOf(NOW),
                String.valueOf(NOW), String.valueOf(NOW + Sessions.LIFETIME_MS)), row.subList(0, 7));
        assertEquals(Sessions.USER_AGENT_MAX, row.get(7).length());
        assertEquals("192.0.2.9", row.get(8));

        ActorResolver resolver = new ActorResolver(db, new io.siggi.togethertunes.settings.Settings(db), clock);
        ActorResolver.Credentials cookie = new ActorResolver.Credentials(null, created.token(), null, null);
        assertEquals(new Actor.User(admin.id(), created.sessionId(), Actor.Via.COOKIE), resolver.resolve(cookie));

        assertTrue(sessions.deleteByToken(created.token()));
        assertFalse(sessions.deleteByToken(created.token()));
        assertFalse(sessions.deleteByToken("not a token"));
        assertEquals(Actor.ANONYMOUS, resolver.resolve(cookie));

        Sessions.Created bearer = sessions.create(admin.id(), Actor.Via.BEARER, null, null);
        assertEquals("bearer", rows("SELECT kind FROM sessions").get(0).get(0));
        assertTrue(sessions.delete(bearer.sessionId()));
        assertFalse(sessions.delete(bearer.sessionId()));
    }
}
