package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
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

/** {@code /api/me} and the guest identity ({@link MeApi}, {@link Guests}) over HTTP, as {@link Main#router} wires them. */
class MeApiTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Settings settings;
    private Guests guests;
    private ActorResolver actors;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(System.currentTimeMillis());
    private String admin; // sid cookie of the first account

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(MeApiTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        settings = new Settings(database, config);
        actors = new ActorResolver(database, settings, clock, config.secureCookies());
        Passwords passwords = new Passwords(new Passwords.Params(64, 1, 1));
        Accounts accounts = new Accounts(database, passwords, clock);
        Sessions sessions = new Sessions(database, clock);
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock));
        Users users = new Users(database, passwords, clock);
        Permissions permissions = new Permissions(database, settings);
        guests = new Guests(database, settings, clock, config.secureCookies(), new Names());
        server = WebServer.start("127.0.0.1", 0, Main.router(config, staticFiles, actors,
                new SetupApi(accounts, sessions, config.secureCookies(), limits),
                new AuthApi(accounts, sessions, settings, config.secureCookies(), limits),
                new SessionsApi(sessions, accounts, config.secureCookies()),
                new MeApi(accounts, users, permissions, guests, settings),
                new AdminApi(database, accounts, users, new Roles(database), permissions, settings)), dir.toFile());

        HttpResponse<String> created = send(server, "POST", "/api/setup",
                "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null);
        assertEquals(201, created.statusCode(), created.body());
        admin = cookie(created, "sid");
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    /** Deterministic generated names: Generated 1, Generated 2, ... */
    private static final class Names implements java.util.function.Supplier<String> {
        private int n;

        @Override
        public synchronized String get() {
            return "Generated " + (++n);
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> send(WebServer to, String method, String path, String json, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Origin", PUBLIC_BASE_URL);
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String json, String cookie) throws Exception {
        HttpResponse<String> r = send(server, method, path, json, cookie);
        assertFalse(r.body().contains("127.0.0.1"), "no IP in responses: " + r.body());
        return r;
    }

    /** Sends and checks the status; returns the body as JSON. */
    private JsonObject call(int status, String method, String path, String json, String cookie) throws Exception {
        HttpResponse<String> r = send(method, path, json, cookie);
        assertEquals(status, r.statusCode(), method + " " + path + ": " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static void assertError(String code, JsonObject body) {
        assertEquals(code, body.getAsJsonObject("error").get("code").getAsString(), body.toString());
    }

    /** {@code name=value} of the named Set-Cookie, or {@code null}. */
    private static String cookie(HttpResponse<String> r, String name) {
        String header = setCookie(r, name);
        return header == null ? null : header.substring(0, header.indexOf(';'));
    }

    private static String setCookie(HttpResponse<String> r, String name) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(name + "=")) {
                return c;
            }
        }
        return null;
    }

    private long count(String sql) throws Exception {
        return database.read(c -> {
            try (var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String ipLast(String guestId) throws Exception {
        return database.read(c -> {
            try (var ps = c.prepareStatement("SELECT ip_last FROM guests WHERE id = ?")) {
                ps.setString(1, guestId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void anonymousMe() throws Exception {
        JsonObject me = call(200, "GET", "/api/me", null, null);
        assertEquals("anonymous", me.get("kind").getAsString());
        assertTrue(me.get("guestsEnabled").getAsBoolean());
        assertFalse(me.get("customNames").getAsBoolean());

        settings.set(Settings.GUESTS_ENABLED, false);
        assertFalse(call(200, "GET", "/api/me", null, null).get("guestsEnabled").getAsBoolean());
    }

    @Test
    void cookieModeGuestIsCreatedOnceAndThenRecognised() throws Exception {
        HttpResponse<String> r = send("POST", "/api/me/guest", null, null);
        assertEquals(201, r.statusCode(), r.body());
        String setCookie = setCookie(r, "gid");
        assertTrue(setCookie.contains("Max-Age=" + Guests.COOKIE_MAX_AGE_SECONDS), setCookie);
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Lax") && setCookie.contains("Secure"),
                setCookie);
        assertTrue(setCookie.contains("Path=/"), setCookie);
        JsonObject guest = JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals("guest", guest.get("kind").getAsString());
        assertEquals("Generated 1", guest.get("name").getAsString());
        assertFalse(guest.get("nameChosen").getAsBoolean());
        String gid = cookie(r, "gid");
        String id = guest.get("id").getAsString();

        JsonObject me = call(200, "GET", "/api/me", null, gid);
        assertEquals("guest", me.get("kind").getAsString());
        assertEquals(id, me.get("id").getAsString());

        // Asking again with the cookie returns the same guest, without a new row or cookie.
        HttpResponse<String> again = send("POST", "/api/me/guest", null, gid);
        assertEquals(200, again.statusCode(), again.body());
        assertNull(setCookie(again, "gid"));
        assertEquals(id, JsonParser.parseString(again.body()).getAsJsonObject().get("id").getAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM guests"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'guest.created' AND actor_guest_id = '"
                + id + "'"));
        assertEquals("cookie", database.read(c -> {
            try (var ps = c.prepareStatement("SELECT kind FROM guests"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }));
        // Stored for ip.view only, never in a response (checked by send()).
        assertEquals("127.0.0.1", ipLast(id));

        // Another browser (no cookie) gets a different guest.
        HttpResponse<String> other = send("POST", "/api/me/guest", "{}", null);
        assertEquals(201, other.statusCode(), other.body());
        assertNotEquals(id, JsonParser.parseString(other.body()).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void ipModeGuestIsKeyedByTheClientAddress() throws Exception {
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        assertEquals("anonymous", call(200, "GET", "/api/me", null, null).get("kind").getAsString());

        HttpResponse<String> r = send("POST", "/api/me/guest", null, null);
        assertEquals(201, r.statusCode(), r.body());
        assertNull(setCookie(r, "gid"), "IP mode sets no cookie");
        String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();

        // Same address, no cookie: the same guest.
        JsonObject me = call(200, "GET", "/api/me", null, null);
        assertEquals("guest", me.get("kind").getAsString());
        assertEquals(id, me.get("id").getAsString());
        assertEquals(id, call(200, "POST", "/api/me/guest", null, null).get("id").getAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM guests WHERE kind = 'ip'"));
        assertEquals("127.0.0.1", ipLast(id));
    }

    @Test
    void ipModeCreationIsIdempotentForOneAddress() throws Exception {
        Guests.Created first = guests.create(GuestIdentity.IP, "2001:db8:1:2::5", null);
        Guests.Created second = guests.create(GuestIdentity.IP, "2001:db8:1:2:aaaa::9", null);
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.guest().id(), second.guest().id(), "IPv6 guests share their /64");
        assertNull(first.cookieToken());
        assertNotEquals(first.guest().id(), guests.create(GuestIdentity.IP, "2001:db8:1:3::5", null).guest().id());
    }

    @Test
    void guestsDisabled() throws Exception {
        settings.set(Settings.GUESTS_ENABLED, false);
        assertError("guests_disabled", call(403, "POST", "/api/me/guest", null, null));
        assertEquals(0, count("SELECT COUNT(*) FROM guests"));
    }

    @Test
    void customNames() throws Exception {
        // Off by default.
        assertError("custom_names_disabled", call(403, "POST", "/api/me/guest", "{\"name\":\"Night Owl\"}", null));
        HttpResponse<String> r = send("POST", "/api/me/guest", null, null);
        String gid = cookie(r, "gid");
        assertError("custom_names_disabled", call(403, "PATCH", "/api/me/guest", "{\"name\":\"Night Owl\"}", gid));

        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        JsonObject renamed = call(200, "PATCH", "/api/me/guest", "{\"name\":\"  Night \\t  Owl \"}", gid);
        assertEquals("Night Owl", renamed.get("name").getAsString());
        assertTrue(renamed.get("nameChosen").getAsBoolean());
        assertTrue(renamed.get("customNames").getAsBoolean());
        assertEquals("Night Owl", call(200, "GET", "/api/me", null, gid).get("name").getAsString());
        assertEquals("Early Bird", call(200, "PATCH", "/api/me", "{\"name\":\"Early Bird\"}", gid)
                .get("name").getAsString());
        assertError("invalid_name", call(400, "PATCH", "/api/me/guest", "{\"name\":\"x\"}", gid));
        assertError("invalid_name", call(400, "PATCH", "/api/me/guest", "{\"name\":\"" + "y".repeat(33) + "\"}", gid));
        assertError("invalid_name", call(400, "PATCH", "/api/me/guest", "{\"name\":\"a\\u0000b\"}", gid));
        assertError("invalid_name", call(400, "PATCH", "/api/me/guest", "{}", gid));

        // A name at creation; POST with a name from an existing guest renames it.
        HttpResponse<String> named = send("POST", "/api/me/guest", "{\"name\":\"Quiet Walrus\"}", null);
        assertEquals(201, named.statusCode(), named.body());
        JsonObject guest = JsonParser.parseString(named.body()).getAsJsonObject();
        assertEquals("Quiet Walrus", guest.get("name").getAsString());
        assertTrue(guest.get("nameChosen").getAsBoolean());
        assertEquals("Loud Walrus", call(200, "POST", "/api/me/guest", "{\"name\":\"Loud Walrus\"}",
                cookie(named, "gid")).get("name").getAsString());
        assertError("invalid_name", call(400, "POST", "/api/me/guest", "{\"name\":\" \"}", null));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'guest.renamed' AND details_json LIKE '%Loud Walrus%'"));
    }

    @Test
    void bannedGuestCantRename() throws Exception {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        String gid = cookie(send("POST", "/api/me/guest", null, null), "gid");
        database.write(c -> {
            try (var ps = c.prepareStatement("UPDATE guests SET banned = 1")) {
                return ps.executeUpdate();
            }
        });
        assertError("guest_banned", call(403, "PATCH", "/api/me/guest", "{\"name\":\"Night Owl\"}", gid));
    }

    @Test
    void anonymousCantRename() throws Exception {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        assertError("identity_required", call(401, "PATCH", "/api/me/guest", "{\"name\":\"Night Owl\"}", null));
        assertError("identity_required", call(401, "PATCH", "/api/me", "{\"name\":\"Night Owl\"}", null));
        assertEquals(0, count("SELECT COUNT(*) FROM guests"));
    }

    @Test
    void users() throws Exception {
        JsonObject me = call(200, "GET", "/api/me", null, admin);
        assertEquals("user", me.get("kind").getAsString());
        assertEquals("siggi", me.get("username").getAsString());
        assertTrue(me.getAsJsonArray("roles").toString().contains("admin"));
        assertTrue(me.getAsJsonArray("permissions").toString().contains("admin.users"), me.toString());
        assertError("already_logged_in", call(409, "POST", "/api/me/guest", null, admin));
        assertError("not_a_guest", call(409, "PATCH", "/api/me/guest", "{\"name\":\"Night Owl\"}", admin));
        assertEquals(0, count("SELECT COUNT(*) FROM guests"));

        assertEquals("Siggi B", call(200, "PATCH", "/api/me", "{\"displayName\":\"Siggi B\"}", admin)
                .get("displayName").getAsString());
        assertEquals("Siggi B", call(200, "PATCH", "/api/me", "{}", admin).get("displayName").getAsString());
        assertEquals("siggi", call(200, "PATCH", "/api/me", "{\"displayName\":\"\"}", admin)
                .get("displayName").getAsString());
    }

    /** {@link Guests#requireIdentity} as an identity-requiring action would use it. */
    @Test
    void lazyCreationOnFirstIdentityRequiringAction() throws Exception {
        Router router = new Router(actors.hook());
        router.post("/act", (request, response) -> {
            Actor before = ActorResolver.actor(request);
            Actor actor = guests.requireIdentity(request, response);
            // Later reads in the same request see the new identity.
            assertEquals(actor, ActorResolver.actor(request));
            String who = actor instanceof Actor.Guest g ? "guest:" + g.guestId()
                    : actor instanceof Actor.User u ? "user:" + u.userId() : "anonymous";
            response.json(Map.of("before", before.getClass().getSimpleName(), "who", who));
        });
        try (WebServer action = WebServer.start("127.0.0.1", 0, router, dir.toFile())) {
            HttpResponse<String> first = send(action, "POST", "/act", null, null);
            assertEquals(200, first.statusCode(), first.body());
            JsonObject body = JsonParser.parseString(first.body()).getAsJsonObject();
            assertEquals("Anonymous", body.get("before").getAsString());
            String who = body.get("who").getAsString();
            assertTrue(who.startsWith("guest:"), who);
            String gid = cookie(first, "gid");

            HttpResponse<String> second = send(action, "POST", "/act", null, gid);
            body = JsonParser.parseString(second.body()).getAsJsonObject();
            assertEquals("Guest", body.get("before").getAsString());
            assertEquals(who, body.get("who").getAsString());
            assertNull(setCookie(second, "gid"));
            assertEquals(1, count("SELECT COUNT(*) FROM guests"));

            body = JsonParser.parseString(send(action, "POST", "/act", null, admin).body()).getAsJsonObject();
            assertTrue(body.get("who").getAsString().startsWith("user:"));
            assertEquals(1, count("SELECT COUNT(*) FROM guests"));

            settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
            HttpResponse<String> ip = send(action, "POST", "/act", null, null);
            assertNull(setCookie(ip, "gid"));
            assertEquals(1, count("SELECT COUNT(*) FROM guests WHERE kind = 'ip'"));
            assertEquals(JsonParser.parseString(ip.body()).getAsJsonObject().get("who").getAsString(),
                    JsonParser.parseString(send(action, "POST", "/act", null, null).body()).getAsJsonObject()
                            .get("who").getAsString());

            settings.set(Settings.GUESTS_ENABLED, false);
            database.write(c -> {
                try (var ps = c.prepareStatement("DELETE FROM guests")) {
                    return ps.executeUpdate();
                }
            });
            assertEquals(403, send(action, "POST", "/act", null, null).statusCode());
        }
    }

    @Test
    void customNameValidation() {
        assertEquals("Ab", Guests.validCustomName(" Ab "));
        assertEquals("a b c", Guests.validCustomName("a \n b  c"));
        assertEquals(32, Guests.validCustomName("z".repeat(32)).length());
        for (String bad : List.of("", "a", " a ", "z".repeat(33), "a​b", "a\u0007b")) {
            try {
                Guests.validCustomName(bad);
                throw new AssertionError("accepted " + bad);
            } catch (io.siggi.togethertunes.http.HttpError e) {
                assertEquals("invalid_name", e.code());
            }
        }
    }
}
