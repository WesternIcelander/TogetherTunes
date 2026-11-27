package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mobile layout (docs/ui.md#mobile-narrow-windows): runs the unit tests of its DOM-free rules
 * ({@code src/test/resources/web-tests/mobile.test.mjs}) against the compiled {@code web/js/mobile.js},
 * and checks that index.html, the frame, the lists and main.css hold its parts (top bar, bottom tabs,
 * panes, mini bar, ⋯ menus and long press, Select mode).
 */
class MobileJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void mobileUnitTestsPass() throws Exception {
        NodeTests.run("mobile.test.mjs", Map.of("MOBILE_JS", "web/js/mobile.js"));
    }

    @Test
    void mobileRulesArePureLogic() throws Exception {
        String mobile = read("web/js/mobile.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.")) {
            assertFalse(mobile.contains(s), s);
        }
    }

    @Test
    void theLayoutHasItsParts() throws Exception {
        String index = read("web/index.html");
        for (String s : List.of("id=\"m-back\"", "id=\"m-title\"", "id=\"m-tabs\"", "id=\"m-pane-title\"",
                "id=\"m-room-tabs\"", "id=\"m-tab-rooms\"", "id=\"m-tab-this-room\"", "id=\"m-account\"",
                "id=\"m-now-empty\"", "Up Next", "id=\"pb-m-mute\"", "id=\"pb-m-mute-song\"",
                "id=\"list-select-bar\"", "id=\"list-select-queue\"", "class=\"col-more\"", "id=\"m-np\"",
                "id=\"np-title\"", "id=\"np-heart\"", "id=\"np-seek\"", "id=\"np-pos\"", "id=\"np-dur\"",
                "id=\"np-prev\"", "id=\"np-play\"", "id=\"np-next\"", "id=\"np-more\"")) {
            assertTrue(index.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("MOBILE_QUERY", "mPane", "pushState", "\"popstate\"", "tabAction(", "mobileBack(",
                "showBarTitle(", "onLongPress(", "\"/mine\"", "PLAYLIST_ACTION_LABELS",
                "shownPane(", "nowPlayerSize(", "nowPlayingPath(", "#drawNowPlayer(", "--np-h", "--np-top",
                "--np-bottom", "\"np-more\"", "#homeMenu(", "closest(MINI_BAR_CONTROLS)", "Open Now Playing")) {
            assertTrue(frame.contains(s), s);
        }
        // The Now Playing pane holds only the player: the request parts stay in the room view.
        assertFalse(frame.contains("rv-request"), "frame.ts doesn't move the request parts");
        String list = read("web/js/libview.js");
        for (String s : List.of("onLongPress(", "openMenu(", "new Selection()", "\"queue.add\"", "Add to Queue",
                "Select")) {
            assertTrue(list.contains(s), s);
        }
        String touch = read("web/js/touch.js");
        for (String s : List.of("\"pointerdown\"", "\"contextmenu\"", "LONG_PRESS_MS", "popup-menu")) {
            assertTrue(touch.contains(s), s);
        }
        String css = read("web/css/main.css");
        for (String s : List.of("@media (max-width: 799.98px)", "body[data-m-pane=\"now\"] .app-video",
                "body.m-tabs-on .player-bar", "table.songs .col-album", ".m-tabs:not([hidden])",
                ".rv-columns > .rv-request", "body[data-m-pane=\"now\"] .app-panel > *",
                "body[data-m-pane=\"now\"] .app-panel > .m-np", "body[data-m-pane=\"now\"] .player-bar",
                "var(--np-h", "var(--np-art", "var(--np-top", "var(--np-bottom", "body > #pb-menu",
                "@media (max-width: 799.98px) and (max-height: 499.98px)")) {
            assertTrue(css.contains(s), s);
        }
        assertFalse(css.contains("body[data-m-pane=\"now\"] .app-panel > .rv-request"), "no request parts in Now Playing");
    }
}
