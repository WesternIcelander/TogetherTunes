package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Media types in the web client ({@code docs/media-types.md}): runs
 * {@code src/test/resources/web-tests/mediatypes.test.mjs} against the compiled
 * {@code web/js/mediatypes.js}, and checks that the item page edits the type with it.
 */
class MediaTypesJsTest {
    @Test
    void mediaTypesUnitTestsPass() throws Exception {
        NodeTests.run("mediatypes.test.mjs", Map.of("MEDIATYPES_JS", "web/js/mediatypes.js"));
    }

    @Test
    void itemPageEditsTheMediaType() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./mediatypes.js\"", "mediaTypeBody", "\"mediaType\"")) {
            assertTrue(item.contains(s), s);
        }
        assertTrue(!item.contains("body[\"presentAsAudio\"]"), "the PATCH no longer sends presentAsAudio");
    }
}
