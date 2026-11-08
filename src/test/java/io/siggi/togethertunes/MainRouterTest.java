package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.http.StaticFiles;
import io.siggi.togethertunes.http.WebServer;
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
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", PUBLIC_BASE_URL), 4, dir)
                .load(null).config();
        staticFiles = StaticFiles.fromClasspath(MainRouterTest.class.getClassLoader(), dir);
        server = WebServer.start("127.0.0.1", 0, Main.router(config, staticFiles), dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
        staticFiles.close();
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
}
