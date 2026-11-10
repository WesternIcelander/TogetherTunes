package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
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
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The encode jobs API over HTTP ({@link JobsApi}): who sees which jobs, the JSON shape, retry and
 * cancel with their audit rows, and the refusals.
 */
class JobsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final AtomicBoolean paused = new AtomicBoolean();
    private IngestJobs jobs;
    private String adminId;
    private String admin;
    private String aliceId;
    private String alice;
    private String bobId;
    private String bob;
    private String guestCookie;
    private final java.util.Map<String, IngestQueue.LiveStep> liveSteps = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        jobs = new IngestJobs(db, clock);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        JobList list = new JobList(db, jobs, clock);
        list.steps(jobId -> java.util.Optional.ofNullable(liveSteps.get(jobId)));
        new JobsApi(jobs, list, new Permissions(db, settings), paused::get, clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        // the first account gets the admin role
        adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        guestCookie = new Guests(db, settings, false).create(GuestIdentity.COOKIE, "127.0.0.1", null).cookieToken();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> asGuest(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Cookie", "gid=" + guestCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject ok(int status, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    /** An item added by {@code ownerId} and in their library ({@code via}); returns its id. */
    private String item(String title, String ownerId, String via, String status) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'video', ?, 'Artist', 'upload', ?, 3, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, ownerId);
                ps.setString(4, status);
                ps.executeUpdate();
            }
            if (via != null) {
                library(id, ownerId, via);
            }
            return null;
        });
        return id;
    }

    private void library(String itemId, String userId, String via) throws java.sql.SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 3, ?)")) {
                ps.setString(1, userId);
                ps.setString(2, itemId);
                ps.setString(3, via);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static List<String> titles(JsonObject page) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : page.getAsJsonArray("items")) {
            out.add(e.getAsJsonObject().getAsJsonObject("item").get("title").getAsString());
        }
        return out;
    }

    @Test
    void adminSeesEveryJobAUserOnlyTheirLibrarysItems() throws Exception {
        String a = item("Alice upload", aliceId, "upload", "pending");
        jobs.enqueue(a, Kind.UPLOAD);
        clock.advance(1);
        String b = item("Bob video", bobId, "youtube", "pending");
        jobs.enqueue(b, Kind.YOUTUBE);
        clock.advance(1);
        // Bob shared his item with Alice: it is in her library too
        String s = item("Shared", bobId, "upload", "ready");
        library(s, aliceId, "share");
        jobs.enqueue(s, Kind.QUALITY, IngestJob.Tier.QUALITY);
        clock.advance(1);
        // a guest's request: in nobody's library
        jobs.enqueue(item("Guest request", aliceId, null, "pending"), Kind.UPLOAD);
        jobs.claimNext();

        JsonObject all = ok(200, call("GET", "/api/admin/jobs", admin));
        assertEquals(List.of("Alice upload", "Bob video", "Guest request", "Shared"), titles(all));
        assertFalse(all.get("paused").getAsBoolean());
        assertFalse(all.has("nextCursor"));
        JsonObject running = all.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("running", running.get("state").getAsString());
        assertEquals("upload", running.get("kind").getAsString());
        assertEquals(1, running.get("attempts").getAsInt());
        assertFalse(running.has("position"));
        assertFalse(running.has("log"));
        assertFalse(running.has("step"));
        // a running job's live step, once it has named one
        liveSteps.put(running.get("id").getAsString(), new IngestQueue.LiveStep("download", 0.5));
        JsonObject stepped = ok(200, call("GET", "/api/admin/jobs", admin)).getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("download", stepped.get("step").getAsString());
        assertEquals(0.5, stepped.get("stepProgress").getAsDouble());
        JsonObject runningItem = running.getAsJsonObject("item");
        assertEquals(a, runningItem.get("id").getAsString());
        assertEquals("video", runningItem.get("kind").getAsString());
        assertEquals("Artist", runningItem.get("artist").getAsString());
        assertEquals("processing", runningItem.get("status").getAsString());
        assertFalse(runningItem.has("addedBy"));
        JsonObject quality = all.getAsJsonArray("items").get(3).getAsJsonObject();
        assertEquals("quality", quality.get("kind").getAsString());
        assertEquals("quality", quality.get("tier").getAsString());
        assertEquals(3, quality.get("position").getAsInt());

        // Alice: her upload and the item Bob shared with her (it is in her library); not Bob's own item
        JsonObject alices = ok(200, call("GET", "/api/jobs", alice));
        assertEquals(List.of("Alice upload", "Shared"), titles(alices));
        assertEquals(3, alices.getAsJsonArray("items").get(1).getAsJsonObject().get("position").getAsInt(),
                "the place in the whole queue");
        assertEquals(List.of("Bob video", "Shared"), titles(ok(200, call("GET", "/api/jobs", bob))));
        // Alice removes the shared item from her library: gone from her list
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM library_entries WHERE user_id = ? AND item_id = ?")) {
                ps.setString(1, aliceId);
                ps.setString(2, s);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(List.of("Alice upload"), titles(ok(200, call("GET", "/api/jobs", alice))));

        // paging and the low-disk flag
        paused.set(true);
        JsonObject first = ok(200, call("GET", "/api/admin/jobs?state=active&limit=2", admin));
        assertEquals(List.of("Alice upload", "Bob video"), titles(first));
        assertTrue(first.get("paused").getAsBoolean());
        assertEquals("2", first.get("nextCursor").getAsString());
        assertEquals(List.of("Guest request", "Shared"),
                titles(ok(200, call("GET", "/api/admin/jobs?limit=2&cursor=2", admin))));
        assertFalse(ok(200, call("GET", "/api/admin/jobs?state=failed", admin)).has("paused"));
    }

    @Test
    void refusals() throws Exception {
        // guests and anonymous callers: 401; users without admin.jobs: 403
        assertEquals(401, call("GET", "/api/jobs", null).statusCode());
        HttpResponse<String> guest = asGuest("/api/jobs");
        assertEquals(401, guest.statusCode(), guest.body());
        assertEquals("not_authenticated", code(guest));
        assertEquals(401, asGuest("/api/admin/jobs").statusCode());
        for (String[] route : new String[][] {{"GET", "/api/admin/jobs"}, {"GET", "/api/admin/jobs/x"},
                {"POST", "/api/admin/jobs/x/retry"}, {"POST", "/api/admin/jobs/x/cancel"}}) {
            HttpResponse<String> r = call(route[0], route[1], alice);
            assertEquals(403, r.statusCode(), route[1]);
            assertEquals("forbidden", code(r));
        }
        assertEquals("invalid_state", code(call("GET", "/api/admin/jobs?state=nope", admin)));
        assertEquals("invalid_cursor", code(call("GET", "/api/jobs?cursor=-1", alice)));
        assertEquals("invalid_cursor", code(call("GET", "/api/jobs?cursor=abc", alice)));
        assertEquals("invalid_limit", code(call("GET", "/api/jobs?limit=201", alice)));
        assertEquals("job_not_found", code(call("GET", "/api/admin/jobs/nope", admin)));
        assertEquals("job_not_found", code(call("POST", "/api/admin/jobs/nope/retry", admin)));
        assertEquals("job_not_found", code(call("POST", "/api/admin/jobs/nope/cancel", admin)));
    }

    @Test
    void retryAndCancelAreAudited() throws Exception {
        String a = item("Broken", aliceId, "upload", "pending");
        IngestJob job = jobs.enqueue(a, Kind.UPLOAD);
        assertEquals("job_not_failed", code(call("POST", "/api/admin/jobs/" + job.id() + "/retry", admin)));
        jobs.claimNext();
        assertEquals("job_not_queued", code(call("POST", "/api/admin/jobs/" + job.id() + "/cancel", admin)));
        jobs.fail(job.id(), "Unreadable media", "[ingest] something broke\n");

        JsonObject failed = ok(200, call("GET", "/api/admin/jobs?state=failed", admin));
        assertEquals(List.of("Broken"), titles(failed));
        JsonObject failedItem = failed.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("item");
        assertEquals("Unreadable media", failedItem.get("error").getAsString());
        // the same failure for Alice, without the log
        assertEquals(List.of("Broken"), titles(ok(200, call("GET", "/api/jobs?state=failed", alice))));
        assertTrue(ok(200, call("GET", "/api/jobs?state=failed", bob)).getAsJsonArray("items").isEmpty());
        JsonObject detail = ok(200, call("GET", "/api/admin/jobs/" + job.id(), admin));
        assertEquals("[ingest] something broke\n", detail.get("log").getAsString());

        JsonObject retried = ok(201, call("POST", "/api/admin/jobs/" + job.id() + "/retry", admin));
        assertEquals("queued", retried.get("state").getAsString());
        assertEquals("upload", retried.get("kind").getAsString());
        assertEquals(1, retried.get("position").getAsInt());
        assertEquals("pending", retried.getAsJsonObject("item").get("status").getAsString());
        assertEquals("job_superseded", code(call("POST", "/api/admin/jobs/" + job.id() + "/retry", admin)));
        assertTrue(ok(200, call("GET", "/api/admin/jobs?state=failed", admin)).getAsJsonArray("items").isEmpty());

        String newId = retried.get("id").getAsString();
        JsonObject cancelled = ok(200, call("POST", "/api/admin/jobs/" + newId + "/cancel", admin));
        assertEquals("failed", cancelled.get("state").getAsString());
        assertEquals("failed", cancelled.getAsJsonObject("item").get("status").getAsString());
        assertEquals(IngestJobs.CANCELLED_ERROR, cancelled.getAsJsonObject("item").get("error").getAsString());
        assertTrue(ok(200, call("GET", "/api/admin/jobs", admin)).getAsJsonArray("items").isEmpty());

        assertEquals(List.of(JobsApi.AUDIT_RETRIED + " " + job.id(), JobsApi.AUDIT_CANCELLED + " " + newId), audit());
        // done list: a successful retry shows there for 24 h
        JsonObject again = ok(201, call("POST", "/api/admin/jobs/" + newId + "/retry", admin));
        jobs.claimNext();
        jobs.complete(again.get("id").getAsString(), null);
        JsonArray done = ok(200, call("GET", "/api/admin/jobs?state=done", admin)).getAsJsonArray("items");
        assertEquals(1, done.size());
        assertEquals("ready", done.get(0).getAsJsonObject().getAsJsonObject("item").get("status").getAsString());
    }

    /** {@code "<action> <target>"} of every audit row, oldest first, all by the admin. */
    private List<String> audit() throws Exception {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT action, target, actor_user_id, details_json FROM audit_log WHERE action LIKE 'job.%' ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertEquals(adminId, rs.getString(3));
                        assertTrue(rs.getString(4).contains("\"kind\":\"upload\""), rs.getString(4));
                        out.add(rs.getString(1) + " " + rs.getString(2));
                    }
                }
            }
            return out;
        });
    }
}
