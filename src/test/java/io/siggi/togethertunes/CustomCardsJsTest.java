package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Custom card images in the web client ({@code docs/media-types.md#custom-card-images}): runs
 * {@code src/test/resources/web-tests/customcards.test.mjs} against the compiled
 * {@code web/js/customcards.js}, and checks that the admin page and the room management view mount
 * the panel on their own paths.
 */
class CustomCardsJsTest {
    @Test
    void customCardsUnitTestsPass() throws Exception {
        NodeTests.run("customcards.test.mjs", Map.of("CUSTOM_CARDS_JS", "web/js/customcards.js"));
    }

    @Test
    void pagesMountThePanel() throws Exception {
        String admin = Files.readString(NodeTests.resource("web/js/admin.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./customcardsui.js\"", "mountCustomCards(", "serverCardsPath()", "\"cards\"")) {
            assertTrue(admin.contains(s), "admin.js: " + s);
        }
        String manage = Files.readString(NodeTests.resource("web/js/roommanage.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./customcardsui.js\"", "mountCustomCards(", "roomCardsPath(room.id)")) {
            assertTrue(manage.contains(s), "roommanage.js: " + s);
        }
        String ui = Files.readString(NodeTests.resource("web/js/customcardsui.js"), StandardCharsets.UTF_8);
        for (String s : List.of("\"PUT\"", "\"DELETE\"", "w.message", "canEdit")) {
            assertTrue(ui.contains(s), "customcardsui.js: " + s);
        }
        for (String page : List.of("web/admin/index.html", "web/room-manage.html")) {
            String html = Files.readString(NodeTests.resource(page), StandardCharsets.UTF_8);
            assertTrue(html.contains("id=\"cards-root\"") && html.contains("data-tab=\"cards\""), page);
        }
    }
}
