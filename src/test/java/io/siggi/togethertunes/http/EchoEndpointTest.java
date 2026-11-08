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
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
        EchoEndpoint.register(router, new WebSockets(ORIGIN));
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
}
