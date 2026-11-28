package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.library.FakeTrackSplitter;
import io.siggi.togethertunes.library.MetadataLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Removing personal data from audio uploads ({@code docs/library-and-ingest.md#looking-details-up-online},
 * "Removing personal data from uploads"): the upload job cleans an {@code .m4a}/{@code .mp3}/{@code .flac}
 * with TrackSplitter before it becomes {@code original.<ext>}; failures, a missing TrackSplitter and
 * timeouts leave the original unserved; the one-time pass over older originals. Real ffmpeg (skipped
 * without it); the real TrackSplitter ({@code ../../TrackSplitter/target/release/tracksplitter}) where a
 * test says so (skipped without it), else the fake script. Siggi's purchased files in
 * {@code sample-files/} are used only through copies, and nothing read from them is printed.
 */
@Timeout(300)
@Execution(ExecutionMode.SAME_THREAD)
class OriginalCleaningTest extends IngestPipelineTestBase {
    static final Path TRACKSPLITTER = Path.of(System.getProperty("user.dir"))
            .resolve("../../TrackSplitter/target/release/tracksplitter").normalize();
    static final Path SAMPLES = Path.of(System.getProperty("user.dir")).resolve("sample-files");

    private MetadataLookup realTrackSplitter() throws Exception {
        assumeTrue(Files.isExecutable(TRACKSPLITTER), "TrackSplitter isn't built at " + TRACKSPLITTER);
        Path cache = Files.createDirectories(dir.resolve("ts-cache"));
        return new MetadataLookup(TRACKSPLITTER.toString(), cache, "TogetherTunes/test ( https://tunes.example )",
                tools.ffmpegExecutable(), Clock.systemUTC());
    }

    private MetadataLookup fakeTrackSplitter(FakeTrackSplitter fake) {
        return new MetadataLookup(fake.script.toString(), fake.cacheDir, "TogetherTunes/test ( https://tunes.example )",
                tools.ffmpegExecutable(), new TestClock(1_800_000_000_000L));
    }

    private IngestPipeline pipeline(OriginalCleaner cleaner) {
        IngestPipeline p = new IngestPipeline(tools, data, new IngestItems(db), reference, 1800);
        p.cleaner(cleaner);
        return p;
    }

    private IngestQueue queue(IngestPipeline pipeline) throws Exception {
        IngestQueue q = new IngestQueue(jobs, 1, pipeline, Clock.systemUTC(), Duration.ofHours(1), Duration.ZERO);
        queues.add(q);
        q.start();
        return q;
    }

    /** A tagged AAC .m4a of {@code seconds} in the test directory. */
    private Path m4a(String name, int seconds) throws Exception {
        Path out = dir.resolve(name);
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", Integer.toString(seconds), "-c:a", "aac",
                "-metadata", "title=Clean Song", "-metadata", "artist=Clean Artist", "-metadata", "album=Clean Album",
                out.toString());
        return out;
    }

    /** {original_web, original_cleaned} of an item. */
    private Object[] cleanRow(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT original_web, original_cleaned FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    int web = rs.getInt(1);
                    int cleaned = rs.getInt(2);
                    return new Object[] {web, rs.wasNull() ? null : cleaned};
                }
            }
        });
    }

    private String originalFile(String id) throws Exception {
        return (String) originalRow(id)[0];
    }

    /** An upload item added by a guest. */
    private String guestItem() throws Exception {
        String guest = UuidV7.newId();
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, display_name,"
                    + " name_chosen, created_at, last_seen_at, banned) VALUES (?, 'cookie', ?, 'Quiet Walrus', 0, 1, 1, 0)")) {
                ps.setString(1, guest);
                ps.setString(2, "hash-" + guest);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_guest_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'pending', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, guest);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private long durationUs(Path file) throws Exception {
        return MediaProbe.probe(tools, file, null).durationUs();
    }

    /** Decodes the whole file; any decode error fails. */
    private void assertPlays(Path file) throws Exception {
        ffmpeg("-xerror", "-i", file.toString(), "-f", "null", "-");
    }

    // ------------------------------------------------------------------ the upload job

    /**
     * The real TrackSplitter on an .m4a with apID/ownr tags and a pinf box: the kept original has
     * none of them (and neither the email nor the name), keeps its other tags, plays, is as long as
     * the upload and is web-playable; original_cleaned 1. The staged upload itself is untouched.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void purchaseDataIsRemovedFromAnUpload() throws Exception {
        MetadataLookup ts = realTrackSplitter();
        Path made = m4a("bought.m4a", 4);
        PurchaseFixtures.addPurchaseData(made);
        assertEquals(Set.of("apID", "ownr", "pinf"), PurchaseData.find(made));
        String item = fixtures.item("pending");
        Path source = stage(item, "source.m4a");
        Files.copy(made, source);
        byte[] uploaded = Files.readAllBytes(source);

        IngestJob job = run(queue(pipeline(ts)), Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("original.m4a", originalFile(item));
        Path kept = IngestPipeline.itemDir(data, item).resolve("original.m4a");
        byte[] bytes = Files.readAllBytes(kept);
        assertFalse(PurchaseFixtures.contains(bytes, PurchaseFixtures.EMAIL), "the email is gone");
        assertFalse(PurchaseFixtures.contains(bytes, PurchaseFixtures.NAME), "the name is gone");
        assertEquals(Set.of(), PurchaseData.find(kept));
        assertArrayEquals(new Object[] {1, 1}, cleanRow(item), "cleaned and web-playable");
        MediaProbe probe = MediaProbe.probe(tools, kept, null);
        assertEquals("Clean Song", probe.tags().get("title"));
        assertEquals("Clean Artist", probe.tags().get("artist"));
        assertEquals("Clean Album", probe.tags().get("album"));
        assertEquals(durationUs(made), probe.durationUs(), 1000, "same duration");
        assertPlays(kept);
        assertTrue(OriginalCleaner.served("original.m4a", 1));
        assertTrue(job.log().contains("step: remove personal data"), job.log());
        assertTrue(job.log().contains("personal data: removed apID, ownr, pinf"), job.log());
        assertArrayEquals(uploaded, Files.readAllBytes(made), "the upload's bytes (and so its hash) don't change");
        assertNoLeftovers();
    }

    /** Files without purchase data stay playable with their tags (.mp3, .flac), and pass the web checks. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void filesWithoutPurchaseDataKeepTheirTags() throws Exception {
        MetadataLookup ts = realTrackSplitter();
        IngestQueue q = queue(pipeline(ts));
        for (String[] f : List.of(new String[] {"mp3", "libmp3lame"}, new String[] {"flac", "flac"},
                new String[] {"m4a", "aac"})) {
            String item = fixtures.item("pending");
            Path source = stage(item, "source." + f[0]);
            ffmpeg("-f", "lavfi", "-i", "sine=f=330:r=44100", "-t", "3", "-c:a", f[1], "-metadata", "title=Plain " + f[0],
                    "-metadata", "artist=Plain Artist", "-metadata", "album=Plain Album", "-metadata", "genre=Test Genre",
                    source.toString());
            long before = durationUs(source);
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), job.log());
            assertArrayEquals(new Object[] {1, 1}, cleanRow(item), f[0] + ": cleaned and web-playable\n" + job.log());
            Path kept = IngestPipeline.itemDir(data, item).resolve("original." + f[0]);
            MediaProbe probe = MediaProbe.probe(tools, kept, null);
            assertEquals("Plain " + f[0], probe.tags().get("title"));
            assertEquals("Plain Artist", probe.tags().get("artist"));
            assertEquals("Plain Album", probe.tags().get("album"));
            assertEquals("Test Genre", probe.tags().get("genre"));
            assertEquals(before, probe.durationUs(), 1000, f[0] + ": same duration");
            assertPlays(kept);
            assertTrue(job.log().contains("personal data: none found"), job.log());
        }
    }

    /**
     * Siggi's real files (sample-files/, git-ignored; skipped without them), through copies: an
     * iTunes Store purchase and an iTunes Match file. Every printable string of their pinf box is
     * gone from the kept original, which plays and is as long as the upload. Nothing read from
     * them is printed.
     */
    @Test
    @Timeout(600)
    void realPurchasedFiles() throws Exception {
        assumeTrue(Files.isDirectory(SAMPLES), "no sample-files/");
        MetadataLookup ts = realTrackSplitter();
        IngestQueue q = queue(pipeline(ts));
        int n = 0;
        for (String name : List.of("1-08 Firework.m4a", "1-01 Midsummer.m4a")) {
            Path sample = SAMPLES.resolve(name);
            if (!Files.isRegularFile(sample)) {
                continue;
            }
            n++;
            String item = fixtures.item("pending");
            Path source = stage(item, "source.m4a");
            Files.copy(sample, source);
            byte[] uploaded = Files.readAllBytes(source);
            long before = durationUs(source);
            List<String> personal = PurchaseFixtures.pinfStrings(uploaded);
            assertFalse(personal.isEmpty(), "sample " + n + " has a pinf box with text");
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), "sample " + n + " ingests");
            Path kept = IngestPipeline.itemDir(data, item).resolve("original.m4a");
            byte[] bytes = Files.readAllBytes(kept);
            int left = 0;
            for (String s : personal) {
                if (PurchaseFixtures.contains(bytes, s)) {
                    left++;
                }
            }
            assertEquals(0, left, "sample " + n + ": no text of the purchase info is left");
            assertEquals(Set.of(), PurchaseData.find(kept), "sample " + n);
            assertEquals(1, cleanRow(item)[1], "sample " + n + " cleaned");
            assertEquals(before, durationUs(kept), 1000, "sample " + n + ": same duration");
            assertPlays(kept);
            assertEquals(uploaded.length, bytes.length, "sample " + n + ": the box is blanked in place");
        }
        assumeTrue(n > 0, "neither sample file is there");
    }

    /**
     * A guest's upload is cleaned like anyone's, offline: TrackSplitter gets --no-lookup and
     * --in-place on a scratch copy (never the staged upload), and nothing that goes online.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aGuestUploadIsCleanedOffline() throws Exception {
        FakeTrackSplitter fake = new FakeTrackSplitter(dir.resolve("fake"));
        fake.respond("{\"tracks\":[{\"path\":\"x\",\"tags\":{},\"changes\":[],\"written\":false}],\"notices\":[]}", 0);
        String item = guestItem();
        Files.copy(m4a("guest.m4a", 3), stage(item, "source.m4a"));
        IngestJob job = run(queue(pipeline(fakeTrackSplitter(fake))), Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        List<String> args = fake.lastArgs();
        assertEquals(List.of("metadata", "--no-lookup", "--json", "--in-place", "--no-decode-check"), args.subList(0, 5));
        assertEquals("--", args.get(args.size() - 2));
        Path cleaned = Path.of(args.get(args.size() - 1));
        assertEquals(IngestPipeline.scratchDir(data, job.id()).resolve("clean.m4a"), cleaned, "a scratch copy");
        for (String a : args) {
            assertFalse(a.equals("--user-agent") || a.equals("--title") || a.equals("--refresh")
                    || a.equals("--lookup"), args.toString());
        }
        assertArrayEquals(new Object[] {1, 1}, cleanRow(item));
        assertEquals(1, fake.calls());
    }

    /**
     * Failures: TrackSplitter fails, times out, leaves the purchase data in place, or isn't
     * installed → the upload still ingests, its original (as uploaded) is kept for re-encodes but
     * not web-playable and not served; the job log says why.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void failuresLeaveTheOriginalUnserved() throws Exception {
        FakeTrackSplitter fake = new FakeTrackSplitter(dir.resolve("fake"));
        MetadataLookup ts = fakeTrackSplitter(fake);
        ts.cleanTimeout(Duration.ofSeconds(1));
        IngestQueue q = queue(pipeline(ts));
        Path made = m4a("bought.m4a", 3);
        PurchaseFixtures.addPurchaseData(made);
        String ok = "{\"tracks\":[{\"path\":\"x\",\"tags\":{},\"changes\":[],\"written\":true}],\"notices\":[]}";
        record Case(String json, int exit, double sleep, String logged) {
        }
        for (Case c : List.of(
                new Case("{\"tracks\":[{\"path\":\"x\",\"error\":\"cannot write tags\"}],\"notices\":[]}", 1, 0,
                        "TrackSplitter failed (exit code 1): cannot write tags"),
                new Case(ok, 0, 5, "TrackSplitter took longer than 1 s"),
                // exit 0, but the fake changed nothing: the purchase data is still there
                new Case(ok, 0, 0, "purchase data was left in place (apID, ownr, pinf)"))) {
            fake.respond(c.json(), c.exit()).sleep(c.sleep());
            String item = fixtures.item("pending");
            Path source = stage(item, "source.m4a");
            Files.copy(made, source);
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), job.log());
            assertTrue(job.log().contains("personal data couldn't be removed: " + c.logged()), job.log());
            assertArrayEquals(new Object[] {0, 0}, cleanRow(item), c.logged());
            assertFalse(OriginalCleaner.served(originalFile(item), 0));
            assertArrayEquals(Files.readAllBytes(made),
                    Files.readAllBytes(IngestPipeline.itemDir(data, item).resolve("original.m4a")),
                    "kept as uploaded, for re-encodes");
            assertEquals("ready", itemRow(item)[0]);
        }

        // not installed: nothing is cleaned; the .m4a waits (null) and isn't served
        IngestQueue none = queue(pipeline(new MetadataLookup(null, null, "ua", null, Clock.systemUTC())));
        String item = fixtures.item("pending");
        Files.copy(made, stage(item, "source.m4a"));
        IngestJob job = run(none, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertTrue(job.log().contains("TrackSplitter isn't installed, so purchase data can't be removed; the original"
                + " isn't played in browsers or served until it is"), job.log());
        assertNull(cleanRow(item)[1]);
        assertFalse(OriginalCleaner.served("original.m4a", null));
        // an .mp3 without TrackSplitter: served as before
        String mp3 = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=f=330:r=44100", "-t", "2", "-c:a", "libmp3lame",
                stage(mp3, "source.mp3").toString());
        job = run(none, Kind.UPLOAD, mp3);
        assertEquals(State.DONE, job.state(), job.log());
        assertArrayEquals(new Object[] {1, null}, cleanRow(mp3));
        assertTrue(OriginalCleaner.served("original.mp3", null));
    }

    // ------------------------------------------------------------------ the one-time pass

    /** A ready audio upload made before cleaning: {@code original.<ext>} from {@code file}, original_web 1, cleaned null. */
    private String oldItem(Path file, String ext, String kind) throws Exception {
        String id = fixtures.item("ready");
        Path itemDir = Files.createDirectories(IngestPipeline.itemDir(data, id));
        Files.copy(file, itemDir.resolve("original." + ext));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = ?, original_file = ?,"
                    + " original_codec = 'aac/mov', original_web = 1, original_gain_db = -1.5 WHERE id = ?")) {
                ps.setString(1, kind);
                ps.setString(2, "original." + ext);
                ps.setString(3, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /**
     * The pass cleans the waiting originals once (real TrackSplitter when built, else the fake),
     * leaves other items alone, retries an item whose job is running, and records a failure as
     * not served; it never re-encodes (the gain stays).
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void theOneTimePass() throws Exception {
        Path plain = m4a("plain.m4a", 2);
        Path bought = m4a("bought.m4a", 2);
        PurchaseFixtures.addPurchaseData(bought);
        FakeTrackSplitter fake = new FakeTrackSplitter(dir.resolve("fake"));
        fake.respond("{\"tracks\":[{\"path\":\"x\",\"tags\":{},\"changes\":[],\"written\":false}],\"notices\":[]}", 0);
        MetadataLookup ts = fakeTrackSplitter(fake);
        IngestPipeline pipeline = pipeline(ts);

        String clean = oldItem(plain, "m4a", "audio");
        String failing = oldItem(bought, "m4a", "audio"); // the fake leaves the purchase data: a failure
        String busy = oldItem(plain, "m4a", "audio");
        String video = oldItem(plain, "m4a", "video");
        String wav = oldItem(plain, "wav", "audio");

        OriginalCleaning pass = new OriginalCleaning(db, data, tools, pipeline, ts);
        pass.retryDelay(Duration.ofMillis(300));
        ReentrantLock lock = pipeline.itemLock(busy);
        lock.lock(); // as if a job of the item were running
        int[] cleaned = {0};
        Thread runner = new Thread(() -> {
            try {
                cleaned[0] = pass.run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        runner.start();
        long deadline = System.nanoTime() + 60_000_000_000L;
        Path busyCopy = data.tmp().resolve("clean").resolve(busy + ".m4a");
        while (fake.calls() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        // the busy item's copy is deleted once its tryLock failed
        while (Files.exists(busyCopy) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        lock.unlock();
        runner.join(60_000);
        assertFalse(runner.isAlive());
        assertEquals(2, cleaned[0], "the plain one, and the busy one in the next round");
        assertEquals(4, fake.calls(), "the busy item was cleaned again in the next round");
        assertArrayEquals(new Object[] {1, 1}, cleanRow(clean));
        assertArrayEquals(new Object[] {1, 1}, cleanRow(busy));
        assertArrayEquals(new Object[] {0, 0}, cleanRow(failing));
        assertArrayEquals(Files.readAllBytes(bought),
                Files.readAllBytes(IngestPipeline.itemDir(data, failing).resolve("original.m4a")), "left as it was");
        assertArrayEquals(new Object[] {1, null}, cleanRow(video), "a video item's original isn't served anyway");
        assertArrayEquals(new Object[] {1, null}, cleanRow(wav), "a WAV isn't cleaned");
        assertEquals(-1.5, (Double) originalRow(clean)[3], 0.001, "no re-encode: the gain stays");
        assertEquals(List.of(), files(data.tmp().resolve("clean")));

        // once only
        assertEquals(0, pass.run());
        assertEquals(4, fake.calls());

        // the real TrackSplitter removes the purchase data from an old original
        if (Files.isExecutable(TRACKSPLITTER)) {
            MetadataLookup real = realTrackSplitter();
            String old = oldItem(bought, "m4a", "audio");
            assertEquals(1, new OriginalCleaning(db, data, tools, pipeline(real), real).run());
            Path kept = IngestPipeline.itemDir(data, old).resolve("original.m4a");
            assertEquals(Set.of(), PurchaseData.find(kept));
            assertFalse(PurchaseFixtures.contains(Files.readAllBytes(kept), PurchaseFixtures.EMAIL));
            assertFalse(PurchaseFixtures.contains(Files.readAllBytes(kept), PurchaseFixtures.NAME));
            assertArrayEquals(new Object[] {1, 1}, cleanRow(old));
            assertPlays(kept);
        }

        // without TrackSplitter the pass does nothing (the items wait for a later start)
        String waiting = oldItem(plain, "flac", "audio");
        MetadataLookup none = new MetadataLookup(null, null, "ua", null, Clock.systemUTC());
        assertEquals(0, new OriginalCleaning(db, data, tools, pipeline(none), none).run());
        assertNull(cleanRow(waiting)[1]);
    }

    /** What is served, by original_cleaned; which originals are cleaned; the purchase-data finder. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void servedRuleAndFinder() throws Exception {
        assertTrue(OriginalCleaner.served("original.m4a", 1));
        assertFalse(OriginalCleaner.served("original.m4a", 0));
        assertFalse(OriginalCleaner.served("original.m4a", null), "an .m4a not cleaned yet");
        assertTrue(OriginalCleaner.served("original.mp3", null));
        assertTrue(OriginalCleaner.served("original.wav", null));
        assertFalse(OriginalCleaner.served("original.flac", 0));
        assertTrue(OriginalCleaner.cleanable("original.flac"));
        assertFalse(OriginalCleaner.cleanable("original.ogg"));
        Path m = m4a("finder.m4a", 1);
        assertEquals(Set.of(), PurchaseData.find(m));
        PurchaseFixtures.addPurchaseData(m);
        assertEquals(Set.of("apID", "ownr", "pinf"), PurchaseData.find(m));
        assertPlays(m);
        assertEquals("Clean Song", MediaProbe.probe(tools, m, null).tags().get("title"), "still a valid file");
    }
}
