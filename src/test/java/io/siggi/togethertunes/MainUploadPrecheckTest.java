package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The upload pre-check is on only for an {@code https} {@code http.publicBaseUrl} ({@link Main#uploadPrecheck}). */
class MainUploadPrecheckTest {
    private static Config config(String baseUrl) {
        return new Config("127.0.0.1", 0, baseUrl, List.of(), Path.of("data"), "jdbc:sqlite:x.db", null, null, null,
                OutputProfile.P720_30, 3000, 800, 1, Settings.GIB, 1800, X264Preset.VERYFAST, X264Preset.VERYFAST, 1,
                Settings.GIB, null);
    }

    @Test
    void onlyOverHttps() {
        assertTrue(Main.uploadPrecheck(config("https://tunes.example")));
        assertTrue(Main.uploadPrecheck(config("HTTPS://tunes.example/prefix")));
        assertFalse(Main.uploadPrecheck(config("http://tunes.example")));
        assertFalse(Main.uploadPrecheck(config("http://localhost:8080")), "not even on localhost");
    }
}
