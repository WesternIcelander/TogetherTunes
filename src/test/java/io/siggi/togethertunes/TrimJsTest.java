package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The video trim timeline ({@code docs/library-and-ingest.md#trim}): runs
 * {@code src/test/resources/web-tests/trim.test.mjs} against the compiled {@code web/js/trim.js}, and
 * checks that the item page uses it and the trim API.
 */
class TrimJsTest {
    @Test
    void trimUnitTestsPass() throws Exception {
        NodeTests.run("trim.test.mjs", Map.of("TRIM_JS", "web/js/trim.js"));
    }

    @Test
    void itemPageUsesTheTrimModule() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./trim.js\"", "\"/trim\"", "estimateText", "previewSpan",
                "audioTrimBody", "Trim audio for audio-only rooms", "Follow the video trim")) {
            assertTrue(item.contains(s), s);
        }
        // the handles move freely: no snapping to keyframes any more (every start change is smart)
        assertTrue(!item.contains("Snap to keyframes") && !item.contains("snapToGrid"), "no snapping");
    }
}
