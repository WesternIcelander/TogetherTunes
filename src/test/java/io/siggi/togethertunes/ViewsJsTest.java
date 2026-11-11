package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Views and pins in the web client ({@code docs/media-types.md#views-auto-delete-and-pins}): runs
 * {@code src/test/resources/web-tests/views.test.mjs} against the compiled {@code web/js/views.js},
 * and checks that the item page reports a view when its player starts and offers the pin.
 */
class ViewsJsTest {
    @Test
    void viewsUnitTestsPass() throws Exception {
        NodeTests.run("views.test.mjs", Map.of("VIEWS_JS", "web/js/views.js"));
    }

    @Test
    void itemPageReportsViewsAndPins() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./views.js\"", "viewReporter", "addEventListener(\"play\"", "reportView(",
                "pinPath(", "canPin", "pinText(")) {
            assertTrue(item.contains(s), s);
        }
    }
}
