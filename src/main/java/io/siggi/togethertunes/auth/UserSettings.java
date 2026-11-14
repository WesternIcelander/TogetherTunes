package io.siggi.togethertunes.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A user's own preferences ({@code user_settings}, {@code docs/ui.md#existing-features-in-the-new-ui},
 * {@code GET}/{@code PATCH /api/me/settings}). Stored on the server so they follow the account
 * to every browser; the client applies them.
 *
 * <ul>
 *   <li>{@code doubleClickAlone}: {@code "play"} (default: play immediately) or {@code "queue"}
 *       (add to the queue): what double-clicking a song does while the user is alone.</li>
 *   <li>{@code webAudio}: {@code "auto"} (default), {@code "on"} or {@code "off"}: whether the
 *       player plays through Web Audio (the client decides what Auto does).</li>
 *   <li>{@code debug}: {@code false} (default) or {@code true}: debug mode (e.g. the Send
 *       diagnostics button).</li>
 * </ul>
 * A user without a row has the defaults. The row stores only the keys the user set; a stored value
 * that is missing or not understood (e.g. an option removed later) reads as the default.
 * {@link #patch} is all or nothing: an unknown key is {@code 400 unknown_setting}, a bad value
 * {@code 400 invalid_setting}, and nothing is saved then.
 */
public final class UserSettings {
    public static final String DOUBLE_CLICK_ALONE = "doubleClickAlone";
    public static final String WEB_AUDIO = "webAudio";
    public static final String DEBUG = "debug";

    private static final List<String> DOUBLE_CLICK_VALUES = List.of("play", "queue");
    private static final List<String> WEB_AUDIO_VALUES = List.of("auto", "on", "off");

    /** The effective preferences. */
    public record Prefs(String doubleClickAlone, String webAudio, boolean debug) {
        public static final Prefs DEFAULTS = new Prefs("play", "auto", false);

        public Prefs {
            Objects.requireNonNull(doubleClickAlone);
            Objects.requireNonNull(webAudio);
        }

        /** The API form: every key, defaults included. */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(DOUBLE_CLICK_ALONE, doubleClickAlone);
            m.put(WEB_AUDIO, webAudio);
            m.put(DEBUG, debug);
            return m;
        }
    }

    private final Database db;
    private final Clock clock;

    public UserSettings(Database db) {
        this(db, Clock.systemUTC());
    }

    public UserSettings(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The user's preferences, the defaults when they never changed any. */
    public Prefs get(String userId) throws SQLException {
        return effective(db.read(c -> stored(c, userId, false)));
    }

    /**
     * Sets the keys in {@code changes} (others keep their value) and returns the result. An empty
     * object changes nothing.
     *
     * @throws HttpError {@code 400 unknown_setting}, {@code 400 invalid_setting}
     */
    public Prefs patch(String userId, JsonObject changes) throws SQLException {
        Objects.requireNonNull(userId);
        JsonObject valid = validate(changes);
        if (valid.isEmpty()) {
            return get(userId);
        }
        return effective(db.write(c -> {
            // Make sure the row exists, then lock it (PostgreSQL), so concurrent patches of
            // different keys don't lose each other's change.
            try (PreparedStatement ps = c.prepareStatement(db.dialect().upsert("user_settings",
                    List.of("user_id", "settings_json", "updated_at"), List.of("user_id"), List.of()))) {
                ps.setString(1, userId);
                ps.setString(2, "{}");
                ps.setLong(3, EpochMs.now(clock));
                ps.executeUpdate();
            }
            JsonObject stored = stored(c, userId, true);
            for (Map.Entry<String, JsonElement> e : valid.entrySet()) {
                stored.add(e.getKey(), e.getValue());
            }
            // Keep only the keys this version knows (drops leftovers of removed settings).
            JsonObject keep = new JsonObject();
            for (String key : List.of(DOUBLE_CLICK_ALONE, WEB_AUDIO, DEBUG)) {
                if (stored.has(key)) {
                    keep.add(key, stored.get(key));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE user_settings SET settings_json = ?, updated_at = ? WHERE user_id = ?")) {
                ps.setString(1, keep.toString());
                ps.setLong(2, EpochMs.now(clock));
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            return keep;
        }));
    }

    // ------------------------------------------------------------- internals

    private JsonObject stored(Connection c, String userId, boolean lock) throws SQLException {
        String sql = "SELECT settings_json FROM user_settings WHERE user_id = ?"
                + (lock && db.dialect() instanceof PostgresDialect ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new JsonObject();
                }
                return parse(rs.getString(1));
            }
        }
    }

    private static JsonObject parse(String json) {
        try {
            JsonElement e = json == null ? null : JsonParser.parseString(json);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    /** The stored keys over the defaults; anything not understood is the default. */
    static Prefs effective(JsonObject stored) {
        Prefs d = Prefs.DEFAULTS;
        String doubleClick = choice(stored.get(DOUBLE_CLICK_ALONE), DOUBLE_CLICK_VALUES);
        String webAudio = choice(stored.get(WEB_AUDIO), WEB_AUDIO_VALUES);
        Boolean debug = bool(stored.get(DEBUG));
        return new Prefs(doubleClick == null ? d.doubleClickAlone() : doubleClick,
                webAudio == null ? d.webAudio() : webAudio,
                debug == null ? d.debug() : debug);
    }

    /** Checks every key and value of a patch; returns them (a {@code null} value is invalid). */
    static JsonObject validate(JsonObject changes) {
        if (changes == null) {
            throw new HttpError(400, "invalid_json", "Expected a JSON object");
        }
        JsonObject valid = new JsonObject();
        for (Map.Entry<String, JsonElement> e : changes.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            switch (key) {
                case DOUBLE_CLICK_ALONE -> requireChoice(key, value, DOUBLE_CLICK_VALUES);
                case WEB_AUDIO -> requireChoice(key, value, WEB_AUDIO_VALUES);
                case DEBUG -> {
                    if (bool(value) == null) {
                        throw new HttpError(400, "invalid_setting", key + " must be true or false");
                    }
                }
                default -> throw new HttpError(400, "unknown_setting", "Unknown setting: " + key);
            }
            valid.add(key, value);
        }
        return valid;
    }

    private static void requireChoice(String key, JsonElement value, List<String> options) {
        if (choice(value, options) == null) {
            throw new HttpError(400, "invalid_setting", key + " must be one of " + String.join(", ", options));
        }
    }

    private static String choice(JsonElement value, List<String> options) {
        if (value instanceof JsonPrimitive p && p.isString() && options.contains(p.getAsString())) {
            return p.getAsString();
        }
        return null;
    }

    private static Boolean bool(JsonElement value) {
        if (value instanceof JsonPrimitive p && p.isBoolean()) {
            return p.getAsBoolean();
        }
        return null;
    }
}
