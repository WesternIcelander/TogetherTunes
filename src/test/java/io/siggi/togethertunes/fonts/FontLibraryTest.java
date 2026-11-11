package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.settings.CjkVariant;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The font library ({@link FontLibrary}) against a local HTTP server: downloads with the hash and
 * size checks, only manifest URLs, deduplication, retries, on-demand downloads, the cards re-made
 * after a download, uploads and the card font setting.
 */
class FontLibraryTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    /** Requests per path. */
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Failures still to answer with 500 on /flaky.ttf. */
    private final AtomicInteger flakyFailures = new AtomicInteger();
    /** Held while set: /slow.ttf waits for it. */
    private final AtomicReference<CountDownLatch> slowGate = new AtomicReference<>();
    private final AtomicBoolean auto = new AtomicBoolean(true);
    private final AtomicReference<String> cardFont = new AtomicReference<>();
    private FontLibrary library;

    @BeforeEach
    void setUp() throws IOException {
        byte[] thai = TestFonts.thai();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            try (exchange) {
                switch (path) {
                    case "/thai.ttf", "/slow.ttf" -> {
                        CountDownLatch gate = slowGate.get();
                        if (path.equals("/slow.ttf") && gate != null) {
                            try {
                                gate.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        send(exchange, 200, thai);
                    }
                    case "/flaky.ttf" -> {
                        if (flakyFailures.getAndDecrement() > 0) {
                            send(exchange, 500, new byte[0]);
                        } else {
                            send(exchange, 200, thai);
                        }
                    }
                    case "/big.ttf" -> {
                        byte[] more = new byte[thai.length + 100];
                        System.arraycopy(thai, 0, more, 0, thai.length);
                        send(exchange, 200, more);
                    }
                    case "/chunked.ttf" -> {
                        // no Content-Length: the limit applies while reading
                        exchange.sendResponseHeaders(200, 0);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(thai);
                            out.write(new byte[100]);
                        }
                    }
                    case "/redirect.ttf" -> {
                        exchange.getResponseHeaders().add("Location", base + "/elsewhere.ttf");
                        send(exchange, 302, new byte[0]);
                    }
                    case "/elsewhere.ttf" -> send(exchange, 200, thai);
                    default -> send(exchange, 404, new byte[0]);
                }
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (library != null) {
            library.close();
        }
        server.stop(0);
        CardFonts.install(CardFonts.bundledOnly()); // the chain is process-wide
    }

    private FontManifest manifest() {
        byte[] thai = TestFonts.thai();
        String wrongHash = "0".repeat(64);
        return FontManifest.of(List.of(
                TestFonts.entry("thai", "NotoSansThai-Regular.ttf", thai, base + "/thai.ttf",
                        Character.UnicodeScript.THAI),
                new FontManifest.Entry("badhash", "Bad hash", "Bad.ttf", thai.length, wrongHash,
                        java.net.URI.create(base + "/thai.ttf"), "OFL-notofonts.txt", Set.of(), null, new int[0]),
                TestFonts.entry("big", "Big.ttf", thai, base + "/big.ttf"),
                TestFonts.entry("chunked", "Chunked.ttf", thai, base + "/chunked.ttf"),
                TestFonts.entry("redirect", "Redirect.ttf", thai, base + "/redirect.ttf"),
                TestFonts.entry("flaky", "Flaky.ttf", thai, base + "/flaky.ttf"),
                TestFonts.entry("slow", "Slow.ttf", thai, base + "/slow.ttf")));
    }

    private FontLibrary library(FontManifest manifest) throws IOException {
        library = new FontLibrary(dir.resolve("fonts"), manifest, new FontDownloader(), SystemFonts.NONE,
                new FontLibrary.Prefs() {
                    @Override
                    public String cardFont() {
                        return cardFont.get();
                    }

                    @Override
                    public boolean autoDownload() {
                        return auto.get();
                    }

                    @Override
                    public CjkVariant cjkVariant() {
                        return CjkVariant.JP;
                    }
                }, attempt -> 20, Clock.systemUTC());
        library.start();
        return library;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private FontLibrary.State state(String id) {
        return library.status(id).state();
    }

    private int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    @Test
    void aDownloadIsCheckedInstalledAndChangesTheChain() throws Exception {
        library(manifest());
        AtomicInteger changes = new AtomicInteger();
        library.changeListener(changes::incrementAndGet);
        String before = library.current().id();
        assertEquals(FontLibrary.State.NOT_DOWNLOADED, state("thai"));
        assertTrue(library.download("thai", true));
        await(() -> state("thai") == FontLibrary.State.INSTALLED, "the Thai font");
        Path fonts = dir.resolve("fonts");
        assertTrue(Files.isRegularFile(fonts.resolve("NotoSansThai-Regular.ttf")));
        assertTrue(Files.readString(fonts.resolve("OFL-notofonts.txt")).contains("SIL Open Font License"),
                "the licence goes with the font");
        assertNotEquals(before, library.current().id());
        assertEquals(1, changes.get());
        assertTrue(library.current().uncovered("สวัสดี").isEmpty());
        assertEquals(library.current().id(), CardFonts.installed().id(), "installed for every renderer");
        // A new library finds it again at startup, after checking its hash.
        library.close();
        library(manifest());
        assertEquals(FontLibrary.State.INSTALLED, state("thai"));
        assertTrue(library.current().uncovered("สวัสดี").isEmpty());
        // Removed: gone from disk and the chain.
        assertTrue(library.remove("thai"));
        assertFalse(Files.exists(fonts.resolve("NotoSansThai-Regular.ttf")));
        assertFalse(library.current().uncovered("สวัสดี").isEmpty());
    }

    @Test
    void aFileWithTheWrongHashOrSizeIsNeverUsed() throws Exception {
        library(manifest());
        library.download("badhash", true);
        await(() -> library.status("badhash").attempts() >= FontLibrary.MAX_ATTEMPTS, "all attempts");
        FontLibrary.FontStatus s = library.status("badhash");
        assertEquals(FontLibrary.State.FAILED, s.state());
        assertTrue(s.error().contains("SHA-256"), s.error());
        assertNull(s.retryAtMs(), "no retry after the last attempt");
        assertFalse(Files.exists(dir.resolve("fonts").resolve("Bad.ttf")));
        library.download("big", true);
        await(() -> library.status("big").state() == FontLibrary.State.FAILED, "the oversized font");
        assertTrue(library.status("big").error().contains("bytes"), library.status("big").error());
        assertFalse(Files.exists(dir.resolve("fonts").resolve("Big.ttf")));
        library.download("chunked", true);
        await(() -> library.status("chunked").state() == FontLibrary.State.FAILED, "the oversized chunked font");
        assertTrue(library.status("chunked").error().contains("more than"), library.status("chunked").error());
        try (var files = Files.list(dir.resolve("fonts"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "nothing left behind");
        }
        // A tampered file on disk is deleted at startup.
        library.close();
        Files.write(dir.resolve("fonts").resolve("NotoSansThai-Regular.ttf"), new byte[] {1, 2, 3});
        library(manifest());
        assertEquals(FontLibrary.State.NOT_DOWNLOADED, state("thai"));
        assertFalse(Files.exists(dir.resolve("fonts").resolve("NotoSansThai-Regular.ttf")));
    }

    @Test
    void onlyManifestUrlsAreFetched() throws Exception {
        library(manifest());
        assertFalse(library.download("https://example.com/font.ttf", true), "an id, never a URL");
        assertFalse(library.download("nope", true));
        library.download("redirect", true);
        await(() -> library.status("redirect").state() == FontLibrary.State.FAILED, "the redirect");
        assertTrue(library.status("redirect").error().contains("302"), library.status("redirect").error());
        assertEquals(0, hits("/elsewhere.ttf"), "redirects are not followed");
        assertThrows(IllegalArgumentException.class, () -> new FontDownloader(
                java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build(),
                java.time.Duration.ofSeconds(5)));
    }

    @Test
    void downloadsAreDeduplicatedAndRunOneAtATime() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        slowGate.set(gate);
        library(manifest());
        library.download("slow", true);
        await(() -> state("slow") == FontLibrary.State.DOWNLOADING, "the slow download");
        library.download("slow", true);
        library.download("slow", false);
        library.download("thai", true);
        assertEquals(FontLibrary.State.QUEUED, state("thai"), "waits for the running download");
        assertThrows(IllegalStateException.class, () -> library.remove("slow"));
        gate.countDown();
        await(() -> state("thai") == FontLibrary.State.INSTALLED && state("slow") == FontLibrary.State.INSTALLED,
                "both");
        assertEquals(1, hits("/slow.ttf"));
        assertEquals(1, hits("/thai.ttf"));
    }

    @Test
    void aFailedDownloadIsRetriedWithBackoff() throws Exception {
        flakyFailures.set(2);
        library(manifest());
        library.download("flaky", false);
        await(() -> state("flaky") == FontLibrary.State.INSTALLED, "the retried download");
        assertEquals(3, hits("/flaky.ttf"));
        assertEquals(0, library.status("flaky").attempts());
    }

    @Test
    void aCardWithAnUncoveredScriptQueuesItsFontAndIsMadeAgainWhenItLands() throws Exception {
        library(manifest());
        CardRenderer.ItemCard card = new CardRenderer.ItemCard("เพลงไทย", "Artist", null, null);
        String keyBefore = CardRenderer.fontKey(CardFonts.installed(), card.title(), card.artist(), card.album());
        List<String> keysAfter = new ArrayList<>();
        library.changeListener(() -> keysAfter.add(CardRenderer.fontKey(CardFonts.installed(), card.title(),
                card.artist(), card.album())));
        auto.set(false);
        new CardRenderer(OutputProfile.P720_30).renderItem(card);
        assertEquals(FontLibrary.State.NOT_DOWNLOADED, state("thai"), "fonts.autoDownload off");
        auto.set(true);
        // Rendered with what is available now (boxes), and the Thai font queued.
        new CardRenderer(OutputProfile.P720_30).renderItem(card);
        await(() -> state("thai") == FontLibrary.State.INSTALLED, "the on-demand download");
        await(() -> !keysAfter.isEmpty(), "the change listener");
        assertNotEquals(keyBefore, keysAfter.get(0), "the card is made again with the Thai font");
        assertEquals(CardRenderer.fontKey(CardFonts.installed(), "Latin", "Artist", null),
                CardRenderer.fontKey(CardFonts.bundledOnly(), "Latin", "Artist", null), "Latin cards stay");
        assertEquals(1, hits("/thai.ttf"));
    }

    @Test
    void uncoveredCharactersAreListedWithTheirFont() throws Exception {
        library(manifest());
        List<FontLibrary.Uncovered> list = library.uncovered(c -> {
            c.accept("สวัสดี");
            c.accept("สวย");
            c.accept("Plain");
            c.accept(null);
        });
        assertFalse(list.isEmpty());
        for (FontLibrary.Uncovered u : list) {
            assertEquals("THAI", u.script());
            assertEquals("thai", u.fontId());
        }
        // ส and ว are in both texts (counted per text), so they come first.
        assertEquals(Set.of((int) 'ส', (int) 'ว'), Set.of(list.get(0).codePoint(), list.get(1).codePoint()));
        assertEquals(2, list.get(0).count());
        assertEquals(1, list.get(list.size() - 1).count());
    }

    @Test
    void uploadsAreCheckedAndCanBeTheCardFont() throws Exception {
        library(manifest());
        FontLibrary.Upload up = library.addUpload(new ByteArrayInputStream(TestFonts.lao()));
        assertEquals("Noto Sans Lao", up.family());
        assertEquals(up, library.addUpload(new ByteArrayInputStream(TestFonts.lao())), "same file, same upload");
        assertTrue(Files.isRegularFile(dir.resolve("fonts").resolve("uploads").resolve(up.fileName())));
        assertThrows(IllegalArgumentException.class,
                () -> library.addUpload(new ByteArrayInputStream("not a font".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> library.addUpload(new java.io.InputStream() {
            private long left = FontLibrary.MAX_UPLOAD_BYTES + 1;

            @Override
            public int read() {
                return left-- > 0 ? 0 : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                left -= n;
                return n;
            }
        }), "too big");
        library.validateCardFont(null);
        library.validateCardFont("bundled");
        library.validateCardFont("upload:" + up.id());
        assertThrows(IllegalArgumentException.class, () -> library.validateCardFont("upload:0123456789abcdef"));
        assertThrows(IllegalArgumentException.class, () -> library.validateCardFont("system:No Such Family"));
        assertThrows(IllegalArgumentException.class, () -> library.validateCardFont("Arial"));
        // As the card font: Lao text is drawn with it.
        cardFont.set("upload:" + up.id());
        library.rebuild();
        assertEquals("upload:" + up.id(), library.current().preferred().id());
        assertThrows(IllegalStateException.class, () -> library.removeUpload(up.id()), "in use");
        cardFont.set(null);
        library.rebuild();
        assertTrue(library.removeUpload(up.id()));
        assertFalse(library.removeUpload(up.id()));
        assertFalse(library.removeUpload("../../etc"));
        // A missing upload falls back to the bundled font.
        cardFont.set("upload:" + up.id());
        assertEquals(BundledFonts.ID, library.rebuild().preferred().id());
    }
}
