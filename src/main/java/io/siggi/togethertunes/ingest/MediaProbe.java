package io.siggi.togethertunes.ingest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The probe step of an ingest job: what ffprobe says about a source file, reduced to what the
 * pipeline needs (which streams to encode, where the cover art is, how long it is).
 *
 * <p>Runs {@code ffprobe -v error -show_format -show_streams -of json FILE}. A video stream that
 * is an attached picture (cover art in an MP3/FLAC/M4A), or a single still image in an image
 * codec, is a cover, not video; a file with only such streams is an audio item (see
 * {@link #isStillImage}). An image codec with moving pictures (Motion JPEG from a camera, a
 * looped still in Matroska) is video.
 *
 * @param formatName  ffprobe's {@code format_name}, e.g. {@code mov,mp4,m4a,3gp,3g2,mj2}
 * @param startUs     the container start time in microseconds ({@code start_time}; ffmpeg shifts
 *                    every stream by this so the output starts at 0), or 0 if unknown
 * @param durationUs  the container duration in microseconds, or -1 if unknown
 * @param audio       the first audio stream, or null if there is none
 * @param video       the first real video stream, or null (audio item)
 * @param cover       the first cover-art stream, or null
 * @param tags        metadata tags with lower-case keys: the container's ({@code format.tags}),
 *                    then those of the first audio stream for keys the container lacks (Ogg/Opus
 *                    keep their Vorbis comments there); empty if none
 */
public record MediaProbe(String formatName, long startUs, long durationUs, Stream audio, Stream video, Stream cover,
                         Map<String, String> tags) {
    public MediaProbe {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    /** A probe without tags. */
    public MediaProbe(String formatName, long startUs, long durationUs, Stream audio, Stream video, Stream cover) {
        this(formatName, startUs, durationUs, audio, video, cover, Map.of());
    }

    /** The tag {@code key} (lower case), stripped, or null if missing or blank. */
    public String tag(String key) {
        String v = tags.get(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** Size limit of ffprobe's JSON (a file with thousands of streams or huge tags is rejected). */
    static final int MAX_JSON_BYTES = 8 * 1024 * 1024;

    /** Codecs that are always still images, never a video track. */
    static final Set<String> IMAGE_CODECS = Set.of("mjpeg", "png", "bmp", "tiff", "webp", "gif", "jpeg2000");

    /**
     * One stream.
     *
     * @param index      the absolute stream index, for {@code -map 0:<index>}
     * @param codec      {@code codec_name}, e.g. {@code aac}, {@code h264}
     * @param startUs    the stream's start time in microseconds, or -1 if ffprobe has none
     * @param durationUs the stream's own duration in microseconds, or -1 if ffprobe has none
     */
    public record Stream(int index, String codec, long startUs, long durationUs) {
        /** The {@code -map} specifier for this stream of input 0. */
        public String map() {
            return "0:" + index;
        }
    }

    /** Whether this is a video item (has a real video stream). */
    public boolean isVideo() {
        return video != null;
    }

    /**
     * Where the audio ends in the encode, in microseconds: ffmpeg shifts the file so that it
     * starts at 0 and the encode pads a late audio start with silence, so this is the audio
     * stream's offset from the file start plus its own duration. -1 if ffprobe reports no
     * duration for the audio stream (common for Matroska); the pipeline then uses the length
     * read by {@link #readAudioEndUs}.
     */
    public long audioEndUs() {
        if (audio == null || audio.durationUs() <= 0) {
            return -1;
        }
        long offset = audio.startUs() < 0 ? 0 : Math.max(0, audio.startUs() - startUs);
        return offset + audio.durationUs();
    }

    /** How far before the container's end {@link #readAudioEndUs} starts reading packets. */
    static final long TAIL_READ_US = 30_000_000;

    /**
     * Where the audio ends, like {@link #audioEndUs()}, for files whose audio stream has no
     * duration (Matroska/WebM, e.g. yt-dlp's downloads): reads the audio packets of the last
     * {@link #TAIL_READ_US} (or of the whole file if the container's duration is unknown) and takes
     * the end of the last one. -1 if there are none.
     */
    public long readAudioEndUs(Tools tools, Path file, JobLog log) throws ToolException, InterruptedException {
        if (audio == null) {
            return -1;
        }
        long seekUs = durationUs > 0 ? Math.max(0, startUs + durationUs - TAIL_READ_US) : -1;
        long[] end = {-1};
        ToolCall call = tools.ffprobe(tailArgs(file, audio.index(), seekUs)).stdoutLines(line -> {
            long e = packetEndUs(line);
            if (e > end[0]) {
                end[0] = e;
            }
        });
        if (log != null) {
            call.log(log);
        }
        call.run();
        return end[0] < 0 ? -1 : Math.max(0, end[0] - startUs);
    }

    static List<String> tailArgs(Path file, int streamIndex, long seekUs) {
        List<String> args = new ArrayList<>(List.of("-v", "error", "-select_streams", Integer.toString(streamIndex),
                "-show_entries", "packet=pts_time,duration_time", "-of", "csv=p=0"));
        if (seekUs >= 0) {
            args.addAll(List.of("-read_intervals", String.format(Locale.ROOT, "%.6f%%", seekUs / 1e6)));
        }
        args.add(file.toString());
        return args;
    }

    /** {@code pts_time,duration_time} → end in microseconds; -1 if the PTS is unknown. */
    static long packetEndUs(String line) {
        String[] parts = line.strip().split(",");
        if (parts.length < 1) {
            return -1;
        }
        long pts = micros(parts[0]);
        if (pts < 0) {
            return -1;
        }
        long duration = parts.length > 1 ? micros(parts[1]) : -1;
        return pts + Math.max(0, duration);
    }

    /** The ffprobe arguments (without the {@code -hide_banner} prefix). */
    static List<String> args(Path file) {
        return List.of("-v", "error", "-show_format", "-show_streams", "-of", "json", file.toString());
    }

    /** Probes {@code file}. ffprobe failing (unreadable/unknown format) is an {@link IngestFailure}. */
    public static MediaProbe probe(Tools tools, Path file, JobLog log) throws ToolException, InterruptedException,
            IngestFailure {
        ToolCall call = tools.ffprobe(args(file)).stdoutToString(MAX_JSON_BYTES).allowFailure();
        if (log != null) {
            call.log(log);
        }
        ToolResult result = call.run();
        if (result.exitCode() != 0) {
            String why = result.stderrTail().isBlank() ? "exit code " + result.exitCode() : lastLine(result.stderrTail());
            throw new IngestFailure("The file can't be read as audio or video (" + why + ")");
        }
        return parse(new String(result.stdout(), StandardCharsets.UTF_8));
    }

    private static String lastLine(String text) {
        String t = text.strip();
        int nl = t.lastIndexOf('\n');
        return nl < 0 ? t : t.substring(nl + 1);
    }

    /** Parses ffprobe's JSON output. */
    static MediaProbe parse(String json) throws IngestFailure {
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                throw new IngestFailure("ffprobe gave no information about the file");
            }
            root = e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IngestFailure("ffprobe's output could not be parsed", e);
        }
        JsonObject format = root.has("format") && root.get("format").isJsonObject()
                ? root.getAsJsonObject("format") : new JsonObject();
        String formatName = string(format, "format_name");
        long duration = micros(string(format, "duration"));
        long start = Math.max(0, micros(string(format, "start_time")));

        Stream audio = null;
        JsonObject audioJson = null;
        Stream video = null;
        Stream cover = null;
        List<JsonObject> streams = new ArrayList<>();
        if (root.has("streams") && root.get("streams").isJsonArray()) {
            JsonArray array = root.getAsJsonArray("streams");
            for (JsonElement e : array) {
                if (e.isJsonObject()) {
                    streams.add(e.getAsJsonObject());
                }
            }
        }
        for (JsonObject s : streams) {
            String type = string(s, "codec_type");
            String codec = string(s, "codec_name");
            if (!s.has("index") || codec == null) {
                continue;
            }
            int index;
            try {
                index = s.get("index").getAsInt();
            } catch (RuntimeException e) {
                continue;
            }
            Stream stream = new Stream(index, codec, micros(string(s, "start_time")), micros(string(s, "duration")));
            if ("audio".equals(type)) {
                if (audio == null) {
                    audio = stream;
                    audioJson = s;
                }
            } else if ("video".equals(type)) {
                if (attachedPic(s) || isStillImage(s, codec)) {
                    if (cover == null) {
                        cover = stream;
                    }
                } else if (video == null) {
                    video = stream;
                }
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        addTags(tags, format);
        if (audioJson != null) {
            addTags(tags, audioJson);
        }
        return new MediaProbe(formatName == null ? "" : formatName, start, duration, audio, video, cover, tags);
    }

    /** Most tags kept; longer values are cut (the item columns take a few hundred characters). */
    static final int MAX_TAGS = 200;
    static final int MAX_TAG_CHARS = 4096;

    /** Adds {@code o.tags} (string values) with lower-case keys, keeping keys already present. */
    private static void addTags(Map<String, String> into, JsonObject o) {
        if (!o.has("tags") || !o.get("tags").isJsonObject()) {
            return;
        }
        for (var e : o.getAsJsonObject("tags").entrySet()) {
            if (into.size() >= MAX_TAGS) {
                return;
            }
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonPrimitive()) {
                continue;
            }
            String value = v.getAsString();
            if (value.length() > MAX_TAG_CHARS) {
                value = value.substring(0, MAX_TAG_CHARS);
            }
            into.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), value);
        }
    }

    /** Above this length an image-codec stream of unknown frame count is taken as moving pictures. */
    static final long STILL_MAX_US = 500_000;

    /**
     * Whether a video stream that is not an attached picture is a still image (a cover) rather
     * than video: its codec is an image codec ({@link #IMAGE_CODECS}) and it has at most one frame
     * ({@code nb_frames}, or Matroska's {@code NUMBER_OF_FRAMES} statistics tag). If the frame
     * count is unknown, it is a still unless it lasts longer than {@link #STILL_MAX_US}
     * ({@code duration}, or Matroska's {@code DURATION} tag); with neither, a still.
     */
    static boolean isStillImage(JsonObject stream, String codec) {
        if (!IMAGE_CODECS.contains(codec.toLowerCase(Locale.ROOT))) {
            return false;
        }
        long frames = count(string(stream, "nb_frames"));
        if (frames < 0) {
            frames = count(tag(stream, "NUMBER_OF_FRAMES"));
        }
        if (frames >= 0) {
            return frames <= 1;
        }
        long duration = micros(string(stream, "duration"));
        if (duration < 0) {
            duration = clockMicros(tag(stream, "DURATION"));
        }
        return duration <= STILL_MAX_US;
    }

    private static String tag(JsonObject stream, String name) {
        if (!stream.has("tags") || !stream.get("tags").isJsonObject()) {
            return null;
        }
        for (var e : stream.getAsJsonObject("tags").entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) || e.getKey().toUpperCase(Locale.ROOT).startsWith(name + "-")) {
                JsonElement v = e.getValue();
                return v.isJsonPrimitive() ? v.getAsString() : null;
            }
        }
        return null;
    }

    /** A non-negative integer, or -1 if missing or not one. */
    static long count(String s) {
        if (s == null) {
            return -1;
        }
        try {
            long n = Long.parseLong(s.strip());
            return n < 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code HH:MM:SS.fraction} (Matroska's {@code DURATION} tag) to microseconds; -1 if missing or malformed. */
    static long clockMicros(String s) {
        if (s == null) {
            return -1;
        }
        String[] parts = s.strip().split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            long h = Long.parseLong(parts[0]);
            long m = Long.parseLong(parts[1]);
            long sec = micros(parts[2]);
            if (h < 0 || m < 0 || m >= 60 || sec < 0) {
                return -1;
            }
            return (h * 3600 + m * 60) * 1_000_000 + sec;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean attachedPic(JsonObject stream) {
        if (!stream.has("disposition") || !stream.get("disposition").isJsonObject()) {
            return false;
        }
        JsonElement v = stream.getAsJsonObject("disposition").get("attached_pic");
        try {
            return v != null && v.getAsInt() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) {
            return null;
        }
        return v.getAsString();
    }

    /** Seconds (a decimal string, e.g. {@code "12.345000"}) to microseconds; -1 if missing or not a number. */
    static long micros(String seconds) {
        if (seconds == null || seconds.isBlank() || seconds.equals("N/A")) {
            return -1;
        }
        try {
            BigDecimal s = new BigDecimal(seconds.strip());
            if (s.signum() < 0) {
                return -1;
            }
            return s.movePointRight(6).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }
}
