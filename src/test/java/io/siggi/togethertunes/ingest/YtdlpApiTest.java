package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admin's yt-dlp update over HTTP ({@link YtdlpApi}, {@link YtdlpUpdater}) with a fake yt-dlp:
 * version, a successful and a failed {@code -U}, one run at a time, the audit row, no yt-dlp, and
 * who may call it.
 */
class YtdlpApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    /** Background runs, started by the test with {@link #runQueued()}. */
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private Path versionFile;
    private String admin;
    private String adminId;
    private String alice;
    private String guestCookie;

    @BeforeAll
    static void unixOnly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separatorChar == '/', "needs /bin/sh");
    }

    /** A fake yt-dlp: {@code --version} prints the version file; {@code -U} runs {@code update}. */
    private Path fakeYtdlp(String update) throws Exception {
        versionFile = dir.resolve("version");
        Files.writeString(versionFile, "2026.03.17\n");
        Path p = dir.resolve("yt-dlp");
        Files.writeString(p, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then cat '" + versionFile + "'; exit 0; fi\n"
                + "if [ \"$1\" = \"-U\" ]; then\n" + update + "\nfi\nexit 2\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    private void start(String ytdlp) throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        YtdlpUpdater updater = new YtdlpUpdater(new Tools(null, null, ytdlp), clock, queued::add);
        new YtdlpApi(updater, db, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        guestCookie = new Guests(db, settings, false).create(GuestIdentity.COOKIE, "127.0.0.1", null).cookieToken();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        if (db != null) db.close();
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject ok(int status, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    @Test
    void updateRunsInTheBackgroundAndReportsTheNewVersion() throws Exception {
        start(fakeYtdlp("echo 'Current version: 2026.03.17'\necho 'Updating to 2026.11.01' >&2\n"
                + "printf '2026.11.01\\n' > '" + dir.resolve("version") + "'\necho 'Updated yt-dlp to 2026.11.01'\nexit 0")
                .toString());
        JsonObject info = ok(200, call("GET", "/api/admin/ytdlp", admin));
        assertTrue(info.get("available").getAsBoolean());
        assertEquals("2026.03.17", info.get("version").getAsString());
        assertFalse(info.has("update"));

        clock.advance(1000);
        JsonObject started = ok(202, call("POST", "/api/admin/ytdlp/update", admin));
        assertEquals("running", started.get("state").getAsString());
        assertEquals(clock.millis(), started.get("startedAt").getAsLong());
        assertEquals("2026.03.17", started.get("versionBefore").getAsString());
        assertFalse(started.has("finishedAt"));
        // one run at a time
        assertEquals(409, call("POST", "/api/admin/ytdlp/update", admin).statusCode());
        assertEquals("ytdlp_update_running", code(call("POST", "/api/admin/ytdlp/update", admin)));
        assertEquals("running", ok(200, call("GET", "/api/admin/ytdlp", admin))
                .getAsJsonObject("update").get("state").getAsString());

        runQueued();
        info = ok(200, call("GET", "/api/admin/ytdlp", admin));
        assertEquals("2026.11.01", info.get("version").getAsString(), "the cached version is read again");
        JsonObject update = info.getAsJsonObject("update");
        assertEquals("succeeded", update.get("state").getAsString());
        assertEquals(0, update.get("exitCode").getAsInt());
        assertEquals("2026.03.17", update.get("versionBefore").getAsString());
        assertEquals("2026.11.01", update.get("versionAfter").getAsString());
        assertTrue(update.has("finishedAt"));
        String log = update.get("log").getAsString();
        assertTrue(log.contains(" -U"), log);
        assertTrue(log.contains("Current version: 2026.03.17"), log);
        assertTrue(log.contains("Updating to 2026.11.01"), log);
        assertTrue(log.contains("Updated yt-dlp to 2026.11.01"), log);

        // audited once per accepted update, with who and the version before
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT actor_user_id, target, details_json FROM audit_log"
                    + " WHERE action = ?")) {
                ps.setString(1, YtdlpApi.AUDIT_UPDATE);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(adminId, rs.getString(1));
                    assertEquals(null, rs.getString(2));
                    assertEquals("{\"versionBefore\":\"2026.03.17\"}", rs.getString(3));
                    assertFalse(rs.next());
                }
            }
            return null;
        });

        // a finished run doesn't block the next one
        ok(202, call("POST", "/api/admin/ytdlp/update", admin));
        runQueued();
    }

    @Test
    void aFailedUpdateShowsTheExitCodeAndOutput() throws Exception {
        start(fakeYtdlp("echo 'ERROR: You installed yt-dlp with pip; use that to update' >&2\nexit 1").toString());
        ok(202, call("POST", "/api/admin/ytdlp/update", admin));
        runQueued();
        JsonObject info = ok(200, call("GET", "/api/admin/ytdlp", admin));
        JsonObject update = info.getAsJsonObject("update");
        assertEquals("failed", update.get("state").getAsString());
        assertEquals(1, update.get("exitCode").getAsInt());
        assertEquals("2026.03.17", update.get("versionAfter").getAsString());
        assertTrue(update.get("log").getAsString().contains("installed yt-dlp with pip"), update.toString());
    }

    @Test
    void withoutYtdlpNothingRuns() throws Exception {
        start(null);
        JsonObject info = ok(200, call("GET", "/api/admin/ytdlp", admin));
        assertFalse(info.get("available").getAsBoolean());
        assertFalse(info.has("version"));
        HttpResponse<String> r = call("POST", "/api/admin/ytdlp/update", admin);
        assertEquals(503, r.statusCode());
        assertEquals("ytdlp_unavailable", code(r));
        assertTrue(queued.isEmpty());
    }

    @Test
    void onlyAdminSettingsHolders() throws Exception {
        start(fakeYtdlp("exit 0").toString());
        for (String method : new String[] {"GET", "POST"}) {
            String path = method.equals("GET") ? "/api/admin/ytdlp" : "/api/admin/ytdlp/update";
            assertEquals(401, call(method, path, null).statusCode());
            assertEquals(403, call(method, path, alice).statusCode());
            HttpResponse<String> asGuest = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + path)).header("Cookie", "gid=" + guestCookie)
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, asGuest.statusCode());
        }
        assertTrue(queued.isEmpty());
    }
}
