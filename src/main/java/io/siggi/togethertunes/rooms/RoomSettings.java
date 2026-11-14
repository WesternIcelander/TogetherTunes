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
 *   <tr><td>{@code libraryRequests}</td><td>true</td><td>off: only the owner and moderators may search libraries and request items by id</td></tr>
 *   <tr><td>{@code uploadRequests}</td><td>true</td><td>off: only the owner and moderators may request by upload; on needs {@code libraryRequests} on</td></tr>
 *   <tr><td>{@code requestsPerActor}</td><td>3</td><td>1..{@value ControlSettings#MAX_REQUESTS_PER_ACTOR}</td></tr>
 *   <tr><td>{@code allowDownvotes}</td><td>false</td><td></td></tr>
 *   <tr><td>{@code voteSkipThreshold}</td><td>0.5</td><td>a number in (0, 1], or {@code "off"}</td></tr>
 *   <tr><td>{@code noRepeatWindow}</td><td>10</td><td>0 (off)..{@value ControlSettings#MAX_NO_REPEAT_WINDOW}</td></tr>
 *   <tr><td>{@code transitionCard}</td><td>false</td><td></td></tr>
 *   <tr><td>{@code gapMs}</td><td>0</td><td>0..{@value #MAX_GAP_MS}</td></tr>
 *   <tr><td>{@code audioOnly}</td><td>false</td><td>audio-only room: every item plays as audio (a video's audio-only file), the program output shows cards at {@code output.audioOnlyBitrateKbps}; a change applies from the next item</td></tr>
 *   <tr><td>{@code requestsOnly}</td><td>false</td><td>forced requests-only mode ({@code docs/ui.md#requests-only-mode}):
 *       the room's pages play nothing, and guests and anonymous visitors get no media through this room
 *       ({@link RoomViewers}); logged-in users are never refused media</td></tr>
 *   <tr><td>{@code guests.canVote}, {@code guests.canRequestLibrary}</td><td>true</td><td>effective only if the server setting is on too</td></tr>
 *   <tr><td>{@code guests.canUpload}, {@code guests.canYoutube}</td><td>false</td><td>effective only if the server setting is on too</td></tr>
 *   <tr><td>{@code overlay.mode}</td><td>{@code "off"}</td><td>{@code off} | {@code always} | {@code idleOnly}
 *       ({@link OverlayMode}); effective only if the server's {@code overlay.enabled} is on too. Replaced the
 *       room's {@code overlay.enabled} (migration 0025: true → {@code always})</td></tr>
 *   <tr><td>{@code overlay.corner}</td><td>{@code "br"}</td><td>{@code br} | {@code bl} | {@code tr} | {@code tl}</td></tr>
 *   <tr><td>{@code overlay.heightPct}</td><td>22</td><td>{@value #MIN_OVERLAY_HEIGHT_PCT}..{@value #MAX_OVERLAY_HEIGHT_PCT}, % of the frame height</td></tr>
 *   <tr><td>{@code overlay.margin}</td><td>3</td><td>0..{@value #MAX_OVERLAY_MARGIN}, % of the frame height</td></tr>
 *   <tr><td>{@code outputs.rtmp.playbackUrl}</td><td>{@code ""} (none)</td><td>the viewer URL the RTMP service gives out; http(s), rtmp(s), rtsp(t), at most {@value #MAX_URL_LENGTH} characters</td></tr>
 *   <tr><td>{@code outputs.autoOff}</td><td>true</td><td>turn every output off automatically after {@code outputs.autoOffMinutes} with an empty queue, or (.ts, HLS, MP3) without listeners; off = always on. The server's {@code outputs.forceAutoOff} makes it on regardless</td></tr>
 *   <tr><td>{@code outputs.autoOffMinutes}</td><td>10</td><td>1..{@value #MAX_IDLE_STOP_MINUTES}; at most the server's {@code outputs.maxAutoOffMinutes} (higher values act as that)</td></tr>
 *   <tr><td>{@code outputs.ts.join}</td><td>{@code "next"}</td><td>{@code next} | {@code latest} (join point of {@code .ts} viewers)</td></tr>
 *   <tr><td>{@code autoplay.selectors}</td><td>{@code "owner"}</td><td>{@code owner} | {@code moderators} | {@code members}:
 *       who besides the owner may select playlists for autoplay ({@link AutoplaySelections}); by stored room role</td></tr>
 *   <tr><td>{@code autoplay.upcoming}</td><td>5</td><td>0..{@value #MAX_AUTOPLAY_UPCOMING}: how many autoplay songs to keep queued (0: autoplay off)</td></tr>
 *   <tr><td>{@code autoplay.mix}</td><td>{@code "random"}</td><td>{@code random} (one pool) | {@code turns} (between the selecting people)</td></tr>
 *   <tr><td>{@code autoplay.recentPct}</td><td>50</td><td>0..100: a song's weight ramps back to full once its last play is this % of the pool ago</td></tr>
 *   <tr><td>{@code autoplay.requestsFirst}</td><td>{@code "first"}</td><td>{@code first} (requests always first) | {@code alternate} (a request, then an autoplay song)</td></tr>
 *   <tr><td>{@code estimatedDelayMs.rtmp} / {@code .ts} / {@code .hls} / {@code .mp3}</td><td>5000 / 2000 / 8000 / 3000</td><td>0..{@value #MAX_DELAY_MS}, for requests-only mode (API name: {@code remote})</td></tr>
 * </table>
 *
 * <p>Whether an output exists and is on is not a setting: that is the {@code stream_outputs} row
 * and its {@code enabled} flag ({@code /api/rooms/{id}/outputs}), so there is one source of truth.
 *
 * <p><b>Uploads need library requests</b> ({@code docs/rooms.md#uploads-need-library-requests}): with
 * the upload pre-check, holding a file's content ID is enough to get its item, so an upload could
 * stand in for a library request. Hence {@code uploadRequests} on needs {@code libraryRequests} on,
 * and {@code guests.canUpload} on needs {@code guests.canRequestLibrary} on ({@link #COUPLED}). A
 * patch that would break this makes the companion change ({@link #patchAdjusting}); settings that
 * break it anyway (older rows) are read with the upload switch off.
 *
 * <p>{@link #parse} reads stored JSON leniently (anything unknown or invalid falls back to the
 * default); {@link #patch} validates a change from a client strictly.
 */
public final class RoomSettings {
    public static final int MAX_GAP_MS = 10_000;
    /** The shortest pause between items while {@code transitionCard} is on: one card GOP. */
    public static final int TRANSITION_CARD_MIN_MS = 2000;
    public static final int MIN_OVERLAY_HEIGHT_PCT = 5;
    public static final int MAX_OVERLAY_HEIGHT_PCT = 50;
    public static final int MAX_OVERLAY_MARGIN = 20;
    public static final int MAX_DELAY_MS = 120_000;
    public static final int MAX_URL_LENGTH = 2048;
    public static final int DEFAULT_IDLE_STOP_MINUTES = 10;
    public static final int MAX_IDLE_STOP_MINUTES = 1440;
    public static final int MAX_AUTOPLAY_UPCOMING = 50;

    /**
     * Upload switch → the library-request switch it needs: an upload switch is only on while its
     * companion is.
     */
    public static final Map<String, String> COUPLED = Map.of(
            "uploadRequests", "libraryRequests",
            "guests.canUpload", "guests.canRequestLibrary");

    /** The room's QR overlay mode ({@code overlay.mode}, {@code docs/media-types.md#qr-overlay-modes}). */
    public enum OverlayMode {
        /** No overlay anywhere. */
        OFF("off"),
        /** Every card (items, paused, queue empty, loading, transition) and music/uncategorized videos. */
        ALWAYS("always"),
        /** Only the queue-empty card. */
        IDLE_ONLY("idleOnly");

        private final String wire;

        OverlayMode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The mode with this wire name; {@link #OFF} for anything else. */
        public static OverlayMode of(String wire) {
            for (OverlayMode m : values()) {
                if (m.wire.equals(wire)) {
                    return m;
                }
            }
            return OFF;
        }

        static String[] wires() {
            return java.util.Arrays.stream(values()).map(OverlayMode::wire).toArray(String[]::new);
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
        def("libraryRequests", true, RoomSettings::bool);
        def("uploadRequests", true, RoomSettings::bool);
        def("requestsPerActor", ControlSettings.DEFAULT_REQUESTS_PER_ACTOR,
                whole(1, ControlSettings.MAX_REQUESTS_PER_ACTOR));
        def("allowDownvotes", false, RoomSettings::bool);
        def("voteSkipThreshold", 0.5, RoomSettings::threshold);
        def("noRepeatWindow", ControlSettings.DEFAULT_NO_REPEAT_WINDOW, whole(0, ControlSettings.MAX_NO_REPEAT_WINDOW));
        def("transitionCard", false, RoomSettings::bool);
        def("gapMs", 0, whole(0, MAX_GAP_MS));
        def("audioOnly", false, RoomSettings::bool);
        def("requestsOnly", false, RoomSettings::bool);
        def("guests.canVote", true, RoomSettings::bool);
        def("guests.canRequestLibrary", true, RoomSettings::bool);
        def("guests.canUpload", false, RoomSettings::bool);
        def("guests.canYoutube", false, RoomSettings::bool);
        def("overlay.mode", OverlayMode.OFF.wire(), choice(OverlayMode.wires()));
        def("overlay.corner", "br", choice("br", "bl", "tr", "tl"));
        def("overlay.heightPct", 22, whole(MIN_OVERLAY_HEIGHT_PCT, MAX_OVERLAY_HEIGHT_PCT));
        def("overlay.margin", 3, whole(0, MAX_OVERLAY_MARGIN));
        def("outputs.rtmp.playbackUrl", "", RoomSettings::playbackUrl);
        def("outputs.autoOff", true, RoomSettings::bool);
        def("outputs.autoOffMinutes", DEFAULT_IDLE_STOP_MINUTES, whole(1, MAX_IDLE_STOP_MINUTES));
        def("outputs.ts.join", "next", choice("next", "latest"));
        def("autoplay.selectors", "owner", choice("owner", "moderators", "members"));
        def("autoplay.upcoming", 5, whole(0, MAX_AUTOPLAY_UPCOMING));
        def("autoplay.mix", "random", choice("random", "turns"));
        def("autoplay.recentPct", 50, whole(0, 100));
        def("autoplay.requestsFirst", "first", choice("first", "alternate"));
        def("estimatedDelayMs.rtmp", 5000, whole(0, MAX_DELAY_MS));
        def("estimatedDelayMs.ts", 2000, whole(0, MAX_DELAY_MS));
        def("estimatedDelayMs.hls", 8000, whole(0, MAX_DELAY_MS));
        def("estimatedDelayMs.mp3", 3000, whole(0, MAX_DELAY_MS));
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
        for (Map.Entry<String, String> c : COUPLED.entrySet()) {
            if (Boolean.TRUE.equals(values.get(c.getKey())) && !Boolean.TRUE.equals(values.get(c.getValue()))) {
                values.put(c.getKey(), false); // stored state breaking the rule: read as upload off
            }
        }
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
        return patchAdjusting(change).settings();
    }

    /**
     * {@link #patchAdjusting}'s result.
     *
     * @param adjusted the settings changed on top of the patch to keep uploads coupled to library
     *                 requests ({@link #COUPLED}), dotted names; empty if none
     */
    public record Patched(RoomSettings settings, List<String> adjusted) {
    }

    /**
     * {@link #patch}, reporting the companion changes it made: turning an upload switch on while
     * its library-request switch is off turns that on too; turning a library-request switch off
     * while its upload switch is on turns that off too.
     *
     * @throws HttpError as {@link #patch}; {@code 400 invalid_setting} for a patch that turns an
     *                   upload switch on and its library-request switch off at once
     */
    public Patched patchAdjusting(JsonObject change) {
        Objects.requireNonNull(change);
        Map<String, Object> next = new LinkedHashMap<>(values);
        apply(next, "", change, true);
        List<String> adjusted = new java.util.ArrayList<>();
        for (String upload : List.of("uploadRequests", "guests.canUpload")) {
            String library = COUPLED.get(upload);
            if (!(Boolean) next.get(upload) || (Boolean) next.get(library)) {
                continue;
            }
            // These settings keep the rule, so the patch turned the upload switch on, the library one off, or both.
            boolean uploadTurnedOn = !(Boolean) values.get(upload);
            boolean libraryTurnedOff = (Boolean) values.get(library);
            if (uploadTurnedOn && libraryTurnedOff) {
                throw new HttpError(400, "invalid_setting", upload + ": uploads need " + library
                        + " on; it can't be turned off at the same time");
            }
            if (uploadTurnedOn) {
                next.put(library, true);
                adjusted.add(library);
            } else {
                next.put(upload, false);
                adjusted.add(upload);
            }
        }
        return new Patched(new RoomSettings(next), List.copyOf(adjusted));
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

    /**
     * {@code libraryRequests}: whether people other than the owner and moderators may search
     * libraries and request items by id (YouTube links are unaffected; off also means
     * {@link #uploadRequests()} off).
     */
    public boolean libraryRequests() {
        return (Boolean) values.get("libraryRequests");
    }

    /**
     * {@code uploadRequests}: whether people other than the owner and moderators may request by
     * upload. Only ever on while {@link #libraryRequests()} is.
     */
    public boolean uploadRequests() {
        return (Boolean) values.get("uploadRequests");
    }

    public boolean transitionCard() {
        return (Boolean) values.get("transitionCard");
    }

    public int gapMs() {
        return (Integer) values.get("gapMs");
    }

    /**
     * The pause between an item's natural end and the next item ({@code docs/rooms.md#transitions}):
     * {@link #gapMs()}, but at least {@value #TRANSITION_CARD_MIN_MS} ms (one card GOP) while
     * {@link #transitionCard()} is on, so the card is seen.
     */
    public int transitionGapMs() {
        return transitionCard() ? Math.max(gapMs(), TRANSITION_CARD_MIN_MS) : gapMs();
    }

    /**
     * {@code audioOnly}: whether the room is audio-only ({@code docs/rooms.md#audio-only-rooms}).
     * Entries that start playing after a change use the new mode; the current one keeps its own.
     */
    public boolean audioOnly() {
        return (Boolean) values.get("audioOnly");
    }

    /**
     * {@code requestsOnly}: the owner forces requests-only mode ({@code docs/ui.md#requests-only-mode}).
     * Clients play nothing; guests and anonymous visitors get no media through this room
     * ({@link RoomViewers#allowed}), logged-in users still get everything.
     */
    public boolean requestsOnly() {
        return (Boolean) values.get("requestsOnly");
    }

    /** The room half of {@code guests.canVote} (the server setting must be on too). */
    public boolean guestsCanVote() {
        return (Boolean) values.get("guests.canVote");
    }

    /** The room half of {@code guests.canRequestLibrary}. */
    public boolean guestsCanRequestLibrary() {
        return (Boolean) values.get("guests.canRequestLibrary");
    }

    /** The room half of {@code guests.canUpload} (only ever on while {@code guests.canRequestLibrary} is). */
    public boolean guestsCanUpload() {
        return (Boolean) values.get("guests.canUpload");
    }

    /** The room half of {@code guests.canYoutube}. */
    public boolean guestsCanYoutube() {
        return (Boolean) values.get("guests.canYoutube");
    }

    /**
     * {@code overlay.mode} ({@code docs/media-types.md#qr-overlay-modes}): what gets the QR overlay,
     * the room's half (the server's {@code overlay.enabled} must be on too).
     */
    public OverlayMode overlayMode() {
        return OverlayMode.of((String) values.get("overlay.mode"));
    }

    /** Whether the room's overlay mode is anything but {@code off} (the server's switch must be on too). */
    public boolean overlayEnabled() {
        return overlayMode() != OverlayMode.OFF;
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

    /**
     * {@code outputs.autoOff}: whether the room's outputs turn off automatically (the room's half;
     * the server's {@code outputs.forceAutoOff} turns it on regardless).
     */
    public boolean autoOff() {
        return (Boolean) values.get("outputs.autoOff");
    }

    /**
     * Minutes with an empty queue or without listeners after which an output is turned off (the
     * room's half; the effective limit is at most the server's {@code outputs.maxAutoOffMinutes}).
     */
    public int autoOffMinutes() {
        return (Integer) values.get("outputs.autoOffMinutes");
    }

    /** Whether {@code .ts} viewers get the most recent unit at once ({@code join=latest}) instead of waiting for the next. */
    public boolean tsJoinLatest() {
        return "latest".equals(values.get("outputs.ts.join"));
    }

    /** Who may select playlists for autoplay, besides the owner. */
    public enum AutoplaySelectors {
        OWNER, MODERATORS, MEMBERS
    }

    /** {@code autoplay.selectors}. */
    public AutoplaySelectors autoplaySelectors() {
        return switch ((String) values.get("autoplay.selectors")) {
            case "moderators" -> AutoplaySelectors.MODERATORS;
            case "members" -> AutoplaySelectors.MEMBERS;
            default -> AutoplaySelectors.OWNER;
        };
    }

    /** {@code autoplay.upcoming}: how many autoplay entries to keep queued (0: none). */
    public int autoplayUpcoming() {
        return (Integer) values.get("autoplay.upcoming");
    }

    /** {@code autoplay.mix}: whether the selecting people take turns ({@code turns}) instead of one pool. */
    public boolean autoplayTurns() {
        return "turns".equals(values.get("autoplay.mix"));
    }

    /** {@code autoplay.recentPct}. */
    public int autoplayRecentPct() {
        return (Integer) values.get("autoplay.recentPct");
    }

    /** {@code autoplay.requestsFirst}: whether requests and autoplay songs alternate ({@code alternate}). */
    public boolean autoplayAlternate() {
        return "alternate".equals(values.get("autoplay.requestsFirst"));
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

    public int estimatedDelayMsMp3() {
        return (Integer) values.get("estimatedDelayMs.mp3");
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
