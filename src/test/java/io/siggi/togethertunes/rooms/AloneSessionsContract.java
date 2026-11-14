package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The alone state ({@link AloneSessions}, {@link AloneApi}, {@code docs/rooms.md#alone}) and the
 * own play history ({@link PlayHistory}) over HTTP: save and restore, fork, the create-room move,
 * a room deleted while users are in it, two devices of one user, the alone room's privacy.
 */
abstract class AloneSessionsContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private Database db;
    private RoomRegistry registry;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(T0);
    private String aliceId;
    private String bobId;
    private String alice;
    private String alice2; // alice's second device
    private String bob;
    private String root; // admin: rooms.moderateAny

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        Rooms rooms = new Rooms(db, permissions, clock);
        RoomGuests guests = new RoomGuests(db, settings, permissions, rooms, registry);
        RoomsApi api = new RoomsApi(rooms, registry, permissions, guests, new RequestSearch(db, rooms, guests),
                new RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock));
        api.aloneSessions(new AloneSessions(db, rooms, registry, clock),
                new PlayHistory(db, settings::roomsRequestHistoryDays, clock));
        api.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String rootId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "phone", null).token();
        alice2 = sessions.create(aliceId, Actor.Via.BEARER, "laptop", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        root = sessions.create(rootId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private JsonObject ok(int status, String token, String method, String path, String body) throws Exception {
        HttpResponse<String> r = call(token, method, path, body);
        assertEquals(status, r.statusCode(), method + " " + path + ": " + r.body());
        return r.body().isEmpty() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code")
                .getAsString(), r.body());
    }

    private JsonObject session(String token) throws Exception {
        return ok(200, token, "GET", "/api/me/play-session", null);
    }

    private String aloneRoom(String token) throws Exception {
        return session(token).get("aloneRoomId").getAsString();
    }

    private JsonObject join(String token, String room) throws Exception {
        return ok(200, token, "POST", "/api/me/play-session/join", "{\"room\":\"" + room + "\"}");
    }

    private JsonObject leave(String token, String choice) throws Exception {
        return ok(200, token, "POST", "/api/me/play-session/leave", "{\"choice\":\"" + choice + "\"}");
    }

    private String room(String token, String name, String visibility) throws Exception {
        return ok(201, token, "POST", "/api/rooms", "{\"name\":\"" + name + "\",\"visibility\":\"" + visibility
                + "\",\"controlMode\":\"owner\"}").get("id").getAsString();
    }

    /** A ready 60 s audio item. */
    private String item(String title) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms)"
                    + " VALUES (?, 'audio', ?, 'upload', ?, 1, 'ready', '720p30', 1, 60000)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, aliceId);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /** Adds items to a room as its owner (the first one starts if the room is idle). */
    private void add(String roomId, String ownerId, String... itemIds) throws Exception {
        registry.call(roomId, tx -> {
            for (String itemId : itemIds) {
                QueueOps.add(tx, RoomActor.user(ownerId, Rooms.Role.OWNER), itemId);
            }
            return null;
        });
    }

    /** What the room plays: {currentItemId or null, state, position now, queued item ids...}. */
    private record Plays(String current, String state, long positionMs, List<String> queue) {
    }

    private Plays plays(String roomId) throws Exception {
        return registry.call(roomId, tx -> {
            RoomState s = tx.state();
            List<String> queue = new ArrayList<>();
            for (QueueEntry e : Playback.playOrder(s)) {
                queue.add(e.itemId());
            }
            Timeline t = s.timeline();
            return new Plays(s.current() == null ? null : s.current().itemId(), t.state().wire(),
                    s.current() == null ? 0 : t.positionAt(tx.now(), s.current().lengthMs()), queue);
        });
    }

    private int count(String sql, String... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void joiningSavesAndRestoreBringsItBack() throws Exception {
        String song1 = item("One");
        String song2 = item("Two");
        JsonObject s = session(alice);
        String aloneId = s.get("aloneRoomId").getAsString();
        assertFalse(s.has("roomId"));
        assertFalse(s.has("deletedRoom"));
        assertEquals(aloneId, aloneRoom(alice2), "one alone room per user, made once");
        add(aloneId, aliceId, song1, song2);
        clock.advance(12_000);
        assertEquals(new Plays(song1, "playing", 12_000, List.of(song2)), plays(aloneId));

        String party = room(bob, "Party", "unlisted");
        add(party, bobId, item("Bob's"));
        s = join(alice, party);
        assertEquals(party, s.get("roomId").getAsString());
        assertEquals(aloneId, s.get("aloneRoomId").getAsString());
        // saved: paused where it was
        clock.advance(30_000);
        assertEquals(new Plays(song1, "paused", 12_000, List.of(song2)), plays(aloneId));
        assertEquals(party, registry.call(aloneId, tx -> tx.state().awayInRoomId()));
        assertEquals(party, session(alice2).get("roomId").getAsString(), "the other device sees it");
        // joining the same room again changes nothing; another room keeps the saved state
        join(alice, party);
        String other = room(bob, "Other", "public");
        join(alice, other);
        assertEquals(new Plays(song1, "paused", 12_000, List.of(song2)), plays(aloneId));

        s = leave(alice2, "restore");
        assertFalse(s.has("roomId"));
        clock.advance(1_000);
        assertEquals(new Plays(song1, "playing", 13_000, List.of(song2)), plays(aloneId), "playing again");
        assertNull(registry.call(aloneId, tx -> tx.state().awayInRoomId()));
        expectError(409, "not_in_room", call(alice, "POST", "/api/me/play-session/leave", "{\"choice\":\"restore\"}"));

        // a paused alone state comes back paused
        registry.call(aloneId, tx -> Playback.pause(tx));
        join(alice, party);
        leave(alice, "restore");
        clock.advance(5_000);
        assertEquals("paused", plays(aloneId).state());
    }

    @Test
    void forkCopiesTheRoomsSongPositionAndQueue() throws Exception {
        String mine = item("Mine");
        String mine2 = item("Mine 2");
        String aloneId = aloneRoom(alice);
        add(aloneId, aliceId, mine, mine2);
        String party = room(bob, "Party", "public");
        String song2 = item("Two");
        String song3 = item("Three");
        String song4 = item("Four");
        add(party, bobId, song2, song3, song4);
        join(alice, party);
        clock.advance(10_000);

        leave(alice, "fork");
        assertEquals(new Plays(song2, "playing", 10_000, List.of(song3, song4)), plays(aloneId));
        assertEquals(new Plays(song2, "playing", 10_000, List.of(song3, song4)), plays(party), "the room goes on");
        // the old alone entries are gone (removed, not in the alone room's history)
        assertEquals(0, count("SELECT COUNT(*) FROM queue_entries WHERE room_id = ? AND state IN ('queued', 'playing',"
                + " 'played', 'skipped') AND item_id IN (?, ?)", aloneId, mine, mine2));
        assertEquals(3, count("SELECT COUNT(*) FROM queue_entries WHERE room_id = ? AND state IN ('queued', 'playing')"
                + " AND added_by_user_id = ?", aloneId, aliceId), "the copies are alice's");

        // a paused room forks paused; failed items are left out
        registry.call(party, tx -> Playback.pause(tx));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'failed' WHERE id = ?")) {
                ps.setString(1, song4);
                ps.executeUpdate();
            }
            return null;
        });
        join(alice, party);
        leave(alice, "fork");
        assertEquals(new Plays(song2, "paused", 10_000, List.of(song3)), plays(aloneId));
    }

    @Test
    void creatingARoomMovesTheAloneState() throws Exception {
        String song1 = item("One");
        String song2 = item("Two");
        String aloneId = aloneRoom(alice);
        add(aloneId, aliceId, song1, song2);
        clock.advance(7_000);

        JsonObject created = ok(201, alice, "POST", "/api/rooms", "{\"name\":\"New\",\"moveAloneSession\":true}");
        String roomId = created.get("id").getAsString();
        assertFalse(created.has("alone"));
        assertEquals(new Plays(song1, "playing", 7_000, List.of(song2)), plays(roomId));
        assertEquals(new Plays(null, "idle", 0, List.of()), plays(aloneId), "the alone room is left empty");
        assertEquals(roomId, session(alice).get("roomId").getAsString(), "she is in the new room");
        // restoring now brings back an empty alone state
        leave(alice, "restore");
        assertEquals(new Plays(null, "idle", 0, List.of()), plays(aloneId));

        // from another room, the saved (paused) alone state moves, paused
        add(aloneId, aliceId, song2);
        String party = room(bob, "Party", "public");
        join(alice, party);
        String second = ok(201, alice, "POST", "/api/rooms", "{\"name\":\"Second\",\"moveAloneSession\":true}")
                .get("id").getAsString();
        assertEquals(new Plays(song2, "paused", 0, List.of()), plays(second));
        assertEquals(second, session(alice).get("roomId").getAsString());

        // without the flag nothing moves; a bad flag is refused
        add(aloneId, aliceId, song1);
        String plain = room(alice, "Plain", "public");
        assertEquals(new Plays(null, "idle", 0, List.of()), plays(plain));
        assertEquals(song1, plays(aloneId).current());
        expectError(400, "invalid_move_alone_session", call(alice, "POST", "/api/rooms",
                "{\"name\":\"Bad\",\"moveAloneSession\":\"yes\"}"));
    }

    @Test
    void deletingARoomWhileInItOffersForkOrRestore() throws Exception {
        String song1 = item("One");
        String song2 = item("Two");
        String mine = item("Mine");
        String party = room(alice, "Doomed Party", "public");
        add(party, aliceId, song1, song2);
        String bobAlone = aloneRoom(bob);
        add(bobAlone, bobId, mine);
        clock.advance(3_000);
        join(bob, party); // bob saves his alone state (playing)
        String rootAlone = aloneRoom(root);
        join(root, party);
        clock.advance(2_000);

        ok(204, alice, "DELETE", "/api/rooms/" + party, null);
        JsonObject s = session(bob);
        assertFalse(s.has("roomId"));
        assertEquals("Doomed Party", s.getAsJsonObject("deletedRoom").get("name").getAsString());
        assertNull(registry.call(bobAlone, tx -> tx.state().awayInRoomId()));

        // bob forks: the room as it was when it was deleted
        clock.advance(60_000);
        s = leave(bob, "fork");
        assertFalse(s.has("deletedRoom"));
        assertEquals(new Plays(song1, "playing", 5_000, List.of(song2)), plays(bobAlone));
        // root restores his own (empty) alone state
        leave(root, "restore");
        assertEquals(new Plays(null, "idle", 0, List.of()), plays(rootAlone));
        expectError(409, "not_in_room", call(bob, "POST", "/api/me/play-session/leave", "{\"choice\":\"fork\"}"));

        // restore after a deletion plays the saved state again; joining another room instead drops the choice
        String again = room(alice, "Again", "public");
        join(bob, again);
        ok(204, alice, "DELETE", "/api/rooms/" + again, null);
        assertTrue(session(bob).has("deletedRoom"));
        leave(bob, "restore");
        assertEquals("playing", plays(bobAlone).state());
        String third = room(alice, "Third", "public");
        join(bob, third);
        ok(204, alice, "DELETE", "/api/rooms/" + third, null);
        join(bob, room(alice, "Fourth", "public"));
        assertFalse(session(bob).has("deletedRoom"));
        leave(bob, "restore");
        assertEquals("playing", plays(bobAlone).state(), "the alone state played when he first left it");
    }

    @Test
    void theAloneRoomIsOnlyItsUsers() throws Exception {
        String aloneId = aloneRoom(alice);
        JsonObject view = ok(200, alice, "GET", "/api/rooms/" + aloneId, null);
        assertTrue(view.get("alone").getAsBoolean());
        assertEquals("private", view.get("visibility").getAsString());
        expectError(404, "room_not_found", call(bob, "GET", "/api/rooms/" + aloneId, null));
        expectError(404, "room_not_found", call(root, "GET", "/api/rooms/" + aloneId, null)); // even rooms.moderateAny
        expectError(404, "room_not_found", call(null, "GET", "/api/rooms/~" + aliceId, null));
        expectError(404, "room_not_found", call(bob, "POST", "/api/me/play-session/join",
                "{\"room\":\"" + aloneId + "\"}"));
        expectError(409, "alone_room", call(alice, "POST", "/api/me/play-session/join", "{\"room\":\"" + aloneId + "\"}"));
        expectError(409, "alone_room", call(alice, "PATCH", "/api/rooms/" + aloneId, "{\"name\":\"Mine\"}"));
        expectError(409, "alone_room", call(alice, "DELETE", "/api/rooms/" + aloneId, null));
        expectError(409, "alone_room", call(alice, "PUT", "/api/rooms/" + aloneId + "/members/" + bobId,
                "{\"role\":\"member\"}"));
        expectError(404, "room_not_found", call(root, "DELETE", "/api/rooms/" + aloneId, null));
        String own = room(alice, "Own", "public");
        JsonArray listed = ok(200, alice, "GET", "/api/rooms", null).getAsJsonArray("items");
        assertEquals(1, listed.size(), listed.toString());
        assertEquals(own, listed.get(0).getAsJsonObject().get("id").getAsString());
        // guests and anonymous visitors have no alone state
        expectError(401, "not_authenticated", call(null, "GET", "/api/me/play-session", null));
        expectError(401, "not_authenticated", call(null, "GET", "/api/me/history", null));
        expectError(400, "invalid_choice", call(alice, "POST", "/api/me/play-session/leave", "{\"choice\":\"both\"}"));
        expectError(400, "invalid_room", call(alice, "POST", "/api/me/play-session/join", "{}"));
    }

    @Test
    void playHistoryAloneAndInRooms() throws Exception {
        String song1 = item("One");
        String song2 = item("Two");
        String song3 = item("Three");
        String aloneId = aloneRoom(alice);
        add(aloneId, aliceId, song1);
        String party = room(bob, "Party", "public");
        join(alice, party);
        // alice is present in the party (a socket), bob isn't
        registry.call(party, tx -> {
            tx.state().presence().join("conn-1", "u:" + aliceId, RoomPresence.Mode.LISTEN);
            return null;
        });
        clock.advance(1_000);
        add(party, bobId, song2);
        clock.advance(1_000);
        add(party, bobId, song3);
        registry.call(party, tx -> QueueOps.next(tx, RoomActor.user(bobId, Rooms.Role.OWNER)));

        JsonObject page = ok(200, alice, "GET", "/api/me/history?limit=2", null);
        JsonArray items = page.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals(song3, items.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertEquals(song2, items.get(1).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        JsonObject where = items.get(1).getAsJsonObject().getAsJsonObject("room");
        assertEquals(party, where.get("id").getAsString());
        assertEquals("Party", where.get("name").getAsString());
        String cursor = page.get("nextCursor").getAsString();
        items = ok(200, alice, "GET", "/api/me/history?cursor=" + cursor, null).getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals(song1, items.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertFalse(items.get(0).getAsJsonObject().has("room"), "played alone");
        assertEquals(0, ok(200, bob, "GET", "/api/me/history", null).getAsJsonArray("items").size(),
                "bob wasn't in his room");

        // the room's name stays when it is deleted
        ok(204, bob, "DELETE", "/api/rooms/" + party, null);
        where = ok(200, alice, "GET", "/api/me/history", null).getAsJsonArray("items").get(1).getAsJsonObject()
                .getAsJsonObject("room");
        assertFalse(where.has("id"));
        assertEquals("Party", where.get("name").getAsString());
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/me/history?cursor=x", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/me/history?limit=0", null));
    }
}
