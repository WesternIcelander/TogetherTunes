package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
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
import java.util.ArrayList;
import java.util.Collections;
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
    private RoomRegistry registry;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String aliceId;
    private String bobId;
    private String alice;
    private String bob;
    private String noCreate; // a user whose roles lack rooms.create
    private String root; // admin: ip.view, rooms.moderateAny

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        Rooms rooms = new Rooms(db, permissions, clock);
        RoomGuests guests = new RoomGuests(db, settings, permissions, rooms, registry);
        new RoomsApi(rooms, registry, permissions, guests, new RequestSearch(db, rooms, guests)).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String rootId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id(); // admin
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        String plainId = accounts.createLocal("plain", null, "plain-password", false, null, "account.register").id();
        Roles roles = new Roles(db);
        roles.setUserRoles(plainId, List.of(roles.create("listeners", List.of(Permission.LIBRARY_UPLOAD), false).id()));
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        noCreate = sessions.create(plainId, Actor.Via.BEARER, "test", null).token();
        root = sessions.create(rootId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        registry.close();
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
        assertEquals(RoomSettings.DEFAULTS.toJson(), room.get("settings"));
        assertEquals(9, room.size(), room.toString());

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
        expectError(400, "unknown_field", call(alice, "POST", "/api/rooms", "{\"name\":\"a\",\"settings\":{}}"));
        expectError(400, "invalid_settings", call(alice, "PATCH", "/api/rooms/room-a", "{\"settings\":[]}"));
        expectError(400, "unknown_setting", call(alice, "PATCH", "/api/rooms/room-a", "{\"settings\":{\"x\":1}}"));
        expectError(400, "invalid_setting", call(alice, "PATCH", "/api/rooms/room-a",
                "{\"settings\":{\"guests\":{\"canVote\":1}}}"));
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
    void settings() throws Exception {
        String id = json(call(alice, "POST", "/api/rooms", "{\"name\":\"Set\",\"visibility\":\"public\"}"))
                .get("id").getAsString();
        List<RoomChange> changes = Collections.synchronizedList(new ArrayList<>());
        registry.executor(id).addListener((state, change) -> changes.add(change));

        HttpResponse<String> r = call(alice, "PATCH", "/api/rooms/set", "{\"settings\":{\"requestsPerActor\":5,"
                + "\"voteSkipThreshold\":\"off\",\"guests\":{\"canUpload\":true},\"overlay\":{\"enabled\":true,"
                + "\"corner\":\"tl\"},\"outputs\":{\"rtmp\":{\"playbackUrl\":\"rtspt://stream.example/live/x\"}}}}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject settings = json(r).getAsJsonObject("settings");
        assertEquals(5, settings.get("requestsPerActor").getAsInt());
        assertEquals("off", settings.get("voteSkipThreshold").getAsString());
        assertEquals("owner", settings.get("requestScope").getAsString(), "defaults filled in");
        assertTrue(settings.getAsJsonObject("guests").get("canUpload").getAsBoolean());
        assertTrue(settings.getAsJsonObject("guests").get("canVote").getAsBoolean());
        assertEquals("tl", settings.getAsJsonObject("overlay").get("corner").getAsString());
        assertEquals(22, settings.getAsJsonObject("overlay").get("heightPct").getAsInt());
        assertEquals(2000, settings.getAsJsonObject("estimatedDelayMs").get("ts").getAsInt());
        // Anyone who sees the room sees its settings.
        assertEquals(settings, json(call(null, "GET", "/api/rooms/set", null)).getAsJsonObject("settings"));
        assertEquals(settings, json(call(bob, "GET", "/api/rooms", null)).getAsJsonArray("items").get(0)
                .getAsJsonObject().getAsJsonObject("settings"));

        // The live state and the stored row (only what differs from the defaults).
        assertEquals(List.of(5, 1L), registry.call(id, tx -> List.of(
                tx.state().controlSettings().requestsPerActor(), tx.state().version())));
        assertEquals(1, changes.size());
        assertEquals(List.of(new RoomEvent.SettingsChanged(List.of("requestsPerActor", "voteSkipThreshold",
                "guests.canUpload", "overlay.enabled", "overlay.corner", "outputs.rtmp.playbackUrl"))),
                changes.get(0).events());
        String stored = db.read(c -> {
            try (var ps = c.prepareStatement("SELECT settings_json FROM rooms WHERE id = ?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertEquals(JsonParser.parseString("{\"requestsPerActor\":5,\"voteSkipThreshold\":\"off\","
                + "\"guests\":{\"canUpload\":true},\"overlay\":{\"enabled\":true,\"corner\":\"tl\"},"
                + "\"outputs\":{\"rtmp\":{\"playbackUrl\":\"rtspt://stream.example/live/x\"}}}"),
                JsonParser.parseString(stored));

        // The same values again: nothing changes, no version bump.
        assertEquals(200, call(alice, "PATCH", "/api/rooms/set", "{\"settings\":{\"requestsPerActor\":5}}").statusCode());
        assertEquals(1L, (long) registry.call(id, tx -> tx.state().version()));
        // null resets a setting, or a whole group.
        settings = json(call(alice, "PATCH", "/api/rooms/set", "{\"settings\":{\"requestsPerActor\":null,"
                + "\"overlay\":null}}")).getAsJsonObject("settings");
        assertEquals(3, settings.get("requestsPerActor").getAsInt());
        assertFalse(settings.getAsJsonObject("overlay").get("enabled").getAsBoolean());
        assertEquals("br", settings.getAsJsonObject("overlay").get("corner").getAsString());
        assertTrue(settings.getAsJsonObject("guests").get("canUpload").getAsBoolean(), "untouched");
        assertEquals(2L, (long) registry.call(id, tx -> tx.state().version()));

        // All or nothing; the owner only.
        expectError(400, "invalid_setting", call(alice, "PATCH", "/api/rooms/set",
                "{\"name\":\"Other\",\"settings\":{\"gapMs\":5,\"noRepeatWindow\":-1}}"));
        assertEquals("Set", json(call(alice, "GET", "/api/rooms/set", null)).get("name").getAsString());
        assertEquals(0, json(call(alice, "GET", "/api/rooms/set", null)).getAsJsonObject("settings").get("gapMs")
                .getAsInt());
        assertEquals(201, call(alice, "PUT", "/api/rooms/set/members/" + bobId, "{\"role\":\"moderator\"}").statusCode());
        expectError(403, "forbidden", call(bob, "PATCH", "/api/rooms/set", "{\"settings\":{\"gapMs\":5}}"));
        assertEquals(2L, (long) registry.call(id, tx -> tx.state().version()));
    }

    @Test
    void requestSearch() throws Exception {
        String slug = json(call(alice, "POST", "/api/rooms", "{\"name\":\"Search Room\",\"visibility\":\"public\"}"))
                .get("slug").getAsString();
        String itemId = io.siggi.togethertunes.db.UuidV7.newId();
        db.write(c -> {
            try (var ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, source, added_by_user_id,"
                    + " added_at, status, output_profile, last_referenced_at) VALUES (?, 'audio', 'Blue Monday',"
                    + " 'New Order', 'upload', ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, itemId);
                ps.setString(2, aliceId);
                ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at, via)"
                    + " VALUES (?, ?, 5, 'upload')")) {
                ps.setString(1, aliceId);
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
        String path = "/api/rooms/" + slug + "/search";
        for (String token : java.util.Arrays.asList(alice, bob, null)) {
            HttpResponse<String> r = call(token, "GET", path + "?q=monday", null);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
            JsonObject body = json(r);
            assertEquals(1, body.getAsJsonArray("items").size(), r.body());
            JsonObject item = body.getAsJsonArray("items").get(0).getAsJsonObject();
            assertEquals(itemId, item.get("id").getAsString());
            assertFalse(item.has("addedAt") || item.has("via") || item.has("sharedBy"), r.body());
        }
        assertEquals(0, json(call(bob, "GET", path + "?q=nothing&kind=audio", null)).getAsJsonArray("items").size());
        expectError(400, "invalid_limit", call(bob, "GET", path + "?limit=0", null));
        expectError(400, "invalid_kind", call(bob, "GET", path + "?kind=x", null));
        expectError(400, "invalid_cursor", call(bob, "GET", path + "?cursor=x", null));
        expectError(404, "room_not_found", call(bob, "GET", "/api/rooms/no-such-room/search", null));
        assertEquals(200, call(alice, "PATCH", "/api/rooms/" + slug, "{\"settings\":{\"requestScope\":\"none\"}}")
                .statusCode());
        expectError(403, "library_requests_disabled", call(bob, "GET", path, null));
        expectError(403, "library_requests_disabled", call(null, "GET", path, null));
        assertEquals(1, json(call(alice, "GET", path, null)).getAsJsonArray("items").size());
        assertEquals(200, call(alice, "PATCH", "/api/rooms/" + slug,
                "{\"settings\":{\"requestScope\":null,\"guests\":{\"canRequestLibrary\":false}}}").statusCode());
        expectError(403, "guest_library_requests_disabled", call(null, "GET", path, null));
        assertEquals(200, call(bob, "GET", path, null).statusCode());
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

    @Test
    void changesGoThroughTheRoomExecutor() throws Exception {
        String id = json(call(alice, "POST", "/api/rooms", "{\"name\":\"Live\"}")).get("id").getAsString();
        assertNull(registry.loaded(id), "rooms load on first use");
        List<RoomChange> changes = Collections.synchronizedList(new ArrayList<>());
        registry.executor(id).addListener((state, change) -> changes.add(change));

        assertEquals(200, call(alice, "PATCH", "/api/rooms/live", "{\"name\":\"Live!\",\"slug\":\"on-air\","
                + "\"visibility\":\"public\",\"controlMode\":\"open\"}").statusCode());
        assertEquals(List.of("on-air", "Live!", "public", "open", 1L), registry.call(id, tx -> List.of(
                tx.state().slug(), tx.state().name(), tx.state().visibility().wire(), tx.state().controlMode().wire(),
                tx.state().version())));
        // A PATCH that changes nothing doesn't bump the version.
        assertEquals(200, call(alice, "PATCH", "/api/rooms/on-air", "{\"name\":\"Live!\"}").statusCode());
        // Neither does a role change that isn't an ownership transfer; a transfer does.
        assertEquals(201, call(alice, "PUT", "/api/rooms/on-air/members/" + bobId, "{\"role\":\"member\"}").statusCode());
        assertEquals(200, call(alice, "PUT", "/api/rooms/on-air/members/" + bobId, "{\"role\":\"owner\"}").statusCode());
        assertEquals(List.of(bobId, 2L), registry.call(id, tx -> List.of(tx.state().ownerId(), tx.state().version())));
        assertEquals(2, changes.size());
        assertEquals(List.of(new RoomEvent.RoomUpdated()), changes.get(1).events());
        // Refused changes leave it alone.
        expectError(403, "forbidden", call(alice, "PATCH", "/api/rooms/on-air", "{\"name\":\"Mine\"}"));
        assertEquals(2L, (long) registry.call(id, tx -> tx.state().version()));

        RoomExecutor ex = registry.executor(id);
        assertEquals(204, call(bob, "DELETE", "/api/rooms/on-air", null).statusCode());
        assertTrue(ex.isClosed());
        assertNull(registry.loaded(id));
        expectError(404, "room_not_found", call(bob, "PATCH", "/api/rooms/" + id, "{\"name\":\"x\"}"));
        expectError(404, "room_not_found", call(bob, "DELETE", "/api/rooms/on-air", null));
    }

    private void sql(String statement, Object... args) throws Exception {
        db.write(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void guestBans() throws Exception {
        String id = json(call(alice, "POST", "/api/rooms", "{\"name\":\"Bar\",\"visibility\":\"public\"}"))
                .get("id").getAsString();
        assertEquals(201, call(alice, "PUT", "/api/rooms/bar/members/" + bobId, "{\"role\":\"moderator\"}").statusCode());
        String cookieGuest = io.siggi.togethertunes.db.UuidV7.newId();
        String ipGuest = io.siggi.togethertunes.db.UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, ip_last, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', 'k1', '198.51.100.8', 'Quiet Walrus', 1, 1)", cookieGuest);
        sql("INSERT INTO guests (id, kind, key_hash, ip_last, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'ip', 'k2', '203.0.113.77', 'Loud Heron', 1, 1)", ipGuest);

        // Before banning: the state, with the IP-mode warning, never the address.
        HttpResponse<String> r = call(alice, "GET", "/api/rooms/bar/guest-bans/" + ipGuest, null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        JsonObject ban = json(r);
        assertEquals(ipGuest, ban.getAsJsonObject("guest").get("id").getAsString());
        assertEquals("guest", ban.getAsJsonObject("guest").get("kind").getAsString());
        assertTrue(ban.getAsJsonObject("guest").get("isGuest").getAsBoolean());
        assertEquals("ip", ban.get("identifiedBy").getAsString());
        assertFalse(ban.get("banned").getAsBoolean());
        assertEquals(RoomGuests.IP_MODE_WARNING, ban.get("warning").getAsString());
        assertFalse(r.body().contains("203.0.113.77"), r.body());

        r = call(alice, "PUT", "/api/rooms/bar/guest-bans/" + ipGuest, null);
        assertEquals(201, r.statusCode(), r.body());
        assertTrue(json(r).get("banned").getAsBoolean());
        assertEquals(clock.millis(), json(r).get("bannedAt").getAsLong());
        assertFalse(r.body().contains("203.0.113.77"), r.body());
        assertEquals(200, call(bob, "PUT", "/api/rooms/" + id + "/guest-bans/" + ipGuest, null).statusCode());
        r = call(bob, "PUT", "/api/rooms/bar/guest-bans/" + cookieGuest, null);
        assertEquals(201, r.statusCode(), r.body());
        assertFalse(json(r).has("warning"), "a cookie guest is one browser");

        r = call(bob, "GET", "/api/rooms/bar/guest-bans?limit=1", null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, json(r).getAsJsonArray("items").size());
        assertTrue(json(r).has("nextCursor"));
        r = call(alice, "GET", "/api/rooms/bar/guest-bans", null);
        assertEquals(2, json(r).getAsJsonArray("items").size());
        assertFalse(r.body().contains("203.0.113.77") || r.body().contains("198.51.100.8"), r.body());
        // ip.view (the admin, a moderator everywhere through rooms.moderateAny) sees the last IP.
        r = call(root, "GET", "/api/rooms/bar/guest-bans", null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"ip\":\"203.0.113.77\""), r.body());
        r = call(root, "GET", "/api/rooms/bar/guest-bans/" + cookieGuest, null);
        assertEquals("198.51.100.8", json(r).get("ip").getAsString());

        // Refusals.
        expectError(401, "not_authenticated", call(null, "GET", "/api/rooms/bar/guest-bans", null));
        expectError(401, "not_authenticated", call(null, "PUT", "/api/rooms/bar/guest-bans/" + cookieGuest, null));
        expectError(403, "forbidden", call(noCreate, "GET", "/api/rooms/bar/guest-bans", null));
        expectError(403, "forbidden", call(noCreate, "PUT", "/api/rooms/bar/guest-bans/" + cookieGuest, null));
        expectError(403, "forbidden", call(noCreate, "DELETE", "/api/rooms/bar/guest-bans/" + cookieGuest, null));
        expectError(404, "guest_not_found", call(alice, "PUT", "/api/rooms/bar/guest-bans/nope", null));
        expectError(404, "guest_not_found", call(alice, "GET", "/api/rooms/bar/guest-bans/"
                + io.siggi.togethertunes.db.UuidV7.newId(), null));
        expectError(404, "room_not_found", call(alice, "PUT", "/api/rooms/nope/guest-bans/" + cookieGuest, null));
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/rooms/bar/guest-bans?cursor=x", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/rooms/bar/guest-bans?limit=0", null));

        // Lifting a ban.
        assertEquals(204, call(bob, "DELETE", "/api/rooms/bar/guest-bans/" + cookieGuest, null).statusCode());
        expectError(404, "ban_not_found", call(bob, "DELETE", "/api/rooms/bar/guest-bans/" + cookieGuest, null));
        assertEquals(1, json(call(alice, "GET", "/api/rooms/bar/guest-bans", null)).getAsJsonArray("items").size());
    }
}
