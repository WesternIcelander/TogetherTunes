package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rooms page ({@code /rooms/}): runs the unit tests of its logic
 * ({@code src/test/resources/web-tests/roomlist.test.mjs}) against the compiled
 * {@code web/js/roomlist.js}, and checks that the page lists and creates rooms through the API, is
 * open to guests and anonymous visitors, and is linked from the navigation and the front page.
 */
class RoomsPageJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void roomListUnitTestsPass() throws Exception {
        NodeTests.run("roomlist.test.mjs", Map.of("ROOMLIST_JS", "web/js/roomlist.js"));
    }

    @Test
    void roomsPageListsAndCreatesRooms() throws Exception {
        String logic = read("web/js/roomlist.js");
        for (String s : List.of("document.", "fetch(", "createElement")) {
            assertFalse(logic.contains(s), s);
        }
        String page = read("web/js/rooms.js");
        for (String s : List.of("from \"./session.js\"", "from \"./roomlist.js\"", "\"/api/rooms?\"",
                "\"POST\", \"/api/rooms\"", "nextCursor", "canCreateRooms(", "fieldForError(", "navigate(roomPagePath(room))",
                "renderNav(")) {
            assertTrue(page.contains(s), s);
        }
        // Open to everyone: guests and anonymous visitors see the public rooms.
        assertFalse(page.contains("requireUser"), page);
        assertTrue(logic.contains("\"rooms.create\""), logic);
        String html = NodeTests.view("view-rooms");
        for (String s : List.of("id=\"rooms-rows\"", "id=\"rooms-more\"",
                "id=\"create-panel\" class=\"panel\" hidden", "id=\"create-room\"", "name=\"name\"", "name=\"slug\"",
                "name=\"visibility\"", "name=\"controlMode\"", "id=\"create-slug-error\"")) {
            assertTrue(html.contains(s), s);
        }
        String session = read("web/js/session.js");
        assertTrue(session.contains("\"/rooms/\""), session);
        assertTrue(session.contains("link(ROOMS_PAGE, \"Rooms\")"), session);
        assertTrue(read("web/js/main.js").contains("link(ROOMS_PAGE, \"Rooms\")"));
    }
}
