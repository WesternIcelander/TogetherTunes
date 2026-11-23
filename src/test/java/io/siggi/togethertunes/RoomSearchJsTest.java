package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The room view's request search (docs/ui.md#leftovers-removed-end-of-m9): runs
 * {@code src/test/resources/web-tests/roomsearch.test.mjs} against the compiled {@code web/js/roomsearch.js},
 * checks that the module is pure logic and that room.js uses it (clearing on input, Search YouTube).
 */
class RoomSearchJsTest {
    @Test
    void unitTestsPass() throws Exception {
        NodeTests.run("roomsearch.test.mjs", Map.of("ROOMSEARCH_JS", "web/js/roomsearch.js"));
    }

    @Test
    void itIsPureLogic() throws Exception {
        String js = Files.readString(NodeTests.resource("web/js/roomsearch.js"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void theRoomViewUsesIt() throws Exception {
        String room = Files.readString(NodeTests.resource("web/js/room.js"), StandardCharsets.UTF_8);
        for (String s : List.of("roomSearchButtons(", "new RoomSearchResults(", "state.input()", "\"input\"",
                "roomYoutubeSearchPath(", "youtubeRows(", "youtubeUrl: watchUrl(r)", "SEARCH_YOUTUBE_LABEL")) {
            assertTrue(room.contains(s), s);
        }
    }
}
