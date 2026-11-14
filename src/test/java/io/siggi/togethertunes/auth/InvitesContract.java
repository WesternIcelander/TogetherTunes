package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.RegistrationMode;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Invites over HTTP ({@link InvitesApi}, {@link Invites}) and registration in {@code invite} mode
 * ({@link AuthApi}): who may create, list and revoke; codes are single-use, expire, can be revoked,
 * die with a disabled creator; the code is never stored or listed. On SQLite
 * ({@link SqliteInvitesTest}) and PostgreSQL ({@link PostgresInvitesIT}).
 */
abstract class InvitesContract {
    private static final String BASE = "https://tunes.example";

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String adminId;
    private String adminToken;
    private String bobToken;
    private String carolId;
    private String carolToken;
    private String daveToken;

    protected abstract Database open() throws java.sql.SQLException;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                .load(null).config();
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.INVITE);
        Permissions permissions = new Permissions(db, settings);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        AuthApi auth = new AuthApi(accounts, sessions, settings, true,
                new AuthRateLimits(new RateLimiter("setup", 1000, 1, clock),
                        new RateLimiter("register", 1000, 1, clock), new RateLimiter("login", 1000, 1, clock)));
        Invites invites = new Invites(db, clock);
        auth.invites(invites, new InvitesApi(invites, permissions, BASE + "/"));
        Router router = new Router(new ActorResolver(db, settings, clock, true).hook());
        auth.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());

        adminId = accounts.createLocal("admin", "Admin", "admin-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        String daveId = accounts.createLocal("dave", "Dave", "dave-password", false, null, "account.register").id();
        String inviters = new Roles(db).create("inviters", List.of(Permission.USERS_INVITE), false).id();
        String userAdmins = new Roles(db).create("user admins", List.of(Permission.ADMIN_USERS), false).id();
        sql("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", carolId, inviters);
        sql("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", daveId, userAdmins);
        adminToken = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        bobToken = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        carolToken = sessions.create(carolId, Actor.Via.BEARER, "test", null).token();
        daveToken = sessions.create(daveId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, json(r).getAsJsonObject("error").get("code").getAsString(), r.body());
    }

    private void sql(String statement, Object... args) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String one(String query, Object... args) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private JsonObject create(String bearer, String body) throws Exception {
        HttpResponse<String> r = call("POST", "/api/invites", bearer, body);
        assertEquals(201, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return json(r);
    }

    private HttpResponse<String> register(String username, String invite) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"0123456789\""
                + (invite == null ? "" : ",\"invite\":\"" + invite + "\"") + "}";
        return call("POST", "/api/auth/register", null, body);
    }

    private static Set<String> ids(HttpResponse<String> list) {
        Set<String> out = new HashSet<>();
        for (JsonElement e : json(list).getAsJsonArray("items")) {
            out.add(e.getAsJsonObject().get("id").getAsString());
        }
        return out;
    }

    @Test
    void whoMayCreateListAndRevoke() throws Exception {
        expectError(401, "not_authenticated", call("POST", "/api/invites", null, null));
        expectError(401, "not_authenticated", call("GET", "/api/invites", null, null));
        expectError(403, "forbidden", call("POST", "/api/invites", bobToken, null));
        expectError(403, "forbidden", call("GET", "/api/invites", bobToken, null));
        expectError(403, "forbidden", call("POST", "/api/invites", daveToken, null)); // admin.users alone can't create

        JsonObject carols = create(carolToken, "{\"note\":\"  for Erin  \",\"expiresInHours\":2}");
        String code = carols.get("code").getAsString();
        assertTrue(Tokens.isWellFormed(code), code);
        assertEquals(BASE + "/register.html?invite=" + code, carols.get("url").getAsString());
        JsonObject invite = carols.getAsJsonObject("invite");
        String carolsId = invite.get("id").getAsString();
        assertEquals("pending", invite.get("status").getAsString());
        assertEquals("for Erin", invite.get("note").getAsString());
        assertEquals(clock.millis(), invite.get("createdAt").getAsLong());
        assertEquals(clock.millis() + 2 * 3_600_000L, invite.get("expiresAt").getAsLong());
        assertEquals(JsonParser.parseString("{\"kind\":\"user\",\"id\":\"" + carolId
                + "\",\"name\":\"Carol\",\"isGuest\":false}"), invite.get("createdBy"));
        assertFalse(invite.has("code"));
        // Only the hash is stored.
        assertEquals(Tokens.hash(code), one("SELECT code_hash FROM invites WHERE id = ?", carolsId));
        assertEquals("0", one("SELECT COUNT(*) FROM invites WHERE code_hash = ?", code));

        String adminsId = create(adminToken, null).getAsJsonObject("invite").get("id").getAsString();
        JsonObject adminInvite = create(adminToken, "{}").getAsJsonObject("invite");
        assertEquals(clock.millis() + Invites.DEFAULT_HOURS * 3_600_000L, adminInvite.get("expiresAt").getAsLong());

        // Listing: others their own, admins (admin.users) everyone's; never a code.
        HttpResponse<String> carolList = call("GET", "/api/invites", carolToken, null);
        assertEquals(200, carolList.statusCode(), carolList.body());
        assertEquals(Set.of(carolsId), ids(carolList));
        assertFalse(carolList.body().contains(code));
        assertEquals(3, ids(call("GET", "/api/invites", adminToken, null)).size());
        assertEquals(3, ids(call("GET", "/api/invites", daveToken, null)).size());
        HttpResponse<String> page = call("GET", "/api/invites?limit=2", adminToken, null);
        JsonArray first = json(page).getAsJsonArray("items");
        assertEquals(2, first.size());
        assertEquals(adminInvite.get("id").getAsString(), first.get(0).getAsJsonObject().get("id").getAsString(),
                "newest first");
        String cursor = json(page).get("nextCursor").getAsString();
        assertEquals(Set.of(carolsId), ids(call("GET", "/api/invites?limit=2&cursor=" + cursor, adminToken, null)));
        expectError(400, "invalid_limit", call("GET", "/api/invites?limit=0", adminToken, null));

        // Revoking: own ones; someone else's looks missing; admins any.
        expectError(404, "invite_not_found", call("DELETE", "/api/invites/" + adminsId, carolToken, null));
        expectError(403, "forbidden", call("DELETE", "/api/invites/" + carolsId, bobToken, null));
        HttpResponse<String> revoked = call("DELETE", "/api/invites/" + carolsId, carolToken, null);
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals("revoked", json(revoked).get("status").getAsString());
        assertEquals(clock.millis(), json(revoked).get("revokedAt").getAsLong());
        assertEquals(200, call("DELETE", "/api/invites/" + carolsId, carolToken, null).statusCode(), "idempotent");
        assertEquals(200, call("DELETE", "/api/invites/" + adminsId, daveToken, null).statusCode());
        expectError(404, "invite_not_found", call("DELETE", "/api/invites/nope", adminToken, null));
        assertEquals("1", one("SELECT COUNT(*) FROM audit_log WHERE action = ? AND actor_user_id = ? AND target = ?",
                Invites.AUDIT_REVOKED, carolId, carolsId));
        assertEquals("3", one("SELECT COUNT(*) FROM audit_log WHERE action = ?", Invites.AUDIT_CREATED));
        expectError(403, "invalid_invite", register("erin", code));

        // Validation.
        expectError(400, "invalid_expiry", call("POST", "/api/invites", carolToken, "{\"expiresInHours\":0}"));
        expectError(400, "invalid_expiry", call("POST", "/api/invites", carolToken,
                "{\"expiresInHours\":" + (Invites.MAX_HOURS + 1) + "}"));
        expectError(400, "invalid_note", call("POST", "/api/invites", carolToken,
                "{\"note\":\"" + "x".repeat(Invites.NOTE_MAX + 1) + "\"}"));
        expectError(400, "invalid_note", call("POST", "/api/invites", carolToken, "{\"note\":\"a\\nb\"}"));
    }

    @Test
    void pendingInvitesAreLimitedForNonAdmins() throws Exception {
        for (int i = 0; i < Invites.PENDING_MAX; i++) {
            create(carolToken, null);
        }
        expectError(409, "too_many_invites", call("POST", "/api/invites", carolToken, null));
        create(adminToken, null); // no limit with admin.users
        clock.advance(Invites.DEFAULT_HOURS * 3_600_000L);
        create(carolToken, null); // the old ones expired
    }

    @Test
    void registrationInInviteModeUsesUpOneInvite() throws Exception {
        expectError(403, "invite_required", register("erin", null));
        expectError(403, "invalid_invite", register("erin", "not-a-code"));
        expectError(403, "invalid_invite", register("erin", Tokens.newToken()));
        assertEquals("4", one("SELECT COUNT(*) FROM users"));

        JsonObject made = create(carolToken, "{\"expiresInHours\":1}");
        String code = made.get("code").getAsString();
        String id = made.getAsJsonObject("invite").get("id").getAsString();
        // A bad password doesn't use the invite up.
        HttpResponse<String> weak = call("POST", "/api/auth/register", null,
                "{\"username\":\"erin\",\"password\":\"short\",\"invite\":\"" + code + "\"}");
        expectError(400, "invalid_password", weak);
        HttpResponse<String> taken = register("bob", code);
        expectError(409, "username_taken", taken);
        assertNull(one("SELECT used_at FROM invites WHERE id = ?", id));

        HttpResponse<String> registered = register("erin", code);
        assertEquals(201, registered.statusCode(), registered.body());
        String erinId = json(registered).getAsJsonObject("user").get("id").getAsString();
        assertEquals(erinId, one("SELECT used_by_user_id FROM invites WHERE id = ?", id));
        assertEquals(Long.toString(clock.millis()), one("SELECT used_at FROM invites WHERE id = ?", id));
        assertTrue(one("SELECT details_json FROM audit_log WHERE action = ? AND target = ?", AuthApi.AUDIT_REGISTER,
                erinId).contains("\"invite\":\"" + id + "\""));
        expectError(403, "invalid_invite", register("frank", code));
        JsonObject listed = json(call("GET", "/api/invites", carolToken, null)).getAsJsonArray("items").get(0)
                .getAsJsonObject();
        assertEquals("used", listed.get("status").getAsString());
        assertEquals("erin", listed.getAsJsonObject("usedBy").get("name").getAsString());
        expectError(409, "invite_used", call("DELETE", "/api/invites/" + id, carolToken, null));

        // Expired.
        String expiring = create(carolToken, "{\"expiresInHours\":1}").get("code").getAsString();
        clock.advance(3_600_000L);
        expectError(403, "invalid_invite", register("frank", expiring));
        assertEquals("expired", json(call("GET", "/api/invites", carolToken, null)).getAsJsonArray("items").get(0)
                .getAsJsonObject().get("status").getAsString());

        // A disabled creator's invites can't be used.
        String carols = create(carolToken, null).get("code").getAsString();
        sql("UPDATE users SET disabled = 1 WHERE id = ?", carolId);
        expectError(403, "invalid_invite", register("frank", carols));
        sql("UPDATE users SET disabled = 0 WHERE id = ?", carolId);
        assertEquals(201, register("frank", carols).statusCode());

        // In open mode a code isn't needed (and is ignored); closed refuses even with one.
        String unused = create(adminToken, null).get("code").getAsString();
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.OPEN);
        assertEquals(201, register("gina", null).statusCode());
        assertEquals(201, register("hank", unused).statusCode());
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.CLOSED);
        expectError(403, "registration_closed", register("ivy", unused));
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.INVITE);
        assertEquals(201, register("ivy", unused).statusCode(), "still unused");
    }

    @Test
    void concurrentRegistrationsWithOneCodeMakeOneAccount() throws Exception {
        String code = create(adminToken, null).get("code").getAsString();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String name = "racer" + i;
                Callable<Integer> task = () -> register(name, code).statusCode();
                results.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
            assertEquals(3, statuses.stream().filter(s -> s == 403).count(), statuses.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("5", one("SELECT COUNT(*) FROM users"));
        assertNotNull(one("SELECT used_by_user_id FROM invites"));
    }

    @Test
    void usersInviteIsNotInTheDefaultRole() {
        assertFalse(BuiltinRoles.USER_DEFAULT_PERMISSIONS.contains(Permission.USERS_INVITE));
    }
}
