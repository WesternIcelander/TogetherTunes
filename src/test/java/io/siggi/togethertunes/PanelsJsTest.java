package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The desktop layout (docs/ui.md#layout-desktop): runs the unit tests of its DOM-free rules
 * ({@code src/test/resources/web-tests/panels.test.mjs}) against the compiled {@code web/js/panels.js},
 * and checks that index.html holds the frame (header search, sidebars, right panel tabs, video panel,
 * player bar with its menu) and that the middle lists' view uses the APIs the layout needs.
 */
class PanelsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void panelsUnitTestsPass() throws Exception {
        NodeTests.run("panels.test.mjs", Map.of("PANELS_JS", "web/js/panels.js"));
    }

    @Test
    void panelsArePureLogic() throws Exception {
        String panels = read("web/js/panels.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(")) {
            assertFalse(panels.contains(s), s);
        }
    }

    @Test
    void indexHtmlHasTheDesktopFrame() throws Exception {
        String index = read("web/index.html");
        for (String s : List.of("class=\"app-header\"", "id=\"app-search\"", "action=\"/search\"",
                "id=\"app-tab-library\"", "id=\"app-tab-rooms\"", "id=\"app-room-search\"", "id=\"app-new-room\"",
                "id=\"app-tab-queue\"", "id=\"app-tab-recents\"", "id=\"app-tab-room\"", "id=\"app-video\"",
                "id=\"player-bar\"", "id=\"pb-prev\"", "id=\"pb-play\"", "id=\"pb-next\"", "id=\"pb-heart\"",
                "id=\"pb-mute-big\"", "id=\"pb-seek\"", "id=\"pb-volume\"", "Mute only until end of song",
                "Audio Only", "Requests-only mode", "Now Playing View", "Fullscreen", "<template id=\"view-list\"")) {
            assertTrue(index.contains(s), s);
        }
        String list = read("web/js/libview.js");
        for (String s : List.of("\"/api/library\"", "\"/api/library/uncategorized\"", "/api/library/shows",
                "\"/api/me/likes\"", "\"/api/playlists/\"", "/api/search?", "\"/api/imports/youtube\"",
                "\"queue.addPlaylist\"", "In Order", "Shuffled", "+ Add from link", "/autoplay/playlists/",
                "onReady(start)")) {
            assertTrue(list.contains(s), s);
        }
        // the listener's volume and mute reach the player after its meters (never mistaken for a silent path)
        String listen = read("web/js/listen.js");
        assertTrue(listen.contains("setVolume(") && listen.contains("setMuted(") && listen.contains("#output("), listen);
    }
}
