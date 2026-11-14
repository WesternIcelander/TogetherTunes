package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
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
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /api/library...} over HTTP ({@link LibraryApi}): JSON shape, statuses, paging by cursor. */
class LibraryApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Settings settings;
    private String aliceId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        TestUploads.libraryApi(db, config, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        token = new Sessions(db, clock).create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (auth) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String item(String title, String status, String youtubeId) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, source,"
                    + " youtube_id, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', ?, 'Artist', ?, ?, ?, 1, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, youtubeId == null ? "upload" : "youtube");
                ps.setString(4, youtubeId);
                ps.setString(5, aliceId);
                ps.setString(6, status);
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

    /** A ready show episode in alice's library. */
    private String episode(String show, Integer season, int episode) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, media_type, show_name,"
                    + " season_number, episode_number) VALUES (?, 'video', ?, 'upload', ?, 1, 'ready', '720p30', 1,"
                    + " 'show', ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, show + " " + season + "x" + episode);
                ps.setString(3, aliceId);
                ps.setString(4, show);
                ps.setObject(5, season);
                ps.setInt(6, episode);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')")) {
                ps.setString(1, aliceId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Test
    void showsAreGroupedByNameSeasonAndEpisode() throws Exception {
        String b2 = episode("Beta", 2, 1);
        String a2 = episode("Alpha", 1, 2);
        String a1 = episode("alpha", 1, 1);
        String bNone = episode("Beta", null, 4);
        String b1 = episode("Beta", 1, 3);
        item("Not a show", "ready", null);
        assertEquals(401, call("GET", "/api/library/shows", false).statusCode());
        HttpResponse<String> r = call("GET", "/api/library/shows", true);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        JsonArray shows = json(r).getAsJsonArray("shows");
        assertEquals(2, shows.size(), r.body());
        JsonObject alpha = shows.get(0).getAsJsonObject();
        assertEquals("alpha", alpha.get("name").getAsString()); // as the first episode spells it
        JsonArray alphaS1 = alpha.getAsJsonArray("seasons").get(0).getAsJsonObject().getAsJsonArray("episodes");
        assertEquals(a1, alphaS1.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertEquals(a2, alphaS1.get(1).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        JsonArray beta = shows.get(1).getAsJsonObject().getAsJsonArray("seasons");
        assertEquals(3, beta.size());
        assertFalse(beta.get(0).getAsJsonObject().has("season"));
        assertEquals(bNone, beta.get(0).getAsJsonObject().getAsJsonArray("episodes").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("id").getAsString());
        assertEquals(1, beta.get(1).getAsJsonObject().get("season").getAsInt());
        assertEquals(b1, beta.get(1).getAsJsonObject().getAsJsonArray("episodes").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("id").getAsString());
        assertEquals(b2, beta.get(2).getAsJsonObject().getAsJsonArray("episodes").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("id").getAsString());

        JsonArray one = json(call("GET", "/api/library/shows?name=%20BETA", true)).getAsJsonArray("shows");
        assertEquals(1, one.size());
        assertEquals("Beta", one.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0, json(call("GET", "/api/library/shows?name=Gamma", true)).getAsJsonArray("shows").size());
    }

    @Test
    void addListRemove() throws Exception {
        String id = item("First", "ready", null);
        HttpResponse<String> added = call("PUT", "/api/library/" + id, true);
        assertEquals(201, added.statusCode(), added.body());
        assertEquals("no-store", added.headers().firstValue("Cache-Control").orElse(""));
        JsonObject entry = json(added);
        assertEquals("manual", entry.get("via").getAsString());
        assertEquals(clock.millis(), entry.get("addedAt").getAsLong());
        assertFalse(entry.has("sharedBy"));
        JsonObject itemJson = entry.getAsJsonObject("item");
        assertEquals(id, itemJson.get("id").getAsString());
        assertEquals("audio", itemJson.get("kind").getAsString());
        assertEquals("First", itemJson.get("title").getAsString());
        assertEquals("Artist", itemJson.get("artist").getAsString());
        assertFalse(itemJson.has("album"));
        assertFalse(itemJson.has("durationMs"));
        assertFalse(itemJson.has("youtubeId"));
        assertEquals("upload", itemJson.get("source").getAsString());
        assertEquals("ready", itemJson.get("status").getAsString());
        assertFalse(itemJson.get("presentAsAudio").getAsBoolean());

        assertEquals(200, call("PUT", "/api/library/" + id, true).statusCode());
        JsonObject list = json(call("GET", "/api/library", true));
        assertEquals(1, list.getAsJsonArray("items").size());
        assertEquals(entry, list.getAsJsonArray("items").get(0));
        assertFalse(list.has("nextCursor"));

        assertEquals(204, call("DELETE", "/api/library/" + id, true).statusCode());
        HttpResponse<String> again = call("DELETE", "/api/library/" + id, true);
        assertEquals(404, again.statusCode());
        assertEquals("not_in_library", code(again));
        assertEquals(0, json(call("GET", "/api/library", true)).getAsJsonArray("items").size());
    }

    @Test
    void pagesAndFilters() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = item("Track " + i, "ready", null);
            clock.advance(1000);
            assertEquals(201, call("PUT", "/api/library/" + id, true).statusCode());
            ids.add(0, id);
        }
        List<String> seen = new ArrayList<>();
        String path = "/api/library?limit=2";
        while (true) {
            JsonObject page = json(call("GET", path, true));
            JsonArray items = page.getAsJsonArray("items");
            items.forEach(e -> seen.add(e.getAsJsonObject().getAsJsonObject("item").get("id").getAsString()));
            if (!page.has("nextCursor")) {
                break;
            }
            path = "/api/library?limit=2&cursor=" + page.get("nextCursor").getAsString();
        }
        assertEquals(ids, seen);
        JsonArray hits = json(call("GET", "/api/library?q=track%203&kind=audio", true)).getAsJsonArray("items");
        assertEquals(1, hits.size());
        assertEquals(ids.get(1), hits.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertEquals(0, json(call("GET", "/api/library?kind=video", true)).getAsJsonArray("items").size());
        assertEquals(5, json(call("GET", "/api/library?kind=&q=", true)).getAsJsonArray("items").size());

        assertEquals("invalid_kind", code(call("GET", "/api/library?kind=podcast", true)));
        assertEquals("invalid_cursor", code(call("GET", "/api/library?cursor=nope", true)));
        for (String bad : List.of("0", "201", "x")) {
            HttpResponse<String> r = call("GET", "/api/library?limit=" + bad, true);
            assertEquals(400, r.statusCode());
            assertEquals("invalid_limit", code(r));
        }
        assertEquals("invalid_query", code(call("GET", "/api/library?q=" + "x".repeat(201), true)));
    }

    /** The Uncategorized Videos view: the caller's uncategorized entries and their total. */
    @Test
    void uncategorizedView() throws Exception {
        JsonObject empty = json(call("GET", "/api/library/uncategorized?limit=1", true));
        assertEquals(0, empty.get("total").getAsLong());
        assertEquals(0, empty.getAsJsonArray("items").size());
        assertFalse(empty.has("nextCursor"));
        String song = item("Song", "ready", null);
        List<String> clips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = item("Clip " + i, "ready", null);
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = 'video',"
                        + " media_type = 'uncategorized' WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id,"
                        + " added_at, via) VALUES (?, ?, ?, 'upload')")) {
                    ps.setString(1, aliceId);
                    ps.setString(2, id);
                    ps.setLong(3, clock.millis());
                    ps.executeUpdate();
                }
                return null;
            });
            clock.advance(1000);
            clips.add(0, id);
        }
        assertEquals(201, call("PUT", "/api/library/" + song, true).statusCode());
        HttpResponse<String> first = call("GET", "/api/library/uncategorized?limit=2", true);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElse(""));
        JsonObject page = json(first);
        assertEquals(3, page.get("total").getAsLong());
        List<String> seen = new ArrayList<>();
        page.getAsJsonArray("items").forEach(e -> seen.add(
                e.getAsJsonObject().getAsJsonObject("item").get("id").getAsString()));
        JsonObject second = json(call("GET", "/api/library/uncategorized?limit=2&cursor="
                + page.get("nextCursor").getAsString(), true));
        second.getAsJsonArray("items").forEach(e -> seen.add(
                e.getAsJsonObject().getAsJsonObject("item").get("id").getAsString()));
        assertEquals(clips, seen, "newest first, the song left out");
        assertFalse(second.has("nextCursor"));
        JsonObject searched = json(call("GET", "/api/library/uncategorized?q=clip%201", true));
        assertEquals(1, searched.getAsJsonArray("items").size());
        assertEquals(3, searched.get("total").getAsLong(), "the total isn't narrowed by q");
        assertEquals("uncategorized", searched.getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("mediaType").getAsString());
        assertEquals("invalid_limit", code(call("GET", "/api/library/uncategorized?limit=0", true)));
        assertEquals("invalid_cursor", code(call("GET", "/api/library/uncategorized?cursor=nope", true)));
        assertEquals(401, call("GET", "/api/library/uncategorized", false).statusCode());
    }

    private HttpResponse<String> put(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Classify on library add: an uncategorized video needs {@code mediaType} in the PUT body;
     * without it 409 needs_classification with the item and the suggestion (an upload's file name
     * with SxxEyy → show), and nothing changes.
     */
    @Test
    void anUncategorizedVideoNeedsATypeToBeAdded() throws Exception {
        String id = item("Clip", "ready", null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = 'video',"
                    + " media_type = 'uncategorized', metadata_json = ? WHERE id = ?")) {
                ps.setString(1, "{\"upload\":{\"filename\":\"Some_Show - s1e12 [720p].mkv\"}}");
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        HttpResponse<String> r = call("PUT", "/api/library/" + id, true);
        assertEquals(409, r.statusCode(), r.body());
        JsonObject error = json(r).getAsJsonObject("error");
        assertEquals("needs_classification", error.get("code").getAsString());
        assertEquals(id, error.get("itemId").getAsString());
        JsonObject suggested = error.getAsJsonObject("suggested");
        assertEquals("show", suggested.get("mediaType").getAsString());
        assertEquals("Some Show", suggested.getAsJsonObject("show").get("name").getAsString());
        assertEquals(1, suggested.getAsJsonObject("show").get("season").getAsInt());
        assertEquals(12, suggested.getAsJsonObject("show").get("episode").getAsInt());
        assertEquals(0, json(call("GET", "/api/library", true)).getAsJsonArray("items").size());

        assertEquals("invalid_media_type", code(put("/api/library/" + id, "{\"mediaType\":\"podcast\"}")));
        assertEquals("show_required", code(put("/api/library/" + id, "{\"mediaType\":\"show\"}")));
        assertEquals("invalid_show", code(put("/api/library/" + id, "{\"mediaType\":\"show\",\"show\":{\"episode\":1}}")));
        assertEquals("not_a_show", code(put("/api/library/" + id,
                "{\"mediaType\":\"movie\",\"show\":{\"name\":\"X\",\"episode\":1}}")));
        assertEquals("unknown_field", code(put("/api/library/" + id, "{\"title\":\"x\"}")));
        assertEquals(0, json(call("GET", "/api/library", true)).getAsJsonArray("items").size());

        HttpResponse<String> ok = put("/api/library/" + id,
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"Some Show\",\"season\":1,\"episode\":12}}");
        assertEquals(201, ok.statusCode(), ok.body());
        JsonObject itemJson = json(ok).getAsJsonObject("item");
        assertEquals("show", itemJson.get("mediaType").getAsString());
        assertEquals("Some Show", itemJson.getAsJsonObject("show").get("name").getAsString());
        assertEquals(12, itemJson.getAsJsonObject("show").get("episode").getAsInt());
        // already in the library: no question, and a type sent along changes nothing
        assertEquals(200, put("/api/library/" + id, "{\"mediaType\":\"movie\"}").statusCode());
        assertEquals("show", json(call("GET", "/api/library", true)).getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("item").get("mediaType").getAsString());
        // an audio item never asks
        assertEquals(201, call("PUT", "/api/library/" + item("Song", "ready", null), true).statusCode());
        // "uncategorized": added, left uncategorized on purpose
        String left = item("Left", "ready", null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = 'video',"
                    + " media_type = 'uncategorized' WHERE id = ?")) {
                ps.setString(1, left);
                ps.executeUpdate();
            }
            return null;
        });
        HttpResponse<String> leave = put("/api/library/" + left, "{\"mediaType\":\"uncategorized\"}");
        assertEquals(201, leave.statusCode(), leave.body());
        assertEquals("uncategorized", json(leave).getAsJsonObject("item").get("mediaType").getAsString());
    }

    @Test
    void errorsAndCallers() throws Exception {
        String id = item("Song", "ready", null);
        for (String[] c : List.of(new String[]{"GET", "/api/library"}, new String[]{"PUT", "/api/library/" + id},
                new String[]{"DELETE", "/api/library/" + id})) {
            HttpResponse<String> r = call(c[0], c[1], false);
            assertEquals(401, r.statusCode(), c[0]);
            assertEquals("not_authenticated", code(r));
        }
        HttpResponse<String> missing = call("PUT", "/api/library/" + UuidV7.newId(), true);
        assertEquals(404, missing.statusCode());
        assertEquals("item_not_found", code(missing));
        HttpResponse<String> expired = call("PUT", "/api/library/" + item("Old", "purged", null), true);
        assertEquals(409, expired.statusCode());
        assertEquals("media_expired", code(expired));

        // A purged YouTube item is fetched again, which needs library.youtube (gated by ytdlp.enabled).
        String yt = item("Video", "purged", "eeeeeeeeeee");
        settings.set(Settings.YTDLP_ENABLED, false);
        HttpResponse<String> off = call("PUT", "/api/library/" + yt, true);
        assertEquals(403, off.statusCode());
        assertEquals("feature_disabled", code(off));
        settings.set(Settings.YTDLP_ENABLED, true);
        HttpResponse<String> refetch = call("PUT", "/api/library/" + yt, true);
        assertEquals(201, refetch.statusCode(), refetch.body());
        assertEquals("pending", json(refetch).getAsJsonObject("item").get("status").getAsString());
    }
}
