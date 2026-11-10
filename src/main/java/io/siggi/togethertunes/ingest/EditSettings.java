package io.siggi.togethertunes.ingest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.http.Json;

/**
 * An item's edit settings ({@code docs/library-and-ingest.md#trim}), stored as JSON per media
 * version ({@code media_versions.edit_json}) and on a {@code trim} job ({@code ingest_jobs.edit_json}).
 * All times are milliseconds on the <b>original</b>'s timeline.
 *
 * <ul>
 *   <li>{@code videoStartMs}: where a video item's encode starts (absent: 0);</li>
 *   <li>{@code videoEndMs}: where it ends (absent: the end of the original);</li>
 *   <li>{@code originalMs}: how long the original is, as probed by the encode that applied a trim
 *       (informational, for the trim timeline; absent while there is no trim, when the item's
 *       duration is the original's).</li>
 * </ul>
 *
 * <p>Other keys (later edit settings, e.g. an audio trim) are kept as they are by
 * {@link #withVideoTrim} and {@link #withOriginalMs}.
 *
 * @param videoStartMs where the video encode starts, ≥ 0
 * @param videoEndMs   where it ends, or null for the end of the original
 * @param originalMs   the original's length, or null if not recorded
 */
public record EditSettings(long videoStartMs, Long videoEndMs, Long originalMs) {
    /** No edits. */
    public static final EditSettings NONE = new EditSettings(0, null, null);

    /** Whether a video trim is set. */
    public boolean videoTrimmed() {
        return videoStartMs > 0 || videoEndMs != null;
    }

    /** The settings in {@code json}; {@link #NONE} for null, blank or malformed JSON. */
    public static EditSettings parse(String json) {
        JsonObject o = object(json);
        long start = Math.max(0, number(o, "videoStartMs", 0));
        long end = number(o, "videoEndMs", -1);
        long original = number(o, "originalMs", -1);
        return new EditSettings(start, end > start ? end : null, original > 0 ? original : null);
    }

    /**
     * {@code json} with the video trim replaced: {@code videoStartMs} (left out when 0) and
     * {@code videoEndMs} (left out when null), and {@code originalMs} when given; other keys kept.
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

    /** {@code json} with {@code originalMs} set; other keys kept. */
    public static String withOriginalMs(String json, long originalMs) {
        JsonObject o = object(json);
        o.addProperty("originalMs", originalMs);
        return Json.GSON.toJson(o);
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
