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
        server = WebServer.start("127.0.0.1", 0,
                Main.router(config, staticFiles, actors, setup, auth, sessionsApi,
                        new MeApi(accounts, new Users(database, passwords, clock), new Permissions(database, settings),
                                new Guests(database, settings, config.secureCookies()), settings,
                                new io.siggi.togethertunes.auth.IdentityTickets()), adminApi,
                        io.siggi.togethertunes.auth.AdminActivityApi.create(database,
                                new Guests(database, settings, config.secureCookies()), new Permissions(database, settings),
                                clock)), dir.toFile());

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
                {"GET", "/api/admin/settings"}, {"PATCH", "/api/admin/settings"},
                {"GET", "/api/admin/guests"}, {"GET", "/api/admin/audit"}, {"GET", "/api/admin/ip/sessions/" + bobId},
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
        assertEquals(13, perms.size());
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

        // null resets to the default.
        call(200, "PATCH", "/api/admin/settings", "{\"overlay.enabled\":null,\"ytdlp.cookiesFile\":null}", admin);
        assertFalse(settings.overlayEnabled());
        assertEquals(null, settings.ytdlpCookiesFile());
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log WHERE action = 'admin.settings_changed'"));

        // Feature switches show up in the role editor.
        call(200, "PATCH", "/api/admin/settings", "{\"ytdlp.enabled\":false}", admin);
        JsonObject youtube = call(200, "GET", "/api/admin/roles", null, admin).getAsJsonArray("permissions").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(p -> p.get("name").getAsString().equals("library.youtube"))
                .findFirst().orElseThrow();
        assertFalse(youtube.get("featureEnabled").getAsBoolean());
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
    void adminPageLoads() throws Exception {
        HttpResponse<String> page = send("GET", "/admin/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("/js/admin.js"), page.body());
        assertEquals(200, send("GET", "/js/admin.js", null, null).statusCode());
    }
}
