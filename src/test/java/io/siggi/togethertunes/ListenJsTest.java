package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Listen mode (docs/rooms.md#listen-mode): runs the unit tests of the sync decisions
 * ({@code src/test/resources/web-tests/listensync.test.mjs}) against the compiled
 * {@code web/js/listensync.js}, and checks that the player and the room page use what the design asks
 * for (the element's GainNode, pitch kept, the autoplay overlay, the video / audio-only crossfade), and
 * that the sync loop records its decisions for the "Send diagnostics" report
 * ({@code diagnostics.test.mjs} tests the rings).
 */
class ListenJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void listenSyncUnitTestsPass() throws Exception {
        NodeTests.run("listensync.test.mjs", Map.of("LISTENSYNC_JS", "web/js/listensync.js"));
    }

    @Test
    void diagnosticsUnitTestsPass() throws Exception {
        NodeTests.run("diagnostics.test.mjs", Map.of("DIAGNOSTICS_JS", "web/js/diagnostics.js"));
    }

    @Test
    void syncLoopRecordsDiagnosticsAndTheRoomPageSendsThem() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("from \"./diagnostics.js\"", "recordDecision(", "recordError(\"tick\"",
                "new DriftController(", "new SeekLead(", "\"playing\"", "\"seeked\"", "landed(")) {
            assertTrue(player.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("captureErrors(window", "\"/api/diagnostics\"", "syncModeFor(engineHints()", "maxTouchPoints", "mozPreservesPitch",
                "togetherTunesClock")) {
            assertTrue(room.contains(s), s);
        }
        String html = read("web/room.html");
        assertTrue(html.contains("id=\"room-diagnostics\""), html);
        assertTrue(html.contains("Send diagnostics"), html);
    }

    @Test
    void playerUsesGainNodesKeepsPitchAndChecksCanPlayType() throws Exception {
        String player = read("web/js/listen.js");
        for (String s : List.of("from \"./listensync.js\"", "createMediaElementSource(", "createGain(",
                "preservesPitch", "canPlayType(", "NotAllowedError", "playsinline", ".resume()",
                "linearRampToValueAtTime(", "SwitchGate(", "CROSSFADE_MS", "removeAttribute(\"src\")")) {
            assertTrue(player.contains(s), s);
        }
        String room = read("web/js/room.js");
        assertTrue(room.contains("from \"./listen.js\""), room);
        assertTrue(room.contains("new ListenPlayer("), room);
        assertTrue(room.contains("setAudioOnly("), room);
        String html = read("web/room.html");
        assertTrue(html.contains("id=\"room-listen-overlay\""), html);
        assertTrue(html.contains("Click to listen"), html);
        assertTrue(html.contains("id=\"room-player\""), html);
        assertTrue(html.contains("id=\"room-audio-only\""), html);
    }
}
