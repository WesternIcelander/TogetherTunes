package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.LiveTsApiTest.readHead;
import static io.siggi.togethertunes.output.LiveTsApiTest.send;
import static io.siggi.togethertunes.output.PackagerTest.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET}/{@code HEAD /live/{token}.mp3} over raw sockets with a real ffmpeg encode: headers,
 * close-delimited framing, MP3 frames from the first byte, ICY metadata at exactly
 * {@code icy-metaint} with the title change, one encoder per room, limits, revocation, and that
 * tokens never reach the logs.
 */
class LiveMp3ApiTest {
    static final String TOKEN = "MMMMmp3StreamToken0001";
    static final String OTHER = "NNNNmp3StreamToken0002";

    @TempDir
    static Path mediaDir;
    @TempDir
    Path dir;
    static Tools tools;
    static List<TsUnit> units;

    final AtomicBoolean revoked = new AtomicBoolean();
    final List<Mp3TestMedia.FakeFeed> feeds = new CopyOnWriteArrayList<>();
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
    Mp3Encoders encoders;
    LiveMp3Api api;
    WebServer server;

    @BeforeAll
    static void media() throws Exception {
        tools = Mp3TestMedia.tools();
        units = Mp3TestMedia.units(Mp3TestMedia.ts(tools, mediaDir, 12),
                "item-a", "item-a", "item-b", "item-b", "item-b", "item-b");
    }

    LiveMp3Api.Target lookup(String token) {
        if (token.equals(TOKEN) && !revoked.get()) {
            return new LiveMp3Api.Target("room-1", "Club Rööm\r\nX-Evil: 1");
        }
        if (token.equals(OTHER)) {
            return new LiveMp3Api.Target("room-2", "Other");
        }
        return null;
    }

    WebServer start(LiveMp3Api mp3) throws IOException {
        LiveTsApi ts = new LiveTsApi(token -> null, new ProgramOutputs((r, c) -> {
            throw new IOException("no .ts here");
        }, id -> null, System::currentTimeMillis, 0));
        mp3.register(ts);
        Router router = new Router();
        ts.register(router);
        return WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @BeforeEach
    void setUp() throws IOException {
        java.util.logging.Logger.getLogger("").addHandler(logCapture);
        encoders = new Mp3Encoders((roomId, onIdle) -> {
            Mp3TestMedia.FakeFeed feed = new Mp3TestMedia.FakeFeed(false, List.of(units));
            feeds.add(feed);
            return new Mp3Encoder(roomId, tools.ffmpegExecutable(), () -> 128, feed, Mp3TestMedia::title,
                    RtmpPusher.processLauncher(), Thread::sleep, System::currentTimeMillis, onIdle);
        }, 60_000);
        api = new LiveMp3Api(this::lookup, encoders, () -> 96, LiveLimits.noIpLimit());
        server = start(api);
    }

    @AfterEach
    void tearDown() {
        try {
            server.close();
            encoders.close();
        } finally {
            java.util.logging.Logger.getLogger("").removeHandler(logCapture);
        }
        for (String token : List.of(TOKEN, OTHER)) {
            assertFalse(logged.toString().contains(token), "a token was logged in full: " + logged);
        }
    }

    Socket connect(WebServer ws) throws IOException {
        Socket s = new Socket("127.0.0.1", ws.port());
        s.setSoTimeout(10_000);
        return s;
    }

    Socket connect() throws IOException {
        return connect(server);
    }

    static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = in.readNBytes(n);
        assertEquals(n, b.length, "body ended early");
        return b;
    }

    @Test
    void getIsCloseDelimitedPlainMp3FromAFrameBoundary() throws Exception {
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + TOKEN + ".mp3", "Range: bytes=100-");
            InputStream in = s.getInputStream();
            LiveTsApiTest.Head head = readHead(in);
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals("audio/mpeg", head.header("Content-Type"));
            assertNull(head.header("Content-Length"));
            assertNull(head.header("Transfer-Encoding"));
            assertEquals("close", head.header("Connection").toLowerCase(Locale.ROOT));
            assertEquals("Club RoomX-Evil: 1", head.header("icy-name"), "ASCII only, no line breaks");
            assertNull(head.header("X-Evil"));
            assertEquals("128", head.header("icy-br"));
            assertEquals("0", head.header("icy-pub"));
            assertNull(head.header("icy-metaint"), "no metadata unless asked for");
            byte[] mp3 = read(in, 64_000);
            int frames = Mp3TestMedia.assertWholeFrames(mp3, 63_000);
            assertTrue(frames > 140, "frames: " + frames);
            assertEquals(1, encoders.listeners("room-1"));
            assertEquals(1, api.connections());
        }
    }

    @Test
    void icyMetadataComesEveryMetaintBytesWithTheTitleChange() throws Exception {
        try (Socket s = connect()) {
            send(s, "GET", "/live/" + TOKEN + ".mp3", "Icy-MetaData: 1");
            InputStream in = s.getInputStream();
            LiveTsApiTest.Head head = readHead(in);
            assertEquals("16000", head.header("icy-metaint"));
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            List<String> titles = new ArrayList<>();
            for (int block = 0; block < 9; block++) {
                audio.writeBytes(read(in, IcyWriter.METAINT));
                int len = in.read() * 16;
                byte[] text = read(in, len);
                titles.add(new String(text, StandardCharsets.UTF_8).replace("\0", ""));
            }
            byte[] mp3 = audio.toByteArray();
            Mp3TestMedia.assertWholeFrames(mp3, mp3.length - 500);
            assertEquals("StreamTitle='Artist A - Song A';", titles.get(0), titles.toString());
            int change = titles.indexOf("StreamTitle='Artist B - Song B';");
            // B starts 4 s in: 64000 bytes, so in the block after 4 or 5 s of audio (index 3..4)
            assertTrue(change >= 3 && change <= 4, titles.toString());
            for (int i = 0; i < titles.size(); i++) {
                if (i != 0 && i != change) {
                    assertEquals("", titles.get(i), "unchanged titles aren't repeated: " + titles);
                }
            }
        }
    }

    @Test
    void listenersOfARoomShareOneEncoder() throws Exception {
        try (Socket a = connect(); Socket b = connect()) {
            send(a, "GET", "/live/" + TOKEN + ".mp3");
            readHead(a.getInputStream());
            read(a.getInputStream(), 20_000);
            send(b, "GET", "/live/" + TOKEN + ".mp3");
            readHead(b.getInputStream());
            byte[] late = read(b.getInputStream(), 20_000);
            Mp3TestMedia.assertWholeFrames(late, 19_000);
            assertEquals(1, feeds.size(), "one encoder for both");
            assertEquals(2, encoders.listeners("room-1"));
        }
    }

    @Test
    void headSendsTheHeadersAndStartsNothing() throws Exception {
        try (Socket s = connect()) {
            send(s, "HEAD", "/live/" + TOKEN + ".mp3", "Icy-MetaData: 1");
            LiveTsApiTest.Head head = readHead(s.getInputStream());
            assertEquals("HTTP/1.1 200 OK", head.statusLine());
            assertEquals("audio/mpeg", head.header("Content-Type"));
            assertEquals("96", head.header("icy-br"), "the setting, as no encoder runs");
            assertEquals("16000", head.header("icy-metaint"));
            assertNull(head.header("Content-Length"));
        }
        assertTrue(feeds.isEmpty());
        assertFalse(encoders.running("room-1"));
    }

    @Test
    void unknownMalformedOrSwitchedOffTokensAre404() throws Exception {
        revoked.set(true); // as with the server switch off: the lookup finds nothing
        for (String path : List.of("/live/" + TOKEN + ".mp3", "/live/AAAAunknownToken000001.mp3",
                "/live/short.mp3", "/live/bad!chars!token!000.mp3", "/live/.mp3")) {
            for (String method : List.of("GET", "HEAD")) {
                try (Socket s = connect()) {
                    send(s, method, path);
                    assertEquals("HTTP/1.1 404 Not Found", readHead(s.getInputStream()).statusLine(),
                            method + " " + path);
                }
            }
        }
        assertTrue(feeds.isEmpty());
    }

    @Test
    void withoutFfmpegGetIs503() throws Exception {
        WebServer ws = start(new LiveMp3Api(this::lookup, null, () -> 128, LiveLimits.noIpLimit()));
        try (Socket s = connect(ws)) {
            send(s, "GET", "/live/" + TOKEN + ".mp3");
            assertEquals("HTTP/1.1 503 Service Unavailable", readHead(s.getInputStream()).statusLine());
        } finally {
            ws.close();
        }
    }

    @Test
    void revokedTokenEndsItsConnections() throws Exception {
        try (Socket a = connect(); Socket b = connect()) {
            send(a, "GET", "/live/" + TOKEN + ".mp3");
            send(b, "GET", "/live/" + OTHER + ".mp3");
            readHead(a.getInputStream());
            readHead(b.getInputStream());
            await(() -> api.connections() == 2);
            assertEquals(0, api.revalidate());
            revoked.set(true); // rotated, turned off, or the server switch off
            assertEquals(1, api.revalidate());
            InputStream in = a.getInputStream();
            boolean ended = false;
            try {
                long until = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < until) {
                    if (in.read(new byte[65536]) == -1) {
                        ended = true;
                        break;
                    }
                }
            } catch (IOException e) {
                ended = true; // reset
            }
            assertTrue(ended, "the revoked connection ends");
            await(() -> encoders.listeners("room-1") == 0);
            assertEquals(1, encoders.listeners("room-2"), "the other room's listener stays");
            read(b.getInputStream(), 10_000);
        }
    }

    @Test
    void connectionsPerTokenAreCapped() throws Exception {
        LiveLimits limits = new LiveLimits(null, 1);
        WebServer ws = start(new LiveMp3Api(this::lookup, encoders, () -> 128, limits));
        try (Socket a = connect(ws)) {
            send(a, "GET", "/live/" + TOKEN + ".mp3");
            assertEquals("HTTP/1.1 200 OK", readHead(a.getInputStream()).statusLine());
            try (Socket b = connect(ws)) {
                send(b, "GET", "/live/" + TOKEN + ".mp3");
                LiveTsApiTest.Head head = readHead(b.getInputStream());
                assertEquals("HTTP/1.1 429 Too Many Requests", head.statusLine());
            }
        } finally {
            ws.close();
        }
    }

    @Test
    void requestsArePerIpRateLimited() throws Exception {
        io.siggi.togethertunes.TestClock clock = new io.siggi.togethertunes.TestClock(1_800_000_000_000L);
        LiveLimits limits = new LiveLimits(new io.siggi.togethertunes.http.RateLimiter("live", 2, 1000, clock), 64);
        WebServer ws = start(new LiveMp3Api(this::lookup, encoders, () -> 128, limits));
        try {
            for (String path : List.of("/live/" + TOKEN + ".mp3", "/live/AAAAunknownToken000001.mp3")) {
                try (Socket s = connect(ws)) {
                    send(s, "HEAD", path);
                    assertTrue(readHead(s.getInputStream()).statusLine().matches("HTTP/1.1 (200|404) .*"));
                }
            }
            try (Socket s = connect(ws)) {
                send(s, "HEAD", "/live/" + TOKEN + ".mp3");
                assertEquals("HTTP/1.1 429 Too Many Requests", readHead(s.getInputStream()).statusLine());
            }
        } finally {
            ws.close();
        }
    }

    @Test
    void icyName() {
        assertEquals("Cafe Muller", LiveMp3Api.icyName("Café Müller"));
        assertEquals("ab", LiveMp3Api.icyName("a\r\nb"));
        assertEquals("", LiveMp3Api.icyName(null));
    }
}
