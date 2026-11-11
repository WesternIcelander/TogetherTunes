package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Classify on library add in the web client ({@code docs/media-types.md#media-types}): runs
 * {@code src/test/resources/web-tests/classify.test.mjs} against the compiled
 * {@code web/js/classify.js}, and checks that every page that adds to a library handles the
 * {@code 409 needs_classification} question.
 */
class ClassifyJsTest {
    @Test
    void classifyUnitTestsPass() throws Exception {
        NodeTests.run("classify.test.mjs", Map.of("CLASSIFY_JS", "web/js/classify.js"));
    }

    @Test
    void everyLibraryAddAsksForATypeWhenNeeded() throws Exception {
        for (String page : new String[] {"item.js", "library.js", "room.js"}) {
            String js = Files.readString(NodeTests.resource("web/js/" + page), StandardCharsets.UTF_8);
            assertTrue(js.contains("withClassification("), page);
        }
        String add = Files.readString(NodeTests.resource("web/js/addmedia.js"), StandardCharsets.UTF_8);
        assertTrue(add.contains("needsClassification(") && add.contains("classifyAndAdd("), "addmedia.js");
        String uploads = Files.readString(NodeTests.resource("web/js/uploads.js"), StandardCharsets.UTF_8);
        assertTrue(uploads.contains("needsClassification("), "uploads.js");
        String room = Files.readString(NodeTests.resource("web/js/room.js"), StandardCharsets.UTF_8);
        assertTrue(room.contains("classifyAndAdd("), "room.js: an upload also going into the library");
    }
}
