package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Looking song details up online in the web client ({@code docs/library-and-ingest.md#looking-details-up-online}):
 * runs {@code src/test/resources/web-tests/lookup.test.mjs} against the compiled {@code web/js/lookup.js},
 * and checks that the item page uses it only for those who may.
 */
class LookupJsTest {
    @Test
    void lookupUnitTestsPass() throws Exception {
        NodeTests.run("lookup.test.mjs", Map.of("LOOKUP_JS", "web/js/lookup.js"));
    }

    @Test
    void itemPageUsesIt() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./lookup.js\"", "reviewRows(item, r.details)", "applyBody(", "candidateText(c)",
                "coverChecked(item.hasCover)", "item.you.canLookUp === true", "\"Look up details\"", "/lookup\"",
                "/cover\"", "artworkPanel(item, onSaved)", "\"Replace artwork\"", "artworkProblem(file)",
                "ARTWORK_TYPES.join(\",\")")) {
            assertTrue(item.contains(s), s);
        }
        // the review shows the found cover from the server only
        assertFalse(item.contains("artworkUrl"), "never a catalogue's address");
    }
}
