package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.RegistrationMode;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application's routes as {@link Main#router} wires them: the test page loads from the built
 * web UI, and the test WebSocket at {@code /ws/echo} echoes for the origin of
 * {@code http.publicBaseUrl}.
 */
class MainRouterTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Settings settings;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    /** The server's clock; tests move it forward. */
    private final TestClock clock = new TestClock(System.currentTimeMillis());

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(MainRouterTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, clock, config.secureCookies());
        Accounts accounts = new Accounts(database, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(database, clock);
        // Generous route limits, so tests can make many requests; loginBacksOff… checks the login backoff.
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock));
        SetupApi setup = new SetupApi(accounts, sessions, config.secureCookies(), limits);
        AuthApi auth = new AuthApi(accounts, sessions, settings, config.secureCookies(), limits);
        SessionsApi sessionsApi = new SessionsApi(sessions, accounts, config.secureCookies());
        Passwords fastPasswords = new Passwords(new Passwords.Params(64, 1, 1));
        AdminApi admin = new AdminApi(database, accounts, new Users(database, fastPasswords, clock), new Roles(database),
                new Permissions(database, settings), settings);
        TestUploads.RoomsWiring roomsWiring = TestUploads.rooms(database, dir, config, settings,
                new Permissions(database, settings), actors, new io.siggi.togethertunes.auth.IdentityTickets(), clock);
        server = WebServer.start("127.0.0.1", 0, Main.router(config, staticFiles, actors, setup, auth, sessionsApi,
                new MeApi(accounts, new Users(database, fastPasswords, clock), new Permissions(database, settings),
                        new Guests(database, settings, config.secureCookies()), settings,
                        new io.siggi.togethertunes.auth.IdentityTickets()), admin,
                io.siggi.togethertunes.auth.AdminActivityApi.create(database,
                        new Guests(database, settings, config.secureCookies()), new Permissions(database, settings), clock),
                TestUploads.api(database, dir, config, new Permissions(database, settings), clock),
                TestUploads.youtubeApi(database, config, new Permissions(database, settings), clock),
                TestUploads.libraryApi(database, config, new Permissions(database, settings), clock),
                TestUploads.itemsApi(database, dir, new Permissions(database, settings), clock),
                TestUploads.playlistsApi(database, config, new Permissions(database, settings), clock),
                TestUploads.sharesApi(database, config, new Permissions(database, settings), clock),
                TestUploads.userLibrariesApi(database, config, new Permissions(database, settings), clock),
                TestUploads.mediaApi(database, dir, settings, io.siggi.togethertunes.rooms.PlayingItems.NO_ROOMS),
                roomsWiring.api(), roomsWiring.socket()),
                dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testPageLoads() throws Exception {
        HttpResponse<String> page = get("/test/ws-echo.html");
        assertEquals(200, page.statusCode());
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(""));
        assertTrue(page.body().contains("/js/test/ws-echo.js"), page.body());

        HttpResponse<String> script = get("/js/test/ws-echo.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertEquals(200, get("/css/test.css").statusCode());
        assertTrue(get("/").body().contains("/test/ws-echo.html"));
    }

    @Test
    void itemPageIsServedForItsDirectLink() throws Exception {
        HttpResponse<String> page = get("/items/0190abcd-ef01-7000-8000-000000000001");
        assertEquals(200, page.statusCode());
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(""));
        assertTrue(page.body().contains("src=\"/js/item.js\""), page.body());
        assertEquals(200, get("/js/item.js").statusCode());
        // The API and media routes stay more specific than the page.
        assertEquals(404, get("/api/items/0190abcd-ef01-7000-8000-000000000001").statusCode());
        for (String path : List.of("/library/", "/playlists/")) {
            assertEquals(200, get(path).statusCode(), path);
        }
    }

    @Test
    void plainGetOnTheEchoSocketIs426() throws Exception {
        assertEquals(426, get("/ws/echo").statusCode());
    }

    @Test
    void testWebSocketEchoes() throws Exception {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", PUBLIC_BASE_URL);
        try (SimpleWebsocket ws = new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers)) {
            ws.send(WebSocketMessage.create("hello"));
            assertEquals("hello", ws.read().getText());
        }
    }

    @Test
    void testWebSocketRefusesOtherOrigins() {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", "https://evil.example");
        assertThrows(IOException.class, () -> new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers).close());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("Origin", PUBLIC_BASE_URL)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void setupFlow() throws Exception {
        HttpResponse<String> status = get("/api/setup");
        assertEquals(200, status.statusCode());
        assertEquals("{\"needed\":true}", status.body());
        assertEquals("no-store", status.headers().firstValue("Cache-Control").orElse(""));

        assertEquals(415, post("/api/setup", "text/plain", "{}").statusCode());
        HttpResponse<String> badJson = post("/api/setup", "application/json", "{nope");
        assertEquals(400, badJson.statusCode());
        assertTrue(badJson.body().contains("\"invalid_json\""), badJson.body());
        HttpResponse<String> shortPassword = post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"password\":\"short\"}");
        assertEquals(400, shortPassword.statusCode());
        assertTrue(shortPassword.body().contains("\"invalid_password\""), shortPassword.body());
        assertEquals("{\"needed\":true}", get("/api/setup").body());

        HttpResponse<String> created = post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"displayName\":\"Siggi\",\"password\":\"correct horse\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().matches("\\{\"user\":\\{\"id\":\"[0-9a-f-]{36}\",\"username\":\"siggi\","
                + "\"displayName\":\"Siggi\",\"roles\":\\[\"admin\",\"user\"],\"mustChangePassword\":false}}"), created.body());
        assertTrue(!created.body().contains("127.0.0.1"), "no IP in responses");

        assertEquals("{\"needed\":false}", get("/api/setup").body());
        HttpResponse<String> again = post("/api/setup", "application/json",
                "{\"username\":\"mallory\",\"password\":\"0123456789\"}");
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("\"setup_complete\""), again.body());
    }

    @Test
    void setupPageLoads() throws Exception {
        HttpResponse<String> page = get("/setup.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("/js/setup.js"), page.body());
        HttpResponse<String> script = get("/js/setup.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("\"/api/setup\"") || get("/js/api.js").body().contains("\"/api/setup\""));
        assertTrue(get("/js/main.js").body().contains("/setup.html"), "the home page sends you to setup");
    }

    private HttpResponse<String> postJson(String path, String body, String cookie) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Origin", PUBLIC_BASE_URL)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The {@code sid=...} pair from a response's {@code Set-Cookie}, checking the flags. */
    private static String sidCookie(HttpResponse<String> response) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertEquals(1, cookies.size(), cookies.toString());
        String cookie = cookies.get(0);
        assertTrue(cookie.matches("sid=[A-Za-z0-9_-]{43}; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax; Secure"), cookie);
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private long count(String sql) throws Exception {
        return database.read(c -> {
            try (var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void registrationFollowsTheRegistrationMode() throws Exception {
        String body = "{\"username\":\"alice\",\"password\":\"0123456789\"}";
        // Closed by default; and before setup, registration never creates the first account.
        HttpResponse<String> closed = postJson("/api/auth/register", body, null);
        assertEquals(403, closed.statusCode());
        assertTrue(closed.body().contains("\"registration_closed\""), closed.body());
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.OPEN);
        HttpResponse<String> beforeSetup = postJson("/api/auth/register", body, null);
        assertEquals(409, beforeSetup.statusCode());
        assertTrue(beforeSetup.body().contains("\"setup_required\""), beforeSetup.body());
        assertEquals(0, count("SELECT COUNT(*) FROM users"));

        assertEquals(201, post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"password\":\"correct horse\"}").statusCode());
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.INVITE);
        HttpResponse<String> invite = postJson("/api/auth/register", body, null);
        assertEquals(403, invite.statusCode());
        assertTrue(invite.body().contains("\"registration_invite_only\""), invite.body());
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.CLOSED);
        assertEquals(403, postJson("/api/auth/register", body, null).statusCode());

        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.OPEN);
        HttpResponse<String> shortPassword = postJson("/api/auth/register",
                "{\"username\":\"alice\",\"password\":\"012345678\"}", null);
        assertEquals(400, shortPassword.statusCode());
        assertTrue(shortPassword.body().contains("\"invalid_password\""), shortPassword.body());
        HttpResponse<String> registered = postJson("/api/auth/register", body, null);
        assertEquals(201, registered.statusCode(), registered.body());
        assertTrue(registered.body().matches("\\{\"user\":\\{\"id\":\"[0-9a-f-]{36}\",\"username\":\"alice\","
                + "\"displayName\":\"alice\",\"roles\":\\[\"user\"],\"mustChangePassword\":false}}"), registered.body());
        assertFalse(registered.body().contains("127.0.0.1"), "no IP in responses");
        sidCookie(registered); // registering logs you in
        HttpResponse<String> taken = postJson("/api/auth/register", "{\"username\":\"ALICE\",\"password\":\"0123456789\"}", null);
        assertEquals(409, taken.statusCode());
        assertTrue(taken.body().contains("\"username_taken\""), taken.body());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'account.register'"));
    }

    @Test
    void loginBacksOffPerUsernameWithRetryAfter() throws Exception {
        assertEquals(201, post("/api/setup", "application/json",
                "{\"username\":\"Siggi\",\"password\":\"correct horse\"}").statusCode());
        for (int i = 0; i < 6; i++) { // five free failures, the sixth locks the username for 1 s
            assertEquals(401, postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"wrong horse\"}", null)
                    .statusCode());
        }
        HttpResponse<String> locked = postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null);
        assertEquals(429, locked.statusCode(), locked.body());
        assertTrue(locked.body().contains("\"rate_limited\""), locked.body());
        assertEquals("1", locked.headers().firstValue("Retry-After").orElse(""));
        assertTrue(locked.headers().allValues("Set-Cookie").isEmpty());
        HttpResponse<String> token = postJson("/api/auth/token", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null);
        assertEquals(429, token.statusCode(), "the token endpoint shares the backoff");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.login_throttled'"));
        assertEquals(6, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.login_failed'"));
        clock.advance(1_000);
        assertEquals(200, postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null)
                .statusCode());
    }

    @Test
    void loginAndLogout() throws Exception {
        assertEquals(201, post("/api/setup", "application/json",
                "{\"username\":\"Siggi\",\"password\":\"correct horse\"}").statusCode());

        HttpResponse<String> wrong = postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"wrong horse\"}", null);
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("\"invalid_credentials\""), wrong.body());
        assertTrue(wrong.headers().allValues("Set-Cookie").isEmpty());
        HttpResponse<String> unknown = postJson("/api/auth/login", "{\"username\":\"nobody\",\"password\":\"wrong horse\"}", null);
        assertEquals(wrong.body(), unknown.body());
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.login_failed'"));

        HttpResponse<String> ok = postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"username\":\"Siggi\"") && ok.body().contains("[\"admin\",\"user\"]"), ok.body());
        assertEquals("no-store", ok.headers().firstValue("Cache-Control").orElse(""));
        String sid = sidCookie(ok);
        assertEquals(2, count("SELECT COUNT(*) FROM sessions")); // setup's and this one

        // Logging in again from the same browser replaces its session.
        HttpResponse<String> again = postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", sid);
        String sid2 = sidCookie(again);
        assertFalse(sid.equals(sid2));
        assertEquals(2, count("SELECT COUNT(*) FROM sessions"));

        HttpResponse<String> out = postJson("/api/auth/logout", "", sid2);
        assertEquals(204, out.statusCode());
        assertEquals(List.of("sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure"), out.headers().allValues("Set-Cookie"));
        assertEquals(1, count("SELECT COUNT(*) FROM sessions"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.logout'"));
        // Idempotent, also without a cookie.
        assertEquals(204, postJson("/api/auth/logout", "", sid2).statusCode());
        HttpResponse<String> anonymous = postJson("/api/auth/logout", "", null);
        assertEquals(204, anonymous.statusCode());
        assertTrue(anonymous.headers().allValues("Set-Cookie").isEmpty());
    }

    private HttpResponse<String> send(String method, String path, String jsonBody, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
        }
        boolean origin = false;
        for (int i = 0; i < headers.length; i += 2) {
            origin |= headers[i].equalsIgnoreCase("Origin");
        }
        if (!origin) {
            b.header("Origin", PUBLIC_BASE_URL); // as a browser on our own page would
        }
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String setupAdmin() throws Exception {
        HttpResponse<String> created = post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"password\":\"correct horse\"}");
        assertEquals(201, created.statusCode(), created.body());
        return sidCookie(created);
    }

    private static String json(String body, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(body);
        assertTrue(m.find(), key + " in " + body);
        return m.group(1);
    }

    @Test
    void setupLogsTheAdminIn() throws Exception {
        String sid = setupAdmin();
        HttpResponse<String> list = send("GET", "/api/me/sessions", null, "Cookie", sid);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().matches("\\{\"items\":\\[\\{\"id\":\"[0-9a-f-]{36}\",\"kind\":\"cookie\","
                + "\"createdAt\":\\d+,\"lastSeenAt\":\\d+,\"expiresAt\":\\d+,\"userAgent\":\"Java-http-client/[^\"]+\","
                + "\"current\":true}]}"), list.body());
        assertEquals("no-store", list.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void bearerTokens() throws Exception {
        String sid = setupAdmin();
        HttpResponse<String> wrong = send("POST", "/api/auth/token", "{\"username\":\"siggi\",\"password\":\"nope nope nope\"}");
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("\"invalid_credentials\""), wrong.body());
        HttpResponse<String> anonymous = send("POST", "/api/auth/token", null);
        assertEquals(401, anonymous.statusCode());
        assertTrue(anonymous.body().contains("\"not_authenticated\""), anonymous.body());
        assertEquals(401, send("POST", "/api/auth/token", "{}").statusCode());

        HttpResponse<String> issued = send("POST", "/api/auth/token",
                "{\"username\":\"SIGGI\",\"password\":\"correct horse\"}", "User-Agent", "tt-app/1.0");
        assertEquals(201, issued.statusCode(), issued.body());
        assertTrue(issued.body().matches("\\{\"token\":\"[A-Za-z0-9_-]{43}\",\"tokenType\":\"Bearer\","
                + "\"sessionId\":\"[0-9a-f-]{36}\",\"expiresAt\":\\d+,\"user\":\\{.*\"username\":\"siggi\".*}}"),
                issued.body());
        assertTrue(issued.headers().allValues("Set-Cookie").isEmpty(), "a token request sets no cookie");
        assertEquals("no-store", issued.headers().firstValue("Cache-Control").orElse(""));
        String token = json(issued.body(), "token");
        String bearerId = json(issued.body(), "sessionId");
        String auth = "Bearer " + token;

        HttpResponse<String> list = send("GET", "/api/me/sessions", null, "Authorization", auth);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("{\"id\":\"" + bearerId + "\",\"kind\":\"bearer\""), list.body());
        assertTrue(list.body().contains("\"userAgent\":\"tt-app/1.0\""), list.body());
        assertTrue(list.body().matches(".*\"kind\":\"bearer\"[^}]*\"current\":true}.*"), list.body());
        assertTrue(list.body().matches(".*\"kind\":\"cookie\"[^}]*\"current\":false}.*"), list.body());
        assertFalse(list.body().contains("127.0.0.1"), "no IP in the session list");
        assertFalse(list.body().contains("\"ip"), list.body());

        // From a logged-in browser, without a body.
        HttpResponse<String> fromSession = send("POST", "/api/auth/token", null, "Cookie", sid);
        assertEquals(201, fromSession.statusCode(), fromSession.body());
        assertTrue(fromSession.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(3, count("SELECT COUNT(*) FROM sessions"));
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.token'"));
        assertEquals(0, count("SELECT COUNT(*) FROM sessions WHERE token_hash = '" + token + "'"), "only hashes stored");

        // Logging out with the token revokes it and leaves the cookie session alone.
        assertEquals(204, send("POST", "/api/auth/logout", null, "Authorization", auth).statusCode());
        HttpResponse<String> revoked = send("GET", "/api/me/sessions", null, "Authorization", auth);
        assertEquals(401, revoked.statusCode());
        assertTrue(revoked.body().contains("\"invalid_token\""), revoked.body());
        assertEquals(200, send("GET", "/api/me/sessions", null, "Cookie", sid).statusCode());
    }

    @Test
    void sessionListAndRevoke() throws Exception {
        String sid = setupAdmin();
        assertEquals(401, send("GET", "/api/me/sessions", null).statusCode());
        assertEquals(401, send("DELETE", "/api/me/sessions", null).statusCode());
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.OPEN);
        String aliceSid = sidCookie(postJson("/api/auth/register", "{\"username\":\"alice\",\"password\":\"0123456789\"}", null));
        String aliceSession = json(send("GET", "/api/me/sessions", null, "Cookie", aliceSid).body(), "id");

        String sid2 = sidCookie(postJson("/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null));
        String token = json(send("POST", "/api/auth/token", null, "Cookie", sid).body(), "token");
        HttpResponse<String> list = send("GET", "/api/me/sessions?limit=2", null, "Cookie", sid);
        assertTrue(list.body().contains("\"nextCursor\":\""), list.body());
        String cursor = json(list.body(), "nextCursor");
        HttpResponse<String> page2 = send("GET", "/api/me/sessions?limit=2&cursor=" + cursor, null, "Cookie", sid);
        assertFalse(page2.body().contains("nextCursor"), page2.body());
        assertEquals(400, send("GET", "/api/me/sessions?limit=0", null, "Cookie", sid).statusCode());
        assertEquals(400, send("GET", "/api/me/sessions?limit=x", null, "Cookie", sid).statusCode());

        // Someone else's session is not found, and stays.
        HttpResponse<String> notMine = send("DELETE", "/api/me/sessions/" + aliceSession, null, "Cookie", sid);
        assertEquals(404, notMine.statusCode());
        assertEquals(200, send("GET", "/api/me/sessions", null, "Cookie", aliceSid).statusCode());

        // Log out everywhere else: the second browser and the token go, this one stays.
        HttpResponse<String> others = send("DELETE", "/api/me/sessions", null, "Cookie", sid);
        assertEquals(200, others.statusCode(), others.body());
        assertEquals("{\"revoked\":2}", others.body());
        assertEquals(401, send("GET", "/api/me/sessions", null, "Cookie", sid2).statusCode());
        assertEquals(401, send("GET", "/api/me/sessions", null, "Authorization", "Bearer " + token).statusCode());

        // Revoking the current session logs this browser out.
        String current = json(send("GET", "/api/me/sessions", null, "Cookie", sid).body(), "id");
        HttpResponse<String> self = send("DELETE", "/api/me/sessions/" + current, null, "Cookie", sid);
        assertEquals(204, self.statusCode());
        assertEquals(List.of("sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure"), self.headers().allValues("Set-Cookie"));
        assertEquals(401, send("GET", "/api/me/sessions", null, "Cookie", sid).statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.session_revoked'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.sessions_revoked'"));
    }

    @Test
    void cookieSlidesWithUse() throws Exception {
        String sid = setupAdmin();
        HttpResponse<String> soon = send("GET", "/api/me/sessions", null, "Cookie", sid);
        assertTrue(soon.headers().allValues("Set-Cookie").isEmpty(), "not re-sent within a minute");

        clock.advance(2 * 60_000);
        HttpResponse<String> later = send("GET", "/api/me/sessions", null, "Cookie", sid);
        assertEquals(200, later.statusCode());
        assertEquals(List.of(sid + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax; Secure"),
                later.headers().allValues("Set-Cookie"));
        assertEquals(1, count("SELECT COUNT(*) FROM sessions WHERE expires_at = last_seen_at + 2592000000"
                + " AND last_seen_at > created_at"));
        assertTrue(send("GET", "/api/me/sessions", null, "Cookie", sid).headers().allValues("Set-Cookie").isEmpty());

        // Static files never resolve the actor, so they don't slide or set cookies.
        clock.advance(2 * 60_000);
        assertTrue(send("GET", "/setup.html", null, "Cookie", sid).headers().allValues("Set-Cookie").isEmpty());

        // A logout that slides first still sends only the clearing cookie.
        HttpResponse<String> out = send("POST", "/api/auth/logout", null, "Cookie", sid);
        assertEquals(List.of("sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure"), out.headers().allValues("Set-Cookie"));
    }

    @Test
    void csrfOriginCheckOnRealRoutes() throws Exception {
        String sid = setupAdmin();
        // logout from another site, or without Origin, is refused and the session survives
        assertEquals(403, send("POST", "/api/auth/logout", null, "Cookie", sid, "Origin", "https://evil.example").statusCode());
        HttpResponse<String> noOrigin = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/api/auth/logout"))
                .header("Cookie", sid).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noOrigin.statusCode(), noOrigin.body());
        assertTrue(noOrigin.body().contains("\"origin_not_allowed\""), noOrigin.body());
        assertTrue(noOrigin.headers().allValues("Set-Cookie").isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM sessions"));
        // reading is fine from anywhere (GET isn't state-changing)
        assertEquals(200, send("GET", "/api/me/sessions", null, "Cookie", sid, "Origin", "https://evil.example").statusCode());
        // login CSRF: a cross-site login is refused even without cookies
        assertEquals(403, send("POST", "/api/auth/login", "{\"username\":\"siggi\",\"password\":\"correct horse\"}",
                "Origin", "https://evil.example").statusCode());
        assertEquals(1, count("SELECT COUNT(*) FROM sessions"));
        // a bearer client needs no Origin even with a stray cookie
        String token = json(send("POST", "/api/auth/token", "{\"username\":\"siggi\",\"password\":\"correct horse\"}")
                .body(), "token");
        HttpResponse<String> bearer = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/api/auth/logout"))
                .header("Authorization", "Bearer " + token).header("Cookie", sid)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, bearer.statusCode(), bearer.body());
        // from our own page it works
        assertEquals(204, send("POST", "/api/auth/logout", null, "Cookie", sid).statusCode());
        assertEquals(0, count("SELECT COUNT(*) FROM sessions"));
    }

    @Test
    void webSocketUpgradeWithCookieNeedsOrigin() {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Cookie", "sid=abc");
        assertThrows(IOException.class, () -> new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers).close());
    }
}
