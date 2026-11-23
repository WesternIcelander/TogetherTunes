package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The web client's the header search's dropdown (docs/ui.md#header-search): runs
 * {@code src/test/resources/web-tests/searchdrop.test.mjs} against the compiled {@code web/js/searchdrop.js}
 * and checks that the module is pure logic.
 */
class SearchDropJsTest {
    @Test
    void unitTestsPass() throws Exception {
        NodeTests.run("searchdrop.test.mjs", Map.of("SEARCHDROP_JS", "web/js/searchdrop.js"));
    }

    @Test
    void itIsPureLogic() throws Exception {
        String js = Files.readString(NodeTests.resource("web/js/searchdrop.js"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }
}
