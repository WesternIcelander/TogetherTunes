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

/** {@code /api/shares...} over HTTP ({@link SharesApi}): JSON shape, statuses, body validation. */
class SharesApiTest {
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
        TestUploads.sharesApi(db, config, new Permissions(db, settings), clock).register(router);
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

    @Test
    void shareListAndMarkSeen() throws Exception {
        String song = item("Song", "ready", null);
        clock.set(1_800_000_100_000L);
        HttpResponse<String> created = call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"BOB\",\"message\":\"Hi there\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElse(""));
        JsonObject s = json(created);
        assertEquals(List.of("createdAt", "from", "id", "item", "message", "to"), sortedKeys(s));
        assertEquals(song, s.getAsJsonObject("item").get("id").getAsString());
        assertEquals("Song", s.getAsJsonObject("item").get("title").getAsString());
        assertEquals("{\"kind\":\"user\",\"id\":\"" + aliceId + "\",\"name\":\"alice\",\"isGuest\":false}",
                s.getAsJsonObject("from").toString());
        assertEquals("bob", s.getAsJsonObject("to").get("name").getAsString());
        assertEquals("Hi there", s.get("message").getAsString());
        assertEquals(1_800_000_100_000L, s.get("createdAt").getAsLong());
        String shareId = s.get("id").getAsString();

        // in bob's library, marked as shared by alice
        JsonObject entry = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT via, shared_by_user_id FROM library_entries WHERE item_id = ?")) {
                ps.setString(1, song);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    JsonObject o = new JsonObject();
                    o.addProperty("via", rs.getString(1));
                    o.addProperty("by", rs.getString(2));
                    assertFalse(rs.next());
                    return o;
                }
            }
        });
        assertEquals("share", entry.get("via").getAsString());
        assertEquals(aliceId, entry.get("by").getAsString());

        HttpResponse<String> in = call(bob, "GET", "/api/shares", null);
        assertEquals(200, in.statusCode(), in.body());
        assertEquals("no-store", in.headers().firstValue("Cache-Control").orElse(""));
        JsonObject inPage = json(in);
        assertEquals(1, inPage.get("unseenCount").getAsInt());
        assertFalse(inPage.has("nextCursor"));
        JsonObject received = inPage.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(List.of("createdAt", "from", "id", "item", "message", "seen", "to"), sortedKeys(received));
        assertFalse(received.get("seen").getAsBoolean());
        assertEquals(shareId, received.get("id").getAsString());

        JsonObject outPage = json(call(alice, "GET", "/api/shares?direction=out", null));
        assertEquals(0, outPage.get("unseenCount").getAsInt());
        assertFalse(outPage.getAsJsonArray("items").get(0).getAsJsonObject().has("seen"));
        assertEquals(0, json(call(alice, "GET", "/api/shares?direction=in", null)).getAsJsonArray("items").size());

        HttpResponse<String> seen = call(bob, "POST", "/api/shares/seen", "{\"ids\":[\"" + shareId + "\"]}");
        assertEquals(200, seen.statusCode(), seen.body());
        assertEquals("{\"unseenCount\":0}", seen.body());
        assertTrue(json(call(bob, "GET", "/api/shares", null)).getAsJsonArray("items").get(0).getAsJsonObject()
                .get("seen").getAsBoolean());

        call(alice, "POST", "/api/shares", "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\"}");
        call(alice, "POST", "/api/shares", "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\"}");
        JsonObject page1 = json(call(bob, "GET", "/api/shares?limit=2", null));
        assertEquals(2, page1.get("unseenCount").getAsInt());
        assertEquals(2, page1.getAsJsonArray("items").size());
        JsonObject page2 = json(call(bob, "GET", "/api/shares?limit=2&cursor="
                + page1.get("nextCursor").getAsString(), null));
        assertEquals(shareId, page2.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("{\"unseenCount\":0}", call(bob, "POST", "/api/shares/seen", "{\"all\":true}").body());
    }

    private static List<String> sortedKeys(JsonObject o) {
        List<String> keys = new ArrayList<>(o.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }

    @Test
    void errors() throws Exception {
        String song = item("Song", "ready", null);
        expectError(401, "not_authenticated", call(null, "GET", "/api/shares", null));
        expectError(401, "not_authenticated", call(null, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\"}"));
        expectError(401, "not_authenticated", call(null, "POST", "/api/shares/seen", "{\"all\":true}"));

        expectError(400, "invalid_direction", call(alice, "GET", "/api/shares?direction=sideways", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/shares?limit=0", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/shares?limit=201", null));
        expectError(400, "invalid_limit", call(alice, "GET", "/api/shares?limit=x", null));
        expectError(400, "invalid_cursor", call(alice, "GET", "/api/shares?cursor=abc", null));

        expectError(400, "invalid_item_id", call(alice, "POST", "/api/shares", "{\"toUsername\":\"bob\"}"));
        expectError(400, "invalid_item_id", call(alice, "POST", "/api/shares",
                "{\"itemId\":5,\"toUsername\":\"bob\"}"));
        expectError(400, "invalid_username", call(alice, "POST", "/api/shares", "{\"itemId\":\"" + song + "\"}"));
        expectError(400, "invalid_username", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":[\"bob\"]}"));
        expectError(400, "invalid_message", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\",\"message\":1}"));
        expectError(400, "message_too_long", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\",\"message\":\"" + "x".repeat(501) + "\"}"));
        expectError(400, "unknown_field", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\",\"toUserId\":\"x\"}"));
        expectError(400, "cannot_share_with_self", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"alice\"}"));
        expectError(404, "user_not_found", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"nobody\"}"));
        expectError(404, "item_not_found", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + UuidV7.newId() + "\",\"toUsername\":\"bob\"}"));
        expectError(409, "media_expired", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + item("Old", "purged", null) + "\",\"toUsername\":\"bob\"}"));
        assertEquals(415, call(alice, "POST", "/api/shares", null).statusCode());

        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", "{}"));
        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", "{\"all\":false}"));
        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", "{\"all\":true,\"ids\":[]}"));
        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", "{\"ids\":\"x\"}"));
        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", "{\"ids\":[1]}"));
        StringBuilder many = new StringBuilder("{\"ids\":[");
        for (int i = 0; i <= Shares.SEEN_IDS_MAX; i++) {
            many.append(i == 0 ? "" : ",").append("\"x").append(i).append("\"");
        }
        expectError(400, "invalid_ids", call(bob, "POST", "/api/shares/seen", many.append("]}").toString()));
        expectError(400, "invalid_all", call(bob, "POST", "/api/shares/seen", "{\"all\":\"yes\"}"));
        expectError(400, "unknown_field", call(bob, "POST", "/api/shares/seen", "{\"every\":true}"));
        assertEquals(200, call(bob, "POST", "/api/shares/seen", "{\"ids\":[]}").statusCode());

        // A purged YouTube item is fetched again, which needs library.youtube (gated by ytdlp.enabled).
        String yt = item("Video", "purged", "eeeeeeeeeee");
        settings.set(Settings.YTDLP_ENABLED, false);
        expectError(403, "feature_disabled", call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + yt + "\",\"toUsername\":\"bob\"}"));
        settings.set(Settings.YTDLP_ENABLED, true);
        HttpResponse<String> refetch = call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + yt + "\",\"toUsername\":\"bob\"}");
        assertEquals(201, refetch.statusCode(), refetch.body());
        assertEquals("pending", json(refetch).getAsJsonObject("item").get("status").getAsString());
    }

    @Test
    void tooManySharesHasRetryAfter() throws Exception {
        String song = item("Song", "ready", null);
        for (int i = 0; i < Shares.MAX_PER_HOUR; i++) {
            assertEquals(201, call(alice, "POST", "/api/shares",
                    "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\"}").statusCode());
        }
        HttpResponse<String> limited = call(alice, "POST", "/api/shares",
                "{\"itemId\":\"" + song + "\",\"toUsername\":\"bob\"}");
        expectError(429, "too_many_shares", limited);
        assertEquals("3600", limited.headers().firstValue("Retry-After").orElse(""));
    }
}
