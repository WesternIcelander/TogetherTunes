package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.AdminApi;
import io.siggi.togethertunes.auth.AuthApi;
import io.siggi.togethertunes.auth.AuthRateLimits;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.MeApi;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.SessionsApi;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.auth.Users;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.config.X264Preset;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The admin API ({@link AdminApi}) over HTTP, as {@link Main#router} wires it. */
class AdminApiTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private Settings settings;
    private final long freeBytes = 8 * Settings.GIB;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(System.currentTimeMillis());
    private String admin; // sid cookie of the first account
    private String adminId;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(AdminApiTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        settings = new Settings(database, config);
        ActorResolver actors = new ActorResolver(database, settings, clock, config.secureCookies());
        Passwords passwords = new Passwords(new Passwords.Params(64, 1, 1));
        Accounts accounts = new Accounts(database, passwords, clock);
        Sessions sessions = new Sessions(database, clock);
        AuthRateLimits limits = new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock));
        SetupApi setup = new SetupApi(accounts, sessions, config.secureCookies(), limits);
        AuthApi auth = new AuthApi(accounts, sessions, settings, config.secureCookies(), limits);
        SessionsApi sessionsApi = new SessionsApi(sessions, accounts, config.secureCookies());
        AdminApi adminApi = new AdminApi(database, accounts, new Users(database, passwords, clock), new Roles(database),
                new Permissions(database, settings), settings);
        // as Main wires it, with a fake check: this "x264 build" can't do veryslow with the reference avcC
        io.siggi.togethertunes.ingest.EncodePresets presets = new io.siggi.togethertunes.ingest.EncodePresets(
                p -> p != X264Preset.VERYSLOW, settings::ingestQuickPreset, settings::ingestQualityPreset);
        adminApi.settingCheck((key, value) -> {
            if (value instanceof X264Preset preset) {
                presets.validate(key.name(), preset);
            }
        });
        // a fake library: 7 ready items, all of them encoded for the default encoding
        adminApi.encodingInfo(new AdminApi.EncodingInfo() {
            @Override
            public io.siggi.togethertunes.config.OutputEncoding current() {
                return io.siggi.togethertunes.config.OutputEncoding.DEFAULT;
            }

            @Override
            public long reencodeCount(io.siggi.togethertunes.config.OutputEncoding target) {
                return target.equals(io.siggi.togethertunes.config.OutputEncoding.DEFAULT) ? 0 : 7;
            }
        });
        // a fake data volume: 8 GiB free of 100, against the storage.minFreeBytes setting
        adminApi.diskSpace(new io.siggi.togethertunes.storage.DiskSpace(
                () -> new io.siggi.togethertunes.storage.DiskSpace.Usage(freeBytes, 100 * Settings.GIB),
                settings::storageMinFreeBytes, java.time.Duration.ZERO, System::nanoTime));
        TestUploads.RoomsWiring roomsWiring = TestUploads.rooms(database, dir, config, settings,
                new Permissions(database, settings), actors, new io.siggi.togethertunes.auth.IdentityTickets(), clock);
        server = WebServer.start("127.0.0.1", 0,
                Main.router(config, staticFiles, actors, setup, auth, sessionsApi,
                        new MeApi(accounts, new Users(database, passwords, clock), new Permissions(database, settings),
                                new Guests(database, settings, config.secureCookies()), settings,
                                new io.siggi.togethertunes.auth.IdentityTickets()), adminApi,
                        TestUploads.providersApi(database, config, settings, accounts),
                        io.siggi.togethertunes.auth.AdminActivityApi.create(database,
                                new Guests(database, settings, config.secureCookies()), new Permissions(database, settings),
                                clock),
                        TestUploads.api(database, dir, config, new Permissions(database, settings), clock),
                TestUploads.youtubeApi(database, config, new Permissions(database, settings), clock),
                TestUploads.libraryApi(database, config, new Permissions(database, settings), clock),
                TestUploads.itemsApi(database, dir, new Permissions(database, settings), clock),
                TestUploads.playlistsApi(database, config, new Permissions(database, settings), clock),
                TestUploads.userLibrariesApi(database, config, new Permissions(database, settings), clock),
                TestUploads.mediaApi(database, dir, io.siggi.togethertunes.rooms.GuestMediaAccess.USERS_ONLY),
                roomsWiring.api(), roomsWiring.socket()), dir.toFile());

        HttpResponse<String> created = send("POST", "/api/setup", "{\"username\":\"siggi\",\"password\":\"correct horse\"}", null);
        assertEquals(201, created.statusCode(), created.body());
        admin = sid(created);
        adminId = obj(created).getAsJsonObject("user").get("id").getAsString();
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> send(String method, String path, String json, String cookie)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
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

    /** Sends and checks the status; returns the body as JSON ({@code null} if empty). */
    private JsonObject call(int status, String method, String path, String json, String cookie) throws Exception {
        HttpResponse<String> r = send(method, path, json, cookie);
        assertEquals(status, r.statusCode(), method + " " + path + ": " + r.body());
        assertFalse(r.body().contains("127.0.0.1"), "no IP in responses: " + r.body());
        return r.body().isEmpty() ? null : obj(r);
    }

    private static void assertError(String code, JsonObject body) {
        assertEquals(code, body.getAsJsonObject("error").get("code").getAsString(), body.toString());
    }

    private static JsonObject obj(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String sid(HttpResponse<String> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("sid=")) {
                return c.substring(0, c.indexOf(';'));
            }
        }
        throw new AssertionError("no sid cookie: " + r.headers().map());
    }

    private String login(String username, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", null);
        assertEquals(200, r.statusCode(), r.body());
        return sid(r);
    }

    /** Creates a user through the admin API; returns its id. */
    private String createUser(String username, String password) throws Exception {
        return call(201, "POST", "/api/admin/users",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", admin).get("id").getAsString();
    }

    private String roleId(String name) throws Exception {
        for (JsonElement e : call(200, "GET", "/api/admin/roles", null, admin).getAsJsonArray("items")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
                return e.getAsJsonObject().get("id").getAsString();
            }
        }
        throw new AssertionError("no role " + name);
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.isJsonObject() ? e.getAsJsonObject().get("name").getAsString() : e.getAsString()));
        return out;
    }

    private long count(String sql) throws Exception {
        return database.read(c -> {
            try (var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void guestsAuditAndSessionIpsShowIpsOnlyToIpView() throws Exception {
        // A cookie guest (created from 127.0.0.1, like every request here).
        HttpResponse<String> guestCreated = send("POST", "/api/me/guest", null, null);
        assertEquals(201, guestCreated.statusCode(), guestCreated.body());
        String guestId = obj(guestCreated).get("id").getAsString();
        String gid = guestCreated.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("gid="))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();

        String helpdeskRole = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"helpdesk\",\"permissions\":[\"admin.users\"]}", admin).get("id").getAsString();
        String ipRole = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"ip\",\"permissions\":[\"ip.view\"]}", admin).get("id").getAsString();
        String helpId = createUser("help", "temporary-1");
        call(200, "PUT", "/api/admin/users/" + helpId + "/roles", "{\"roleIds\":[\"" + helpdeskRole + "\"]}", admin);
        String help = login("help", "temporary-1");
        String ipId = createUser("ipview", "temporary-1");
        call(200, "PUT", "/api/admin/users/" + ipId + "/roles", "{\"roleIds\":[\"" + ipRole + "\"]}", admin);
        String ipView = login("ipview", "temporary-1");

        // admin.users without ip.view: guests and audit log, no IP anywhere (call() checks the body).
        JsonObject guests = call(200, "GET", "/api/admin/guests", null, help);
        JsonObject g = guests.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(guestId, g.get("id").getAsString());
        assertEquals("cookie", g.get("kind").getAsString());
        assertFalse(g.has("ip"), g.toString());
        assertTrue(g.has("lastSeenAt") && g.has("createdAt") && g.has("name") && g.has("banned"));
        JsonObject audit = call(200, "GET", "/api/admin/audit", null, help);
        assertTrue(audit.getAsJsonArray("items").size() > 3);
        for (JsonElement e : audit.getAsJsonArray("items")) {
            assertFalse(e.getAsJsonObject().has("ip"), e.toString());
        }
        JsonObject setup = call(200, "GET", "/api/admin/audit?action=account.setup", null, help)
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(adminId, setup.getAsJsonObject("actor").get("id").getAsString());
        assertFalse(setup.getAsJsonObject("actor").get("isGuest").getAsBoolean());
        JsonObject guestAudit = call(200, "GET", "/api/admin/audit?actor=" + guestId, null, help)
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("guest.created", guestAudit.get("action").getAsString());
        assertEquals("cookie", guestAudit.getAsJsonObject("details").get("kind").getAsString());
        assertTrue(guestAudit.getAsJsonObject("actor").get("isGuest").getAsBoolean());
        assertError("forbidden", call(403, "GET", "/api/admin/ip/sessions/" + adminId, null, help));

        // ip.view without admin.users: session IPs only.
        assertError("forbidden", call(403, "GET", "/api/admin/guests", null, ipView));
        assertError("forbidden", call(403, "GET", "/api/admin/audit", null, ipView));
        HttpResponse<String> sessions = send("GET", "/api/admin/ip/sessions/" + adminId, null, ipView);
        assertEquals(200, sessions.statusCode(), sessions.body());
        JsonObject s = obj(sessions);
        assertEquals(adminId, s.getAsJsonObject("user").get("id").getAsString());
        JsonObject first = s.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("127.0.0.1", first.get("ip").getAsString());
        assertEquals("cookie", first.get("kind").getAsString());
        assertEquals("no-store", sessions.headers().firstValue("Cache-Control").orElse(null));
        assertError("user_not_found", call(404, "GET", "/api/admin/ip/sessions/nope", null, ipView));

        // The admin holds both: IPs in all three.
        HttpResponse<String> r = send("GET", "/api/admin/guests", null, admin);
        assertEquals("127.0.0.1", obj(r).getAsJsonArray("items").get(0).getAsJsonObject().get("ip").getAsString());
        r = send("GET", "/api/admin/audit?action=account.setup", null, admin);
        assertEquals("127.0.0.1", obj(r).getAsJsonArray("items").get(0).getAsJsonObject().get("ip").getAsString());

        // Guests and anonymous visitors: 401 everywhere.
        for (String path : List.of("/api/admin/guests", "/api/admin/audit", "/api/admin/ip/sessions/" + adminId)) {
            assertError("not_authenticated", call(401, "GET", path, null, gid));
            assertError("not_authenticated", call(401, "GET", path, null, null));
        }

        // Paging and errors.
        JsonObject page = call(200, "GET", "/api/admin/audit?limit=1", null, help);
        assertEquals(1, page.getAsJsonArray("items").size());
        JsonObject next = call(200, "GET", "/api/admin/audit?limit=1&cursor=" + page.get("nextCursor").getAsString(),
                null, help);
        assertFalse(next.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString()
                .equals(page.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString()));
        assertError("invalid_limit", call(400, "GET", "/api/admin/guests?limit=201", null, help));
        assertError("invalid_cursor", call(400, "GET", "/api/admin/audit?cursor=nope", null, help));
        assertEquals(0, call(200, "GET", "/api/admin/guests?q=zzzz", null, help).getAsJsonArray("items").size());
    }

    @Test
    void everyRouteNeedsItsPermission() throws Exception {
        String bobId = createUser("bob", "temporary-1");
        String bob = login("bob", "temporary-1");
        String[][] routes = {
                {"GET", "/api/admin/users"}, {"POST", "/api/admin/users"}, {"GET", "/api/admin/users/" + bobId},
                {"PATCH", "/api/admin/users/" + bobId}, {"POST", "/api/admin/users/" + bobId + "/password"},
                {"GET", "/api/admin/users/" + bobId + "/roles"}, {"PUT", "/api/admin/users/" + bobId + "/roles"},
                {"GET", "/api/admin/roles"}, {"POST", "/api/admin/roles"}, {"GET", "/api/admin/roles/x"},
                {"PATCH", "/api/admin/roles/x"}, {"DELETE", "/api/admin/roles/x"},
                {"GET", "/api/admin/roles/x/permissions"}, {"PUT", "/api/admin/roles/x/permissions"},
                {"GET", "/api/admin/settings"}, {"PATCH", "/api/admin/settings"}, {"GET", "/api/admin/storage"},
                {"GET", "/api/admin/encoding"}, {"GET", "/api/admin/guests"}, {"GET", "/api/admin/audit"}, {"GET", "/api/admin/ip/sessions/" + bobId},
        };
        for (String[] route : routes) {
            String body = route[0].equals("GET") || route[0].equals("DELETE") ? null : "{}";
            assertError("not_authenticated", call(401, route[0], route[1], body, null));
            assertError("forbidden", call(403, route[0], route[1], body, bob));
        }
    }

    @Test
    void usersTemporaryPasswordsAndDisabling() throws Exception {
        JsonObject created = call(201, "POST", "/api/admin/users",
                "{\"username\":\"Bob\",\"displayName\":\"Bobby\",\"password\":\"temporary-1\"}", admin);
        String bobId = created.get("id").getAsString();
        assertEquals("Bobby", created.get("displayName").getAsString());
        assertTrue(created.get("mustChangePassword").getAsBoolean());
        assertTrue(created.get("hasPassword").getAsBoolean());
        assertFalse(created.get("disabled").getAsBoolean());
        assertEquals(List.of("user"), strings(created.getAsJsonArray("roles")));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'admin.user_created' AND actor_user_id = '"
                + adminId + "' AND target = '" + bobId + "'"));
        assertError("username_taken", call(409, "POST", "/api/admin/users",
                "{\"username\":\"bob\",\"password\":\"temporary-1\"}", admin));
        assertError("invalid_password", call(400, "POST", "/api/admin/users",
                "{\"username\":\"carl\",\"password\":\"short\"}", admin));

        // List and search.
        JsonObject list = call(200, "GET", "/api/admin/users", null, admin);
        assertEquals(List.of("Bob", "siggi"), list.getAsJsonArray("items").asList().stream()
                .map(e -> e.getAsJsonObject().get("username").getAsString()).toList());
        assertFalse(list.has("nextCursor"));
        JsonObject page = call(200, "GET", "/api/admin/users?limit=1", null, admin);
        assertEquals("bob", page.get("nextCursor").getAsString());
        assertEquals(1, call(200, "GET", "/api/admin/users?q=BOBB", null, admin).getAsJsonArray("items").size());
        assertError("invalid_limit", call(400, "GET", "/api/admin/users?limit=0", null, admin));
        assertError("user_not_found", call(404, "GET", "/api/admin/users/nope", null, admin));

        // Bob logs in with the temporary password and replaces it.
        HttpResponse<String> login = send("POST", "/api/auth/login", "{\"username\":\"bob\",\"password\":\"temporary-1\"}", null);
        assertTrue(obj(login).getAsJsonObject("user").get("mustChangePassword").getAsBoolean(), login.body());
        String bob = sid(login);
        assertError("wrong_password", call(403, "POST", "/api/me/password",
                "{\"currentPassword\":\"nope-nope-nope\",\"newPassword\":\"bobs-own-password\"}", bob));
        call(204, "POST", "/api/me/password",
                "{\"currentPassword\":\"temporary-1\",\"newPassword\":\"bobs-own-password\"}", bob);
        assertFalse(call(200, "GET", "/api/admin/users/" + bobId, null, admin).get("mustChangePassword").getAsBoolean());
        assertError("not_authenticated", call(401, "POST", "/api/me/password",
                "{\"currentPassword\":\"x\",\"newPassword\":\"bobs-own-password\"}", null));

        // Admin resets it: Bob is logged out everywhere.
        call(204, "POST", "/api/admin/users/" + bobId + "/password", "{\"password\":\"temporary-2\"}", admin);
        call(401, "GET", "/api/me/sessions", null, bob);
        assertTrue(call(200, "GET", "/api/admin/users/" + bobId, null, admin).get("mustChangePassword").getAsBoolean());
        bob = login("bob", "temporary-2");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'admin.user_password_set'"));

        // Display name and disabling.
        JsonObject patched = call(200, "PATCH", "/api/admin/users/" + bobId, "{\"displayName\":\"  Robert \"}", admin);
        assertEquals("Robert", patched.get("displayName").getAsString());
        assertTrue(call(200, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":true}", admin).get("disabled").getAsBoolean());
        call(401, "GET", "/api/me/sessions", null, bob);
        assertError("account_disabled", call(403, "POST", "/api/auth/login",
                "{\"username\":\"bob\",\"password\":\"temporary-2\"}", null));
        assertFalse(call(200, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":false}", admin).get("disabled").getAsBoolean());
        login("bob", "temporary-2");
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log WHERE action = 'admin.user_updated'"));
        assertError("unknown_field", call(400, "PATCH", "/api/admin/users/" + bobId, "{\"username\":\"x\"}", admin));
        assertError("invalid_json", call(400, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":\"yes\"}", admin));

        assertError("cannot_disable_self", call(409, "PATCH", "/api/admin/users/" + adminId, "{\"disabled\":true}", admin));
        assertError("last_admin", call(409, "PUT", "/api/admin/users/" + adminId + "/roles",
                "{\"roleIds\":[\"" + roleId("user") + "\"]}", admin));
    }

    @Test
    void rolesAndRolePermissions() throws Exception {
        JsonObject list = call(200, "GET", "/api/admin/roles", null, admin);
        assertEquals(List.of("admin", "user"), strings(list.getAsJsonArray("items")));
        JsonArray perms = list.getAsJsonArray("permissions");
        assertEquals(17, perms.size());
        JsonObject youtube = perms.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(p -> p.get("name").getAsString().equals("library.youtube")).findFirst().orElseThrow();
        assertEquals("ytdlp.enabled", youtube.get("feature").getAsString());
        assertTrue(youtube.get("featureEnabled").getAsBoolean());

        JsonObject mods = call(201, "POST", "/api/admin/roles",
                "{\"name\":\" Room  mods \",\"permissions\":[\"rooms.moderateAny\"]}", admin);
        String modsId = mods.get("id").getAsString();
        assertEquals("Room mods", mods.get("name").getAsString());
        assertEquals(List.of("rooms.moderateAny"), strings(mods.getAsJsonArray("permissions")));
        assertFalse(mods.get("isDefault").getAsBoolean());
        assertTrue(mods.get("editable").getAsBoolean());
        assertError("role_name_taken", call(409, "POST", "/api/admin/roles", "{\"name\":\"ROOM MODS\"}", admin));
        assertError("unknown_permission", call(400, "POST", "/api/admin/roles",
                "{\"name\":\"x\",\"permissions\":[\"root\"]}", admin));
        assertError("invalid_role_name", call(400, "POST", "/api/admin/roles", "{\"name\":\"-x\"}", admin));

        JsonObject renamed = call(200, "PATCH", "/api/admin/roles/" + modsId, "{\"name\":\"Moderators\",\"isDefault\":true}", admin);
        assertEquals("Moderators", renamed.get("name").getAsString());
        assertTrue(renamed.get("isDefault").getAsBoolean());
        JsonObject withPerms = call(200, "PUT", "/api/admin/roles/" + modsId + "/permissions",
                "{\"permissions\":[\"rooms.moderateAny\",\"library.editAny\"]}", admin);
        assertEquals(List.of("library.editAny", "rooms.moderateAny"), strings(withPerms.getAsJsonArray("permissions")));
        assertEquals(List.of("library.editAny", "rooms.moderateAny"),
                strings(call(200, "GET", "/api/admin/roles/" + modsId + "/permissions", null, admin).getAsJsonArray("permissions")));

        // Assign it; new accounts get it as a default role too.
        String bobId = createUser("bob", "temporary-1");
        assertEquals(List.of("user", "Moderators"), strings(call(200, "GET", "/api/admin/users/" + bobId + "/roles", null, admin)
                .getAsJsonArray("items")));
        JsonObject set = call(200, "PUT", "/api/admin/users/" + bobId + "/roles",
                "{\"roleIds\":[\"" + modsId + "\"]}", admin);
        assertEquals(List.of("Moderators"), strings(set.getAsJsonArray("items")));
        assertError("unknown_role", call(400, "PUT", "/api/admin/users/" + bobId + "/roles", "{\"roleIds\":[\"nope\"]}", admin));

        // Built-in roles.
        String adminRole = roleId("admin");
        String userRole = roleId("user");
        assertError("role_immutable", call(409, "PATCH", "/api/admin/roles/" + adminRole, "{\"name\":\"boss\"}", admin));
        assertError("role_immutable", call(409, "PATCH", "/api/admin/roles/" + adminRole, "{\"isDefault\":false}", admin));
        assertError("role_immutable", call(409, "PUT", "/api/admin/roles/" + adminRole + "/permissions", "{\"permissions\":[]}", admin));
        assertError("role_immutable", call(409, "DELETE", "/api/admin/roles/" + adminRole, null, admin));
        assertError("role_builtin", call(409, "PATCH", "/api/admin/roles/" + userRole, "{\"name\":\"users\"}", admin));
        assertError("role_builtin", call(409, "DELETE", "/api/admin/roles/" + userRole, null, admin));
        call(200, "PUT", "/api/admin/roles/" + userRole + "/permissions", "{\"permissions\":[]}", admin);

        call(204, "DELETE", "/api/admin/roles/" + modsId, null, admin);
        assertError("role_not_found", call(404, "GET", "/api/admin/roles/" + modsId, null, admin));
        assertEquals(List.of(), strings(call(200, "GET", "/api/admin/users/" + bobId + "/roles", null, admin).getAsJsonArray("items")));
        for (String action : List.of("admin.role_created", "admin.role_updated", "admin.role_permissions_set",
                "admin.role_deleted", "admin.user_roles_set")) {
            assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = '" + action + "'") > 0, action);
        }
    }

    @Test
    void noEscalationThroughTheAdminPages() throws Exception {
        String userAdmins = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"user admins\",\"permissions\":[\"admin.users\",\"library.upload\",\"library.youtube\",\"rooms.create\"]}",
                admin).get("id").getAsString();
        String mods = call(201, "POST", "/api/admin/roles", "{\"name\":\"mods\",\"permissions\":[\"rooms.moderateAny\"]}",
                admin).get("id").getAsString();
        String userRole = roleId("user");
        String adminRole = roleId("admin");
        String carolId = createUser("carol", "temporary-1");
        call(200, "PUT", "/api/admin/users/" + carolId + "/roles",
                "{\"roleIds\":[\"" + userRole + "\",\"" + userAdmins + "\"]}", admin);
        String carol = login("carol", "temporary-1");
        String bobId = createUser("bob", "temporary-1");

        // Carol manages users: she can read the role list (for the picker) but not change roles or settings.
        call(200, "GET", "/api/admin/users", null, carol);
        call(200, "GET", "/api/admin/roles", null, carol);
        assertError("forbidden", call(403, "GET", "/api/admin/roles/" + mods, null, carol));
        assertError("forbidden", call(403, "GET", "/api/admin/settings", null, carol));

        // She can manage Bob, who holds nothing she lacks.
        call(204, "POST", "/api/admin/users/" + bobId + "/password", "{\"password\":\"temporary-2\"}", carol);
        call(200, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":true}", carol);
        call(200, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":false}", carol);
        // But can't hand out what she doesn't hold, or the admin role.
        assertError("forbidden", call(403, "PUT", "/api/admin/users/" + bobId + "/roles",
                "{\"roleIds\":[\"" + userRole + "\",\"" + mods + "\"]}", carol));
        assertError("forbidden", call(403, "PUT", "/api/admin/users/" + carolId + "/roles",
                "{\"roleIds\":[\"" + userRole + "\",\"" + userAdmins + "\",\"" + adminRole + "\"]}", carol));
        assertError("forbidden", call(403, "POST", "/api/admin/users",
                "{\"username\":\"dave\",\"password\":\"temporary-1\",\"roleIds\":[\"" + adminRole + "\"]}", carol));
        call(201, "POST", "/api/admin/users",
                "{\"username\":\"dave\",\"password\":\"temporary-1\",\"roleIds\":[\"" + userRole + "\"]}", carol);
        // And can't touch the admin (password, disable, roles, name).
        assertError("forbidden", call(403, "POST", "/api/admin/users/" + adminId + "/password",
                "{\"password\":\"temporary-3\"}", carol));
        assertError("forbidden", call(403, "PATCH", "/api/admin/users/" + adminId, "{\"disabled\":true}", carol));
        assertError("forbidden", call(403, "PUT", "/api/admin/users/" + adminId + "/roles",
                "{\"roleIds\":[\"" + userRole + "\"]}", carol));
        // Nor a user holding a permission she lacks.
        call(200, "PUT", "/api/admin/users/" + bobId + "/roles", "{\"roleIds\":[\"" + userRole + "\",\"" + mods + "\"]}", admin);
        assertError("forbidden", call(403, "PATCH", "/api/admin/users/" + bobId, "{\"disabled\":true}", carol));

        // A role admin without the other permissions can't give a role more than they hold.
        String roleAdmins = call(201, "POST", "/api/admin/roles", "{\"name\":\"role admins\",\"permissions\":[\"admin.roles\"]}",
                admin).get("id").getAsString();
        String erinId = createUser("erin", "temporary-1");
        call(200, "PUT", "/api/admin/users/" + erinId + "/roles", "{\"roleIds\":[\"" + roleAdmins + "\"]}", admin);
        String erin = login("erin", "temporary-1");
        assertError("forbidden", call(403, "PUT", "/api/admin/roles/" + roleAdmins + "/permissions",
                "{\"permissions\":[\"admin.roles\",\"admin.settings\"]}", erin));
        assertError("forbidden", call(403, "POST", "/api/admin/roles", "{\"name\":\"x\",\"permissions\":[\"ip.view\"]}", erin));
        assertError("forbidden", call(403, "DELETE", "/api/admin/roles/" + mods, null, erin));
        assertError("forbidden", call(403, "PATCH", "/api/admin/roles/" + mods, "{\"isDefault\":true}", erin));
        call(200, "PATCH", "/api/admin/roles/" + mods, "{\"name\":\"moderators\"}", erin); // renaming is fine
        String empty = call(201, "POST", "/api/admin/roles", "{\"name\":\"empty\",\"permissions\":[\"admin.roles\"]}", erin)
                .get("id").getAsString();
        call(204, "DELETE", "/api/admin/roles/" + empty, null, erin);
    }

    /**
     * output.profile, output.videoBitrateKbps, ingest.workers, ingest.maxUploadBytes,
     * overlay.workers and overlay.cacheMaxBytes are admin settings; GET /api/admin/encoding tells
     * the page how many items a new encoding would re-encode.
     */
    @Test
    void outputEncodingAndWorkerSettings() throws Exception {
        JsonArray items = call(200, "GET", "/api/admin/settings", null, admin).getAsJsonArray("items");
        JsonObject profile = find(items, "output.profile");
        assertEquals("output", profile.get("group").getAsString());
        assertEquals("enum", profile.get("type").getAsString());
        assertEquals(List.of("720p30", "1080p30"), strings(profile.getAsJsonArray("options")));
        JsonObject bitrate = find(items, "output.videoBitrateKbps");
        assertEquals("integer", bitrate.get("type").getAsString());
        assertFalse(bitrate.has("value"), "unset: the profile's default");
        assertEquals("bytes", find(items, "ingest.maxUploadBytes").get("type").getAsString());
        assertEquals("library", find(items, "ingest.workers").get("group").getAsString());
        assertEquals("overlay", find(items, "overlay.workers").get("group").getAsString());
        assertEquals("bytes", find(items, "overlay.cacheMaxBytes").get("type").getAsString());

        JsonObject same = call(200, "GET", "/api/admin/encoding", null, admin);
        assertEquals("720p30-3000k-v3", same.get("current").getAsString());
        assertEquals("720p30-3000k-v3", same.get("encoding").getAsString());
        assertEquals(0, same.get("reencodeItems").getAsInt());
        JsonObject hd = call(200, "GET", "/api/admin/encoding?profile=1080p30&videoBitrateKbps=", null, admin);
        assertEquals("1080p30-6000k-v3", hd.get("encoding").getAsString());
        assertEquals(7, hd.get("reencodeItems").getAsInt());
        assertEquals("720p30-2000k-v3",
                call(200, "GET", "/api/admin/encoding?videoBitrateKbps=2000", null, admin).get("encoding").getAsString());
        assertError("invalid_setting", call(400, "GET", "/api/admin/encoding?profile=480p25", null, admin));
        assertError("invalid_setting", call(400, "GET", "/api/admin/encoding?videoBitrateKbps=100", null, admin));
        assertError("invalid_setting", call(400, "GET", "/api/admin/encoding?videoBitrateKbps=fast", null, admin));

        JsonObject changed = call(200, "PATCH", "/api/admin/settings", "{\"output.profile\":\"1080p30\","
                + "\"output.videoBitrateKbps\":8000,\"ingest.workers\":5,\"ingest.maxUploadBytes\":1073741824,"
                + "\"overlay.workers\":2,\"overlay.cacheMaxBytes\":0}", admin);
        assertEquals(8000, find(changed.getAsJsonArray("items"), "output.videoBitrateKbps").get("value").getAsInt());
        assertEquals("1080p30-8000k-v3", settings.outputEncoding().id());
        assertEquals(5, settings.ingestWorkers());
        assertEquals(Settings.GIB, settings.ingestMaxUploadBytes());
        assertEquals(2, settings.overlayWorkers());
        assertEquals(0, settings.overlayCacheMaxBytes());
        // the missing profile is now the setting's
        assertEquals("1080p30-6000k-v3",
                call(200, "GET", "/api/admin/encoding", null, admin).get("encoding").getAsString());
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings",
                "{\"ingest.workers\":0}", admin));
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings",
                "{\"output.videoBitrateKbps\":30000}", admin));
        call(200, "PATCH", "/api/admin/settings", "{\"output.videoBitrateKbps\":null,\"output.profile\":null}", admin);
        assertEquals("720p30-3000k-v3", settings.outputEncoding().id());
    }

    /** Uploads need library requests: guests.canUpload on needs guests.canRequestLibrary on. */
    @Test
    void guestUploadsNeedGuestLibraryRequests() throws Exception {
        // turning uploads on while library requests are off turns them on too
        call(200, "PATCH", "/api/admin/settings", "{\"guests.canRequestLibrary\":false}", admin);
        JsonObject on = call(200, "PATCH", "/api/admin/settings", "{\"guests.canUpload\":true}", admin);
        assertEquals("[\"guests.canRequestLibrary\"]", on.get("adjusted").toString());
        assertTrue(find(on.getAsJsonArray("items"), "guests.canRequestLibrary").get("value").getAsBoolean());
        assertTrue(settings.guestsCanUpload());
        assertTrue(settings.guestsCanRequestLibrary());
        // turning library requests off while uploads are on turns those off too (also by a reset to a default of off)
        JsonObject off = call(200, "PATCH", "/api/admin/settings", "{\"guests.canRequestLibrary\":false}", admin);
        assertEquals("[\"guests.canUpload\"]", off.get("adjusted").toString());
        assertFalse(settings.guestsCanUpload());
        assertFalse(settings.get(Settings.GUESTS_CAN_UPLOAD), "stored off, not just read as off");
        // no adjustment needed: no "adjusted"
        assertFalse(call(200, "PATCH", "/api/admin/settings", "{\"guests.canRequestLibrary\":true}", admin)
                .has("adjusted"));
        // both the wrong way at once
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings",
                "{\"guests.canUpload\":true,\"guests.canRequestLibrary\":false}", admin));
        assertFalse(settings.guestsCanUpload(), "nothing changed");
        // a stored state breaking the rule (older rows) is shown and read as upload off
        settings.set(Settings.GUESTS_CAN_UPLOAD, true);
        settings.set(Settings.GUESTS_CAN_REQUEST_LIBRARY, false);
        assertFalse(settings.guestsCanUpload());
        assertFalse(find(call(200, "GET", "/api/admin/settings", null, admin).getAsJsonArray("items"),
                "guests.canUpload").get("value").getAsBoolean());
    }

    @Test
    void serverSettings() throws Exception {
        JsonObject all = call(200, "GET", "/api/admin/settings", null, admin);
        JsonArray items = all.getAsJsonArray("items");
        assertEquals(Settings.KEYS.size(), items.size());
        JsonObject mode = items.get(0).getAsJsonObject();
        assertEquals("registration.mode", mode.get("key").getAsString());
        assertEquals("accounts", mode.get("group").getAsString());
        assertEquals("enum", mode.get("type").getAsString());
        assertEquals("closed", mode.get("value").getAsString());
        assertEquals(List.of("open", "invite", "closed"), strings(mode.getAsJsonArray("options")));
        JsonObject quota = find(items, "guests.uploadQuota");
        assertEquals("uploadQuota", quota.get("type").getAsString());
        assertEquals(3, quota.getAsJsonObject("value").get("perHour").getAsInt());
        JsonObject blocklist = find(items, "guests.nameBlocklist");
        assertEquals("stringList", blocklist.get("type").getAsString());
        assertEquals(0, blocklist.getAsJsonArray("value").size());
        JsonObject cookies = find(items, "ytdlp.cookiesFile");
        assertEquals("string", cookies.get("type").getAsString());
        assertFalse(cookies.has("value"));
        assertEquals("integer", find(items, "retention.unreferencedDays").get("type").getAsString());
        assertEquals("retention", find(items, "retention.unreferencedDays").get("group").getAsString());
        assertEquals(72, find(items, "retention.videoAfterViewHours").get("value").getAsInt());
        assertEquals(14, find(items, "retention.videoUnviewedDays").get("default").getAsInt());
        assertEquals("boolean", find(items, "overlay.enabled").get("type").getAsString());
        assertEquals("outputs", find(items, "outputs.ts.enabled").get("group").getAsString());

        JsonObject changed = call(200, "PATCH", "/api/admin/settings",
                "{\"registration.mode\":\"open\",\"guests.uploadQuota\":{\"perHour\":5,\"maxItemBytes\":1000},"
                        + "\"ytdlp.cookiesFile\":\"/data/cookies.txt\",\"overlay.enabled\":true}", admin);
        assertEquals("open", find(changed.getAsJsonArray("items"), "registration.mode").get("value").getAsString());
        assertEquals(RegistrationMode.OPEN, settings.registrationMode());
        assertEquals(5, settings.guestsUploadQuota().perHour());
        assertEquals("/data/cookies.txt", settings.ytdlpCookiesFile());
        assertTrue(settings.overlayEnabled());
        JsonObject listed = call(200, "PATCH", "/api/admin/settings",
                "{\"guests.nameBlocklist\":[\" bad  word \",\"\",\"other\"]}", admin);
        assertEquals(List.of("bad word", "other"),
                strings(find(listed.getAsJsonArray("items"), "guests.nameBlocklist").getAsJsonArray("value")));
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings",
                "{\"guests.nameBlocklist\":\"bad\"}", admin));

        // All or nothing.
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings",
                "{\"overlay.enabled\":false,\"retention.unreferencedDays\":-1}", admin));
        assertTrue(settings.overlayEnabled());
        assertError("unknown_setting", call(400, "PATCH", "/api/admin/settings", "{\"overlay.enabled\":false,\"x\":1}", admin));
        assertTrue(settings.overlayEnabled());
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings", "{\"registration.mode\":\"sometimes\"}", admin));

        // x264 presets: the allowed ones as options; one that fails the avcC check is refused.
        JsonObject quality = find(call(200, "GET", "/api/admin/settings", null, admin).getAsJsonArray("items"),
                "ingest.qualityPreset");
        assertEquals("enum", quality.get("type").getAsString());
        assertEquals("library", quality.get("group").getAsString());
        assertEquals("slow", quality.get("value").getAsString());
        assertEquals(List.of("veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"),
                strings(quality.getAsJsonArray("options")));
        call(200, "PATCH", "/api/admin/settings", "{\"ingest.qualityPreset\":\"slower\"}", admin);
        assertEquals(X264Preset.SLOWER, settings.ingestQualityPreset());
        JsonObject refused = call(400, "PATCH", "/api/admin/settings",
                "{\"ingest.quickPreset\":\"faster\",\"ingest.qualityPreset\":\"veryslow\"}", admin);
        assertError("invalid_setting", refused);
        assertTrue(refused.toString().contains("veryslow doesn't produce the same"), refused.toString());
        assertEquals(X264Preset.VERYFAST, settings.ingestQuickPreset(), "all or nothing");
        assertEquals(X264Preset.SLOWER, settings.ingestQualityPreset());
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings", "{\"ingest.quickPreset\":\"ultrafast\"}", admin));
        call(200, "PATCH", "/api/admin/settings", "{\"ingest.qualityPreset\":null}", admin);

        // null resets to the default.
        call(200, "PATCH", "/api/admin/settings", "{\"overlay.enabled\":null,\"ytdlp.cookiesFile\":null}", admin);
        assertFalse(settings.overlayEnabled());
        assertEquals(null, settings.ytdlpCookiesFile());
        assertEquals(5, count("SELECT COUNT(*) FROM audit_log WHERE action = 'admin.settings_changed'"));

        // Feature switches show up in the role editor.
        call(200, "PATCH", "/api/admin/settings", "{\"ytdlp.enabled\":false}", admin);
        JsonObject youtube = call(200, "GET", "/api/admin/roles", null, admin).getAsJsonArray("permissions").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(p -> p.get("name").getAsString().equals("library.youtube"))
                .findFirst().orElseThrow();
        assertFalse(youtube.get("featureEnabled").getAsBoolean());
    }

    @Test
    void storageMinimumAndFreeSpace() throws Exception {
        JsonObject min = find(call(200, "GET", "/api/admin/settings", null, admin).getAsJsonArray("items"),
                "storage.minFreeBytes");
        assertEquals("bytes", min.get("type").getAsString());
        assertEquals("storage", min.get("group").getAsString());
        assertEquals(5 * Settings.GIB, min.get("value").getAsLong());
        assertEquals(5 * Settings.GIB, min.get("default").getAsLong());

        JsonObject storage = call(200, "GET", "/api/admin/storage", null, admin);
        assertEquals(8 * Settings.GIB, storage.get("freeBytes").getAsLong());
        assertEquals(100 * Settings.GIB, storage.get("totalBytes").getAsLong());
        assertEquals(5 * Settings.GIB, storage.get("minFreeBytes").getAsLong());
        assertFalse(storage.get("low").getAsBoolean());

        call(200, "PATCH", "/api/admin/settings", "{\"storage.minFreeBytes\":" + 10 * Settings.GIB + "}", admin);
        assertEquals(10 * Settings.GIB, settings.storageMinFreeBytes());
        storage = call(200, "GET", "/api/admin/storage", null, admin);
        assertEquals(10 * Settings.GIB, storage.get("minFreeBytes").getAsLong());
        assertTrue(storage.get("low").getAsBoolean(), "8 GiB free is below a 10 GiB minimum");

        call(200, "PATCH", "/api/admin/settings", "{\"storage.minFreeBytes\":0}", admin);
        assertFalse(call(200, "GET", "/api/admin/storage", null, admin).get("low").getAsBoolean(), "0 disables it");
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings", "{\"storage.minFreeBytes\":-1}", admin));
        assertError("invalid_setting", call(400, "PATCH", "/api/admin/settings", "{\"storage.minFreeBytes\":1.5}", admin));
        call(200, "PATCH", "/api/admin/settings", "{\"storage.minFreeBytes\":null}", admin);
        assertEquals(5 * Settings.GIB, settings.storageMinFreeBytes());
    }

    private static JsonObject find(JsonArray items, String key) {
        for (JsonElement e : items) {
            if (e.getAsJsonObject().get("key").getAsString().equals(key)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no setting " + key);
    }

    @Test
    void rolePermissionsCombineAsAUnion() throws Exception {
        String helpdeskRole = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"helpdesk\",\"permissions\":[\"admin.users\",\"rooms.create\"]}", admin).get("id").getAsString();
        String ipRole = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"ip\",\"permissions\":[\"ip.view\",\"rooms.create\"]}", admin).get("id").getAsString();
        String emptyRole = call(201, "POST", "/api/admin/roles",
                "{\"name\":\"empty\",\"permissions\":[]}", admin).get("id").getAsString();
        String bothId = createUser("both", "temporary-1");
        String both = login("both", "temporary-1");

        // One role at a time: only that role's permissions.
        call(200, "PUT", "/api/admin/users/" + bothId + "/roles", "{\"roleIds\":[\"" + helpdeskRole + "\"]}", admin);
        assertEquals(List.of("admin.users", "rooms.create"),
                strings(call(200, "GET", "/api/me", null, both).getAsJsonArray("permissions")).stream().sorted().toList());
        assertEquals(200, send("GET", "/api/admin/guests", null, both).statusCode());
        assertEquals(403, send("GET", "/api/admin/ip/sessions/" + adminId, null, both).statusCode());

        // Several roles (overlapping, plus an empty one and the built-in user role): the union, on the same session.
        call(200, "PUT", "/api/admin/users/" + bothId + "/roles", "{\"roleIds\":[\"" + helpdeskRole + "\",\""
                + ipRole + "\",\"" + emptyRole + "\",\"" + roleId("user") + "\"]}", admin);
        assertEquals(List.of("admin.users", "ip.view", "library.upload", "library.youtube", "rooms.create"),
                strings(call(200, "GET", "/api/me", null, both).getAsJsonArray("permissions")).stream().sorted().toList());
        assertEquals(200, send("GET", "/api/admin/guests", null, both).statusCode());
        assertEquals(200, send("GET", "/api/admin/ip/sessions/" + adminId, null, both).statusCode());
        assertError("forbidden", call(403, "GET", "/api/admin/settings", null, both));

        // Editing one role's permissions changes the union at once; a permission still granted by another role stays.
        call(200, "PUT", "/api/admin/roles/" + ipRole + "/permissions", "{\"permissions\":[]}", admin);
        assertEquals(List.of("admin.users", "library.upload", "library.youtube", "rooms.create"),
                strings(call(200, "GET", "/api/me", null, both).getAsJsonArray("permissions")).stream().sorted().toList());
        assertEquals(403, send("GET", "/api/admin/ip/sessions/" + adminId, null, both).statusCode());

        // Only the empty role left: nothing.
        call(200, "PUT", "/api/admin/users/" + bothId + "/roles", "{\"roleIds\":[\"" + emptyRole + "\"]}", admin);
        assertEquals(List.of(), strings(call(200, "GET", "/api/me", null, both).getAsJsonArray("permissions")));
        assertError("forbidden", call(403, "GET", "/api/admin/guests", null, both));
    }

    // ------------------------------------------------------------------ login providers

    private static final String OWN_PROVIDER = "{\"slug\":\"own\",\"displayName\":\"Own login\","
            + "\"issuer\":\"https://id.tunes.example\",\"clientId\":\"tunes\",\"clientSecret\":\"s3cret-own\","
            + "\"allowSignup\":true}";
    private static final String CHAT_PROVIDER = "{\"slug\":\"chat\",\"displayName\":\"Chat login\","
            + "\"kind\":\"oauth2\",\"clientId\":\"chat-client\",\"clientSecret\":\"s3cret-chat\","
            + "\"scopes\":\"user:read:email identify\",\"claimMapping\":{"
            + "\"authorizationEndpoint\":\"https://chat.example/oauth2/authorize\","
            + "\"tokenEndpoint\":\"https://chat.example/oauth2/token\","
            + "\"userinfoUrl\":\"https://api.chat.example/users/@me\",\"subject\":\"data.0.id\","
            + "\"username\":\"data.0.login\",\"displayName\":\"data.0.display_name\"}}";

    @Test
    void twoProvidersEnabledAtOnce() throws Exception {
        assertEquals(0, call(200, "GET", "/api/auth/providers", null, null).getAsJsonArray("items").size());
        JsonObject own = call(201, "POST", "/api/admin/providers", OWN_PROVIDER, admin);
        JsonObject chat = call(201, "POST", "/api/admin/providers", CHAT_PROVIDER, admin);
        assertEquals("oidc", own.get("kind").getAsString());
        assertTrue(own.get("hasClientSecret").getAsBoolean());
        assertEquals("openid profile email", own.get("scopes").getAsString());
        assertEquals(PUBLIC_BASE_URL + "/auth/oidc/own/callback", own.get("redirectUri").getAsString());
        assertEquals(0, own.get("identities").getAsLong());
        assertFalse(own.has("claimMapping"), own.toString());
        assertEquals("oauth2", chat.get("kind").getAsString());
        assertEquals("https://chat.example", chat.get("issuer").getAsString());
        assertEquals("data.0.id", chat.getAsJsonObject("claimMapping").get("subject").getAsString());
        assertEquals(PUBLIC_BASE_URL + "/auth/oidc/chat/callback", chat.get("redirectUri").getAsString());

        // both are login buttons, for anyone; no secret, issuer or client id in the public list
        HttpResponse<String> list = send("GET", "/api/auth/providers", null, null);
        assertEquals(200, list.statusCode());
        assertEquals("no-store", list.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("{\"items\":[{\"slug\":\"own\",\"displayName\":\"Own login\",\"allowSignup\":true,"
                + "\"startUrl\":\"/auth/oidc/own/start\"},{\"slug\":\"chat\",\"displayName\":\"Chat login\","
                + "\"allowSignup\":false,\"startUrl\":\"/auth/oidc/chat/start\"}]}", list.body());

        // one user linked to both: both stay listed, each counts its identity
        String userId = createUser("linked", "linked-password");
        database.write(c -> {
            for (JsonObject p : List.of(own, chat)) {
                try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO user_identities (id, user_id,"
                        + " provider_id, subject, created_at) VALUES (?, ?, ?, ?, 0)")) {
                    ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                    ps.setString(2, userId);
                    ps.setString(3, p.get("id").getAsString());
                    ps.setString(4, "subject-of-linked");
                    ps.executeUpdate();
                }
            }
            return null;
        });
        JsonArray all = call(200, "GET", "/api/admin/providers", null, admin).getAsJsonArray("items");
        assertEquals(2, all.size());
        for (JsonElement e : all) {
            assertEquals(1, e.getAsJsonObject().get("identities").getAsLong(), e.toString());
        }

        // disabling one leaves the other
        call(200, "PATCH", "/api/admin/providers/" + chat.get("id").getAsString(), "{\"enabled\":false}", admin);
        JsonArray buttons = call(200, "GET", "/api/auth/providers", null, null).getAsJsonArray("items");
        assertEquals(1, buttons.size());
        assertEquals("own", buttons.get(0).getAsJsonObject().get("slug").getAsString());
    }

    @Test
    void providerAdmin() throws Exception {
        JsonObject own = call(201, "POST", "/api/admin/providers", OWN_PROVIDER, admin);
        String id = own.get("id").getAsString();
        String path = "/api/admin/providers/" + id;
        assertEquals(own, call(200, "GET", path, null, admin));

        // the secret is write-only: keep, remove, set
        JsonObject renamed = call(200, "PATCH", path, "{\"displayName\":\"Renamed\",\"slug\":\"Own-2\"}", admin);
        assertEquals("Renamed", renamed.get("displayName").getAsString());
        assertEquals("own-2", renamed.get("slug").getAsString());
        assertEquals(PUBLIC_BASE_URL + "/auth/oidc/own-2/callback", renamed.get("redirectUri").getAsString());
        assertTrue(renamed.get("hasClientSecret").getAsBoolean());
        assertFalse(call(200, "PATCH", path, "{\"clientSecret\":null}", admin).get("hasClientSecret").getAsBoolean());
        assertTrue(call(200, "PATCH", path, "{\"clientSecret\":\"s3cret-new\"}", admin)
                .get("hasClientSecret").getAsBoolean());

        // validation
        assertError("unknown_field", call(400, "PATCH", path, "{\"secret\":\"x\"}", admin));
        assertError("invalid_provider", call(400, "PATCH", path, "{\"kind\":\"oauth2\"}", admin));
        assertError("invalid_provider", call(400, "PATCH", path, "{\"issuer\":\"http://id.tunes.example\"}", admin));
        assertError("invalid_provider", call(400, "PATCH", path, "{\"scopes\":\"profile\"}", admin));
        assertError("invalid_provider", call(400, "PATCH", path,
                "{\"claimMapping\":{\"userinfoUrl\":\"https://x.example\"}}", admin));
        assertError("invalid_json", call(400, "PATCH", path, "{\"enabled\":\"yes\"}", admin));
        assertError("invalid_provider", call(400, "POST", "/api/admin/providers",
                "{\"slug\":\"x\",\"displayName\":\"X\",\"kind\":\"oauth2\",\"clientId\":\"c\"}", admin));
        assertError("invalid_provider", call(400, "POST", "/api/admin/providers",
                "{\"slug\":\"x\",\"displayName\":\"X\",\"kind\":\"saml\",\"clientId\":\"c\"}", admin));
        assertError("provider_slug_taken", call(409, "POST", "/api/admin/providers",
                OWN_PROVIDER.replace("\"own\"", "\"own-2\""), admin));
        assertError("provider_not_found", call(404, "GET", "/api/admin/providers/" + adminId, null, admin));
        // OpenID Connect claim names may be set, and removed again
        assertEquals("nickname", call(200, "PATCH", path, "{\"claimMapping\":{\"username\":\"nickname\"}}", admin)
                .getAsJsonObject("claimMapping").get("username").getAsString());
        assertFalse(call(200, "PATCH", path, "{\"claimMapping\":null}", admin).has("claimMapping"));

        // an identity pins the issuer and blocks deleting
        String userId = createUser("linked", "linked-password");
        database.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO user_identities (id, user_id,"
                    + " provider_id, subject, created_at) VALUES (?, ?, ?, 'sub', 0)")) {
                ps.setString(1, io.siggi.togethertunes.db.UuidV7.newId());
                ps.setString(2, userId);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return null;
        });
        assertError("provider_has_identities", call(409, "PATCH", path,
                "{\"issuer\":\"https://other.tunes.example\"}", admin));
        assertError("provider_has_identities", call(409, "DELETE", path, null, admin));
        call(200, "PATCH", path, "{\"enabled\":false}", admin);
        String spare = call(201, "POST", "/api/admin/providers", OWN_PROVIDER, admin).get("id").getAsString();
        call(204, "DELETE", "/api/admin/providers/" + spare, null, admin);
        assertEquals(1, call(200, "GET", "/api/admin/providers", null, admin).getAsJsonArray("items").size());

        // audited, never with the secret
        List<String> audit = database.read(c -> {
            List<String> out = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT action, details_json FROM audit_log WHERE action LIKE 'admin.provider%' ORDER BY id");
                 java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1) + " " + rs.getString(2));
                }
            }
            return out;
        });
        assertEquals(List.of("admin.provider_created", "admin.provider_updated", "admin.provider_updated",
                        "admin.provider_updated", "admin.provider_updated", "admin.provider_updated",
                        "admin.provider_updated", "admin.provider_created", "admin.provider_deleted"),
                audit.stream().map(a -> a.substring(0, a.indexOf(' '))).toList(), audit.toString());
        assertTrue(audit.get(0).contains("\"clientSecret\":\"set\""), audit.get(0));
        assertTrue(audit.get(2).contains("\"clientSecret\":\"removed\""), audit.get(2));
        for (String a : audit) {
            assertFalse(a.contains("s3cret"), a);
        }

        // admin.providers is needed
        createUser("plain", "plain-password");
        String plain = login("plain", "plain-password");
        assertError("forbidden", call(403, "GET", "/api/admin/providers", null, plain));
        assertError("forbidden", call(403, "POST", "/api/admin/providers", OWN_PROVIDER, plain));
        assertError("not_authenticated", call(401, "GET", "/api/admin/providers", null, null));
    }

    @Test
    void adminPageLoads() throws Exception {
        HttpResponse<String> page = send("GET", "/admin/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("/js/admin.js"), page.body());
        assertEquals(200, send("GET", "/js/admin.js", null, null).statusCode());
    }
}
