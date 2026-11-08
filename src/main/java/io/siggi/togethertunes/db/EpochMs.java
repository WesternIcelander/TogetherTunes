package io.siggi.togethertunes.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Times as epoch milliseconds (UTC), the only time representation in the database
 * ({@code BIGINT} columns) and in the API.
 *
 * <p>Code that needs a controllable time takes a {@link Clock} and calls {@link #now(Clock)};
 * tests pass a fixed or offset clock. JDBC helpers map nullable columns to {@code Long}.
 */
public final class EpochMs {
    private EpochMs() {
    }

    /** The current time from the system clock. */
    public static long now() {
        return System.currentTimeMillis();
    }

    /** The current time from {@code clock}. */
    public static long now(Clock clock) {
        return clock.millis();
    }

    public static long of(Instant instant) {
        return instant.toEpochMilli();
    }

    public static Instant toInstant(long epochMs) {
        return Instant.ofEpochMilli(epochMs);
    }

    /** {@code epochMs + duration}, saturating instead of overflowing. */
    public static long plus(long epochMs, Duration duration) {
        long add;
        try {
            add = duration.toMillis();
        } catch (ArithmeticException e) {
            add = duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        long r = epochMs + add;
        // Overflow iff both operands have the same sign and the result's sign differs.
        if (((epochMs ^ r) & (add ^ r)) < 0) {
            return add < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return r;
    }

    /** ISO-8601 UTC form, e.g. {@code 2026-11-08T16:54:33.123Z}, for logs and messages. */
    public static String format(long epochMs) {
        return Instant.ofEpochMilli(epochMs).toString();
    }

    /** Reads a nullable {@code BIGINT} time column; SQL {@code NULL} gives {@code null}. */
    public static Long get(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** Reads a nullable {@code BIGINT} time column by index; SQL {@code NULL} gives {@code null}. */
    public static Long get(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** Binds a nullable time; {@code null} binds SQL {@code NULL} as {@code BIGINT}. */
    public static void set(PreparedStatement ps, int index, Long epochMs) throws SQLException {
        if (epochMs == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, epochMs);
        }
    }
}
