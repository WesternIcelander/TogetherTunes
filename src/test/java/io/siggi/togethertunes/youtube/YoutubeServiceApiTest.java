package io.siggi.togethertunes.youtube;

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
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admin's YouTube service box over HTTP ({@link YoutubeServiceApi}) against {@link FakeYtSucks}:
 * the status summary, Update now (audited), the update log, the connection test (URL and token, the
 * stored ones or the form's), not set up / unreachable, the token never in an answer, and that only
 * {@code admin.settings} may call them.
 */
class YoutubeServiceApiTest {
    private static final String TOKEN = "tt-secret-token-123";

    @TempDir
    Path dir;

    private FakeYtSucks fake;
    private Database db;
    private WebServer server;
    private Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String admin;
    private String alice;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeYtSucks();
        fake.requiredToken = TOKEN;
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        YoutubeService service = new YoutubeService(() -> {
            try {
                return new YoutubeService.Endpoint(settings.youtubeServiceUrl(), settings.youtubeServiceToken());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        new YoutubeServiceApi(service, settings, db, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        String adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        fake.close();
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject ok(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertFalse(r.body().contains(TOKEN), "never the token: " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private void configure(String token) throws Exception {
        settings.setJson("youtube.serviceUrl", "\"" + fake.url() + "/\"");
        if (token != null) {
            settings.setJson("youtube.serviceToken", "\"" + token + "\"");
        }
    }

    private int audits() throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?")) {
                ps.setString(1, YoutubeServiceApi.AUDIT_UPDATE);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void notSetUp() throws Exception {
        JsonObject s = ok(call("GET", "/api/admin/youtube-service", admin, null));
        assertFalse(s.get("configured").getAsBoolean());
        assertFalse(s.get("reachable").getAsBoolean());
        HttpResponse<String> r = call("POST", "/api/admin/youtube-service/update", admin, null);
        assertEquals(503, r.statusCode());
        assertEquals("youtube_service_unavailable", code(r));
        JsonObject t = ok(call("POST", "/api/admin/youtube-service/test", admin, null));
        assertFalse(t.get("reachable").getAsBoolean());
        assertEquals(0, fake.requests().size());
    }

    @Test
    void statusUpdateAndLog() throws Exception {
        configure(TOKEN);
        JsonObject s = ok(call("GET", "/api/admin/youtube-service", admin, null));
        assertTrue(s.get("configured").getAsBoolean());
        assertTrue(s.get("reachable").getAsBoolean());
        JsonObject v = s.getAsJsonObject("versions");
        assertEquals("2026.11.20", v.get("ytdlp").getAsString());
        assertEquals("0.3.1", v.get("ytdlpEjs").getAsString());
        assertEquals("1.2.2", v.get("tokenPlugin").getAsString());
        assertEquals("1.2.2", v.get("tokenProvider").getAsString());
        assertEquals("2.5.6", v.get("deno").getAsString());
        assertEquals("N-12345", v.get("ffmpeg").getAsString());
        assertEquals(1, s.getAsJsonObject("providers").get("ready").getAsInt());
        assertEquals(1, s.getAsJsonObject("providers").get("total").getAsInt());
        assertFalse(s.get("updating").getAsBoolean());
        assertFalse(s.get("staging").getAsBoolean());
        assertEquals("up_to_date", s.getAsJsonObject("lastUpdate").get("result").getAsString());
        assertEquals(0, s.get("activeJobs").getAsInt());
        assertEquals(null, fake.requests("/api/status").get(0).query().get("token"));

        JsonObject u = ok(call("POST", "/api/admin/youtube-service/update", admin, null));
        assertTrue(u.get("started").getAsBoolean());
        assertEquals("Bearer " + TOKEN, fake.requests("/api/update").get(0).authorization());
        assertEquals(1, audits());
        fake.updateBody = "{\"started\":false,\"reason\":\"update already running\"}";
        u = ok(call("POST", "/api/admin/youtube-service/update", admin, null));
        assertFalse(u.get("started").getAsBoolean());
        assertEquals("update already running", u.get("reason").getAsString());
        assertEquals(1, audits(), "only a started update is audited");

        JsonObject log = ok(call("GET", "/api/admin/youtube-service/update-log", admin, null));
        assertEquals("2026-11-28T04:00:00+00:00: up_to_date\nstage: building\nresult: up_to_date\n",
                log.get("log").getAsString());
        assertFalse(log.get("truncated").getAsBoolean());
        fake.updateLogBody = "{\"time\":\"t\",\"result\":\"failing\",\"log\":[\"" + "x".repeat(300_000) + "\",\"the end\"]}";
        log = ok(call("GET", "/api/admin/youtube-service/update-log", admin, null));
        assertTrue(log.get("truncated").getAsBoolean());
        assertTrue(log.get("log").getAsString().endsWith("the end\n"));
        assertTrue(log.get("log").getAsString().length() <= YoutubeServiceApi.LOG_MAX_CHARS + 2);
        fake.updateLogBody = "null";
        assertFalse(ok(call("GET", "/api/admin/youtube-service/update-log", admin, null)).has("log"), "no update yet");

        // a wrong token: the status still works (it needs none); the update and the log are refused
        settings.setJson("youtube.serviceToken", "\"wrong\"");
        assertTrue(ok(call("GET", "/api/admin/youtube-service", admin, null)).get("reachable").getAsBoolean());
        HttpResponse<String> r = call("POST", "/api/admin/youtube-service/update", admin, null);
        assertEquals(502, r.statusCode());
        assertEquals("youtube_service_refused", code(r));
        assertEquals(502, call("GET", "/api/admin/youtube-service/update-log", admin, null).statusCode());

        // unreachable
        fake.close();
        s = ok(call("GET", "/api/admin/youtube-service", admin, null));
        assertFalse(s.get("reachable").getAsBoolean());
        assertTrue(s.has("error"));
        assertEquals(503, call("POST", "/api/admin/youtube-service/update", admin, null).statusCode());
    }

    @Test
    void connectionTest() throws Exception {
        configure(TOKEN);
        JsonObject t = ok(call("POST", "/api/admin/youtube-service/test", admin, null));
        assertTrue(t.get("reachable").getAsBoolean());
        assertTrue(t.get("tokenAccepted").getAsBoolean());
        assertEquals(404, t.get("httpStatus").getAsInt());
        // the form's unsaved token (wrong) and URL
        t = ok(call("POST", "/api/admin/youtube-service/test", admin, "{\"url\":\"" + fake.url() + "\",\"token\":\"nope\"}"));
        assertTrue(t.get("reachable").getAsBoolean());
        assertFalse(t.get("tokenAccepted").getAsBoolean());
        assertEquals("Bearer nope", fake.requests().get(fake.requests().size() - 1).authorization());
        // a blank token in the form keeps the stored one
        t = ok(call("POST", "/api/admin/youtube-service/test", admin, "{\"token\":\"\"}"));
        assertTrue(t.get("tokenAccepted").getAsBoolean());
        HttpResponse<String> bad = call("POST", "/api/admin/youtube-service/test", admin, "{\"url\":\"ftp://x\"}");
        assertEquals(400, bad.statusCode());
        assertEquals("invalid_url", code(bad));
        // nothing listening
        t = ok(call("POST", "/api/admin/youtube-service/test", admin, "{\"url\":\"http://127.0.0.1:1\"}"));
        assertFalse(t.get("reachable").getAsBoolean());
        assertTrue(t.has("error"));
    }

    @Test
    void onlyAdminSettings() throws Exception {
        configure(TOKEN);
        for (String[] route : new String[][] {{"GET", "/api/admin/youtube-service"},
                {"POST", "/api/admin/youtube-service/update"}, {"GET", "/api/admin/youtube-service/update-log"},
                {"POST", "/api/admin/youtube-service/test"}}) {
            assertEquals(401, call(route[0], route[1], null, null).statusCode(), route[1]);
            assertEquals(403, call(route[0], route[1], alice, null).statusCode(), route[1]);
        }
        assertEquals(0, fake.requests().size(), "refused calls ask nobody");
        assertEquals(0, audits());
    }
}
