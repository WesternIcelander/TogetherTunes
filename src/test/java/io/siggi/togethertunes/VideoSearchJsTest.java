package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Find a music video in the web client ({@code docs/library-and-ingest.md#linked-media}): runs
 * {@code src/test/resources/web-tests/videosearch.test.mjs} against the compiled
 * {@code web/js/videosearch.js} and checks that the module is pure logic (songmenu.ts draws it).
 */
class VideoSearchJsTest {
    @Test
    void videoSearchUnitTestsPass() throws Exception {
        NodeTests.run("videosearch.test.mjs", Map.of("VIDEOSEARCH_JS", "web/js/videosearch.js"));
    }

    @Test
    void itIsPureLogic() throws Exception {
        String js = Files.readString(NodeTests.resource("web/js/videosearch.js"), StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }
}
