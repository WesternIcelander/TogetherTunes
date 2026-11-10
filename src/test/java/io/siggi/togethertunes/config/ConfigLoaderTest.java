package io.siggi.togethertunes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    private static final String URL_ONLY = "{\"http\": {\"publicBaseUrl\": \"https://music.example.com\"}}";

    @TempDir
    Path dir;
    Path bin;
    Map<String, String> env;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectory(dir.resolve("bin"));
        for (String tool : List.of("ffmpeg", "ffprobe", "yt-dlp")) {
            executable(bin.resolve(tool));
        }
        env = new HashMap<>();
        env.put("PATH", bin.toString());
    }

    private static Path executable(Path p) throws IOException {
        Files.writeString(p, "#!/bin/sh\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    private ConfigLoader loader() {
        return new ConfigLoader(env, 8, dir);
    }

    private Config load(String json) throws ConfigException {
        return loader().loadFromJson(null, json).config();
    }

    private ConfigException fails(String json) {
        return assertThrows(ConfigException.class, () -> load(json));
    }

    private static void assertProblem(ConfigException e, String fragment) {
        assertTrue(e.problems().stream().anyMatch(p -> p.contains(fragment)),
                "expected a problem containing '" + fragment + "', got " + e.problems());
    }

    @Test
    void defaults() throws ConfigException {
        Config c = load(URL_ONLY);
        assertEquals("0.0.0.0", c.httpBind());
        assertEquals(8080, c.httpPort());
        assertEquals("https://music.example.com", c.publicBaseUrl());
        assertEquals(List.of(), c.trustedProxies());
        assertEquals(dir.resolve("data"), c.dataDir());
        assertEquals("jdbc:sqlite:" + dir.resolve("data/db/media.db"), c.dbUrl());
        assertFalse(c.isPostgres());
        assertEquals(bin.resolve("ffmpeg").toString(), c.ffmpeg());
        assertEquals(bin.resolve("ffprobe").toString(), c.ffprobe());
        assertEquals(bin.resolve("yt-dlp").toString(), c.ytdlp());
        assertEquals(OutputProfile.P720_30, c.outputProfile());
        assertEquals(3000, c.outputVideoBitrateKbps());
        assertEquals("720p30-3000k-v3", c.outputEncoding().id());
        assertEquals(800, c.outputAudioOnlyBitrateKbps());
        assertEquals(X264Preset.VERYFAST, c.ingestQuickPreset());
        assertEquals(X264Preset.SLOW, c.ingestQualityPreset());
        assertEquals(4, c.ingestWorkers());
        assertEquals(4L << 30, c.ingestMaxUploadBytes());
        assertEquals(1800, c.ingestMaxDurationSeconds());
        assertEquals(1, c.overlayWorkers());
        assertEquals(20L << 30, c.overlayCacheMaxBytes());
    }

    @Test
    void ingestWorkersDefaultIsAtLeastOne() throws ConfigException {
        Config c = new ConfigLoader(env, 1, dir).loadFromJson(null, URL_ONLY).config();
        assertEquals(1, c.ingestWorkers());
    }

    @Test
    void everyKeyFromTheFile() throws IOException, ConfigException {
        Path ff = executable(dir.resolve("my-ffmpeg"));
        String json = """
                {
                  "http": {
                    "bind": "127.0.0.1",
                    "port": 9000,
                    "publicBaseUrl": "https://Music.Example.com:8443/tt/",
                    "trustedProxies": ["10.0.0.0/8", "192.168.1.1", "2001:db8::/32"]
                  },
                  "data": {"dir": "srv"},
                  "db": {"url": "jdbc:postgresql://localhost/tt"},
                  "tools": {"ffmpeg": "./my-ffmpeg", "ffprobe": "ffprobe", "ytdlp": "yt-dlp"},
                  "output.profile": "1080p30",
                  "ingest": {"workers": 3, "maxUploadBytes": "512 MiB", "maxDurationSeconds": "600"},
                  "overlay": {"workers": 2, "cacheMaxBytes": 1048576}
                }
                """;
        Config c = load(json);
        assertEquals("127.0.0.1", c.httpBind());
        assertEquals(9000, c.httpPort());
        assertEquals("https://music.example.com:8443/tt", c.publicBaseUrl());
        assertEquals(List.of("10.0.0.0/8", "192.168.1.1/32", "2001:db8:0:0:0:0:0:0/32"), c.trustedProxies());
        assertEquals(dir.resolve("srv"), c.dataDir());
        assertEquals("jdbc:postgresql://localhost/tt", c.dbUrl());
        assertTrue(c.isPostgres());
        assertEquals(ff.toString(), c.ffmpeg());
        assertEquals(OutputProfile.P1080_30, c.outputProfile());
        assertEquals(1920, c.outputProfile().width());
        assertEquals(6000, c.outputVideoBitrateKbps(), "the default follows the profile");
        assertEquals(3, c.ingestWorkers());
        assertEquals(512L << 20, c.ingestMaxUploadBytes());
        assertEquals(600, c.ingestMaxDurationSeconds());
        assertEquals(2, c.overlayWorkers());
        assertEquals(1048576L, c.overlayCacheMaxBytes());
    }

    @Test
    void dbUrlDefaultFollowsDataDir() throws ConfigException {
        Config c = load("{\"http.publicBaseUrl\": \"http://localhost:8080\", \"data.dir\": \"/var/lib/tt\"}");
        assertEquals(Path.of("/var/lib/tt"), c.dataDir());
        assertEquals("jdbc:sqlite:/var/lib/tt/db/media.db", c.dbUrl());
    }

    @Test
    void environmentOverridesTheFile() throws ConfigException {
        env.put("TOGETHERTUNES_HTTP_PORT", "9999");
        env.put("TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://other.example.org");
        env.put("TOGETHERTUNES_OUTPUT_PROFILE", "1080p30");
        env.put("TOGETHERTUNES_HTTP_TRUSTED_PROXIES", "127.0.0.1, ::1");
        env.put("TOGETHERTUNES_INGEST_MAX_UPLOAD_BYTES", "1GiB");
        env.put("TOGETHERTUNES_OVERLAY_WORKERS", "  ");
        Config c = load("{\"http\": {\"port\": 1234, \"publicBaseUrl\": \"https://music.example.com\"}, \"overlay.workers\": 3}");
        assertEquals(9999, c.httpPort());
        assertEquals("https://other.example.org", c.publicBaseUrl());
        assertEquals(OutputProfile.P1080_30, c.outputProfile());
        assertEquals(List.of("127.0.0.1/32", "0:0:0:0:0:0:0:1/128"), c.trustedProxies());
        assertEquals(1L << 30, c.ingestMaxUploadBytes());
        assertEquals(3, c.overlayWorkers(), "a blank variable counts as unset");
    }

    @Test
    void audioOnlyBitrate() throws ConfigException {
        Config c = load("{\"http.publicBaseUrl\": \"https://music.example.com\", \"output\": {\"audioOnlyBitrateKbps\": 600}}");
        assertEquals(600, c.outputAudioOnlyBitrateKbps());
        env.put("TOGETHERTUNES_OUTPUT_AUDIO_ONLY_BITRATE_KBPS", "1200");
        assertEquals(1200, load(URL_ONLY).outputAudioOnlyBitrateKbps());
        env.put("TOGETHERTUNES_OUTPUT_AUDIO_ONLY_BITRATE_KBPS", "499");
        assertProblem(fails(URL_ONLY), "between 500 and 25000");
        env.remove("TOGETHERTUNES_OUTPUT_AUDIO_ONLY_BITRATE_KBPS");
    }

    @Test
    void videoBitrate() throws ConfigException {
        Config c = load("{\"http.publicBaseUrl\": \"https://music.example.com\", \"output\": {\"videoBitrateKbps\": 4500}}");
        assertEquals(4500, c.outputVideoBitrateKbps());
        assertEquals(new OutputEncoding(OutputProfile.P720_30, 4500), c.outputEncoding());
        assertEquals("720p30-4500k-v3", c.outputEncoding().id());
        env.put("TOGETHERTUNES_OUTPUT_VIDEO_BITRATE_KBPS", "2500");
        assertEquals(2500, load(URL_ONLY).outputVideoBitrateKbps());
        env.put("TOGETHERTUNES_OUTPUT_VIDEO_BITRATE_KBPS", "499");
        assertProblem(fails(URL_ONLY), "between 500 and 25000");
        env.put("TOGETHERTUNES_OUTPUT_VIDEO_BITRATE_KBPS", "25001");
        assertProblem(fails(URL_ONLY), "between 500 and 25000");
        env.remove("TOGETHERTUNES_OUTPUT_VIDEO_BITRATE_KBPS");
        // a bad profile still reports the bitrate problem on its own
        ConfigException e = fails("{\"http.publicBaseUrl\": \"https://music.example.com\","
                + " \"output\": {\"profile\": \"4k60\", \"videoBitrateKbps\": \"fast\"}}");
        assertProblem(e, "'output.profile'");
        assertProblem(e, "'output.videoBitrateKbps'");
    }

    @Test
    void presets() throws ConfigException {
        Config c = load("{\"http.publicBaseUrl\": \"https://music.example.com\","
                + " \"ingest\": {\"quickPreset\": \"faster\", \"qualityPreset\": \"veryslow\"}}");
        assertEquals(X264Preset.FASTER, c.ingestQuickPreset());
        assertEquals(X264Preset.VERYSLOW, c.ingestQualityPreset());
        env.put("TOGETHERTUNES_INGEST_QUALITY_PRESET", "medium");
        assertEquals(X264Preset.MEDIUM, load(URL_ONLY).ingestQualityPreset());
        env.put("TOGETHERTUNES_INGEST_QUALITY_PRESET", "ultrafast");
        assertProblem(fails(URL_ONLY), "must be one of veryfast, faster, fast, medium, slow, slower, veryslow");
        env.remove("TOGETHERTUNES_INGEST_QUALITY_PRESET");
        env.put("TOGETHERTUNES_INGEST_QUICK_PRESET", "Slow");
        assertProblem(fails(URL_ONLY), "TOGETHERTUNES_INGEST_QUICK_PRESET");
    }

    @Test
    void environmentAloneIsEnough() throws ConfigException {
        env.put("TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://music.example.com");
        ConfigLoader.Loaded loaded = loader().load(null);
        assertNull(loaded.configFile());
        assertEquals("https://music.example.com", loaded.config().publicBaseUrl());
    }

    @Test
    void envNames() {
        assertEquals("TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", ConfigLoader.envName("http.publicBaseUrl"));
        assertEquals("TOGETHERTUNES_TOOLS_YTDLP", ConfigLoader.envName("tools.ytdlp"));
        assertEquals("TOGETHERTUNES_OVERLAY_CACHE_MAX_BYTES", ConfigLoader.envName("overlay.cacheMaxBytes"));
        assertEquals("TOGETHERTUNES_DATA_DIR", ConfigLoader.envName("data.dir"));
        assertEquals("TOGETHERTUNES_OUTPUT_VIDEO_BITRATE_KBPS", ConfigLoader.envName("output.videoBitrateKbps"));
    }

    @Test
    void everyDocumentedKeyIsKnown() {
        // docs/architecture.md#configuration
        assertEquals(List.of("http.bind", "http.port", "http.publicBaseUrl", "data.dir", "db.url",
                "tools.ffmpeg", "tools.ffprobe", "tools.ytdlp", "output.profile", "output.videoBitrateKbps",
                "output.audioOnlyBitrateKbps", "ingest.workers",
                "ingest.maxUploadBytes", "ingest.maxDurationSeconds", "ingest.quickPreset", "ingest.qualityPreset",
                "overlay.workers",
                "overlay.cacheMaxBytes", "http.trustedProxies"), ConfigLoader.KEYS);
    }

    @Test
    void publicBaseUrlIsRequired() {
        assertProblem(fails("{}"), "http.publicBaseUrl is required");
        assertProblem(fails("{\"http\": {\"publicBaseUrl\": null}}"), "http.publicBaseUrl is required");
        env.put("TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "");
        assertProblem(fails("{}"), "http.publicBaseUrl is required");
    }

    @Test
    void badPublicBaseUrls() {
        for (String url : List.of("music.example.com", "ftp://music.example.com", "https://", "https://u:p@example.com",
                "https://example.com/?x=1", "https://example.com/#x", "https://exa mple.com")) {
            ConfigException e = fails("{\"http.publicBaseUrl\": \"" + url + "\"}");
            assertProblem(e, "config key 'http.publicBaseUrl'");
            assertFalse(e.getMessage().contains("is required"), url);
        }
    }

    @Test
    void badValuesFailFastAndAreAllReported() {
        ConfigException e = fails("""
                {
                  "http": {"publicBaseUrl": "https://music.example.com", "port": 70000, "bind": ""},
                  "output": {"profile": "4k60"},
                  "ingest": {"workers": 0, "maxUploadBytes": "lots", "maxDurationSeconds": -5},
                  "overlay": {"workers": 1.5, "cacheMaxBytes": "99999999999 TiB"},
                  "db.url": "jdbc:mysql://localhost/tt",
                  "tools.ffmpeg": ["ffmpeg"]
                }
                """);
        assertProblem(e, "'http.port': must be between 1 and 65535");
        assertProblem(e, "'http.bind': must not be empty");
        assertProblem(e, "'output.profile': must be one of 720p30, 1080p30");
        assertProblem(e, "'ingest.workers': must be between 1 and 256");
        assertProblem(e, "'ingest.maxUploadBytes': must be a byte count");
        assertProblem(e, "'ingest.maxDurationSeconds': must be at least 1");
        assertProblem(e, "'overlay.workers': must be a whole number");
        assertProblem(e, "'overlay.cacheMaxBytes': is too large");
        assertProblem(e, "'db.url': must start with jdbc:sqlite: or jdbc:postgresql:");
        assertProblem(e, "'tools.ffmpeg': must be a single value");
        assertEquals(10, e.problems().size(), e.getMessage());
    }

    @Test
    void badEnvironmentValuesNameTheVariable() {
        env.put("TOGETHERTUNES_HTTP_PORT", "http");
        assertProblem(fails(URL_ONLY), "environment variable TOGETHERTUNES_HTTP_PORT: must be a whole number (got 'http')");
    }

    @Test
    void unknownKeysAreErrors() {
        ConfigException e = fails("{\"http\": {\"publicBaseUrl\": \"https://a.example\", \"publicbaseurl\": \"x\"}, \"foo\": 1}");
        assertProblem(e, "unknown key 'http.publicbaseurl' (did you mean 'http.publicBaseUrl'?)");
        assertProblem(e, "unknown key 'foo'");
    }

    @Test
    void unknownEnvironmentVariablesAreWarnings() throws ConfigException {
        env.put("TOGETHERTUNES_HTTP_PROT", "1");
        env.put("TOGETHERTUNES_CONFIG", "");
        env.put("TOGETHERTUNES_SECRET_KEY", "read by ServerSecret, not a config key");
        List<String> warnings = loader().loadFromJson(null, URL_ONLY).warnings();
        assertEquals(List.of("Ignoring unknown environment variable TOGETHERTUNES_HTTP_PROT"), warnings);
    }

    @Test
    void invalidJson() {
        assertProblem(fails("{\"http\": {"), "invalid JSON");
        assertProblem(fails("[]"), "top level must be an object");
        assertProblem(fails("{\"http.port\": 1, \"http\": {\"port\": 2}}"), "duplicate key 'http.port'");
        assertProblem(fails("{http: 1}"), "invalid JSON");
        assertProblem(fails(URL_ONLY + " {}"), "invalid JSON");
    }

    @Test
    void sizes() {
        assertEquals(0, ConfigLoader.parseSize("0"));
        assertEquals(1536, ConfigLoader.parseSize("1536 B"));
        assertEquals(2048, ConfigLoader.parseSize("2KiB"));
        assertEquals(3L << 20, ConfigLoader.parseSize("3 mib"));
        assertEquals(4L << 30, ConfigLoader.parseSize("4GB"));
        assertEquals(5L << 40, ConfigLoader.parseSize("5TiB"));
        for (String bad : List.of("", "-1", "1.5GiB", "1 PiB", "GiB", "99999999999999999999")) {
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseSize(bad), bad);
        }
    }

    @Test
    void cidrs() {
        assertEquals("10.0.0.0/8", ConfigLoader.parseCidr("10.0.0.0/8"));
        assertEquals("0.0.0.0/0", ConfigLoader.parseCidr("0.0.0.0/0"));
        assertEquals("203.0.113.7/32", ConfigLoader.parseCidr("203.0.113.7"));
        assertEquals("fd00:0:0:0:0:0:0:0/8", ConfigLoader.parseCidr("fd00::/8"));
        for (String bad : List.of("10.0.0.1/8", "10.0.0.0/33", "256.0.0.0/8", "10.0.0/8", "example.com",
                "localhost", "::1/129", "10.0.0.0/", "10.0.0.0/-1", "fe80::1%en0", "")) {
            assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseCidr(bad), bad);
        }
    }

    @Test
    void trustedProxiesMustBeAList() {
        assertProblem(fails("{\"http.publicBaseUrl\": \"https://a.example\", \"http.trustedProxies\": \"10.0.0.0/8\"}"),
                "'http.trustedProxies': must be a list");
    }

    @Test
    void tools() throws IOException, ConfigException {
        Files.delete(bin.resolve("yt-dlp"));
        ConfigLoader.Loaded loaded = loader().loadFromJson(null, URL_ONLY);
        assertEquals("yt-dlp", loaded.config().ytdlp(), "a missing default stays a bare name");
        assertTrue(loaded.warnings().get(0).contains("yt-dlp was not found on PATH"), loaded.warnings().toString());

        assertProblem(fails("{\"http.publicBaseUrl\": \"https://a.example\", \"tools.ytdlp\": \"yt-dlp\"}"),
                "'tools.ytdlp': 'yt-dlp' was not found on PATH");
        assertProblem(fails("{\"http.publicBaseUrl\": \"https://a.example\", \"tools.ffprobe\": \"/nonexistent/ffprobe\"}"),
                "'tools.ffprobe': not an executable file");
        Path notExecutable = Files.writeString(dir.resolve("plain"), "x");
        assertProblem(fails("{\"http.publicBaseUrl\": \"https://a.example\", \"tools.ffmpeg\": \"" + notExecutable + "\"}"),
                "'tools.ffmpeg': not an executable file");
    }

    @Test
    void dataDirMustNotBeAFile() throws IOException {
        Files.writeString(dir.resolve("data"), "x");
        assertProblem(fails(URL_ONLY), "is not a directory");
    }

    @Test
    void configFileSelection() throws IOException, ConfigException {
        Files.writeString(dir.resolve("config.json"), URL_ONLY);
        Files.writeString(dir.resolve("other.json"), "{\"http.publicBaseUrl\": \"https://other.example\"}");

        ConfigLoader.Loaded def = loader().load(null);
        assertEquals(dir.resolve("config.json"), def.configFile());
        assertEquals("https://music.example.com", def.config().publicBaseUrl());

        env.put("TOGETHERTUNES_CONFIG", "other.json");
        assertEquals("https://other.example", loader().load(null).config().publicBaseUrl());

        assertEquals("https://music.example.com", loader().load(Path.of("config.json")).config().publicBaseUrl(),
                "--config wins over the environment");

        ConfigException missing = assertThrows(ConfigException.class, () -> loader().load(Path.of("missing.json")));
        assertProblem(missing, "Config file not found");
        env.put("TOGETHERTUNES_CONFIG", "missing.json");
        assertThrows(ConfigException.class, () -> loader().load(null));
    }
}
