package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Endless streaming responses through {@link Response#stream}: chunked and close-delimited bodies,
 * {@code HEAD}, a detached response closed from another thread, and how fast a client leaving
 * shows up as a write failure (without SiggiHTTP logging anything). Uses raw sockets so the exact
 * framing headers can be checked.
 */
class StreamingResponseTest {
    private static final String TS = "video/mp2t";
    /** A 188-byte MPEG-TS-sized packet. */
    private static final byte[] PACKET = new byte[188];

    static {
        PACKET[0] = 0x47;
        for (int i = 1; i < PACKET.length; i++) {
            PACKET[i] = (byte) i;
        }
    }

    @TempDir
    Path dir;

    private WebServer server;
    private PrintStream originalErr;
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

    /** Set by the endless handlers when a write fails. */
    private final AtomicLong writeFailedAt = new AtomicLong();
    private final AtomicReference<IOException> writeFailure = new AtomicReference<>();
    private final CountDownLatch handlerEnded = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        originalErr = System.err;
        System.setErr(new PrintStream(new TeeStream(originalErr, capturedErr), true));
        Router router = new Router();
        router.get("/endless.ts", (rq, rs) -> endless(rs.stream(TS, StreamBody.Framing.CLOSE_DELIMITED)));
        router.get("/endless.chunked", (rq, rs) -> endless(rs.stream(TS, StreamBody.Framing.CHUNKED)));
        router.get("/finite.ts", (rq, rs) -> {
            StreamBody body = rs.stream(TS, StreamBody.Framing.CLOSE_DELIMITED);
            for (int i = 0; i < 3 && !body.headOnly(); i++) {
                body.write(PACKET);
            }
        });
        router.get("/finite.chunked", (rq, rs) -> {
            StreamBody body = rs.stream(TS, StreamBody.Framing.CHUNKED);
            for (int i = 0; i < 3 && !body.headOnly(); i++) {
                body.write(PACKET);
            }
        });
        router.get("/detached.ts", (rq, rs) -> {
            StreamBody body = rs.stream(TS, StreamBody.Framing.CLOSE_DELIMITED);
            rs.detach();
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        body.write(PACKET);
                        Thread.sleep(20);
                    }
                    body.close();
                } catch (IOException | InterruptedException e) {
                    writeFailure.set(new IOException(e));
                }
            });
        });
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) {
                server.close();
            }
        } finally {
            System.setErr(originalErr);
        }
    }

    /** Writes a packet every 5 ms until the client goes away; the IOException propagates. */
    private void endless(StreamBody body) throws IOException {
        if (body.headOnly()) {
            handlerEnded.countDown();
            return;
        }
        try {
            while (true) {
                try {
                    body.write(PACKET);
                } catch (IOException e) {
                    writeFailedAt.set(System.nanoTime());
                    writeFailure.set(e);
                    throw e;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            handlerEnded.countDown();
        }
    }

    // --- raw HTTP client ---

    private record Head(String statusLine, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        return s;
    }

    private static void sendRequest(Socket s, String method, String path) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.isEmpty()) {
            throw new IOException("EOF");
        }
        return sb.toString();
    }

    private static Head readHead(InputStream in) throws IOException {
        String status = readLine(in);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            assertFalse(headers.containsKey(name), "Duplicate header " + name);
            headers.put(name, line.substring(colon + 1).trim());
        }
        return new Head(status, headers);
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] b = in.readNBytes(n);
        assertEquals(n, b.length, "Body ended early");
        return b;
    }

    /** Reads one chunk of a chunked body; empty array for the last chunk. */
    private static byte[] readChunk(InputStream in) throws IOException {
        String sizeLine = readLine(in);
        int semi = sizeLine.indexOf(';');
        int size = Integer.parseInt(semi < 0 ? sizeLine.trim() : sizeLine.substring(0, semi).trim(), 16);
        byte[] data = readExactly(in, size);
        if (size == 0) {
            // trailers end with an empty line
            while (!readLine(in).isEmpty()) {
            }
        } else {
            assertEquals("", readLine(in));
        }
        return data;
    }

    private static void assertNothingMore(Socket s, long waitMillis) throws IOException {
        s.setSoTimeout((int) waitMillis);
        try {
            int b = s.getInputStream().read();
            assertEquals(-1, b, "Unexpected body byte");
        } catch (SocketTimeoutException expected) {
            // connection kept open, but nothing was sent: fine
        }
    }

    private void assertNoErrorLogged() {
        String err = capturedErr.toString(StandardCharsets.UTF_8);
        assertFalse(err.contains("SiggiHTTP"), "SiggiHTTP logged: " + err);
        assertFalse(err.contains("Exception"), "Something logged an exception: " + err);
    }

    // --- tests ---

    @Test
    void closeDelimitedHasNoLengthOrChunking() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "GET", "/endless.ts");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals(TS, head.header("Content-Type"));
            assertNull(head.header("Content-Length"), head.headers().toString());
            assertNull(head.header("Transfer-Encoding"), head.headers().toString());
            assertEquals("close", head.header("Connection").toLowerCase(Locale.ROOT));
            assertEquals("no-store", head.header("Cache-Control"));
            // The raw body is the packets back to back.
            for (int i = 0; i < 50; i++) {
                assertArrayEquals(PACKET, readExactly(in, PACKET.length));
            }
        }
        assertTrue(handlerEnded.await(5, TimeUnit.SECONDS), "handler did not notice the client leaving");
        assertNotNull(writeFailure.get());
    }

    @Test
    void closeDelimitedBodyEndsWithTheConnection() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "GET", "/finite.ts");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertNull(head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            byte[] body = in.readAllBytes(); // until the server closes
            assertEquals(3 * PACKET.length, body.length);
        }
        assertNoErrorLogged();
    }

    @Test
    void chunkedEndlessStream() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "GET", "/endless.chunked");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals(TS, head.header("Content-Type"));
            assertEquals("chunked", head.header("Transfer-Encoding"));
            assertNull(head.header("Content-Length"));
            // Each write was flushed, so it arrives as its own chunk.
            for (int i = 0; i < 50; i++) {
                assertArrayEquals(PACKET, readChunk(in));
            }
        }
        assertTrue(handlerEnded.await(5, TimeUnit.SECONDS), "handler did not notice the client leaving");
    }

    @Test
    void chunkedFiniteStreamKeepsTheConnection() throws Exception {
        try (Socket s = connect()) {
            InputStream in = s.getInputStream();
            for (int round = 0; round < 2; round++) {
                sendRequest(s, "GET", "/finite.chunked");
                Head head = readHead(in);
                assertEquals("HTTP/1.1 200 OK", head.statusLine());
                assertEquals("chunked", head.header("Transfer-Encoding"));
                for (int i = 0; i < 3; i++) {
                    assertArrayEquals(PACKET, readChunk(in));
                }
                assertEquals(0, readChunk(in).length);
            }
        }
        assertNoErrorLogged();
    }

    @Test
    void headCloseDelimited() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "HEAD", "/endless.ts");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals(TS, head.header("Content-Type"));
            assertNull(head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            assertEquals("close", head.header("Connection").toLowerCase(Locale.ROOT));
            assertEquals(-1, in.read(), "HEAD must have no body and the connection must close");
        }
        assertTrue(handlerEnded.await(5, TimeUnit.SECONDS));
        assertNull(writeFailure.get());
        assertNoErrorLogged();
    }

    @Test
    void headChunked() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "HEAD", "/endless.chunked");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals(TS, head.header("Content-Type"));
            assertNull(head.header("Content-Length"));
            assertNothingMore(s, 300);
        }
        assertTrue(handlerEnded.await(5, TimeUnit.SECONDS));
        assertNoErrorLogged();
    }

    @Test
    void detachedCloseDelimitedClosedFromAnotherThread() throws Exception {
        try (Socket s = connect()) {
            sendRequest(s, "GET", "/detached.ts");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertNull(head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            byte[] body = in.readAllBytes(); // EOF once the other thread closes
            assertEquals(3 * PACKET.length, body.length);
        }
        assertNull(writeFailure.get());
        assertNoErrorLogged();
    }

    @Test
    void closeDelimitedDisconnectIsDetectedFast() throws Exception {
        assertDisconnectDetectedFast("/endless.ts", false);
    }

    @Test
    void chunkedDisconnectIsDetectedFast() throws Exception {
        assertDisconnectDetectedFast("/endless.chunked", true);
    }

    private void assertDisconnectDetectedFast(String path, boolean chunked) throws Exception {
        long closedAt;
        try (Socket s = connect()) {
            sendRequest(s, "GET", path);
            InputStream in = s.getInputStream();
            readHead(in);
            for (int i = 0; i < 10; i++) {
                if (chunked) {
                    readChunk(in);
                } else {
                    readExactly(in, PACKET.length);
                }
            }
            closedAt = System.nanoTime();
        }
        assertTrue(handlerEnded.await(5, TimeUnit.SECONDS), "handler did not notice the client leaving");
        assertNotNull(writeFailure.get());
        long millis = TimeUnit.NANOSECONDS.toMillis(writeFailedAt.get() - closedAt);
        System.out.println(path + ": client disconnect detected after " + millis + " ms");
        assertTrue(millis < 1000, "disconnect took " + millis + " ms to surface");
        // Give SiggiHTTP a moment to finish the connection, then check it stayed quiet.
        Thread.sleep(100);
        assertNoErrorLogged();
    }

    /** Copies writes to two streams. */
    private static final class TeeStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
