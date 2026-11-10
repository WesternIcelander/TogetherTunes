package io.siggi.togethertunes.ingest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.media.EncodeProfile;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An item's edit settings ({@code docs/library-and-ingest.md#trim}), stored as JSON per media
 * version ({@code media_versions.edit_json}). All times are milliseconds on the <b>original</b>'s
 * timeline.
 *
 * <ul>
 *   <li>{@code videoStartMs}: where a video item's encode starts (absent: 0);</li>
 *   <li>{@code videoEndMs}: where it ends (absent: the end of the original);</li>
 *   <li>{@code audioStartMs} / {@code audioEndMs}: the audio trim ({@code docs/library-and-ingest.md#audio-trim}):
 *       for an audio item the range its {@code audio.m4a} is encoded from; for a video item the range
 *       of its separate audio-only file ({@code audio-only.m4a}, played only in audio-only rooms).
 *       Absent {@code audioStartMs}: no audio trim (an audio item plays the whole original; a video's
 *       audio-only file is its {@code audio.m4a}); absent {@code audioEndMs}: to the end of the original;</li>
 *   <li>{@code originalMs}: how long the original is, as probed by the encode that applied a trim
 *       (informational, for the trim timeline; absent while there is no trim, when the item's
 *       duration is the original's).</li>
 *   <li>{@code firstGopFrames}: how many frames the version's first GOP has (1..59), when a smart
 *       trim made it shorter than 2 s (invariant 2); absent: 60. Not an edit setting but a fact about
 *       the encode, kept here so it travels with the version (undo) and the trim page can plan with
 *       it ({@link #firstGopFrames}, {@link #withFirstGop}); a full encode removes it.</li>
 * </ul>
 *
 * <p>Other keys are kept as they are by the {@code with…} methods.
 *
 * <p><b>Trim jobs</b> ({@code ingest_jobs.edit_json}) carry only what they change: {@code parts}
 * ({@code ["video"]}, {@code ["audio"]} or both; absent: {@code ["video"]}, as jobs queued before
 * the audio trim) and the keys of those parts ({@link #job}, {@link #applyJob}, {@link #mergeJobs}),
 * so a change applies on top of whatever the item has when the job runs (an undo may have come
 * between).
 *
 * @param videoStartMs where the video encode starts, ≥ 0
 * @param videoEndMs   where it ends, or null for the end of the original
 * @param originalMs   the original's length, or null if not recorded
 * @param audioStartMs where the audio trim starts, or null for no audio trim
 * @param audioEndMs   where it ends, or null for the end of the original (only with {@code audioStartMs})
 */
public record EditSettings(long videoStartMs, Long videoEndMs, Long originalMs, Long audioStartMs, Long audioEndMs) {
    /** No edits. */
    public static final EditSettings NONE = new EditSettings(0, null, null, null, null);

    /** The part of a trim job that sets the video trim. */
    public static final String PART_VIDEO = "video";
    /** The part of a trim job that sets the audio trim. */
    public static final String PART_AUDIO = "audio";

    /** Without an audio trim. */
    public EditSettings(long videoStartMs, Long videoEndMs, Long originalMs) {
        this(videoStartMs, videoEndMs, originalMs, null, null);
    }

    /** Whether a video trim is set. */
    public boolean videoTrimmed() {
        return videoStartMs > 0 || videoEndMs != null;
    }

    /** Whether an audio trim is set. */
    public boolean audioTrimmed() {
        return audioStartMs != null;
    }

    /** Whether the video trims of the two are the same. */
    public boolean sameVideoTrim(EditSettings o) {
        return videoStartMs == o.videoStartMs && java.util.Objects.equals(videoEndMs, o.videoEndMs);
    }

    /** Whether the audio trims of the two are the same. */
    public boolean sameAudioTrim(EditSettings o) {
        return java.util.Objects.equals(audioStartMs, o.audioStartMs) && java.util.Objects.equals(audioEndMs, o.audioEndMs);
    }

    /** The settings in {@code json}; {@link #NONE} for null, blank or malformed JSON. */
    public static EditSettings parse(String json) {
        JsonObject o = object(json);
        long start = Math.max(0, number(o, "videoStartMs", 0));
        long end = number(o, "videoEndMs", -1);
        long original = number(o, "originalMs", -1);
        long aStart = number(o, "audioStartMs", -1);
        long aEnd = number(o, "audioEndMs", -1);
        Long audioStart = aStart >= 0 ? aStart : null;
        Long audioEnd = audioStart != null && aEnd > aStart ? aEnd : null;
        return new EditSettings(start, end > start ? end : null, original > 0 ? original : null, audioStart, audioEnd);
    }

    /**
     * {@code json} with the video trim replaced: {@code videoStartMs} (left out when 0) and
     * {@code videoEndMs} (left out when null), and {@code originalMs} when given; other keys kept.
     *
     * @return the JSON, or null if nothing is left
     */
    public static String withVideoTrim(String json, long startMs, Long endMs, Long originalMs) {
        JsonObject o = object(json);
        o.remove("videoStartMs");
        o.remove("videoEndMs");
        if (startMs > 0) {
            o.addProperty("videoStartMs", startMs);
        }
        if (endMs != null) {
            o.addProperty("videoEndMs", endMs);
        }
        if (originalMs != null) {
            o.addProperty("originalMs", originalMs);
        }
        return o.size() == 0 ? null : Json.GSON.toJson(o);
    }

    /**
     * {@code json} with the audio trim replaced: {@code audioStartMs} (left out when null: no audio
     * trim) and {@code audioEndMs} (left out when null, or when there is no start); other keys kept.
     *
     * @return the JSON, or null if nothing is left
     */
    public static String withAudioTrim(String json, Long startMs, Long endMs) {
        JsonObject o = object(json);
        o.remove("audioStartMs");
        o.remove("audioEndMs");
        if (startMs != null) {
            o.addProperty("audioStartMs", startMs);
            if (endMs != null) {
                o.addProperty("audioEndMs", endMs);
            }
        }
        return o.size() == 0 ? null : Json.GSON.toJson(o);
    }

    /** {@code json} with {@code originalMs} set; other keys kept. */
    public static String withOriginalMs(String json, long originalMs) {
        JsonObject o = object(json);
        o.addProperty("originalMs", originalMs);
        return Json.GSON.toJson(o);
    }

    /** The version's first GOP in frames ({@code firstGopFrames}; 60 when absent or out of range). */
    public static int firstGopFrames(String json) {
        long f = number(object(json), "firstGopFrames", EncodeProfile.GOP_FRAMES);
        return f >= 1 && f < EncodeProfile.GOP_FRAMES ? (int) f : EncodeProfile.GOP_FRAMES;
    }

    /**
     * {@code json} with {@code firstGopFrames} set (left out when it is 60, a normal encode); other
     * keys kept.
     *
     * @return the JSON, or null if nothing is left
     */
    public static String withFirstGop(String json, int firstGopFrames) {
        JsonObject o = object(json);
        o.remove("firstGopFrames");
        if (firstGopFrames >= 1 && firstGopFrames < EncodeProfile.GOP_FRAMES) {
            o.addProperty("firstGopFrames", firstGopFrames);
        }
        return o.size() == 0 ? null : Json.GSON.toJson(o);
    }

    // ------------------------------------------------------------------ trim jobs

    /**
     * A trim job's settings: the parts it changes ({@link #PART_VIDEO} if {@code video},
     * {@link #PART_AUDIO} if {@code audio}) and their keys.
     */
    public static String job(boolean video, long videoStartMs, Long videoEndMs,
                             boolean audio, Long audioStartMs, Long audioEndMs) {
        JsonObject o = new JsonObject();
        JsonArray parts = new JsonArray();
        if (video) {
            parts.add(PART_VIDEO);
            if (videoStartMs > 0) {
                o.addProperty("videoStartMs", videoStartMs);
            }
            if (videoEndMs != null) {
                o.addProperty("videoEndMs", videoEndMs);
            }
        }
        if (audio) {
            parts.add(PART_AUDIO);
            if (audioStartMs != null) {
                o.addProperty("audioStartMs", audioStartMs);
                if (audioEndMs != null) {
                    o.addProperty("audioEndMs", audioEndMs);
                }
            }
        }
        o.add("parts", parts);
        return Json.GSON.toJson(o);
    }

    /** The parts a trim job changes (absent: only the video trim, as jobs queued before the audio trim). */
    public static Set<String> parts(String jobJson) {
        JsonObject o = object(jobJson);
        Set<String> parts = new LinkedHashSet<>();
        if (o.get("parts") instanceof JsonArray a) {
            for (JsonElement e : a) {
                if (e instanceof JsonPrimitive p && p.isString()
                        && (PART_VIDEO.equals(p.getAsString()) || PART_AUDIO.equals(p.getAsString()))) {
                    parts.add(p.getAsString());
                }
            }
        } else {
            parts.add(PART_VIDEO);
        }
        return parts;
    }

    /** The settings a trim job makes from the item's current ones: its parts replaced, the rest kept. */
    public static String applyJob(String currentJson, String jobJson) {
        Set<String> parts = parts(jobJson);
        EditSettings asked = parse(jobJson);
        String json = currentJson;
        if (parts.contains(PART_VIDEO)) {
            json = withVideoTrim(json, asked.videoStartMs(), asked.videoEndMs(), null);
        }
        if (parts.contains(PART_AUDIO)) {
            json = withAudioTrim(json, asked.audioStartMs(), asked.audioEndMs());
        }
        return json;
    }

    /**
     * A queued trim job's settings with a newer change on top: the parts of {@code newer} replace
     * those of {@code queued}, parts only {@code queued} has are kept.
     */
    public static String mergeJobs(String queued, String newer) {
        Set<String> a = parts(queued);
        Set<String> b = parts(newer);
        EditSettings oldS = parse(queued);
        EditSettings newS = parse(newer);
        EditSettings video = b.contains(PART_VIDEO) ? newS : oldS;
        EditSettings audio = b.contains(PART_AUDIO) ? newS : oldS;
        return job(a.contains(PART_VIDEO) || b.contains(PART_VIDEO), video.videoStartMs(), video.videoEndMs(),
                a.contains(PART_AUDIO) || b.contains(PART_AUDIO), audio.audioStartMs(), audio.audioEndMs());
    }

    private static JsonObject object(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject().deepCopy() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static long number(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (Double.isFinite(d)) {
                return Math.round(d);
            }
        }
        return fallback;
    }
}
