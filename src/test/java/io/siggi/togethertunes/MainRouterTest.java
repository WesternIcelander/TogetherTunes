package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.SetupApi;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application's routes as {@link Main#router} wires them: the test page loads from the built
 * web UI, and the test WebSocket at {@code /ws/echo} echoes for the origin of
 * {@code http.publicBaseUrl}.
 */
class MainRouterTest {
    private static final String PUBLIC_BASE_URL = "https://tunes.example";

    @TempDir
    Path dir;

    private StaticFiles staticFiles;
    private Database database;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(MainRouterTest.class.getClassLoader(), dir);
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(database);
        BuiltinRoles.ensure(database);
        ActorResolver actors = new ActorResolver(database, new Settings(database, config));
        SetupApi setup = new SetupApi(new Accounts(database, new Passwords(new Passwords.Params(64, 1, 1))));
        server = WebServer.start("127.0.0.1", 0, Main.router(config, staticFiles, actors, setup), dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
        database.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testPageLoads() throws Exception {
        HttpResponse<String> page = get("/test/ws-echo.html");
        assertEquals(200, page.statusCode());
        assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(""));
        assertTrue(page.body().contains("/js/test/ws-echo.js"), page.body());

        HttpResponse<String> script = get("/js/test/ws-echo.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertEquals(200, get("/css/test.css").statusCode());
        assertTrue(get("/").body().contains("/test/ws-echo.html"));
    }

    @Test
    void plainGetOnTheEchoSocketIs426() throws Exception {
        assertEquals(426, get("/ws/echo").statusCode());
    }

    @Test
    void testWebSocketEchoes() throws Exception {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", PUBLIC_BASE_URL);
        try (SimpleWebsocket ws = new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers)) {
            ws.send(WebSocketMessage.create("hello"));
            assertEquals("hello", ws.read().getText());
        }
    }

    @Test
    void testWebSocketRefusesOtherOrigins() {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", "https://evil.example");
        assertThrows(IOException.class, () -> new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers).close());
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void setupFlow() throws Exception {
        HttpResponse<String> status = get("/api/setup");
        assertEquals(200, status.statusCode());
        assertEquals("{\"needed\":true}", status.body());
        assertEquals("no-store", status.headers().firstValue("Cache-Control").orElse(""));

        assertEquals(415, post("/api/setup", "text/plain", "{}").statusCode());
        HttpResponse<String> badJson = post("/api/setup", "application/json", "{nope");
        assertEquals(400, badJson.statusCode());
        assertTrue(badJson.body().contains("\"invalid_json\""), badJson.body());
        HttpResponse<String> shortPassword = post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"password\":\"short\"}");
        assertEquals(400, shortPassword.statusCode());
        assertTrue(shortPassword.body().contains("\"invalid_password\""), shortPassword.body());
        assertEquals("{\"needed\":true}", get("/api/setup").body());

        HttpResponse<String> created = post("/api/setup", "application/json",
                "{\"username\":\"siggi\",\"displayName\":\"Siggi\",\"password\":\"correct horse\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().matches("\\{\"user\":\\{\"id\":\"[0-9a-f-]{36}\",\"username\":\"siggi\","
                + "\"displayName\":\"Siggi\",\"roles\":\\[\"admin\",\"user\"]}}"), created.body());
        assertTrue(!created.body().contains("127.0.0.1"), "no IP in responses");

        assertEquals("{\"needed\":false}", get("/api/setup").body());
        HttpResponse<String> again = post("/api/setup", "application/json",
                "{\"username\":\"mallory\",\"password\":\"0123456789\"}");
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("\"setup_complete\""), again.body());
    }

    @Test
    void setupPageLoads() throws Exception {
        HttpResponse<String> page = get("/setup.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("/js/setup.js"), page.body());
        HttpResponse<String> script = get("/js/setup.js");
        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("\"/api/setup\"") || get("/js/api.js").body().contains("\"/api/setup\""));
        assertTrue(get("/js/main.js").body().contains("/setup.html"), "the home page sends you to setup");
    }
}
