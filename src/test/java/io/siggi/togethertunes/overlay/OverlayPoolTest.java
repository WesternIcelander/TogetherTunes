package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.overlay.OverlayPool.Job;
import io.siggi.togethertunes.overlay.OverlayPool.Priority;
import io.siggi.togethertunes.overlay.OverlayPool.State;
import io.siggi.togethertunes.overlay.OverlayPool.Task;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OverlayPoolTest {
    private OverlayPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /** A task that says it started and then waits for {@link #release}. */
    private static final class Gate implements Task {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        volatile boolean interrupted;

        @Override
        public void run(Job job) throws Exception {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            }
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS), "task did not start");
        }
    }

    private static State end(Job job) throws Exception {
        return job.end().get(5, TimeUnit.SECONDS);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached in time");
            Thread.sleep(5);
        }
    }

    @Test
    void runsJobsAndReportsTheirEnd() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Job job = pool.submit("a", Priority.NORMAL, j -> j.progress(0.5));
        assertEquals(State.DONE, end(job));
        assertEquals(State.DONE, job.state());
        assertEquals(1.0, job.currentProgress());
        assertNull(job.failure());
        // ended jobs aren't kept; the key can be used again
        eventually(() -> pool.job("a").isEmpty());
        Job again = pool.submit("a", Priority.NORMAL, j -> { });
        assertFalse(again == job);
        assertEquals(State.DONE, end(again));
    }

    @Test
    void highPriorityGoesFirstThenFifo() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Gate blocker = new Gate();
        Job b = pool.submit("blocker", Priority.NORMAL, blocker);
        blocker.awaitStarted();
        List<String> order = new CopyOnWriteArrayList<>();
        Job n1 = pool.submit("n1", Priority.NORMAL, j -> order.add("n1"));
        Job n2 = pool.submit("n2", Priority.NORMAL, j -> order.add("n2"));
        Job h1 = pool.submit("h1", Priority.HIGH, j -> order.add("h1"));
        Job h2 = pool.submit("h2", Priority.HIGH, j -> order.add("h2"));
        assertEquals(4, pool.queued());
        assertEquals(1, pool.running());
        assertEquals(State.QUEUED, n1.state());
        blocker.release.countDown();
        for (Job j : List.of(b, n1, n2, h1, h2)) {
            assertEquals(State.DONE, end(j));
        }
        assertEquals(List.of("h1", "h2", "n1", "n2"), order);
    }

    @Test
    void sameKeyGivesTheSameJobAndCanRaiseItsPriority() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Gate blocker = new Gate();
        pool.submit("blocker", Priority.NORMAL, blocker);
        blocker.awaitStarted();
        // a running job is returned as is
        assertSame(pool.job("blocker").orElseThrow(), pool.submit("blocker", Priority.HIGH, j -> { }));
        assertEquals(1, blocker.runs.get());

        List<String> order = new CopyOnWriteArrayList<>();
        pool.submit("other", Priority.NORMAL, j -> order.add("other"));
        Job next = pool.submit("next", Priority.NORMAL, j -> order.add("next"));
        Job h = pool.submit("h", Priority.HIGH, j -> order.add("h"));
        // "next" became current: moved up behind the HIGH job already waiting
        Job same = pool.submit("next", Priority.HIGH, j -> order.add("replacement task"));
        assertSame(next, same);
        assertEquals(Priority.HIGH, next.priority());
        // a lower priority never lowers it
        assertSame(next, pool.submit("next", Priority.NORMAL, j -> { }));
        assertEquals(Priority.HIGH, next.priority());
        assertEquals(3, pool.queued());
        blocker.release.countDown();
        assertEquals(State.DONE, end(next));
        assertEquals(State.DONE, end(h));
        eventually(() -> order.size() == 3);
        assertEquals(List.of("h", "next", "other"), order);
    }

    @Test
    void busyWhenEveryWorkerIsTakenOrPromised() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        assertFalse(pool.busy());
        Gate a = new Gate();
        pool.submit("a", Priority.NORMAL, a);
        assertTrue(pool.busy()); // queued for the only worker, or already running
        a.awaitStarted();
        assertTrue(pool.busy());
        pool.resize(2);
        assertFalse(pool.busy());
        Gate b = new Gate();
        pool.submit("b", Priority.NORMAL, b);
        b.awaitStarted();
        assertEquals(2, pool.running());
        assertTrue(pool.busy());
        a.release.countDown();
        b.release.countDown();
        eventually(() -> !pool.busy());
    }

    @Test
    void cancellingAQueuedJobDropsIt() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Gate blocker = new Gate();
        pool.submit("blocker", Priority.NORMAL, blocker);
        blocker.awaitStarted();
        AtomicInteger ran = new AtomicInteger();
        Job queued = pool.submit("q", Priority.NORMAL, j -> ran.incrementAndGet());
        assertTrue(pool.cancel("q"));
        assertEquals(State.CANCELLED, end(queued));
        assertTrue(queued.cancelled());
        assertTrue(pool.job("q").isEmpty());
        assertEquals(0, pool.queued());
        assertFalse(pool.cancel("q"));
        assertFalse(pool.cancel("unknown"));
        blocker.release.countDown();
        Job after = pool.submit("after", Priority.NORMAL, j -> { });
        assertEquals(State.DONE, end(after));
        assertEquals(0, ran.get());
    }

    @Test
    void cancellingARunningJobInterruptsItAndTheWorkerCarriesOn() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Gate running = new Gate();
        Job job = pool.submit("r", Priority.HIGH, running);
        running.awaitStarted();
        job.progress(0.6);
        assertEquals(0.6, job.currentProgress());
        assertTrue(pool.cancel("r"));
        assertEquals(State.CANCELLED, end(job));
        assertTrue(running.interrupted);
        assertNull(job.failure());
        // the interrupt doesn't leak into the next job on the same worker
        Job next = pool.submit("next", Priority.NORMAL, j -> {
            assertFalse(Thread.currentThread().isInterrupted());
            Thread.sleep(1);
        });
        assertEquals(State.DONE, end(next));
    }

    @Test
    void aTaskThatSeesTheCancelAndReturnsEndsCancelled() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        CountDownLatch started = new CountDownLatch(1);
        Job job = pool.submit("polite", Priority.NORMAL, j -> {
            started.countDown();
            while (!j.cancelled()) {
                Thread.onSpinWait();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        pool.cancel("polite");
        assertEquals(State.CANCELLED, end(job));
    }

    @Test
    void aFailedJobIsReportedAndTheWorkerCarriesOn() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Job bad = pool.submit("bad", Priority.NORMAL, j -> {
            throw new IllegalStateException("SPS differs from the reference");
        });
        assertEquals(State.FAILED, end(bad));
        assertInstanceOf(IllegalStateException.class, bad.failure());
        Job good = pool.submit("good", Priority.NORMAL, j -> { });
        assertEquals(State.DONE, end(good));
    }

    @Test
    void resizeGrowsAtOnceAndShrinksAfterRunningJobs() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        assertEquals(1, pool.liveWorkers());
        pool.resize(3);
        assertEquals(3, pool.workers());
        Gate a = new Gate();
        Gate b = new Gate();
        Gate c = new Gate();
        pool.submit("a", Priority.NORMAL, a);
        pool.submit("b", Priority.NORMAL, b);
        pool.submit("c", Priority.NORMAL, c);
        a.awaitStarted();
        b.awaitStarted();
        c.awaitStarted();
        assertEquals(3, pool.running());

        pool.resize(1);
        assertEquals(1, pool.workers());
        // running jobs aren't touched
        Thread.sleep(50);
        assertEquals(3, pool.liveWorkers());
        assertFalse(a.interrupted || b.interrupted || c.interrupted);
        Gate d = new Gate();
        Job dj = pool.submit("d", Priority.NORMAL, d);
        a.release.countDown();
        b.release.countDown();
        // a's and b's workers stop instead of taking d; d waits for the one worker left
        eventually(() -> pool.liveWorkers() == 1);
        Thread.sleep(50);
        assertEquals(State.QUEUED, dj.state());
        c.release.countDown();
        d.awaitStarted();
        d.release.countDown();
        assertEquals(State.DONE, end(dj));
        eventually(() -> pool.liveWorkers() == 1);

        assertThrows(IllegalArgumentException.class, () -> pool.resize(0));
        assertThrows(IllegalArgumentException.class, () -> new OverlayPool(0));
    }

    @Test
    void jobsSubmittedBeforeStartWaitForIt() throws Exception {
        pool = new OverlayPool(2);
        Job job = pool.submit("early", Priority.NORMAL, j -> { });
        pool.resize(1); // before start only the count changes
        assertEquals(0, pool.liveWorkers());
        assertEquals(State.QUEUED, job.state());
        pool.start();
        assertEquals(State.DONE, end(job));
        assertEquals(1, pool.liveWorkers());
        assertThrows(IllegalStateException.class, pool::start);
    }

    @Test
    void closeCancelsEverythingAndKeepsNothing() throws Exception {
        pool = new OverlayPool(1);
        pool.start();
        Gate running = new Gate();
        Job r = pool.submit("r", Priority.NORMAL, running);
        running.awaitStarted();
        Job q = pool.submit("q", Priority.HIGH, j -> { });
        pool.close();
        assertEquals(State.CANCELLED, end(r));
        assertEquals(State.CANCELLED, end(q));
        assertTrue(running.interrupted);
        assertEquals(0, pool.liveWorkers());
        assertTrue(pool.job("r").isEmpty());
        assertTrue(pool.job("q").isEmpty());
        assertThrows(IllegalStateException.class, () -> pool.submit("x", Priority.NORMAL, j -> { }));
        pool.close(); // twice is fine

        // a new pool (the next start) starts empty: nothing was persisted
        OverlayPool next = new OverlayPool(1);
        try {
            next.start();
            assertTrue(next.job("r").isEmpty());
            assertEquals(0, next.queued());
        } finally {
            next.close();
        }
    }
}
