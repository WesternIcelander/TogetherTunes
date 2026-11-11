package io.siggi.togethertunes.output;

import io.siggi.togethertunes.media.AnnexB;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The packager's offline harness ({@code docs/program-output.md#testing-the-packager}): runs the
 * real {@link TimelineFollower}, {@link UnitBuilder} and {@link TsMuxer} against a
 * {@link ScriptedTimeline} and writes the MPEG-TS to a stream as fast as it can be built, with no
 * real-time pacing (unless asked for). The result is checked with {@code ffmpeg -v error -i out.ts -f null -} and
 * ffprobe.
 *
 * <p>Time is simulated the way the {@link Packager} sees it: output clock {@link OutputClock#START}
 * is the script's time 0 (a {@link PacingClock} on a frozen {@link TimeSource}), and before each
 * unit the harness hands the follower every timeline change up to the moment the real packager
 * would build that unit ({@link Packager#LEAD_MS} before it is due). So a seek shows up in the file
 * where it would show up live.
 */
public final class OfflineHarness {
    private OfflineHarness() {
    }

    /**
     * What a run produced.
     *
     * @param units       units written
     * @param videoFrames video access units written
     * @param audioFrames AAC frames written
     * @param bytes       bytes written
     * @param endPts      where the output ends (unwrapped output clock: the last unit's audio end)
     * @param log         one line per unit and per timeline change, in order (for reports and tests)
     */
    public record Result(long units, long videoFrames, long audioFrames, long bytes, long endPts, List<String> log) {
        public Result {
            log = List.copyOf(log);
        }

        /** Output duration in ms. */
        public long durationMs() {
            return (endPts - OutputClock.START) / 90;
        }
    }

    /**
     * Runs {@code timeline} for at least {@code durationMs} of output and writes the TS to {@code out}
     * (not closed). The sources are not closed either.
     *
     * @param sources    the item and filler sources ({@link ProgramMedia} with a direct executor
     *                   for real media)
     * @param avcC       the profile's reference avcC (what the muxer writes SPS/PPS from)
     * @param timeline   the script; it must not have been run yet
     * @param durationMs how much output to write; the run stops after the first unit that reaches it
     */
    public static Result run(TimelineFollower.SourceFactory sources, byte[] avcC, ScriptedTimeline timeline,
            long durationMs, OutputStream out) throws IOException {
        return run(sources, avcC, 0, timeline, durationMs, out);
    }

    /**
     * {@link #run(TimelineFollower.SourceFactory, byte[], ScriptedTimeline, long, OutputStream)} with
     * the video padded to a constant {@code streamBitrateBps} ({@link CbrPadder}, as the live
     * pipeline does); 0 writes it unpadded.
     */
    public static Result run(TimelineFollower.SourceFactory sources, byte[] avcC, long streamBitrateBps,
            ScriptedTimeline timeline, long durationMs, OutputStream out) throws IOException {
        return run(sources, avcC, streamBitrateBps, 0, timeline, durationMs, out);
    }

    /**
     * As above, with audio-only output ({@link TimelineFollower#audioOnlyRate()}) padded to
     * {@code audioOnlyBitrateBps} instead (0: the same rate), as the live pipeline does.
     */
    public static Result run(TimelineFollower.SourceFactory sources, byte[] avcC, long streamBitrateBps,
            long audioOnlyBitrateBps, ScriptedTimeline timeline, long durationMs, OutputStream out)
            throws IOException {
        return run(sources, avcC, streamBitrateBps, audioOnlyBitrateBps, timeline, durationMs, out, false);
    }

    /**
     * As above; with {@code realTime}, each unit is built no earlier (wall clock, from the start of
     * the run) than the live packager would build it ({@link Packager#LEAD_MS} before it is due), so
     * work running beside the output on real time (overlay re-encodes) gets the time it would get
     * live. The output and the simulated timeline are the same either way.
     */
    public static Result run(TimelineFollower.SourceFactory sources, byte[] avcC, long streamBitrateBps,
            long audioOnlyBitrateBps, ScriptedTimeline timeline, long durationMs, OutputStream out,
            boolean realTime) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(avcC, "avcC");
        Objects.requireNonNull(timeline, "timeline");
        Objects.requireNonNull(out, "out");
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        long startMs = timeline.startServerMs();
        long wallStart = System.nanoTime();
        PacingClock clock = new PacingClock(frozenAt(startMs));
        List<String> log = new ArrayList<>();
        timeline.advanceTo(0).forEach(c -> log.add(describe(c)));
        long endTarget = OutputClock.START + durationMs * 90;
        long units = 0;
        long video = 0;
        long audio = 0;
        long bytes = 0;
        long endPts = OutputClock.START;
        TsMuxer muxer = new TsMuxer(avcC);
        CbrPadder padder = streamBitrateBps > 0
                ? new CbrPadder(new AnnexB(avcC).nalLengthSize(), streamBitrateBps) : null;
        try (TimelineFollower follower = new TimelineFollower(new UnitBuilder(), sources, clock, timeline.cue())) {
            while (endPts < endTarget) {
                long buildAtMs = clock.serverMsAt(follower.nextUnitStart()) - Packager.LEAD_MS - startMs;
                if (realTime) {
                    long waitNanos = wallStart + buildAtMs * 1_000_000 - System.nanoTime();
                    if (waitNanos > 0) {
                        try {
                            Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.InterruptedIOException("interrupted while pacing");
                        }
                    }
                }
                for (ScriptedTimeline.Change c : timeline.advanceTo(buildAtMs)) {
                    log.add(describe(c));
                    follower.update(c.cue());
                }
                Unit u = follower.nextUnit();
                if (padder != null) {
                    padder.bitrateBps(follower.audioOnlyRate() && audioOnlyBitrateBps > 0 ? audioOnlyBitrateBps
                            : streamBitrateBps, u.startPts());
                    u = padder.pad(u);
                }
                byte[] ts = muxer.mux(u);
                out.write(ts);
                units++;
                video += u.video().size();
                audio += u.audio().size();
                bytes += ts.length;
                endPts = Math.max(u.boundaryPts(), u.audioEndPts());
                log.add(String.format("unit %d pts %d..%d %s%s", units, u.startPts(), u.audioEndPts(),
                        u.source().describe(), u.sourceEnded() ? " (ended)" : ""));
            }
        }
        out.flush();
        return new Result(units, video, audio, bytes, endPts, log);
    }

    private static String describe(ScriptedTimeline.Change c) {
        return "timeline " + c.atMs() + " ms: " + c.what();
    }

    /** Time standing still at {@code serverMs}: the harness never waits. */
    private static TimeSource frozenAt(long serverMs) {
        return new TimeSource() {
            @Override
            public long nanoTime() {
                return 0;
            }

            @Override
            public long millis() {
                return serverMs;
            }

            @Override
            public void sleepNanos(long nanos) {
            }
        };
    }
}
