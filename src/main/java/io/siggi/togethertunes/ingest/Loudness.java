package io.siggi.togethertunes.ingest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Two-pass EBU R128 loudness normalisation ({@code docs/library-and-ingest.md#the-mezzanine-profile}):
 * pass 1 measures the source with {@code loudnorm=…:print_format=json}; pass 2 is the audio
 * filter of the encode, {@link Measurement#filter()}, which applies the measured values with
 * {@code linear=true}.
 */
public final class Loudness {
    /** Integrated loudness target, LUFS. */
    public static final double TARGET_I = -14;
    /** True-peak ceiling, dBTP. */
    public static final double TARGET_TP = -1;
    /** Loudness range target, LU. */
    public static final double TARGET_LRA = 11;

    static final String BASE = "loudnorm=I=-14:TP=-1:LRA=11";
    /** Back to the profile rate (loudnorm works at 192 kHz internally); a late audio start is padded with silence so the audio starts at 0. */
    static final String RESAMPLE = "aresample=48000:first_pts=0";

    private Loudness() {
    }

    /**
     * The result of pass 1.
     *
     * @param inputI      integrated loudness, LUFS ({@code -Infinity} for silence)
     * @param inputTp     true peak, dBTP
     * @param inputLra    loudness range, LU
     * @param inputThresh gating threshold, LUFS
     * @param offset      {@code target_offset}, LU
     */
    public record Measurement(double inputI, double inputTp, double inputLra, double inputThresh, double offset) {
        /**
         * Whether the values can drive pass 2. Digital silence (or a source too short to gate)
         * measures {@code -inf}; it is then encoded without normalisation.
         */
        public boolean usable() {
            return Double.isFinite(inputI) && Double.isFinite(inputTp) && Double.isFinite(inputLra)
                    && Double.isFinite(inputThresh) && Double.isFinite(offset);
        }

        /** The pass-2 audio filter, or plain resampling if the measurement isn't {@link #usable()}. */
        public String filter() {
            if (!usable()) {
                return RESAMPLE;
            }
            return String.format(Locale.ROOT, "%s:measured_I=%.2f:measured_TP=%.2f:measured_LRA=%.2f"
                    + ":measured_thresh=%.2f:offset=%.2f:linear=true:print_format=summary,%s",
                    BASE, inputI, inputTp, inputLra, inputThresh, offset, RESAMPLE);
        }
    }

    /**
     * The pass-1 ffmpeg arguments for the audio stream {@code map} of {@code input}. The resampler
     * after loudnorm is not needed for the measurement, but without it ffmpeg's progress
     * {@code out_time} is far too small (loudnorm's 192 kHz output), so the progress bar wouldn't move.
     */
    static List<String> measureArgs(String input, String map) {
        return List.of("-i", input, "-map", map, "-vn", "-sn", "-dn",
                "-af", BASE + ":print_format=json,aresample=48000", "-f", "null", "-");
    }

    /**
     * Collects the JSON block loudnorm prints to stderr at the end of pass 1: the lines after the
     * {@code [Parsed_loudnorm…]} line, from {@code {} to {@code }}.
     */
    static final class JsonCollector implements Consumer<String> {
        private final List<String> lines = new ArrayList<>();
        private boolean afterHeader;
        private boolean inside;

        @Override
        public synchronized void accept(String line) {
            String t = line.strip();
            if (t.startsWith("[Parsed_loudnorm")) {
                afterHeader = true;
                inside = false;
                lines.clear();
                return;
            }
            if (!afterHeader) {
                return;
            }
            if (!inside && t.equals("{")) {
                inside = true;
                lines.clear();
            }
            if (inside) {
                lines.add(t);
                if (t.equals("}")) {
                    inside = false;
                    afterHeader = false;
                }
            }
        }

        synchronized String json() {
            return lines.isEmpty() || !lines.get(lines.size() - 1).equals("}") ? null : String.join("\n", lines);
        }
    }

    /**
     * Runs pass 1 on stream {@code map} of {@code input}.
     *
     * @param log      job log (every stderr line also goes there)
     * @param progress ffmpeg progress, or null
     */
    public static Measurement measure(Tools tools, String input, String map, JobLog log,
                                      Consumer<FfmpegProgress> progress)
            throws ToolException, InterruptedException, IngestFailure {
        JsonCollector collector = new JsonCollector();
        JobLog tee = new JobLog(JobLog.DEFAULT_MAX_CHARS, line -> {
            collector.accept(line);
            if (log != null) {
                log.append(line);
            }
        });
        ToolCall call = tools.ffmpeg(measureArgs(input, map)).log(tee);
        if (progress != null) {
            call.ffmpegProgress(progress);
        }
        call.run();
        String json = collector.json();
        if (json == null) {
            throw new IngestFailure("The loudness measurement gave no result");
        }
        return parse(json);
    }

    /** Parses loudnorm's JSON block. */
    static Measurement parse(String json) throws IngestFailure {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new IngestFailure("The loudness measurement could not be parsed");
            }
            JsonObject o = e.getAsJsonObject();
            return new Measurement(number(o, "input_i"), number(o, "input_tp"), number(o, "input_lra"),
                    number(o, "input_thresh"), number(o, "target_offset"));
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IngestFailure("The loudness measurement could not be parsed", e);
        }
    }

    private static double number(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        String s = v.getAsString().strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan", "-nan" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }
}
