package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DiagnosticsApi}: the listen-mode report is logged on one line, size- and rate-limited, without capabilities. */
class DiagnosticsApiTest {
    @TempDir
    Path dir;

    private Database database;
    private WebServer server;
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example"),
                4, dir).load(null).config();
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        TestClock clock = new TestClock(System.currentTimeMillis());
        ActorResolver actors = new ActorResolver(database, new Settings(database, config), clock, config.secureCookies());
        Router router = new Router(actors.hook());
        new DiagnosticsApi(new RateLimiter("diagnostics", 3, 60_000, clock), lines::add).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        database.close();
    }

    private HttpResponse<String> post(String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + DiagnosticsApi.PATH))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void logsTheReportOnOneLineWithCapabilitiesMasked() throws Exception {
        String report = "{\"ref\":\"abc123\",\"userAgent\":\"iPhone OS 15_1\",\"errors\":[{\"kind\":\"error\","
                + "\"error\":\"TypeError: x\\nat https://h/ws?ticket=SECRET1&x=1 and /live/a.ts?t=SECRET2 key=ok\"}],"
                + "\"decisions\":[{\"t\":1,\"el\":\"v0\",\"drift\":-30,\"action\":\"steady\"}]}";
        assertEquals(204, post("application/json", report).statusCode());
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertFalse(line.contains("\n"), line);
        assertTrue(line.startsWith("Client diagnostics from anonymous: {"), line);
        assertTrue(line.contains("\"ref\":\"abc123\""), line);
        assertTrue(line.contains("\"action\":\"steady\""), line);
        assertFalse(line.contains("SECRET1") || line.contains("SECRET2"), line);
        assertTrue(line.contains("?ticket=***&x=1"), line);
        assertTrue(line.contains("key=ok"), line); // not a URL parameter
        // what is logged is still the JSON that was sent
        JsonObject logged = JsonParser.parseString(line.substring(line.indexOf('{'))).getAsJsonObject();
        assertEquals("iPhone OS 15_1", logged.get("userAgent").getAsString());
    }

    @Test
    void acceptsAFullReport() throws Exception {
        // 200 decisions and 150 events are some 40-60 KB; bodies above 16 KiB must arrive whole
        StringBuilder b = new StringBuilder("{\"ref\":\"big\",\"decisions\":[");
        for (int i = 0; i < 1000; i++) {
            b.append(i == 0 ? "" : ",").append("{\"t\":").append(i).append(",\"el\":\"a0\",\"drift\":-12,"
                    + "\"action\":\"steady\",\"rate\":1,\"readyState\":4,\"seeking\":false,\"paused\":false}");
        }
        String body = b.append("]}").toString();
        assertTrue(body.length() > 64 * 1024 && body.length() < DiagnosticsApi.MAX_BYTES, String.valueOf(body.length()));
        HttpResponse<String> r = post("application/json", body);
        assertEquals(204, r.statusCode(), r.body());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("{\"t\":999,"), "the whole report is logged");
    }

    @Test
    void rejectsWhatIsNotAJsonObjectOrTooLarge() throws Exception {
        assertEquals(415, post("text/plain", "{}").statusCode());
        assertEquals(400, post("application/json", "not json").statusCode());
        HttpResponse<String> big = post("application/json", "{\"x\":\"" + "a".repeat(DiagnosticsApi.MAX_BYTES) + "\"}");
        assertEquals(413, big.statusCode(), big.body());
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    void isRateLimitedPerClient() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(204, post("application/json", "{}").statusCode());
        }
        assertEquals(429, post("application/json", "{}").statusCode());
        assertEquals(3, lines.size());
    }

    @Test
    void namesTheActorByKindAndIdOnly() {
        assertEquals("user u1", DiagnosticsApi.describe(new Actor.User("u1", "session-secret", Actor.Via.COOKIE)));
        assertEquals("anonymous", DiagnosticsApi.describe(Actor.ANONYMOUS));
        assertEquals("guest g1", DiagnosticsApi.describe(new Actor.Guest("g1", GuestIdentity.COOKIE, false)));
        assertEquals("a?token=***&b=2", DiagnosticsApi.mask("a?token=abc&b=2"));
    }
}
