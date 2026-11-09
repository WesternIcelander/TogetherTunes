package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Runs the clock-sync unit tests ({@code src/test/resources/web-tests/clocksync.test.mjs}) against
 * the compiled {@code web/js/clocksync.js} with the Node that the frontend build downloaded into
 * {@code web/node/} (docs/rooms.md#clock-sync-web-and-app).
 */
class ClockSyncJsTest {
    private static Path resource(String path) throws URISyntaxException {
        return NodeTests.resource(path);
    }

    @Test
    void clockSyncUnitTestsPass() throws Exception {
        NodeTests.run("clocksync.test.mjs", Map.of("CLOCKSYNC_JS", "web/js/clocksync.js"));
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
