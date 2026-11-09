package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
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
                "720p30", null, -9.0),
                items.get(item).orElseThrow());
        assertTrue(items.get("nope").isEmpty());

        items.published(item, false, 61_234, io.siggi.togethertunes.config.OutputProfile.P1080_30, -17.25,
                new Originals.Original("original.flac", "flac/flac", true, 3.25));
        assertArrayEquals(new Object[] {"audio", 61_234L, "1080p30", -17.25, null, "original.flac", "flac/flac", 1, 3.25},
                publishedRow(item));
        items.published(item, false, 1, io.siggi.togethertunes.config.OutputProfile.P720_30, null,
                new Originals.Original("original.wma", "wmav2/asf", false, null));
        assertArrayEquals(new Object[] {"audio", 1L, "720p30", null, null, "original.wma", "wmav2/asf", 0, null},
                publishedRow(item));
        // a video (or YouTube) publish clears an earlier original
        items.published(item, true, 2, io.siggi.togethertunes.config.OutputProfile.P720_30, -9.5, null);
        assertArrayEquals(new Object[] {"video", 2L, "720p30", -9.5, null, null, null, 0, null}, publishedRow(item));
        assertThrows(SQLException.class, () -> items.published("nope", false, 1,
                io.siggi.togethertunes.config.OutputProfile.P720_30, null, null));
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

    /** A profile change queues a re-encode for ready items of another (or no) profile, once. */
    @Test
    void enqueueReencodesQueuesReadyItemsOfAnotherProfile() throws SQLException {
        String current = fixtures.item("ready");
        String old = fixtures.item("ready");
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
                ps.setString(1, "1080p30");
                ps.setString(2, current);
                ps.executeUpdate();
                ps.setString(1, "480p25"); // a profile this version doesn't know
                ps.setString(2, unknown);
                ps.executeUpdate();
            }
            return null;
        });
        IngestJob busyJob = jobs.enqueue(busy, Kind.UPLOAD);
        clock.advance(1000);

        assertEquals(2, jobs.enqueueReencodes(io.siggi.togethertunes.config.OutputProfile.P1080_30));
        List<IngestJob> queued = jobs.list(State.QUEUED, 10);
        assertEquals(3, queued.size());
        Set<String> reencoded = new HashSet<>();
        for (IngestJob j : queued) {
            if (j.kind() == Kind.REENCODE) {
                reencoded.add(j.itemId());
            }
        }
        assertEquals(Set.of(old, unknown), reencoded);
        assertEquals("ready", fixtures.itemStatus(old)[0], "a re-encode leaves the item ready");
        assertEquals("ready", fixtures.itemStatus(unknown)[0]);
        // they already have unfinished jobs now
        assertEquals(0, jobs.enqueueReencodes(io.siggi.togethertunes.config.OutputProfile.P1080_30));

        // Other jobs are claimed first, even when they are newer.
        clock.advance(1000);
        String upload = fixtures.item("pending");
        IngestJob newer = jobs.enqueue(upload, Kind.UPLOAD);
        assertEquals(busyJob.id(), jobs.claimNext().id());
        assertEquals(newer.id(), jobs.claimNext().id());
        assertEquals(Kind.REENCODE, jobs.claimNext().kind());
        assertEquals(Kind.REENCODE, jobs.claimNext().kind());
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
}
