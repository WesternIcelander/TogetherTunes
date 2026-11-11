package io.siggi.togethertunes.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import java.io.IOException;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server settings ({@code docs/auth.md#server-settings}), stored in the {@code settings} table
 * as {@code (key, value_json)} rows and edited in the admin UI.
 *
 * <p>A setting without a row has its default; {@link #reset} deletes the row, so a later change
 * of a default applies to every server that never changed that setting. A stored value that
 * can't be parsed or no longer validates (e.g. after a downgrade) is logged and replaced by the
 * default when read; rows with unknown keys are ignored. Values are validated on {@link #set},
 * so the admin UI gets an {@link IllegalArgumentException} with a readable message.
 *
 * <p>Reads go to the database every time (no cache), so a change is seen at once everywhere and
 * a {@code set} inside a transaction that rolls back leaves nothing behind. Calls inside a
 * {@link Database#read}/{@link Database#write} callback join that transaction.
 *
 * <p>Default roles are not a setting: they are the {@code roles.is_default} flag.
 */
public final class Settings {
    private static final System.Logger LOG = System.getLogger(Settings.class.getName());

    // Accounts
    public static final SettingKey<RegistrationMode> REGISTRATION_MODE = SettingKey.enumeration(
            "registration.mode", RegistrationMode.class, RegistrationMode.CLOSED, RegistrationMode::wireName);

    // Guests (docs/auth.md#guest-policy-server-settings)
    public static final SettingKey<Boolean> GUESTS_ENABLED = SettingKey.bool("guests.enabled", true);
    public static final SettingKey<GuestIdentity> GUESTS_IDENTIFY_BY = SettingKey.enumeration(
            "guests.identifyBy", GuestIdentity.class, GuestIdentity.COOKIE, GuestIdentity::wireName);
    public static final SettingKey<Boolean> GUESTS_ALLOW_CUSTOM_NAMES = SettingKey.bool("guests.allowCustomNames", false);
    public static final SettingKey<Boolean> GUESTS_CAN_VOTE = SettingKey.bool("guests.canVote", true);
    public static final SettingKey<Boolean> GUESTS_CAN_REQUEST_LIBRARY = SettingKey.bool("guests.canRequestLibrary", true);
    public static final SettingKey<Boolean> GUESTS_CAN_UPLOAD = SettingKey.bool("guests.canUpload", false);
    public static final SettingKey<Boolean> GUESTS_CAN_YOUTUBE = SettingKey.bool("guests.canYoutube", false);
    public static final SettingKey<UploadQuota> GUESTS_UPLOAD_QUOTA = SettingKey.uploadQuota(
            "guests.uploadQuota", new UploadQuota(3, 500L * 1024 * 1024));
    public static final SettingKey<Boolean> GUESTS_DOWNLOAD = SettingKey.bool("guests.download", false);
    /**
     * Words and phrases custom guest names must not contain (compared ignoring case, NFKC
     * normalisation, spaces and punctuation; see {@code auth.GuestNameRules}). Empty by default.
     */
    public static final SettingKey<StringList> GUESTS_NAME_BLOCKLIST = SettingKey.stringList(
            "guests.nameBlocklist", 2000, 64);

    // Library
    public static final SettingKey<Boolean> YTDLP_ENABLED = SettingKey.bool("ytdlp.enabled", true);
    public static final SettingKey<String> YTDLP_COOKIES_FILE = SettingKey.optionalString("ytdlp.cookiesFile");
    public static final SettingKey<Integer> RETENTION_UNREFERENCED_DAYS = SettingKey.integer(
            "retention.unreferencedDays", 14, 0, 36_500);
    /** Default here is the documented 1800; {@link #Settings(Database, Config)} uses the config file's value. */
    public static final SettingKey<Integer> INGEST_MAX_DURATION_SECONDS = SettingKey.integer(
            "ingest.maxDurationSeconds", 1800, 1, Integer.MAX_VALUE);
    /**
     * x264 preset of quick encodes: items wanted in a room now
     * ({@code docs/library-and-ingest.md#presets}). Default from the config file.
     */
    public static final SettingKey<X264Preset> INGEST_QUICK_PRESET = SettingKey.enumeration(
            "ingest.quickPreset", X264Preset.class, X264Preset.DEFAULT_QUICK, X264Preset::id);
    /** x264 preset of quality encodes: library items. Default from the config file. */
    public static final SettingKey<X264Preset> INGEST_QUALITY_PRESET = SettingKey.enumeration(
            "ingest.qualityPreset", X264Preset.class, X264Preset.DEFAULT_QUALITY, X264Preset::id);

    /** One GiB, the unit the admin page shows byte sizes in. */
    public static final long GIB = 1024L * 1024 * 1024;
    /** The largest byte size a setting takes: 1 PiB (well inside what JavaScript numbers hold exactly). */
    public static final long MAX_BYTES = 1024L * 1024 * GIB;
    /** The most ingest or overlay workers. */
    public static final int MAX_WORKERS = 256;

    /** Ingest worker count; the built-in default is half the cores, the config file's is used instead. */
    public static final SettingKey<Integer> INGEST_WORKERS = SettingKey.integer(
            "ingest.workers", Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 1, MAX_WORKERS);
    /** Upload size limit in bytes (new uploads). Default from the config file. */
    public static final SettingKey<Long> INGEST_MAX_UPLOAD_BYTES = SettingKey.bytes(
            "ingest.maxUploadBytes", 4 * GIB, 1, MAX_BYTES);

    // Storage
    /**
     * Adding media pauses while the data volume has less free space than this
     * ({@code docs/library-and-ingest.md#free-disk-space}); 0 disables the check. At most 1 PiB
     * (well inside what JavaScript numbers hold exactly).
     */
    public static final SettingKey<Long> STORAGE_MIN_FREE_BYTES = SettingKey.bytes(
            "storage.minFreeBytes", 5 * GIB, 0, MAX_BYTES);

    // Media
    /**
     * Hours the previous media version of an item is kept after an edit or re-encode, so a person
     * who may edit the item can undo the change ({@code docs/library-and-ingest.md#undo}); 0 turns
     * undo off. At most a year.
     */
    public static final SettingKey<Integer> MEDIA_UNDO_HOURS = SettingKey.integer("media.undoHours", 48, 0, 8760);

    // Rooms
    /**
     * Days a room's history (entries that played) is kept; 0 means never trim. The key keeps its
     * old name from the request history it replaced, so stored values stay valid.
     */
    public static final SettingKey<Integer> ROOMS_REQUEST_HISTORY_DAYS = SettingKey.integer(
            "rooms.requestHistoryDays", 90, 0, 36_500);

    // Output encoding (docs/library-and-ingest.md#re-encodes): changing either of the first two
    // re-encodes every item; the audio-only stream rate only changes the packager's padding.
    /** The output profile every encode is made for. Default from the config file. */
    public static final SettingKey<OutputProfile> OUTPUT_PROFILE = SettingKey.enumeration(
            "output.profile", OutputProfile.class, OutputProfile.DEFAULT, OutputProfile::id);
    /**
     * Video bitrate cap of every encode in kb/s, also the constant stream rate the packager pads
     * normal rooms' program output to; {@code null} (the built-in default) means the profile's own
     * ({@link OutputEncoding#defaultVideoBitrateKbps}).
     */
    public static final SettingKey<Integer> OUTPUT_VIDEO_BITRATE_KBPS = SettingKey.optionalInteger(
            "output.videoBitrateKbps", OutputEncoding.MIN_VIDEO_BITRATE_KBPS, OutputEncoding.MAX_VIDEO_BITRATE_KBPS);
    /**
     * Constant video stream rate of audio-only rooms' program output in kb/s (cards only, so it may
     * be below the cap; {@code docs/program-output.md#constant-rate-padding}). No re-encode. Default
     * from the config file.
     */
    public static final SettingKey<Integer> OUTPUT_AUDIO_ONLY_BITRATE_KBPS = SettingKey.integer(
            "output.audioOnlyBitrateKbps", OutputEncoding.DEFAULT_AUDIO_ONLY_BITRATE_KBPS,
            OutputEncoding.MIN_VIDEO_BITRATE_KBPS, OutputEncoding.MAX_VIDEO_BITRATE_KBPS);

    // Outputs
    public static final SettingKey<Boolean> OUTPUTS_RTMP_ENABLED = SettingKey.bool("outputs.rtmp.enabled", true);
    public static final SettingKey<Boolean> OUTPUTS_TS_ENABLED = SettingKey.bool("outputs.ts.enabled", true);
    public static final SettingKey<Boolean> OUTPUTS_HLS_ENABLED = SettingKey.bool("outputs.hls.enabled", true);
    /** The MP3 output for internet-radio players ({@code docs/program-output.md#mp3-output}). */
    public static final SettingKey<Boolean> OUTPUTS_MP3_ENABLED = SettingKey.bool("outputs.mp3.enabled", true);
    /** The MP3 output's bitrate in kb/s (CBR, 44.1 kHz stereo); applies to encodes started after a change. */
    public static final SettingKey<Integer> OUTPUTS_MP3_BITRATE_KBPS = SettingKey.integer(
            "outputs.mp3.bitrateKbps", 128, 64, 320);
    /**
     * Every room's outputs turn off automatically, whatever the room's {@code outputs.autoOff}
     * says ({@code docs/program-output.md#auto-turn-off}).
     */
    public static final SettingKey<Boolean> OUTPUTS_FORCE_AUTO_OFF = SettingKey.bool("outputs.forceAutoOff", false);
    /**
     * The longest an output keeps running with an empty queue or without listeners, in minutes: the
     * room's {@code outputs.autoOffMinutes} applies up to this ({@code docs/program-output.md#auto-turn-off}).
     */
    public static final SettingKey<Integer> OUTPUTS_MAX_AUTO_OFF_MINUTES = SettingKey.integer(
            "outputs.maxAutoOffMinutes", 60, 1, 1440);

    // Overlay
    public static final SettingKey<Boolean> OVERLAY_ENABLED = SettingKey.bool("overlay.enabled", false);
    /** Concurrent overlay re-encodes (M5). Default from the config file. */
    public static final SettingKey<Integer> OVERLAY_WORKERS = SettingKey.integer("overlay.workers", 1, 1, MAX_WORKERS);
    /** Size limit of the overlay re-encode cache in bytes (M5); 0 keeps nothing. Default from the config file. */
    public static final SettingKey<Long> OVERLAY_CACHE_MAX_BYTES = SettingKey.bytes(
            "overlay.cacheMaxBytes", 20 * GIB, 0, MAX_BYTES);

    // Card fonts (docs/library-and-ingest.md#card-fonts)
    /**
     * The font card text is drawn with: unset = the bundled DejaVu Sans, {@code system:<family>} an
     * installed font family, {@code upload:<id>} a font file an admin uploaded. Characters it has no
     * glyph for fall back to the bundled font, the downloaded script fonts and then any installed font.
     */
    public static final SettingKey<String> CARDS_FONT = SettingKey.optionalString("cards.font");
    /** Download a missing script font when a card needs it. */
    public static final SettingKey<Boolean> FONTS_AUTO_DOWNLOAD = SettingKey.bool("fonts.autoDownload", true);
    /** The CJK font variant for Han text without kana, hangul or bopomofo. */
    public static final SettingKey<CjkVariant> FONTS_CJK_VARIANT = SettingKey.enumeration(
            "fonts.cjkVariant", CjkVariant.class, CjkVariant.JP, CjkVariant::wireName);

    /** Every setting, in documentation order, by name. */
    public static final Map<String, SettingKey<?>> KEYS;

    /**
     * Uploads need library requests ({@code docs/auth.md#server-settings}): an upload switch (key)
     * is only on while its library-request switch (value) is. The admin API makes the companion
     * change when a patch would break this; {@link #all()} and {@link #guestsCanUpload()} read a
     * stored state that breaks it as upload off.
     */
    public static final Map<SettingKey<Boolean>, SettingKey<Boolean>> COUPLED =
            Map.of(GUESTS_CAN_UPLOAD, GUESTS_CAN_REQUEST_LIBRARY);

    static {
        Map<String, SettingKey<?>> keys = new LinkedHashMap<>();
        for (SettingKey<?> k : List.of(REGISTRATION_MODE,
                GUESTS_ENABLED, GUESTS_IDENTIFY_BY, GUESTS_ALLOW_CUSTOM_NAMES, GUESTS_NAME_BLOCKLIST, GUESTS_CAN_VOTE,
                GUESTS_CAN_REQUEST_LIBRARY, GUESTS_CAN_UPLOAD, GUESTS_CAN_YOUTUBE, GUESTS_UPLOAD_QUOTA,
                GUESTS_DOWNLOAD,
                YTDLP_ENABLED, YTDLP_COOKIES_FILE, RETENTION_UNREFERENCED_DAYS, INGEST_MAX_DURATION_SECONDS,
                INGEST_QUICK_PRESET, INGEST_QUALITY_PRESET, INGEST_WORKERS, INGEST_MAX_UPLOAD_BYTES,
                STORAGE_MIN_FREE_BYTES, MEDIA_UNDO_HOURS,
                ROOMS_REQUEST_HISTORY_DAYS,
                OUTPUT_PROFILE, OUTPUT_VIDEO_BITRATE_KBPS, OUTPUT_AUDIO_ONLY_BITRATE_KBPS,
                OUTPUTS_FORCE_AUTO_OFF, OUTPUTS_MAX_AUTO_OFF_MINUTES,
                OUTPUTS_RTMP_ENABLED, OUTPUTS_TS_ENABLED, OUTPUTS_HLS_ENABLED,
                OUTPUTS_MP3_ENABLED, OUTPUTS_MP3_BITRATE_KBPS,
                OVERLAY_ENABLED, OVERLAY_WORKERS, OVERLAY_CACHE_MAX_BYTES,
                CARDS_FONT, FONTS_AUTO_DOWNLOAD, FONTS_CJK_VARIANT)) {
            if (keys.put(k.name(), k) != null) {
                throw new ExceptionInInitializerError("duplicate setting " + k.name());
            }
        }
        KEYS = Collections.unmodifiableMap(keys);
    }

    private final Database db;
    private final Map<SettingKey<?>, Object> defaults;

    /** Settings with the built-in defaults. */
    public Settings(Database db) {
        this(db, Map.of());
    }

    /**
     * Settings whose defaults for the config keys that are also admin settings come from the config
     * file ({@code docs/architecture.md#configuration}): {@code output.profile},
     * {@code output.videoBitrateKbps}, {@code output.audioOnlyBitrateKbps}, {@code ingest.workers}, {@code ingest.maxUploadBytes},
     * {@code ingest.maxDurationSeconds}, {@code ingest.quickPreset}, {@code ingest.qualityPreset},
     * {@code overlay.workers} and {@code overlay.cacheMaxBytes}. The admin UI can still override them.
     * A configured bitrate equal to its profile's default counts as "the profile's default"
     * ({@code null}), so an admin who switches the profile gets that profile's default bitrate.
     * Sizes above {@link #MAX_BYTES} are capped to it.
     */
    public Settings(Database db, Config config) {
        this(db, configDefaults(config));
    }

    private static Map<SettingKey<?>, Object> configDefaults(Config config) {
        Map<SettingKey<?>, Object> d = new HashMap<>();
        d.put(OUTPUT_PROFILE, config.outputProfile());
        int bitrate = config.outputVideoBitrateKbps();
        d.put(OUTPUT_VIDEO_BITRATE_KBPS,
                bitrate == OutputEncoding.defaultVideoBitrateKbps(config.outputProfile()) ? null : bitrate);
        d.put(OUTPUT_AUDIO_ONLY_BITRATE_KBPS, config.outputAudioOnlyBitrateKbps());
        d.put(INGEST_WORKERS, Math.min(config.ingestWorkers(), MAX_WORKERS));
        d.put(INGEST_MAX_UPLOAD_BYTES, Math.min(config.ingestMaxUploadBytes(), MAX_BYTES));
        d.put(INGEST_MAX_DURATION_SECONDS, config.ingestMaxDurationSeconds());
        d.put(INGEST_QUICK_PRESET, config.ingestQuickPreset());
        d.put(INGEST_QUALITY_PRESET, config.ingestQualityPreset());
        d.put(OVERLAY_WORKERS, Math.min(config.overlayWorkers(), MAX_WORKERS));
        d.put(OVERLAY_CACHE_MAX_BYTES, Math.min(config.overlayCacheMaxBytes(), MAX_BYTES));
        return d;
    }

    private Settings(Database db, Map<SettingKey<?>, Object> defaultOverrides) {
        this.db = Objects.requireNonNull(db);
        Map<SettingKey<?>, Object> d = new HashMap<>();
        for (SettingKey<?> k : KEYS.values()) {
            d.put(k, k.defaultValue());
        }
        defaultOverrides.forEach((k, v) -> d.put(k, validated(k, v)));
        this.defaults = d;
    }

    private static <T> T validated(SettingKey<T> key, Object value) {
        if (value == null && key.nullable()) {
            return null;
        }
        T typed = key.type().cast(value);
        return key.decode(key.encode(typed));
    }

    /** The effective default of {@code key} on this server. */
    public <T> T defaultValue(SettingKey<T> key) {
        return key.type().cast(defaults.get(known(key)));
    }

    // ------------------------------------------------------------- generic access

    /** The current value of {@code key}: the stored value, or the default. */
    public <T> T get(SettingKey<T> key) throws SQLException {
        known(key);
        String json = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value_json FROM settings WHERE key = ?")) {
                ps.setString(1, key.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        return json == null ? defaultValue(key) : parseStored(key, json);
    }

    /**
     * Stores {@code value} for {@code key}. {@code null} is allowed only for nullable settings.
     *
     * @throws IllegalArgumentException if the value is invalid
     */
    public <T> void set(SettingKey<T> key, T value) throws SQLException {
        known(key);
        String json = key.encode(value).toString();
        db.write(c -> {
            String sql = db.dialect().upsert("settings", List.of("key", "value_json"), List.of("key"),
                    List.of("value_json"));
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, key.name());
                ps.setString(2, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes the stored value, so {@code key} goes back to its default. */
    public void reset(SettingKey<?> key) throws SQLException {
        known(key);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM settings WHERE key = ?")) {
                ps.setString(1, key.name());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Every setting's current value, in {@link #KEYS} order, read in one query. */
    public Map<SettingKey<?>, Object> all() throws SQLException {
        Map<String, String> stored = db.read(c -> {
            Map<String, String> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT key, value_json FROM settings");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getString(2));
                }
            }
            return rows;
        });
        Map<SettingKey<?>, Object> out = new LinkedHashMap<>();
        for (SettingKey<?> k : KEYS.values()) {
            String json = stored.get(k.name());
            out.put(k, json == null ? defaults.get(k) : parseStored(k, json));
        }
        for (Map.Entry<SettingKey<Boolean>, SettingKey<Boolean>> c : COUPLED.entrySet()) {
            if (Boolean.TRUE.equals(out.get(c.getKey())) && !Boolean.TRUE.equals(out.get(c.getValue()))) {
                out.put(c.getKey(), false); // stored state breaking the rule reads as upload off
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Sets a setting by name from JSON text (for the admin API).
     *
     * @throws IllegalArgumentException for an unknown name, malformed JSON or an invalid value
     */
    public void setJson(String name, String json) throws SQLException {
        SettingKey<?> key = KEYS.get(name);
        if (key == null) {
            throw new IllegalArgumentException("unknown setting: " + name);
        }
        setDecoded(key, parseJson(json));
    }

    private <T> void setDecoded(SettingKey<T> key, JsonElement json) throws SQLException {
        set(key, key.decode(json));
    }

    private <T> T parseStored(SettingKey<T> key, String json) {
        try {
            return key.decode(parseJson(json));
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring stored setting " + key.name() + " ("
                    + e.getMessage() + "); using the default");
            return key.type().cast(defaults.get(key));
        }
    }

    /** Parses one complete, strict JSON value. */
    private static JsonElement parseJson(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trailing data after the JSON value");
            }
            return element;
        } catch (JsonParseException | IOException | IllegalStateException e) {
            throw new IllegalArgumentException("malformed JSON: " + e.getMessage(), e);
        }
    }

    private static <K extends SettingKey<?>> K known(K key) {
        if (KEYS.get(key.name()) != key) {
            throw new IllegalArgumentException("unknown setting: " + key.name());
        }
        return key;
    }

    // ------------------------------------------------------------- typed accessors

    public RegistrationMode registrationMode() throws SQLException {
        return get(REGISTRATION_MODE);
    }

    public boolean guestsEnabled() throws SQLException {
        return get(GUESTS_ENABLED);
    }

    public GuestIdentity guestsIdentifyBy() throws SQLException {
        return get(GUESTS_IDENTIFY_BY);
    }

    public boolean guestsAllowCustomNames() throws SQLException {
        return get(GUESTS_ALLOW_CUSTOM_NAMES);
    }

    /** The admin blocklist for custom guest names (raw entries, possibly empty). */
    public List<String> guestsNameBlocklist() throws SQLException {
        return get(GUESTS_NAME_BLOCKLIST).items();
    }

    public boolean guestsCanVote() throws SQLException {
        return get(GUESTS_CAN_VOTE);
    }

    public boolean guestsCanRequestLibrary() throws SQLException {
        return get(GUESTS_CAN_REQUEST_LIBRARY);
    }

    /**
     * The server half of {@code guests.canUpload}: only ever on while
     * {@code guests.canRequestLibrary} is (uploads need library requests, {@link #COUPLED}); a
     * stored state breaking that reads as off.
     */
    public boolean guestsCanUpload() throws SQLException {
        return get(GUESTS_CAN_UPLOAD) && get(GUESTS_CAN_REQUEST_LIBRARY);
    }

    public boolean guestsCanYoutube() throws SQLException {
        return get(GUESTS_CAN_YOUTUBE);
    }

    public UploadQuota guestsUploadQuota() throws SQLException {
        return get(GUESTS_UPLOAD_QUOTA);
    }

    public boolean guestsDownload() throws SQLException {
        return get(GUESTS_DOWNLOAD);
    }

    public boolean ytdlpEnabled() throws SQLException {
        return get(YTDLP_ENABLED);
    }

    /** The yt-dlp cookies file, or {@code null} for none. */
    public String ytdlpCookiesFile() throws SQLException {
        return get(YTDLP_COOKIES_FILE);
    }

    /** Days an unreferenced item is kept; 0 means never delete. */
    public int retentionUnreferencedDays() throws SQLException {
        return get(RETENTION_UNREFERENCED_DAYS);
    }

    /** Days a room's history is kept ({@link #ROOMS_REQUEST_HISTORY_DAYS}); 0 means never trim. */
    public int roomsRequestHistoryDays() throws SQLException {
        return get(ROOMS_REQUEST_HISTORY_DAYS);
    }

    public int ingestMaxDurationSeconds() throws SQLException {
        return get(INGEST_MAX_DURATION_SECONDS);
    }

    public X264Preset ingestQuickPreset() throws SQLException {
        return get(INGEST_QUICK_PRESET);
    }

    public X264Preset ingestQualityPreset() throws SQLException {
        return get(INGEST_QUALITY_PRESET);
    }

    /** Number of ingest workers. */
    public int ingestWorkers() throws SQLException {
        return get(INGEST_WORKERS);
    }

    /** Upload size limit in bytes. */
    public long ingestMaxUploadBytes() throws SQLException {
        return get(INGEST_MAX_UPLOAD_BYTES);
    }

    public OutputProfile outputProfile() throws SQLException {
        return get(OUTPUT_PROFILE);
    }

    /** The {@code output.videoBitrateKbps} setting, or null for the profile's default. */
    public Integer outputVideoBitrateKbps() throws SQLException {
        return get(OUTPUT_VIDEO_BITRATE_KBPS);
    }

    /** Constant stream rate of audio-only rooms' program output in kb/s. */
    public int outputAudioOnlyBitrateKbps() throws SQLException {
        return get(OUTPUT_AUDIO_ONLY_BITRATE_KBPS);
    }

    /** The output encoding every encode is made for: {@code output.profile} at its effective bitrate. */
    public OutputEncoding outputEncoding() throws SQLException {
        Map<SettingKey<?>, Object> all = all();
        return encoding((OutputProfile) all.get(OUTPUT_PROFILE), (Integer) all.get(OUTPUT_VIDEO_BITRATE_KBPS));
    }

    /** {@code profile} at {@code videoBitrateKbps}, or at the profile's default bitrate if that is null. */
    public static OutputEncoding encoding(OutputProfile profile, Integer videoBitrateKbps) {
        return videoBitrateKbps == null ? OutputEncoding.defaultFor(profile)
                : new OutputEncoding(profile, videoBitrateKbps);
    }

    public int overlayWorkers() throws SQLException {
        return get(OVERLAY_WORKERS);
    }

    public long overlayCacheMaxBytes() throws SQLException {
        return get(OVERLAY_CACHE_MAX_BYTES);
    }

    /** The minimum free space on the data volume in bytes; 0 means no minimum. */
    public long storageMinFreeBytes() throws SQLException {
        return get(STORAGE_MIN_FREE_BYTES);
    }

    /** Hours a previous media version is kept for undo ({@link #MEDIA_UNDO_HOURS}); 0 = no undo. */
    public int mediaUndoHours() throws SQLException {
        return get(MEDIA_UNDO_HOURS);
    }

    public boolean outputsRtmpEnabled() throws SQLException {
        return get(OUTPUTS_RTMP_ENABLED);
    }

    public boolean outputsForceAutoOff() throws SQLException {
        return get(OUTPUTS_FORCE_AUTO_OFF);
    }

    public int outputsMaxAutoOffMinutes() throws SQLException {
        return get(OUTPUTS_MAX_AUTO_OFF_MINUTES);
    }

    public boolean outputsTsEnabled() throws SQLException {
        return get(OUTPUTS_TS_ENABLED);
    }

    public boolean outputsHlsEnabled() throws SQLException {
        return get(OUTPUTS_HLS_ENABLED);
    }

    public boolean outputsMp3Enabled() throws SQLException {
        return get(OUTPUTS_MP3_ENABLED);
    }

    public int outputsMp3BitrateKbps() throws SQLException {
        return get(OUTPUTS_MP3_BITRATE_KBPS);
    }

    public boolean overlayEnabled() throws SQLException {
        return get(OVERLAY_ENABLED);
    }

    /** {@code cards.font}: null for the bundled font. */
    public String cardsFont() throws SQLException {
        return get(CARDS_FONT);
    }

    public boolean fontsAutoDownload() throws SQLException {
        return get(FONTS_AUTO_DOWNLOAD);
    }

    public CjkVariant fontsCjkVariant() throws SQLException {
        return get(FONTS_CJK_VARIANT);
    }
}
