package io.siggi.togethertunes.output;

/** Conversions between track timescales, milliseconds and the 90 kHz output clock. */
final class MediaTime {
    static final long CLOCK = 90_000;

    private MediaTime() {
    }

    /** {@code value} ticks of {@code from} in ticks of {@code to}, rounded to nearest (half up). */
    static long rescale(long value, long from, long to) {
        long num = Math.multiplyExact(value, to);
        return Math.floorDiv(Math.addExact(Math.multiplyExact(num, 2), from), Math.multiplyExact(from, 2));
    }

    /** {@code ms} in ticks of {@code timescale}, rounded down. */
    static long msToTicks(long ms, long timescale) {
        return Math.floorDiv(Math.multiplyExact(ms, timescale), 1000);
    }

    /** {@code ticks} of {@code timescale} in ms, rounded down. */
    static long ticksToMs(long ticks, long timescale) {
        return Math.floorDiv(Math.multiplyExact(ticks, 1000), timescale);
    }
}
