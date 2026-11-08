package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class UuidV7Test {
    private static final long T = 1_762_620_873_123L; // 2025-11-08T16:54:33.123Z

    /** A clock tests can move freely (also backwards). */
    static final class MutableClock extends Clock {
        long ms;

        MutableClock(long ms) {
            this.ms = ms;
        }

        @Override
        public long millis() {
            return ms;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void formatVersionVariantAndTimestamp() {
        UuidV7 gen = new UuidV7(Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC), new Random(1));
        UUID u = gen.nextUuid();
        assertEquals(7, u.version());
        assertEquals(2, u.variant());
        assertEquals(T, UuidV7.timestampMs(u));
        String s = u.toString();
        assertEquals(36, s.length());
        assertEquals(s.toLowerCase(), s);
        assertTrue(UuidV7.isValid(s));
        assertEquals(T, UuidV7.timestampMs(s));
        // The first 12 hex digits are the timestamp.
        assertEquals(String.format("%012x", T), s.substring(0, 8) + s.substring(9, 13));
    }

    @Test
    void knownLayout() {
        // RFC 9562 appendix A.6 example: 017F22E2-79B0-7CC3-98C4-DC0C0C07398F.
        UUID u = UuidV7.build(0x017F22E279B0L, 0xCC3, 0x18C4DC0C0C07398FL);
        assertEquals("017f22e2-79b0-7cc3-98c4-dc0c0c07398f", u.toString());
    }

    @Test
    void newIdIsCloseToNow() {
        long before = System.currentTimeMillis();
        String id = UuidV7.newId();
        long after = System.currentTimeMillis();
        assertTrue(UuidV7.isValid(id), id);
        long ts = UuidV7.timestampMs(id);
        assertTrue(ts >= before && ts <= after + 1, ts + " not in [" + before + ", " + after + "]");
        assertNotEquals(id, UuidV7.newId());
    }

    @Test
    void strictlyIncreasingWithinOneMillisecond() {
        UuidV7 gen = new UuidV7(Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC), new Random(2));
        String prev = gen.next();
        for (int i = 0; i < 100_000; i++) {
            String next = gen.next();
            assertTrue(next.compareTo(prev) > 0, prev + " !< " + next);
            assertEquals(T, UuidV7.timestampMs(next));
            prev = next;
        }
    }

    @Test
    void sameMillisecondStepsAreRandom() {
        UuidV7 gen = new UuidV7(Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC), new Random(3));
        Set<Long> steps = new HashSet<>();
        UUID prev = gen.nextUuid();
        for (int i = 0; i < 100; i++) {
            UUID next = gen.nextUuid();
            long step = next.getLeastSignificantBits() - prev.getLeastSignificantBits();
            assertTrue(step >= 1 && step <= 1L << 32, "step " + step);
            steps.add(step);
            prev = next;
        }
        assertTrue(steps.size() > 90, "increments are not random: " + steps.size());
    }

    @Test
    void clockGoingBackwardsKeepsOrder() {
        MutableClock clock = new MutableClock(T);
        UuidV7 gen = new UuidV7(clock, new Random(4));
        String a = gen.next();
        clock.ms = T - 5_000;
        String b = gen.next();
        assertTrue(b.compareTo(a) > 0);
        assertEquals(T, UuidV7.timestampMs(b));
        clock.ms = T + 1;
        String c = gen.next();
        assertTrue(c.compareTo(b) > 0);
        assertEquals(T + 1, UuidV7.timestampMs(c));
    }

    @Test
    void newMillisecondGetsFreshRandomBits() {
        MutableClock clock = new MutableClock(T);
        // A random that returns all ones, so the 74 bits are at their maximum.
        RandomGenerator ones = new RandomGenerator() {
            @Override
            public long nextLong() {
                return -1L;
            }

            @Override
            public long nextLong(long origin, long bound) {
                return origin;
            }
        };
        UuidV7 gen = new UuidV7(clock, ones);
        UUID a = gen.nextUuid();
        assertEquals(0xfff, a.getMostSignificantBits() & 0xfff);
        assertEquals(0xbfff_ffff_ffff_ffffL, a.getLeastSignificantBits());
        // The counter overflows: the next ID borrows the next millisecond.
        UUID b = gen.nextUuid();
        assertEquals(T + 1, UuidV7.timestampMs(b));
        assertTrue(b.toString().compareTo(a.toString()) > 0);
        // The real clock catching up doesn't go back or repeat (with this random every
        // increment overflows again, so the timestamp keeps moving ahead by one).
        clock.ms = T + 1;
        UUID c = gen.nextUuid();
        assertEquals(T + 2, UuidV7.timestampMs(c));
        assertTrue(c.toString().compareTo(b.toString()) > 0);
    }

    @Test
    void carryFromRandBIntoRandA() {
        MutableClock clock = new MutableClock(T);
        RandomGenerator r = new RandomGenerator() {
            @Override
            public long nextLong() {
                return -1L; // rand_b all ones
            }

            @Override
            public int nextInt() {
                return 5; // rand_a = 5
            }

            @Override
            public long nextLong(long origin, long bound) {
                return origin;
            }
        };
        UuidV7 gen = new UuidV7(clock, r);
        UUID a = gen.nextUuid();
        UUID b = gen.nextUuid();
        assertEquals(T, UuidV7.timestampMs(b));
        assertEquals(5, a.getMostSignificantBits() & 0xfff);
        assertEquals(6, b.getMostSignificantBits() & 0xfff);
        assertEquals(0x8000_0000_0000_0000L, b.getLeastSignificantBits());
        assertTrue(b.toString().compareTo(a.toString()) > 0);
    }

    @Test
    void uniqueAcrossThreads() throws Exception {
        UuidV7 gen = new UuidV7(Clock.systemUTC(), new Random(5));
        ConcurrentLinkedQueue<String> all = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < 16; t++) {
                futures.add(ex.submit(() -> {
                    List<String> mine = new ArrayList<>();
                    for (int i = 0; i < 10_000; i++) {
                        mine.add(gen.next());
                    }
                    for (int i = 1; i < mine.size(); i++) {
                        assertTrue(mine.get(i).compareTo(mine.get(i - 1)) > 0);
                    }
                    all.addAll(mine);
                }));
            }
        }
        for (Future<?> f : futures) {
            f.get();
        }
        assertEquals(160_000, all.size());
        assertEquals(160_000, new HashSet<>(all).size());
    }

    @Test
    void validation() {
        assertTrue(UuidV7.isValid("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"));
        assertTrue(UuidV7.isValid("017f22e2-79b0-7cc3-b8c4-dc0c0c07398f"));
        assertFalse(UuidV7.isValid(null));
        assertFalse(UuidV7.isValid(""));
        assertFalse(UuidV7.isValid("017F22E2-79B0-7CC3-98C4-DC0C0C07398F")); // not canonical
        assertFalse(UuidV7.isValid("017f22e2-79b0-4cc3-98c4-dc0c0c07398f")); // v4
        assertFalse(UuidV7.isValid("017f22e2-79b0-7cc3-c8c4-dc0c0c07398f")); // wrong variant
        assertFalse(UuidV7.isValid("017f22e279b07cc398c4dc0c0c07398f"));
        assertFalse(UuidV7.isValid("017f22e2-79b0-7cc3-98c4-dc0c0c07398g"));
        assertFalse(UuidV7.isValid("017f22e2_79b0-7cc3-98c4-dc0c0c07398f"));
        assertFalse(UuidV7.isValid("017f22e2-79b0-7cc3-98c4-dc0c0c07398f "));
        assertThrows(IllegalArgumentException.class, () -> UuidV7.timestampMs("nope"));
        assertThrows(IllegalArgumentException.class, () -> UuidV7.timestampMs(UUID.randomUUID()));
    }

    @Test
    void clockOutOfRange() {
        UuidV7 gen = new UuidV7(new MutableClock(-1), new Random(6));
        assertThrows(IllegalStateException.class, gen::next);
        UuidV7 far = new UuidV7(new MutableClock(UuidV7.MAX_TIMESTAMP + 1), new Random(6));
        assertThrows(IllegalStateException.class, far::next);
    }

    @Test
    void textColumnSortsInCreationOrder() throws Exception {
        MutableClock clock = new MutableClock(T);
        UuidV7 gen = new UuidV7(clock, new Random(7));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            if (i % 7 == 0) {
                clock.ms += 1 + (i % 3) * 1000L;
            }
            ids.add(gen.next());
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id TEXT PRIMARY KEY, n INTEGER NOT NULL)");
            List<Integer> shuffled = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                shuffled.add(i);
            }
            Collections.shuffle(shuffled, new Random(8));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (id, n) VALUES (?, ?)")) {
                for (int i : shuffled) {
                    ps.setString(1, ids.get(i));
                    ps.setInt(2, i);
                    ps.executeUpdate();
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT n FROM t ORDER BY id")) {
                int expected = 0;
                while (rs.next()) {
                    assertEquals(expected++, rs.getInt(1));
                }
                assertEquals(ids.size(), expected);
            }
        }
    }
}
