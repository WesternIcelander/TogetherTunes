package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shows in the web client ({@code docs/media-types.md#next-episode}): runs
 * {@code src/test/resources/web-tests/shows.test.mjs} against the compiled {@code web/js/shows.js},
 * and checks that the library page groups shows and the room page offers Next episode with it.
 */
class ShowsJsTest {
    @Test
    void showsUnitTestsPass() throws Exception {
        NodeTests.run("shows.test.mjs", Map.of("SHOWS_JS", "web/js/shows.js"));
    }

    @Test
    void pagesUseIt() throws Exception {
        String library = Files.readString(NodeTests.resource("web/js/library.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./shows.js\"", "/api/library/shows")) {
            assertTrue(library.contains(s), s);
        }
        String room = Files.readString(NodeTests.resource("web/js/room.js"), StandardCharsets.UTF_8);
        for (String s : List.of("offersNextEpisode", "queue.nextEpisode")) {
            assertTrue(room.contains(s), s);
        }
    }
}
