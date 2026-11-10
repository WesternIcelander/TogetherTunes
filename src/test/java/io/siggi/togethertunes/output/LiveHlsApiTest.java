package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.LiveTsApiTest.readHead;
import static io.siggi.togethertunes.output.LiveTsApiTest.send;
import static io.siggi.togethertunes.output.PackagerTest.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.rooms.Timeline;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /live/{token}/index.m3u8} and {@code /live/{token}/seg/{seq}.ts} over raw sockets:
 * headers, the playlist from the ring, segments, the wait for a fresh output's first segment,
 * restarts, and that tokens never reach the logs.
 */
class LiveHlsApiTest {
    static final String TOKEN = "HHHHhlsOutputToken0001";
    static final String FAILING_TOKEN = "CCCCfailingLookup00001";
    static final int UNIT_BYTES = 1880;

    @TempDir
    Path dir;

    final List<PackagerTest.FakeProducer> producers = Collections.synchronizedList(new ArrayList<>());
    final List<Packager> packagers = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean failStart = new AtomicBoolean();
    final AtomicLong now = new AtomicLong(1_000_000);
    ProgramOutputs outputs;
    LiveHlsApi api;
    WebServer server;
    PrintStream originalErr;
    final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
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

    String output() {
        return capturedErr.toString(StandardCharsets.UTF_8) + logged;
    }

    @BeforeEach
    void setUp() throws IOException {
        originalErr = System.err;
        System.setErr(new PrintStream(new LiveTsApiTest.TeeStream(originalErr, capturedErr), true));
        java.util.logging.Logger.getLogger("").addHandler(logCapture);
        outputs = new ProgramOutputs((roomId, cue) -> {
            if (failStart.get()) {
                throw new IOException("no filler cards");
            }
            TimeSource time = TimeSource.system(Clock.systemUTC());
            PackagerTest.FakeProducer p = new PackagerTest.FakeProducer(time, 90); // 1 ms units
            Packager pk = new Packager(roomId, p,
                    u -> ByteBuffer.allocate(UNIT_BYTES).putLong(u.startPts()).array(), new PacingClock(time), time);
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
        }, id -> null, now::get, 0);
        outputs.snapshots(roomId -> new ProgramOutputs.Snapshot(1, Timeline.IDLE, null, null, null));
        api = new LiveHlsApi(token -> switch (token) {
            case TOKEN -> "room-hls";
            case FAILING_TOKEN -> throw new java.sql.SQLException("database down");
            default -> null;
        }, outputs, now::get);
        Router router = new Router();
        api.register(router);
        new LiveTsApi(token -> null, outputs).register(router); // routes side by side, as in Main
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
        for (String token : List.of(TOKEN, FAILING_TOKEN)) {
            assertFalse(err.contains(token), "a token was logged in full: " + err);
        }
    }

    record Reply(LiveTsApiTest.Head head, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    Reply fetch(String method, String path) throws IOException {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(10_000);
            send(s, method, path);
            InputStream in = s.getInputStream();
            LiveTsApiTest.Head head = readHead(in);
            String cl = head.header("Content-Length");
            assertNotNull(cl, "Content-Length on " + path);
            assertNull(head.header("Transfer-Encoding"));
            byte[] body = method.equals("HEAD") ? new byte[0] : in.readNBytes(Integer.parseInt(cl));
            return new Reply(head, body);
        }
    }

    PackagerTest.FakeProducer producer() {
        return producers.get(producers.size() - 1);
    }

    Packager packager() {
        return packagers.get(packagers.size() - 1);
    }

    HlsSegmenter segmenter() {
        return api.segmenter("room-hls");
    }

    /** Lets the packager emit {@code n} units and waits until the segmenter has seen them. */
    void emit(int n) throws InterruptedException {
        PackagerTest.FakeProducer p = producer();
        Packager pk = packager();
        int target = p.built.get() + n;
        p.permits.release(n);
        await(() -> p.built.get() >= target && pk.latest() != null && pk.latest().sequence() == target - 1);
        HlsSegmenter h = segmenter();
        // the segment for unit target-2 exists once unit target-1 was delivered (first packager only:
        // its unit numbers are the segment numbers)
        await(() -> h.segment(target - 2) != null);
    }

    static List<String> uris(String playlist) {
        return playlist.lines().filter(l -> !l.startsWith("#")).toList();
    }

    @Test
    void playlistAndSegmentsWithTheirHeaders() throws Exception {
        // the first request starts the output and waits for the first segment
        Thread emitter = Thread.ofVirtual().start(() -> {
            try {
                await(() -> !producers.isEmpty() && segmenter() != null && segmenter().attachedTo(packager()));
                producer().permits.release(3);
            } catch (InterruptedException e) {
                // test ends
            }
        });
        Reply r = fetch("GET", "/live/" + TOKEN + "/index.m3u8");
        emitter.join();
        assertEquals("HTTP/1.1 200 OK", r.head().statusLine());
        assertEquals(LiveHlsApi.PLAYLIST_TYPE, r.head().header("Content-Type"));
        assertEquals("no-cache", r.head().header("Cache-Control"));
        String pl = r.text();
        assertTrue(pl.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"), pl);
        assertTrue(pl.contains("#EXTINF:0.001,\nseg/0.ts\n"), pl);
        assertEquals(1, packagers.size());

        emit(10);
        pl = fetch("GET", "/live/" + TOKEN + "/index.m3u8").text();
        List<String> uris = uris(pl);
        assertEquals(HlsSegmenter.LISTED, uris.size(), pl);
        assertEquals(HlsSegmenter.RING, segmenter().size());
        long last = Long.parseLong(uris.get(5).substring(4, uris.get(5).length() - 3));
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:" + (last - 5) + "\n"), pl);

        Reply seg = fetch("GET", "/live/" + TOKEN + "/" + uris.get(5));
        assertEquals("HTTP/1.1 200 OK", seg.head().statusLine());
        assertEquals(LiveHlsApi.SEGMENT_TYPE, seg.head().header("Content-Type"));
        assertEquals("no-cache", seg.head().header("Cache-Control"));
        assertEquals(Integer.toString(UNIT_BYTES), seg.head().header("Content-Length"));
        assertArrayEquals(segmenter().segment(last).ts(), seg.body());
        // 2 more kept beyond the listed ones
        assertEquals(200, status(fetch("GET", "/live/" + TOKEN + "/seg/" + (last - 7) + ".ts")));
        assertEquals(404, status(fetch("GET", "/live/" + TOKEN + "/seg/" + (last - 8) + ".ts")));
        assertEquals(404, status(fetch("GET", "/live/" + TOKEN + "/seg/" + (last + 1) + ".ts")));
        // HEAD: the same headers, no body
        Reply head = fetch("HEAD", "/live/" + TOKEN + "/seg/" + last + ".ts");
        assertEquals(200, status(head));
        assertEquals(Integer.toString(UNIT_BYTES), head.head().header("Content-Length"));
        Reply headPl = fetch("HEAD", "/live/" + TOKEN + "/index.m3u8");
        assertEquals(200, status(headPl));
        assertEquals(LiveHlsApi.PLAYLIST_TYPE, headPl.head().header("Content-Type"));
        assertEquals(1, packagers.size(), "fetches reuse the running output");
        assertEquals(0, outputs.leases("room-hls"), "HLS fetches are activity, not leases");
    }

    static int status(Reply r) {
        return Integer.parseInt(r.head().statusLine().split(" ")[1]);
    }

    @Test
    void badPathsAndTokensAre404AndStartNothing() throws Exception {
        for (String path : List.of("/live/AAAAunknownToken000001/index.m3u8", "/live/short/index.m3u8",
                "/live/" + TOKEN + "/index.m3u", "/live/" + TOKEN + "/seg/abc.ts", "/live/" + TOKEN + "/seg/-1.ts",
                "/live/" + TOKEN + "/seg/01.ts", "/live/" + TOKEN + "/seg/1.m4s",
                "/live/" + TOKEN + "/seg/99999999999999999999.ts", "/live/" + TOKEN + ".ts")) {
            try (Socket s = new Socket("127.0.0.1", server.port())) {
                s.setSoTimeout(5000);
                send(s, "GET", path);
                assertEquals("HTTP/1.1 404 Not Found", readHead(s.getInputStream()).statusLine(), path);
            }
        }
        assertTrue(producers.isEmpty());
    }

    @Test
    void lookupFailureIs500AndStartFailureIs503() throws Exception {
        assertEquals(500, status(fetch("GET", "/live/" + FAILING_TOKEN + "/index.m3u8")));
        assertTrue(output().contains("CCCC…"), output());
        failStart.set(true);
        assertEquals(503, status(fetch("GET", "/live/" + TOKEN + "/index.m3u8")));
        assertEquals(503, status(fetch("GET", "/live/" + TOKEN + "/seg/0.ts")));
    }

    @Test
    void noSegmentInTimeIs503WithRetryAfter() throws Exception {
        long t0 = System.nanoTime();
        Reply r = fetch("GET", "/live/" + TOKEN + "/index.m3u8"); // the producer never builds a unit
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(503, status(r));
        assertEquals("1", r.head().header("Retry-After"));
        assertTrue(waitedMs >= LiveHlsApi.FIRST_SEGMENT_WAIT_MS - 100, "waited " + waitedMs + " ms");
    }

    @Test
    void restartedOutputContinuesTheSequence() throws Exception {
        producerReady();
        emit(4);
        String before = fetch("GET", "/live/" + TOKEN + "/index.m3u8").text();
        List<String> u1 = uris(before);
        long lastBefore = Long.parseLong(u1.get(u1.size() - 1).substring(4).replace(".ts", ""));
        // the output fails and is restarted by the sweep-free path: the next fetch
        Packager old = packager();
        producer().failWith = new IOException("disk gone");
        producer().permits.release(1);
        await(() -> !old.isRunning());
        assertEquals(0, segmenter().size(), "the ring empties when the packager stops");
        Thread emitter = Thread.ofVirtual().start(() -> {
            try {
                await(() -> packagers.size() == 2 && segmenter().attachedTo(packager()));
                producer().permits.release(3);
            } catch (InterruptedException e) {
                // test ends
            }
        });
        String after = fetch("GET", "/live/" + TOKEN + "/index.m3u8").text();
        emitter.join();
        assertEquals(2, packagers.size());
        assertTrue(after.contains("#EXT-X-MEDIA-SEQUENCE:" + (lastBefore + 1) + "\n"), after);
        assertTrue(after.contains("#EXT-X-DISCONTINUITY\n#EXTINF:0.001,\nseg/" + (lastBefore + 1) + ".ts\n"), after);
    }

    @Test
    void restartByAnotherConsumerReattachesOnlyWhileHlsIsFetched() throws Exception {
        producerReady();
        emit(2);
        Packager first = packager();
        outputs.stop("room-hls");
        await(() -> !first.isRunning());
        // a .ts viewer (lease) restarts it: HLS was fetched just now, so the ring follows
        try (ProgramOutputs.Lease lease = outputs.acquire("room-hls")) {
            assertTrue(segmenter().attachedTo(lease.packager()));
        }
        outputs.stop("room-hls");
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS + 1);
        try (ProgramOutputs.Lease lease = outputs.acquire("room-hls")) {
            assertFalse(segmenter().attachedTo(lease.packager()), "no HLS fetch for a minute: no ring");
        }
    }

    /** Starts the output with one HLS fetch (that gets its first segment). */
    void producerReady() throws Exception {
        Thread emitter = Thread.ofVirtual().start(() -> {
            try {
                await(() -> !producers.isEmpty() && segmenter() != null && segmenter().attachedTo(packager()));
                producer().permits.release(2);
            } catch (InterruptedException e) {
                // test ends
            }
        });
        assertEquals(200, status(fetch("GET", "/live/" + TOKEN + "/index.m3u8")));
        emitter.join();
    }
}
