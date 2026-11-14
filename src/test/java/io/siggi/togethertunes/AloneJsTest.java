package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Alone and switching rooms (docs/ui.md#alone-and-in-rooms): runs the unit tests of the DOM-free
 * rules ({@code src/test/resources/web-tests/alone.test.mjs}) against the compiled
 * {@code web/js/alone.js}, and checks that the shell, the room view, the frame and the rooms page use
 * the play session API (background room, join, leave with fork or restore, Recents alone, New Room
 * moving the alone state, the room list's listeners and search).
 */
class AloneJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void aloneUnitTestsPass() throws Exception {
        NodeTests.run("alone.test.mjs", Map.of("ALONE_JS", "web/js/alone.js"));
    }

    @Test
    void aloneRulesArePureLogic() throws Exception {
        String alone = read("web/js/alone.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.")) {
            assertFalse(alone.contains(s), s);
        }
    }

    @Test
    void theViewsUseThePlaySession() throws Exception {
        String shell = read("web/js/shell.js");
        for (String s : List.of("\"/api/me/play-session\"", "\"/api/me/play-session/leave\"", "sessionRoom(",
                "needsLeaveChoice(", "leaveKind(", "registerViewStart(", "#startBackground(")) {
            assertTrue(shell.contains(s), s);
        }
        String room = read("web/js/room.js");
        for (String s : List.of("viewStart(import.meta.url)", "\"/api/me/play-session/join\"", "shouldJoin(",
                "gone(reason)")) {
            assertTrue(room.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("\"/api/me/history?limit=50\"", "historyPlace(", "recentsFromHistory(", "On your own")) {
            assertTrue(frame.contains(s), s);
        }
        String rooms = read("web/js/rooms.js");
        for (String s : List.of("createRoomBody(", "listeners", "params.set(\"q\", q)")) {
            assertTrue(rooms.contains(s), s);
        }
        assertTrue(read("web/index.html").contains("id=\"rooms-search\""));
    }
}
