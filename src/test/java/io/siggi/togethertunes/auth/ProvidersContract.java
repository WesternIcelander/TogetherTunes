package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.secrets.SecretDecryptionException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link Providers} against a migrated database, in both dialects. */
abstract class ProvidersContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    protected Providers providers;
    protected AuthFixtures fixtures;
    private final SecretCipher cipher = new SecretCipher(new byte[32]);

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        providers = new Providers(db, cipher);
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

    static Providers.Spec oidc(String slug) {
        return new Providers.Spec(slug, "Provider " + slug, Providers.Kind.OIDC, "https://" + slug + ".example",
                "client-" + slug, null, true, false, null);
    }

    static Providers.Spec oauth(String slug) {
        return new Providers.Spec(slug, "OAuth " + slug, Providers.Kind.OAUTH2, null, "client-" + slug, "identify",
                true, true, new ClaimMapping("https://oauth.example/authorize", "https://oauth.example/token",
                "https://api.oauth.example/me", "data.0.id", "login", null, null));
    }

    private void link(String userId, String providerId, String subject) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO user_identities (id, user_id, provider_id,"
                    + " subject, email, created_at, last_login_at) VALUES (?, ?, ?, ?, NULL, ?, NULL)")) {
                ps.setString(1, UuidV7.newId());
                ps.setString(2, userId);
                ps.setString(3, providerId);
                ps.setString(4, subject);
                ps.setLong(5, NOW);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String storedSecret(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT client_secret_enc FROM oidc_providers WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void severalProvidersAtOnceAndOneUserLinkingBoth() throws SQLException {
        Providers.Provider a = providers.create(oidc("own"), "own-secret");
        Providers.Provider b = providers.create(oauth("chat"), null);
        Providers.Provider off = providers.create(new Providers.Spec("off", "Off", Providers.Kind.OIDC,
                "https://off.example", "c", "openid", false, false, null), null);

        assertEquals(List.of(a.id(), b.id(), off.id()), providers.list().stream().map(Providers.Provider::id).toList());
        assertEquals(List.of(a.id(), b.id()), providers.listEnabled().stream().map(Providers.Provider::id).toList());
        assertEquals(b, providers.bySlug("chat"));
        assertNull(providers.bySlug("nope"));

        // one user, an identity at each enabled provider
        String user = fixtures.user("both", false);
        link(user, a.id(), "subject-1");
        link(user, b.id(), "12345");
        assertEquals(1, providers.identityCount(a.id()));
        assertEquals(1, providers.identityCount(b.id()));
        assertEquals(0, providers.identityCount(off.id()));
        // the same subject at another provider is another identity
        String other = fixtures.user("other", false);
        link(other, b.id(), "subject-1");
        assertEquals(2, providers.identityCount(b.id()));
    }

    @Test
    void defaultsAndNormalisation() throws SQLException {
        Providers.Provider p = providers.create(new Providers.Spec("  My-IdP ", " My IdP ", null,
                " https://idp.example/realms/x ", " cid ", null, true, false, null), null);
        assertEquals("my-idp", p.slug());
        assertEquals("My IdP", p.displayName());
        assertEquals(Providers.Kind.OIDC, p.kind());
        assertEquals("https://idp.example/realms/x", p.issuer());
        assertEquals("cid", p.clientId());
        assertEquals(Providers.OIDC_DEFAULT_SCOPES, p.scopes());
        assertFalse(p.hasClientSecret());
        assertNull(p.claimMapping());

        Providers.Provider o = providers.create(oauth("chat"), null);
        assertEquals("https://oauth.example", o.issuer(), "the authorization endpoint's origin");
        assertEquals("identify", o.scopes());
        assertEquals("data.0.id", o.claimMapping().subject());
        assertEquals("login", o.claimMapping().username());
        assertTrue(o.allowSignup());

        Providers.Provider s = providers.create(new Providers.Spec("scoped", "S", Providers.Kind.OIDC,
                "http://127.0.0.1:9000", "c", " openid   email openid ", true, false,
                new ClaimMapping(null, null, null, null, "nickname", null, null)), null);
        assertEquals("openid email", s.scopes());
        assertEquals("nickname", s.claimMapping().username());
    }

    @Test
    void validation() throws SQLException {
        assertError(400, "invalid_provider", () -> providers.create(oidc("Bad Slug"), null));
        assertError(400, "invalid_provider", () -> providers.create(oidc("-x"), null));
        assertError(400, "invalid_provider", () -> providers.create(oidc("a".repeat(33)), null));
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", " ", Providers.Kind.OIDC,
                "https://x.example", "c", null, true, false, null), null));
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X", Providers.Kind.OIDC,
                null, "c", null, true, false, null), null));
        for (String issuer : List.of("http://idp.example", "ftp://idp.example", "https://idp.example/#x",
                "https://user@idp.example", "https://idp.example?x=1", "/relative", "http://10.0.0.1")) {
            assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X",
                    Providers.Kind.OIDC, issuer, "c", null, true, false, null), null));
        }
        for (String issuer : List.of("http://localhost:8080", "http://127.0.0.1", "http://[::1]:5556/dex")) {
            providers.delete(providers.create(new Providers.Spec("x", "X", Providers.Kind.OIDC, issuer, "c", null,
                    true, false, null), null).id());
        }
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X", Providers.Kind.OIDC,
                "https://x.example", "c", "profile email", true, false, null), null));
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X", Providers.Kind.OIDC,
                "https://x.example", "c", "openid \"quoted\"", true, false, null), null));
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X", Providers.Kind.OAUTH2,
                null, "c", null, true, false, null), null));
        assertError(400, "invalid_provider", () -> providers.create(new Providers.Spec("x", "X", Providers.Kind.OIDC,
                "https://x.example", "c", null, true, false, oauth("y").claimMapping()), null));
        assertError(400, "invalid_provider", () -> providers.create(oidc("x"), ""));
        assertError(400, "invalid_provider", () -> providers.create(oidc("x"), "a\nb"));

        providers.create(oidc("taken"), null);
        assertError(409, "provider_slug_taken", () -> providers.create(oidc("TAKEN"), null));
        Providers.Provider other = providers.create(oidc("other"), null);
        Providers.Spec rename = new Providers.Spec("taken", "X", Providers.Kind.OIDC, "https://other.example",
                "c", null, true, false, null);
        assertError(409, "provider_slug_taken", () -> providers.update(other.id(), rename, Providers.SecretChange.KEEP));
        assertError(404, "provider_not_found", () -> providers.get(UuidV7.newId()));
        assertError(404, "provider_not_found", () -> providers.delete(UuidV7.newId()));
    }

    @Test
    void clientSecretIsEncryptedAndWriteOnly() throws Exception {
        Providers.Provider p = providers.create(oidc("own"), "s3cret-value");
        assertTrue(p.hasClientSecret());
        String stored = storedSecret(p.id());
        assertFalse(stored.contains("s3cret"), stored);
        assertEquals("s3cret-value", cipher.decrypt(stored, "oidc_providers.client_secret_enc:" + p.id()));
        assertEquals("s3cret-value", providers.clientSecret(p.id()));
        // bound to its row: another provider's context doesn't open it
        Providers.Provider q = providers.create(oidc("other"), null);
        assertThrows(SecretDecryptionException.class,
                () -> cipher.decrypt(stored, "oidc_providers.client_secret_enc:" + q.id()));
        assertNull(providers.clientSecret(q.id()));
        assertFalse(p.toString().contains("s3cret"), "the record has no secret");

        // keep, replace, remove
        Providers.Spec renamed = new Providers.Spec("own", "Renamed", Providers.Kind.OIDC, p.issuer(), p.clientId(),
                p.scopes(), true, true, null);
        assertTrue(providers.update(p.id(), renamed, Providers.SecretChange.KEEP).hasClientSecret());
        assertEquals("s3cret-value", providers.clientSecret(p.id()));
        providers.update(p.id(), renamed, new Providers.SecretChange.Replace("next"));
        assertEquals("next", providers.clientSecret(p.id()));
        assertFalse(providers.update(p.id(), renamed, new Providers.SecretChange.Replace(null)).hasClientSecret());
        assertNull(providers.clientSecret(p.id()));
        assertError(400, "invalid_provider",
                () -> providers.update(p.id(), renamed, new Providers.SecretChange.Replace("")));
    }

    @Test
    void identitiesPinTheSubjectsMeaning() throws SQLException {
        Providers.Provider p = providers.create(oidc("own"), null);
        Providers.Provider o = providers.create(oauth("chat"), null);
        Providers.Spec newIssuer = new Providers.Spec("own", "Own", Providers.Kind.OIDC, "https://elsewhere.example",
                p.clientId(), p.scopes(), true, false, null);
        // no identities yet: anything goes (but the kind)
        assertEquals("https://elsewhere.example",
                providers.update(p.id(), newIssuer, Providers.SecretChange.KEEP).issuer());
        assertError(400, "invalid_provider", () -> providers.update(p.id(), oauth("own"), Providers.SecretChange.KEEP));

        String user = fixtures.user("linked", false);
        link(user, p.id(), "sub");
        link(user, o.id(), "42");
        Providers.Spec back = new Providers.Spec("own", "Own", Providers.Kind.OIDC, p.issuer(), p.clientId(),
                p.scopes(), true, false, null);
        assertError(409, "provider_has_identities", () -> providers.update(p.id(), back, Providers.SecretChange.KEEP));
        // the rest still changes
        Providers.Spec renamed = new Providers.Spec("own-2", "Own 2", Providers.Kind.OIDC,
                "https://elsewhere.example", "new-client", "openid", false, true, null);
        Providers.Provider after = providers.update(p.id(), renamed, Providers.SecretChange.KEEP);
        assertEquals("own-2", after.slug());
        assertFalse(after.enabled());

        ClaimMapping m = o.claimMapping();
        Providers.Spec otherSubject = new Providers.Spec("chat", "Chat", Providers.Kind.OAUTH2, null, o.clientId(),
                o.scopes(), true, true, new ClaimMapping(m.authorizationEndpoint(), m.tokenEndpoint(),
                m.userinfoUrl(), "id", null, null, null));
        assertError(409, "provider_has_identities",
                () -> providers.update(o.id(), otherSubject, Providers.SecretChange.KEEP));
        Providers.Spec otherUserinfo = new Providers.Spec("chat", "Chat", Providers.Kind.OAUTH2, null, o.clientId(),
                o.scopes(), true, true, new ClaimMapping(m.authorizationEndpoint(), m.tokenEndpoint(),
                "https://api.oauth.example/v2/me", m.subject(), null, null, null));
        assertError(409, "provider_has_identities",
                () -> providers.update(o.id(), otherUserinfo, Providers.SecretChange.KEEP));
        Providers.Spec newEndpoints = new Providers.Spec("chat", "Chat", Providers.Kind.OAUTH2, null, o.clientId(),
                o.scopes(), true, true, new ClaimMapping("https://login.oauth.example/a", "https://login.oauth.example/t",
                m.userinfoUrl(), m.subject(), "name", "display", "mail"));
        assertEquals("display", providers.update(o.id(), newEndpoints, Providers.SecretChange.KEEP)
                .claimMapping().displayName());

        assertError(409, "provider_has_identities", () -> providers.delete(p.id()));
        assertEquals(2, providers.list().size());
        Providers.Provider spare = providers.create(oidc("spare"), "x");
        assertEquals(spare, providers.delete(spare.id()));
        assertEquals(2, providers.list().size());
    }
}
