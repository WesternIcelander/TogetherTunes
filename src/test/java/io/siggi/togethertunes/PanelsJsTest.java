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
                "\"/api/me/likes\"", "\"/api/playlists/\"", "/api/search?", "openAddLink(",
                "\"queue.addPlaylist\"", "In Order", "Shuffled", "+ Add from link", "/autoplay/playlists/",
                "onReady(start)")) {
            assertTrue(list.contains(s), s);
        }
        assertTrue(read("web/js/addlink.js").contains("\"/api/imports/youtube\""));
        // the listener's volume and mute reach the player after its meters (never mistaken for a silent path)
        String listen = read("web/js/listen.js");
        assertTrue(listen.contains("setVolume(") && listen.contains("setMuted(") && listen.contains("#output("), listen);
    }

    @Test
    void roomViewHasItsLayout() throws Exception {
        // docs/ui.md#room-view-desktop: video area, queue, quick outputs above Request Link, Join as guest,
        // people and Earlier; the frame moves its video panel in and draws the room parts.
        String room = NodeTests.view("view-room");
        for (String s : List.of("id=\"rv-video-slot\"", "id=\"rv-title\"", "id=\"rv-vote-skip\"", "id=\"rv-queue\"",
                "id=\"rv-outputs\"", "id=\"rv-request-link\"", "id=\"room-identity\"", "id=\"rv-people\"",
                "id=\"rv-earlier\"", "id=\"rv-grid\"")) {
            assertTrue(room.contains(s), s);
        }
        assertTrue(room.indexOf("id=\"rv-outputs\"") < room.indexOf("id=\"rv-request-link\""), "quick outputs above Request Link");
        assertTrue(room.indexOf("id=\"rv-request-link\"") < room.indexOf("id=\"room-identity\""), "Join as guest under the request section");
        assertTrue(room.indexOf("id=\"rv-people\"") < room.indexOf("id=\"rv-earlier\""), "people above Earlier");
        assertTrue(read("web/index.html").contains("id=\"app-sidebar-toggle\""));
        String frame = read("web/js/frame.js");
        for (String s : List.of("rv-video-slot", "\"rv-queue\"", "\"rv-people\"", "\"rv-earlier\"", "\"rv-title\"",
                "room-view", "sidebar-open", "\"dblclick\"", "personActions(", "parseDurationInput(", "/members?limit=200",
                "/bans", "\"/api/admin/users/\"", "release(", "room-listen-overlay")) {
            assertTrue(frame.contains(s), s);
        }
        assertTrue(read("web/js/shell.js").contains("this.#frame.release("));
        String roomJs = read("web/js/room.js");
        for (String s : List.of("Request Link", "Join as guest", "Change your name", "\"rv-outputs\"", "youtubeUrl")) {
            assertTrue(roomJs.contains(s), s);
        }
        String outputsPanel = read("web/js/outputspanel.js");
        assertTrue(outputsPanel.contains("quickOutputs("), outputsPanel);
        String css = read("web/css/main.css");
        assertTrue(css.contains("body.room-view .app-panel"), "the right panel hides in the room view");
        assertTrue(css.contains(".app-video .listen-overlay"), "the sound layer covers the video area only");
    }
}
