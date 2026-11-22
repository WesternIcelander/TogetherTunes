package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The new UI's interactions (docs/ui.md#interaction-rules-all-layouts): runs the unit tests of the
 * DOM-free rules ({@code src/test/resources/web-tests/interactions.test.mjs}) against the compiled
 * {@code web/js/interactions.js}, and checks that the lists and the frame use them (double-click,
 * multi-select, right-click menus with ⋯ equivalents, autoplay selection, rename).
 */
class InteractionsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void interactionUnitTestsPass() throws Exception {
        NodeTests.run("interactions.test.mjs",
                Map.of("INTERACTIONS_JS", "web/js/interactions.js", "MOBILE_JS", "web/js/mobile.js"));
    }

    @Test
    void interactionRulesArePureLogic() throws Exception {
        String js = read("web/js/interactions.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void theListsAndTheFrameUseThem() throws Exception {
        String list = read("web/js/libview.js");
        for (String s : List.of("\"dblclick\"", "doubleClickAction(", "prefs.load()",
                "clickSelect(", "songMenuEntries(", "copyText(", "findVideo(", "itemLinks(", "queueItem(",
                "pickedText(")) {
            assertTrue(list.contains(s), s);
        }
        String songMenu = read("web/js/songmenu.js");
        for (String s : List.of("\"queue.move\"", "\"queue.add\"", "playNowBefore(", "/video-search", "\"/pick\"",
                "candidateDetail(", "NO_VIDEO_FOUND", "searchErrorText(")) {
            assertTrue(songMenu.contains(s), s);
        }
        String item = read("web/js/item.js");
        for (String s : List.of("songMenuEntries(", "list: \"item\"", "findVideo(item)", "queueItem(", "openMenu(")) {
            assertTrue(item.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("\"dblclick\"", "playlistClick(", "playlistMenu(", "entryMenu(", "entryMessage(",
                "\"PATCH\"", "lastPressWasTouch(", "entry-more", "#openPersonMenu(room, p, more)")) {
            assertTrue(frame.contains(s), s);
        }
    }
}
