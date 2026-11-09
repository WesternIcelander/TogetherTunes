package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
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

/** {@code /api/rooms...} over HTTP ({@link RoomsApi}): JSON shape, statuses, body validation. */
class RoomsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String aliceId;
    private String bobId;
    private String alice;
    private String bob;
    private String noCreate; // a user whose roles lack rooms.create

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        TestUploads.roomsApi(db, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register"); // the first: admin
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        String plainId = accounts.createLocal("plain", null, "plain-password", false, null, "account.register").id();
        Roles roles = new Roles(db);
        roles.setUserRoles(plainId, List.of(roles.create("listeners", List.of(Permission.LIBRARY_UPLOAD), false).id()));
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        noCreate = sessions.create(plainId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, json(r).getAsJsonObject("error").get("code").getAsString(), r.body());
    }

    @Test
    void roomLifecycle() throws Exception {
        HttpResponse<String> created = call(alice, "POST", "/api/rooms",
                "{\"name\":\"Movie Night\",\"visibility\":\"public\",\"controlMode\":\"owner\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElse(""));
        JsonObject room = json(created);
        String id = room.get("id").getAsString();
        assertEquals("movie-night", room.get("slug").getAsString());
        assertEquals("Movie Night", room.get("name").getAsString());
        assertEquals("public", room.get("visibility").getAsString());
        assertEquals("owner", room.get("controlMode").getAsString());
        assertEquals(clock.millis(), room.get("createdAt").getAsLong());
        assertEquals(JsonParser.parseString("{\"kind\":\"user\",\"id\":\"" + aliceId
                + "\",\"name\":\"Alice\",\"isGuest\":false}"), room.get("owner"));
        assertEquals(JsonParser.parseString("{\"role\":\"owner\",\"canEdit\":true,\"canDelete\":true,"
                + "\"canModerate\":true}"), room.get("you"));
        assertEquals(8, room.size(), room.toString());

        // read by slug, anonymously: no role (left out)
        JsonObject anon = json(call(null, "GET", "/api/rooms/movie-night", null));
        assertEquals(id, anon.get("id").getAsString());
        assertFalse(anon.getAsJsonObject("you").has("role"));
        JsonObject list = json(call(null, "GET", "/api/rooms?limit=10", null));
        assertEquals(1, list.getAsJsonArray("items").size());
        assertFalse(list.has("nextCursor"));

        HttpResponse<String> patched = call(alice, "PATCH", "/api/rooms/" + id,
                "{\"name\":\"Film Night\",\"slug\":\"film\",\"visibility\":\"private\",\"controlMode\":\"open\"}");
        assertEquals(200, patched.statusCode(), patched.body());
        assertEquals("film", json(patched).get("slug").getAsString());
        assertEquals("open", json(patched).get("controlMode").getAsString());
        expectError(404, "room_not_found", call(null, "GET", "/api/rooms/film", null));
        expectError(404, "room_not_found", call(bob, "GET", "/api/rooms/" + id, null));

        // members: bob invited, promoted, then the room handed over
        HttpResponse<String> put = call(alice, "PUT", "/api/rooms/film/members/" + bobId, "{\"role\":\"member\"}");
        assertEquals(201, put.statusCode(), put.body());
        assertEquals(JsonParser.parseString("{\"user\":{\"kind\":\"user\",\"id\":\"" + bobId
                + "\",\"name\":\"Bob\",\"isGuest\":false},\"role\":\"member\",\"joinedAt\":" + clock.millis() + "}"),
                JsonParser.parseString(put.body()));
        assertEquals(200, call(bob, "GET", "/api/rooms/film", null).statusCode());
        expectError(403, "forbidden", call(bob, "GET", "/api/rooms/film/members", null));
        assertEquals(200, call(alice, "PUT", "/api/rooms/film/members/" + bobId, "{\"role\":\"moderator\"}").statusCode());
        JsonObject members = json(call(bob, "GET", "/api/rooms/film/members?limit=1", null));
        assertEquals(1, members.getAsJsonArray("items").size());
        assertTrue(members.has("nextCursor"));
        assertEquals(200, call(alice, "PUT", "/api/rooms/film/members/" + bobId, "{\"role\":\"owner\"}").statusCode());
        assertEquals("owner", json(call(bob, "GET", "/api/rooms/film", null)).getAsJsonObject("you")
                .get("role").getAsString());
        expectError(403, "forbidden", call(alice, "PATCH", "/api/rooms/film", "{\"name\":\"x\"}"));
        // alice steps down, then can't see the private room any more
        assertEquals(204, call(alice, "DELETE", "/api/rooms/film/members/" + aliceId, null).statusCode());
        expectError(404, "room_not_found", call(alice, "GET", "/api/rooms/film", null));
        expectError(404, "member_not_found", call(bob, "DELETE", "/api/rooms/film/members/" + aliceId, null));
        expectError(409, "owner_cannot_leave", call(bob, "DELETE", "/api/rooms/film/members/" + bobId, null));

        assertEquals(204, call(bob, "DELETE", "/api/rooms/" + id, null).statusCode());
        expectError(404, "room_not_found", call(bob, "GET", "/api/rooms/" + id, null));
    }

    @Test
    void createNeedsALoggedInUserWithRoomsCreate() throws Exception {
        expectError(401, "not_authenticated", call(null, "POST", "/api/rooms", "{\"name\":\"x\"}"));
        expectError(403, "forbidden", call(noCreate, "POST", "/api/rooms", "{\"name\":\"x\"}"));
        assertEquals(201, call(bob, "POST", "/api/rooms", "{\"name\":\"x y z\"}").statusCode());
    }

    @Test
    void bodiesAreValidated() throws Exception {
        expectError(400, "invalid_name", call(alice, "POST", "/api/rooms", "{}"));
        expectError(400, "invalid_name", call(alice, "POST", "/api/rooms", "{\"name\":5}"));
        expectError(400, "name_too_long", call(alice, "POST", "/api/rooms", "{\"name\":\"" + "n".repeat(101) + "\"}"));
        expectError(400, "invalid_slug", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"slug\":\"a b\"}"));
        expectError(400, "invalid_slug", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"slug\":true}"));
        expectError(400, "invalid_visibility", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"visibility\":\"secret\"}"));
        expectError(400, "invalid_control_mode", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"controlMode\":1}"));
        expectError(400, "unknown_field", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"ownerId\":\"x\"}"));
        assertEquals(201, call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"slug\":\"room-a\"}").statusCode());
        expectError(409, "slug_taken", call(bob, "POST", "/api/rooms", "{\"name\":\"b\",\"slug\":\"ROOM-A\"}"));

        expectError(400, "invalid_name", call(alice, "PATCH", "/api/rooms/room-a", "{\"name\":\" \"}"));
        expectError(400, "invalid_slug", call(alice, "PATCH", "/api/rooms/room-a", "{\"slug\":null}"));
        expectError(400, "unknown_field", call(alice, "PATCH", "/api/rooms/room-a", "{\"settings\":{}}"));
        expectError(400, "invalid_role", call(alice, "PUT", "/api/rooms/room-a/members/" + bobId, "{\"role\":\"admin\"}"));
        expectError(400, "invalid_role", call(alice, "PUT", "/api/rooms/room-a/members/" + bobId, "{}"));
        expectError(400, "unknown_field", call(alice, "PUT", "/api/rooms/room-a/members/" + bobId,
                "{\"role\":\"member\",\"x\":1}"));
        expectError(404, "user_not_found", call(alice, "PUT", "/api/rooms/room-a/members/nobody", "{\"role\":\"member\"}"));
        expectError(409, "cannot_change_own_role", call(alice, "PUT", "/api/rooms/room-a/members/" + aliceId,
                "{\"role\":\"member\"}"));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/rooms?limit=0", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/rooms/room-a/members?limit=201", null));
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/rooms?cursor=zzz", null));
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/rooms/room-a/members?cursor=zzz", null));
        expectError(404, "room_not_found", call(alice, "PATCH", "/api/rooms/nope", "{\"name\":\"x\"}"));
    }

    @Test
    void guestsAndAnonymousOnlyRead() throws Exception {
        assertEquals(201, call(alice, "POST", "/api/rooms", "{\"name\":\"Open\",\"visibility\":\"public\"}").statusCode());
        assertEquals(200, call(null, "GET", "/api/rooms", null).statusCode());
        assertEquals(200, call(null, "GET", "/api/rooms/open", null).statusCode());
        expectError(401, "not_authenticated", call(null, "PATCH", "/api/rooms/open", "{\"name\":\"x\"}"));
        expectError(401, "not_authenticated", call(null, "DELETE", "/api/rooms/open", null));
        expectError(401, "not_authenticated", call(null, "GET", "/api/rooms/open/members", null));
        expectError(401, "not_authenticated", call(null, "PUT", "/api/rooms/open/members/" + bobId, "{\"role\":\"member\"}"));
        expectError(401, "not_authenticated", call(null, "DELETE", "/api/rooms/open/members/" + bobId, null));
    }
}
