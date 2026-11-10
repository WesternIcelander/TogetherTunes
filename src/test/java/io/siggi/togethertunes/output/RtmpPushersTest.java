package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** {@link RtmpPushers}: a push pins the room's output and holds a lease per attempt; stop and close release them. */
class RtmpPushersTest {
    final ProgramOutputsTest fixture = new ProgramOutputsTest();
    final List<String> events = new CopyOnWriteArrayList<>();
    final AtomicInteger launches = new AtomicInteger();
    RtmpPushers pushers;

    @AfterEach
    void tearDown() {
        if (pushers != null) {
            pushers.close();
        }
        fixture.tearDown();
    }

    RtmpPushers pushers() {
        ProgramOutputs outputs = fixture.outputs();
        pushers = new RtmpPushers(outputs, "ffmpeg", command -> {
            launches.incrementAndGet();
            assertTrue(command.get(command.size() - 1).startsWith("rtmp://ingest.example/live/key"), command.toString());
            return new ProcessBuilder("sh", "-c", "echo progress=continue; exec cat > /dev/null").start();
        });
        pushers.statusListener((roomId, s) -> events.add(roomId + ":" + s.state()));
        return pushers;
    }

    @Test
    @Timeout(60)
    void startPinsTheOutputAndStopReleasesIt() throws Exception {
        RtmpPushers p = pushers();
        ProgramOutputs outputs = fixture.outputs;
        assertNull(p.status("r1"));
        p.start("r1", "rtmp://ingest.example/live", "key1");
        RtmpPusherTest.await(() -> p.status("r1").state() == RtmpPusher.State.CONNECTED, "connected");
        assertTrue(events.contains("r1:CONNECTED"), events.toString());
        assertNotNull(outputs.running("r1"));
        assertEquals(1, outputs.leases("r1"));
        assertEquals(1, fixture.started.size());

        // Replacing the push (new key): the old one stops, a new one connects on the same output.
        p.start("r1", "rtmp://ingest.example/live", "key2");
        RtmpPusherTest.await(() -> p.status("r1").state() == RtmpPusher.State.CONNECTED && launches.get() == 2,
                "reconnected");
        assertEquals(1, outputs.leases("r1"));
        assertEquals(1, fixture.started.size());

        p.stop("r1");
        assertNull(p.status("r1"));
        assertEquals(0, outputs.leases("r1"));
        assertTrue(events.contains("r1:STOPPED"));
        // Unpinned: the output stops after the usual idle time.
        fixture.now.addAndGet(ProgramOutputs.IDLE_STOP_MS);
        outputs.sweep();
        assertNull(outputs.running("r1"));
    }

    @Test
    @Timeout(60)
    void closeStopsEveryPush() throws Exception {
        RtmpPushers p = pushers();
        p.start("r1", "rtmp://ingest.example/live", "key1");
        p.start("r2", "rtmp://ingest.example/live/", "key2");
        RtmpPusherTest.await(() -> p.status("r1").state() == RtmpPusher.State.CONNECTED
                && p.status("r2").state() == RtmpPusher.State.CONNECTED, "both connected");
        p.close();
        assertNull(p.status("r1"));
        assertNull(p.status("r2"));
        assertEquals(0, fixture.outputs.leases("r1"));
        assertEquals(0, fixture.outputs.leases("r2"));
        assertThrows(IllegalStateException.class, () -> p.start("r3", "rtmp://ingest.example/live", "key3"));
    }
}
