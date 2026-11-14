package io.siggi.togethertunes.library;

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

/** {@code /api/playlists...} over HTTP ({@link PlaylistsApi}): JSON shape, statuses, body validation. */
class PlaylistsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Settings settings;
    private String aliceId;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        TestUploads.playlistsApi(db, config, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
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

    private void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, code(r), r.body());
    }

    private List<String> entryIds(String playlistId) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : json(call(alice, "GET", "/api/playlists/" + playlistId, null)).getAsJsonArray("entries")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        return ids;
    }

    @Test
    void playlistLifecycle() throws Exception {
        HttpResponse<String> created = call(alice, "POST", "/api/playlists",
                "{\"name\":\" Road  trip \",\"description\":\"For the car\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElse(""));
        JsonObject p = json(created);
        String id = p.get("id").getAsString();
        assertEquals("Road trip", p.get("name").getAsString());
        assertEquals("For the car", p.get("description").getAsString());
        assertEquals(0, p.get("entryCount").getAsInt());
        assertEquals(clock.millis(), p.get("createdAt").getAsLong());
        assertEquals(clock.millis(), p.get("updatedAt").getAsLong());
        assertEquals(6, p.size(), p.toString());

        String song = item("Song", "ready", null);
        clock.advance(1000);
        HttpResponse<String> added = call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + song + "\"}");
        assertEquals(201, added.statusCode(), added.body());
        JsonObject entry = json(added);
        assertEquals(clock.millis(), entry.get("addedAt").getAsLong());
        assertFalse(entry.has("position"));
        JsonObject itemJson = entry.getAsJsonObject("item");
        assertEquals(song, itemJson.get("id").getAsString());
        assertEquals("Song", itemJson.get("title").getAsString());
        assertEquals("ready", itemJson.get("status").getAsString());
        assertFalse(itemJson.has("album"));
        String e1 = entry.get("id").getAsString();
        String e2 = json(call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + song + "\",\"beforeEntryId\":\"" + e1 + "\"}")).get("id").getAsString();
        String e3 = json(call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + song + "\",\"beforeEntryId\":null}")).get("id").getAsString();
        assertEquals(List.of(e2, e1, e3), entryIds(id));

        HttpResponse<String> moved = call(alice, "PATCH", "/api/playlists/" + id + "/entries/" + e3,
                "{\"beforeEntryId\":\"" + e2 + "\"}");
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(e3, json(moved).get("id").getAsString());
        assertEquals(List.of(e3, e2, e1), entryIds(id));
        assertEquals(200, call(alice, "PATCH", "/api/playlists/" + id + "/entries/" + e3,
                "{\"beforeEntryId\":null}").statusCode());
        assertEquals(List.of(e2, e1, e3), entryIds(id));

        JsonObject page = json(call(alice, "GET", "/api/playlists/" + id, null));
        assertEquals(3, page.get("entryCount").getAsInt());
        assertEquals("Road trip", page.get("name").getAsString());
        assertEquals(clock.millis(), page.get("updatedAt").getAsLong());
        assertEquals(entry, page.getAsJsonArray("entries").get(1));

        HttpResponse<String> renamed = call(alice, "PATCH", "/api/playlists/" + id,
                "{\"name\":\"Trip\",\"description\":null}");
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Trip", json(renamed).get("name").getAsString());
        assertFalse(json(renamed).has("description"));
        assertEquals(3, json(renamed).get("entryCount").getAsInt());

        JsonObject list = json(call(alice, "GET", "/api/playlists", null));
        JsonArray items = list.getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals(json(renamed), items.get(0));
        assertFalse(list.has("nextCursor"));
        assertEquals(0, json(call(bob, "GET", "/api/playlists", null)).getAsJsonArray("items").size());

        assertEquals(204, call(alice, "DELETE", "/api/playlists/" + id + "/entries/" + e1, null).statusCode());
        expectError(404, "entry_not_found", call(alice, "DELETE", "/api/playlists/" + id + "/entries/" + e1, null));
        assertEquals(List.of(e2, e3), entryIds(id));
        assertEquals(204, call(alice, "DELETE", "/api/playlists/" + id, null).statusCode());
        expectError(404, "playlist_not_found", call(alice, "DELETE", "/api/playlists/" + id, null));
        expectError(404, "playlist_not_found", call(alice, "GET", "/api/playlists/" + id, null));
    }

    @Test
    void pagesByCursor() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clock.advance(1000);
            ids.add(0, json(call(alice, "POST", "/api/playlists", "{\"name\":\"L" + i + "\"}")).get("id").getAsString());
        }
        List<String> seen = new ArrayList<>();
        String path = "/api/playlists?limit=2";
        while (true) {
            JsonObject page = json(call(alice, "GET", path, null));
            page.getAsJsonArray("items").forEach(e -> seen.add(e.getAsJsonObject().get("id").getAsString()));
            if (!page.has("nextCursor")) {
                break;
            }
            path = "/api/playlists?limit=2&cursor=" + page.get("nextCursor").getAsString();
        }
        assertEquals(ids, seen);
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/playlists?cursor=nope", null));
        for (String bad : List.of("0", "201", "x")) {
            expectError(400, "invalid_limit", call(alice, "GET", "/api/playlists?limit=" + bad, null));
        }
        assertEquals(5, json(call(alice, "GET", "/api/playlists?limit=&cursor=", null)).getAsJsonArray("items").size());
    }

    @Test
    void errorsAndCallers() throws Exception {
        String id = json(call(alice, "POST", "/api/playlists", "{\"name\":\"Mine\"}")).get("id").getAsString();
        String song = item("Song", "ready", null);
        String entry = json(call(alice, "POST", "/api/playlists/" + id + "/entries", "{\"itemId\":\"" + song + "\"}"))
                .get("id").getAsString();
        String entryPath = "/api/playlists/" + id + "/entries/" + entry;
        List<String[]> calls = List.of(new String[]{"GET", "/api/playlists", null},
                new String[]{"POST", "/api/playlists", "{\"name\":\"x\"}"},
                new String[]{"GET", "/api/playlists/" + id, null},
                new String[]{"PATCH", "/api/playlists/" + id, "{\"name\":\"x\"}"},
                new String[]{"DELETE", "/api/playlists/" + id, null},
                new String[]{"POST", "/api/playlists/" + id + "/entries", "{\"itemId\":\"" + song + "\"}"},
                new String[]{"PATCH", entryPath, "{\"beforeEntryId\":null}"},
                new String[]{"DELETE", entryPath, null});
        for (String[] c : calls) {
            expectError(401, "not_authenticated", call(null, c[0], c[1], c[2]));
        }
        // bob sees none of it
        for (String[] c : calls.subList(2, calls.size())) {
            expectError(404, "playlist_not_found", call(bob, c[0], c[1], c[2]));
        }
        assertEquals(List.of(entry), entryIds(id));

        // bodies
        expectError(400, "invalid_name", call(alice, "POST", "/api/playlists", "{}"));
        expectError(400, "invalid_name", call(alice, "POST", "/api/playlists", "{\"name\":\"  \"}"));
        expectError(400, "invalid_name", call(alice, "POST", "/api/playlists", "{\"name\":5}"));
        expectError(400, "name_too_long", call(alice, "POST", "/api/playlists",
                "{\"name\":\"" + "n".repeat(101) + "\"}"));
        expectError(400, "invalid_description", call(alice, "POST", "/api/playlists",
                "{\"name\":\"a\",\"description\":[]}"));
        expectError(400, "description_too_long", call(alice, "POST", "/api/playlists",
                "{\"name\":\"a\",\"description\":\"" + "d".repeat(2001) + "\"}"));
        expectError(400, "unknown_field", call(alice, "POST", "/api/playlists", "{\"name\":\"a\",\"public\":true}"));
        expectError(400, "invalid_name", call(alice, "PATCH", "/api/playlists/" + id, "{\"name\":null}"));
        expectError(400, "unknown_field", call(alice, "PATCH", "/api/playlists/" + id, "{\"owner\":\"x\"}"));
        expectError(400, "invalid_item_id", call(alice, "POST", "/api/playlists/" + id + "/entries", "{}"));
        expectError(400, "invalid_item_id", call(alice, "POST", "/api/playlists/" + id + "/entries", "{\"itemId\":1}"));
        expectError(400, "invalid_before_entry_id", call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + song + "\",\"beforeEntryId\":7}"));
        expectError(400, "unknown_field", call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + song + "\",\"position\":1.5}"));
        expectError(400, "invalid_before_entry_id", call(alice, "PATCH", entryPath, "{}"));
        expectError(400, "unknown_field", call(alice, "PATCH", entryPath, "{\"beforeEntryId\":null,\"x\":1}"));
        expectError(409, "before_entry_not_found", call(alice, "PATCH", entryPath,
                "{\"beforeEntryId\":\"" + UuidV7.newId() + "\"}"));
        expectError(404, "entry_not_found", call(alice, "PATCH", "/api/playlists/" + id + "/entries/" + UuidV7.newId(),
                "{\"beforeEntryId\":null}"));
        expectError(404, "item_not_found", call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + UuidV7.newId() + "\"}"));
        expectError(409, "media_expired", call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + item("Old", "purged", null) + "\"}"));
        assertEquals(415, call(alice, "POST", "/api/playlists", null).statusCode());

        // A purged YouTube item is fetched again, which needs library.youtube (gated by ytdlp.enabled).
        String yt = item("Video", "purged", "eeeeeeeeeee");
        settings.set(Settings.YTDLP_ENABLED, false);
        expectError(403, "feature_disabled", call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + yt + "\"}"));
        settings.set(Settings.YTDLP_ENABLED, true);
        HttpResponse<String> refetch = call(alice, "POST", "/api/playlists/" + id + "/entries",
                "{\"itemId\":\"" + yt + "\"}");
        assertEquals(201, refetch.statusCode(), refetch.body());
        assertEquals("pending", json(refetch).getAsJsonObject("item").get("status").getAsString());
        assertTrue(entryIds(id).size() == 2);
    }

    private String video(String title) throws Exception {
        String id = item(title, "ready", null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET kind = 'video', media_type = 'uncategorized' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private List<String> playlistIds(String token) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonElement e : json(call(token, "GET", "/api/playlists", null)).getAsJsonArray("items")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        return ids;
    }

    @Test
    void likedSongsOrderAndMoves() throws Exception {
        String song = item("Song", "ready", null);
        String other = item("Other", "ready", null);
        // likes
        assertEquals("{\"liked\":false}", call(alice, "GET", "/api/me/likes/" + song, null).body());
        HttpResponse<String> liked = call(alice, "PUT", "/api/me/likes/" + song, null);
        assertEquals(201, liked.statusCode(), liked.body());
        String likedEntry = json(liked).get("id").getAsString();
        assertEquals(200, call(alice, "PUT", "/api/me/likes/" + song, null).statusCode());
        assertEquals(201, call(alice, "PUT", "/api/me/likes/" + other, "{}").statusCode());
        assertEquals("{\"liked\":true}", call(alice, "GET", "/api/me/likes/" + song, null).body());
        JsonObject likes = json(call(alice, "GET", "/api/me/likes", null));
        String likesId = likes.get("id").getAsString();
        assertEquals("likes", likes.get("builtin").getAsString());
        assertEquals("Liked Songs", likes.get("name").getAsString());
        assertEquals(2, likes.get("entryCount").getAsInt());
        assertEquals(List.of(json(call(alice, "PUT", "/api/me/likes/" + other, null)).get("id").getAsString(),
                likedEntry), entryIds(likesId));
        expectError(409, "builtin_playlist", call(alice, "PATCH", "/api/playlists/" + likesId, "{\"name\":\"x\"}"));
        expectError(409, "builtin_playlist", call(alice, "DELETE", "/api/playlists/" + likesId, null));
        assertEquals(204, call(alice, "DELETE", "/api/me/likes/" + song, null).statusCode());
        assertEquals(204, call(alice, "DELETE", "/api/me/likes/" + song, null).statusCode());
        assertEquals("{\"liked\":false}", call(alice, "GET", "/api/me/likes/" + song, null).body());
        expectError(400, "unknown_field", call(alice, "PUT", "/api/me/likes/" + song, "{\"x\":1}"));
        expectError(404, "item_not_found", call(alice, "PUT", "/api/me/likes/" + UuidV7.newId(), null));

        // an uncategorized video needs a type, in a playlist and in Liked Songs
        String clip = video("Clip");
        HttpResponse<String> needs = call(alice, "PUT", "/api/me/likes/" + clip, null);
        expectError(409, "needs_classification", needs);
        assertEquals(clip, json(needs).getAsJsonObject("error").get("itemId").getAsString());
        String a = json(call(alice, "POST", "/api/playlists", "{\"name\":\"A\"}")).get("id").getAsString();
        expectError(409, "needs_classification", call(alice, "POST", "/api/playlists/" + a + "/entries",
                "{\"itemId\":\"" + clip + "\"}"));
        expectError(400, "invalid_media_type", call(alice, "POST", "/api/playlists/" + a + "/entries",
                "{\"itemId\":\"" + clip + "\",\"mediaType\":\"podcast\"}"));
        // "uncategorized" leaves it so on purpose: in the playlist and the library, still uncategorized
        String left = video("Left");
        HttpResponse<String> leftAdd = call(alice, "POST", "/api/playlists/" + a + "/entries",
                "{\"itemId\":\"" + left + "\",\"mediaType\":\"uncategorized\"}");
        assertEquals(201, leftAdd.statusCode(), leftAdd.body());
        assertEquals("uncategorized", json(leftAdd).getAsJsonObject("item").get("mediaType").getAsString());
        assertEquals(1, json(call(alice, "GET", "/api/library/" + left + "/playlists", null))
                .getAsJsonArray("playlists").size(), "in the playlist, so in the library");
        assertEquals(204, call(alice, "DELETE", "/api/playlists/" + a + "/entries/"
                + json(leftAdd).get("id").getAsString(), null).statusCode());
        HttpResponse<String> typed = call(alice, "POST", "/api/playlists/" + a + "/entries",
                "{\"itemId\":\"" + clip + "\",\"mediaType\":\"movie\"}");
        assertEquals(201, typed.statusCode(), typed.body());
        assertEquals("movie", json(typed).getAsJsonObject("item").get("mediaType").getAsString());
        assertEquals(201, call(alice, "PUT", "/api/me/likes/" + clip, null).statusCode());
        JsonArray holding = json(call(alice, "GET", "/api/library/" + clip + "/playlists", null))
                .getAsJsonArray("playlists");
        assertEquals(2, holding.size());
        assertEquals(likesId, holding.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(a, holding.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals(0, json(call(bob, "GET", "/api/library/" + clip + "/playlists", null))
                .getAsJsonArray("playlists").size());

        // playlist order
        String b = json(call(alice, "POST", "/api/playlists", "{\"name\":\"B\"}")).get("id").getAsString();
        String c = json(call(alice, "POST", "/api/playlists", "{\"name\":\"C\"}")).get("id").getAsString();
        assertEquals(List.of(likesId, c, b, a), playlistIds(alice));
        assertEquals(204, call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + a + "\",\"" + b + "\"],\"beforePlaylistId\":\"" + c + "\"}").statusCode());
        assertEquals(List.of(likesId, a, b, c), playlistIds(alice));
        assertEquals(204, call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + a + "\"],\"beforePlaylistId\":null}").statusCode());
        assertEquals(List.of(likesId, b, c, a), playlistIds(alice));
        expectError(400, "invalid_playlist_ids", call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[],\"beforePlaylistId\":null}"));
        expectError(400, "invalid_playlist_ids", call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + a + "\",\"" + a + "\"],\"beforePlaylistId\":null}"));
        expectError(400, "invalid_before_playlist_id", call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + a + "\"]}"));
        expectError(409, "builtin_playlist", call(alice, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + likesId + "\"],\"beforePlaylistId\":null}"));
        expectError(404, "playlist_not_found", call(bob, "PUT", "/api/playlists/order",
                "{\"playlistIds\":[\"" + a + "\"],\"beforePlaylistId\":null}"));

        // several entries at once
        List<String> e = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            e.add(json(call(alice, "POST", "/api/playlists/" + b + "/entries", "{\"itemId\":\"" + song + "\"}"))
                    .get("id").getAsString());
        }
        HttpResponse<String> moved = call(alice, "POST", "/api/playlists/" + b + "/entries/move",
                "{\"entryIds\":[\"" + e.get(3) + "\",\"" + e.get(2) + "\"],\"beforeEntryId\":\"" + e.get(0) + "\"}");
        assertEquals(200, moved.statusCode(), moved.body());
        List<String> order = List.of(e.get(3), e.get(2), e.get(0), e.get(1));
        assertEquals(order, entryIds(b));
        assertEquals(4, json(moved).getAsJsonArray("entries").size());
        assertEquals(e.get(3), json(moved).getAsJsonArray("entries").get(0).getAsJsonObject().get("id").getAsString());
        expectError(400, "invalid_entry_ids", call(alice, "POST", "/api/playlists/" + b + "/entries/move",
                "{\"entryIds\":\"x\",\"beforeEntryId\":null}"));
        expectError(400, "invalid_before_entry_id", call(alice, "POST", "/api/playlists/" + b + "/entries/move",
                "{\"entryIds\":[\"" + e.get(0) + "\"]}"));
        expectError(404, "entry_not_found", call(alice, "POST", "/api/playlists/" + b + "/entries/move",
                "{\"entryIds\":[\"" + UuidV7.newId() + "\"],\"beforeEntryId\":null}"));
        expectError(404, "playlist_not_found", call(bob, "POST", "/api/playlists/" + b + "/entries/move",
                "{\"entryIds\":[\"" + e.get(0) + "\"],\"beforeEntryId\":null}"));
        assertEquals(order, entryIds(b));

        // guests and anonymous callers have none of it
        for (String[] call : List.of(new String[]{"GET", "/api/me/likes", null},
                new String[]{"GET", "/api/me/likes/" + song, null},
                new String[]{"PUT", "/api/me/likes/" + song, null},
                new String[]{"DELETE", "/api/me/likes/" + song, null},
                new String[]{"GET", "/api/library/" + song + "/playlists", null},
                new String[]{"PUT", "/api/playlists/order", "{\"playlistIds\":[\"" + a + "\"],\"beforePlaylistId\":null}"},
                new String[]{"POST", "/api/playlists/" + b + "/entries/move",
                        "{\"entryIds\":[\"" + e.get(0) + "\"],\"beforeEntryId\":null}"})) {
            expectError(401, "not_authenticated", call(null, call[0], call[1], call[2]));
        }
        assertEquals(List.of(likesId, b, c, a), playlistIds(alice));
    }
}
