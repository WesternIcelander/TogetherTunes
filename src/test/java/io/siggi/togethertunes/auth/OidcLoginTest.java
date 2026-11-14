package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OidcLogin}: the authorization code flow with PKCE S256 against a local fake IdP
 * ({@code com.sun.net.httpserver}, discovery and token endpoint) through the real router: the
 * authorization request, the server-side state/nonce/verifier, the browser binding, the code
 * exchange and its client authentication, the ID-token validation, and every failure redirect.
 *
 * <p>The fake IdP's token endpoint replaces {@value #ID_TOKEN} in its answer with an RS256 ID token
 * for the requesting client, carrying the nonce of the login the code belongs to (the
 * {@link #callback} helper records which login a code was sent for, as a real IdP would).
 */
class OidcLoginTest {
    private static final String BASE = "https://tunes.example";
    private static final String ID_TOKEN = "$ID_TOKEN";
    private static RSAKey signingKey;

    @TempDir
    Path dir;

    private HttpServer idp;
    private String issuer;
    private final AtomicReference<String> discoveryDoc = new AtomicReference<>();
    /** The token endpoint's answer: status and body. */
    private final AtomicReference<Object[]> tokenAnswer = new AtomicReference<>();
    private final List<Map<String, String>> tokenForms = new CopyOnWriteArrayList<>();
    private final List<String> tokenAuth = new CopyOnWriteArrayList<>();
    /** The OAuth2 userinfo URL's answer (status, body, extra header or null), and each request's Authorization. */
    private final AtomicReference<Object[]> userinfoAnswer = new AtomicReference<>();
    private final List<String> userinfoAuth = new CopyOnWriteArrayList<>();
    /** The nonce each login (by state) was started with, and each code was issued for. */
    private final Map<String, String> nonceByState = new ConcurrentHashMap<>();
    private final Map<String, String> nonceByCode = new ConcurrentHashMap<>();
    /** The key the token endpoint signs ID tokens with, the JWKS it publishes, and how often that was fetched. */
    private final AtomicReference<RSAKey> issuingKey = new AtomicReference<>();
    private final AtomicReference<JWKSet> publishedKeys = new AtomicReference<>();
    private final AtomicInteger jwksHits = new AtomicInteger();
    /** Changes the token endpoint makes to the claims of the ID tokens it issues (a misbehaving IdP). */
    private final AtomicReference<Consumer<JWTClaimsSet.Builder>> claimsTweak = new AtomicReference<>(b -> { });

    private Database database;
    private Providers providers;
    private OidcLogin login;
    private WebServer server;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final List<OidcLogin.Callback> completed = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private AuthFixtures fixtures;

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
                int status = 200;
                String body;
                if (path.endsWith("/.well-known/openid-configuration")) {
                    body = discoveryDoc.get() != null ? discoveryDoc.get() : discovery(List.of("S256"), List.of());
                } else if (path.endsWith("/jwks")) {
                    jwksHits.incrementAndGet();
                    body = publishedKeys.get().toString(true);
                } else if (path.endsWith("/token")) {
                    Map<String, String> form = parseForm(readAll(exchange.getRequestBody()));
                    tokenForms.add(form);
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    tokenAuth.add(auth == null ? "" : auth);
                    Object[] answer = tokenAnswer.get();
                    status = (Integer) answer[0];
                    body = (String) answer[1];
                    if (body.contains(ID_TOKEN)) {
                        body = body.replace(ID_TOKEN, idToken(clientId(form, auth),
                                nonceByCode.get(form.get("code")), "user-" + form.get("code")));
                    }
                } else if (path.endsWith("/user")) {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    userinfoAuth.add(auth == null ? "" : auth);
                    Object[] answer = userinfoAnswer.get();
                    status = (Integer) answer[0];
                    body = (String) answer[1];
                    if (answer[2] != null) {
                        exchange.getResponseHeaders().add("Location", (String) answer[2]);
                    }
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        issuingKey.set(signingKey);
        publishedKeys.set(new JWKSet(signingKey));
        idp.start();
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/idp";
        tokenAnswer.set(new Object[]{200,
                "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"id_token\":\"" + ID_TOKEN
                        + "\",\"expires_in\":3600}"});
        userinfoAnswer.set(new Object[]{200, "{\"id\":42,\"login\":\"octo\"}", null});

        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                .load(null).config();
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        Settings settings = new Settings(database, config);
        providers = new Providers(database, new SecretCipher(new byte[32]));
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        OidcDiscovery discovery = new OidcDiscovery(client, Duration.ofSeconds(5), clock::millis);
        login = new OidcLogin(providers, discovery, BASE, true, client, Duration.ofSeconds(5), clock,
                new RateLimiter("provider-login", 1000, 1, clock));
        login.completion((request, response, callback) -> {
            completed.add(callback);
            response.text(200, "done");
        });
        ActorResolver actors = new ActorResolver(database, settings, clock, true);
        Router router = new Router(actors.hook());
        login.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        fixtures = new AuthFixtures(database, clock.millis());
    }

    @AfterEach
    void tearDown() {
        server.close();
        idp.stop(0);
        database.close();
    }

    // ------------------------------------------------------------------ fixtures

    /** An RS256 ID token from the fake IdP for {@code clientId}, now, with {@code nonce}. */
    private String idToken(String clientId, String nonce, String subject) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(clientId)
                .issueTime(new Date(clock.millis()))
                .expirationTime(new Date(clock.millis() + 300_000));
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        claimsTweak.get().accept(claims);
        RSAKey key = issuingKey.get();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The client the token request authenticated as (form or Basic). */
    private static String clientId(Map<String, String> form, String auth) {
        if (form.containsKey("client_id")) {
            return form.get("client_id");
        }
        String pair = new String(Base64.getDecoder().decode(auth.substring("Basic ".length())),
                StandardCharsets.UTF_8);
        return URLDecoder.decode(pair.substring(0, pair.indexOf(':')), StandardCharsets.UTF_8);
    }

    private String discovery(List<String> challengeMethods, List<String> authMethods) {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"code_challenge_methods_supported\":" + jsonList(challengeMethods) + ","
                + "\"token_endpoint_auth_methods_supported\":" + jsonList(authMethods) + "}";
    }

    private static String jsonList(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (String v : values) {
            out.append(out.length() > 1 ? "," : "").append('"').append(v).append('"');
        }
        return out.append(']').toString();
    }

    private Providers.Provider oidc(String slug, String secret) throws Exception {
        return providers.create(new Providers.Spec(slug, "Provider " + slug, Providers.Kind.OIDC, issuer,
                "client-" + slug, null, true, false, null), secret);
    }

    private Providers.Provider oauth(String slug, String secret) throws Exception {
        String base = "http://127.0.0.1:" + idp.getAddress().getPort() + "/oauth";
        return providers.create(new Providers.Spec(slug, "Provider " + slug, Providers.Kind.OAUTH2, null,
                "client-" + slug, "identify email", true, false,
                new ClaimMapping(base + "/authorize?prompt=none", base + "/token", base + "/user", "id",
                        null, null, null)), secret);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<String> r) {
        assertEquals(302, r.statusCode(), r.body());
        return r.headers().firstValue("Location").orElseThrow();
    }

    private static Map<String, String> query(String url) {
        int q = url.indexOf('?');
        return q < 0 ? Map.of() : parseForm(url.substring(q + 1));
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

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The binding cookie a start set ({@code name=value}). */
    private static String bindingCookie(HttpResponse<String> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(OidcLogin.BINDING_COOKIE + "=")) {
                return c.substring(0, c.indexOf(';'));
            }
        }
        return null;
    }

    /** A started login: the authorization URL's parameters and the browser's cookie. */
    private record Started(String url, Map<String, String> params, String cookie) {
        String state() {
            return params.get("state");
        }
    }

    private Started start(String slug, String query, String cookie) throws Exception {
        HttpResponse<String> r = get("/auth/oidc/" + slug + "/start" + query, cookie);
        String url = location(r);
        String binding = bindingCookie(r);
        String jar = cookie;
        if (binding != null) {
            jar = cookie == null ? binding
                    : cookie.contains(OidcLogin.BINDING_COOKIE + "=") ? cookie : cookie + "; " + binding;
        }
        Map<String, String> params = query(url);
        if (params.get("nonce") != null) {
            nonceByState.put(params.get("state"), params.get("nonce"));
        }
        return new Started(url, params, jar);
    }

    private HttpResponse<String> callback(String slug, String state, String code, String cookie) throws Exception {
        String nonce = nonceByState.get(state);
        if (nonce != null) {
            nonceByCode.put(code, nonce);
        }
        return get("/auth/oidc/" + slug + "/callback?state=" + state + "&code=" + code, cookie);
    }

    private static void assertError(String expectedPage, String code, HttpResponse<String> r) {
        String url = location(r);
        assertTrue(url.startsWith(expectedPage + "?"), url);
        assertEquals(code, query(url).get("error"), url);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void codeChallengeIsRfc7636S256() {
        // RFC 7636, Appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OidcLogin.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void startRedirectsWithPkceStateAndNonceKeptServerSide() throws Exception {
        oidc("idp", null);
        HttpResponse<String> r = get("/auth/oidc/idp/start?next=%2Frooms%2Fabc", null);
        String url = location(r);
        assertTrue(url.startsWith(issuer + "/authorize?"), url);
        Map<String, String> p = query(url);
        assertEquals("code", p.get("response_type"));
        assertEquals("client-idp", p.get("client_id"));
        assertEquals(BASE + "/auth/oidc/idp/callback", p.get("redirect_uri"));
        assertEquals("openid profile email", p.get("scope"));
        assertEquals("S256", p.get("code_challenge_method"));
        assertTrue(Tokens.isWellFormed(p.get("state")), p.toString());
        assertTrue(Tokens.isWellFormed(p.get("nonce")), p.toString());
        assertNotEquals(p.get("state"), p.get("nonce"));
        assertEquals(43, p.get("code_challenge").length());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));

        String cookie = r.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(OidcLogin.BINDING_COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("; Path=/auth/oidc/") && cookie.contains("; HttpOnly")
                && cookie.contains("; SameSite=Lax") && cookie.contains("; Secure")
                && cookie.contains("; Max-Age=600"), cookie);
        assertEquals(1, login.pending().size());

        // completing it: the stored verifier matches the challenge, the nonce is the one sent
        nonceByCode.put("the-code", p.get("nonce"));
        HttpResponse<String> done = callback("idp", p.get("state"), "the-code", bindingCookie(r));
        assertEquals(200, done.statusCode(), done.headers().map().toString());
        OidcLogin.Callback cb = completed.get(0);
        assertEquals(p.get("code_challenge"), OidcLogin.codeChallenge(cb.pending().codeVerifier()));
        assertEquals(p.get("nonce"), cb.pending().nonce());
        assertEquals("/rooms/abc", cb.pending().next());
        assertEquals(OidcLogin.Intent.LOGIN, cb.pending().intent());
        assertEquals("idp", cb.provider().slug());
        assertEquals("at-1", cb.tokens().accessToken());
        assertNotNull(cb.tokens().idToken());
        assertEquals("user-the-code", cb.idToken().subject(), "the validated ID token");
        assertEquals("client-idp", cb.idToken().claims().getAudience().get(0));
        assertEquals(3600L, cb.tokens().expiresIn());

        Map<String, String> form = tokenForms.get(0);
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("the-code", form.get("code"));
        assertEquals(BASE + "/auth/oidc/idp/callback", form.get("redirect_uri"));
        assertEquals(cb.pending().codeVerifier(), form.get("code_verifier"));
        assertEquals("client-idp", form.get("client_id"), "a public client names itself");
        assertFalse(form.containsKey("client_secret"));
        assertEquals("", tokenAuth.get(0));
    }

    @Test
    void popupLoginsEndAtThePopupDonePage() throws Exception {
        oidc("idp", null);
        Started s = start("idp", "?popup=1&next=%2Frooms%2Fabc", null);
        assertEquals(200, callback("idp", s.state(), "c", s.cookie()).statusCode());
        assertTrue(completed.get(0).pending().popup(), "the completion learns it was a popup login");
        assertEquals("/rooms/abc", completed.get(0).pending().next());
        // Failures at the callback and at the start go to the done page with the code.
        Started s2 = start("idp", "?popup=1", null);
        assertError(OidcLogin.POPUP_DONE_PAGE, "login_browser_mismatch", callback("idp", s2.state(), "c", null));
        assertError(OidcLogin.POPUP_DONE_PAGE, "provider_unavailable", get("/auth/oidc/nope/start?popup=1", null));
        // Without popup=1, or for a link, as before.
        Started plain = start("idp", "", null);
        assertEquals(200, callback("idp", plain.state(), "c3", plain.cookie()).statusCode());
        assertFalse(completed.get(1).pending().popup());
        assertError("/account/", "not_authenticated", get("/auth/oidc/idp/start?intent=link&popup=1", null));

        // The done page: no-store, its script reports to the opener; nothing secret in it.
        HttpResponse<String> page = get(OidcLogin.POPUP_DONE_PAGE + "?error=login_expired", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(page.body().contains("src=\"/js/popupdone.js\""), page.body());
        assertFalse(page.body().contains("<script>"), "no inline script");
    }

    @Test
    void stateIsSingleUseAndExpires() throws Exception {
        oidc("idp", null);
        Started s = start("idp", "", null);
        assertEquals(200, callback("idp", s.state(), "c", s.cookie()).statusCode());
        assertError("/login", "login_expired", callback("idp", s.state(), "c", s.cookie()));
        assertEquals(1, tokenForms.size(), "a replayed state never reaches the token endpoint");

        Started old = start("idp", "", s.cookie());
        clock.advance(OidcLogin.TTL_MS);
        assertError("/login", "login_expired", callback("idp", old.state(), "c", old.cookie()));
        assertError("/login", "login_expired", callback("idp", "not-a-state", "c", old.cookie()));
        assertError("/login", "login_expired", get("/auth/oidc/idp/callback?code=c", old.cookie()));
        assertEquals(1, tokenForms.size());
        assertEquals(0, login.pending().size());
    }

    @Test
    void callbackNeedsTheBrowserThatStarted() throws Exception {
        oidc("idp", null);
        Started s = start("idp", "?next=%2Fx", null);
        // e.g. an attacker's own callback URL sent to a victim: no binding cookie, or another one
        assertError("/login", "login_browser_mismatch", callback("idp", s.state(), "c", null));
        Started s2 = start("idp", "", null);
        assertError("/login", "login_browser_mismatch",
                callback("idp", s2.state(), "c", OidcLogin.BINDING_COOKIE + "=" + Tokens.newToken()));
        // and the state is gone after the failed try
        assertError("/login", "login_expired", callback("idp", s2.state(), "c", s2.cookie()));
        assertTrue(tokenForms.isEmpty());
        assertTrue(completed.isEmpty());
    }

    @Test
    void stateOnlyWorksAtItsProvidersCallback() throws Exception {
        oidc("one", null);
        oidc("two", null);
        Started s = start("one", "", null);
        assertError("/login", "login_expired", callback("two", s.state(), "c", s.cookie()));
        assertTrue(tokenForms.isEmpty());
    }

    @Test
    void twoProvidersAtOnceInOneBrowser() throws Exception {
        oidc("one", null);
        oauth("two", "s3cret");
        Started a = start("one", "", null);
        Started b = start("two", "", a.cookie());
        assertEquals(a.cookie(), b.cookie(), "the binding cookie is reused");
        assertEquals(2, login.pending().size());
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"at-2\",\"token_type\":\"bearer\"}"});
        assertEquals(200, callback("two", b.state(), "c2", b.cookie()).statusCode());
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"at-1\",\"id_token\":\"" + ID_TOKEN + "\"}"});
        assertEquals(200, callback("one", a.state(), "c1", a.cookie()).statusCode());
        assertEquals("two", completed.get(0).provider().slug());
        assertEquals("at-2", completed.get(0).tokens().accessToken());
        assertEquals("one", completed.get(1).provider().slug());
        assertEquals("user-c1", completed.get(1).idToken().subject());
        assertNull(completed.get(0).idToken(), "no ID token for OAuth2");
        assertEquals(42, completed.get(0).userinfo().getAsJsonObject().get("id").getAsInt());
        assertNull(completed.get(1).userinfo(), "no userinfo request for OIDC");
        assertEquals(List.of("Bearer at-2"), userinfoAuth);
    }

    @Test
    void oauthProvider() throws Exception {
        oauth("gh", "s3cret");
        Started s = start("gh", "", null);
        String base = "http://127.0.0.1:" + idp.getAddress().getPort() + "/oauth";
        assertTrue(s.url().startsWith(base + "/authorize?prompt=none&response_type=code&"), s.url());
        assertNull(s.params().get("nonce"), "no nonce without OIDC");
        assertEquals("identify email", s.params().get("scope"));
        assertEquals("S256", s.params().get("code_challenge_method"));
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"gho_x\",\"token_type\":\"bearer\",\"scope\":\"\"}"});
        assertEquals(200, callback("gh", s.state(), "c", s.cookie()).statusCode());
        Map<String, String> form = tokenForms.get(0);
        assertEquals("client-gh", form.get("client_id"));
        assertEquals("s3cret", form.get("client_secret"), "client_secret_post");
        assertEquals("", tokenAuth.get(0));
        assertNull(completed.get(0).tokens().idToken());
        assertNull(completed.get(0).pending().nonce());
        assertEquals(List.of("Bearer gho_x"), userinfoAuth);
        assertEquals("octo", completed.get(0).userinfo().getAsJsonObject().get("login").getAsString());
    }

    @Test
    void oauthUserinfoFailuresNeverReachTheCompletion() throws Exception {
        oauth("gh", null);
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"gho_x\"}"});
        String big = "{\"id\":1,\"pad\":\"" + "x".repeat(OidcLogin.MAX_BODY_BYTES) + "\"}";
        Object[][] answers = {
                {401, "{\"message\":\"Bad credentials\"}", null},
                {302, "{}", "http://127.0.0.1:1/elsewhere"}, // redirects aren't followed
                {200, "<html>", null},
                {200, "", null},
                {200, "42", null},
                {200, big, null},
        };
        for (Object[] answer : answers) {
            userinfoAnswer.set(answer);
            Started s = start("gh", "", null);
            assertError("/login", "userinfo_failed", callback("gh", s.state(), "c", s.cookie()));
        }
        assertTrue(completed.isEmpty());
        assertEquals(answers.length, userinfoAuth.size());
        // An unreachable userinfo URL.
        String base = "http://127.0.0.1:" + idp.getAddress().getPort() + "/oauth";
        Providers.Provider p = providers.bySlug("gh");
        providers.update(p.id(), new Providers.Spec(p.slug(), p.displayName(), p.kind(), p.issuer(), p.clientId(),
                p.scopes(), true, false, new ClaimMapping(base + "/authorize", base + "/token",
                        "http://127.0.0.1:1/user", "id", null, null, null)), Providers.SecretChange.KEEP);
        Started s = start("gh", "", null);
        assertError("/login", "userinfo_failed", callback("gh", s.state(), "c", s.cookie()));
        assertTrue(completed.isEmpty());
    }

    @Test
    void oidcClientAuthentication() throws Exception {
        // default (no methods listed): client_secret_basic, both parts form-encoded
        providers.create(new Providers.Spec("basic", "Basic", Providers.Kind.OIDC, issuer, "client:1", null,
                true, false, null), "p@ss word");
        Started s = start("basic", "", null);
        assertEquals(200, callback("basic", s.state(), "c", s.cookie()).statusCode());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("client%3A1:p%40ss+word"
                .getBytes(StandardCharsets.UTF_8)), tokenAuth.get(0));
        assertFalse(tokenForms.get(0).containsKey("client_id"));
        assertFalse(tokenForms.get(0).containsKey("client_secret"));

        // only client_secret_post listed
        discoveryDoc.set(discovery(List.of("S256"), List.of("client_secret_post")));
        expireDiscovery();
        Started s2 = start("basic", "", s.cookie());
        assertEquals(200, callback("basic", s2.state(), "c", s2.cookie()).statusCode());
        assertEquals("", tokenAuth.get(1));
        assertEquals("client:1", tokenForms.get(1).get("client_id"));
        assertEquals("p@ss word", tokenForms.get(1).get("client_secret"));

        // neither: refused before sending the secret anywhere
        discoveryDoc.set(discovery(List.of("S256"), List.of("private_key_jwt")));
        expireDiscovery();
        Started s3 = start("basic", "", s.cookie());
        assertError("/login", "provider_unsupported", callback("basic", s3.state(), "c", s3.cookie()));
        assertEquals(2, tokenForms.size());
    }

    /** Lets the discovery cache expire (24 h on the shared clock). */
    private void expireDiscovery() {
        clock.advance(OidcDiscovery.METADATA_TTL_MS + 1);
    }

    @Test
    void providerWithoutS256IsRefused() throws Exception {
        discoveryDoc.set(discovery(List.of("plain"), List.of()));
        oidc("idp", null);
        assertError("/login", "provider_unsupported", get("/auth/oidc/idp/start", null));
        assertEquals(0, login.pending().size());
    }

    @Test
    void unreachableUnknownOrDisabledProvider() throws Exception {
        assertError("/login", "provider_unavailable", get("/auth/oidc/nope/start", null));
        Providers.Provider p = oidc("idp", null);
        Started s = start("idp", "", null);
        Providers.Spec off = new Providers.Spec(p.slug(), p.displayName(), p.kind(), p.issuer(), p.clientId(),
                p.scopes(), false, p.allowSignup(), p.claimMapping());
        providers.update(p.id(), off, Providers.SecretChange.KEEP);
        assertError("/login", "provider_unavailable", get("/auth/oidc/idp/start", null));
        assertError("/login", "provider_unavailable", callback("idp", s.state(), "c", s.cookie()));

        providers.create(new Providers.Spec("down", "Down", Providers.Kind.OIDC, "http://127.0.0.1:9/idp",
                "c", null, true, false, null), null);
        assertError("/login", "provider_unreachable", get("/auth/oidc/down/start?next=%2Fq", null));
        assertTrue(tokenForms.isEmpty());
    }

    @Test
    void providerErrorsAndTokenFailures() throws Exception {
        oidc("idp", null);
        Started s = start("idp", "?next=%2Frooms%2Fx", null);
        HttpResponse<String> denied = get("/auth/oidc/idp/callback?state=" + s.state()
                + "&error=access_denied&error_description=no", s.cookie());
        assertError("/login", "provider_denied", denied);
        assertEquals("/rooms/x", query(location(denied)).get("next"));

        Started s2 = start("idp", "", s.cookie());
        assertError("/login", "provider_error",
                get("/auth/oidc/idp/callback?state=" + s2.state() + "&error=server_error", s.cookie()));
        Started s3 = start("idp", "", s.cookie());
        assertError("/login", "provider_error", get("/auth/oidc/idp/callback?state=" + s3.state(), s.cookie()));

        for (Object[] answer : List.of(
                new Object[]{400, "{\"error\":\"invalid_grant\"}"},
                new Object[]{200, "{\"error\":\"bad_verification_code\"}"}, // 200 with an error
                new Object[]{500, "oops"},
                new Object[]{200, "not json"},
                new Object[]{200, "{\"token_type\":\"Bearer\",\"id_token\":\"a.b.c\"}"}, // no access token
                new Object[]{200, "{\"access_token\":\"x\",\"token_type\":\"mac\",\"id_token\":\"a.b.c\"}"},
                new Object[]{200, "{\"access_token\":\"x\",\"token_type\":\"Bearer\"}"}, // OIDC needs an ID token
                new Object[]{200, "{\"access_token\":\"" + "x".repeat(OidcLogin.MAX_BODY_BYTES) + "\"}"})) {
            tokenAnswer.set(answer);
            Started t = start("idp", "", s.cookie());
            assertError("/login", "token_exchange_failed", callback("idp", t.state(), "c", t.cookie()));
        }
        assertTrue(completed.isEmpty());
    }

    @Test
    void idTokenIsValidatedBeforeTheCompletion() throws Exception {
        oidc("idp", null);
        Started a = start("idp", "?next=%2Frooms%2Fx", null);
        Started b = start("idp", "", a.cookie());
        // the IdP hands out a token carrying another login's nonce (e.g. a replayed ID token)
        nonceByCode.put("c", b.params().get("nonce"));
        HttpResponse<String> r = get("/auth/oidc/idp/callback?state=" + a.state() + "&code=c", a.cookie());
        assertError("/login", "id_token_invalid", r);
        assertEquals("/rooms/x", query(location(r)).get("next"));

        // signed by a key the provider never published
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("u").audience("client-idp")
                .issueTime(new Date(clock.millis())).expirationTime(new Date(clock.millis() + 300_000))
                .claim("nonce", b.params().get("nonce")).build();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rogue").build(), claims);
        forged.sign(new RSASSASigner(rogue));
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"at\",\"id_token\":\"" + forged.serialize() + "\"}"});
        assertError("/login", "id_token_invalid", callback("idp", b.state(), "c2", b.cookie()));

        // the same token, but issued long before now: iat out of range
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"at\",\"id_token\":\"" + ID_TOKEN + "\"}"});
        Started d = start("idp", "", a.cookie());
        nonceByCode.put("c3", d.params().get("nonce"));
        String early = idToken("client-idp", d.params().get("nonce"), "u");
        clock.advance(IdTokenValidator.MAX_SKEW_MS + 1);
        tokenAnswer.set(new Object[]{200, "{\"access_token\":\"at\",\"id_token\":\"" + early + "\"}"});
        assertError("/login", "id_token_invalid", callback("idp", d.state(), "c3", d.cookie()));
        assertTrue(completed.isEmpty());
    }

    /**
     * The four ID-token failures the plan names (bad {@code nonce}, bad {@code aud}, expired token,
     * unknown {@code kid}) end to end: a real login through the router, the fake IdP's token endpoint
     * handing out the bad token. Each ends in {@code id_token_invalid} with the login's {@code next},
     * the completion is never called, and the state can't be retried. A good login before and after
     * shows the IdP and the flow are otherwise fine; a rotated-in key (new {@code kid}) is accepted
     * after one JWKS refetch, a never-published one is not.
     */
    @Test
    void badIdTokensAreRefusedAtTheCallback() throws Exception {
        oidc("idp", null);
        Started ok = start("idp", "", null);
        assertEquals(200, callback("idp", ok.state(), "ok1", ok.cookie()).statusCode());
        assertEquals("user-ok1", completed.get(0).idToken().subject());
        assertEquals(1, jwksHits.get());
        String browser = ok.cookie();

        Map<String, Consumer<JWTClaimsSet.Builder>> bad = new LinkedHashMap<>();
        bad.put("nonce of another login", b -> b.claim("nonce", "Zm9yZWlnbi1ub25jZS1mcm9tLWFub3RoZXItbG9naW4"));
        bad.put("no nonce", b -> b.claim("nonce", null));
        bad.put("aud of another client", b -> b.audience("client-other"));
        bad.put("no aud", b -> b.audience((String) null));
        bad.put("several aud, no azp", b -> b.audience(List.of("client-idp", "client-other")));
        bad.put("azp of another client", b -> b.audience(List.of("client-idp", "client-other"))
                .claim("azp", "client-other"));
        bad.put("expired 2 min ago", b -> b.issueTime(new Date(clock.millis() - 30_000))
                .expirationTime(new Date(clock.millis() - 120_000)));
        bad.put("expired exactly at the skew", b -> b.expirationTime(
                new Date(clock.millis() - IdTokenValidator.MAX_SKEW_MS)));
        bad.put("no exp", b -> b.expirationTime(null));
        int n = 0;
        for (Map.Entry<String, Consumer<JWTClaimsSet.Builder>> e : bad.entrySet()) {
            claimsTweak.set(e.getValue());
            Started s = start("idp", "?next=%2Frooms%2Fr" + n, browser);
            HttpResponse<String> r = callback("idp", s.state(), "bad" + n, s.cookie());
            assertError("/login", "id_token_invalid", r);
            assertEquals("/rooms/r" + n, query(location(r)).get("next"), e.getKey());
            // the failed state is gone: the same callback again doesn't get a second try
            assertNotEquals("id_token_invalid", query(location(
                    callback("idp", s.state(), "bad" + n, s.cookie()))).get("error"), e.getKey());
            n++;
        }
        assertEquals(1, completed.size(), "no bad token reached the completion");
        assertEquals(1, jwksHits.get(), "claim failures need no key refetch");

        // unknown kid: signed by a key the IdP never published -> one refetch, still unknown, refused
        claimsTweak.set(b -> { });
        clock.advance(OidcDiscovery.REFETCH_MIN_MS);
        issuingKey.set(new RSAKeyGenerator(2048).keyID("never-published").keyUse(KeyUse.SIGNATURE).generate());
        Started u = start("idp", "?next=%2Fq", browser);
        HttpResponse<String> r = callback("idp", u.state(), "kid1", u.cookie());
        assertError("/login", "id_token_invalid", r);
        assertEquals("/q", query(location(r)).get("next"));
        assertEquals(2, jwksHits.get(), "the unknown kid made the keys be fetched again");
        // a second try right away doesn't hammer the IdP (refetch rate limit), and is refused too
        Started u2 = start("idp", "", browser);
        assertError("/login", "id_token_invalid", callback("idp", u2.state(), "kid2", u2.cookie()));
        assertEquals(2, jwksHits.get());
        // an impostor key reusing the published kid: the signature doesn't verify
        issuingKey.set(new RSAKeyGenerator(2048).keyID(signingKey.getKeyID()).generate());
        Started u3 = start("idp", "", browser);
        assertError("/login", "id_token_invalid", callback("idp", u3.state(), "kid3", u3.cookie()));
        assertEquals(1, completed.size());

        // the IdP rotates in a new key: its new kid is fetched and the login goes through
        clock.advance(OidcDiscovery.REFETCH_MIN_MS);
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("k2").keyUse(KeyUse.SIGNATURE).generate();
        publishedKeys.set(new JWKSet(List.of(signingKey, rotated)));
        issuingKey.set(rotated);
        Started k = start("idp", "", browser);
        assertEquals(200, callback("idp", k.state(), "ok2", k.cookie()).statusCode());
        assertEquals(3, jwksHits.get());
        assertEquals(2, completed.size());
        assertEquals("user-ok2", completed.get(1).idToken().subject());
    }

    @Test
    void linkNeedsTheSameLoggedInUser() throws Exception {
        oidc("idp", null);
        assertError("/account/", "not_authenticated", get("/auth/oidc/idp/start?intent=link", null));
        assertError("/login", "provider_error", get("/auth/oidc/idp/start?intent=weird", null));

        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        String aliceSid = ActorResolver.SESSION_COOKIE + "="
                + fixtures.session(alice, "cookie", clock.millis() + 86_400_000L, null);
        String bobSid = ActorResolver.SESSION_COOKIE + "="
                + fixtures.session(bob, "cookie", clock.millis() + 86_400_000L, null);

        Started s = start("idp", "?intent=link", aliceSid);
        assertEquals(200, callback("idp", s.state(), "c", s.cookie()).statusCode());
        assertEquals(OidcLogin.Intent.LINK, completed.get(0).pending().intent());
        assertEquals(alice, completed.get(0).pending().userId());

        // logged out, or someone else logged in on that browser, meanwhile
        Started s2 = start("idp", "?intent=link", aliceSid);
        String binding = s2.cookie().substring(s2.cookie().indexOf(OidcLogin.BINDING_COOKIE));
        assertError("/account/", "link_session_changed", callback("idp", s2.state(), "c", binding));
        Started s3 = start("idp", "?intent=link", s2.cookie());
        assertError("/account/", "link_session_changed",
                callback("idp", s3.state(), "c", bobSid + "; " + binding));
        assertEquals(1, tokenForms.size());
    }

    @Test
    void defaultCompletionSaysLoginIsNotAvailableYet() throws Exception {
        oidc("idp", null);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        OidcLogin plain = new OidcLogin(providers, new OidcDiscovery(client, Duration.ofSeconds(5), clock::millis),
                BASE, true, client, Duration.ofSeconds(5), clock, new RateLimiter("l", 1000, 1, clock));
        Router router = new Router();
        plain.register(router);
        try (WebServer other = WebServer.start("127.0.0.1", 0, router, dir.toFile())) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other.port()
                    + "/auth/oidc/idp/start?next=%2Fnext"));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> p = query(location(r));
            nonceByCode.put("c", p.get("nonce"));
            HttpResponse<String> done = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + other.port() + "/auth/oidc/idp/callback?state=" + p.get("state") + "&code=c"))
                    .header("Cookie", bindingCookie(r)).build(), HttpResponse.BodyHandlers.ofString());
            assertError("/login", "login_unavailable", done);
            assertEquals("/next", query(location(done)).get("next"));
        }
    }

    @Test
    void nextMustBeLocal() {
        assertEquals("/rooms/a?b=c", OidcLogin.safeNext("/rooms/a?b=c"));
        for (String bad : new String[]{null, "", "https://evil.example/", "//evil.example", "/\\evil.example",
                "rooms", "/a\nb", "/a\\b", "/" + "x".repeat(OidcLogin.NEXT_MAX)}) {
            assertEquals("/", OidcLogin.safeNext(bad), String.valueOf(bad));
        }
    }

    @Test
    void pendingStoreIsBounded() {
        OidcLogin.PendingLogins store = new OidcLogin.PendingLogins(clock, 3);
        String[] states = new String[4];
        for (int i = 0; i < 4; i++) {
            states[i] = Tokens.newToken();
            store.put(states[i], "h", new OidcLogin.Pending("p", "s", Providers.Kind.OIDC, "i", "n", "v",
                    OidcLogin.Intent.LOGIN, null, "/", clock.millis() + OidcLogin.TTL_MS, false));
        }
        assertEquals(3, store.size());
        assertNull(store.take(states[0]), "the oldest went");
        assertNotNull(store.take(states[3]));
        assertNull(store.take(states[3]), "taken once");
    }
}
