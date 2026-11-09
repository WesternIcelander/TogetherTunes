package io.siggi.togethertunes.rooms;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.http.HttpError;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A room's settings ({@code rooms.settings_json}, {@code docs/rooms.md#room-settings-settings_json}),
 * immutable. Every setting has a default; the stored JSON holds only the values that differ from
 * it, so a default changed in a later version reaches every room that never set it.
 *
 * <p>On the wire (and in storage) the dotted names are nested objects: {@code guests.canVote} is
 * {@code {"guests": {"canVote": true}}}. The settings and their defaults:
 *
 * <table>
 *   <caption>Room settings</caption>
 *   <tr><td>{@code requestScope}</td><td>{@code "owner"}</td><td>{@code owner} | {@code owner+self} | {@code none}</td></tr>
 *   <tr><td>{@code requestsPerActor}</td><td>3</td><td>1..{@value ControlSettings#MAX_REQUESTS_PER_ACTOR}</td></tr>
 *   <tr><td>{@code allowDownvotes}</td><td>false</td><td></td></tr>
 *   <tr><td>{@code voteSkipThreshold}</td><td>0.5</td><td>a number in (0, 1], or {@code "off"}</td></tr>
 *   <tr><td>{@code noRepeatWindow}</td><td>10</td><td>0 (off)..{@value ControlSettings#MAX_NO_REPEAT_WINDOW}</td></tr>
 *   <tr><td>{@code transitionCard}</td><td>false</td><td></td></tr>
 *   <tr><td>{@code gapMs}</td><td>0</td><td>0..{@value #MAX_GAP_MS}</td></tr>
 *   <tr><td>{@code guests.canVote}, {@code guests.canRequestLibrary}</td><td>true</td><td>effective only if the server setting is on too</td></tr>
 *   <tr><td>{@code guests.canUpload}, {@code guests.canYoutube}</td><td>false</td><td>effective only if the server setting is on too</td></tr>
 *   <tr><td>{@code overlay.enabled}</td><td>false</td><td>effective only if the server's {@code overlay.enabled} is on too</td></tr>
 *   <tr><td>{@code overlay.corner}</td><td>{@code "br"}</td><td>{@code br} | {@code bl} | {@code tr} | {@code tl}</td></tr>
 *   <tr><td>{@code overlay.heightPct}</td><td>22</td><td>{@value #MIN_OVERLAY_HEIGHT_PCT}..{@value #MAX_OVERLAY_HEIGHT_PCT}, % of the frame height</td></tr>
 *   <tr><td>{@code overlay.margin}</td><td>3</td><td>0..{@value #MAX_OVERLAY_MARGIN}, % of the frame height</td></tr>
 *   <tr><td>{@code outputs.rtmp.playbackUrl}</td><td>{@code ""} (none)</td><td>the viewer URL the RTMP service gives out; http(s), rtmp(s), rtsp(t), at most {@value #MAX_URL_LENGTH} characters</td></tr>
 *   <tr><td>{@code outputs.ts.join}</td><td>{@code "next"}</td><td>{@code next} | {@code latest} (join point of {@code .ts} viewers)</td></tr>
 *   <tr><td>{@code estimatedDelayMs.rtmp} / {@code .ts} / {@code .hls}</td><td>5000 / 2000 / 8000</td><td>0..{@value #MAX_DELAY_MS}, for remote mode</td></tr>
 * </table>
 *
 * <p>Whether an output exists and is on is not a setting: that is the {@code stream_outputs} row
 * and its {@code enabled} flag ({@code /api/rooms/{id}/outputs}), so there is one source of truth.
 *
 * <p>{@link #parse} reads stored JSON leniently (anything unknown or invalid falls back to the
 * default); {@link #patch} validates a change from a client strictly.
 */
public final class RoomSettings {
    public static final int MAX_GAP_MS = 10_000;
    public static final int MIN_OVERLAY_HEIGHT_PCT = 5;
    public static final int MAX_OVERLAY_HEIGHT_PCT = 50;
    public static final int MAX_OVERLAY_MARGIN = 20;
    public static final int MAX_DELAY_MS = 120_000;
    public static final int MAX_URL_LENGTH = 2048;

    /** {@code requestScope}: whose library a room request may come from ({@code docs/libraries.md#visibility}). */
    public enum RequestScope {
        OWNER("owner"), OWNER_SELF("owner+self"), NONE("none");

        private final String wire;

        RequestScope(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static RequestScope parse(String s) {
            for (RequestScope r : values()) {
                if (r.wire.equals(s)) {
                    return r;
                }
            }
            throw new IllegalStateException("unknown request scope " + s);
        }
    }

    // ------------------------------------------------------------------ definitions

    /** One setting: its dotted name, default, and how a JSON value is checked. */
    private record Def(String key, Object defaultValue, Function<JsonElement, Object> parser) {
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    /** Dotted names of the groups ({@code guests}, {@code outputs}, {@code outputs.rtmp}, …). */
    private static final Set<String> GROUPS;

    static {
        def("requestScope", "owner", choice("owner", "owner+self", "none"));
        def("requestsPerActor", ControlSettings.DEFAULT_REQUESTS_PER_ACTOR,
                whole(1, ControlSettings.MAX_REQUESTS_PER_ACTOR));
        def("allowDownvotes", false, RoomSettings::bool);
        def("voteSkipThreshold", 0.5, RoomSettings::threshold);
        def("noRepeatWindow", ControlSettings.DEFAULT_NO_REPEAT_WINDOW, whole(0, ControlSettings.MAX_NO_REPEAT_WINDOW));
        def("transitionCard", false, RoomSettings::bool);
        def("gapMs", 0, whole(0, MAX_GAP_MS));
        def("guests.canVote", true, RoomSettings::bool);
        def("guests.canRequestLibrary", true, RoomSettings::bool);
        def("guests.canUpload", false, RoomSettings::bool);
        def("guests.canYoutube", false, RoomSettings::bool);
        def("overlay.enabled", false, RoomSettings::bool);
        def("overlay.corner", "br", choice("br", "bl", "tr", "tl"));
        def("overlay.heightPct", 22, whole(MIN_OVERLAY_HEIGHT_PCT, MAX_OVERLAY_HEIGHT_PCT));
        def("overlay.margin", 3, whole(0, MAX_OVERLAY_MARGIN));
        def("outputs.rtmp.playbackUrl", "", RoomSettings::playbackUrl);
        def("outputs.ts.join", "next", choice("next", "latest"));
        def("estimatedDelayMs.rtmp", 5000, whole(0, MAX_DELAY_MS));
        def("estimatedDelayMs.ts", 2000, whole(0, MAX_DELAY_MS));
        def("estimatedDelayMs.hls", 8000, whole(0, MAX_DELAY_MS));
        Set<String> groups = new java.util.HashSet<>();
        for (String key : DEFS.keySet()) {
            for (int i = key.indexOf('.'); i > 0; i = key.indexOf('.', i + 1)) {
                groups.add(key.substring(0, i));
            }
        }
        GROUPS = Set.copyOf(groups);
    }

    private static void def(String key, Object defaultValue, Function<JsonElement, Object> parser) {
        DEFS.put(key, new Def(key, defaultValue, parser));
    }

    public static final RoomSettings DEFAULTS;

    static {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Def d : DEFS.values()) {
            values.put(d.key(), d.defaultValue());
        }
        DEFAULTS = new RoomSettings(values);
    }

    /** All setting names, dotted, in a stable order. */
    public static List<String> keys() {
        return List.copyOf(DEFS.keySet());
    }

    // ------------------------------------------------------------------ value parsers (throw IllegalArgumentException)

    private static Object bool(JsonElement e) {
        if (e instanceof JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        throw new IllegalArgumentException("expected true or false");
    }

    private static Function<JsonElement, Object> whole(int min, int max) {
        return e -> {
            if (e instanceof JsonPrimitive p && p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.rint(d) && d >= min && d <= max) {
                    return (int) d;
                }
            }
            throw new IllegalArgumentException("expected a whole number from " + min + " to " + max);
        };
    }

    private static Function<JsonElement, Object> choice(String... allowed) {
        List<String> options = List.of(allowed);
        return e -> {
            if (e instanceof JsonPrimitive p && p.isString() && options.contains(p.getAsString())) {
                return p.getAsString();
            }
            throw new IllegalArgumentException("expected one of " + String.join(", ", options));
        };
    }

    /** A number in (0, 1], or {@code "off"} (kept as the string). */
    private static Object threshold(JsonElement e) {
        if (e instanceof JsonPrimitive p) {
            if (p.isString() && "off".equals(p.getAsString())) {
                return "off";
            }
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d > 0 && d <= 1) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("expected a number above 0 and at most 1, or \"off\"");
    }

    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "rtmp", "rtmps", "rtsp", "rtspt");

    /** An absolute URL with a host, trimmed; an empty string means "none". */
    private static Object playbackUrl(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new IllegalArgumentException("expected a URL");
        }
        String s = p.getAsString().strip();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("at most " + MAX_URL_LENGTH + " characters");
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme != null && URL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)) && uri.getHost() != null
                    && !uri.getHost().isEmpty()) {
                return s;
            }
        } catch (URISyntaxException ignored) {
            // fall through
        }
        throw new IllegalArgumentException("expected an http(s), rtmp(s) or rtsp(t) URL");
    }

    // ------------------------------------------------------------------ instances

    /** Setting name -> value (Boolean, Integer, Double or String), every setting present. */
    private final Map<String, Object> values;
    private final ControlSettings control;

    private RoomSettings(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
        Object threshold = values.get("voteSkipThreshold");
        this.control = new ControlSettings((Boolean) values.get("allowDownvotes"),
                threshold instanceof Double d ? d : null, (Integer) values.get("requestsPerActor"),
                (Integer) values.get("noRepeatWindow"));
    }

    /**
     * Reads stored settings leniently: unknown names are ignored, and an invalid or missing value
     * (or JSON that isn't an object at all) gives the default.
     */
    public static RoomSettings parse(String settingsJson) {
        JsonElement e;
        try {
            e = settingsJson == null ? null : JsonParser.parseString(settingsJson);
        } catch (RuntimeException ex) {
            return DEFAULTS;
        }
        if (e == null || !e.isJsonObject()) {
            return DEFAULTS;
        }
        Map<String, Object> values = new LinkedHashMap<>(DEFAULTS.values);
        apply(values, "", e.getAsJsonObject(), false);
        return new RoomSettings(values);
    }

    /**
     * These settings with a change from a client applied (a JSON merge patch): a value sets that
     * setting, a nested object changes only the settings it names, and {@code null} resets a
     * setting (or a whole group) to its default. All or nothing.
     *
     * @throws HttpError {@code 400 unknown_setting} (a name that isn't a setting), {@code 400
     *                   invalid_setting} (a value out of range or of the wrong type)
     */
    public RoomSettings patch(JsonObject change) {
        Objects.requireNonNull(change);
        Map<String, Object> next = new LinkedHashMap<>(values);
        apply(next, "", change, true);
        return new RoomSettings(next);
    }

    private static void apply(Map<String, Object> values, String prefix, JsonObject o, boolean strict) {
        for (Map.Entry<String, JsonElement> m : o.entrySet()) {
            String key = prefix + m.getKey();
            JsonElement v = m.getValue();
            // Names are nested objects, never dotted: {"guests": {"canVote": …}}.
            Def def = m.getKey().contains(".") ? null : DEFS.get(key);
            if (def != null) {
                if (v == null || v.isJsonNull()) {
                    values.put(key, def.defaultValue());
                    continue;
                }
                try {
                    values.put(key, def.parser().apply(v));
                } catch (IllegalArgumentException ex) {
                    if (strict) {
                        throw new HttpError(400, "invalid_setting", key + ": " + ex.getMessage());
                    }
                    values.put(key, def.defaultValue());
                }
            } else if (!m.getKey().contains(".") && GROUPS.contains(key)) {
                if (v == null || v.isJsonNull()) {
                    for (Def d : DEFS.values()) {
                        if (d.key().startsWith(key + ".")) {
                            values.put(d.key(), d.defaultValue());
                        }
                    }
                } else if (v.isJsonObject()) {
                    apply(values, key + ".", v.getAsJsonObject(), strict);
                } else if (strict) {
                    throw new HttpError(400, "invalid_setting", key + ": expected an object");
                }
            } else if (strict) {
                throw new HttpError(400, "unknown_setting", "Unknown setting: " + key);
            }
        }
    }

    /** Every setting with its value, nested, as clients get it. */
    public JsonObject toJson() {
        return json(false);
    }

    /** The settings that differ from their defaults, nested: what {@code settings_json} stores. */
    public String toStoredJson() {
        return json(true).toString();
    }

    private JsonObject json(boolean onlyChanged) {
        JsonObject root = new JsonObject();
        for (Def d : DEFS.values()) {
            Object v = values.get(d.key());
            if (onlyChanged && Objects.equals(v, d.defaultValue())) {
                continue;
            }
            String[] path = d.key().split("\\.");
            JsonObject o = root;
            for (int i = 0; i < path.length - 1; i++) {
                JsonObject child = o.getAsJsonObject(path[i]);
                if (child == null) {
                    child = new JsonObject();
                    o.add(path[i], child);
                }
                o = child;
            }
            String name = path[path.length - 1];
            if (v == null) {
                o.add(name, JsonNull.INSTANCE);
            } else if (v instanceof Boolean b) {
                o.addProperty(name, b);
            } else if (v instanceof Number n) {
                o.addProperty(name, n);
            } else {
                o.addProperty(name, (String) v);
            }
        }
        return root;
    }

    /** The names of the settings whose values differ between these settings and {@code other}. */
    public List<String> changedFrom(RoomSettings other) {
        return DEFS.keySet().stream().filter(k -> !Objects.equals(values.get(k), other.values.get(k))).toList();
    }

    // ------------------------------------------------------------------ typed accessors

    /** The settings the control modes and queue operations read. */
    public ControlSettings control() {
        return control;
    }

    public RequestScope requestScope() {
        return RequestScope.parse((String) values.get("requestScope"));
    }

    public boolean transitionCard() {
        return (Boolean) values.get("transitionCard");
    }

    public int gapMs() {
        return (Integer) values.get("gapMs");
    }

    /** The room half of {@code guests.canVote} (the server setting must be on too). */
    public boolean guestsCanVote() {
        return (Boolean) values.get("guests.canVote");
    }

    /** The room half of {@code guests.canRequestLibrary}. */
    public boolean guestsCanRequestLibrary() {
        return (Boolean) values.get("guests.canRequestLibrary");
    }

    /** The room half of {@code guests.canUpload}. */
    public boolean guestsCanUpload() {
        return (Boolean) values.get("guests.canUpload");
    }

    /** The room half of {@code guests.canYoutube}. */
    public boolean guestsCanYoutube() {
        return (Boolean) values.get("guests.canYoutube");
    }

    /** The room half of the overlay switch (the server's {@code overlay.enabled} must be on too). */
    public boolean overlayEnabled() {
        return (Boolean) values.get("overlay.enabled");
    }

    /** {@code br}, {@code bl}, {@code tr} or {@code tl}. */
    public String overlayCorner() {
        return (String) values.get("overlay.corner");
    }

    public int overlayHeightPct() {
        return (Integer) values.get("overlay.heightPct");
    }

    /** The overlay's distance from the frame edges, in % of the frame height. */
    public int overlayMargin() {
        return (Integer) values.get("overlay.margin");
    }

    /** The viewer URL of the RTMP service, or {@code null} for none. */
    public String rtmpPlaybackUrl() {
        String url = (String) values.get("outputs.rtmp.playbackUrl");
        return url.isEmpty() ? null : url;
    }

    /** Whether {@code .ts} viewers get the most recent unit at once ({@code join=latest}) instead of waiting for the next. */
    public boolean tsJoinLatest() {
        return "latest".equals(values.get("outputs.ts.join"));
    }

    public int estimatedDelayMsRtmp() {
        return (Integer) values.get("estimatedDelayMs.rtmp");
    }

    public int estimatedDelayMsTs() {
        return (Integer) values.get("estimatedDelayMs.ts");
    }

    public int estimatedDelayMsHls() {
        return (Integer) values.get("estimatedDelayMs.hls");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoomSettings other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "RoomSettings" + toStoredJson();
    }
}
