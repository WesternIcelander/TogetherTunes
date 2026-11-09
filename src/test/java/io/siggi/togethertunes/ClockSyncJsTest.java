package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs the clock-sync unit tests ({@code src/test/resources/web-tests/clocksync.test.mjs}) against
 * the compiled {@code web/js/clocksync.js} with the Node that the frontend build downloaded into
 * {@code web/node/} (docs/rooms.md#clock-sync-web-and-app).
 */
class ClockSyncJsTest {
    private static Path resource(String path) throws URISyntaxException {
        URL url = ClockSyncJsTest.class.getClassLoader().getResource(path);
        assertNotNull(url, path + " is missing from the classpath");
        return Path.of(url.toURI());
    }

    @Test
    void clockSyncUnitTestsPass() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path node = Path.of("web", "node", windows ? "node.exe" : "node").toAbsolutePath();
        // Skipped only when the frontend build (and with it Node) was skipped, e.g. -Dskip.npm.
        Assumptions.assumeTrue(Files.isExecutable(node), "no Node at " + node);
        Path module = resource("web/js/clocksync.js");
        Path tests = resource("web-tests/clocksync.test.mjs");
        ProcessBuilder pb = new ProcessBuilder(List.of(node.toString(), "--test", tests.toString()))
                .redirectErrorStream(true);
        pb.environment().put("CLOCKSYNC_JS", module.toString());
        Process p = pb.start();
        String out;
        try {
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "node --test timed out");
        } finally {
            p.destroyForcibly();
        }
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("ℹ fail 0") && !out.contains("ℹ pass 0"), out);
    }

    @Test
    void roomPageUsesClockSync() throws IOException, URISyntaxException {
        String room = Files.readString(resource("web/js/room.js"), StandardCharsets.UTF_8);
        assertTrue(room.contains("from \"./clocksync.js\""), room);
        assertTrue(room.contains("new ClockSync("), room);
        String client = Files.readString(resource("web/js/roomclient.js"), StandardCharsets.UTF_8);
        assertTrue(client.contains("type: \"time\""), client);
    }
}
