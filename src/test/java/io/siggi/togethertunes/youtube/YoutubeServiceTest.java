package io.siggi.togethertunes.youtube;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The YouTube service client ({@link YoutubeService}) against {@link FakeYtSucks}: the lookup's
 * stream, search, the download stream and the file, the cover, the admin calls, every error kind,
 * and the token (only in the header, never without one, never in {@code toString}).
 */
class YoutubeServiceTest {
    private static final String ID = "jNQXAC9IVRw";

    @TempDir
    Path dir;

    private FakeYtSucks fake;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeYtSucks();
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private static YoutubeServiceException.Kind kind(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(YoutubeServiceException.class, e).kind();
    }

    @Test
    void lookupStreamsStagesThenTheResult() throws Exception {
        fake.stages = List.of("queued", "[youtube] " + ID + ": Downloading webpage");
        fake.info(ID, "{\"id\":\"" + ID + "\",\"title\":\"Me at the zoo\",\"duration\":19,\"live_status\":\"not_live\","
                + "\"track\":null}");
        List<String> stages = new CopyOnWriteArrayList<>();
        JsonObject r = fake.service().info(ID, stages::add);
        assertEquals(List.of("queued", "[youtube] " + ID + ": Downloading webpage"), stages, "keepalive ignored");
        assertEquals("Me at the zoo", r.get("title").getAsString());
        assertFalse(r.has("type"));
        FakeYtSucks.Req req = fake.requests("/api/info").get(0);
        assertEquals(Map.of("vid", ID, "stream", "true"), req.query());
        assertNull(req.authorization(), "no token: no Authorization header");

        fake.service("s3cret").info(ID, null);
        assertEquals("Bearer s3cret", fake.requests("/api/info").get(1).authorization());
        assertThrows(IllegalArgumentException.class, () -> fake.service().info("https://youtu.be/" + ID, null),
                "only IDs");
    }

    @Test
    void lookupErrors() throws Exception {
        for (String type : List.of("unavailable", "private", "age_restricted", "sign_in_required", "network", "other")) {
            fake.infoError(ID, type, "message for " + type);
            YoutubeServiceException e = assertThrows(YoutubeServiceException.class, () -> fake.service().info(ID, null));
            assertEquals(YoutubeServiceException.Kind.YOUTUBE, e.kind(), type);
            assertEquals(type, e.errorType());
            assertEquals("message for " + type, e.getMessage());
        }
        // a broken stream: no result line
        FakeYtSucks broken = new FakeYtSucks();
        try {
            broken.defaultInfo = "not json";
            assertEquals(YoutubeServiceException.Kind.BAD_ANSWER, kind(() -> broken.service().info(ID, null)));
            broken.infoStatus = 500;
            assertEquals(YoutubeServiceException.Kind.BAD_ANSWER, kind(() -> broken.service().info(ID, null)));
        } finally {
            broken.close();
        }
        fake.requiredToken = "right";
        assertEquals(YoutubeServiceException.Kind.UNAUTHORIZED, kind(() -> fake.service("wrong").info(ID, null)));
        assertEquals(YoutubeServiceException.Kind.UNAUTHORIZED, kind(() -> fake.service().info(ID, null)));
        assertEquals(YoutubeServiceException.Kind.NOT_CONFIGURED, kind(() -> YoutubeService.none().info(ID, null)));
        assertFalse(YoutubeService.none().configured());
        String url = fake.url();
        fake.close();
        assertEquals(YoutubeServiceException.Kind.UNREACHABLE,
                kind(() -> new YoutubeService(() -> new YoutubeService.Endpoint(url, null)).info(ID, null)));
    }

    @Test
    void lookupTimesOutAndClosesTheConnection() throws Exception {
        fake.info(ID, "{\"id\":\"" + ID + "\"}");
        fake.infoDelayMs = 5000;
        YoutubeService s = fake.service();
        FakeYtSucks.shortTimeouts(s, Duration.ofMillis(300));
        long start = System.nanoTime();
        assertEquals(YoutubeServiceException.Kind.TIMEOUT, kind(() -> s.info(ID, null)));
        assertTrue(System.nanoTime() - start < 3_000_000_000L);
        assertFalse(Thread.currentThread().isInterrupted(), "the watchdog's interrupt doesn't leak");
    }

    @Test
    void aCancelledLookupIsAnInterrupt() throws Exception {
        fake.info(ID, "{\"id\":\"" + ID + "\"}");
        fake.infoDelayMs = 5000;
        YoutubeService s = fake.service();
        List<Throwable> thrown = new ArrayList<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                s.info(ID, null);
            } catch (Throwable e) {
                thrown.add(e);
            }
        });
        Thread.sleep(300);
        t.interrupt();
        t.join(3000);
        assertFalse(t.isAlive());
        assertTrue(thrown.get(0) instanceof InterruptedException, thrown.toString());
    }

    @Test
    void search() throws Exception {
        fake.searchBody = FakeYtSucks.results(new String[] {ID, "Zoo", "jawed", "19"});
        JsonObject r = fake.service().search("me at the zoo & more", 8);
        assertEquals(ID, r.getAsJsonArray("results").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(Map.of("q", "me at the zoo & more", "n", "8", "exclude_live", "true"),
                fake.requests("/api/search").get(0).query());
        fake.searchStatus = 403;
        fake.searchBody = "{\"error_type\":\"sign_in_required\",\"error\":\"Sign in to confirm\"}";
        YoutubeServiceException e = assertThrows(YoutubeServiceException.class, () -> fake.service().search("x", 1));
        assertEquals(YoutubeServiceException.Kind.YOUTUBE, e.kind());
        assertEquals("sign_in_required", e.errorType());
        fake.searchStatus = 502;
        fake.searchBody = "<html>bad gateway</html>";
        assertEquals(YoutubeServiceException.Kind.BAD_ANSWER, kind(() -> fake.service().search("x", 1)));
        fake.searchStatus = 200;
        fake.searchDelayMs = 3000;
        YoutubeService slow = fake.service();
        FakeYtSucks.shortTimeouts(slow, Duration.ofMillis(300));
        assertEquals(YoutubeServiceException.Kind.TIMEOUT, kind(() -> slow.search("x", 1)));
    }

    @Test
    void downloadReportsProgressThenTheFileIsFetched() throws Exception {
        byte[] mp4 = new byte[300_000];
        for (int i = 0; i < mp4.length; i++) {
            mp4[i] = (byte) (i * 31);
        }
        fake.video(ID, mp4);
        List<Double> progress = new CopyOnWriteArrayList<>();
        List<String> states = new CopyOnWriteArrayList<>();
        YoutubeService s = fake.service("tok");
        String file = s.download(ID, 1080, progress::add, st -> states.add(st.get("status").getAsString()),
                Duration.ofSeconds(10));
        assertEquals("/api/jobs/" + ID + "/file", file);
        assertEquals(List.of(0.0, 0.0, 0.4, 0.8, 1.0, 1.0), progress);
        assertEquals(List.of("queued", "downloading", "downloading", "downloading", "processing", "done"), states);
        assertEquals(Map.of("vid", ID, "quality", "1080", "download", "true", "stream", "true"),
                fake.requests("/api/video").get(0).query());
        Path target = dir.resolve("src.mp4");
        List<Long> bytes = new CopyOnWriteArrayList<>();
        assertEquals(mp4.length, s.fetchFile(file, target, bytes::add, Duration.ofSeconds(10)));
        assertArrayEquals(mp4, Files.readAllBytes(target));
        assertEquals(mp4.length, bytes.get(bytes.size() - 1));
        assertFalse(Files.exists(dir.resolve("src.mp4.part")));
        for (FakeYtSucks.Req r : fake.requests()) {
            assertEquals("Bearer tok", r.authorization(), r.path());
        }
        // a file that isn't there
        assertEquals(YoutubeServiceException.Kind.BAD_ANSWER,
                kind(() -> s.fetchFile("/api/jobs/nope/file", dir.resolve("x.mp4"), null, Duration.ofSeconds(5))));
        assertFalse(Files.exists(dir.resolve("x.mp4.part")));

        fake.videoError(ID, "private", "Private video");
        YoutubeServiceException e = assertThrows(YoutubeServiceException.class,
                () -> s.download(ID, 1080, p -> { }, null, Duration.ofSeconds(10)));
        assertEquals(YoutubeServiceException.Kind.YOUTUBE, e.kind());
        assertEquals("private", e.errorType());
    }

    @Test
    void aStalledDownloadTimesOutThoughTheServiceRepeatsItsState() throws Exception {
        fake.videoStalls = true;
        long start = System.nanoTime();
        assertEquals(YoutubeServiceException.Kind.TIMEOUT,
                kind(() -> fake.service().download(ID, 1080, p -> { }, null, Duration.ofMillis(800))));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms >= 700 && ms < 5000, ms + " ms");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void theFileUrlMustBeOnTheService() throws Exception {
        YoutubeService.Endpoint e = new YoutubeService.Endpoint("https://yt.example/base/", "t");
        assertEquals(URI.create("https://yt.example/base/api/jobs/1/file"), YoutubeService.fileUri(e, "/api/jobs/1/file"));
        assertEquals(URI.create("https://yt.example:443/api/jobs/1/file"),
                YoutubeService.fileUri(e, "https://yt.example:443/api/jobs/1/file"));
        for (String bad : List.of("https://evil.example/api/jobs/1/file", "//evil.example/x", "http://yt.example/x",
                "https://yt.example:8443/x", "https://u:p@yt.example/x", "file:///etc/passwd")) {
            assertEquals(YoutubeServiceException.Kind.BAD_ANSWER, kind(() -> YoutubeService.fileUri(e, bad)), bad);
        }
    }

    @Test
    void coverFromTheThumbnailElseTheFallback() throws Exception {
        String thumb = "https://i.ytimg.com/vi_webp/" + ID + "/maxresdefault.webp";
        byte[] webp = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        fake.info(ID, "{\"id\":\"" + ID + "\",\"thumbnail\":\"" + thumb + "\"}");
        fake.image(thumb, webp);
        List<String> log = new ArrayList<>();
        Path p = fake.service().cover(ID, dir, "cover", log::add);
        assertEquals(dir.resolve("cover.webp"), p);
        assertArrayEquals(webp, Files.readAllBytes(p));

        // a host that isn't allowed: never fetched, YouTube's own instead
        String fallback = "https://i.ytimg.com/vi/" + ID + "/hqdefault.jpg";
        fake.image(fallback, FakeYtSucks.jpeg());
        int n = 0;
        for (String bad : List.of("https://evil.example/x.jpg", "http://i.ytimg.com/x.jpg",
                "https://i.ytimg.com:8443/x.jpg", "https://user@i.ytimg.com/x.jpg", "https://ytimg.com.evil.example/x")) {
            fake.info(ID, "{\"id\":\"" + ID + "\",\"thumbnail\":\"" + bad + "\"}");
            Path d = Files.createDirectories(dir.resolve("bad" + n++));
            Path q = fake.service().cover(ID, d, "cover", log::add);
            assertEquals(d.resolve("cover.jpg"), q, bad);
            assertFalse(fake.imageRequests().contains(URI.create(bad)), bad);
        }
        // a thumbnail that isn't an image, or no lookup at all: the fallback
        fake.image(thumb, "<html>".getBytes());
        fake.info(ID, "{\"id\":\"" + ID + "\",\"thumbnail\":\"" + thumb + "\"}");
        Files.createDirectories(dir.resolve("a"));
        assertEquals("cover.jpg", fake.service().cover(ID, dir.resolve("a"), "cover", log::add).getFileName().toString());
        fake.infoError(ID, "network", "down");
        Files.createDirectories(dir.resolve("b"));
        assertEquals("cover.jpg", fake.service().cover(ID, dir.resolve("b"), "cover", log::add).getFileName().toString());
        // nothing at all: no cover, no failure
        FakeYtSucks empty = new FakeYtSucks();
        try {
            Files.createDirectories(dir.resolve("c"));
            assertNull(empty.service().cover(ID, dir.resolve("c"), "cover", log::add));
        } finally {
            empty.close();
        }
        assertTrue(YoutubeService.allowedThumbnail("https://lh3.googleusercontent.com/abc=w480") != null);
        assertNull(YoutubeService.allowedThumbnail("https://googleusercontent.com.evil.example/x"));
    }

    @Test
    void adminCalls() throws Exception {
        YoutubeService s = fake.service();
        assertEquals("tc1", s.status().getAsJsonObject("active").get("id").getAsString());
        assertTrue(s.startUpdate().get("started").getAsBoolean());
        assertEquals("up_to_date", s.updateLog().getAsJsonObject().get("result").getAsString());
        fake.requiredToken = "t";
        assertEquals(404, YoutubeService.checkToken(new YoutubeService.Endpoint(fake.url(), "t")));
        assertEquals(401, YoutubeService.checkToken(new YoutubeService.Endpoint(fake.url(), "bad")));
        assertEquals(401, YoutubeService.checkToken(new YoutubeService.Endpoint(fake.url(), null)));
        // the status needs no token; the update does
        assertEquals("tc1", s.status().getAsJsonObject("active").get("id").getAsString());
        assertEquals(YoutubeServiceException.Kind.UNAUTHORIZED, kind(s::startUpdate));
        assertEquals(YoutubeServiceException.Kind.UNAUTHORIZED, kind(s::updateLog));
    }

    @Test
    void theTokenNeverShowsUp() {
        YoutubeService.Endpoint e = new YoutubeService.Endpoint("https://yt.example", "s3cret-token");
        assertFalse(e.toString().contains("s3cret"), e.toString());
        assertEquals("https://yt.example/api/jobs/1/file?token=…&x=1",
                YoutubeService.redact("https://yt.example/api/jobs/1/file?token=s3cret&x=1"));
        assertEquals("https://yt.example/x?a=1&token=…", YoutubeService.redact("https://yt.example/x?a=1&token=abc"));
    }
}
