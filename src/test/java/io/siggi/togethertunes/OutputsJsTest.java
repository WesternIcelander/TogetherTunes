package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The room page's outputs (docs/rooms.md#outputs): runs the unit tests of the outputs decisions
 * ({@code src/test/resources/web-tests/outputs.test.mjs}) against the compiled {@code web/js/outputs.js},
 * and checks that the room page and its socket client use them: {@code output.status} applied to the
 * state, the outputs panel with copy buttons, rotate, on/off, the RTMP target form, status and
 * viewer URL, and the player allowlist and HLS desync notes.
 */
class OutputsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void outputsUnitTestsPass() throws Exception {
        NodeTests.run("outputs.test.mjs", Map.of("OUTPUTS_JS", "web/js/outputs.js", "REMOTE_JS", "web/js/remote.js"));
    }

    @Test
    void roomPageShowsTheOutputs() throws Exception {
        String outputs = read("web/js/outputs.js");
        for (String s : List.of("document.", "createElement", "fetch(")) {
            assertFalse(outputs.contains(s), s);
        }
        String client = read("web/js/roomclient.js");
        for (String s : List.of("case \"output.status\"", "applyOutputStatus(")) {
            assertTrue(client.contains(s), s);
        }
        String panel = read("web/js/outputspanel.js");
        for (String s : List.of("navigator.clipboard", "execCommand(\"copy\")", "/rotate", "\"Rotate URL\"",
                "\"Turn off\"", "\"Turn on\"", "rtmpKey", "outputNotes(", "pushLine(", "rtmpPlaybackUrl(",
                "/server-switches", "type: \"password\"", "secretField(", "\"Reveal\"", "\"Hide\"",
                "\"Stream key (if needed)\"")) {
            assertTrue(panel.contains(s), s);
        }
        // moderators never see the key or its last 4; the old "stored encrypted" text is gone
        for (String s : List.of("rtmpKeyLast4", "stored encrypted")) {
            assertFalse(panel.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("from \"./outputspanel.js\"", "new OutputsPanel(", "outputsPanel.update(")) {
            assertTrue(room.contains(s), s);
        }
        assertTrue(read("web/room.html").contains("id=\"room-outputs\""));
    }
}
