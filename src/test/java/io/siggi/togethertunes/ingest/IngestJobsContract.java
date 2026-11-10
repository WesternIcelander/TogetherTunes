package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link IngestJobs}: the queue's state transitions, in both dialects. */
abstract class IngestJobsContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    private TestClock clock;
    private IngestJobs jobs;
    private IngestFixtures fixtures;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        clock = new TestClock(NOW);
        jobs = new IngestJobs(db, clock);
        fixtures = new IngestFixtures(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** {@link IngestItems}: what the pipeline reads, and the columns its publish step writes. */
    @Test
    void ingestItemsReadAndPublish() throws SQLException {
        IngestItems items = new IngestItems(db);
        String item = fixtures.item("purged");
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE items SET artist = 'Artist', youtube_id = 'dQw4w9WgXcQ',"
                    + " source = 'youtube', purged_at = 5, loudness_lufs = -9 WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(new IngestItems.Item(item, "audio", "Song", "Artist", null, "youtube", "dQw4w9WgXcQ", "purged",
                "720p30", null, -9.0, null, false),
                items.get(item).orElseThrow());
        assertTrue(items.get("nope").isEmpty());

        items.published(item, false, 61_234, OutputEncoding.defaultFor(OutputProfile.P1080_30), -17.25,
                new Originals.Original("original.flac", "flac/flac", true, 3.25));
        assertArrayEquals(new Object[] {"audio", 61_234L, "1080p30-6000k-v2", -17.25, null, "original.flac", "flac/flac", 1, 3.25},
                publishedRow(item));
        items.published(item, false, 1, OutputEncoding.defaultFor(OutputProfile.P720_30), null,
                new Originals.Original("original.wma", "wmav2/asf", false, null));
        assertArrayEquals(new Object[] {"audio", 1L, "720p30-3000k-v2", null, null, "original.wma", "wmav2/asf", 0, null},
                publishedRow(item));
        // a video (or YouTube) publish clears an earlier original
        items.published(item, true, 2, OutputEncoding.defaultFor(OutputProfile.P720_30), -9.5, null);
        assertArrayEquals(new Object[] {"video", 2L, "720p30-3000k-v2", -9.5, null, null, null, 0, null}, publishedRow(item));
        assertThrows(SQLException.class, () -> items.published("nope", false, 1,
                OutputEncoding.defaultFor(OutputProfile.P720_30), null, null));
        // the preset columns: the short form records the reference preset as not the quality encode
        assertArrayEquals(new Object[] {"veryfast", false}, fixtures.encode(item));
        items.published(item, true, 2, OutputEncoding.DEFAULT, X264Preset.SLOWER, true, -9.5, null);
        assertArrayEquals(new Object[] {"slower", true}, fixtures.encode(item));
        assertEquals("slower", items.get(item).orElseThrow().encodePreset());
        assertTrue(items.get(item).orElseThrow().encodeQuality());
        items.published(item, true, 2, OutputEncoding.DEFAULT, X264Preset.FASTER, false, -9.5, null);
        assertArrayEquals(new Object[] {"faster", false}, fixtures.encode(item));
        items.markQuality(item);
        assertArrayEquals(new Object[] {"faster", true}, fixtures.encode(item));
    }

    /** What decides the preset tier when a job starts: library entries and room queues. */
    @Test
    void ingestItemsKnowsLibrariesAndRooms() throws SQLException {
        IngestItems items = new IngestItems(db);
        String item = fixtures.item("pending");
        assertFalse(items.inLibrary(item));
        assertFalse(items.wantedInRoom(item));
        fixtures.addToLibrary(item);
        assertTrue(items.inLibrary(item));
        for (String state : List.of("played", "skipped", "removed")) {
            fixtures.queue(item, state);
        }
        assertFalse(items.wantedInRoom(item), "finished entries don't count");
        for (String state : List.of("queued", "suggested", "playing")) {
            String other = fixtures.item("pending");
            fixtures.queue(other, state);
            assertTrue(items.wantedInRoom(other), state);
        }
    }

    @Test
    void enqueueStoresTheTier() throws SQLException {
        IngestJob quick = jobs.enqueue(fixtures.item("pending"), Kind.YOUTUBE, Tier.QUICK);
        IngestJob quality = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD, Tier.QUALITY);
        IngestJob none = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        assertEquals(Tier.QUICK, jobs.get(quick.id()).orElseThrow().tier());
        assertEquals(Tier.QUALITY, jobs.get(quality.id()).orElseThrow().tier());
        assertNull(jobs.get(none.id()).orElseThrow().tier());
        assertEquals(Tier.QUICK, jobs.claimNext().tier());
    }

    @Test
    void enqueueTellsTheListenerAfterTheCommit() throws SQLException {
        int[] calls = {0};
        jobs.enqueueListener(() -> calls[0]++);
        String item = fixtures.item("pending");
        assertThrows(IllegalStateException.class, () -> db.write(c -> {
            jobs.enqueue(item, Kind.UPLOAD);
            throw new IllegalStateException("roll back");
        }));
        assertEquals(0, calls[0]);
        jobs.enqueue(item, Kind.UPLOAD);
        assertEquals(1, calls[0]);
        jobs.enqueue(item, Kind.UPLOAD); // attaches to the unfinished job: nothing new
        assertEquals(1, calls[0]);
    }

    /** A quick encode of a library item gets a quality job when it finishes; nothing else does. */
    @Test
    void aFinishedQuickEncodeOfALibraryItemQueuesItsQualityJob() throws SQLException {
        jobs.qualityPreset(() -> X264Preset.SLOW);
        // in a room only: no quality job
        String roomOnly = fixtures.item("pending");
        IngestJob j1 = jobs.enqueue(roomOnly, Kind.YOUTUBE, Tier.QUICK);
        jobs.claimNext();
        fixtures.encoded(roomOnly, "video", "veryfast", false);
        jobs.complete(j1.id(), null);
        assertEquals(j1.id(), jobs.latestForItem(roomOnly).orElseThrow().id());

        // in a library: the quality job follows, low priority and owning nothing
        String library = fixtures.item("pending");
        fixtures.addToLibrary(library);
        IngestJob j2 = jobs.enqueue(library, Kind.YOUTUBE, Tier.QUICK);
        jobs.claimNext();
        fixtures.encoded(library, "video", "veryfast", false);
        jobs.complete(j2.id(), null);
        IngestJob quality = jobs.latestForItem(library).orElseThrow();
        assertEquals(Kind.QUALITY, quality.kind());
        assertEquals(Tier.QUALITY, quality.tier());
        assertEquals(State.QUEUED, quality.state());
        assertEquals("ready", fixtures.itemStatus(library)[0], "the item stays ready");
        // when the quality job finishes with the quality encode, nothing more is queued
        assertEquals(quality.id(), jobs.claimNext().id());
        fixtures.encoded(library, "video", "slow", true);
        jobs.complete(quality.id(), null);
        assertEquals(quality.id(), jobs.latestForItem(library).orElseThrow().id());

        // made with the quality preset already (equal presets): no second job
        String equal = fixtures.item("pending");
        fixtures.addToLibrary(equal);
        IngestJob j3 = jobs.enqueue(equal, Kind.UPLOAD, Tier.QUICK);
        jobs.claimNext();
        fixtures.encoded(equal, "video", "slow", false);
        jobs.complete(j3.id(), null);
        assertEquals(j3.id(), jobs.latestForItem(equal).orElseThrow().id());

        // an audio item has no video to improve
        String audio = fixtures.item("pending");
        fixtures.addToLibrary(audio);
        IngestJob j4 = jobs.enqueue(audio, Kind.UPLOAD, Tier.QUICK);
        jobs.claimNext();
        fixtures.encoded(audio, "audio", "veryfast", false);
        jobs.complete(j4.id(), null);
        assertEquals(j4.id(), jobs.latestForItem(audio).orElseThrow().id());

        // a failed job queues nothing
        String failing = fixtures.item("pending");
        fixtures.addToLibrary(failing);
        fixtures.encoded(failing, "video", "veryfast", false);
        IngestJob j5 = jobs.enqueue(failing, Kind.UPLOAD, Tier.QUICK);
        jobs.claimNext();
        jobs.fail(j5.id(), "broken", null);
        assertEquals(j5.id(), jobs.latestForItem(failing).orElseThrow().id());
    }

    /** Adding a ready quick-encoded item to a library (the listener's call), and the startup sweep. */
    @Test
    void enqueueQualityIfNeededAndAtStartup() throws SQLException {
        String item = fixtures.item("ready");
        fixtures.encoded(item, "video", "veryfast", false);
        assertFalse(jobs.enqueueQualityIfNeeded(item), "no quality preset known yet");
        jobs.qualityPreset(() -> X264Preset.SLOWER);
        assertFalse(jobs.enqueueQualityIfNeeded(item), "not in a library");
        fixtures.addToLibrary(item);
        assertTrue(jobs.enqueueQualityIfNeeded(item));
        assertFalse(jobs.enqueueQualityIfNeeded(item), "it has an unfinished job now");
        assertEquals(Kind.QUALITY, jobs.latestForItem(item).orElseThrow().kind());

        String pending = fixtures.item("pending");
        fixtures.addToLibrary(pending);
        assertFalse(jobs.enqueueQualityIfNeeded(pending), "not ready: its job checks when it finishes");

        String done = fixtures.item("ready");
        fixtures.encoded(done, "video", "slow", true);
        fixtures.addToLibrary(done);
        assertFalse(jobs.enqueueQualityIfNeeded(done), "already the quality encode, even with another preset now");

        String legacy = fixtures.item("ready");
        fixtures.encoded(legacy, "video", null, false);
        fixtures.addToLibrary(legacy);
        String failedBefore = fixtures.item("ready");
        fixtures.encoded(failedBefore, "video", "faster", false);
        fixtures.addToLibrary(failedBefore);
        IngestJob old = jobs.enqueue(failedBefore, Kind.QUALITY, Tier.QUALITY);
        // claim in order: item's job first
        assertEquals(Kind.QUALITY, jobs.claimNext().kind());
        assertEquals(old.id(), jobs.claimNext().id());
        jobs.fail(old.id(), "broke", null);

        assertEquals(2, jobs.enqueueQualityReencodes(), "the legacy item and the one whose quality job failed");
        assertEquals(Kind.QUALITY, jobs.latestForItem(legacy).orElseThrow().kind());
        assertNotEquals(old.id(), jobs.latestForItem(failedBefore).orElseThrow().id());
        assertEquals(0, jobs.enqueueQualityReencodes());
    }

    /** New jobs first, then profile re-encodes, then quality re-encodes. */
    @Test
    void qualityJobsComeLast() throws SQLException {
        String q = fixtures.item("ready");
        IngestJob quality = jobs.enqueue(q, Kind.QUALITY, Tier.QUALITY);
        clock.advance(1);
        IngestJob reencode = jobs.enqueue(fixtures.item("ready"), Kind.REENCODE);
        assertFalse(jobs.hasQueuedNewItemJob());
        clock.advance(1);
        IngestJob upload = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        assertTrue(jobs.hasQueuedNewItemJob());
        assertEquals(upload.id(), jobs.claimNext().id());
        assertFalse(jobs.hasQueuedNewItemJob());
        assertEquals(reencode.id(), jobs.claimNext().id());
        assertEquals(quality.id(), jobs.claimNext().id());
        assertEquals("ready", fixtures.itemStatus(q)[0], "a quality job leaves the item ready");
        assertNull(jobs.claimNext());
    }

    private Object[] publishedRow(String item) throws SQLException {
        return db.read(c -> {
            try (var ps = c.prepareStatement("SELECT kind, duration_ms, output_profile, loudness_lufs, purged_at,"
                    + " original_file, original_codec, original_web, original_gain_db FROM items WHERE id = ?")) {
                ps.setString(1, item);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    double lufs = rs.getDouble(4);
                    Object l = rs.wasNull() ? null : lufs;
                    Object purged = rs.getObject(5);
                    String file = rs.getString(6);
                    String codec = rs.getString(7);
                    int web = rs.getInt(8);
                    double gain = rs.getDouble(9);
                    Object g = rs.wasNull() ? null : gain;
                    return new Object[] {rs.getString(1), rs.getLong(2), rs.getString(3), l, purged, file, codec, web, g};
                }
            }
        });
    }

    @Test
    void enqueueCreatesAQueuedJobAndAttachesToAnUnfinishedOne() throws SQLException {
        String item = fixtures.item("processing");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD);
        assertEquals(State.QUEUED, job.state());
        assertEquals(0, job.attempts());
        assertEquals(NOW, job.createdAt());
        assertEquals(job, jobs.get(job.id()).orElseThrow());
        assertArrayEquals(new String[] {"pending", null}, fixtures.itemStatus(item));

        // A second request (any kind) attaches to the job that is already there.
        assertEquals(job.id(), jobs.enqueue(item, Kind.REFETCH).id());
        assertEquals(job.id(), jobs.claimNext().id());
        assertEquals(job.id(), jobs.enqueue(item, Kind.UPLOAD).id());
        assertEquals(1, jobs.list(State.RUNNING, 10).size());
        assertTrue(jobs.list(State.QUEUED, 10).isEmpty());
    }

    @Test
    void claimTakesTheOldestJobOnceAndMarksTheItemProcessing() throws SQLException {
        String a = fixtures.item("pending");
        String b = fixtures.item("pending");
        IngestJob first = jobs.enqueue(a, Kind.UPLOAD);
        clock.advance(1000);
        IngestJob second = jobs.enqueue(b, Kind.YOUTUBE);
        clock.advance(1000);

        IngestJob claimed = jobs.claimNext();
        assertEquals(first.id(), claimed.id());
        assertEquals(State.RUNNING, claimed.state());
        assertEquals(1, claimed.attempts());
        assertEquals(NOW + 2000, claimed.startedAt());
        assertEquals(claimed, jobs.get(first.id()).orElseThrow());
        assertEquals("processing", fixtures.itemStatus(a)[0]);
        assertEquals("pending", fixtures.itemStatus(b)[0]);

        assertEquals(second.id(), jobs.claimNext().id());
        assertNull(jobs.claimNext());
    }

    @Test
    void completeMarksTheJobDoneAndTheItemReady() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD);
        jobs.claimNext();
        jobs.saveProgress(job.id(), 0.5, "half\n");
        clock.advance(5000);
        assertTrue(jobs.complete(job.id(), "all done\n"));
        IngestJob done = jobs.get(job.id()).orElseThrow();
        assertEquals(State.DONE, done.state());
        assertEquals(1.0, done.progress());
        assertEquals(NOW + 5000, done.finishedAt());
        assertEquals("all done\n", done.log());
        assertArrayEquals(new String[] {"ready", null}, fixtures.itemStatus(item));
        // The running job referenced the item; finishing is an unreference event.
        assertEquals(NOW + 5000, fixtures.lastReferencedAt(item));

        // Only a running job can finish.
        assertFalse(jobs.complete(job.id(), "again"));
        assertFalse(jobs.fail(job.id(), "late", "x"));
        assertFalse(jobs.release(job.id(), "x"));
        assertEquals(done, jobs.get(job.id()).orElseThrow());

        // A finished job doesn't block a new one; the item's latest job is the new one.
        clock.advance(1000);
        IngestJob reencode = jobs.enqueue(item, Kind.REENCODE);
        assertNotEquals(job.id(), reencode.id());
        assertEquals(reencode.id(), jobs.latestForItem(item).orElseThrow().id());
        assertEquals("ready", fixtures.itemStatus(item)[0], "a reencode leaves the item ready");
        assertTrue(jobs.latestForItem(fixtures.item("pending")).isEmpty());
    }

    @Test
    void failMarksTheItemFailedWithTheError() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.YOUTUBE);
        jobs.claimNext();
        jobs.saveProgress(job.id(), 0.25, null);
        assertTrue(jobs.fail(job.id(), "The video is a live stream", "log\n"));
        IngestJob failed = jobs.get(job.id()).orElseThrow();
        assertEquals(State.FAILED, failed.state());
        assertEquals(0.25, failed.progress(), 1e-9);
        assertNotNull(failed.finishedAt());
        assertArrayEquals(new String[] {"failed", "The video is a live stream"}, fixtures.itemStatus(item));
        assertEquals(NOW, fixtures.lastReferencedAt(item));

        // A retry (e.g. a YouTube request after 10 minutes) clears the error.
        jobs.enqueue(item, Kind.YOUTUBE);
        assertArrayEquals(new String[] {"pending", null}, fixtures.itemStatus(item));
    }

    @Test
    void longErrorsAreCutAndBlankOnesGetADefault() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD);
        jobs.claimNext();
        jobs.fail(job.id(), "x".repeat(5000), null);
        assertEquals(IngestJobs.MAX_ERROR_CHARS + 1, fixtures.itemStatus(item)[1].length());

        String other = fixtures.item("pending");
        IngestJob job2 = jobs.enqueue(other, Kind.UPLOAD);
        jobs.claimNext();
        jobs.fail(job2.id(), " ", null);
        assertEquals("Ingest failed", fixtures.itemStatus(other)[1]);
    }

    /**
     * A profile or bitrate change queues a re-encode for ready items of another (or no) encoding,
     * once: items from before CBR stored the bare profile name, which never matches.
     */
    @Test
    void enqueueReencodesQueuesReadyItemsOfAnotherProfile() throws SQLException {
        String current = fixtures.item("ready");
        String old = fixtures.item("ready");
        String otherRate = fixtures.item("ready");
        String olderArgs = fixtures.item("ready");
        String bare = fixtures.item("ready");
        String unknown = fixtures.item("ready");
        String purged = fixtures.item("purged");
        String failed = fixtures.item("failed");
        String busy = fixtures.item("ready");
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE items SET output_profile = ? WHERE id = ?")) {
                for (String id : List.of(old, purged, failed, busy)) {
                    ps.setString(1, "720p30");
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
                ps.setString(1, "1080p30-6000k-v2");
                ps.setString(2, current);
                ps.executeUpdate();
                ps.setString(1, "1080p30-6000k"); // the same encoding before ref=4 (encoder args v1)
                ps.setString(2, olderArgs);
                ps.executeUpdate();
                ps.setString(1, "1080p30-8000k-v2"); // same profile, another bitrate
                ps.setString(2, otherRate);
                ps.executeUpdate();
                ps.setString(1, "1080p30"); // before CBR: the profile name only
                ps.setString(2, bare);
                ps.executeUpdate();
                ps.setString(1, "480p25"); // a profile this version doesn't know
                ps.setString(2, unknown);
                ps.executeUpdate();
            }
            return null;
        });
        IngestJob busyJob = jobs.enqueue(busy, Kind.UPLOAD);
        clock.advance(1000);

        assertEquals(5, jobs.enqueueReencodes(OutputEncoding.defaultFor(OutputProfile.P1080_30)));
        List<IngestJob> queued = jobs.list(State.QUEUED, 10);
        assertEquals(6, queued.size());
        Set<String> reencoded = new HashSet<>();
        for (IngestJob j : queued) {
            if (j.kind() == Kind.REENCODE) {
                reencoded.add(j.itemId());
            }
        }
        assertEquals(Set.of(old, unknown, otherRate, olderArgs, bare), reencoded);
        assertEquals("ready", fixtures.itemStatus(old)[0], "a re-encode leaves the item ready");
        assertEquals("ready", fixtures.itemStatus(unknown)[0]);
        // they already have unfinished jobs now
        assertEquals(0, jobs.enqueueReencodes(OutputEncoding.defaultFor(OutputProfile.P1080_30)));

        // Other jobs are claimed first, even when they are newer.
        clock.advance(1000);
        String upload = fixtures.item("pending");
        IngestJob newer = jobs.enqueue(upload, Kind.UPLOAD);
        assertEquals(busyJob.id(), jobs.claimNext().id());
        assertEquals(newer.id(), jobs.claimNext().id());
        for (int i = 0; i < 5; i++) {
            assertEquals(Kind.REENCODE, jobs.claimNext().kind());
        }
        assertNull(jobs.claimNext());
    }

    @Test
    void aFailedReencodeLeavesTheItemReady() throws SQLException {
        String item = fixtures.item("ready");
        IngestJob job = jobs.enqueue(item, Kind.REENCODE);
        assertEquals("ready", fixtures.itemStatus(item)[0]);
        jobs.claimNext();
        assertEquals("ready", fixtures.itemStatus(item)[0]);
        jobs.fail(job.id(), "encoder broke", null);
        assertEquals(NOW, fixtures.lastReferencedAt(item), "any kind of job references its item");
        assertArrayEquals(new String[] {"ready", null}, fixtures.itemStatus(item));
        assertEquals(State.FAILED, jobs.get(job.id()).orElseThrow().state());
    }

    @Test
    void progressIsClampedAndOnlyStoredWhileRunning() throws SQLException {
        IngestJob job = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        jobs.saveProgress(job.id(), 0.5, "early");
        assertEquals(0.0, jobs.get(job.id()).orElseThrow().progress());
        assertNull(jobs.get(job.id()).orElseThrow().log());
        jobs.claimNext();
        jobs.saveProgress(job.id(), 1.7, "a\n");
        assertEquals(1.0, jobs.get(job.id()).orElseThrow().progress());
        jobs.saveProgress(job.id(), -3, "b\n");
        assertEquals(0.0, jobs.get(job.id()).orElseThrow().progress());
        jobs.saveProgress(job.id(), Double.NaN, "c\n");
        assertEquals(0.0, jobs.get(job.id()).orElseThrow().progress());
        jobs.saveProgress(job.id(), 0.42, "d\n");
        IngestJob read = jobs.get(job.id()).orElseThrow();
        assertEquals(0.42, read.progress(), 1e-9);
        assertEquals("d\n", read.log());
    }

    @Test
    void releaseRequeuesWithoutCountingTheAttempt() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD);
        jobs.claimNext();
        jobs.saveProgress(job.id(), 0.6, null);
        assertTrue(jobs.release(job.id(), "stopped\n"));
        IngestJob released = jobs.get(job.id()).orElseThrow();
        assertEquals(State.QUEUED, released.state());
        assertEquals(0, released.attempts());
        assertEquals(0.0, released.progress());
        assertEquals("stopped\n", released.log());
        // The log is kept for the next run; the item says processing until it runs again.
        IngestJob again = jobs.claimNext();
        assertEquals(1, again.attempts());
        assertEquals("stopped\n", again.log());
    }

    @Test
    void recoveryRequeuesJobsACrashLeftRunningAndGivesUpAfterMaxAttempts() throws SQLException {
        String a = fixtures.item("pending");
        String b = fixtures.item("pending");
        String c = fixtures.item("pending");
        // b crashed the server on every attempt.
        IngestJob jb = jobs.enqueue(b, Kind.YOUTUBE);
        for (int i = 0; i < IngestJobs.MAX_ATTEMPTS; i++) {
            if (i > 0) {
                assertEquals(new IngestJobs.Recovered(1, 0), jobs.recoverInterrupted());
            }
            assertEquals(jb.id(), jobs.claimNext().id());
        }
        clock.advance(1);
        IngestJob ja = jobs.enqueue(a, Kind.UPLOAD);
        clock.advance(1);
        IngestJob jc = jobs.enqueue(c, Kind.UPLOAD);
        assertEquals(ja.id(), jobs.claimNext().id()); // a: attempt 1
        jobs.saveProgress(ja.id(), 0.3, "line\n");
        assertEquals(IngestJobs.MAX_ATTEMPTS, jobs.get(jb.id()).orElseThrow().attempts());

        assertEquals(new IngestJobs.Recovered(1, 1), jobs.recoverInterrupted());
        IngestJob ra = jobs.get(ja.id()).orElseThrow();
        assertEquals(State.QUEUED, ra.state());
        assertEquals(0.0, ra.progress());
        assertTrue(ra.log().contains("Interrupted"), ra.log());
        assertEquals("pending", fixtures.itemStatus(a)[0]);

        IngestJob rb = jobs.get(jb.id()).orElseThrow();
        assertEquals(State.FAILED, rb.state());
        assertNotNull(rb.finishedAt());
        assertTrue(rb.log().contains("giving up"), rb.log());
        assertEquals("failed", fixtures.itemStatus(b)[0]);
        assertTrue(fixtures.itemStatus(b)[1].startsWith("Interrupted 3 times"), fixtures.itemStatus(b)[1]);
        assertEquals(NOW + 2, fixtures.lastReferencedAt(b), "giving up ends the job's reference");
        assertEquals(1_700_000_000_000L, fixtures.lastReferencedAt(a), "a requeued job still references its item");

        assertEquals(State.QUEUED, jobs.get(jc.id()).orElseThrow().state());
        assertEquals(new IngestJobs.Recovered(0, 0), jobs.recoverInterrupted());
    }

    @Test
    void enqueueJoinsTheCallersTransaction() throws SQLException {
        String item = fixtures.item("pending");
        assertThrows(IllegalStateException.class, () -> db.write(c -> {
            jobs.enqueue(item, Kind.UPLOAD);
            throw new IllegalStateException("roll back");
        }));
        assertTrue(jobs.latestForItem(item).isEmpty());
    }

    @Test
    void concurrentClaimsHandEachJobToOneWorker() throws Exception {
        int jobCount = 30;
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < jobCount; i++) {
            expected.add(jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD).id());
        }
        ConcurrentLinkedQueue<String> claimed = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    IngestJob job;
                    while ((job = jobs.claimNext()) != null) {
                        claimed.add(job.id());
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(jobCount, claimed.size(), "each job claimed exactly once");
        assertEquals(expected, new HashSet<>(claimed));
        for (IngestJob job : jobs.list(State.RUNNING, 100)) {
            assertEquals(1, job.attempts());
        }
    }

    // ------------------------------------------------------------------ encode jobs page

    private static List<String> ids(JobList.Page page) {
        List<String> out = new ArrayList<>();
        for (JobList.Row r : page.rows()) {
            out.add(r.job().id());
        }
        return out;
    }

    /** The active list: running first, then the queue in claim order with its positions. */
    @Test
    void activeListShowsRunningThenTheQueueInClaimOrder() throws SQLException {
        JobList list = new JobList(db, clock);
        IngestJob quality = jobs.enqueue(fixtures.item("ready"), Kind.QUALITY, Tier.QUALITY);
        clock.advance(1);
        IngestJob reencode = jobs.enqueue(fixtures.item("ready"), Kind.REENCODE);
        clock.advance(1);
        IngestJob running = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        clock.advance(1);
        IngestJob youtube = jobs.enqueue(fixtures.item("pending"), Kind.YOUTUBE);
        clock.advance(1);
        IngestJob upload = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        assertEquals(running.id(), jobs.claimNext().id());

        JobList.Page page = list.list(JobList.View.ACTIVE, null, 0, 50);
        assertEquals(List.of(running.id(), youtube.id(), upload.id(), reencode.id(), quality.id()), ids(page));
        assertNull(page.nextOffset());
        List<Integer> positions = new ArrayList<>();
        for (JobList.Row r : page.rows()) {
            positions.add(r.position());
        }
        assertEquals(java.util.Arrays.asList(null, 1, 2, 3, 4), positions);
        JobList.Row first = page.rows().get(0);
        assertEquals(State.RUNNING, first.job().state());
        assertEquals("Song", first.item().title());
        assertEquals("processing", first.item().status());
        assertNull(first.job().log(), "lists never carry the log");
        assertEquals(Tier.QUALITY, page.rows().get(4).job().tier());

        // paging, and the list matches what the workers take next
        JobList.Page one = list.list(JobList.View.ACTIVE, null, 0, 2);
        assertEquals(List.of(running.id(), youtube.id()), ids(one));
        assertEquals(2, one.nextOffset());
        assertEquals(List.of(upload.id(), reencode.id()), ids(list.list(JobList.View.ACTIVE, null, 2, 2)));
        for (String next : List.of(youtube.id(), upload.id(), reencode.id(), quality.id())) {
            assertEquals(next, jobs.claimNext().id());
        }
    }

    /** A user's lists hold only items in their library (shares count); positions stay global. */
    @Test
    void aUsersListHoldsOnlyTheirLibrarysItems() throws SQLException {
        JobList list = new JobList(db, clock);
        String bob = fixtures.newUser("bob");
        String carol = fixtures.newUser("carol");
        String others = fixtures.item("pending");
        fixtures.addToLibrary(others, carol, "upload");
        IngestJob othersJob = jobs.enqueue(others, Kind.UPLOAD);
        clock.advance(1);
        String own = fixtures.item("pending");
        fixtures.addToLibrary(own, bob, "youtube");
        IngestJob ownJob = jobs.enqueue(own, Kind.YOUTUBE);
        clock.advance(1);
        String shared = fixtures.item("ready");
        fixtures.addToLibrary(shared, carol, "upload");
        fixtures.addToLibrary(shared, bob, "share");
        IngestJob sharedJob = jobs.enqueue(shared, Kind.QUALITY, Tier.QUALITY);
        clock.advance(1);
        IngestJob nobodys = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD);

        JobList.Page bobs = list.list(JobList.View.ACTIVE, bob, 0, 50);
        assertEquals(List.of(ownJob.id(), sharedJob.id()), ids(bobs));
        assertEquals(2, bobs.rows().get(0).position(), "the place in the whole queue");
        assertEquals(4, bobs.rows().get(1).position());
        assertEquals(List.of(othersJob.id(), sharedJob.id()), ids(list.list(JobList.View.ACTIVE, carol, 0, 50)));
        assertEquals(4, ids(list.list(JobList.View.ACTIVE, null, 0, 50)).size());
        assertTrue(ids(list.list(JobList.View.ACTIVE, fixtures.newUser("dave"), 0, 50)).isEmpty());
        assertTrue(ids(list.list(JobList.View.ACTIVE, null, 0, 50)).contains(nobodys.id()));
    }

    /** Failed: only unresolved failures of items that aren't purged; done: the last 24 h. */
    @Test
    void failedAndDoneLists() throws SQLException {
        JobList list = new JobList(db, clock);
        String a = fixtures.item("pending");
        IngestJob aJob = jobs.enqueue(a, Kind.UPLOAD);
        jobs.claimNext();
        jobs.fail(aJob.id(), "broken", "log\n");
        clock.advance(1);
        String b = fixtures.item("pending");
        IngestJob bFirst = jobs.enqueue(b, Kind.YOUTUBE);
        jobs.claimNext();
        jobs.fail(bFirst.id(), "gone", null);
        clock.advance(1);
        IngestJob bSecond = jobs.enqueue(b, Kind.YOUTUBE);
        jobs.claimNext();
        jobs.complete(bSecond.id(), null);
        clock.advance(1);
        String purged = fixtures.item("pending");
        IngestJob pJob = jobs.enqueue(purged, Kind.UPLOAD);
        jobs.claimNext();
        jobs.fail(pJob.id(), "x", null);
        fixtures.status(purged, "purged");

        JobList.Page failed = list.list(JobList.View.FAILED, null, 0, 50);
        assertEquals(List.of(aJob.id()), ids(failed), "b's failure was replaced; the purged item's isn't actionable");
        assertEquals("broken", failed.rows().get(0).item().error());
        assertEquals(List.of(bSecond.id()), ids(list.list(JobList.View.DONE, null, 0, 50)));
        clock.advance(JobList.RECENT.toMillis() + 1);
        assertTrue(ids(list.list(JobList.View.DONE, null, 0, 50)).isEmpty());
        assertEquals(aJob.id(), list.get(aJob.id()).orElseThrow().job().id());
        assertTrue(list.get("nope").isEmpty());
    }

    @Test
    void retryQueuesTheSameKindAndTierAgain() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD, Tier.QUALITY);
        assertEquals(IngestJobs.RetryOutcome.NOT_FAILED, jobs.retry(job.id(), null).outcome());
        jobs.claimNext();
        assertEquals(IngestJobs.RetryOutcome.NOT_FAILED, jobs.retry(job.id(), null).outcome());
        jobs.fail(job.id(), "broken", null);
        assertEquals("failed", fixtures.itemStatus(item)[0]);
        clock.advance(10);

        List<String> audited = new ArrayList<>();
        IngestJobs.Retry retry = jobs.retry(job.id(), (c, j) -> audited.add(j.id()));
        assertEquals(IngestJobs.RetryOutcome.QUEUED, retry.outcome());
        assertNotEquals(job.id(), retry.job().id());
        assertEquals(Kind.UPLOAD, retry.job().kind());
        assertEquals(Tier.QUALITY, retry.job().tier());
        assertEquals(State.QUEUED, retry.job().state());
        assertEquals(List.of(job.id()), audited);
        assertArrayEquals(new String[] {"pending", null}, fixtures.itemStatus(item));
        // the old job is superseded now
        assertEquals(IngestJobs.RetryOutcome.SUPERSEDED, jobs.retry(job.id(), null).outcome());
        assertEquals(IngestJobs.RetryOutcome.NOT_FOUND, jobs.retry("nope", null).outcome());

        // a re-encode leaves the item ready; a purged item can't be retried
        String ready = fixtures.item("ready");
        IngestJob re = jobs.enqueue(ready, Kind.REENCODE);
        while (true) {
            IngestJob claimed = jobs.claimNext();
            if (claimed.id().equals(re.id())) {
                break;
            }
        }
        jobs.fail(re.id(), "x", null);
        IngestJobs.Retry again = jobs.retry(re.id(), null);
        assertEquals(Kind.REENCODE, again.job().kind());
        assertEquals("ready", fixtures.itemStatus(ready)[0]);
        String gone = fixtures.item("pending");
        IngestJob g = jobs.enqueue(gone, Kind.YOUTUBE);
        assertEquals(IngestJobs.CancelOutcome.CANCELLED, jobs.cancel(g.id(), null));
        fixtures.status(gone, "purged");
        assertEquals(IngestJobs.RetryOutcome.ITEM_PURGED, jobs.retry(g.id(), null).outcome());
    }

    @Test
    void cancelDropsAQueuedJob() throws SQLException {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.YOUTUBE);
        List<String> audited = new ArrayList<>();
        assertEquals(IngestJobs.CancelOutcome.CANCELLED, jobs.cancel(job.id(), (c, j) -> audited.add(j.id())));
        assertEquals(List.of(job.id()), audited);
        IngestJob cancelled = jobs.get(job.id()).orElseThrow();
        assertEquals(State.FAILED, cancelled.state());
        assertEquals(NOW, cancelled.finishedAt());
        assertTrue(cancelled.log().contains("Cancelled by an administrator"), cancelled.log());
        assertArrayEquals(new String[] {"failed", IngestJobs.CANCELLED_ERROR}, fixtures.itemStatus(item));
        assertNull(jobs.claimNext(), "a cancelled job never runs");
        assertEquals(IngestJobs.CancelOutcome.NOT_QUEUED, jobs.cancel(job.id(), null));
        assertEquals(IngestJobs.CancelOutcome.NOT_FOUND, jobs.cancel("nope", null));

        // a quality job only drops the job; the item stays ready
        String ready = fixtures.item("ready");
        IngestJob quality = jobs.enqueue(ready, Kind.QUALITY, Tier.QUALITY);
        assertEquals(IngestJobs.CancelOutcome.CANCELLED, jobs.cancel(quality.id(), null));
        assertArrayEquals(new String[] {"ready", null}, fixtures.itemStatus(ready));

        // a running job can't be cancelled
        String busy = fixtures.item("pending");
        IngestJob running = jobs.enqueue(busy, Kind.UPLOAD);
        jobs.claimNext();
        assertEquals(IngestJobs.CancelOutcome.NOT_QUEUED, jobs.cancel(running.id(), null));
    }
}
