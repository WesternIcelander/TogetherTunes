package io.siggi.togethertunes.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.http.HttpError;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DiskSpace} with a fake free-space source: the threshold, the size-aware check, 0, the cache. */
class DiskSpaceTest {
    private static final long GIB = 1024L * 1024 * 1024;

    private final AtomicLong free = new AtomicLong(10 * GIB);
    private final AtomicLong min = new AtomicLong(5 * GIB);
    private final AtomicInteger measured = new AtomicInteger();

    private DiskSpace space(Duration cache, AtomicLong clock) {
        return new DiskSpace(() -> {
            measured.incrementAndGet();
            return new DiskSpace.Usage(free.get(), 100 * GIB);
        }, min::get, cache, clock::get);
    }

    private DiskSpace space() {
        return space(Duration.ZERO, new AtomicLong());
    }

    @Test
    void lowBelowTheMinimumOnly() {
        DiskSpace s = space();
        assertFalse(s.low());
        DiskSpace.Status status = s.status();
        assertEquals(10 * GIB, status.freeBytes());
        assertEquals(100 * GIB, status.totalBytes());
        assertEquals(5 * GIB, status.minFreeBytes());
        free.set(5 * GIB);
        assertFalse(s.low(), "exactly the minimum is still enough");
        free.set(5 * GIB - 1);
        assertTrue(s.low());
        free.set(6 * GIB);
        assertFalse(s.low(), "back above: not low again");
    }

    @Test
    void theCheckCountsTheDeclaredSize() {
        DiskSpace s = space();
        s.requireRoomFor(5 * GIB); // 10 - 5 = 5, exactly the minimum
        HttpError e = assertThrows(HttpError.class, () -> s.requireRoomFor(5 * GIB + 1));
        assertEquals(507, e.status());
        assertEquals("insufficient_storage", e.code());
        assertEquals("The server is low on disk space; adding media is paused.", e.getMessage());
        free.set(4 * GIB);
        assertThrows(HttpError.class, () -> s.requireRoomFor(0));
        assertThrows(HttpError.class, () -> s.requireRoomFor(1));
    }

    @Test
    void zeroDisablesTheCheck() {
        DiskSpace s = space();
        min.set(0);
        free.set(0);
        assertFalse(s.low());
        s.requireRoomFor(0);
        s.requireRoomFor(Long.MAX_VALUE);
        assertEquals(0, s.status().minFreeBytes());
    }

    @Test
    void theMinimumIsReadOnEveryDecision() {
        DiskSpace s = space();
        assertFalse(s.low());
        min.set(20 * GIB); // the admin raises it
        assertTrue(s.low());
    }

    @Test
    void aMinimumThatCantBeReadKeepsTheLastValue() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        DiskSpace s = new DiskSpace(() -> new DiskSpace.Usage(free.get(), 100 * GIB), () -> {
            if (reads.incrementAndGet() > 1) {
                throw new SQLException("database is locked");
            }
            return 5 * GIB;
        }, Duration.ZERO, clock::get);
        assertEquals(5 * GIB, s.status().minFreeBytes());
        free.set(GIB);
        assertTrue(s.low());
    }

    @Test
    void unmeasurableSpaceBlocksNothing() {
        DiskSpace s = new DiskSpace(() -> {
            throw new IOException("no such volume");
        }, min::get, Duration.ZERO, System::nanoTime);
        assertFalse(s.low());
        s.requireRoomFor(100 * GIB);
        assertEquals(-1, s.status().freeBytes());
    }

    @Test
    void measurementsAreCachedBriefly() {
        AtomicLong clock = new AtomicLong();
        DiskSpace s = space(Duration.ofSeconds(2), clock);
        s.status();
        s.status();
        assertEquals(1, measured.get());
        free.set(GIB);
        assertFalse(s.low(), "still the cached measurement");
        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        assertTrue(s.low(), "measured again after the cache time");
        assertEquals(2, measured.get());
    }

    @Test
    void measuresTheRealVolume(@TempDir Path dir) {
        DiskSpace s = DiskSpace.of(dir, () -> 0);
        DiskSpace.Status status = s.status();
        assertTrue(status.freeBytes() > 0);
        assertTrue(status.totalBytes() >= status.freeBytes());
        assertFalse(status.low());
    }

    @Test
    void unlimitedIsNeverLow() {
        assertFalse(DiskSpace.unlimited().low());
        DiskSpace.unlimited().requireRoomFor(Long.MAX_VALUE);
    }
}
