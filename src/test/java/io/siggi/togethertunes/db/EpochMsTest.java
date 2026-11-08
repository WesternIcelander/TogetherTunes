package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EpochMsTest {
    private static final long T = 1_762_620_873_123L;

    @Test
    void nowAndClock() {
        long before = System.currentTimeMillis();
        long now = EpochMs.now();
        assertTrue(now >= before && now <= System.currentTimeMillis());
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC);
        assertEquals(T, EpochMs.now(fixed));
        assertEquals(T + 60_000, EpochMs.now(Clock.offset(fixed, Duration.ofMinutes(1))));
    }

    @Test
    void conversions() {
        Instant i = Instant.parse("2025-11-08T16:54:33.123Z");
        assertEquals(T, EpochMs.of(i));
        assertEquals(i, EpochMs.toInstant(T));
        assertEquals("2025-11-08T16:54:33.123Z", EpochMs.format(T));
        assertEquals("1970-01-01T00:00:00Z", EpochMs.format(0));
        // Sub-millisecond precision is truncated.
        assertEquals(T, EpochMs.of(i.plusNanos(999_999)));
    }

    @Test
    void plusSaturates() {
        assertEquals(T + 1500, EpochMs.plus(T, Duration.ofMillis(1500)));
        assertEquals(T - 1500, EpochMs.plus(T, Duration.ofMillis(-1500)));
        assertEquals(Long.MAX_VALUE, EpochMs.plus(T, Duration.ofMillis(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, EpochMs.plus(T, Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, EpochMs.plus(-T, Duration.ofMillis(Long.MIN_VALUE)));
        assertEquals(Long.MIN_VALUE, EpochMs.plus(-T, Duration.ofSeconds(Long.MIN_VALUE)));
    }

    @Test
    void jdbcNullableRoundTrip() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (n INTEGER, at BIGINT)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t (n, at) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                EpochMs.set(ps, 2, T);
                ps.executeUpdate();
                ps.setInt(1, 2);
                EpochMs.set(ps, 2, null);
                ps.executeUpdate();
                ps.setInt(1, 3);
                EpochMs.set(ps, 2, 0L);
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT at FROM t ORDER BY n")) {
                assertTrue(rs.next());
                assertEquals(T, EpochMs.get(rs, "at"));
                assertTrue(rs.next());
                assertNull(EpochMs.get(rs, "at"));
                assertTrue(rs.next());
                assertEquals(0L, EpochMs.get(rs, 1));
            }
        }
    }
}
