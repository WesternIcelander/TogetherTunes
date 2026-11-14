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
        for (String page : new String[] {"item.js", "libview.js", "frame.js", "addlink.js", "media.js"}) {
            String js = Files.readString(NodeTests.resource("web/js/" + page), StandardCharsets.UTF_8);
            assertTrue(js.contains("withClassification("), page);
        }
        for (String page : new String[] {"uploads.js", "filedrop.js"}) {
            String js = Files.readString(NodeTests.resource("web/js/" + page), StandardCharsets.UTF_8);
            assertTrue(js.contains("needsClassification("), page);
        }
    }
}
