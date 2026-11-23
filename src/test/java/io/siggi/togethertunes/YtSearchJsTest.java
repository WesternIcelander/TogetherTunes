package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The web client's YouTube search results (docs/ui.md#header-search, the search page): runs
 * {@code src/test/resources/web-tests/ytsearch.test.mjs} against the compiled {@code web/js/ytsearch.js}
 * and checks that the module is pure logic.
 */
class YtSearchJsTest {
    @Test
    void unitTestsPass() throws Exception {
        NodeTests.run("ytsearch.test.mjs", Map.of("YTSEARCH_JS", "web/js/ytsearch.js"));
    }

    @Test
    void itIsPureLogic() throws Exception {
        String js = Files.readString(NodeTests.resource("web/js/ytsearch.js"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }
}
