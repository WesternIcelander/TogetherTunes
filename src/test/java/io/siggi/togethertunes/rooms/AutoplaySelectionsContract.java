package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
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
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.ingest.Tools;
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
 * Autoplay selections ({@link AutoplaySelections}, {@link AutoplayApi},
 * {@code docs/ui.md#autoplay-from-playlists}) over HTTP: who may select, toggling and "only this
 * one", the listing and its visibility (selected playlists readable through the room, nothing
 * else), and selections going when their person loses the right or leaves.
 */
abstract class AutoplaySelectionsContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private Database db;
    private RoomRegistry registry;
    private WebServer server;
    private Playlists playlists;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(T0);
    private String aliceId;
    private String bobId;
    private String carolId;
    private String alice;
    private String bob;
    private String carol;

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
        playlists = new Playlists(db, TestUploads.youtube(db, config, Tools.from(config), clock), clock);
        api.autoplay(new AutoplaySelections(rooms, playlists));
        api.register(router);
        TestUploads.playlistsApi(db, config, permissions, clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        carol = sessions.create(carolId, Actor.Via.BEARER, "test", null).token();
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

    private String room(String token, String name, String visibility) throws Exception {
        return ok(201, token, "POST", "/api/rooms", "{\"name\":\"" + name + "\",\"visibility\":\"" + visibility
                + "\",\"controlMode\":\"owner\"}").get("id").getAsString();
    }

    private String playlist(String token, String name) throws Exception {
        return ok(201, token, "POST", "/api/playlists", "{\"name\":\"" + name + "\"}").get("id").getAsString();
    }

    private void selectors(String roomId, String who) throws Exception {
        ok(200, alice, "PATCH", "/api/rooms/" + roomId, "{\"settings\":{\"autoplay\":{\"selectors\":\"" + who + "\"}}}");
    }

    private void role(String roomId, String token, String userId, String role) throws Exception {
        HttpResponse<String> r = call(token, "PUT", "/api/rooms/" + roomId + "/members/" + userId,
                "{\"role\":\"" + role + "\"}");
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.body());
    }

    private JsonObject select(String token, String roomId, String playlistId) throws Exception {
        return ok(200, token, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/" + playlistId, null);
    }

    private JsonObject listing(String token, String roomId) throws Exception {
        return ok(200, token, "GET", "/api/rooms/" + roomId + "/autoplay", null);
    }

    /** The listing as "user: playlist, playlist; user: …" by display name and playlist name. */
    private static String summary(JsonObject listing) {
        List<String> people = new ArrayList<>();
        for (JsonElement p : listing.getAsJsonArray("selections")) {
            JsonObject o = p.getAsJsonObject();
            List<String> names = new ArrayList<>();
            for (JsonElement pl : o.getAsJsonArray("playlists")) {
                names.add(pl.getAsJsonObject().get("name").getAsString());
            }
            people.add(o.getAsJsonObject("user").get("name").getAsString() + ": " + String.join(", ", names));
        }
        return String.join("; ", people);
    }

    private int rows(String roomId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM room_autoplay_selections WHERE room_id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
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

    // ------------------------------------------------------------------ tests

    @Test
    void settingsHaveDefaultsAndAreValidated() throws Exception {
        String roomId = room(alice, "Party", "public");
        JsonObject autoplay = ok(200, alice, "GET", "/api/rooms/" + roomId, null).getAsJsonObject("settings")
                .getAsJsonObject("autoplay");
        assertEquals(JsonParser.parseString("{\"selectors\":\"owner\",\"upcoming\":5,\"mix\":\"random\","
                + "\"recentPct\":50,\"requestsFirst\":\"first\"}"), autoplay);
        JsonObject changed = ok(200, alice, "PATCH", "/api/rooms/" + roomId, "{\"settings\":{\"autoplay\":{"
                + "\"selectors\":\"members\",\"upcoming\":0,\"mix\":\"turns\",\"recentPct\":100,"
                + "\"requestsFirst\":\"alternate\"}}}").getAsJsonObject("settings").getAsJsonObject("autoplay");
        assertEquals(JsonParser.parseString("{\"selectors\":\"members\",\"upcoming\":0,\"mix\":\"turns\","
                + "\"recentPct\":100,\"requestsFirst\":\"alternate\"}"), changed);
        for (String bad : List.of("{\"selectors\":\"everyone\"}", "{\"upcoming\":51}", "{\"upcoming\":-1}",
                "{\"mix\":\"shuffle\"}", "{\"recentPct\":101}", "{\"recentPct\":2.5}", "{\"requestsFirst\":true}")) {
            expectError(400, "invalid_setting", call(alice, "PATCH", "/api/rooms/" + roomId,
                    "{\"settings\":{\"autoplay\":" + bad + "}}"));
        }
        expectError(400, "unknown_setting", call(alice, "PATCH", "/api/rooms/" + roomId,
                "{\"settings\":{\"autoplay\":{\"weights\":1}}}"));
        // Members may read the settings, only the owner changes them.
        role(roomId, alice, bobId, "moderator");
        expectError(403, "forbidden", call(bob, "PATCH", "/api/rooms/" + roomId,
                "{\"settings\":{\"autoplay\":{\"upcoming\":3}}}"));
    }

    @Test
    void ownerSelectsTogglesAndMakesOneTheOnlySelection() throws Exception {
        String roomId = room(alice, "Party", "public");
        String rock = playlist(alice, "Rock");
        String jazz = playlist(alice, "Jazz");
        String pop = playlist(alice, "Pop");
        String likes = ok(200, alice, "GET", "/api/me/likes", null).get("id").getAsString();

        JsonObject l = listing(alice, roomId);
        assertTrue(l.get("canSelect").getAsBoolean());
        assertEquals(roomId, l.get("roomId").getAsString());
        assertEquals(0, l.getAsJsonArray("selections").size());

        select(alice, roomId, rock);
        select(alice, roomId, jazz);
        l = select(alice, roomId, likes); // Liked Songs is selectable like any playlist
        // In the owner's playlist order: Liked Songs first, then newest first (new playlists go on top).
        assertEquals("Alice: Liked Songs, Jazz, Rock", summary(l));
        JsonObject first = l.getAsJsonArray("selections").get(0).getAsJsonObject();
        assertEquals(aliceId, first.getAsJsonObject("user").get("id").getAsString());
        assertEquals("likes", first.getAsJsonArray("playlists").get(0).getAsJsonObject().get("builtin").getAsString());
        assertEquals(T0, first.getAsJsonArray("playlists").get(1).getAsJsonObject().get("selectedAt").getAsLong());
        // Selecting again changes nothing.
        assertEquals("Alice: Liked Songs, Jazz, Rock", summary(select(alice, roomId, rock)));
        // Toggle off; deselecting something not selected is fine too.
        assertEquals("Alice: Liked Songs, Rock", summary(ok(200, alice, "DELETE",
                "/api/rooms/" + roomId + "/autoplay/playlists/" + jazz, null)));
        assertEquals("Alice: Liked Songs, Rock", summary(ok(200, alice, "DELETE",
                "/api/rooms/" + roomId + "/autoplay/playlists/" + pop, null)));
        // Only this one (double-click).
        assertEquals("Alice: Pop", summary(ok(200, alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[\"" + pop + "\"]}")));
        // Several at once, then none.
        assertEquals("Alice: Pop, Jazz", summary(ok(200, alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[\"" + pop + "\",\"" + jazz + "\",\"" + pop + "\"]}")));
        assertEquals("", summary(ok(200, alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[]}")));

        // Selections are per room.
        String other = room(alice, "Other", "public");
        select(alice, other, rock);
        assertEquals("", summary(listing(alice, roomId)));
        assertEquals("Alice: Rock", summary(listing(alice, other)));

        // Bad bodies.
        expectError(400, "invalid_playlist_ids", call(alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine", "{}"));
        expectError(400, "invalid_playlist_ids", call(alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[1]}"));
        expectError(400, "unknown_field", call(alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[],\"x\":1}"));
        // A missing playlist leaves the selection as it was (all or nothing).
        select(alice, roomId, rock);
        expectError(404, "playlist_not_found", call(alice, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[\"" + jazz + "\",\"" + UuidV7.newId() + "\"]}"));
        assertEquals("Alice: Rock", summary(listing(alice, roomId)));
        expectError(404, "room_not_found", call(alice, "GET", "/api/rooms/nope-nope/autoplay", null));
    }

    @Test
    void whoMaySelectFollowsTheSelectorsSetting() throws Exception {
        String roomId = room(alice, "Party", "public");
        String bobs = playlist(bob, "Bob's");
        String carols = playlist(carol, "Carol's");
        role(roomId, alice, bobId, "moderator");
        role(roomId, alice, carolId, "member");

        // owner (default): nobody else.
        assertFalse(listing(bob, roomId).get("canSelect").getAsBoolean());
        expectError(403, "forbidden", call(bob, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/" + bobs, null));
        expectError(403, "forbidden", call(bob, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[\"" + bobs + "\"]}"));
        // Clearing never needs the right.
        ok(200, bob, "PUT", "/api/rooms/" + roomId + "/autoplay/mine", "{\"playlistIds\":[]}");
        ok(200, bob, "DELETE", "/api/rooms/" + roomId + "/autoplay/playlists/" + bobs, null);

        selectors(roomId, "moderators");
        assertTrue(listing(bob, roomId).get("canSelect").getAsBoolean());
        assertFalse(listing(carol, roomId).get("canSelect").getAsBoolean());
        select(bob, roomId, bobs);
        expectError(403, "forbidden", call(carol, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/" + carols,
                null));

        selectors(roomId, "members");
        clock.advance(1000);
        select(carol, roomId, carols);
        // Only one's own playlists.
        expectError(404, "playlist_not_found", call(carol, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/"
                + bobs, null));
        // The caller first, then by first selection.
        assertEquals("Carol: Carol's; Bob: Bob's", summary(listing(carol, roomId)));
        assertEquals("Bob: Bob's; Carol: Carol's", summary(listing(bob, roomId)));
        assertEquals("Bob: Bob's; Carol: Carol's", summary(listing(alice, roomId)));
        // Carol's "only this one" leaves Bob's selection alone.
        String carols2 = playlist(carol, "Carol's 2");
        ok(200, carol, "PUT", "/api/rooms/" + roomId + "/autoplay/mine", "{\"playlistIds\":[\"" + carols2 + "\"]}");
        assertEquals("Bob: Bob's; Carol: Carol's 2", summary(listing(alice, roomId)));

        // A user without a room role can't select, even with members (a public room's visitors aren't members).
        ok(204, alice, "DELETE", "/api/rooms/" + roomId + "/members/" + carolId, null);
        assertFalse(listing(carol, roomId).get("canSelect").getAsBoolean());
        expectError(403, "forbidden", call(carol, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/" + carols,
                null));
        // Banned: not even the room.
        role(roomId, alice, carolId, "banned");
        expectError(403, "banned_from_room", call(carol, "GET", "/api/rooms/" + roomId + "/autoplay", null));

        // Guests and anonymous visitors never select.
        expectError(401, "not_authenticated", call(null, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/"
                + bobs, null));
        expectError(401, "not_authenticated", call(null, "PUT", "/api/rooms/" + roomId + "/autoplay/mine",
                "{\"playlistIds\":[]}"));
        assertFalse(listing(null, roomId).get("canSelect").getAsBoolean());
    }

    @Test
    void selectionsGoWhenTheRightIsLostOrThePersonLeaves() throws Exception {
        String roomId = room(alice, "Party", "public");
        String alices = playlist(alice, "Alice's");
        String bobs = playlist(bob, "Bob's");
        String carols = playlist(carol, "Carol's");
        selectors(roomId, "members");
        role(roomId, alice, bobId, "moderator");
        role(roomId, alice, carolId, "member");
        select(alice, roomId, alices);
        select(bob, roomId, bobs);
        select(carol, roomId, carols);
        assertEquals(3, rows(roomId));

        // members -> moderators: Carol (member) loses the right.
        selectors(roomId, "moderators");
        assertEquals("Alice: Alice's; Bob: Bob's", summary(listing(alice, roomId)));
        // Back to members: nothing comes back by itself.
        selectors(roomId, "members");
        assertEquals(2, rows(roomId));
        select(carol, roomId, carols);
        // Unrelated settings changes keep everything.
        ok(200, alice, "PATCH", "/api/rooms/" + roomId, "{\"settings\":{\"autoplay\":{\"upcoming\":8}}}");
        assertEquals(3, rows(roomId));

        // A ban loses it.
        role(roomId, alice, carolId, "banned");
        assertEquals("Alice: Alice's; Bob: Bob's", summary(listing(alice, roomId)));
        ok(204, alice, "DELETE", "/api/rooms/" + roomId + "/members/" + carolId, null); // unban
        // Leaving for good (removing one's own member row).
        role(roomId, alice, carolId, "member");
        select(carol, roomId, carols);
        ok(204, carol, "DELETE", "/api/rooms/" + roomId + "/members/" + carolId, null);
        assertEquals("Alice: Alice's; Bob: Bob's", summary(listing(alice, roomId)));
        // Being removed.
        role(roomId, alice, carolId, "member");
        select(carol, roomId, carols);
        ok(204, alice, "DELETE", "/api/rooms/" + roomId + "/members/" + carolId, null);
        assertEquals(2, rows(roomId));

        // moderators: a moderator demoted to member loses it.
        selectors(roomId, "moderators");
        role(roomId, alice, bobId, "member");
        assertEquals("Alice: Alice's", summary(listing(alice, roomId)));

        // owner: handing the room over makes Alice a moderator, who can't select any more.
        selectors(roomId, "owner");
        assertEquals(1, rows(roomId));
        assertEquals(403, call(bob, "PUT", "/api/rooms/" + roomId + "/autoplay/playlists/" + bobs, null).statusCode());
        role(roomId, alice, bobId, "owner");
        assertEquals(0, rows(roomId));
        assertTrue(listing(bob, roomId).get("canSelect").getAsBoolean());
        select(bob, roomId, bobs);
        assertEquals("Bob: Bob's", summary(listing(alice, roomId)));
    }

    @Test
    void selectionsGoWithTheirPlaylistAndRoom() throws Exception {
        String roomId = room(alice, "Party", "public");
        String rock = playlist(alice, "Rock");
        String jazz = playlist(alice, "Jazz");
        select(alice, roomId, rock);
        select(alice, roomId, jazz);
        ok(204, alice, "DELETE", "/api/playlists/" + jazz, null);
        assertEquals("Alice: Rock", summary(listing(alice, roomId)));
        // Merely disconnecting or opening other rooms keeps them; deleting the room drops them.
        ok(204, alice, "DELETE", "/api/rooms/" + roomId, null);
        assertEquals(0, rows(roomId));
    }

    @Test
    void selectedPlaylistsAreReadableThroughTheRoomAndNothingElse() throws Exception {
        String publicRoom = room(alice, "Party", "public");
        String privateRoom = room(alice, "Secret", "private");
        selectors(publicRoom, "members");
        selectors(privateRoom, "members");
        role(publicRoom, alice, bobId, "member");
        role(privateRoom, alice, bobId, "member");
        String selected = playlist(bob, "Selected");
        String hidden = playlist(bob, "Hidden");
        String song = item("Song A");
        playlists.add(new Actor.User(bobId, "s", Actor.Via.BEARER), selected, song, null, null, () -> {
        });
        playlists.add(new Actor.User(bobId, "s", Actor.Via.BEARER), hidden, item("Song B"), null, null, () -> {
        });
        select(bob, publicRoom, selected);
        select(bob, privateRoom, selected);

        // Bob's own playlists stay his: Carol can't read them directly.
        expectError(404, "playlist_not_found", call(carol, "GET", "/api/playlists/" + selected, null));
        expectError(404, "playlist_not_found", call(carol, "GET", "/api/playlists/" + hidden, null));

        // Through the public room: the selected one, for anyone who may open the room (anonymous too).
        for (String token : new String[] {carol, null, alice}) {
            JsonObject l = listing(token, publicRoom);
            assertEquals("Bob: Selected", summary(l));
            JsonObject read = ok(200, token, "GET", "/api/rooms/" + publicRoom + "/autoplay/playlists/" + selected,
                    null);
            assertEquals(bobId, read.getAsJsonObject("owner").get("id").getAsString());
            assertEquals("Bob", read.getAsJsonObject("owner").get("name").getAsString());
            JsonObject pl = read.getAsJsonObject("playlist");
            assertEquals("Selected", pl.get("name").getAsString());
            JsonArray entries = pl.getAsJsonArray("entries");
            assertEquals(1, entries.size());
            assertEquals(song, entries.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
            // The unselected one isn't readable, not even its name.
            expectError(404, "playlist_not_found", call(token, "GET", "/api/rooms/" + publicRoom
                    + "/autoplay/playlists/" + hidden, null));
            assertFalse(l.toString().contains("Hidden"));
        }
        // Through the private room: only for those invited.
        expectError(404, "room_not_found", call(carol, "GET", "/api/rooms/" + privateRoom + "/autoplay", null));
        expectError(404, "room_not_found", call(carol, "GET", "/api/rooms/" + privateRoom
                + "/autoplay/playlists/" + selected, null));
        expectError(404, "room_not_found", call(null, "GET", "/api/rooms/" + privateRoom + "/autoplay", null));
        assertEquals("Bob: Selected", summary(listing(alice, privateRoom)));

        // Selected in one room doesn't make it readable through another.
        String third = room(alice, "Third", "public");
        expectError(404, "playlist_not_found", call(carol, "GET", "/api/rooms/" + third + "/autoplay/playlists/"
                + selected, null));

        // Deselected: no longer readable.
        ok(200, bob, "DELETE", "/api/rooms/" + publicRoom + "/autoplay/playlists/" + selected, null);
        expectError(404, "playlist_not_found", call(carol, "GET", "/api/rooms/" + publicRoom
                + "/autoplay/playlists/" + selected, null));
        assertEquals("", summary(listing(carol, publicRoom)));
        // The owner's own playlists API is unaffected.
        assertEquals("Selected", ok(200, bob, "GET", "/api/playlists/" + selected, null).get("name").getAsString());
    }
}
