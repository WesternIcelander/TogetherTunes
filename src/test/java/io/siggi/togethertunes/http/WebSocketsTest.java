package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.simplewebsocket.SimpleWebsocket;
import io.siggi.simplewebsocket.WebSocketCloseInfo;
import io.siggi.simplewebsocket.WebSocketConnector;
import io.siggi.simplewebsocket.WebSocketMessage;
import io.siggi.simplewebsocket.WebsocketHeaders;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WebSockets}: the shared acceptor's settings (64 KiB messages, the {@code publicBaseUrl}
 * origin check), one virtual thread per socket with a blocking read loop, and the close info
 * being logged when a socket ends. Uses SimpleWebsocket as the client and raw sockets for the
 * refused handshakes.
 */
class WebSocketsTest {
    /** Mixed case, non-default port and a path prefix: the origin is {@code http://tunes.example:8080}. */
    private static final String PUBLIC_BASE_URL = "http://Tunes.Example:8080/app";
    private static final String ORIGIN = "http://tunes.example:8080";

    @TempDir
    Path dir;

    private WebServer server;
    /** The package logger, so the refusal logged by {@link Csrf} is seen too. */
    private final Logger julLogger = Logger.getLogger(WebSockets.class.getPackageName());
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logLines.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** Events from the endpoint: "open <thread> <virtual>", "close <info>". */
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<WebSocketCloseInfo> closeInfos = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws IOException {
        julLogger.addHandler(logHandler);
        julLogger.setLevel(Level.ALL);
        WebSockets webSockets = new WebSockets(new Csrf(PUBLIC_BASE_URL, List.of("sid", "gid")));
        Router router = new Router();
        router.get("/ws/echo", webSockets.handler((request, ws) -> {
            Thread t = Thread.currentThread();
            events.add("open " + t.getName() + " " + t.isVirtual() + " " + t.threadId());
            return new WebSockets.Session() {
                @Override
                public void onMessage(WebSocketMessage message) throws Exception {
                    if (message.isText() && message.getText().equals("boom")) {
                        throw new IllegalStateException("boom");
                    }
                    ws.send(message);
                }

                @Override
                public void onClose(WebSocketCloseInfo info) {
                    closeInfos.add(info);
                }
            };
        }));
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) {
                server.close();
            }
        } finally {
            julLogger.removeHandler(logHandler);
            julLogger.setLevel(null);
        }
    }

    private SimpleWebsocket connect(String origin) throws IOException {
        WebsocketHeaders headers = new WebsocketHeaders();
        if (origin != null) {
            headers.setHeader("Origin", origin);
        }
        // the client's own frame limit (16 KiB by default) must let the 64 KiB echo through
        return new WebSocketConnector().setMaxPayloadLength(1 << 20)
                .connect(URI.create("ws://127.0.0.1:" + server.port() + "/ws/echo"), headers);
    }

    @Test
    void originIsSchemeHostAndPortOfPublicBaseUrl() {
        assertEquals("http://tunes.example:8080", Csrf.originOf("http://Tunes.Example:8080/app"));
        assertEquals("https://tunes.example", Csrf.originOf("https://tunes.example"));
        assertEquals("https://tunes.example", Csrf.originOf("https://tunes.example:443/"));
        assertEquals("http://tunes.example", Csrf.originOf("HTTP://tunes.example:80"));
        assertEquals("https://tunes.example:80", Csrf.originOf("https://tunes.example:80"));
        assertEquals("http://[::1]:8080", Csrf.originOf("http://[::1]:8080"));
        assertThrows(IllegalArgumentException.class, () -> Csrf.originOf("ftp://tunes.example"));
        assertThrows(IllegalArgumentException.class, () -> Csrf.originOf("/relative"));
        assertEquals(ORIGIN, new WebSockets(new Csrf(PUBLIC_BASE_URL, List.of("sid", "gid"))).origin());
    }

    @Test
    void acceptorSettings() {
        WebSockets webSockets = new WebSockets(new Csrf(PUBLIC_BASE_URL, List.of("sid", "gid")));
        assertEquals(65536, webSockets.acceptor().getMaxMessageSize());
        assertEquals(65536 + 1024, webSockets.acceptor().getMaxPayloadLength());
        assertEquals(List.of(ORIGIN), webSockets.acceptor().getAllowedOrigins());
        assertFalse(webSockets.acceptor().isRequiringSameOrigin());
        assertNull(webSockets.acceptor().getOriginChecker());
    }

    @Test
    void echoesTextAndBinaryWithMatchingOrigin() throws Exception {
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            ws.send(WebSocketMessage.create("hello"));
            assertEquals("hello", ws.read().getText());
            byte[] bytes = {1, 2, 3, 0, (byte) 255};
            ws.send(WebSocketMessage.create(bytes));
            WebSocketMessage reply = ws.read();
            assertTrue(reply.isBinary());
            assertArrayEquals(bytes, reply.getBytes());
        }
        WebSocketCloseInfo info = closeInfos.poll(10, TimeUnit.SECONDS);
        assertNotNull(info);
        assertTrue(info.isClean(), info.toString());
        assertTrue(info.isInitiatedByPeer(), info.toString());
    }

    @Test
    void originMatchIgnoresCaseAndDefaultPortForms() throws Exception {
        try (SimpleWebsocket ws = connect("HTTP://TUNES.EXAMPLE:8080")) {
            ws.send(WebSocketMessage.create("x"));
            assertEquals("x", ws.read().getText());
        }
    }

    @Test
    void missingOriginIsAccepted() throws Exception {
        // Only browsers send Origin, and only browsers can be tricked into sending the user's cookies.
        try (SimpleWebsocket ws = connect(null)) {
            ws.send(WebSocketMessage.create("no origin"));
            assertEquals("no origin", ws.read().getText());
        }
    }

    @Test
    void missingOriginWithACredentialCookieIsRefused() throws Exception {
        // A browser always sends Origin, so an upgrade carrying sid/gid without it is refused
        // (it would be authenticated by the cookie, CSRF check from docs/auth.md).
        for (String cookie : List.of("sid=abc", "gid=abc", "x=1; sid=abc")) {
            String response = rawHandshake(handshakeRequest(null, "13")
                    .replace("\r\n\r\n", "\r\nCookie: " + cookie + "\r\n\r\n"));
            assertTrue(response.startsWith("HTTP/1.1 403 "), cookie + ": " + response);
            assertTrue(response.contains("\"origin_not_allowed\""), response);
        }
        // with the right Origin, cookies are fine; other cookies don't need an Origin
        assertTrue(rawHandshake(handshakeRequest(ORIGIN, "13")
                .replace("\r\n\r\n", "\r\nCookie: sid=abc\r\n\r\n")).startsWith("HTTP/1.1 101 "));
        assertTrue(rawHandshake(handshakeRequest(null, "13")
                .replace("\r\n\r\n", "\r\nCookie: theme=dark\r\n\r\n")).startsWith("HTTP/1.1 101 "));
        assertNotNull(events.poll(5, TimeUnit.SECONDS));
        assertNotNull(events.poll(5, TimeUnit.SECONDS));
        assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void otherOriginsAreRefusedWith403() throws Exception {
        for (String origin : List.of("http://evil.example", "https://tunes.example:8080", "http://tunes.example",
                "http://tunes.example:8081", "null", "http://tunes.example:8080.evil.example")) {
            String response = rawHandshake(origin, "13");
            assertTrue(response.startsWith("HTTP/1.1 403 "), origin + ": " + response);
            assertTrue(response.contains("\"origin_not_allowed\""), response);
            assertFalse(response.toLowerCase().contains("sec-websocket-accept"), response);
            IOException e = assertThrows(IOException.class, () -> connect(origin).close());
            assertNotNull(e);
        }
        assertNull(events.poll(200, TimeUnit.MILLISECONDS), "no socket was opened");
        assertTrue(logLines.stream().anyMatch(l -> l.contains("refused") && l.contains("http://evil.example")), logLines.toString());
    }

    @Test
    void plainGetGets426() throws Exception {
        try (Socket s = rawSocket()) {
            s.getOutputStream().write(("GET /ws/echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = readAll(s.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 426 Upgrade Required"), response);
            assertTrue(response.toLowerCase().contains("upgrade: websocket"), response);
            assertTrue(response.contains("\"upgrade_required\""), response);
        }
    }

    @Test
    void unsupportedVersionGets426FromAcceptor() throws Exception {
        String response = rawHandshake(ORIGIN, "8");
        assertTrue(response.startsWith("HTTP/1.1 426 "), response);
        assertTrue(response.toLowerCase().contains("sec-websocket-version: 13"), response);
        assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void rawHandshakeWithOriginSwitchesProtocols() throws Exception {
        String response = rawHandshake(ORIGIN, "13");
        assertTrue(response.startsWith("HTTP/1.1 101 "), response);
        assertNotNull(events.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void messagesUpTo64KiBAreAcceptedAndBiggerOnesCloseWith1009() throws Exception {
        byte[] max = new byte[65536];
        new java.util.Random(42).nextBytes(max); // incompressible, so one 64 KiB frame even with deflate
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            ws.send(WebSocketMessage.create(max));
            assertArrayEquals(max, ws.read().getBytes());

            ws.send(WebSocketMessage.create(new byte[65537]));
            assertNull(readUntilClosed(ws));
            assertEquals(SimpleWebsocket.CLOSE_MESSAGE_TOO_BIG, ws.getCloseInfo().getRemoteStatusCode());
        }
        WebSocketCloseInfo info = closeInfos.poll(10, TimeUnit.SECONDS);
        assertNotNull(info);
        assertEquals(1009, info.getLocalStatusCode());
    }

    /**
     * What Chrome and Firefox do: with permessage-deflate they compress every message, even
     * incompressible data, so a 64 KiB message travels as a frame of slightly more than 64 KiB.
     */
    @Test
    void compressedFramesOfFullSizeMessagesAreAccepted() throws Exception {
        byte[] max = new byte[65536];
        new java.util.Random(42).nextBytes(max);
        try (Socket s = rawSocket()) {
            OutputStream out = s.getOutputStream();
            String request = handshakeRequest(ORIGIN, "13")
                    .replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n\r\n");
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            String head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101 "), head);
            assertTrue(head.toLowerCase(java.util.Locale.ROOT).contains("permessage-deflate"), head);

            byte[] payload = deflate(max);
            assertTrue(payload.length > 65536, "deflate grows random data: " + payload.length);
            sendFrame(out, 0xC2, payload); // FIN, RSV1 (compressed), binary
            int[] reply = readFrameStart(in);
            assertEquals(0x2, reply[0], "echoed as binary, not closed");

            sendFrame(out, 0xC2, deflate(new byte[65537])); // compresses well, but is over the message limit
            // skip the rest of the echo, then expect the close frame
            int[] next;
            do {
                next = readFrameStart(in);
            } while (next[0] != 0x8);
            assertEquals(1009, next[1]);
        }
    }

    /** permessage-deflate payload: raw deflate, sync flush, without the trailing 00 00 FF FF. */
    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(data);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = deflater.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH)) > 0) {
            buf.write(chunk, 0, n);
        }
        deflater.end();
        byte[] out = buf.toByteArray();
        return Arrays.copyOf(out, out.length - 4);
    }

    /** One masked client frame with a 64-bit length. */
    private static void sendFrame(OutputStream out, int firstByte, byte[] payload) throws IOException {
        byte[] mask = {1, 2, 3, 4};
        out.write(firstByte);
        out.write(0x80 | 127);
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((long) payload.length >>> shift) & 0xFF);
        }
        out.write(mask);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(masked);
        out.flush();
    }

    /** Reads one server frame; returns {opcode, close code (or -1)}. The payload is skipped. */
    private static int[] readFrameStart(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        long length = b1 & 0x7F;
        if (length == 126) {
            length = in.readUnsignedShort();
        } else if (length == 127) {
            length = in.readLong();
        }
        int opcode = b0 & 0x0F;
        int code = -1;
        if (opcode == 0x8 && length >= 2) {
            code = in.readUnsignedShort();
            length -= 2;
        }
        in.skipNBytes(length);
        return new int[] {opcode, code};
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (!buf.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf.write(b);
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void eachSocketRunsOnItsOwnVirtualThread() throws Exception {
        try (SimpleWebsocket a = connect(ORIGIN); SimpleWebsocket b = connect(ORIGIN)) {
            String openA = events.poll(5, TimeUnit.SECONDS);
            String openB = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(openA);
            assertNotNull(openB);
            String[] partsA = openA.split(" ");
            String[] partsB = openB.split(" ");
            assertTrue(partsA[1].startsWith("ws-"), openA);
            assertEquals("true", partsA[2], openA);
            assertEquals("true", partsB[2], openB);
            assertNotEquals(partsA[1], partsB[1]);
            assertNotEquals(partsA[3], partsB[3]);
            // both blocked in read() at the same time, both still answer
            b.send(WebSocketMessage.create("b"));
            a.send(WebSocketMessage.create("a"));
            assertEquals("b", b.read().getText());
            assertEquals("a", a.read().getText());
        }
    }

    @Test
    void closeInfoIsLoggedWhenASocketEnds() throws Exception {
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            ws.close(4001, "bye now");
        }
        assertNotNull(closeInfos.poll(10, TimeUnit.SECONDS));
        String line = waitForLog("closed after");
        assertTrue(line.startsWith("INFO ws-"), line);
        assertTrue(line.contains("/ws/echo"), line);
        assertTrue(line.contains("remote=4001 \"bye now\""), line);
    }

    @Test
    void peerVanishingWithoutCloseFrameIsLoggedAsUnclean() throws Exception {
        Socket s = rawSocket();
        s.getOutputStream().write(handshakeRequest(ORIGIN, "13").getBytes(StandardCharsets.US_ASCII));
        assertNotNull(events.poll(5, TimeUnit.SECONDS));
        s.close();
        WebSocketCloseInfo info = closeInfos.poll(10, TimeUnit.SECONDS);
        assertNotNull(info);
        assertFalse(info.isClean());
        assertEquals(WebSocketCloseInfo.NO_CLOSE_FRAME, info.getRemoteStatusCode());
        assertTrue(waitForLog("closed after").contains("clean=false"));
    }

    @Test
    void handlerExceptionClosesWith1011() throws Exception {
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            ws.send(WebSocketMessage.create("boom"));
            assertNull(readUntilClosed(ws));
            assertEquals(1011, ws.getCloseInfo().getRemoteStatusCode());
        }
        assertNotNull(closeInfos.poll(10, TimeUnit.SECONDS));
        assertTrue(logLines.stream().anyMatch(l -> l.startsWith("SEVERE ") && l.contains("failed")), logLines.toString());
    }

    @Test
    void serverShutdownEndsOpenSockets() throws Exception {
        try (SimpleWebsocket ws = connect(ORIGIN)) {
            assertNotNull(events.poll(5, TimeUnit.SECONDS));
            server.close();
            assertNotNull(closeInfos.poll(10, TimeUnit.SECONDS));
            assertNull(readUntilClosed(ws));
        }
    }

    // --- helpers ---

    /** Reads until {@code read()} returns null (skipping the close frame) or fails. */
    private static WebSocketMessage readUntilClosed(SimpleWebsocket ws) {
        try {
            WebSocketMessage m;
            while ((m = ws.read()) != null) {
                if (!m.isControlMessage()) {
                    return m;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private String waitForLog(String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            synchronized (logLines) {
                for (String l : logLines) {
                    if (l.contains(needle)) {
                        return l;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No log line with '" + needle + "' in " + logLines);
    }

    private Socket rawSocket() throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        return s;
    }

    private static String handshakeRequest(String origin, String version) {
        return "GET /ws/echo HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: " + version + "\r\n"
                + (origin == null ? "" : "Origin: " + origin + "\r\n") + "\r\n";
    }

    /** Sends a handshake and returns the response head (and body, if one comes before 1 s of silence). */
    private String rawHandshake(String origin, String version) throws IOException {
        return rawHandshake(handshakeRequest(origin, version));
    }

    private String rawHandshake(String request) throws IOException {
        try (Socket s = rawSocket()) {
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            s.setSoTimeout(1000);
            return readAll(s.getInputStream());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        try {
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
                String text = buf.toString(StandardCharsets.ISO_8859_1);
                if (text.startsWith("HTTP/1.1 101 ") && text.contains("\r\n\r\n")) {
                    break;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // keep-alive response: we have what was sent
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }
}
