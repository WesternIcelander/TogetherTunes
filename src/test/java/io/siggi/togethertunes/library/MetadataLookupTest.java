package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.http.HttpError;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MetadataLookup} against a fake {@code tracksplitter} ({@link FakeTrackSplitter}): the
 * arguments, the three kinds of result, notices, failures, the one-at-a-time queue and the hourly
 * limit ({@code docs/library-and-ingest.md#looking-details-up-online}).
 */
class MetadataLookupTest {
    private static final String UA = "TogetherTunes/test ( https://tunes.example )";

    @TempDir
    Path dir;

    private FakeTrackSplitter fake;
    private MetadataLookup lookup;
    private final TestClock clock = new TestClock(1_800_000_000_000L);

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeTrackSplitter(dir.resolve("ts"));
        lookup = new MetadataLookup(fake.script.toString(), fake.cacheDir, UA, "/usr/bin/ffmpeg", clock);
    }

    private static MetadataLookup.Query query(String album, Path file, Path artwork) {
        return new MetadataLookup.Query("Good is YEAH!", "Wonderful★opportunity!", album, null, file, artwork);
    }

    @Test
    void notInstalled() {
        MetadataLookup none = new MetadataLookup(null, fake.cacheDir, UA, null, clock);
        assertFalse(none.installed());
        HttpError e = assertThrows(HttpError.class,
                () -> none.lookup("u", query("A", null, null), MetadataLookup.Priority.INTERACTIVE));
        assertEquals("feature_disabled", e.code());
    }

    @Test
    void theExactArguments() {
        String cache = fake.cacheDir.toAbsolutePath().toString();
        // with an album and the original: the file (never -o or --in-place; nothing is written)
        assertEquals(List.of("metadata", "--json", "--cache-dir", cache, "--user-agent", UA,
                "--title", "Good is YEAH!", "--artist", "Wonderful★opportunity!", "--album", "Album",
                "--no-decode-check", "--ffmpeg", "/usr/bin/ffmpeg", "--", "/m/original.m4a"),
                lookup.arguments(query("Album", Path.of("/m/original.m4a"), Path.of("/m/cover.jpg"))));
        // with an album, no such original: the cover as --artwork
        assertEquals(List.of("metadata", "--json", "--cache-dir", cache, "--user-agent", UA,
                "--title", "Good is YEAH!", "--artist", "Wonderful★opportunity!", "--album", "Album",
                "--album-artist", "AA", "--artwork", "/m/cover.jpg"),
                lookup.arguments(new MetadataLookup.Query("Good is YEAH!", "Wonderful★opportunity!", "Album", "AA",
                        null, Path.of("/m/cover.jpg"))));
        // without an album: the album list, never a file or a cover
        assertEquals(List.of("metadata", "--json", "--cache-dir", cache, "--user-agent", UA,
                "--title", "Good is YEAH!", "--artist", "Wonderful★opportunity!", "--limit", "10"),
                lookup.arguments(query(null, Path.of("/m/original.m4a"), Path.of("/m/cover.jpg"))));
        for (List<String> args : List.of(lookup.arguments(query("A", Path.of("/x.m4a"), null)),
                lookup.arguments(query(null, null, null)))) {
            assertFalse(args.contains("-o") || args.contains("--output") || args.contains("--in-place"), args.toString());
            assertTrue(args.contains("--json"));
        }
    }

    @Test
    void oneTrack() throws Exception {
        Path cover = fake.artwork("catalogue-0D8.jpg", 64, 64);
        fake.respond(FakeTrackSplitter.singleTrack(cover, "artwork"), 0);
        Path given = dir.resolve("given.jpg");
        FakeTrackSplitter.writeJpeg(given, 8, 8);
        MetadataLookup.Result r = lookup.lookup("u1", query("Good is YEAH!", null, given),
                MetadataLookup.Priority.INTERACTIVE);
        // what the process got: --artwork with the item's cover
        assertEquals(lookup.arguments(query("Good is YEAH!", null, given)), fake.lastArgs());
        MetadataLookup.Found found = assertInstanceOf(MetadataLookup.Found.class, r);
        assertFalse(found.notices());
        MetadataLookup.Track t = found.track();
        assertEquals("Good is YEAH! (feat. Kagamine Rin & Kagamine Len)", t.title());
        assertEquals("Wonderful★opportunity!, Kagamine Rin & Kagamine Len", t.artist());
        assertEquals(List.of("Wonderful★opportunity!", "Kagamine Rin & Kagamine Len"), t.details().artists());
        assertEquals("Wonderful★opportunity!", t.details().albumArtist());
        assertEquals(1, t.details().track());
        assertEquals(2, t.details().trackTotal());
        assertNull(t.details().disc());
        assertEquals("JPVI02201530", t.details().isrc());
        assertEquals("2022-06-29", t.details().date());
        assertEquals("FlyingDog", t.details().label());
        assertEquals("4573556731873", t.details().barcode());
        assertEquals("J-Pop", t.details().genre());
        assertEquals(cover.toRealPath(), t.artwork());
        assertEquals("0D8", t.albumInfo().get("id").getAsString());
        assertTrue(t.matchedByCoverOrId());
        assertFalse(t.namesOnly());
    }

    @Test
    void valuesThatDontFitAreLeftOutAndForeignCoversIgnored() throws Exception {
        Path outside = dir.resolve("elsewhere.jpg");
        FakeTrackSplitter.writeJpeg(outside, 8, 8);
        fake.respond("{\"tracks\":[{\"tags\":{\"title\":\"T\",\"artist\":\"A\",\"album\":\"B\",\"track\":0,"
                + "\"trackTotal\":\"12\",\"isrc\":\"nope\",\"date\":\"2022-13-01\",\"barcode\":\"12\"},"
                + "\"artwork\":\"" + outside + "\",\"album\":{\"matchedBy\":\"name\",\"namesOnly\":true}}],"
                + "\"notices\":[]}", 0);
        MetadataLookup.Track t = assertInstanceOf(MetadataLookup.Found.class,
                lookup.lookup("u1", query("B", null, null), MetadataLookup.Priority.INTERACTIVE)).track();
        assertNull(t.details().track());
        assertEquals(12, t.details().trackTotal());
        assertNull(t.details().isrc());
        assertNull(t.details().date());
        assertNull(t.details().barcode());
        assertNull(t.artwork(), "a cover outside TrackSplitter's cache is never used");
        assertFalse(t.matchedByCoverOrId());
        assertTrue(t.namesOnly());
        // the image we gave it, echoed back (its album's cover isn't cached): no new cover
        Path given = fake.artwork("given.jpg", 8, 8);
        fake.respond(FakeTrackSplitter.singleTrack(given, "artwork"), 0);
        assertNull(assertInstanceOf(MetadataLookup.Found.class,
                lookup.lookup("u1", query("B", null, given), MetadataLookup.Priority.INTERACTIVE)).track().artwork());
    }

    @Test
    void albumList() throws Exception {
        fake.respond(FakeTrackSplitter.albumList(), 0);
        MetadataLookup.Albums albums = assertInstanceOf(MetadataLookup.Albums.class,
                lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE));
        assertTrue(fake.lastArgs().containsAll(List.of("--limit", "10")));
        assertEquals(2, albums.albums().size());
        assertEquals("Troubadour", albums.albums().get(0).album());
        assertEquals(7, albums.albums().get(0).details().track());
        assertEquals("2009-02-24", albums.albums().get(0).details().date());
        assertEquals("Wavin' Flag (Celebration Mix) - EP", albums.albums().get(1).album());
        assertNull(albums.albums().get(0).artwork(), "a list's covers are addresses: never fetched or passed on");
        // a list of one is still a list (its cover is an address)
        String one = FakeTrackSplitter.albumList().replaceFirst(",\\{\"tags\".*\\]", "]");
        fake.respond(one, 0);
        assertEquals(1, assertInstanceOf(MetadataLookup.Albums.class,
                lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE)).albums().size());
    }

    @Test
    void nothingFoundAndNotices() throws Exception {
        fake.respond("{\"tracks\":[],\"notices\":[]}", 1);
        assertInstanceOf(MetadataLookup.NotFound.class,
                lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE));
        fake.respond("{\"tracks\":[],\"notices\":[\"Catalogue is rate limiting; skipped it\"]}", 1);
        MetadataLookup.Result r = lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE);
        assertInstanceOf(MetadataLookup.NotFound.class, r);
        assertTrue(r.notices());
        fake.respond(FakeTrackSplitter.albumList().replace("\"notices\":[]", "\"notices\":[\"slow\"]"), 0);
        assertTrue(lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE).notices());
        // not identified (no album object): nothing to offer
        fake.respond("{\"tracks\":[{\"tags\":{\"title\":\"T\"},\"warnings\":[\"not found\"]}],\"notices\":[]}", 0);
        assertInstanceOf(MetadataLookup.NotFound.class,
                lookup.lookup("u1", query("A", null, null), MetadataLookup.Priority.INTERACTIVE));
    }

    @Test
    void failures() throws Exception {
        fake.respond("this is not JSON", 0);
        assertEquals("lookup_failed", assertThrows(HttpError.class,
                () -> lookup.lookup("u1", query("A", null, null), MetadataLookup.Priority.INTERACTIVE)).code());
        fake.respond("{\"tracks\":[],\"notices\":[]}", 2);
        HttpError e = assertThrows(HttpError.class,
                () -> lookup.lookup("u1", query("A", null, null), MetadataLookup.Priority.INTERACTIVE));
        assertEquals(502, e.status());
        assertEquals("lookup_failed", e.code());
        assertFalse(e.getMessage().contains("looking it up"), "stderr never reaches the caller");
        fake.respond("{\"tracks\":[]}", 0).sleep(5);
        lookup.limits(Duration.ofSeconds(5), Duration.ofMillis(700));
        long start = System.nanoTime();
        e = assertThrows(HttpError.class,
                () -> lookup.lookup("u1", query("A", null, null), MetadataLookup.Priority.INTERACTIVE));
        assertEquals(504, e.status());
        assertEquals("lookup_timeout", e.code());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4), "killed at the timeout");
    }

    @Test
    void oneAtATimeAndBusy() throws Exception {
        fake.respond("{\"tracks\":[],\"notices\":[]}", 1).sleep(1.5);
        lookup.limits(Duration.ofMillis(300), Duration.ofSeconds(10));
        CompletableFuture<MetadataLookup.Result> first = CompletableFuture.supplyAsync(() -> {
            try {
                return lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(400);
        HttpError busy = assertThrows(HttpError.class,
                () -> lookup.lookup("u2", query(null, null, null), MetadataLookup.Priority.INTERACTIVE));
        assertEquals(503, busy.status());
        assertEquals("lookup_busy", busy.code());
        assertInstanceOf(MetadataLookup.NotFound.class, first.get(10, TimeUnit.SECONDS));
        assertEquals(1, fake.calls(), "the refused one never ran");
    }

    @Test
    void peopleGoBeforeBackgroundWork() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                lookup.exclusive(MetadataLookup.Priority.INTERACTIVE, () -> {
                    holding.countDown();
                    release.await();
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holding.await();
        List<Thread> waiting = new ArrayList<>();
        waiting.add(Thread.ofVirtual().start(() -> run(MetadataLookup.Priority.BACKGROUND, "background", order)));
        Thread.sleep(150);
        waiting.add(Thread.ofVirtual().start(() -> run(MetadataLookup.Priority.INTERACTIVE, "person", order)));
        Thread.sleep(150);
        release.countDown();
        holder.join();
        for (Thread t : waiting) {
            t.join(5000);
        }
        assertEquals(List.of("person", "background"), order);
    }

    private void run(MetadataLookup.Priority p, String name, List<String> order) {
        try {
            lookup.exclusive(p, () -> order.add(name));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void thirtyAnHourPerUser() throws Exception {
        fake.respond("{\"tracks\":[],\"notices\":[]}", 1);
        for (int i = 0; i < MetadataLookup.PER_HOUR; i++) {
            lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE);
            clock.advance(1000);
        }
        HttpError e = assertThrows(HttpError.class,
                () -> lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE));
        assertEquals(429, e.status());
        assertEquals("too_many_lookups", e.code());
        assertTrue(e.retryAfterSeconds() > 0 && e.retryAfterSeconds() <= 3600, "Retry-After " + e.retryAfterSeconds());
        assertEquals(MetadataLookup.PER_HOUR, fake.calls());
        // others aren't affected; an hour after the first, one more
        lookup.lookup("u2", query(null, null, null), MetadataLookup.Priority.INTERACTIVE);
        clock.advance(3_600_000L - 30_000L);
        lookup.lookup("u1", query(null, null, null), MetadataLookup.Priority.INTERACTIVE);
    }

    @Test
    void parseIsStrictAboutTheShape() {
        assertThrows(IllegalArgumentException.class,
                () -> MetadataLookup.parse("{}".getBytes(StandardCharsets.UTF_8), 0, dir, null));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataLookup.parse("[]".getBytes(StandardCharsets.UTF_8), 0, dir, null));
        assertThrows(IllegalArgumentException.class, () -> MetadataLookup.parse(
                FakeTrackSplitter.albumList().getBytes(StandardCharsets.UTF_8), 1, dir, null));
    }
}
