package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.GuestNames;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.library.GuestUploadQuota;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.UploadsApi;
import io.siggi.togethertunes.library.YoutubeImports;
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
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A signed-in user's upload as a request in a room ({@code POST /api/uploads {roomId}}), with
 * real rooms behind {@link AddMediaPolicy}: the room's request checks come up front (the same as
 * {@code queue.add {uploadId}}), and the upload is then queued by its id and lands in the user's
 * library. Guests' uploads are covered by {@code GuestUploadsContract} and
 * {@link RoomGuestsContract}.
 */
class UserRoomUploadsTest {
    private static final String IP = "203.0.113.40";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final HttpClient http = HttpClient.newHttpClient();
    private Database db;
    private RoomRegistry registry;
    private WebServer server;
    private Rooms rooms;
    private RoomGuests roomGuests;
    private RoomQueue queue;
    private Actor.User aliceActor;
    private Actor.User bobActor;
    private String bob;
    private String bobId;
    private String roomId;
    private String roomSlug;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        Settings settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        bob = sessions.create(bobId, Actor.Via.BEARER, null, null).token();
        aliceActor = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bobActor = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        Rooms.RoomView room = rooms.create(aliceActor, "Party", null, Rooms.Visibility.PUBLIC, null, IP);
        roomId = room.id();
        roomSlug = room.slug();

        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        roomGuests = new RoomGuests(db, settings, permissions, rooms, registry);
        rooms.liveIps(registry.mediaAccess());
        AddMediaPolicy policy = new AddMediaPolicy(permissions, settings, roomGuests);
        Tools tools = new Tools(null, null, null);
        Uploads uploads = new Uploads(db, DataDir.create(dir.resolve("data")), tools, new IngestJobs(db, clock), () -> {
        }, OutputEncoding.defaultFor(OutputProfile.P720_30), 20_000_000, 600, new GuestUploadQuota(db, settings, clock),
                clock);
        uploads.precheck(true);
        YoutubeImports youtube = TestUploads.youtube(db, config, io.siggi.togethertunes.youtube.YoutubeService.none(), clock);
        queue = new RoomQueue(registry, db, uploads, youtube, new Playlists(db, youtube, clock), policy, new Random(1));
        Router router = new Router(new ActorResolver(db, settings, clock).hook());
        new UploadsApi(uploads, policy, new Guests(db, settings, clock, false, GuestNames.bundled())).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
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

    private record Reply(int status, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            return json().getAsJsonObject("error").get("code").getAsString();
        }
    }

    private Reply start(String room, String extra) throws Exception {
        String body = "{\"filename\":\"song.m4a\",\"size\":10" + (room == null ? "" : ",\"roomId\":\"" + room + "\"")
                + (extra == null ? "" : "," + extra) + "}";
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/uploads"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bob)
                .header("X-Forwarded-For", IP)
                .build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body());
    }

    private static void assertError(int status, String code, Reply r) {
        assertEquals(status, r.status(), r.body());
        assertEquals(code, r.error(), r.body());
    }

    private void sql(String statement, Object... args) throws SQLException {
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

    private List<Object> row(String query, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Object> out = new ArrayList<>();
                    while (rs.next()) {
                        for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                            out.add(rs.getObject(col));
                        }
                    }
                    return out;
                }
            }
        });
    }

    /** A ready audio item uploaded by alice, with this content ID (or none). */
    private String item(String contentId) throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, content_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', 'upload', ?, ?, 1,"
                + " 'ready', '720p30', 1, 60000)", id, contentId, aliceActor.userId());
        return id;
    }

    private String room(String name, Rooms.Visibility visibility) throws SQLException {
        return rooms.create(aliceActor, name, null, visibility, null, IP).id();
    }

    private QueueOps.Added request(String room, String uploadId) throws Exception {
        return queue.add(room, bobActor, roomGuests.roomActor(bobActor, IP, room), RoomQueue.Source.upload(uploadId));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aUsersUploadIsARequestInTheRoom() throws Exception {
        // By slug too: the room's id is what's recorded.
        Reply r = start(roomSlug, null);
        assertEquals(201, r.status(), r.body());
        String uploadId = r.json().get("uploadId").getAsString();
        assertEquals(List.of(roomId, bobId), row("SELECT room_id, user_id FROM uploads WHERE id = ?", uploadId));
        assertEquals("upload_incomplete", assertRefused(() -> request(roomId, uploadId)));
        // Completed (the ingest itself is covered by the upload tests): queued, and in bob's library.
        String itemId = item(null);
        sql("UPDATE uploads SET item_id = ? WHERE id = ?", itemId, uploadId);
        QueueOps.Added added = request(roomId, uploadId);
        assertEquals(itemId, added.itemId());
        assertEquals(List.of("upload", "upload"), List.of(
                row("SELECT via FROM queue_entries WHERE id = ?", added.entryId()).get(0),
                row("SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?", bobId, itemId).get(0)));
    }

    @Test
    void aPrecheckHitIsQueuedWithoutBytes() throws Exception {
        String contentId = "sha256c8m:10:" + "ab".repeat(32);
        String itemId = item(contentId);
        Reply r = start(roomId, "\"contentId\":\"" + contentId + "\"");
        assertEquals(200, r.status(), r.body());
        assertTrue(r.json().get("deduplicated").getAsBoolean(), r.body());
        assertEquals(itemId, r.json().get("itemId").getAsString());
        String uploadId = r.json().get("uploadId").getAsString();
        assertEquals(List.of(roomId), row("SELECT room_id FROM uploads WHERE id = ?", uploadId));
        QueueOps.Added added = request(roomId, uploadId);
        assertEquals(itemId, added.itemId());
        assertEquals(List.of("upload"), row("SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?",
                bobId, itemId));
    }

    @Test
    void theRoomsRequestChecksComeFirst() throws Exception {
        // Unknown rooms and rooms bob can't see: 404, nothing recorded.
        assertError(404, "room_not_found", start(UuidV7.newId(), null));
        assertError(404, "room_not_found", start("no-such-room", null));
        assertError(404, "room_not_found", start(room("Secret", Rooms.Visibility.PRIVATE), null));
        // The room doesn't take uploads as requests.
        String closed = room("Closed", Rooms.Visibility.PUBLIC);
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"uploadRequests\":false}", closed);
        assertError(403, "upload_requests_off", start(closed, null));
        // Banned or timed out there.
        String banned = room("Banned", Rooms.Visibility.PUBLIC);
        rooms.restrictUser(aliceActor, banned, bobId, null, IP);
        assertError(403, "banned_from_room", start(banned, null));
        String timedOut = room("Timeout", Rooms.Visibility.UNLISTED);
        rooms.restrictUser(aliceActor, timedOut, bobId, 600_000L, IP);
        assertError(403, "timed_out", start(timedOut, null));
        assertEquals(List.of(0L), List.of(((Number) row("SELECT COUNT(*) FROM uploads").get(0)).longValue()));
        // Request limit: as many waiting requests as the room allows (the first one plays at once).
        String limited = room("Limited", Rooms.Visibility.PUBLIC);
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"requestsPerActor\":1}", limited);
        for (int i = 0; i < 2; i++) {
            String id = start(limited, null).json().get("uploadId").getAsString();
            sql("UPDATE uploads SET item_id = ? WHERE id = ?", item(null), id);
            request(limited, id);
        }
        Reply r = start(limited, null);
        assertEquals(409, r.status(), r.body());
        assertEquals("request_limit", r.error());
        // Without a room, a user's upload is as before.
        assertEquals(201, start(null, null).status());
    }

    private interface Body {
        void run() throws Exception;
    }

    private static String assertRefused(Body body) {
        try {
            body.run();
        } catch (io.siggi.togethertunes.http.HttpError e) {
            return e.code();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("not refused");
    }
}
