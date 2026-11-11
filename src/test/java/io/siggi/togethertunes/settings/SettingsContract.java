package io.siggi.togethertunes.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Settings} against a migrated database, in both dialects. */
abstract class SettingsContract {
    protected Database db;
    protected Settings settings;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        settings = new Settings(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private void putRaw(String key, String json) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (key, value_json) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String raw(String key) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value_json FROM settings WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Test
    void emptyTableGivesDocumentedDefaults() throws SQLException {
        assertEquals(RegistrationMode.CLOSED, settings.registrationMode());
        assertTrue(settings.guestsEnabled());
        assertEquals(GuestIdentity.COOKIE, settings.guestsIdentifyBy());
        assertFalse(settings.guestsAllowCustomNames());
        assertTrue(settings.guestsCanVote());
        assertTrue(settings.guestsCanRequestLibrary());
        assertFalse(settings.guestsCanUpload());
        assertFalse(settings.guestsCanYoutube());
        assertEquals(new UploadQuota(3, 500L * 1024 * 1024), settings.guestsUploadQuota());
        assertFalse(settings.guestsDownload());
        assertTrue(settings.ytdlpEnabled());
        assertNull(settings.ytdlpCookiesFile());
        assertEquals(14, settings.retentionUnreferencedDays());
        assertEquals(1800, settings.ingestMaxDurationSeconds());
        assertEquals(90, settings.roomsRequestHistoryDays());
        assertEquals(5L * 1024 * 1024 * 1024, settings.storageMinFreeBytes());
        assertTrue(settings.outputsRtmpEnabled());
        assertEquals(60, settings.outputsMaxAutoOffMinutes());
        assertFalse(settings.outputsForceAutoOff());
        assertTrue(settings.outputsTsEnabled());
        assertTrue(settings.outputsHlsEnabled());
        assertFalse(settings.overlayEnabled());
        assertEquals(OutputProfile.P720_30, settings.outputProfile());
        assertNull(settings.outputVideoBitrateKbps());
        assertEquals("720p30-3000k-v3", settings.outputEncoding().id());
        assertEquals(4L * 1024 * 1024 * 1024, settings.ingestMaxUploadBytes());
        assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), settings.ingestWorkers());
        assertEquals(1, settings.overlayWorkers());
        assertEquals(20L * 1024 * 1024 * 1024, settings.overlayCacheMaxBytes());
        assertFalse(settings.overlayAllowQualityPreset());
        // card fonts (docs/library-and-ingest.md#card-fonts): the bundled font, auto-download on, Japanese
        assertEquals(null, settings.cardsFont());
        assertTrue(settings.fontsAutoDownload());
        assertEquals(CjkVariant.JP, settings.fontsCjkVariant());
    }

    @Test
    void allListsEveryKeyInOrder() throws SQLException {
        Map<SettingKey<?>, Object> all = settings.all();
        assertEquals(List.copyOf(Settings.KEYS.values()), List.copyOf(all.keySet()));
        assertEquals(39, all.size());
        settings.set(Settings.OVERLAY_ENABLED, true);
        assertEquals(true, settings.all().get(Settings.OVERLAY_ENABLED));
        assertEquals(14, settings.all().get(Settings.RETENTION_UNREFERENCED_DAYS));
    }

    @Test
    void setGetResetRoundTrip() throws SQLException {
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.INVITE);
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(0, 1));
        settings.set(Settings.YTDLP_COOKIES_FILE, "/srv/cookies.txt");
        settings.set(Settings.RETENTION_UNREFERENCED_DAYS, 0);
        settings.set(Settings.OUTPUTS_HLS_ENABLED, false);
        settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 5);
        assertEquals(5, settings.outputsMaxAutoOffMinutes());
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 0));
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 1441));
        settings.set(Settings.OUTPUTS_FORCE_AUTO_OFF, true);
        assertTrue(settings.outputsForceAutoOff());
        assertTrue(settings.outputsMp3Enabled());
        assertEquals(128, settings.outputsMp3BitrateKbps());
        settings.set(Settings.OUTPUTS_MP3_ENABLED, false);
        settings.set(Settings.OUTPUTS_MP3_BITRATE_KBPS, 320);
        assertFalse(settings.outputsMp3Enabled());
        assertEquals(320, settings.outputsMp3BitrateKbps());
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.OUTPUTS_MP3_BITRATE_KBPS, 63));
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.OUTPUTS_MP3_BITRATE_KBPS, 321));

        assertEquals(RegistrationMode.INVITE, settings.registrationMode());
        assertEquals(GuestIdentity.IP, settings.guestsIdentifyBy());
        assertEquals(new UploadQuota(0, 1), settings.guestsUploadQuota());
        assertEquals("/srv/cookies.txt", settings.ytdlpCookiesFile());
        assertEquals(0, settings.retentionUnreferencedDays());
        assertFalse(settings.outputsHlsEnabled());

        // Stored as JSON, enums by their wire name.
        assertEquals("\"invite\"", raw("registration.mode"));
        assertEquals("\"ip\"", raw("guests.identifyBy"));
        assertEquals("{\"perHour\":0,\"maxItemBytes\":1}", raw("guests.uploadQuota"));
        assertEquals("false", raw("outputs.hls.enabled"));

        // Overwrite (upsert).
        settings.set(Settings.REGISTRATION_MODE, RegistrationMode.OPEN);
        assertEquals(RegistrationMode.OPEN, settings.registrationMode());

        settings.reset(Settings.REGISTRATION_MODE);
        assertNull(raw("registration.mode"));
        assertEquals(RegistrationMode.CLOSED, settings.registrationMode());

        settings.set(Settings.YTDLP_COOKIES_FILE, null);
        assertNull(settings.ytdlpCookiesFile());
        settings.set(Settings.YTDLP_COOKIES_FILE, "");
        assertNull(settings.ytdlpCookiesFile());
    }

    @Test
    void invalidValuesAreRejectedOnSet() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.RETENTION_UNREFERENCED_DAYS, -1));
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.INGEST_MAX_DURATION_SECONDS, 0));
        assertThrows(IllegalArgumentException.class, () -> settings.set(Settings.GUESTS_ENABLED, null));
        assertThrows(IllegalArgumentException.class, () -> new UploadQuota(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new UploadQuota(1, 0));
        assertEquals(14, settings.retentionUnreferencedDays());
        assertNull(raw("retention.unreferencedDays"));
    }

    @Test
    void setJsonValidatesNameAndValue() throws SQLException {
        settings.setJson("guests.canUpload", "true");
        assertTrue(settings.guestsCanUpload());
        settings.setJson("guests.uploadQuota", "{\"perHour\": 5, \"maxItemBytes\": 1048576}");
        assertEquals(new UploadQuota(5, 1048576), settings.guestsUploadQuota());
        settings.setJson("registration.mode", "\"open\"");
        assertEquals(RegistrationMode.OPEN, settings.registrationMode());

        assertThrows(IllegalArgumentException.class, () -> settings.setJson("no.such", "true"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.canUpload", "\"yes\""));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.canUpload", "1"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.canUpload", "tru"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.canUpload", "true false"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.canUpload", "null"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("registration.mode", "\"OPEN\""));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("retention.unreferencedDays", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("retention.unreferencedDays", "\"3\""));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setJson("guests.uploadQuota", "{\"perHour\": 5}"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setJson("guests.uploadQuota", "{\"perHour\": 5, \"maxItemBytes\": 1, \"x\": 1}"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setJson("guests.uploadQuota", "{\"perHour\": -1, \"maxItemBytes\": 1}"));
        assertTrue(settings.guestsCanUpload());

        assertEquals(List.of(), settings.guestsNameBlocklist());
        settings.setJson("guests.nameBlocklist", "[\"  a\\tb  \", \"\", \" \", \"c\", \"a b\"]");
        assertEquals(List.of("a b", "c"), settings.guestsNameBlocklist());
        assertEquals("[\"a b\",\"c\"]", raw("guests.nameBlocklist"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.nameBlocklist", "\"a\""));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.nameBlocklist", "[1]"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("guests.nameBlocklist", "[[\"a\"]]"));
        assertThrows(IllegalArgumentException.class,
                () -> settings.setJson("guests.nameBlocklist", "[\"" + "x".repeat(65) + "\"]"));
        settings.setJson("guests.nameBlocklist", "[]");
        assertEquals(List.of(), settings.guestsNameBlocklist());

        settings.setJson("ytdlp.cookiesFile", "null");
        assertNull(settings.ytdlpCookiesFile());
    }

    @Test
    void storageMinFreeBytesTakesWholeBytesFromZero() throws SQLException {
        settings.setJson("storage.minFreeBytes", "0");
        assertEquals(0, settings.storageMinFreeBytes());
        settings.setJson("storage.minFreeBytes", "21474836480"); // 20 GiB, beyond an int
        assertEquals(20L * Settings.GIB, settings.storageMinFreeBytes());
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("storage.minFreeBytes", "-1"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("storage.minFreeBytes", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> settings.setJson("storage.minFreeBytes", "\"5\""));
        assertEquals(20L * Settings.GIB, settings.storageMinFreeBytes());
    }

    @Test
    void badOrUnknownStoredRowsFallBackToDefaults() throws SQLException {
        putRaw("guests.enabled", "not json");
        putRaw("retention.unreferencedDays", "-5");
        putRaw("registration.mode", "\"sometimes\"");
        putRaw("some.removed.setting", "42");
        assertTrue(settings.guestsEnabled());
        assertEquals(14, settings.retentionUnreferencedDays());
        assertEquals(RegistrationMode.CLOSED, settings.registrationMode());
        assertEquals(39, settings.all().size());
        // A good value written later replaces the bad row.
        settings.set(Settings.GUESTS_ENABLED, false);
        assertFalse(settings.guestsEnabled());
    }

    @Test
    void setJoinsAnOuterTransaction() throws SQLException {
        assertThrows(IllegalStateException.class, () -> db.write(c -> {
            settings.set(Settings.OVERLAY_ENABLED, true);
            assertTrue(settings.overlayEnabled());
            throw new IllegalStateException("roll back");
        }));
        assertFalse(settings.overlayEnabled());
    }

    @Test
    void configSuppliesTheMaxDurationAndPresetDefaults() throws SQLException {
        Config config = new Config("0.0.0.0", 8080, "https://example.test", List.of(), Path.of("/tmp/data"),
                "jdbc:sqlite::memory:", "ffmpeg", "ffprobe", "yt-dlp", OutputProfile.values()[0], 3000, 800, 1,
                1L << 32, 600, X264Preset.FASTER, X264Preset.SLOWER, 1, 1L << 34, null);
        Settings withConfig = new Settings(db, config);
        assertEquals(X264Preset.FASTER, withConfig.ingestQuickPreset());
        assertEquals(X264Preset.SLOWER, withConfig.ingestQualityPreset());
        assertEquals(X264Preset.VERYFAST, settings.ingestQuickPreset(), "built-in default");
        assertEquals(X264Preset.SLOW, settings.ingestQualityPreset(), "built-in default");
        withConfig.setJson("ingest.qualityPreset", "\"veryslow\"");
        assertEquals(X264Preset.VERYSLOW, withConfig.ingestQualityPreset());
        assertThrows(IllegalArgumentException.class, () -> withConfig.setJson("ingest.quickPreset", "\"ultrafast\""));
        assertEquals(600, withConfig.ingestMaxDurationSeconds());
        assertEquals(600, withConfig.defaultValue(Settings.INGEST_MAX_DURATION_SECONDS));
        withConfig.set(Settings.INGEST_MAX_DURATION_SECONDS, 3600);
        assertEquals(3600, withConfig.ingestMaxDurationSeconds());
        withConfig.reset(Settings.INGEST_MAX_DURATION_SECONDS);
        assertEquals(600, withConfig.ingestMaxDurationSeconds());
    }

    /**
     * The config keys that became admin settings (output.profile, output.videoBitrateKbps,
     * output.audioOnlyBitrateKbps,
     * ingest.workers, ingest.maxUploadBytes, overlay.workers, overlay.cacheMaxBytes): each takes
     * its default from the config, round-trips, overrides that default and resets back to it.
     */
    @Test
    void configKeysThatAreSettingsRoundTripAndOverrideTheirConfigDefault() throws SQLException {
        Config config = new Config("0.0.0.0", 8080, "https://example.test", List.of(), Path.of("/tmp/data"),
                "jdbc:sqlite::memory:", "ffmpeg", "ffprobe", "yt-dlp", OutputProfile.P1080_30, 6000, 1200, 3,
                2 * Settings.GIB, 600, X264Preset.FASTER, X264Preset.SLOWER, 2, 7 * Settings.GIB, null);
        Settings s = new Settings(db, config);
        assertEquals(OutputProfile.P1080_30, s.outputProfile());
        assertNull(s.outputVideoBitrateKbps(), "the profile's default bitrate counts as unset");
        assertEquals("1080p30-6000k-v3", s.outputEncoding().id());
        assertEquals(1200, s.outputAudioOnlyBitrateKbps());
        assertEquals(800, settings.outputAudioOnlyBitrateKbps(), "built-in default");
        assertEquals(3, s.ingestWorkers());
        assertEquals(2 * Settings.GIB, s.ingestMaxUploadBytes());
        assertEquals(2, s.overlayWorkers());
        assertEquals(7 * Settings.GIB, s.overlayCacheMaxBytes());

        // Switching the profile alone takes that profile's default bitrate.
        s.setJson("output.profile", "\"720p30\"");
        assertEquals("720p30-3000k-v3", s.outputEncoding().id());
        s.setJson("output.videoBitrateKbps", "2500");
        assertEquals(2500, s.outputVideoBitrateKbps());
        assertEquals("720p30-2500k-v3", s.outputEncoding().id());
        // the audio-only stream rate may be below the cap (cards only); it re-encodes nothing
        s.setJson("output.audioOnlyBitrateKbps", "600");
        assertEquals(600, s.outputAudioOnlyBitrateKbps());
        assertEquals("720p30-2500k-v3", s.outputEncoding().id());
        s.setJson("ingest.workers", "8");
        s.setJson("ingest.maxUploadBytes", String.valueOf(10 * Settings.GIB));
        s.setJson("overlay.workers", "4");
        s.setJson("overlay.cacheMaxBytes", "0");
        assertEquals(8, s.ingestWorkers());
        assertEquals(10 * Settings.GIB, s.ingestMaxUploadBytes());
        assertEquals(4, s.overlayWorkers());
        assertEquals(0, s.overlayCacheMaxBytes());
        assertEquals("720p30", raw("output.profile").replace("\"", ""));

        for (String key : List.of("output.profile", "output.videoBitrateKbps", "output.audioOnlyBitrateKbps",
                "ingest.workers",
                "ingest.maxUploadBytes", "overlay.workers", "overlay.cacheMaxBytes")) {
            s.reset(Settings.KEYS.get(key));
        }
        assertEquals("1080p30-6000k-v3", s.outputEncoding().id());
        assertEquals(1200, s.outputAudioOnlyBitrateKbps());
        assertEquals(3, s.ingestWorkers());
        assertEquals(2 * Settings.GIB, s.ingestMaxUploadBytes());
        assertEquals(2, s.overlayWorkers());
        assertEquals(7 * Settings.GIB, s.overlayCacheMaxBytes());

        // A configured bitrate other than the profile's default is the setting's default.
        Config custom = new Config("0.0.0.0", 8080, "https://example.test", List.of(), Path.of("/tmp/data"),
                "jdbc:sqlite::memory:", "ffmpeg", "ffprobe", "yt-dlp", OutputProfile.P720_30, 4000, 800, 1,
                1L << 62, 600, X264Preset.FASTER, X264Preset.SLOWER, 1, 1L << 62, null);
        Settings c = new Settings(db, custom);
        assertEquals(4000, c.outputVideoBitrateKbps());
        assertEquals(4000, c.defaultValue(Settings.OUTPUT_VIDEO_BITRATE_KBPS));
        assertEquals(Settings.MAX_BYTES, c.ingestMaxUploadBytes(), "sizes above 1 PiB are capped");
        assertEquals(Settings.MAX_BYTES, c.overlayCacheMaxBytes());
        // A stored null means the profile's default, even where the config gave a bitrate.
        c.set(Settings.OUTPUT_VIDEO_BITRATE_KBPS, null);
        assertNull(c.outputVideoBitrateKbps());
        assertEquals("720p30-3000k-v3", c.outputEncoding().id());
    }

    @Test
    void newSettingsValidateLikeTheConfig() {
        for (String[] bad : new String[][] {
                {"output.profile", "\"480p25\""}, {"output.profile", "720"},
                {"output.videoBitrateKbps", "499"}, {"output.videoBitrateKbps", "25001"},
                {"output.videoBitrateKbps", "3000.5"}, {"output.videoBitrateKbps", "\"3000\""},
                {"output.audioOnlyBitrateKbps", "499"}, {"output.audioOnlyBitrateKbps", "25001"},
                {"output.audioOnlyBitrateKbps", "null"},
                {"ingest.workers", "0"}, {"ingest.workers", "257"},
                {"ingest.maxUploadBytes", "0"}, {"ingest.maxUploadBytes", String.valueOf(Settings.MAX_BYTES + 1)},
                {"overlay.workers", "0"}, {"overlay.cacheMaxBytes", "-1"}}) {
            assertThrows(IllegalArgumentException.class, () -> settings.setJson(bad[0], bad[1]),
                    bad[0] + " = " + bad[1]);
        }
    }
}
