package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.ConfigLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @Test
    void versionFallsBackToDevOutsideTheJar() {
        // Tests run from target/classes, which has no manifest.
        assertEquals("dev", Main.version());
    }

    @Test
    void siggiHttpIsOnTheClasspath() throws ClassNotFoundException {
        Class<?> server = Class.forName("io.siggi.http.HTTPServer");
        assertFalse(server.isInterface());
    }

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        ConfigLoader loader = new ConfigLoader(env, 4, dir);
        return Main.run(args, loader, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void missingPublicBaseUrlStopsStartup() {
        assertEquals(Main.EXIT_CONFIG, run(Map.of()));
        assertTrue(err().contains("http.publicBaseUrl is required"), err());
    }

    @Test
    void loadsTheConfigFileNamedOnTheCommandLine() throws IOException {
        Path file = Files.writeString(dir.resolve("tt.json"), "{\"http\": {\"publicBaseUrl\": \"https://music.example.com\"}}");
        assertEquals(0, run(Map.of("PATH", ""), "--config", file.toString()));
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("https://music.example.com"), stdout);
        assertTrue(stdout.contains("720p30"), stdout);
        assertTrue(err().contains("WARNING: ffmpeg was not found on PATH"), err());
    }

    @Test
    void badInsecureBaseUrlStopsStartup() {
        assertEquals(Main.EXIT_CONFIG, run(Map.of("PATH", "",
                "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://music.example.com",
                "TOGETHERTUNES_HTTP_INSECURE_BASE_URL", "https://music.example.com")));
        assertTrue(err().contains("TOGETHERTUNES_HTTP_INSECURE_BASE_URL"), err());
    }

    @Test
    void helpNamesThePlainHttpAddress() {
        assertEquals(Main.EXIT_USAGE, run(Map.of(), "--help"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("http.insecureBaseUrl"));
    }

    @Test
    void badUsage() {
        assertEquals(Main.EXIT_USAGE, run(Map.of(), "--config"));
        assertEquals(Main.EXIT_USAGE, run(Map.of(), "--bogus"));
        assertTrue(err().contains("Unknown argument: --bogus"), err());
        assertEquals(Main.EXIT_CONFIG, run(Map.of(), "--config=missing.json"));
        assertTrue(err().contains("Config file not found"), err());
    }
}
