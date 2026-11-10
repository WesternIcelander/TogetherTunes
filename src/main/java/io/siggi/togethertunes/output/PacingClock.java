package io.siggi.togethertunes.output;

/**
 * The packager's mapping between the output clock and real time
 * ({@code docs/program-output.md#pacing}): fixed when the packager starts, {@link OutputClock#START}
 * ↔ now. {@link #serverMsAt} is the {@link TimelineFollower.ServerClock} the follower decides by;
 * {@link #nanoTimeAt} is what the packager thread paces by (monotonic, so a server clock step
 * doesn't burst or stall the output).
 *
 * <p>If the packager thread falls far behind (a stall), the mapping is moved forward by the lag
 * ({@link #rebase}) instead of bursting the missed output out to catch up. Output timestamps never
 * change (invariant 3); only their meaning in real time does.
 */
public final class PacingClock implements TimelineFollower.ServerClock {
    private final long startPts;
    private final long startMs;
    private final long startNanos;
    /** Total shift from {@link #rebase}, in ns. */
    private volatile long shiftNanos;

    /** Starts the mapping now: {@link OutputClock#START} ↔ the time source's current time. */
    public PacingClock(TimeSource time) {
        this(time, OutputClock.START);
    }

    PacingClock(TimeSource time, long startPts) {
        this.startPts = startPts;
        this.startNanos = time.nanoTime();
        this.startMs = time.millis();
    }

    /** Server time (epoch ms) at which {@code outputPts} is due (unwrapped output clock). */
    @Override
    public long serverMsAt(long outputPts) {
        return startMs + Math.floorDiv(ticksToNanos(outputPts - startPts) + shiftNanos, 1_000_000L);
    }

    /** {@link TimeSource#nanoTime()} at which {@code outputPts} is due. */
    public long nanoTimeAt(long outputPts) {
        return startNanos + ticksToNanos(outputPts - startPts) + shiftNanos;
    }

    /** Moves the mapping later by {@code nanos} (the output is due later than first planned). */
    public void rebase(long nanos) {
        shiftNanos += nanos;
    }

    /** How far the mapping has been moved by {@link #rebase}, in ns. */
    public long shiftNanos() {
        return shiftNanos;
    }

    private static long ticksToNanos(long ticks) {
        // 1 tick = 100000/9 ns; exact for whole ms and audio frames
        return Math.floorDiv(ticks * 100_000L, 9L);
    }
}
