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
import io.siggi.togethertunes.auth.IdentityTickets;
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
    private IdentityTickets tickets;
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
        tickets = new IdentityTickets(clock);
        server = WebServer.start("127.0.0.1", 0, Main.router(config, staticFiles, actors,
                new SetupApi(accounts, sessions, config.secureCookies(), limits),
                new AuthApi(accounts, sessions, settings, config.secureCookies(), limits),
                new SessionsApi(sessions, accounts, config.secureCookies()),
                new MeApi(accounts, users, permissions, guests, settings, tickets),
                new AdminApi(database, accounts, users, new Roles(database), permissions, settings),
                io.siggi.togethertunes.auth.AdminActivityApi.create(database, guests, permissions, clock)), dir.toFile());

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
        assertNull(me.get("isGuest"));
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
        assertTrue(guest.get("isGuest").getAsBoolean());
        assertTrue(me.get("isGuest").getAsBoolean());

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
    void customNamesCantMatchAccounts() throws Exception {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        assertEquals("DJ Sig", call(200, "PATCH", "/api/me", "{\"displayName\":\"DJ Sig\"}", admin)
                .get("displayName").getAsString());
        String gid = cookie(send("POST", "/api/me/guest", null, null), "gid");
        // Username and display name, ignoring case, width (NFKC) and spacing.
        for (String taken : List.of("siggi", "SIGGI", "\\uff33\\uff49\\uff47\\uff47\\uff49", "dj sig", " DJ   SIG ")) {
            assertError("name_taken", call(409, "PATCH", "/api/me/guest", "{\"name\":\"" + taken + "\"}", gid));
        }
        assertError("name_taken", call(409, "POST", "/api/me/guest", "{\"name\":\"Siggi\"}", null));
        assertEquals(1, count("SELECT COUNT(*) FROM guests"));
        assertEquals("Siggi 2", call(200, "PATCH", "/api/me/guest", "{\"name\":\"Siggi 2\"}", gid)
                .get("name").getAsString());
        // Stored NFKC-normalised.
        assertEquals("Night Owl", call(200, "PATCH", "/api/me/guest",
                "{\"name\":\"\\uff2eight Owl\"}", gid).get("name").getAsString());
    }

    @Test
    void customNameBlocklist() throws Exception {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        settings.setJson("guests.nameBlocklist", "[\"Rude\", \"very bad\"]");
        String gid = cookie(send("POST", "/api/me/guest", null, null), "gid");
        for (String blocked : List.of("rude", "So RUDE", "R.U.D.E", "verybad", "Very-Bad Guy")) {
            assertError("name_blocked", call(400, "PATCH", "/api/me/guest", "{\"name\":\"" + blocked + "\"}", gid));
        }
        assertError("name_blocked", call(400, "POST", "/api/me/guest", "{\"name\":\"rude dude\"}", null));
        assertEquals("Very Good", call(200, "PATCH", "/api/me/guest", "{\"name\":\"Very Good\"}", gid)
                .get("name").getAsString());
        // Generated names are not checked against the blocklist.
        settings.setJson("guests.nameBlocklist", "[\"Generated\"]");
        assertTrue(call(201, "POST", "/api/me/guest", null, null).get("name").getAsString().startsWith("Generated"));
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
        assertEquals(me.get("displayName").getAsString(), me.get("name").getAsString());
        assertFalse(me.get("isGuest").getAsBoolean());
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

    /** Every {@code POST /api/me/guest} that returns a guest also returns a fresh identity ticket for it. */
    @Test
    void postGuestIssuesASingleUseIdentityTicket() throws Exception {
        HttpResponse<String> r = send("POST", "/api/me/guest", null, null);
        assertEquals(201, r.statusCode(), r.body());
        JsonObject created = JsonParser.parseString(r.body()).getAsJsonObject();
        String id = created.get("id").getAsString();
        String gid = cookie(r, "gid");
        String ticket = created.get("identityTicket").getAsString();
        assertEquals(43, ticket.length(), ticket);
        assertNull(call(200, "GET", "/api/me", null, gid).get("identityTicket"), "only POST issues tickets");

        // Already a guest (200): a new ticket each time, for the same guest.
        String second = call(200, "POST", "/api/me/guest", null, gid).get("identityTicket").getAsString();
        String third = call(200, "POST", "/api/me/guest", "{}", gid).get("identityTicket").getAsString();
        assertNotEquals(ticket, second);
        assertNotEquals(second, third);

        assertEquals(id, tickets.redeem(ticket).guestId());
        assertNull(tickets.redeem(ticket), "single use");
        assertEquals(id, tickets.redeem(second).guestId());

        // Valid for 60 s.
        clock.advance(IdentityTickets.TTL_MS);
        assertNull(tickets.redeem(third), "expired");

        // IP mode: also for an existing IP guest.
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        JsonObject ipGuest = call(201, "POST", "/api/me/guest", null, null);
        JsonObject ipAgain = call(200, "POST", "/api/me/guest", null, null);
        String ipId = ipGuest.get("id").getAsString();
        assertEquals(ipId, ipAgain.get("id").getAsString());
        assertEquals(ipId, tickets.redeem(ipGuest.get("identityTicket").getAsString()).guestId());
        assertEquals(ipId, tickets.redeem(ipAgain.get("identityTicket").getAsString()).guestId());

        // Refused requests get none.
        assertNull(call(409, "POST", "/api/me/guest", null, admin).get("identityTicket"));
        assertEquals(0, tickets.size());
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

    // ------------------------------------------------------------------ guest use

    private HttpResponse<String> getFrom(String path, String cookie, String forwardedFor) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("X-Forwarded-For", forwardedFor);
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(r.body().contains(forwardedFor), "no IP in responses: " + r.body());
        return r;
    }

    private long lastSeen(String guestId) throws Exception {
        return database.read(c -> {
            try (var ps = c.prepareStatement("SELECT last_seen_at FROM guests WHERE id = ?")) {
                ps.setString(1, guestId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Test
    void gidCookieSlidesAndIpLastFollowsTheClient() throws Exception {
        HttpResponse<String> r = send("POST", "/api/me/guest", null, null);
        assertEquals(201, r.statusCode(), r.body());
        String gid = cookie(r, "gid");
        String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
        long created = lastSeen(id);

        // Used again within a minute: no cookie, nothing written.
        clock.advance(30_000);
        HttpResponse<String> soon = send("GET", "/api/me", null, gid);
        assertEquals(id, JsonParser.parseString(soon.body()).getAsJsonObject().get("id").getAsString());
        assertNull(setCookie(soon, "gid"), "not re-sent within a minute");
        assertEquals(created, lastSeen(id));

        // After a minute: the same token comes back with a fresh one-year Max-Age.
        clock.advance(60_000);
        HttpResponse<String> later = send("GET", "/api/me", null, gid);
        assertEquals(List.of(gid + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax; Secure"),
                later.headers().allValues("Set-Cookie"));
        assertEquals(clock.millis(), lastSeen(id));

        // Static files never resolve the actor, so they neither slide nor set cookies.
        clock.advance(2 * 60_000);
        HttpResponse<String> file = send("GET", "/test/ws-echo.html", null, gid);
        assertEquals(200, file.statusCode());
        assertTrue(file.headers().allValues("Set-Cookie").isEmpty());

        // The guest's latest address is recorded (for ip.view), never shown.
        getFrom("/api/me", gid, "198.51.100.23");
        assertEquals("198.51.100.23", ipLast(id));
    }

    @Test
    void ipModeGuestKeepsItsIdentityAcrossIpv6InterfaceIds() throws Exception {
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        HttpRequest create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/me/guest"))
                .POST(HttpRequest.BodyPublishers.noBody()).header("Origin", PUBLIC_BASE_URL)
                .header("X-Forwarded-For", "2001:db8:7:8::1").build();
        HttpResponse<String> r = http.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, r.statusCode(), r.body());
        assertNull(setCookie(r, "gid"));
        String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
        assertEquals("2001:db8:7:8:0:0:0:1", normalised(ipLast(id)));

        // Same /64, another interface ID: the same guest, and ip_last follows.
        JsonObject me = JsonParser.parseString(getFrom("/api/me", null, "2001:db8:7:8:aaaa:bbbb:cccc:dddd").body())
                .getAsJsonObject();
        assertEquals(id, me.get("id").getAsString());
        assertEquals("2001:db8:7:8:aaaa:bbbb:cccc:dddd", normalised(ipLast(id)));
        // Another /64, or IPv4: someone else (anonymous until they act).
        assertEquals("anonymous", JsonParser.parseString(getFrom("/api/me", null, "2001:db8:7:9::1").body())
                .getAsJsonObject().get("kind").getAsString());
        assertEquals("anonymous", JsonParser.parseString(getFrom("/api/me", null, "198.51.100.23").body())
                .getAsJsonObject().get("kind").getAsString());
    }

    /** An IPv6 literal in full uncompressed lowercase form, so the test doesn't depend on how SiggiHTTP prints it. */
    private static String normalised(String ip) throws Exception {
        return java.net.InetAddress.getByName(ip).getHostAddress();
    }
}
