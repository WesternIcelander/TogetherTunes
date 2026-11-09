package io.siggi.togethertunes.ingest;

/**
 * One block of ffmpeg's {@code -progress} output: ffmpeg writes {@code key=value} lines and ends
 * each block with {@code progress=continue} (or {@code progress=end} for the last one). Built by
 * {@link FfmpegProgressParser}.
 *
 * @param frame       frames written so far, or -1 if not reported (audio-only outputs)
 * @param fps         current encoding speed in frames per second, or -1 if not reported
 * @param outTimeUs   output position in microseconds, or -1 if not reported / not yet known
 *                    ({@code N/A} or negative at the very start)
 * @param totalSize   bytes written so far, or -1 if not reported
 * @param speed       speed relative to real time (e.g. 4.5 for {@code 4.5x}), or -1 if not reported
 * @param end         whether this is the final block ({@code progress=end})
 */
public record FfmpegProgress(long frame, double fps, long outTimeUs, long totalSize, double speed, boolean end) {

    /**
     * Progress as a fraction of {@code durationUs} (the expected output duration), in [0, 1].
     * 1 once {@link #end()} is reached; 0 when the position or the duration is unknown.
     */
    public double fraction(long durationUs) {
        if (end) {
            return 1.0;
        }
        if (outTimeUs <= 0 || durationUs <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) outTimeUs / durationUs);
    }
}
