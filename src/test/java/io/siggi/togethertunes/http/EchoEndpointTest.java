package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Random;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link EchoEndpoint}: every message comes back unchanged, in order, on the shared acceptor. */
class EchoEndpointTest {
    private static final String ORIGIN = "https://tunes.example";

    @TempDir
    Path dir;

    private WebServer server;

    @BeforeEach
    void setUp() throws IOException {
        Router router = new Router();
        EchoEndpoint.register(router, new WebSockets(new Csrf(ORIGIN, List.of("sid", "gid"))));
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private SimpleWebsocket connect(String origin) throws IOException {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", origin);
        return new WebSocketConnector().setMaxPayloadLength(1 << 20)
                .connect(URI.create("ws://127.0.0.1:" + server.port() + EchoEndpoint.PATH), headers);
    }

    @Test
    void echoesTextAndBinaryInOrder() throws Exception {
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            for (int i = 0; i < 100; i++) {
                ws.send(WebSocketMessage.create("message " + i));
            }
            byte[] bytes = new byte[65536];
            new Random(7).nextBytes(bytes);
            ws.send(WebSocketMessage.create(bytes));
            ws.send(WebSocketMessage.create(""));
            ws.send(WebSocketMessage.create("äöü 🎵"));
            for (int i = 0; i < 100; i++) {
                assertEquals("message " + i, ws.read().getText());
            }
            WebSocketMessage binary = ws.read();
            assertTrue(binary.isBinary());
            assertArrayEquals(bytes, binary.getBytes());
            assertEquals("", ws.read().getText());
            assertEquals("äöü 🎵", ws.read().getText());
        }
    }

    @Test
    void sharesTheOriginCheckAndMessageLimit() throws Exception {
        assertThrows(IOException.class, () -> connect("https://evil.example").close());
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            ws.send(WebSocketMessage.create(new byte[WebSockets.MAX_MESSAGE_SIZE + 1]));
            WebSocketMessage message = null;
            try {
                while ((message = ws.read()) != null && message.isControlMessage()) {
                    // skip the close frame
                }
            } catch (IOException e) {
                message = null;
            }
            assertNull(message);
            assertEquals(SimpleWebsocket.CLOSE_MESSAGE_TOO_BIG, ws.getCloseInfo().getRemoteStatusCode());
        }
    }

    private HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonArray closes(String tag) throws Exception {
        // the close is recorded on the socket's thread, just after the client sees it
        for (int i = 0; i < 100; i++) {
            HttpResponse<String> response = http("GET", EchoEndpoint.CLOSES_PATH + "?tag=" + tag, null);
            assertEquals(200, response.statusCode());
            JsonArray closes = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("closes");
            if (!closes.isEmpty()) {
                return closes;
            }
            Thread.sleep(20);
        }
        return new JsonArray();
    }

    @Test
    void remembersTheCloseInfoOfTaggedSockets() throws Exception {
        WebsocketHeaders headers = new WebsocketHeaders();
        headers.setHeader("Origin", ORIGIN);
        try (SimpleWebsocket ws = new WebSocketConnector()
                .connect(URI.create("ws://127.0.0.1:" + server.port() + EchoEndpoint.PATH + "?tag=run1-close"), headers)) {
            ws.send(WebSocketMessage.create("hi"));
            assertEquals("hi", ws.read().getText());
            ws.close(1001, "going");
        }
        JsonArray closes = closes("run1-close");
        assertEquals(1, closes.size());
        JsonObject closed = closes.get(0).getAsJsonObject();
        assertEquals("run1-close", closed.get("tag").getAsString());
        assertEquals(1001, closed.get("remoteCode").getAsInt());
        assertEquals("going", closed.get("remoteReason").getAsString());
        assertTrue(closed.get("clean").getAsBoolean());
        assertTrue(closed.get("initiatedByPeer").getAsBoolean());
        assertTrue(closes("other-tag").isEmpty());
    }

    @Test
    void rejectsBadTags() throws Exception {
        assertEquals(400, http("GET", EchoEndpoint.CLOSES_PATH, null).statusCode());
        assertEquals(400, http("GET", EchoEndpoint.CLOSES_PATH + "?tag=a%20b", null).statusCode());
    }

    @Test
    void forgetsTheOldestCloses() {
        EchoEndpoint.CloseLog log = new EchoEndpoint.CloseLog();
        for (int i = 0; i <= EchoEndpoint.MAX_REMEMBERED; i++) {
            log.add(new EchoEndpoint.Closed("t" + i, 0, 1000, "", 1000, "", true, true, null));
        }
        assertTrue(log.find("t0").isEmpty());
        assertEquals(1, log.find("t1").size());
        assertEquals(1, log.find("t" + EchoEndpoint.MAX_REMEMBERED).size());
    }

    @Test
    void acceptsReports() throws Exception {
        assertEquals(204, http("POST", EchoEndpoint.REPORT_PATH, "{\"browser\":\"test\",\"results\":[]}").statusCode());
        assertEquals(400, http("POST", EchoEndpoint.REPORT_PATH, "not json").statusCode());
    }
}
