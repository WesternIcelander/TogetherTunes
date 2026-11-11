package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.settings.Settings;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Identity resolution end to end ({@link IdentityResolution}, {@link IdentitiesApi}): provider
 * logins through the real {@link OidcLogin} against a local fake IdP that serves <b>two OIDC
 * providers at once</b> ({@code /a} and {@code /b}, each its own issuer): signing up, logging in
 * through each, one user linking both, the refusals, and {@code /api/me/identities}.
 *
 * <p>The fake IdP issues the code it is told to (the test acts as the browser at the provider): a
 * code is {@code <subject>.<n>} and the ID token carries that subject, the login's nonce,
 * {@code preferred_username} = the subject and an email.
 */
class IdentityResolutionTest {
    private static final String BASE = "https://tunes.example";
    private static RSAKey signingKey;

    @TempDir
    Path dir;

    private HttpServer idp;
    private String idpBase;
    private final Map<String, String> nonceByCode = new ConcurrentHashMap<>();
    private final Map<String, String> issuerByCode = new ConcurrentHashMap<>();
    private Database database;
    private Providers providers;
    private WebServer server;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final HttpClient http = HttpClient.newHttpClient();
    private AuthFixtures fixtures;
    private int codes;

    @BeforeAll
    static void generateKey() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE).generate();
    }

    @BeforeEach
    void setUp() throws Exception {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String issuer = idpBase + path.substring(0, path.indexOf('/', 1));
                String body;
                if (path.endsWith("/.well-known/openid-configuration")) {
                    body = "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\","
                            + "\"token_endpoint\":\"" + issuer + "/token\",\"jwks_uri\":\"" + issuer + "/jwks\","
                            + "\"code_challenge_methods_supported\":[\"S256\"]}";
                } else if (path.endsWith("/jwks")) {
                    body = new JWKSet(signingKey).toString(true);
                } else if (path.endsWith("/token")) {
                    ByteArrayOutputStream in = new ByteArrayOutputStream();
                    exchange.getRequestBody().transferTo(in);
                    Map<String, String> form = parseForm(in.toString(StandardCharsets.UTF_8));
                    String code = form.get("code");
                    // a code only works at the issuer it was issued for
                    String subject = issuer.equals(issuerByCode.get(code)) ? code.substring(0, code.lastIndexOf('.'))
                            : "wrong-issuer";
                    body = "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"id_token\":\""
                            + idToken(issuer, form.get("client_id"), nonceByCode.get(code), subject) + "\"}";
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        idp.start();
        idpBase = "http://127.0.0.1:" + idp.getAddress().getPort();

        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                .load(null).config();
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        Settings settings = new Settings(database, config);
        providers = new Providers(database, new SecretCipher(new byte[32]));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        OidcLogin login = new OidcLogin(providers, new OidcDiscovery(client, Duration.ofSeconds(5), clock::millis),
                BASE, true, client, Duration.ofSeconds(5), clock, new RateLimiter("provider-login", 1000, 1, clock));
        Identities identities = new Identities(database, clock);
        Sessions sessions = new Sessions(database, clock);
        login.completion(new IdentityResolution(identities, sessions, true));
        ActorResolver actors = new ActorResolver(database, settings, clock, true);
        Router router = new Router(actors.hook());
        login.register(router);
        new IdentitiesApi(identities).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        fixtures = new AuthFixtures(database, clock.millis());
        fixtures.user("admin", false); // setup is done
    }

    @AfterEach
    void tearDown() {
        server.close();
        idp.stop(0);
        database.close();
    }

    // ------------------------------------------------------------------ fixtures

    private String idToken(String issuer, String clientId, String nonce, String subject) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject).audience(clientId)
                .issueTime(new Date(clock.millis())).expirationTime(new Date(clock.millis() + 300_000))
                .claim("nonce", nonce)
                .claim("preferred_username", subject)
                .claim("name", "Name of " + subject)
                .claim("email", "shared@example.org");
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private Providers.Provider provider(String slug, boolean allowSignup) throws Exception {
        return providers.create(new Providers.Spec(slug, "Provider " + slug, Providers.Kind.OIDC, idpBase + "/" + slug,
                "client-" + slug, null, true, allowSignup, null), null);
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> out = new LinkedHashMap<>();
        if (form.isEmpty()) {
            return out;
        }
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** A browser: a cookie jar of name → value. */
    private static final class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();

        String header() {
            List<String> parts = new ArrayList<>();
            cookies.forEach((k, v) -> parts.add(k + "=" + v));
            return parts.isEmpty() ? null : String.join("; ", parts);
        }

        void take(HttpResponse<?> r) {
            for (String c : r.headers().allValues("Set-Cookie")) {
                String pair = c.substring(0, c.indexOf(';'));
                int eq = pair.indexOf('=');
                String value = pair.substring(eq + 1);
                if (value.isEmpty() || c.contains("Max-Age=0")) {
                    cookies.remove(pair.substring(0, eq));
                } else {
                    cookies.put(pair.substring(0, eq), value);
                }
            }
        }

        String sid() {
            return cookies.get(ActorResolver.SESSION_COOKIE);
        }
    }

    private HttpResponse<String> send(Browser browser, String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).header("Origin", BASE);
        String cookie = browser.header();
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        browser.take(r);
        return r;
    }

    private static String location(HttpResponse<String> r) {
        assertEquals(302, r.statusCode(), r.body());
        return r.headers().firstValue("Location").orElseThrow();
    }

    /**
     * The whole round trip: start (with {@code query}), the provider sends the browser back with a
     * code for {@code subject}, the callback. Returns where the callback redirected.
     */
    private String loginAs(Browser browser, String slug, String query, String subject) throws Exception {
        String authorize = location(send(browser, "GET", "/auth/oidc/" + slug + "/start" + query));
        assertTrue(authorize.startsWith(idpBase + "/" + slug + "/authorize?"), authorize);
        Map<String, String> params = parseForm(authorize.substring(authorize.indexOf('?') + 1));
        String code = subject + "." + (++codes);
        nonceByCode.put(code, params.get("nonce"));
        issuerByCode.put(code, idpBase + "/" + slug);
        return location(send(browser, "GET", "/auth/oidc/" + slug + "/callback?state=" + params.get("state")
                + "&code=" + code));
    }

    private JsonObject identities(Browser browser) throws Exception {
        HttpResponse<String> r = send(browser, "GET", "/api/me/identities");
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private List<String> slugs(JsonObject identities) {
        List<String> out = new ArrayList<>();
        for (var e : identities.getAsJsonArray("items")) {
            out.add(e.getAsJsonObject().getAsJsonObject("provider").get("slug").getAsString());
        }
        return out;
    }

    private String userOfSession(String token) throws Exception {
        return database.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM sessions WHERE token_hash = ?")) {
                ps.setString(1, Tokens.hash(token));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoProvidersAtOnceLoggingInThroughEachAndLinkingBoth() throws Exception {
        provider("a", true);
        provider("b", false);

        // Sign up through a, back to next, logged in with a cookie session and no password.
        Browser phone = new Browser();
        assertEquals("/rooms/x", loginAs(phone, "a", "?next=%2Frooms%2Fx", "jane"));
        assertNotNull(phone.sid());
        String jane = userOfSession(phone.sid());
        JsonObject mine = identities(phone);
        assertEquals(List.of("a"), slugs(mine));
        assertFalse(mine.get("hasPassword").getAsBoolean());
        JsonObject item = mine.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(Map.of("slug", "a", "displayName", "Provider a", "enabled", true).toString(),
                Map.of("slug", item.getAsJsonObject("provider").get("slug").getAsString(),
                        "displayName", item.getAsJsonObject("provider").get("displayName").getAsString(),
                        "enabled", item.getAsJsonObject("provider").get("enabled").getAsBoolean()).toString());
        assertEquals("shared@example.org", item.get("email").getAsString());
        assertFalse(item.toString().contains("jane."), "no subject in the list: " + item);

        // Link b to the same account.
        String sidBefore = phone.sid();
        assertEquals("/account/?linked=b", loginAs(phone, "b", "?intent=link", "jane-at-b"));
        assertEquals(sidBefore, phone.sid(), "linking keeps the session");
        assertEquals(List.of("a", "b"), slugs(identities(phone)));

        // Logging in through each provider in a fresh browser reaches the same account.
        for (String[] via : new String[][]{{"a", "jane"}, {"b", "jane-at-b"}}) {
            Browser laptop = new Browser();
            assertEquals("/", loginAs(laptop, via[0], "", via[1]));
            assertEquals(jane, userOfSession(laptop.sid()), via[0]);
        }

        // The same email from b with an unknown subject is not this account: b has sign-up off.
        Browser stranger = new Browser();
        String url = loginAs(stranger, "b", "?next=%2Frooms%2Fx", "mallory");
        assertEquals("/login.html?error=signup_disabled&next=%2Frooms%2Fx", url);
        assertNull(stranger.sid());
    }

    @Test
    void linkRefusalsAndAccountSwitch() throws Exception {
        provider("a", true);
        provider("b", true);
        Browser alice = new Browser();
        loginAs(alice, "a", "", "alice");
        String aliceId = userOfSession(alice.sid());
        Browser bob = new Browser();
        loginAs(bob, "b", "", "bob");
        // Bob can't take Alice's identity at a, nor link a second identity at his own provider.
        assertEquals("/account/?error=identity_in_use", loginAs(bob, "a", "?intent=link", "alice"));
        assertEquals("/account/?error=provider_already_linked", loginAs(bob, "b", "?intent=link", "bob2"));
        assertEquals(List.of("b"), slugs(identities(bob)));
        // Linking needs a login.
        assertEquals("/account/?error=not_authenticated",
                location(send(new Browser(), "GET", "/auth/oidc/a/start?intent=link")));

        // Logging in as Alice in Bob's browser replaces Bob's session there.
        String bobSid = bob.sid();
        loginAs(bob, "a", "", "alice");
        assertEquals(aliceId, userOfSession(bob.sid()));
        assertNull(userOfSession(bobSid), "the old session is deleted");

        // A disabled account can't log in.
        database.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, aliceId);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals("/login.html?error=account_disabled", loginAs(new Browser(), "a", "", "alice"));
    }

    @Test
    void unlinkOverHttp() throws Exception {
        provider("a", true);
        provider("b", true);
        Browser user = new Browser();
        loginAs(user, "a", "", "carol");
        loginAs(user, "b", "?intent=link", "carol-b");
        JsonArray items = identities(user).getAsJsonArray("items");
        String idA = items.get(0).getAsJsonObject().get("id").getAsString();
        String idB = items.get(1).getAsJsonObject().get("id").getAsString();

        Browser other = new Browser();
        loginAs(other, "a", "", "dave");
        assertEquals(404, send(other, "DELETE", "/api/me/identities/" + idA).statusCode());
        assertEquals(204, send(user, "DELETE", "/api/me/identities/" + idA).statusCode());
        HttpResponse<String> last = send(user, "DELETE", "/api/me/identities/" + idB);
        assertEquals(409, last.statusCode());
        assertTrue(last.body().contains("last_login_method"), last.body());
        assertEquals(List.of("b"), slugs(identities(user)));
        // Logging in through a again now signs up a new account (the identity is gone).
        Browser again = new Browser();
        loginAs(again, "a", "", "carol");
        assertNotEquals(userOfSession(user.sid()), userOfSession(again.sid()));

        // Anonymous callers have no identities.
        assertEquals(401, send(new Browser(), "GET", "/api/me/identities").statusCode());
        assertEquals(401, send(new Browser(), "DELETE", "/api/me/identities/" + idB).statusCode());
    }
}
