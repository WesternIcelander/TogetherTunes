package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestItems;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Linked media over HTTP ({@code PUT /api/items/{id}/links}, the item page's {@code links};
 * {@link ItemLinks}): permissions, validation, target summaries, delete record.
 */
class ItemLinksApiTest {
    @TempDir
    Path dir;

    private Database db;
    private final List<String> roomChecks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String aliceId;
    private String alice;
    private String admin;
    private String bob;
    private String bobId;
    private Settings settings;
    private io.siggi.togethertunes.ingest.IngestJobs jobs;
    private io.siggi.togethertunes.DataDir data;
    private final java.util.concurrent.atomic.AtomicLong free = new java.util.concurrent.atomic.AtomicLong(50L << 30);

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        this.settings = settings;
        jobs = new io.siggi.togethertunes.ingest.IngestJobs(db, clock);
        YoutubeImports youtube = TestUploads.youtube(db, config, io.siggi.togethertunes.youtube.YoutubeService.none(), clock);
        youtube.diskSpace(new io.siggi.togethertunes.storage.DiskSpace(
                () -> new io.siggi.togethertunes.storage.DiskSpace.Usage(free.get(), 100L << 30), () -> 5L << 30,
                java.time.Duration.ZERO, System::nanoTime));
        data = io.siggi.togethertunes.DataDir.create(dir.resolve("data"));
        Items items = new Items(db, data, clock);
        MediaVersions versions = new MediaVersions(data, db, clock::millis, java.time.Duration.ofMinutes(5));
        versions.undoWindow(() -> 48 * 3_600_000L);
        items.mediaVersions(versions);
        items.trimJobs(jobs);
        ItemsApi itemsApi = new ItemsApi(items, new Permissions(db, settings), youtube);
        // GET /api/search from a room: "open" lets guests and anonymous visitors search, "closed" refuses
        itemsApi.roomSearch((actor, room) -> {
            roomChecks.add(actor + "@" + room);
            if (!room.equals("open")) {
                throw new io.siggi.togethertunes.http.HttpError(403, "guest_library_requests_disabled", "no");
            }
        });
        itemsApi.register(router);
        new LibraryApi(new Library(db, youtube, clock), TestUploads.policy(new Permissions(db, settings), settings))
                .register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        // the first account gets the admin role
        String adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
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

    private String item(String kind) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, ?, 'Song', 'Artist', 'upload', ?, 3, 'ready', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, aliceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 3, 'upload')")) {
                ps.setString(1, aliceId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return json(r).getAsJsonObject("error").get("code").getAsString();
    }


    private long linkRows() throws Exception {
        return db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM item_links")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private HttpResponse<String> put(String itemId, String token, String links) throws Exception {
        return call("PUT", "/api/items/" + itemId + "/links", token, "{\"links\":" + links + "}");
    }

    private static String reason(HttpResponse<String> r) {
        return json(r).getAsJsonObject("error").get("reason").getAsString();
    }

    @Test
    void saveAndShowLinks() throws Exception {
        String song = item("audio");
        String video = item("video");
        HttpResponse<String> r = put(song, alice, "[{\"type\":\"music_video\",\"itemId\":\"" + video + "\"},"
                + "{\"type\":\"external\",\"url\":\" https://example.com/article?a=1 \",\"label\":\"  The   story \"},"
                + "{\"type\":\"external\",\"url\":\"http://shop.example/x\"}]");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        var links = json(r).getAsJsonArray("links");
        assertEquals(3, links.size());
        JsonObject mv = links.get(0).getAsJsonObject();
        assertEquals("music_video", mv.get("type").getAsString());
        assertTrue(mv.has("id"));
        assertFalse(mv.has("url"));
        JsonObject target = mv.getAsJsonObject("item");
        assertEquals(video, target.get("id").getAsString());
        assertEquals("Song", target.get("title").getAsString());
        assertEquals("Artist", target.get("artist").getAsString());
        assertEquals("ready", target.get("status").getAsString());
        assertEquals("uncategorized", target.get("mediaType").getAsString());
        assertFalse(target.has("coverUrl"), "no cover file: no coverUrl");
        assertEquals(java.util.Set.of("id", "title", "artist", "status", "mediaType"), target.keySet(),
                "nothing more than the summary (no adder, no libraries)");
        JsonObject ext = links.get(1).getAsJsonObject();
        assertEquals("external", ext.get("type").getAsString());
        assertEquals("https://example.com/article?a=1", ext.get("url").getAsString());
        assertEquals("The story", ext.get("label").getAsString());
        assertFalse(ext.has("item"));
        assertFalse(links.get(2).getAsJsonObject().has("label"));
        assertFalse(r.body().contains(aliceId), "never who added the links");

        // every viewer sees them, guests and anonymous visitors too
        for (String token : new String[] {bob, null}) {
            JsonObject page = json(call("GET", "/api/items/" + song, token, null));
            assertEquals(links, page.getAsJsonArray("links"));
        }
        // an item without links has an empty list
        assertEquals(0, json(call("GET", "/api/items/" + video, bob, null)).getAsJsonArray("links").size());

        // reorder keeps the ids; a purged target stays linked, without a cover
        String mvId = mv.get("id").getAsString();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged' WHERE id = ?")) {
                ps.setString(1, video);
                ps.executeUpdate();
            }
            return null;
        });
        r = put(song, alice, "[{\"type\":\"external\",\"url\":\"http://shop.example/x\"},"
                + "{\"type\":\"lyric_video\",\"itemId\":\"" + video + "\"}]");
        assertEquals(200, r.statusCode(), r.body());
        links = json(r).getAsJsonArray("links");
        assertEquals("lyric_video", links.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("purged", links.get(1).getAsJsonObject().getAsJsonObject("item").get("status").getAsString());
        assertEquals(2, linkRows());
        r = put(song, alice, "[{\"type\":\"lyric_video\",\"itemId\":\"" + video + "\"},"
                + "{\"type\":\"external\",\"url\":\"http://shop.example/x\"}]");
        assertEquals(links.get(1).getAsJsonObject().get("id"), json(r).getAsJsonArray("links").get(0).getAsJsonObject().get("id"),
                "a link that stays keeps its id");
        assertFalse(mvId.equals(json(r).getAsJsonArray("links").get(0).getAsJsonObject().get("id").getAsString()),
                "a changed type is a new link");

        // remove all
        assertEquals(0, json(put(song, alice, "[]")).getAsJsonArray("links").size());
        assertEquals(0, linkRows());
    }

    @Test
    void whoMaySave() throws Exception {
        String song = item("audio");
        String video = item("video");
        String links = "[{\"type\":\"music_video\",\"itemId\":\"" + video + "\"}]";
        assertEquals(401, put(song, null, links).statusCode());
        assertEquals(403, put(song, bob, links).statusCode(), "not the adder, no library.editAny");
        assertEquals(200, put(song, admin, links).statusCode(), "library.editAny");
        assertEquals(404, put(UuidV7.newId(), alice, links).statusCode());
        assertEquals("item_not_found", code(put(UuidV7.newId(), alice, links)));
        // the adder without the item in their library can't
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM library_entries WHERE item_id = ?")) {
                ps.setString(1, song);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(403, put(song, alice, "[]").statusCode());
        assertEquals(1, linkRows());
    }

    @Test
    void validation() throws Exception {
        String song = item("audio");
        String video = item("video");
        String v = "{\"type\":\"music_video\",\"itemId\":\"" + video + "\"}";
        assertEquals("bad_type", reason(put(song, alice, "[{\"type\":\"karaoke\",\"itemId\":\"" + video + "\"}]")));
        assertEquals("bad_type", reason(put(song, alice, "[{\"itemId\":\"" + video + "\"}]")));
        assertEquals("bad_type", reason(put(song, alice, "[1]")));
        assertEquals("self_link", reason(put(song, alice, "[{\"type\":\"lyric_video\",\"itemId\":\"" + song + "\"}]")));
        HttpResponse<String> r = put(song, alice, "[" + v + "," + v.replace("music_video", "lyric_video") + "]");
        assertEquals(400, r.statusCode());
        assertEquals("invalid_link", code(r));
        assertEquals("duplicate", reason(r));
        assertEquals(1, json(r).getAsJsonObject("error").get("index").getAsInt());
        assertEquals("duplicate", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\"},"
                + "{\"type\":\"external\",\"url\":\"https://a.example/\"}]")));
        for (String bad : new String[] {"ftp://a.example/", "javascript:alert(1)", "https://", "a.example",
                "https://a.example/ x", "https://user:pw@a.example/", ""}) {
            assertEquals("bad_url", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"" + bad + "\"}]")), bad);
        }
        assertEquals("bad_url", reason(put(song, alice, "[{\"type\":\"external\"}]")));
        assertEquals("bad_url", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\","
                + "\"itemId\":\"" + video + "\"}]")));
        assertEquals("bad_url", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/"
                + "x".repeat(2000) + "\"}]")));
        assertEquals("bad_label", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\","
                + "\"label\":\"" + "x".repeat(101) + "\"}]")));
        assertEquals("bad_label", reason(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\","
                + "\"label\":5}]")));
        assertEquals(200, put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\","
                + "\"label\":\"" + "ü".repeat(100) + "\"}]").statusCode(), "100 characters are fine");
        assertEquals("not_an_item", reason(put(song, alice, "[{\"type\":\"music_video\"}]")));
        assertEquals("not_an_item", reason(put(song, alice, "[{\"type\":\"music_video\",\"url\":\"https://a.example/\"}]")));
        assertEquals("not_an_item", reason(put(song, alice, "[{\"type\":\"music_video\",\"itemId\":\"" + video
                + "\",\"label\":\"x\"}]")));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append("{\"type\":\"external\",\"url\":\"https://a.example/").append(i).append("\"}");
        }
        assertEquals("too_many", reason(put(song, alice, many + "]")));
        assertEquals(200, put(song, alice, many.substring(0, many.lastIndexOf(",{")) + "]").statusCode(), "20 are fine");
        r = put(song, alice, "[{\"type\":\"music_video\",\"itemId\":\"" + UuidV7.newId() + "\"}]");
        assertEquals(404, r.statusCode());
        assertEquals("item_not_found", code(r));
        assertEquals("unknown_field", code(call("PUT", "/api/items/" + song + "/links", alice, "{\"links\":[],\"x\":1}")));
        assertEquals("unknown_field", code(put(song, alice, "[{\"type\":\"external\",\"url\":\"https://a.example/\",\"x\":1}]")));
        assertEquals("invalid_link", code(call("PUT", "/api/items/" + song + "/links", alice, "{}")));
        assertEquals(20, linkRows(), "refused saves changed nothing");
    }

    @Test
    void deleteRecordRemovesLinksOnBothSides() throws Exception {
        String song = item("audio");
        String video = item("video");
        String other = item("audio");
        assertEquals(200, put(song, alice, "[{\"type\":\"music_video\",\"itemId\":\"" + video + "\"},"
                + "{\"type\":\"external\",\"url\":\"https://a.example/\"}]").statusCode());
        assertEquals(200, put(video, alice, "[{\"type\":\"lyric_video\",\"itemId\":\"" + other + "\"}]").statusCode());
        assertEquals(200, put(other, alice, "[{\"type\":\"external\",\"url\":\"https://b.example/\"}]").statusCode());
        assertEquals(4, linkRows());
        HttpResponse<String> r = call("DELETE", "/api/admin/items/" + video, admin, null);
        assertEquals(204, r.statusCode(), r.body());
        assertEquals(2, linkRows(), "the two external links are left");
        assertEquals(1, json(call("GET", "/api/items/" + song, alice, null)).getAsJsonArray("links").size());
        assertEquals(1, json(call("GET", "/api/items/" + other, alice, null)).getAsJsonArray("links").size());
    }
}
