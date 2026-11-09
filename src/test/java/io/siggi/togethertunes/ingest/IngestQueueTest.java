package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link IngestQueue}: workers, outcomes, progress, and surviving restarts (SQLite file). */
class IngestQueueTest {
    private static final Duration LONG_POLL = Duration.ofHours(1);

    @TempDir
    Path dir;

    private Database db;
    private IngestJobs jobs;
    private IngestFixtures fixtures;
    private final List<IngestQueue> queues = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        jobs = new IngestJobs(db);
        fixtures = new IngestFixtures(db);
    }

    @AfterEach
    void tearDown() {
        for (IngestQueue q : queues) {
            q.close();
        }
        db.close();
    }

    private IngestQueue queue(int workers, IngestQueue.Handler handler, Duration poll) {
        IngestQueue q = new IngestQueue(jobs, workers, handler, Clock.systemUTC(), poll, Duration.ZERO);
        queues.add(q);
        return q;
    }

    private IngestJob awaitState(String jobId, State state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            IngestJob job = jobs.get(jobId).orElseThrow();
            if (job.state() == state) {
                return job;
            }
            Thread.sleep(20);
        }
        fail("job " + jobId + " never reached " + state + ": " + jobs.get(jobId).orElseThrow());
        return null;
    }

    @Test
    void runsJobsToDoneOrFailedWithTheirLog() throws Exception {
        IngestQueue q = queue(1, job -> {
            job.log().append("working on " + job.job().kind().wire());
            if (job.job().kind() == Kind.YOUTUBE) {
                throw new IngestFailure("The video is a live stream");
            }
        }, LONG_POLL);
        q.start();
        String good = fixtures.item("pending");
        String bad = fixtures.item("pending");
        IngestJob ok = q.enqueue(good, Kind.UPLOAD);
        IngestJob ko = q.enqueue(bad, Kind.YOUTUBE);

        IngestJob done = awaitState(ok.id(), State.DONE);
        assertEquals(1, done.attempts());
        assertTrue(done.log().contains("upload job started (attempt 1)"), done.log());
        assertTrue(done.log().contains("working on upload"), done.log());
        assertTrue(done.log().contains("[ingest] Done after"), done.log());
        assertEquals("ready", fixtures.itemStatus(good)[0]);

        IngestJob failed = awaitState(ko.id(), State.FAILED);
        assertTrue(failed.log().contains("[ingest] Failed after"), failed.log());
        assertTrue(failed.log().contains("live stream"), failed.log());
        assertEquals("failed", fixtures.itemStatus(bad)[0]);
        assertEquals("The video is a live stream", fixtures.itemStatus(bad)[1]);
    }

    @Test
    void unexpectedExceptionsFailTheJobAndTheWorkerCarriesOn() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        IngestQueue q = queue(1, job -> {
            if (runs.incrementAndGet() == 1) {
                throw new NullPointerException();
            }
        }, LONG_POLL);
        q.start();
        String first = fixtures.item("pending");
        IngestJob a = q.enqueue(first, Kind.UPLOAD);
        IngestJob b = q.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        awaitState(a.id(), State.FAILED);
        assertEquals("NullPointerException", fixtures.itemStatus(first)[1]);
        awaitState(b.id(), State.DONE);
    }

    @Test
    void runsAtMostWorkersJobsAtOnce() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        IngestQueue q = queue(2, job -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            twoRunning.countDown();
            release.await();
            running.decrementAndGet();
        }, LONG_POLL);
        q.start();
        List<IngestJob> queued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queued.add(q.enqueue(fixtures.item("pending"), Kind.UPLOAD));
        }
        assertTrue(twoRunning.await(20, TimeUnit.SECONDS), "two workers run in parallel");
        Thread.sleep(200);
        assertEquals(2, running.get());
        assertEquals(2, jobs.list(State.RUNNING, 10).size());
        assertEquals(3, jobs.list(State.QUEUED, 10).size());
        release.countDown();
        for (IngestJob job : queued) {
            awaitState(job.id(), State.DONE);
        }
        assertEquals(2, peak.get());
    }

    @Test
    void progressAndLogAreStoredWhileTheJobRuns() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        IngestQueue q = queue(1, job -> {
            job.log().append("frame=100");
            job.progress(0.37);
            reported.countDown();
            finish.await();
        }, LONG_POLL);
        q.start();
        IngestJob job = q.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        assertTrue(reported.await(20, TimeUnit.SECONDS));
        IngestJob mid = jobs.get(job.id()).orElseThrow();
        assertEquals(State.RUNNING, mid.state());
        assertEquals(0.37, mid.progress(), 1e-9);
        assertTrue(mid.log().contains("frame=100"), mid.log());
        finish.countDown();
        assertEquals(1.0, awaitState(job.id(), State.DONE).progress());
    }

    @Test
    void progressWritesAreThrottled() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        IngestQueue q = new IngestQueue(jobs, 1, job -> {
            job.progress(0.1); // stored: the first report
            job.progress(0.2); // within the interval: not stored
            reported.countDown();
            finish.await();
        }, Clock.systemUTC(), LONG_POLL, Duration.ofHours(1));
        queues.add(q);
        q.start();
        IngestJob job = q.enqueue(fixtures.item("pending"), Kind.UPLOAD);
        assertTrue(reported.await(20, TimeUnit.SECONDS));
        assertEquals(0.1, jobs.get(job.id()).orElseThrow().progress(), 1e-9);
        finish.countDown();
        awaitState(job.id(), State.DONE);
    }

    @Test
    void workersPickUpJobsQueuedWithoutWakingThem() throws Exception {
        IngestQueue q = queue(1, job -> { }, Duration.ofMillis(100));
        q.start();
        IngestJob job = jobs.enqueue(fixtures.item("pending"), Kind.UPLOAD); // no wake()
        awaitState(job.id(), State.DONE);
    }

    @Test
    void aShutdownRequeuesTheRunningJobAndTheNextStartFinishesIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        IngestQueue first = queue(1, job -> {
            job.log().append("first run");
            started.countDown();
            Thread.sleep(60_000); // interrupted by close()
        }, LONG_POLL);
        first.start();
        String item = fixtures.item("pending");
        IngestJob job = first.enqueue(item, Kind.UPLOAD);
        assertTrue(started.await(20, TimeUnit.SECONDS));
        first.close();
        assertTrue(first.closed());

        IngestJob released = jobs.get(job.id()).orElseThrow();
        assertEquals(State.QUEUED, released.state());
        assertEquals(0, released.attempts(), "a shutdown doesn't count as an attempt");
        assertTrue(released.log().contains("Stopped by a server shutdown"), released.log());

        IngestQueue second = queue(1, j -> j.log().append("second run"), LONG_POLL);
        assertEquals(new IngestJobs.Recovered(0, 0), second.start());
        IngestJob done = awaitState(job.id(), State.DONE);
        assertEquals(1, done.attempts());
        assertTrue(done.log().indexOf("first run") < done.log().indexOf("second run"), done.log());
        assertEquals("ready", fixtures.itemStatus(item)[0]);
    }

    @Test
    void aJobACrashLeftRunningRunsAgainAfterTheRestart() throws Exception {
        String item = fixtures.item("pending");
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD);
        jobs.claimNext(); // the "crashed" process took it and never finished
        IngestQueue q = queue(1, j -> { }, LONG_POLL);
        assertEquals(new IngestJobs.Recovered(1, 0), q.start());
        IngestJob done = awaitState(job.id(), State.DONE);
        assertEquals(2, done.attempts());
        assertTrue(done.log().contains("Interrupted"), done.log());
        assertTrue(done.log().contains("attempt 2"), done.log());
    }

    @Test
    void startsOnceAndNeedsAWorker() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new IngestQueue(jobs, 0, j -> { }));
        IngestQueue q = queue(3, j -> { }, LONG_POLL);
        assertEquals(3, q.workers());
        q.start();
        assertThrows(IllegalStateException.class, q::start);
        q.close();
        q.close(); // idempotent
    }
}
