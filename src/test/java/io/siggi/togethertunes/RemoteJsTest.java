package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Remote mode (docs/rooms.md#remote-mode): runs the unit tests of the remote-mode decisions
 * ({@code src/test/resources/web-tests/remote.test.mjs}) against the compiled {@code web/js/remote.js},
 * and checks that the room page uses them: the mode remembered per room, the delayed view, the
 * nudge, and no media in remote mode.
 */
class RemoteJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void remoteUnitTestsPass() throws Exception {
        NodeTests.run("remote.test.mjs", Map.of("REMOTE_JS", "web/js/remote.js"));
    }

    @Test
    void roomPageUsesRemoteMode() throws Exception {
        String remote = read("web/js/remote.js");
        // No DOM and no media in the remote-mode module.
        for (String s : List.of("document.", "new Audio", "createElement", "fetch(")) {
            assertFalse(remote.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("from \"./remote.js\"", "new DelayedTimeline(", "chooseMode(", "modeKey(", "nudgeKey(",
                "outputDelay(", "stepNudge(", "type: \"mode\"", "player?.stop()", "history.replaceState(")) {
            assertTrue(room.contains(s), s);
        }
        String html = NodeTests.view("view-room");
        for (String s : List.of("id=\"room-mode-toggle\"", "id=\"room-remote-controls\"", "id=\"room-delay-less\"",
                "id=\"room-delay-more\"", "id=\"room-delay-total\"")) {
            assertTrue(html.contains(s), s);
        }
    }
}
