package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.secrets.SecretCipher;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/** {@link Identities}: resolving provider logins, linking and unlinking, in both dialects. */
abstract class IdentitiesContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    private Providers providers;
    private Identities identities;
    private AuthFixtures fixtures;
    private final TestClock clock = new TestClock(NOW);
    private Accounts accounts;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        providers = new Providers(db, new SecretCipher(new byte[32]));
        identities = new Identities(db, clock);
        fixtures = new AuthFixtures(db, NOW);
        accounts = new Accounts(db, AccountsContract.CHEAP, clock);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private Providers.Provider provider(String slug, boolean allowSignup) throws SQLException {
        return providers.create(new Providers.Spec(slug, "Provider " + slug, Providers.Kind.OIDC,
                "https://" + slug + ".example", "client-" + slug, null, true, allowSignup, null), null);
    }

    private static Identities.ProviderIdentity who(String subject) {
        return new Identities.ProviderIdentity(subject, "jane", "Jane Doe", "jane@example.com");
    }

    private static void assertRefused(String code, org.junit.jupiter.api.function.Executable call) {
        OidcLogin.LoginException e = assertThrows(OidcLogin.LoginException.class, call);
        assertEquals(code, e.code(), e.getMessage());
    }

    private long count(String sql, String arg) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (arg != null) {
                    ps.setString(1, arg);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private List<String> auditActions() throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT action, details_json FROM audit_log ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String details = rs.getString(2);
                    assertFalse(details != null && (details.contains("sub-") || details.contains("@example")), details);
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private Accounts.Account admin() throws SQLException {
        return accounts.createLocal("admin", null, "correct horse battery", true, null, "account.setup");
    }

    @Test
    void signupThenLoginBySubjectNeverByEmail() throws Exception {
        admin();
        Providers.Provider p = provider("a", true);
        Identities.Resolved first = identities.login(p, who("sub-1"), "203.0.113.5");
        assertEquals(Identities.Outcome.SIGNED_UP, first.outcome());
        assertEquals("jane", first.account().username());
        assertEquals("Jane Doe", first.account().displayName());
        assertEquals(List.of(BuiltinRoles.USER), first.account().roles()); // default roles, not admin
        assertFalse(identities.hasPassword(first.account().id()));

        clock.advance(1000);
        Identities.Resolved again = identities.login(p, who("sub-1"), null);
        assertEquals(Identities.Outcome.LOGGED_IN, again.outcome());
        assertEquals(first.account().id(), again.account().id());
        List<Identities.IdentityView> list = identities.list(first.account().id());
        assertEquals(1, list.size());
        assertEquals("a", list.get(0).providerSlug());
        assertEquals("jane@example.com", list.get(0).email());
        assertEquals(NOW, list.get(0).createdAt());
        assertEquals(NOW + 1000, list.get(0).lastLoginAt());

        // Another subject with the same email and username is another account.
        Identities.Resolved other = identities.login(p, who("sub-2"), null);
        assertEquals(Identities.Outcome.SIGNED_UP, other.outcome());
        assertNotEquals(first.account().id(), other.account().id());
        assertEquals("jane2", other.account().username());
        // The same subject at another provider is another identity too.
        Providers.Provider b = provider("b", true);
        assertNotEquals(first.account().id(), identities.login(b, who("sub-1"), null).account().id());

        assertEquals(List.of("account.setup", "account.register", "auth.login", "auth.login", "account.register",
                "auth.login", "account.register", "auth.login"), auditActions());
    }

    @Test
    void signupNeedsTheProvidersFlagAndAFinishedSetup() throws Exception {
        Providers.Provider closed = provider("closed", false);
        Providers.Provider open = provider("open", true);
        assertRefused("signup_disabled", () -> identities.login(closed, who("sub-1"), null));
        assertRefused("setup_required", () -> identities.login(open, who("sub-1"), null));
        assertEquals(0, count("SELECT COUNT(*) FROM users", null));
        admin();
        assertRefused("signup_disabled", () -> identities.login(closed, who("sub-1"), null));
        assertEquals(Identities.Outcome.SIGNED_UP, identities.login(open, who("sub-1"), null).outcome());
        assertEquals(List.of("auth.login_failed", "auth.login_failed", "account.setup", "auth.login_failed",
                "account.register", "auth.login"), auditActions());
    }

    @Test
    void refusals() throws Exception {
        admin();
        Providers.Provider p = provider("a", true);
        Identities.Resolved r = identities.login(p, who("sub-1"), null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, r.account().id());
                ps.executeUpdate();
            }
            return null;
        });
        assertRefused("account_disabled", () -> identities.login(p, who("sub-1"), null));
        // The provider was disabled (or its issuer changed) after the login started.
        Providers.Provider stale = new Providers.Provider(p.id(), p.slug(), p.displayName(), p.kind(),
                "https://other.example", p.clientId(), false, p.scopes(), true, true, null);
        assertRefused("provider_unavailable", () -> identities.login(stale, who("sub-2"), null));
        providers.update(p.id(), new Providers.Spec("a", "A", Providers.Kind.OIDC, p.issuer(), p.clientId(), null,
                false, true, null), Providers.SecretChange.KEEP);
        assertRefused("provider_unavailable", () -> identities.login(p, who("sub-2"), null));
        assertRefused("provider_error", () -> identities.login(provider("b", true),
                new Identities.ProviderIdentity("", null, null, null), null));
        assertRefused("provider_error", () -> identities.login(provider("c", true),
                new Identities.ProviderIdentity("x".repeat(256), null, null, null), null));
        assertEquals(2, count("SELECT COUNT(*) FROM users", null));
    }

    @Test
    void usernamesAndDisplayNamesFromHints() throws Exception {
        admin();
        assertEquals("user", Identities.usernameBase(null));
        assertEquals("user", Identities.usernameBase("é"));
        assertEquals("JaneDoe", Identities.usernameBase("Jane Doe"));
        assertEquals("jane.doe", Identities.usernameBase("_jane.doe"));
        assertEquals("a".repeat(32), Identities.usernameBase("a".repeat(40)));
        Providers.Provider p = provider("a", true);
        Accounts.Account noHints = identities.login(p, new Identities.ProviderIdentity("s1", null, null, null), null)
                .account();
        assertEquals("user", noHints.username());
        assertEquals("user", noHints.displayName());
        Accounts.Account fromName = identities.login(p, new Identities.ProviderIdentity("s2", null, "Émile Zola", null),
                null).account();
        assertEquals("mileZola", fromName.username());
        assertEquals("Émile Zola", fromName.displayName());
        Accounts.Account taken = identities.login(p, new Identities.ProviderIdentity("s3", "ADMIN", "bad\u0000name",
                null), null).account();
        assertEquals("ADMIN2", taken.username()); // usernames clash ignoring case
        assertEquals("ADMIN2", taken.displayName());
        String longName = "b".repeat(32);
        identities.login(p, new Identities.ProviderIdentity("s4", longName, null, null), null);
        assertEquals("b".repeat(31) + "2",
                identities.login(p, new Identities.ProviderIdentity("s5", longName, null, null), null)
                        .account().username());
    }

    @Test
    void linkRules() throws Exception {
        admin();
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        Providers.Provider a = provider("a", false);
        Providers.Provider b = provider("b", false);
        assertEquals(Identities.Outcome.LINKED, identities.link(a, who("sub-a"), alice, null).outcome());
        assertEquals(Identities.Outcome.LINKED, identities.link(b, who("sub-b"), alice, null).outcome());
        assertEquals(Identities.Outcome.ALREADY_LINKED, identities.link(a, who("sub-a"), alice, null).outcome());
        assertRefused("identity_in_use", () -> identities.link(a, who("sub-a"), bob, null));
        assertRefused("provider_already_linked", () -> identities.link(a, who("sub-other"), alice, null));
        // A linked identity logs in even though the provider has sign-up off.
        assertEquals(alice, identities.login(a, who("sub-a"), null).account().id());
        assertEquals(alice, identities.login(b, who("sub-b"), null).account().id());
        List<Identities.IdentityView> list = identities.list(alice);
        assertEquals(List.of("a", "b"), list.stream().map(Identities.IdentityView::providerSlug).toList());
        assertNull(identities.list(bob).stream().findFirst().orElse(null));
        String carol = fixtures.user("carol", true);
        assertRefused("not_authenticated", () -> identities.link(a, who("sub-c"), carol, null));
        assertEquals(2, count("SELECT COUNT(*) FROM user_identities", null));
        assertTrue(auditActions().contains(Identities.AUDIT_LINKED));
    }

    @Test
    void unlinkKeepsAWayToLogIn() throws Exception {
        admin();
        Providers.Provider a = provider("a", true);
        Providers.Provider b = provider("b", true);
        String user = identities.login(a, who("sub-a"), null).account().id();
        identities.link(b, who("sub-b"), user, null);
        List<Identities.IdentityView> list = identities.list(user);
        String idA = list.get(0).id();
        String idB = list.get(1).id();
        // Another user's or an unknown identity: 404.
        String other = fixtures.user("other", false);
        HttpError e = assertThrows(HttpError.class, () -> identities.unlink(other, idA, null));
        assertEquals("identity_not_found", e.code());
        // With b disabled, a is the only usable login.
        providers.update(b.id(), new Providers.Spec("b", "B", Providers.Kind.OIDC, b.issuer(), b.clientId(), null,
                false, true, null), Providers.SecretChange.KEEP);
        e = assertThrows(HttpError.class, () -> identities.unlink(user, idA, null));
        assertEquals(409, e.status());
        assertEquals("last_login_method", e.code());
        providers.update(b.id(), new Providers.Spec("b", "B", Providers.Kind.OIDC, b.issuer(), b.clientId(), null,
                true, true, null), Providers.SecretChange.KEEP);
        identities.unlink(user, idA, null);
        e = assertThrows(HttpError.class, () -> identities.unlink(user, idB, null));
        assertEquals("last_login_method", e.code());
        assertEquals(1, identities.list(user).size());
        // An account with a password can remove its last identity.
        String admin = accounts.login("admin", "correct horse battery", null).id();
        identities.link(a, who("sub-admin"), admin, null);
        identities.unlink(admin, identities.list(admin).get(0).id(), null);
        assertTrue(identities.list(admin).isEmpty());
        assertTrue(auditActions().contains(Identities.AUDIT_UNLINKED));
    }

    @Test
    void concurrentCallbacksForOneSubjectMakeOneAccount() throws Exception {
        admin();
        Providers.Provider p = provider("a", true);
        int n = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> {
                go.await();
                return identities.login(p, who("sub-1"), null).account().id();
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> t : tasks) {
                futures.add(pool.submit(t));
            }
            go.countDown();
            String id = futures.get(0).get();
            for (Future<String> f : futures) {
                assertEquals(id, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM user_identities", null));
        assertEquals(2, count("SELECT COUNT(*) FROM users", null));
    }
}
