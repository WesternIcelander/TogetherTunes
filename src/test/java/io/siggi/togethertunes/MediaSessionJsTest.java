package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * System media controls ({@code docs/ui.md} "System media controls"): runs
 * {@code src/test/resources/web-tests/mediasession.test.mjs} against the compiled
 * {@code web/js/mediasession.js} (metadata from the room state, which actions for whom, when to
 * clear, the position, applying it to a session), and checks that the frame and the room view wire it.
 */
class MediaSessionJsTest {
    @Test
    void mediaSessionUnitTestsPass() throws Exception {
        NodeTests.run("mediasession.test.mjs", Map.of("MEDIASESSION_JS", "web/js/mediasession.js"));
    }

    @Test
    void frameAndRoomUseIt() throws Exception {
        String frame = Files.readString(NodeTests.resource("web/js/frame.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./mediasession.js\"", "MediaSessionSync", "sessionPlan", "\"mediaSession\" in navigator",
                "new MediaMetadata(", "type: \"next\"")) {
            assertTrue(frame.contains(s), s);
        }
        String room = Files.readString(NodeTests.resource("web/js/room.js"), StandardCharsets.UTF_8);
        assertTrue(room.contains("heard: () =>"), "the room view tells the frame whether this window is heard");
        String listen = Files.readString(NodeTests.resource("web/js/listen.js"), StandardCharsets.UTF_8);
        assertTrue(listen.contains("get heard()"));
    }
}
