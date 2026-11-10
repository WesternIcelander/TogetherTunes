package io.siggi.togethertunes.library;

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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /api/items/...} over HTTP ({@link ItemsApi}): JSON shape, PATCH body rules, statuses. */
class ItemsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String aliceId;
    private String alice;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        TestUploads.itemsApi(db, dir, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        // the first account gets the admin role
        String adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
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

    @Test
    void getShowsTheItemToAnyone() throws Exception {
        String id = item("video");
        HttpResponse<String> r = call("GET", "/api/items/" + id, null, null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        JsonObject page = json(r);
        assertEquals(JsonParser.parseString("{\"id\":\"" + id + "\",\"kind\":\"video\",\"title\":\"Song\","
                + "\"artist\":\"Artist\",\"source\":\"upload\",\"status\":\"ready\",\"presentAsAudio\":false,"
                + "\"addedAt\":3,\"mediaVersion\":0,\"you\":{\"inLibrary\":false,\"canEdit\":false,\"canPurge\":false}}"), page);
        JsonObject mine = json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("you");
        assertTrue(mine.get("inLibrary").getAsBoolean() && mine.get("canEdit").getAsBoolean());
        assertFalse(mine.get("canPurge").getAsBoolean());
        JsonObject admins = json(call("GET", "/api/items/" + id, admin, null)).getAsJsonObject("you");
        assertTrue(admins.get("canEdit").getAsBoolean() && admins.get("canPurge").getAsBoolean());
        HttpResponse<String> missing = call("GET", "/api/items/" + UuidV7.newId(), null, null);
        assertEquals(404, missing.statusCode());
        assertEquals("item_not_found", code(missing));
        HttpResponse<String> noJob = call("GET", "/api/items/" + id + "/job", null, null);
        assertEquals(404, noJob.statusCode());
        assertEquals("job_not_found", code(noJob));
    }

    @Test
    void patchEditsAndValidates() throws Exception {
        String id = item("video");
        HttpResponse<String> r = call("PATCH", "/api/items/" + id, alice,
                "{\"title\":\"  New\\t Title \",\"artist\":null,\"album\":\"An Album\",\"presentAsAudio\":true}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject page = json(r);
        assertEquals("New Title", page.get("title").getAsString());
        assertFalse(page.has("artist"));
        assertEquals("An Album", page.get("album").getAsString());
        assertTrue(page.get("presentAsAudio").getAsBoolean());
        assertEquals(page, json(call("PATCH", "/api/items/" + id, alice, "{}")), "an empty edit changes nothing");
        assertEquals(page, json(call("GET", "/api/items/" + id, alice, null)));
        // blank artist clears too
        assertEquals(200, call("PATCH", "/api/items/" + id, alice, "{\"artist\":\"x\"}").statusCode());
        assertFalse(json(call("PATCH", "/api/items/" + id, alice, "{\"artist\":\"   \"}")).has("artist"));

        Map<String, String> bad = Map.of(
                "{\"title\":\"  \"}", "invalid_title",
                "{\"title\":null}", "invalid_title",
                "{\"title\":5}", "invalid_title",
                "{\"artist\":[\"a\"]}", "invalid_artist",
                "{\"album\":{}}", "invalid_album",
                "{\"presentAsAudio\":\"yes\"}", "invalid_present_as_audio",
                "{\"title\":\"" + "x".repeat(UploadMetadata.MAX_CHARS + 1) + "\"}", "metadata_too_long",
                "{\"cover\":\"x\"}", "unknown_field");
        for (Map.Entry<String, String> e : bad.entrySet()) {
            HttpResponse<String> refused = call("PATCH", "/api/items/" + id, alice, e.getKey());
            assertEquals(400, refused.statusCode(), e.getKey());
            assertEquals(e.getValue(), code(refused), e.getKey());
        }
        assertEquals(200, call("PATCH", "/api/items/" + id, alice,
                "{\"title\":\"" + "é".repeat(UploadMetadata.MAX_CHARS) + "\"}").statusCode());

        String audio = item("audio");
        HttpResponse<String> notVideo = call("PATCH", "/api/items/" + audio, alice, "{\"presentAsAudio\":true}");
        assertEquals(400, notVideo.statusCode());
        assertEquals("not_a_video", code(notVideo));

        HttpResponse<String> anonymous = call("PATCH", "/api/items/" + id, null, "{\"title\":\"x\"}");
        assertEquals(401, anonymous.statusCode());
        assertEquals("not_authenticated", code(anonymous));
        assertEquals(200, call("PATCH", "/api/items/" + id, admin, "{\"title\":\"By Admin\"}").statusCode());
        HttpResponse<String> missing = call("PATCH", "/api/items/" + UuidV7.newId(), admin, "{\"title\":\"x\"}");
        assertEquals(404, missing.statusCode());
    }

    @Test
    void deletePurges() throws Exception {
        String id = item("audio");
        Path itemDir = dir.resolve("data").resolve("media").resolve(id);
        Files.createDirectories(itemDir);
        Files.writeString(itemDir.resolve("audio.m4a"), "a");
        HttpResponse<String> refused = call("DELETE", "/api/items/" + id, alice, null);
        assertEquals(403, refused.statusCode());
        assertEquals(401, call("DELETE", "/api/items/" + id, null, null).statusCode());
        assertEquals(204, call("DELETE", "/api/items/" + id, admin, null).statusCode());
        assertFalse(Files.exists(itemDir));
        assertEquals("purged", json(call("GET", "/api/items/" + id, null, null)).get("status").getAsString());
        assertEquals(204, call("DELETE", "/api/items/" + id, admin, null).statusCode(), "again: still purged");
        assertEquals(404, call("DELETE", "/api/items/" + UuidV7.newId(), admin, null).statusCode());
        assertEquals(404, call("DELETE", "/api/items/.hidden", admin, null).statusCode(), "not a server error");
    }

    @Test
    void parseKeepsAbsentFieldsUnchanged() {
        assertEquals(new Items.Edit(null, null, false, null, false, null), ItemsApi.parse(new JsonObject()));
        assertEquals(new Items.Edit(null, null, true, null, true, null),
                ItemsApi.parse(JsonParser.parseString("{\"artist\":null,\"album\":\"\"}").getAsJsonObject()));
        HttpError e = org.junit.jupiter.api.Assertions.assertThrows(HttpError.class,
                () -> ItemsApi.parse(JsonParser.parseString("{\"presentAsAudio\":null}").getAsJsonObject()));
        assertEquals("invalid_present_as_audio", e.code());
    }
}
