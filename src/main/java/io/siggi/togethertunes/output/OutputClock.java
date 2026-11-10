package io.siggi.togethertunes.output;

/**
 * The program output's 90 kHz clock ({@code docs/program-output.md#timestamp-rules}).
 *
 * <p>Internally the packager keeps timestamps as plain, never-wrapping {@code long} ticks (they
 * would take millions of years to overflow), so comparisons and arithmetic stay simple. Only what
 * goes on the wire is masked to 33 bits with {@link #wrap}: PTS, DTS and PCR. Wrap after any
 * arithmetic (e.g. {@code wrap(dts − 63000)} for the PCR), never before.
 */
public final class OutputClock {
    /** Ticks per second. */
    public static final long HZ = 90_000;
    /** Where the output clock starts: 10 s, so muxer offsets (PCR = DTS − 700 ms) never go negative. */
    public static final long START = 900_000;
    /** PTS, DTS and PCR base are 33-bit fields: they wrap at 2³³ ticks (about 26.5 h). */
    public static final long WRAP = 1L << 33;
    /** Mask for a 33-bit timestamp. */
    public static final long MASK = WRAP - 1;

    private OutputClock() {
    }

    /** {@code ticks} as a 33-bit timestamp field (also correct for a negative value). */
    public static long wrap(long ticks) {
        return ticks & MASK;
    }
}
