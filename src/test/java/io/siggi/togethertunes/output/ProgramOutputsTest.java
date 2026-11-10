package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.rooms.PlaybackState;
import io.siggi.togethertunes.rooms.QueueEntry;
import io.siggi.togethertunes.rooms.Timeline;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ProgramOutputs}: start on the first request or when enabled, stop 60 s after the last
 * consumer unless an RTMP push keeps it running, restarts with a fresh clock, cue resolution.
 */
class ProgramOutputsTest {
    static final long T0 = 1_800_000_000_000L;

    final AtomicLong now = new AtomicLong(T0);
    final List<FakeOutput> started = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger snapshotsTaken = new AtomicInteger();
    final AtomicBoolean failStart = new AtomicBoolean();
    volatile ProgramOutputs.Snapshot roomState = snap(1, Timeline.IDLE, null, null, null);
    ProgramOutputs outputs;

    /** An output whose packager runs a producer that never emits (or fails at once). */
    final class FakeOutput implements ProgramOutputs.Output {
        final String roomId;
        final ProgramCue initial;
        final LinkedBlockingQueue<ProgramCue> updates = new LinkedBlockingQueue<>();
        final Packager packager;
        volatile boolean closed;

        FakeOutput(String roomId, ProgramCue initial, boolean failing) {
            this.roomId = roomId;
            this.initial = initial;
            TimeSource time = TimeSource.system(java.time.Clock.systemUTC());
            PacingClock clock = new PacingClock(time);
            UnitProducer producer = new UnitProducer() {
                @Override
                public long nextUnitStart() {
                    // failing: due at once; otherwise an hour from now
                    return failing ? OutputClock.START : OutputClock.START + 90_000L * 3600;
                }

                @Override
                public Unit nextUnit() throws IOException {
                    throw new IOException("broken");
                }

                @Override
                public void close() {
                }
            };
            packager = new Packager(roomId, producer, u -> new byte[0], clock, time);
            packager.start();
        }

        @Override
        public Packager packager() {
            return packager;
        }

        @Override
        public void update(ProgramCue cue) {
            updates.add(cue);
        }

        @Override
        public void close() {
            closed = true;
            packager.close();
        }
    }

    final AtomicBoolean nextStartFails = new AtomicBoolean();

    ProgramOutputs outputs() {
        outputs = new ProgramOutputs((roomId, cue) -> {
            if (failStart.get()) {
                throw new IOException("no cards");
            }
            FakeOutput o = new FakeOutput(roomId, cue, nextStartFails.getAndSet(false));
            started.add(o);
            return o;
        }, itemId -> itemId.startsWith("v") ? new ProgramOutputs.ItemKind(true, itemId.endsWith("a"))
                : itemId.startsWith("a") ? new ProgramOutputs.ItemKind(false, false) : null, now::get, 0);
        outputs.snapshots(roomId -> {
            snapshotsTaken.incrementAndGet();
            return roomState;
        });
        return outputs;
    }

    @AfterEach
    void tearDown() {
        if (outputs != null) {
            outputs.close();
        }
    }

    static QueueEntry entry(String id, String itemId, long durationMs) {
        return new QueueEntry(id, itemId, "u", null, 1, QueueEntry.State.QUEUED, 1, false, null, "ready", durationMs);
    }

    static ProgramOutputs.Snapshot snap(long version, Timeline t, QueueEntry current, QueueEntry next, String played) {
        return new ProgramOutputs.Snapshot(version, t, current, next, played);
    }

    FakeOutput last() {
        return started.get(started.size() - 1);
    }

    @Test
    void startsOnFirstRequestAndSharesTheOutput() throws Exception {
        outputs();
        assertNull(outputs.running("r1"));
        ProgramOutputs.Lease a = outputs.acquire("r1");
        ProgramOutputs.Lease b = outputs.acquire("r1");
        assertEquals(1, started.size());
        assertSame(a.packager(), b.packager());
        assertSame(a.packager(), outputs.running("r1"));
        assertEquals(2, outputs.leases("r1"));
        assertEquals(1, snapshotsTaken.get()); // no change seen yet: the room was read once
        assertEquals(PlaybackState.IDLE, last().initial.state());
        // another room gets its own output
        outputs.acquire("r2");
        assertEquals(2, started.size());
        assertEquals("r2", last().roomId);
    }

    @Test
    void stopsSixtySecondsAfterTheLastConsumerLeaves() throws Exception {
        outputs();
        ProgramOutputs.Lease a = outputs.acquire("r1");
        ProgramOutputs.Lease b = outputs.acquire("r1");
        ConsumerQueue q = a.packager().subscribe(false);
        now.addAndGet(600_000); // consumers held for 10 minutes: never idle
        outputs.sweep();
        assertNotNull(outputs.running("r1"));
        a.close();
        a.close(); // idempotent
        assertEquals(1, outputs.leases("r1"));
        now.addAndGet(120_000);
        outputs.sweep();
        assertNotNull(outputs.running("r1"));
        b.close();
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS - 1);
        outputs.sweep();
        assertNotNull(outputs.running("r1"));
        now.addAndGet(1);
        outputs.sweep();
        assertNull(outputs.running("r1"));
        assertTrue(last().closed);
        assertFalse(last().packager.isRunning());
        assertTrue(q.isClosed()); // consumers are disconnected with it
    }

    @Test
    void enablingStartsItAndItStopsWithoutConsumers() throws Exception {
        outputs();
        Packager p = outputs.touch("r1"); // the owner enabled .ts or HLS
        assertNotNull(p);
        now.addAndGet(30_000);
        assertSame(p, outputs.touch("r1")); // an HLS fetch: activity, same output
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS - 1);
        outputs.sweep();
        assertSame(p, outputs.running("r1"));
        now.addAndGet(1);
        outputs.sweep();
        assertNull(outputs.running("r1"));
    }

    @Test
    void rtmpKeepsItRunning() throws Exception {
        outputs();
        outputs.keepRunning("r1", true);
        Packager p = outputs.running("r1");
        assertNotNull(p);
        now.addAndGet(3_600_000);
        outputs.sweep();
        assertSame(p, outputs.running("r1"));
        // a .ts viewer comes and goes: RTMP still holds it
        outputs.acquire("r1").close();
        now.addAndGet(3_600_000);
        outputs.sweep();
        assertSame(p, outputs.running("r1"));
        // RTMP disabled: the 60 s count starts now
        outputs.keepRunning("r1", false);
        outputs.sweep();
        assertSame(p, outputs.running("r1"));
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS);
        outputs.sweep();
        assertNull(outputs.running("r1"));
        assertEquals(1, started.size());
        // disabling RTMP on a room that never had an output does nothing
        outputs.keepRunning("r9", false);
        assertNull(outputs.running("r9"));
    }

    @Test
    void aNewStartResetsTheOutput() throws Exception {
        outputs();
        ProgramOutputs.Lease a = outputs.acquire("r1");
        a.close();
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS);
        outputs.sweep();
        assertNull(outputs.running("r1"));
        ProgramOutputs.Lease b = outputs.acquire("r1");
        assertEquals(2, started.size());
        assertNotSame(a.packager(), b.packager());
        assertTrue(b.packager().isRunning());
    }

    /**
     * The output encoding changed: every running output is stopped and started again at once (a
     * new packager, so new cards and avcC), keeping an RTMP push's hold; rooms without a running
     * output stay stopped.
     */
    @Test
    void restartRunningRestartsEveryRunningOutput() throws Exception {
        outputs();
        ProgramOutputs.Lease ts = outputs.acquire("r1");
        outputs.keepRunning("r2", true);
        outputs.touch("r3");
        outputs.stop("r3");
        FakeOutput r1 = started.get(0);
        FakeOutput r2 = started.get(1);
        assertEquals(3, started.size());

        assertEquals(2, outputs.restartRunning("test"));
        assertTrue(r1.closed);
        assertTrue(r2.closed);
        assertEquals(5, started.size());
        assertNotSame(ts.packager(), outputs.running("r1"));
        assertNotNull(outputs.running("r2"));
        assertNull(outputs.running("r3"));
        ts.close();

        // r2 is still held by its RTMP push: no idle stop; r1 stops after the idle time.
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS);
        outputs.sweep();
        assertNull(outputs.running("r1"));
        assertNotNull(outputs.running("r2"));
    }

    @Test
    void stopStopsAtOnceEvenWithRtmp() throws Exception {
        outputs();
        outputs.keepRunning("r1", true);
        ProgramOutputs.Lease a = outputs.acquire("r1");
        ConsumerQueue q = a.packager().subscribe(false);
        outputs.stop("r1");
        assertNull(outputs.running("r1"));
        assertTrue(q.isClosed());
        assertFalse(a.packager().isRunning());
        // RTMP no longer keeps it: nothing restarts it
        outputs.sweep();
        assertNull(outputs.running("r1"));
        a.close();
    }

    @Test
    void aFailedPackagerIsRestarted() throws Exception {
        outputs();
        nextStartFails.set(true);
        ProgramOutputs.Lease a = outputs.acquire("r1");
        awaitStopped(a.packager());
        assertNull(outputs.running("r1"));
        // the next request starts a new one
        ProgramOutputs.Lease b = outputs.acquire("r1");
        assertEquals(2, started.size());
        assertTrue(b.packager().isRunning());
        assertTrue(started.get(0).closed);

        // with RTMP on, the sweep restarts it
        outputs.stop("r1");
        nextStartFails.set(true);
        outputs.keepRunning("r1", true);
        Packager failing = last().packager;
        awaitStopped(failing);
        outputs.sweep();
        assertEquals(4, started.size());
        assertTrue(outputs.running("r1").isRunning());
    }

    @Test
    void startFailuresReachTheCaller() throws Exception {
        outputs();
        failStart.set(true);
        assertThrows(IOException.class, () -> outputs.acquire("r1"));
        assertThrows(IOException.class, () -> outputs.touch("r1"));
        assertEquals(0, outputs.leases("r1"));
        failStart.set(false);
        outputs.acquire("r1");
        assertEquals(1, outputs.leases("r1"));
    }

    @Test
    void closeStopsEverything() throws Exception {
        outputs();
        Packager p1 = outputs.acquire("r1").packager();
        Packager p2 = outputs.touch("r2");
        outputs.close();
        assertFalse(p1.isRunning());
        assertFalse(p2.isRunning());
        assertThrows(IllegalStateException.class, () -> outputs.acquire("r1"));
    }

    @Test
    void resolvesSnapshotsIntoCues() {
        outputs();
        QueueEntry video = entry("e1", "v1", 60_000);
        QueueEntry asAudio = entry("e2", "v2a", 30_000);
        QueueEntry audio = entry("e3", "a1", 20_000);
        QueueEntry missing = entry("e4", "x1", 20_000);

        ProgramCue c = outputs.resolve(snap(5, Timeline.playing("e1", T0, 1000), video, asAudio, "e0"));
        assertEquals(PlaybackState.PLAYING, c.state());
        assertEquals(new ProgramCue.Entry("e1", "v1", true, false, 60_000), c.current());
        assertEquals(new ProgramCue.Entry("e2", "v2a", true, true, 30_000), c.next());
        assertEquals("e0", c.playedOut());
        assertEquals(T0, c.anchorServerMs());
        assertEquals(1000, c.anchorPositionMs());

        c = outputs.resolve(snap(6, Timeline.paused("e3", T0, 500), audio, null, null));
        assertEquals(PlaybackState.PAUSED, c.state());
        assertEquals(new ProgramCue.Entry("e3", "a1", false, false, 20_000), c.current());
        assertNull(c.next());

        assertEquals(ProgramCue.IDLE, outputs.resolve(snap(7, Timeline.IDLE, null, null, null)));

        // a next item that can't be looked up is left out
        c = outputs.resolve(snap(8, Timeline.playing("e3", T0, 0), audio, missing, null));
        assertEquals("e3", c.current().entryId());
        assertNull(c.next());
        // a current one makes the state unknown (→ Paused filler)
        c = outputs.resolve(snap(9, Timeline.playing("e4", T0, 0), missing, audio, null));
        assertNull(c.state());
        assertNull(c.current());
        // so does a current entry that isn't the timeline's
        c = outputs.resolve(snap(10, Timeline.playing("e1", T0, 0), audio, null, null));
        assertNull(c.state());
    }

    /**
     * Audio-only rooms: each entry carries its own mode (the current one the mode it started
     * with, the next one the room's mode now), with the audio-only file's length and whether the
     * video has one; the cue carries the room's setting for the fillers.
     */
    @Test
    void resolvesAudioOnlyEntries() {
        outputs();
        QueueEntry video = entry("e1", "v1", 60_000); // started before the switch: stays video
        QueueEntry trimmed = new QueueEntry("e2", "v2", "u", null, 1, QueueEntry.State.QUEUED, 1, false, null,
                "ready", 60_000L, true, 20_000L, true);
        QueueEntry plain = new QueueEntry("e3", "v3", "u", null, 1, QueueEntry.State.QUEUED, 1, false, null,
                "ready", 45_000L, true, null, true);
        ProgramCue c = outputs.resolve(new ProgramOutputs.Snapshot(5, Timeline.playing("e1", T0, 0), video, trimmed,
                null, true));
        assertTrue(c.audioOnly());
        assertEquals(new ProgramCue.Entry("e1", "v1", true, false, 60_000), c.current());
        assertEquals(new ProgramCue.Entry("e2", "v2", true, false, 20_000, true, true), c.next());
        c = outputs.resolve(new ProgramOutputs.Snapshot(6, Timeline.playing("e2", T0, 0), trimmed, plain, null,
                true));
        assertEquals(new ProgramCue.Entry("e3", "v3", true, false, 45_000, true, false), c.next());
        assertTrue(c.next().playsAsAudio());
        assertTrue(outputs.resolve(new ProgramOutputs.Snapshot(7, Timeline.IDLE, null, null, null, true)).audioOnly());
        assertFalse(outputs.resolve(snap(8, Timeline.IDLE, null, null, null)).audioOnly());
    }

    @Test
    void startsFromTheRoomsStateAndFollowsIt() throws Exception {
        outputs();
        QueueEntry video = entry("e1", "v1", 60_000);
        QueueEntry audio = entry("e3", "a1", 20_000);
        roomState = snap(3, Timeline.playing("e1", T0, 0), video, audio, null);
        ProgramOutputs.Lease a = outputs.acquire("r1");
        FakeOutput out = last();
        assertEquals("e1", out.initial.current().entryId());
        assertEquals("e3", out.initial.next().entryId());
        assertTrue(out.updates.isEmpty());
        a.close();
        assertEquals(1, snapshotsTaken.get());
        now.addAndGet(ProgramOutputs.IDLE_STOP_MS);
        outputs.sweep();
        // the room's state is kept for the next start (changes keep it current), no second read
        outputs.acquire("r1");
        assertEquals(1, snapshotsTaken.get());
        assertEquals(2, started.size());
        assertEquals("e1", last().initial.current().entryId());
    }

    private static void awaitStopped(Packager p) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            while (p.isRunning()) {
                Thread.onSpinWait();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
