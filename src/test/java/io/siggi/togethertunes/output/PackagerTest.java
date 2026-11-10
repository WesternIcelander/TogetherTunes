package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.SourcesTest.AVCC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.output.FillerSource.Filler;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pacing and consumers ({@code docs/program-output.md#pacing}, {@code #direct-mpeg-ts-ts}). */
class PackagerTest {
    static final long S0 = OutputClock.START;
    static final long U = 180_000;
    static final long T0 = 1_700_000_000_000L;
    static final long MS = 1_000_000L;

    /** Virtual time: sleeping moves it forward at once. */
    static final class FakeTime implements TimeSource {
        final AtomicLong nanos = new AtomicLong(5_000_000_000L);

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        @Override
        public long millis() {
            return T0 + (nanos.get() - 5_000_000_000L) / MS;
        }

        @Override
        public void sleepNanos(long n) throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (n > 0) {
                nanos.addAndGet(n);
            }
        }

        /** ms since the clock started. */
        long elapsedMs() {
            return (nanos.get() - 5_000_000_000L) / MS;
        }

        void advanceMs(long ms) {
            nanos.addAndGet(ms * MS);
        }
    }

    /** Units of {@code ticks} each, one per permit; records when each was built. */
    static class FakeProducer implements UnitProducer {
        final TimeSource time;
        final long ticks;
        final Semaphore permits = new Semaphore(0);
        final List<Long> builtAtNanos = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger built = new AtomicInteger();
        volatile Runnable duringBuild;
        volatile IOException failWith;
        final AtomicReference<Thread> closedOn = new AtomicReference<>();
        long next = S0;

        FakeProducer(TimeSource time, long ticks) {
            this.time = time;
            this.ticks = ticks;
        }

        @Override
        public long nextUnitStart() {
            return next;
        }

        @Override
        public Unit nextUnit() throws IOException {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
            if (failWith != null) {
                throw failWith;
            }
            builtAtNanos.add(time.nanoTime());
            Runnable r = duringBuild;
            if (r != null) {
                duringBuild = null;
                r.run();
            }
            long s = next;
            next += ticks;
            built.incrementAndGet();
            return new Unit(s, s + ticks, s + ticks, List.of(new Unit.Video(new byte[0], s, true)), List.of(),
                    null, false);
        }

        @Override
        public void close() {
            closedOn.set(Thread.currentThread());
        }
    }

    /** "Muxes" a unit to its start PTS. */
    static byte[] fakeMux(Unit u) {
        return ByteBuffer.allocate(8).putLong(u.startPts()).array();
    }

    final List<Packager> packagers = new ArrayList<>();

    @AfterEach
    void stop() {
        packagers.forEach(Packager::close);
    }

    Packager packager(FakeProducer p, TimeSource time, PacingClock clock) {
        Packager pk = new Packager("room1", p, PackagerTest::fakeMux, clock, time);
        packagers.add(pk);
        return pk;
    }

    static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void emitsEachUnitLeadAheadOfItsTime() throws Exception {
        FakeTime time = new FakeTime();
        PacingClock clock = new PacingClock(time);
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, clock);
        ConsumerQueue q = pk.subscribe(16, false);
        pk.start();
        prod.permits.release(6);
        List<TsUnit> got = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            got.add(q.poll(10_000));
        }
        for (int k = 0; k < 6; k++) {
            TsUnit u = got.get(k);
            assertEquals(k, u.sequence());
            assertEquals(S0 + k * U, u.startPts());
            assertEquals(S0 + k * U, ByteBuffer.wrap(u.ts()).getLong());
            // unit k is due at k·2 s and goes out LEAD (1 s) before; the first one at once
            long builtMs = (prod.builtAtNanos.get(k) - 5_000_000_000L) / MS;
            assertEquals(Math.max(0, k * 2000 - Packager.LEAD_MS), builtMs, "unit " + k);
        }
        assertEquals(T0, clock.serverMsAt(S0));
        assertEquals(T0 + 2000, clock.serverMsAt(S0 + U));
        assertEquals(T0 + 20, clock.serverMsAt(S0 + 1920 * 15 / 16)); // 1800 ticks = 20 ms
        assertEquals(0, clock.shiftNanos());
    }

    @Test
    void neverMoreThanLeadAheadOfRealTime() throws Exception {
        FakeTime time = new FakeTime();
        PacingClock clock = new PacingClock(time);
        // odd unit lengths (a unit after a switch is up to one audio frame longer)
        FakeProducer prod = new FakeProducer(time, U + 1919);
        Packager pk = packager(prod, time, clock);
        ConsumerQueue q = pk.subscribe(64, false);
        pk.start();
        prod.permits.release(40);
        for (int i = 0; i < 40; i++) {
            TsUnit u = q.poll(10_000);
            long built = prod.builtAtNanos.get(i);
            long due = clock.nanoTimeAt(u.startPts());
            assertTrue(due - built <= Packager.LEAD_MS * MS, "unit " + i + " too early");
            if (i > 0) {
                assertEquals(Packager.LEAD_MS * MS, due - built, 1); // and not late either
            }
        }
    }

    @Test
    void slowConsumerIsDisconnectedOthersKeepGoing() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue slow = pk.subscribe(false);
        AtomicInteger closes = new AtomicInteger();
        slow.onClose(closes::incrementAndGet);
        ConsumerQueue fast = pk.subscribe(false);
        assertEquals(2, pk.consumerCount());
        pk.start();
        List<Long> fastSeq = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            prod.permits.release();
            fastSeq.add(fast.poll(10_000).sequence());
            if (i < 4) {
                assertFalse(slow.isClosed());
                assertEquals(i + 1, slow.size());
            }
        }
        // 4 units queued; the 5th didn't fit
        assertEquals(ConsumerQueue.CloseReason.OVERFLOW, slow.closeReason());
        assertEquals(1, closes.get());
        assertEquals(0, slow.size());
        assertNull(slow.take(), "a dropped consumer gets nothing more");
        assertEquals(1, pk.consumerCount());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), fastSeq);
        assertFalse(fast.isClosed());
        // a close action set after the fact runs at once
        AtomicBoolean late = new AtomicBoolean();
        slow.onClose(() -> late.set(true));
        assertTrue(late.get());
    }

    @Test
    void joinNextAndJoinLatest() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue first = pk.subscribe(true); // nothing emitted yet: just the next one
        assertNull(pk.latest());
        pk.start();
        prod.permits.release(2);
        assertEquals(0, first.poll(10_000).sequence());
        assertEquals(1, first.poll(10_000).sequence());
        await(() -> pk.latest() != null && pk.latest().sequence() == 1);
        ConsumerQueue next = pk.subscribe(false);
        ConsumerQueue latest = pk.subscribe(true);
        assertEquals(1, latest.size());
        prod.permits.release();
        assertEquals(2, next.poll(10_000).sequence());
        assertEquals(1, latest.poll(10_000).sequence());
        assertEquals(2, latest.poll(10_000).sequence());
        assertSame(pk.latest().ts(), first.poll(10_000).ts(), "every consumer shares the same bytes");
    }

    @Test
    void stallMovesPacingForwardInsteadOfBursting() throws Exception {
        FakeTime time = new FakeTime();
        PacingClock clock = new PacingClock(time);
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, clock);
        ConsumerQueue q = pk.subscribe(16, false);
        pk.start();
        prod.permits.release(3);
        for (int i = 0; i < 3; i++) {
            q.poll(10_000);
        }
        // building unit 3 (due 5 s, sent at 5 s) takes 10 s; unit 4 would be 8 s late (> MAX_LAG)
        await(() -> prod.built.get() == 3);
        prod.duringBuild = () -> time.advanceMs(10_000);
        prod.permits.release(4);
        for (int i = 0; i < 4; i++) {
            q.poll(10_000);
        }
        List<Long> at = new ArrayList<>();
        for (long n : prod.builtAtNanos) {
            at.add((n - 5_000_000_000L) / MS);
        }
        assertEquals(List.of(0L, 1000L, 3000L, 5000L, 15000L, 17000L, 19000L), at);
        assertEquals(8000 * MS, clock.shiftNanos());
        assertEquals(T0 + 4 * 2000 + 8000, clock.serverMsAt(S0 + 4 * U));
    }

    @Test
    void smallLagCatchesUpWithoutMovingThePacing() throws Exception {
        FakeTime time = new FakeTime();
        PacingClock clock = new PacingClock(time);
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, clock);
        ConsumerQueue q = pk.subscribe(16, false);
        pk.start();
        prod.permits.release(2);
        q.poll(10_000);
        q.poll(10_000);
        await(() -> prod.built.get() == 2);
        prod.duringBuild = () -> time.advanceMs(3000);
        prod.permits.release(3);
        for (int i = 0; i < 3; i++) {
            q.poll(10_000);
        }
        List<Long> at = new ArrayList<>();
        for (long n : prod.builtAtNanos) {
            at.add((n - 5_000_000_000L) / MS);
        }
        assertEquals(List.of(0L, 1000L, 3000L, 6000L, 7000L), at);
        assertEquals(0, clock.shiftNanos());
    }

    @Test
    void failureStopsTheOutputAndClosesConsumers() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue q = pk.subscribe(false);
        CountDownLatch closed = new CountDownLatch(1);
        q.onClose(closed::countDown);
        pk.start();
        prod.permits.release();
        assertNotNull(q.poll(10_000));
        prod.failWith = new IOException("bad sample");
        prod.permits.release();
        assertNull(q.take());
        assertTrue(closed.await(10, TimeUnit.SECONDS));
        assertEquals(ConsumerQueue.CloseReason.STOPPED, q.closeReason());
        await(() -> prod.closedOn.get() != null);
        assertFalse(pk.isRunning());
        assertInstanceOf(IOException.class, pk.failure());
        assertEquals("packager-room1", prod.closedOn.get().getName());
        assertTrue(pk.subscribe(false).isClosed());
    }

    @Test
    void closeStopsThreadConsumersAndProducer() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue q = pk.subscribe(false);
        AtomicBoolean sinkClosed = new AtomicBoolean();
        List<TsUnit> sinkGot = Collections.synchronizedList(new ArrayList<>());
        pk.addSink(new UnitSink() {
            @Override
            public boolean deliver(TsUnit unit) {
                sinkGot.add(unit);
                return true;
            }

            @Override
            public void close() {
                sinkClosed.set(true);
            }
        });
        pk.start();
        assertThrows(IllegalStateException.class, pk::start);
        prod.permits.release(2);
        q.poll(10_000);
        q.poll(10_000);
        AtomicReference<Thread> packagerThread = new AtomicReference<>();
        await(() -> {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("packager-room1")) {
                    packagerThread.set(t);
                    return true;
                }
            }
            return false;
        });
        assertFalse(packagerThread.get().isVirtual(), "one platform thread per output");
        pk.close(); // the thread is waiting in nextUnit()
        assertFalse(packagerThread.get().isAlive());
        assertSame(packagerThread.get(), prod.closedOn.get());
        assertEquals(ConsumerQueue.CloseReason.STOPPED, q.closeReason());
        assertTrue(sinkClosed.get());
        assertEquals(2, sinkGot.size());
        assertEquals(0, pk.consumerCount());
        assertFalse(pk.isRunning());
        assertNull(pk.failure());
        pk.close(); // again: no-op
        AtomicBoolean lateSink = new AtomicBoolean();
        pk.addSink(new UnitSink() {
            @Override
            public boolean deliver(TsUnit unit) {
                return true;
            }

            @Override
            public void close() {
                lateSink.set(true);
            }
        });
        assertTrue(lateSink.get());
    }

    @Test
    void closeBeforeStartClosesProducerOnce() {
        FakeTime time = new FakeTime();
        AtomicInteger closes = new AtomicInteger();
        FakeProducer prod = new FakeProducer(time, U) {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue q = pk.subscribe(false);
        pk.close();
        pk.close();
        assertEquals(1, closes.get());
        assertTrue(q.isClosed());
        assertThrows(IllegalStateException.class, pk::start);
    }

    @Test
    void consumerLeavingRemovesItWithoutItsCloseAction() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue q = pk.subscribe(false);
        AtomicBoolean action = new AtomicBoolean();
        q.onClose(() -> action.set(true));
        assertNull(q.poll(1));
        q.leave();
        assertEquals(ConsumerQueue.CloseReason.CONSUMER, q.closeReason());
        assertEquals(0, pk.consumerCount());
        assertFalse(action.get(), "the consumer closes its own connection");
        assertNull(q.take());
        assertFalse(q.deliver(new TsUnit(0, S0, S0 + U, S0 + U, new byte[0])));
    }

    @Test
    void takeWakesWhenStopped() throws Exception {
        FakeTime time = new FakeTime();
        FakeProducer prod = new FakeProducer(time, U);
        Packager pk = packager(prod, time, new PacingClock(time));
        ConsumerQueue q = pk.subscribe(false);
        pk.start();
        AtomicReference<Object> got = new AtomicReference<>("unset");
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                got.set(q.take());
            } catch (InterruptedException e) {
                got.set(e);
            }
        });
        Thread.sleep(50);
        pk.close();
        t.join(10_000);
        assertNull(got.get());
    }

    @Test
    void pacesInRealTime() throws Exception {
        TimeSource time = TimeSource.system(Clock.systemUTC());
        PacingClock clock = new PacingClock(time);
        FakeProducer prod = new FakeProducer(time, 9000); // 100 ms units
        Packager pk = new Packager("rt", prod, PackagerTest::fakeMux, clock, time, 50, 4000);
        packagers.add(pk);
        ConsumerQueue q = pk.subscribe(16, false);
        prod.permits.release(8);
        long t0 = System.nanoTime();
        pk.start();
        for (int i = 0; i < 8; i++) {
            assertNotNull(q.poll(10_000));
        }
        long elapsedMs = (System.nanoTime() - t0) / MS;
        // unit 7 goes out at 700 − 50 ms
        assertTrue(elapsedMs >= 640, "too fast: " + elapsedMs);
        assertTrue(elapsedMs < 2000, "too slow: " + elapsedMs);
        for (int i = 0; i < 8; i++) {
            long early = clock.nanoTimeAt(S0 + i * 9000L) - prod.builtAtNanos.get(i);
            assertTrue(early <= 50 * MS + MS, "unit " + i + " " + early / MS + " ms early");
        }
    }

    @Test
    void timelineFollowerIntoTheMuxer() throws Exception {
        FakeTime time = new FakeTime();
        PacingClock clock = new PacingClock(time);
        TimelineFollower.SourceFactory factory = new TimelineFollower.SourceFactory() {
            @Override
            public ProgramSource item(ProgramCue.Entry entry, long startMs) {
                return null;
            }

            @Override
            public ProgramSource filler(Filler f) {
                try {
                    return UnitBuilderTest.filler(f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        TimelineFollower follower = new TimelineFollower(new UnitBuilder(), factory, clock, null);
        TsMuxer muxer = new TsMuxer(AVCC);
        Packager pk = new Packager("room2", follower, muxer::mux, clock, time);
        packagers.add(pk);
        // a sink that records when each unit went out (the virtual clock only moves while the
        // packager waits, so the consumer needn't keep up)
        List<TsUnit> got = Collections.synchronizedList(new ArrayList<>());
        List<Long> sentAt = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch twenty = new CountDownLatch(20);
        pk.addSink(new UnitSink() {
            @Override
            public boolean deliver(TsUnit unit) {
                got.add(unit);
                sentAt.add(time.nanoTime());
                twenty.countDown();
                return got.size() < 20;
            }

            @Override
            public void close() {
            }
        });
        pk.start();
        assertTrue(twenty.await(10, TimeUnit.SECONDS));
        pk.close();
        TsUnit prev = null;
        for (int i = 0; i < 20; i++) {
            TsUnit u = got.get(i);
            assertEquals(i, u.sequence());
            assertEquals(0, u.ts().length % TsMuxer.PACKET_SIZE);
            assertEquals(0x47, u.ts()[0]);
            if (prev != null) {
                assertTrue(u.startPts() == prev.boundaryPts() || u.startPts() == prev.audioEndPts());
            }
            // out LEAD before it's due, never earlier (the first one at once)
            long early = clock.nanoTimeAt(u.startPts()) - sentAt.get(i);
            assertTrue(early <= Packager.LEAD_MS * MS, "unit " + i + " " + early + " ns early");
            prev = u;
        }
        assertEquals(Filler.PAUSED, follower.playingFiller()); // no cue → paused
    }
}
