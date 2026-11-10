package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.PackagerTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.rooms.Timeline;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET}/{@code HEAD /live/{token}.ts} over raw sockets: VRCDN's framing (close-delimited,
 * {@code HEAD} → {@code Content-Length: 0}), join points, viewer counting (probes don't count),
 * leases, the end of the body when the output stops, and that tokens never reach the logs.
 */
class LiveTsApiTest {
    static final String NEXT_TOKEN = "AAAAnextJoinToken00001";
    static final String LATEST_TOKEN = "BBBBlatestJoinToken001";
    static final String FAILING_TOKEN = "CCCCfailingLookup00001";
    /** Fake unit size: the unit's start PTS, then padding (10 TS packets). */
    static final int UNIT_BYTES = 1880;

    @TempDir
    Path dir;

    final List<PackagerTest.FakeProducer> producers = Collections.synchronizedList(new ArrayList<>());
    final List<Packager> packagers = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean failStart = new AtomicBoolean();
    ProgramOutputs outputs;
    LiveTsApi api;
    WebServer server;
    PrintStream originalErr;
    final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    /** Every log record (System.Logger → java.util.logging), formatted, with its exception. */
    final StringBuffer logged = new StringBuffer();
    final java.util.logging.Handler logCapture = new java.util.logging.Handler() {
        final java.util.logging.SimpleFormatter f = new java.util.logging.SimpleFormatter();

        @Override
        public void publish(java.util.logging.LogRecord r) {
            logged.append(f.format(r)).append('\n');
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /** stderr and the log records captured so far. */
    String output() {
        return capturedErr.toString(StandardCharsets.UTF_8) + logged;
    }

    /** Size of the fake units (the stalled-client test makes them big). */
    volatile int unitBytes = UNIT_BYTES;

    byte[] mux(Unit u) {
        return ByteBuffer.allocate(unitBytes).putLong(u.startPts()).array();
    }

    @BeforeEach
    void setUp() throws IOException {
        originalErr = System.err;
        System.setErr(new PrintStream(new TeeStream(originalErr, capturedErr), true));
        java.util.logging.Logger.getLogger("").addHandler(logCapture);
        outputs = new ProgramOutputs((roomId, cue) -> {
            if (failStart.get()) {
                throw new IOException("no filler cards");
            }
            TimeSource time = TimeSource.system(Clock.systemUTC());
            // 1 ms units: always due, so each permit emits one unit at once
            PackagerTest.FakeProducer p = new PackagerTest.FakeProducer(time, 90);
            Packager pk = new Packager(roomId, p, this::mux, new PacingClock(time), time);
            pk.start();
            producers.add(p);
            packagers.add(pk);
            return new ProgramOutputs.Output() {
                @Override
                public Packager packager() {
                    return pk;
                }

                @Override
                public void update(ProgramCue c) {
                }

                @Override
                public void close() {
                    pk.close();
                }
            };
        }, id -> null, System::currentTimeMillis, 0);
        outputs.snapshots(roomId -> new ProgramOutputs.Snapshot(1, Timeline.IDLE, null, null, null));
        api = new LiveTsApi(token -> switch (token) {
            case NEXT_TOKEN -> new LiveTsApi.Target("room-next", false);
            case LATEST_TOKEN -> new LiveTsApi.Target("room-latest", true);
            case FAILING_TOKEN -> throw new java.sql.SQLException("database down");
            default -> null;
        }, outputs);
        Router router = new Router();
        api.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @AfterEach
    void tearDown() {
        try {
            if (server != null) {
                server.close();
            }
            outputs.close();
        } finally {
            System.setErr(originalErr);
            java.util.logging.Logger.getLogger("").removeHandler(logCapture);
        }
        String err = output();
        for (String token : List.of(NEXT_TOKEN, LATEST_TOKEN, FAILING_TOKEN)) {
            assertFalse(err.contains(token), "a token was logged in full: " + err);
        }
    }

    // --- raw HTTP client ---

    record Head(String statusLine, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    Socket connect() throws IOException {
        Socket s = new Socket("127.0.0.1", server.port());
        s.setSoTimeout(5000);
        return s;
    }

    static void send(Socket s, String method, String path, String... extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n");
        for (String h : extraHeaders) {
            sb.append(h).append("\r\n");
        }
        sb.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.isEmpty()) {
            throw new IOException("EOF");
        }
        return sb.toString();
    }

    static Head readHead(InputStream in) throws IOException {
        String status = readLine(in);
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return new Head(status, headers);
    }

    /** Reads one fake unit and returns its start PTS. */
    static long readUnit(InputStream in) throws IOException {
        byte[] b = in.readNBytes(UNIT_BYTES);
        assertEquals(UNIT_BYTES, b.length, "body ended early");
        return ByteBuffer.wrap(b).getLong();
    }

    PackagerTest.FakeProducer producer() {
        return producers.get(producers.size() - 1);
    }

    Packager packager() {
        return packagers.get(packagers.size() - 1);
    }

    /** Lets the packager emit {@code n} units and waits until they are out. */
    void emit(int n) throws InterruptedException {
        PackagerTest.FakeProducer p = producer();
        Packager pk = packager();
        int target = p.built.get() + n;
        p.permits.release(n);
        await(() -> p.built.get() >= target && pk.latest() != null
                && pk.latest().startPts() == pts(target - 1));
    }

    static long pts(int unit) {
        return OutputClock.START + 90L * unit;
    }

    // --- tests ---

    @Test
    void headIsEmptyLikeVrcdnAndStartsNothing() throws Exception {
        try (Socket s = connect()) {
            send(s, "HEAD", "/live/" + NEXT_TOKEN + ".ts");
            InputStream in = s.getInputStream();
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals("0", head.header("Content-Length"));
            assertEquals(LiveTsApi.CONTENT_TYPE, head.header("Content-Type"));
            assertNull(head.header("Transfer-Encoding"));
            // the connection stays usable: a second HEAD on it works
            send(s, "HEAD", "/live/" + NEXT_TOKEN + ".ts");
            assertEquals("HTTP/1.1 200 OK", readHead(in).statusLine());
        }
        assertTrue(producers.isEmpty(), "HEAD must not start the output");
        assertNull(outputs.running("room-next"));
        assertEquals(0, api.viewers("room-next"));
    }

    @Test
    void unknownOrMalformedTokensAre404() throws Exception {
        for (String path : List.of("/live/AAAAunknownToken000001.ts", "/live/" + NEXT_TOKEN,
                "/live/" + NEXT_TOKEN + ".m3u8", "/live/short.ts", "/live/bad!chars!token!000.ts",
                "/live/.ts")) {
            for (String method : List.of("GET", "HEAD")) {
                try (Socket s = connect()) {
                    send(s, method, path);
                    assertEquals("HTTP/1.1 404 Not Found", readHead(s.getInputStream()).statusLine(),
                            method + " " + path);
                }
            }
        }
        assertTrue(producers.isEmpty());
    }

    @Test
    void lookupFailureIs500WithoutLoggingTheToken() throws Exception {
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + FAILING_TOKEN + ".ts");
            assertEquals("HTTP/1.1 500 Internal Server Error", readHead(s.getInputStream()).statusLine());
        }
        assertTrue(output().contains("CCCC…"), output());
    }

    @Test
    void outputThatCannotStartIs503() throws Exception {
        failStart.set(true);
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + NEXT_TOKEN + ".ts");
            assertEquals("HTTP/1.1 503 Service Unavailable", readHead(s.getInputStream()).statusLine());
        }
        assertEquals(0, outputs.leases("room-next"));
    }

    @Test
    void getIsCloseDelimitedAndJoinsAtTheNextUnit() throws Exception {
        try (Socket s = connect()) {
            // Range is ignored: always 200 from the live edge
            send(s, "GET", "/live/" + NEXT_TOKEN + ".ts", "Range: bytes=100-");
            InputStream in = s.getInputStream();
            // headers arrive before any unit exists
            Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals(LiveTsApi.CONTENT_TYPE, head.header("Content-Type"));
            assertEquals("no-store", head.header("Cache-Control"));
            assertNull(head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            assertEquals("close", head.header("Connection").toLowerCase(Locale.ROOT));
            assertEquals(1, outputs.leases("room-next"));
            await(() -> packager().consumerCount() == 1);
            emit(1);
            assertEquals(pts(0), readUnit(in));
            emit(2);
            assertEquals(pts(1), readUnit(in));
            assertEquals(pts(2), readUnit(in));
        }
    }

    @Test
    void secondViewerJoinsAtTheNextUnitNotTheLatest() throws Exception {
        try (Socket a = connect(); Socket b = connect()) {
            send(a, "GET", "/live/" + NEXT_TOKEN + ".ts");
            readHead(a.getInputStream());
            await(() -> !packagers.isEmpty() && packager().consumerCount() == 1);
            emit(2);
            assertEquals(pts(0), readUnit(a.getInputStream()));
            assertEquals(pts(1), readUnit(a.getInputStream()));
            send(b, "GET", "/live/" + NEXT_TOKEN + ".ts");
            readHead(b.getInputStream());
            await(() -> packager().consumerCount() == 2);
            emit(1);
            assertEquals(pts(2), readUnit(a.getInputStream()));
            assertEquals(pts(2), readUnit(b.getInputStream()));
            assertEquals(1, producers.size(), "one output shared by both");
        }
    }

    @Test
    void joinLatestRoomSettingSendsTheMostRecentUnitAtOnce() throws Exception {
        outputs.acquire("room-latest").close(); // start it, as an earlier viewer would have
        emit(3);
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + LATEST_TOKEN + ".ts");
            InputStream in = s.getInputStream();
            readHead(in);
            assertEquals(pts(2), readUnit(in));
            await(() -> packager().consumerCount() == 1);
            emit(1);
            assertEquals(pts(3), readUnit(in));
        }
    }

    @Test
    void countsAsViewerOnlyAfterTwoUnitsAndProbesNever() throws Exception {
        // a probe: reads the headers and a few bytes, then goes away
        try (Socket probe = connect()) {
            send(probe, "GET", "/live/" + NEXT_TOKEN + ".ts");
            readHead(probe.getInputStream());
            await(() -> !packagers.isEmpty() && packager().consumerCount() == 1);
            emit(1);
            assertEquals(16, probe.getInputStream().readNBytes(16).length);
        }
        assertEquals(0, api.viewers("room-next"));
        // the probe's connection ends at the next writes; its lease and queue go
        for (int i = 0; i < 50 && outputs.leases("room-next") > 0; i++) {
            emit(1);
            Thread.sleep(10);
        }
        assertEquals(0, outputs.leases("room-next"));
        await(() -> packager().consumerCount() == 0);
        assertEquals(0, api.viewers("room-next"), "a probe never counts as a viewer");

        Socket viewer = connect();
        try {
            send(viewer, "GET", "/live/" + NEXT_TOKEN + ".ts");
            InputStream in = viewer.getInputStream();
            readHead(in);
            await(() -> packager().consumerCount() == 1);
            emit(1);
            readUnit(in);
            assertEquals(0, api.viewers("room-next"));
            emit(1);
            readUnit(in);
            await(() -> api.viewers("room-next") == 1);
        } finally {
            viewer.close();
        }
        for (int i = 0; i < 50 && api.viewers("room-next") > 0; i++) {
            emit(1);
            Thread.sleep(10);
        }
        assertEquals(0, api.viewers("room-next"));
        assertEquals(0, outputs.leases("room-next"));
    }

    @Test
    void bodyEndsWhenTheOutputStops() throws Exception {
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + NEXT_TOKEN + ".ts");
            InputStream in = s.getInputStream();
            readHead(in);
            await(() -> !packagers.isEmpty() && packager().consumerCount() == 1);
            emit(1);
            assertEquals(pts(0), readUnit(in));
            outputs.stop("room-next");
            assertEquals(-1, in.read(), "the close-delimited body ends with the connection");
        }
        await(() -> outputs.leases("room-next") == 0);
        String err = output();
        assertFalse(err.contains("Exception"), "something logged an exception: " + err);
    }

    @Test
    @org.junit.jupiter.api.Disabled("needs a SiggiHTTP abort for a response whose writer is blocked"
            + " (requested from the SiggiHTTP session 2026-11-10); fails today: the lease stays held")
    void stalledClientIsDisconnectedWhenItsQueueOverflows() throws Exception {
        unitBytes = 4 * 1024 * 1024; // far more than the socket buffers hold
        try (Socket s = new Socket()) {
            s.setReceiveBufferSize(4096);
            s.connect(new java.net.InetSocketAddress("127.0.0.1", server.port()));
            s.setSoTimeout(5000);
            send(s, "GET", "/live/" + NEXT_TOKEN + ".ts");
            // never reads: the writer blocks in the first unit, the queue fills and overflows
            await(() -> !packagers.isEmpty() && packager().consumerCount() == 1);
            emit(ConsumerQueue.DEFAULT_CAPACITY + 2);
            await(() -> packager().consumerCount() == 0); // dropped by the packager
            // the connection is ended although its writer was blocked: lease freed, body ends
            await(() -> outputs.leases("room-next") == 0);
            InputStream in = s.getInputStream();
            try {
                while (in.read(new byte[65536]) != -1) {
                    // drain what was in flight
                }
            } catch (java.net.SocketException reset) {
                // a reset is fine too
            }
        }
        assertEquals(0, api.viewers("room-next"));
    }

    /** Copies everything to two streams (so a test can read stderr). */
    static final class TeeStream extends OutputStream {
        final OutputStream a;
        final OutputStream b;

        TeeStream(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public synchronized void write(int c) throws IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public synchronized void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }
    }
}
