package io.siggi.togethertunes.ingest;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Parses ffmpeg's {@code -progress pipe:1} output line by line and reports one
 * {@link FfmpegProgress} per block. Example block (ffmpeg 6):
 *
 * <pre>
 * frame=120
 * fps=58.43
 * stream_0_0_q=23.0
 * bitrate=1234.5kbits/s
 * total_size=621948
 * out_time_us=4000000
 * out_time_ms=4000000
 * out_time=00:00:04.000000
 * dup_frames=0
 * drop_frames=0
 * speed=1.95x
 * progress=continue
 * </pre>
 *
 * <p>Position: {@code out_time_us}, else {@code out_time_ms} (which is also microseconds, an old
 * ffmpeg quirk), else {@code out_time} ({@code [-]HH:MM:SS.micro}). {@code N/A} and unparsable
 * values count as not reported; unknown keys are ignored, so newer ffmpeg versions keep working.
 * Not thread-safe; feed it from one reader.
 */
public final class FfmpegProgressParser {
    private final Consumer<FfmpegProgress> listener;

    private long frame;
    private double fps;
    private long outTimeUs;
    private long outTimeMs;
    private long outTime;
    private long totalSize;
    private double speed;

    public FfmpegProgressParser(Consumer<FfmpegProgress> listener) {
        this.listener = Objects.requireNonNull(listener);
        reset();
    }

    private void reset() {
        frame = -1;
        fps = -1;
        outTimeUs = -1;
        outTimeMs = -1;
        outTime = -1;
        totalSize = -1;
        speed = -1;
    }

    /** Feeds one line (without the line break). Lines without {@code =} are ignored. */
    public void line(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        switch (key) {
            case "frame" -> frame = parseLong(value);
            case "fps" -> fps = parseDouble(value);
            case "out_time_us" -> outTimeUs = parseLong(value);
            case "out_time_ms" -> outTimeMs = parseLong(value);
            case "out_time" -> outTime = parseClock(value);
            case "total_size" -> totalSize = parseLong(value);
            case "speed" -> speed = parseDouble(value.endsWith("x") ? value.substring(0, value.length() - 1) : value);
            case "progress" -> {
                long pos = outTimeUs >= 0 ? outTimeUs : outTimeMs >= 0 ? outTimeMs : outTime;
                FfmpegProgress p = new FfmpegProgress(frame, fps, pos, totalSize, speed, "end".equals(value));
                reset();
                listener.accept(p);
            }
            default -> {
                // bitrate, dup_frames, stream_*_q, ...: not needed
            }
        }
    }

    /** A non-negative integer, or -1. */
    static long parseLong(String s) {
        try {
            long v = Long.parseLong(s);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A finite non-negative number, or -1. */
    static double parseDouble(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) && v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code HH:MM:SS[.fraction]} in microseconds; -1 if negative, {@code N/A} or malformed. */
    static long parseClock(String s) {
        if (s.startsWith("-")) {
            return -1;
        }
        String[] parts = s.split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            long h = Long.parseLong(parts[0]);
            long m = Long.parseLong(parts[1]);
            String sec = parts[2];
            int dot = sec.indexOf('.');
            long whole = Long.parseLong(dot < 0 ? sec : sec.substring(0, dot));
            long micros = 0;
            if (dot >= 0) {
                String frac = sec.substring(dot + 1);
                if (frac.isEmpty() || !frac.chars().allMatch(Character::isDigit)) {
                    return -1;
                }
                frac = (frac + "000000").substring(0, 6);
                micros = Long.parseLong(frac);
            }
            if (h < 0 || m < 0 || m > 59 || whole < 0 || whole > 59) {
                return -1;
            }
            return ((h * 60 + m) * 60 + whole) * 1_000_000L + micros;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
