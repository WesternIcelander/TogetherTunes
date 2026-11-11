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
import java.util.List;
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
        YoutubeImports youtube = TestUploads.youtube(db, config, io.siggi.togethertunes.ingest.Tools.from(config), clock);
        youtube.diskSpace(new io.siggi.togethertunes.storage.DiskSpace(
                () -> new io.siggi.togethertunes.storage.DiskSpace.Usage(free.get(), 100L << 30), () -> 5L << 30,
                java.time.Duration.ZERO, System::nanoTime));
        data = io.siggi.togethertunes.DataDir.create(dir.resolve("data"));
        Items items = new Items(db, data, clock);
        MediaVersions versions = new MediaVersions(data, db, clock::millis, java.time.Duration.ofMinutes(5));
        versions.undoWindow(() -> 48 * 3_600_000L);
        items.mediaVersions(versions);
        items.trimJobs(jobs);
        new ItemsApi(items, new Permissions(db, settings), youtube).register(router);
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

    @Test
    void searchTheWholeServer() throws Exception {
        String song = item("audio");
        String purged = item("audio");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged' WHERE id = ?")) {
                ps.setString(1, purged);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(401, call("GET", "/api/items/search", null, null).statusCode());
        HttpResponse<String> r = call("GET", "/api/items/search?q=song", bob, null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        // alice's item, found by bob: item metadata and bob's own library flag, nothing about alice
        assertEquals(JsonParser.parseString("{\"items\":[{\"item\":{\"id\":\"" + song + "\",\"kind\":\"audio\","
                + "\"title\":\"Song\",\"artist\":\"Artist\",\"source\":\"upload\",\"status\":\"ready\","
                + "\"presentAsAudio\":false,\"mediaType\":\"audio\"},\"inYourLibrary\":false}]}"), json(r));
        assertFalse(r.body().contains(aliceId));
        assertTrue(json(call("GET", "/api/items/search", alice, null)).getAsJsonArray("items").get(0)
                .getAsJsonObject().get("inYourLibrary").getAsBoolean());
        // Add to Library from a result, then it shows as in bob's library
        assertEquals(201, call("PUT", "/api/library/" + song, bob, null).statusCode());
        assertTrue(json(call("GET", "/api/items/search?q=song", bob, null)).getAsJsonArray("items").get(0)
                .getAsJsonObject().get("inYourLibrary").getAsBoolean());
        assertEquals(400, call("GET", "/api/items/search?limit=0", bob, null).statusCode());
        assertEquals("invalid_kind", code(call("GET", "/api/items/search?kind=x", bob, null)));
        assertEquals("invalid_type", code(call("GET", "/api/items/search?type=x", bob, null)));
        assertEquals("invalid_type", code(call("GET", "/api/items/search?type=movie,", bob, null)));
        assertEquals(0, json(call("GET", "/api/items/search?type=movie,show", bob, null)).getAsJsonArray("items").size());
        assertEquals(song, json(call("GET", "/api/items/search?type=audio", bob, null)).getAsJsonArray("items").get(0)
                .getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertEquals("invalid_cursor", code(call("GET", "/api/items/search?cursor=x", bob, null)));
        // a page of 1 has a cursor to the next (nothing; the purged one isn't listed)
        JsonObject first = json(call("GET", "/api/items/search?limit=1", bob, null));
        assertFalse(first.has("nextCursor"), first.toString());
    }

    private Object[] pinRow(String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT pinned_at, pinned_by_user_id FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    long at = rs.getLong(1);
                    return new Object[] {rs.wasNull() ? null : at, rs.getString(2)};
                }
            }
        });
    }

    private int audits(String action, String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?")) {
                ps.setString(1, action);
                ps.setString(2, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /** Pin and unpin: admins always, others with library.pin; shown on the item; idempotent; audited. */
    @Test
    void pinRights() throws Exception {
        String id = item("audio");
        // alice added it and may edit it, but pinning needs library.pin (not in the default user role)
        assertEquals(403, call("PUT", "/api/items/" + id + "/pin", alice, null).statusCode());
        assertEquals(403, call("DELETE", "/api/items/" + id + "/pin", alice, null).statusCode());
        HttpResponse<String> anon = call("PUT", "/api/items/" + id + "/pin", null, null);
        assertEquals(401, anon.statusCode());
        JsonObject page = json(call("GET", "/api/items/" + id, alice, null));
        assertFalse(page.getAsJsonObject("you").get("canPin").getAsBoolean());
        assertFalse(page.has("pinned"));
        assertTrue(json(call("GET", "/api/items/" + id, admin, null)).getAsJsonObject("you").get("canPin").getAsBoolean());

        // the admin pins it: shown on the item for everyone (not who pinned it)
        clock.advance(5_000);
        long at = clock.millis();
        HttpResponse<String> pinned = call("PUT", "/api/items/" + id + "/pin", admin, null);
        assertEquals(200, pinned.statusCode(), pinned.body());
        assertEquals(at, json(pinned).getAsJsonObject("pinned").get("pinnedAt").getAsLong());
        JsonObject seen = json(call("GET", "/api/items/" + id, null, null));
        assertEquals(at, seen.getAsJsonObject("pinned").get("pinnedAt").getAsLong());
        assertEquals(java.util.Set.of("pinnedAt"), seen.getAsJsonObject("pinned").keySet());
        assertFalse(seen.getAsJsonObject("you").get("canPin").getAsBoolean());
        assertEquals(1, audits(Items.AUDIT_PINNED, id));
        // pinning again changes nothing (the first pin's time stays, no second audit row)
        clock.advance(5_000);
        assertEquals(at, json(call("PUT", "/api/items/" + id + "/pin", admin, null))
                .getAsJsonObject("pinned").get("pinnedAt").getAsLong());
        assertEquals(1, audits(Items.AUDIT_PINNED, id));

        // a user given library.pin unpins and pins it
        io.siggi.togethertunes.auth.Roles roles = new io.siggi.togethertunes.auth.Roles(db);
        io.siggi.togethertunes.auth.Roles.Role pinner = roles.create("Pinner",
                java.util.List.of(io.siggi.togethertunes.auth.Permission.LIBRARY_PIN), false);
        roles.setUserRoles(bobId, java.util.List.of(pinner.id()));
        HttpResponse<String> unpinned = call("DELETE", "/api/items/" + id + "/pin", bob, null);
        assertEquals(200, unpinned.statusCode(), unpinned.body());
        assertFalse(json(unpinned).has("pinned"));
        assertTrue(json(unpinned).getAsJsonObject("you").get("canPin").getAsBoolean());
        assertArrayEquals(new Object[] {null, null}, pinRow(id));
        assertEquals(1, audits(Items.AUDIT_UNPINNED, id));
        assertEquals(200, call("DELETE", "/api/items/" + id + "/pin", bob, null).statusCode());
        assertEquals(1, audits(Items.AUDIT_UNPINNED, id));
        assertEquals(200, call("PUT", "/api/items/" + id + "/pin", bob, null).statusCode());
        assertArrayEquals(new Object[] {clock.millis(), bobId}, pinRow(id));

        assertEquals("item_not_found", code(call("PUT", "/api/items/" + UuidV7.newId() + "/pin", admin, null)));
        assertEquals("item_not_found", code(call("DELETE", "/api/items/not-an-id/pin", bob, null)));
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
                + "\"mediaType\":\"uncategorized\",\"addedAt\":3,\"mediaVersion\":0,\"hasCover\":false,\"you\":{\"inLibrary\":false,\"canEdit\":false,\"canPurge\":false,"
                + "\"canRetry\":false,\"canPin\":false}}"), page);
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
                "{\"title\":\"  New\\t Title \",\"artist\":null,\"album\":\"An Album\",\"mediaType\":\"audio\"}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject page = json(r);
        assertEquals("New Title", page.get("title").getAsString());
        assertFalse(page.has("artist"));
        assertEquals("An Album", page.get("album").getAsString());
        assertTrue(page.get("presentAsAudio").getAsBoolean(), "derived from the type");
        assertEquals("audio", page.get("mediaType").getAsString());
        assertEquals(page, json(call("PATCH", "/api/items/" + id, alice, "{}")), "an empty edit changes nothing");
        assertEquals(page, json(call("GET", "/api/items/" + id, alice, null)));
        // blank artist clears too
        assertEquals(200, call("PATCH", "/api/items/" + id, alice, "{\"artist\":\"x\"}").statusCode());
        assertFalse(json(call("PATCH", "/api/items/" + id, alice, "{\"artist\":\"   \"}")).has("artist"));

        // a show episode
        JsonObject show = json(call("PATCH", "/api/items/" + id, alice,
                "{\"mediaType\":\"show\",\"show\":{\"name\":\" The  Show \",\"season\":1,\"episode\":0}}"));
        assertEquals("show", show.get("mediaType").getAsString());
        assertFalse(show.get("presentAsAudio").getAsBoolean());
        assertEquals(JsonParser.parseString("{\"name\":\"The Show\",\"season\":1,\"episode\":0}"), show.get("show"));
        JsonObject noSeason = json(call("PATCH", "/api/items/" + id, alice,
                "{\"show\":{\"name\":\"The Show\",\"season\":null,\"episode\":3}}"));
        assertEquals(JsonParser.parseString("{\"name\":\"The Show\",\"episode\":3}"), noSeason.get("show"));
        JsonObject movie = json(call("PATCH", "/api/items/" + id, alice, "{\"mediaType\":\"movie\"}"));
        assertEquals("movie", movie.get("mediaType").getAsString());
        assertFalse(movie.has("show"), "cleared with the type");
        assertEquals("show_required", code(call("PATCH", "/api/items/" + id, alice, "{\"mediaType\":\"show\"}")));
        assertEquals("not_a_show", code(call("PATCH", "/api/items/" + id, alice,
                "{\"show\":{\"name\":\"S\",\"episode\":1}}")));

        Map<String, String> bad = new java.util.HashMap<>(Map.of(
                "{\"title\":\"  \"}", "invalid_title",
                "{\"title\":null}", "invalid_title",
                "{\"title\":5}", "invalid_title",
                "{\"artist\":[\"a\"]}", "invalid_artist",
                "{\"album\":{}}", "invalid_album",
                "{\"mediaType\":\"song\"}", "invalid_media_type",
                "{\"mediaType\":null}", "invalid_media_type",
                "{\"title\":\"" + "x".repeat(UploadMetadata.MAX_CHARS + 1) + "\"}", "metadata_too_long",
                "{\"cover\":\"x\"}", "unknown_field",
                "{\"presentAsAudio\":true}", "unknown_field"));
        bad.putAll(Map.of(
                "{\"mediaType\":\"show\",\"show\":null}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"episode\":1}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\" \",\"episode\":1}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\"}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"episode\":-1}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"episode\":1.5}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"season\":-2,\"episode\":1}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"episode\":\"1\"}}", "invalid_show",
                "{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"episode\":1,\"x\":1}}", "unknown_field"));
        for (Map.Entry<String, String> e : bad.entrySet()) {
            HttpResponse<String> refused = call("PATCH", "/api/items/" + id, alice, e.getKey());
            assertEquals(400, refused.statusCode(), e.getKey());
            assertEquals(e.getValue(), code(refused), e.getKey());
        }
        assertEquals(200, call("PATCH", "/api/items/" + id, alice,
                "{\"title\":\"" + "é".repeat(UploadMetadata.MAX_CHARS) + "\"}").statusCode());

        String audio = item("audio");
        HttpResponse<String> notVideo = call("PATCH", "/api/items/" + audio, alice, "{\"mediaType\":\"movie\"}");
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
        assertEquals(new Items.Edit(null, null, false, null, false), ItemsApi.parse(new JsonObject()));
        assertEquals(new Items.Edit(null, null, true, null, true),
                ItemsApi.parse(JsonParser.parseString("{\"artist\":null,\"album\":\"\"}").getAsJsonObject()));
        HttpError e = org.junit.jupiter.api.Assertions.assertThrows(HttpError.class,
                () -> ItemsApi.parse(JsonParser.parseString("{\"mediaType\":5}").getAsJsonObject()));
        assertEquals("invalid_media_type", e.code());
        assertEquals(new Items.Edit(null, null, false, null, false, MediaType.SHOW, new MediaType.Show("S", null, 2)),
                ItemsApi.parse(JsonParser.parseString("{\"mediaType\":\"show\",\"show\":{\"name\":\"S\",\"episode\":2}}")
                        .getAsJsonObject()));
    }

    /** A YouTube item alice added (in her library) whose download job failed. */
    private String failedYoutubeItem() throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source, youtube_id,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'video', 'Clip', 'youtube', ?, ?, 3, 'pending', '720p30', 1)")) {
                ps.setString(1, id);
                String hex = id.replace("-", ""); ps.setString(2, hex.substring(hex.length() - 11));
                ps.setString(3, aliceId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 3, 'youtube')")) {
                ps.setString(1, aliceId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        jobs.enqueue(id, io.siggi.togethertunes.ingest.IngestJob.Kind.YOUTUBE);
        failRunning(id);
        return id;
    }

    /** Claims the item's queued job and fails it, as a download that went wrong. */
    private void failRunning(String itemId) throws Exception {
        io.siggi.togethertunes.ingest.IngestJob job = jobs.claimNext();
        assertEquals(itemId, job.itemId());
        assertTrue(jobs.fail(job.id(), "HTTP Error 429: Too Many Requests", "failed"));
    }

    private int audits(String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'item.ingest_retried' AND target = ?")) {
                ps.setString(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void retryFetchesAFailedYoutubeItemAgain() throws Exception {
        String id = failedYoutubeItem();
        String path = "/api/items/" + id + "/retry";
        assertTrue(json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean(), "the adder, while it is in her library");
        assertFalse(json(call("GET", "/api/items/" + id, bob, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean());
        assertFalse(json(call("GET", "/api/items/" + id, null, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean());

        // refused: anonymous (and guests), another user
        HttpResponse<String> anonymous = call("POST", path, null, null);
        assertEquals(401, anonymous.statusCode());
        assertEquals("not_authenticated", code(anonymous));
        HttpResponse<String> other = call("POST", path, bob, null);
        assertEquals(403, other.statusCode(), other.body());
        assertEquals("forbidden", code(other));
        assertEquals(0, audits(id));

        // the adder retries: a new job, the item pending again, audited
        HttpResponse<String> r = call("POST", path, alice, null);
        assertEquals(202, r.statusCode(), r.body());
        JsonObject retried = json(r);
        assertEquals(id, retried.get("itemId").getAsString());
        assertEquals("pending", retried.get("status").getAsString());
        assertEquals("pending", json(call("GET", "/api/items/" + id, null, null)).get("status").getAsString());
        io.siggi.togethertunes.ingest.IngestJob job = jobs.latestForItem(id).orElseThrow();
        assertEquals(retried.get("jobId").getAsString(), job.id());
        assertEquals(io.siggi.togethertunes.ingest.IngestJob.Kind.YOUTUBE, job.kind());
        assertEquals(1, audits(id));
        assertFalse(json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean(), "not while it is fetched");

        // a second click while queued, and while running → 409
        HttpResponse<String> again = call("POST", path, alice, null);
        assertEquals(409, again.statusCode());
        assertEquals("ingest_in_progress", code(again));
        io.siggi.togethertunes.ingest.IngestJob running = jobs.claimNext();
        assertEquals("ingest_in_progress", code(call("POST", path, admin, null)));

        // it fails again at once: the rate limit holds the next retry for 30 s
        assertTrue(jobs.fail(running.id(), "still throttled", "failed"));
        HttpResponse<String> soon = call("POST", path, alice, null);
        assertEquals(429, soon.statusCode(), soon.body());
        assertEquals("retry_too_soon", code(soon));
        assertTrue(soon.headers().firstValue("Retry-After").isPresent());
        clock.advance(29_000);
        assertEquals("retry_too_soon", code(call("POST", path, admin, null)), "per item, whoever asks");
        clock.advance(1_000);

        // ytdlp.enabled off → refused for everyone
        settings.set(Settings.YTDLP_ENABLED, false);
        HttpResponse<String> off = call("POST", path, admin, null);
        assertEquals(403, off.statusCode());
        assertEquals("feature_disabled", code(off));
        assertFalse(json(call("GET", "/api/items/" + id, admin, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean());
        settings.set(Settings.YTDLP_ENABLED, true);

        // below the free-space minimum → 507
        free.set(1L << 30);
        HttpResponse<String> full = call("POST", path, admin, null);
        assertEquals(507, full.statusCode());
        assertEquals("insufficient_storage", code(full));
        free.set(50L << 30);

        // an administrator (admin.jobs) retries it too
        assertEquals(202, call("POST", path, admin, null).statusCode());
        assertEquals(2, audits(id));
        failRunning(id);

        // out of alice's library she may no longer edit it, so no retry either
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM library_entries WHERE item_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        clock.advance(30_000);
        assertEquals("forbidden", code(call("POST", path, alice, null)));
    }

    @Test
    void retryIsOnlyForFailedYoutubeItems() throws Exception {
        String upload = item("audio");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'failed', error = 'x' WHERE id = ?")) {
                ps.setString(1, upload);
                ps.executeUpdate();
            }
            return null;
        });
        HttpResponse<String> notYoutube = call("POST", "/api/items/" + upload + "/retry", alice, null);
        assertEquals(409, notYoutube.statusCode());
        assertEquals("not_youtube", code(notYoutube));
        assertFalse(json(call("GET", "/api/items/" + upload, alice, null)).getAsJsonObject("you").get("canRetry")
                .getAsBoolean(), "a failed upload's file isn't kept");

        String ready = failedYoutubeItem();
        assertEquals(202, call("POST", "/api/items/" + ready + "/retry", alice, null).statusCode());
        io.siggi.togethertunes.ingest.IngestJob job = jobs.claimNext();
        assertTrue(jobs.complete(job.id(), "done"));
        clock.advance(60_000);
        assertEquals("item_not_failed", code(call("POST", "/api/items/" + ready + "/retry", alice, null)));

        String purged = failedYoutubeItem();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged' WHERE id = ?")) {
                ps.setString(1, purged);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals("item_purged", code(call("POST", "/api/items/" + purged + "/retry", admin, null)));
        HttpResponse<String> missing = call("POST", "/api/items/" + UuidV7.newId() + "/retry", admin, null);
        assertEquals(404, missing.statusCode());
        assertEquals("item_not_found", code(missing));
    }

    /** {@code POST /api/items/{id}/undo}: who may, the page's {@code undo}, and the switch back. */
    @Test
    void undoGoesBackToThePreviousVersion() throws Exception {
        String id = item("audio");
        IngestItems ingestItems = new IngestItems(db);
        for (int v = 1; v <= 2; v++) {
            Path f = MediaVersions.versionDir(data, id, v).resolve(IngestPipeline.AUDIO);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "v" + v);
            ingestItems.published(id, false, 1000L * v, io.siggi.togethertunes.config.OutputEncoding.DEFAULT,
                    io.siggi.togethertunes.config.X264Preset.VERYFAST, true, null, null, v,
                    new IngestItems.VersionChange(v == 1 ? null : 1, true, null, clock.millis()));
        }
        String path = "/api/items/" + id + "/undo";
        JsonObject page = json(call("GET", "/api/items/" + id, alice, null));
        assertEquals(clock.millis(), page.getAsJsonObject("undo").get("changedAt").getAsLong());
        assertFalse(json(call("GET", "/api/items/" + id, bob, null)).has("undo"));
        assertFalse(json(call("GET", "/api/items/" + id, null, null)).has("undo"));

        assertEquals(401, call("POST", path, null, null).statusCode());
        assertEquals(403, call("POST", path, bob, null).statusCode());
        HttpResponse<String> missing = call("POST", "/api/items/" + UuidV7.newId() + "/undo", alice, null);
        assertEquals("item_not_found", code(missing));
        HttpResponse<String> r = call("POST", path, alice, null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(3, json(r).get("mediaVersion").getAsInt());
        assertEquals(1000, json(r).get("durationMs").getAsLong());
        assertTrue(json(r).has("undo"), "the undo can be undone");
        assertEquals(200, call("POST", path, admin, null).statusCode(), "library.editAny");

        String fresh = item("audio");
        HttpResponse<String> nothing = call("POST", "/api/items/" + fresh + "/undo", alice, null);
        assertEquals(409, nothing.statusCode());
        assertEquals("nothing_to_undo", code(nothing));
    }

    /** A ready video item of alice's with a kept original 10 s long. */
    private String trimmableVideo() throws Exception {
        String id = item("video");
        Path original = IngestPipeline.itemDir(data, id).resolve("original.mp4");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "x");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET original_file = 'original.mp4',"
                    + " duration_ms = 10000 WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String jobEditJson(String jobId) throws Exception {
        return jobs.get(jobId).orElseThrow().editJson();
    }

    /** {@code POST /api/items/{id}/trim}: who may, the body rules, the page's {@code trim}, and the job. */
    @Test
    void trimQueuesATrimJob() throws Exception {
        String id = trimmableVideo();
        String path = "/api/items/" + id + "/trim";
        JsonObject trim = json(call("GET", "/api/items/" + id, alice, null)).getAsJsonObject("trim");
        assertEquals(0, trim.get("videoStartMs").getAsLong());
        assertFalse(trim.has("videoEndMs"));
        assertEquals(10000, trim.get("originalMs").getAsLong());
        assertFalse(json(call("GET", "/api/items/" + id, bob, null)).has("trim"));
        assertFalse(json(call("GET", "/api/items/" + id, null, null)).has("trim"));

        String body = "{\"videoStartMs\": 2000, \"videoEndMs\": 8000}";
        assertEquals(401, call("POST", path, null, body).statusCode());
        assertEquals(403, call("POST", path, bob, body).statusCode());
        assertEquals("item_not_found", code(call("POST", "/api/items/" + UuidV7.newId() + "/trim", alice, body)));
        for (String bad : List.of("{}", "{\"videoStartMs\": -1}", "{\"videoStartMs\": \"1\"}",
                "{\"videoStartMs\": 1.5}", "{\"videoStartMs\": 5000, \"videoEndMs\": 5000}",
                "{\"videoStartMs\": 5000, \"videoEndMs\": 5999}", "{\"videoStartMs\": 9500}",
                "{\"videoStartMs\": 0, \"videoEndMs\": \"x\"}")) {
            HttpResponse<String> r = call("POST", path, alice, bad);
            assertEquals(400, r.statusCode(), bad);
            assertEquals("invalid_trim", code(r), bad);
        }
        assertEquals("unknown_field", code(call("POST", path, alice, "{\"videoStartMs\": 0, \"foo\": 1}")));
        assertEquals("no_change", code(call("POST", path, alice, "{\"videoStartMs\": 0}")));
        assertEquals("no_change", code(call("POST", path, alice, "{\"videoStartMs\": 0, \"videoEndMs\": 12000}")),
                "an end at or after the original's end is no end");

        HttpResponse<String> r = call("POST", path, alice, body);
        assertEquals(202, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        io.siggi.togethertunes.ingest.IngestJob job = jobs.latestForItem(id).orElseThrow();
        assertEquals(io.siggi.togethertunes.ingest.IngestJob.Kind.TRIM, job.kind());
        assertEquals("{\"videoStartMs\":2000,\"videoEndMs\":8000,\"parts\":[\"video\"]}", jobEditJson(job.id()));
        assertEquals("ready", json(r).get("status").getAsString(), "the item keeps playing meanwhile");
        // a second trim while the first is queued: the latest wins, on the same job
        HttpResponse<String> second = call("POST", path, admin, "{\"videoStartMs\": 4000, \"videoEndMs\": null}");
        assertEquals(202, second.statusCode(), second.body() + " " +
                "library.editAny");
        assertEquals(job.id(), jobs.latestForItem(id).orElseThrow().id());
        assertEquals("{\"videoStartMs\":4000,\"parts\":[\"video\"]}", jobEditJson(job.id()));
        assertEquals(Integer.valueOf(2), db.<Integer>read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'item.trimmed' AND target = ?")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }));
        // running: busy
        assertEquals(job.id(), jobs.claimNext().id());
        HttpResponse<String> busy = call("POST", path, alice, body);
        assertEquals(409, busy.statusCode());
        assertEquals("item_busy", code(busy));

        // a queued quality job gives way to the trim
        String other = trimmableVideo();
        io.siggi.togethertunes.ingest.IngestJob quality = jobs.enqueue(other,
                io.siggi.togethertunes.ingest.IngestJob.Kind.QUALITY);
        assertEquals(202, call("POST", "/api/items/" + other + "/trim", alice, body).statusCode());
        assertEquals(io.siggi.togethertunes.ingest.IngestJob.State.DONE, jobs.get(quality.id()).orElseThrow().state());
        assertEquals(io.siggi.togethertunes.ingest.IngestJob.Kind.TRIM, jobs.latestForItem(other).orElseThrow().kind());

        // what can't be trimmed
        String audio = item("audio");
        assertEquals("not_a_video", code(call("POST", "/api/items/" + audio + "/trim", alice, body)));
        String noOriginal = item("video");
        assertEquals("no_original", code(call("POST", "/api/items/" + noOriginal + "/trim", alice, body)));
        assertFalse(json(call("GET", "/api/items/" + noOriginal, alice, null)).has("trim"));
        String processing = trimmableVideo();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'processing' WHERE id = ?")) {
                ps.setString(1, processing);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals("item_not_ready", code(call("POST", "/api/items/" + processing + "/trim", alice, body)));
    }

    /** A ready audio item of alice's with a kept original 10 s long. */
    private String trimmableAudio() throws Exception {
        String id = trimmableVideo();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = 'audio' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /**
     * The audio trim through {@code POST /api/items/{id}/trim} ({@code docs/library-and-ingest.md#audio-trim}):
     * an audio item's, a video's for audio-only rooms (with its video trim on the same queued job),
     * removing it, and the body rules.
     */
    @Test
    void audioTrimQueuesATrimJob() throws Exception {
        String audio = trimmableAudio();
        String path = "/api/items/" + audio + "/trim";
        JsonObject trim = json(call("GET", "/api/items/" + audio, alice, null)).getAsJsonObject("trim");
        assertEquals(10000, trim.get("originalMs").getAsLong());
        assertFalse(trim.has("audioStartMs"));
        assertEquals("not_a_video", code(call("POST", path, alice, "{\"videoStartMs\": 1000}")));
        for (String bad : List.of("{\"audioEndMs\": 5000}", "{\"audioStartMs\": -1}", "{\"audioStartMs\": 9500}",
                "{\"audioStartMs\": 5000, \"audioEndMs\": 5500}", "{\"audioStartMs\": null, \"audioEndMs\": 5000}")) {
            HttpResponse<String> r = call("POST", path, alice, bad);
            assertEquals(400, r.statusCode(), bad);
            assertEquals("invalid_trim", code(r), bad);
        }
        assertEquals("no_change", code(call("POST", path, alice, "{\"audioStartMs\": null}")));
        assertEquals("no_change", code(call("POST", path, alice, "{\"audioStartMs\": 0, \"audioEndMs\": null}")),
                "an audio item's whole original is no trim");
        assertEquals(403, call("POST", path, bob, "{\"audioStartMs\": 1000}").statusCode());
        HttpResponse<String> r = call("POST", path, alice, "{\"audioStartMs\": 1000, \"audioEndMs\": 7000}");
        assertEquals(202, r.statusCode(), r.body());
        io.siggi.togethertunes.ingest.IngestJob job = jobs.latestForItem(audio).orElseThrow();
        assertEquals(io.siggi.togethertunes.ingest.IngestJob.Kind.TRIM, job.kind());
        assertEquals("{\"audioStartMs\":1000,\"audioEndMs\":7000,\"parts\":[\"audio\"]}", jobEditJson(job.id()));
        assertEquals(Integer.valueOf(1), db.<Integer>read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = 'item.trimmed'"
                    + " AND target = ? AND details_json LIKE '%audioStartMs%'")) {
                ps.setString(1, audio);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }));

        // a video: the audio-only rooms' range, then the video trim on the same queued job
        String video = trimmableVideo();
        String vpath = "/api/items/" + video + "/trim";
        assertEquals("no_change", code(call("POST", vpath, alice, "{\"audioStartMs\": null}")),
                "a video without an audio trim follows its video trim already");
        assertEquals(202, call("POST", vpath, alice, "{\"audioStartMs\": 0}").statusCode(),
                "the whole original is an audio trim of a video");
        String jobId = jobs.latestForItem(video).orElseThrow().id();
        assertEquals("{\"audioStartMs\":0,\"parts\":[\"audio\"]}", jobEditJson(jobId));
        assertEquals(202, call("POST", vpath, alice, "{\"videoStartMs\": 2000}").statusCode());
        assertEquals(jobId, jobs.latestForItem(video).orElseThrow().id());
        assertEquals("{\"videoStartMs\":2000,\"audioStartMs\":0,\"parts\":[\"video\",\"audio\"]}", jobEditJson(jobId));
        assertEquals(202, call("POST", vpath, alice, "{\"audioStartMs\": 3000, \"audioEndMs\": 9000}").statusCode());
        assertEquals("{\"videoStartMs\":2000,\"audioStartMs\":3000,\"audioEndMs\":9000,\"parts\":[\"video\",\"audio\"]}",
                jobEditJson(jobId));
    }
}
