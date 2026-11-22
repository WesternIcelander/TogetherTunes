package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Linked media in the web client ({@code docs/library-and-ingest.md#linked-media}): runs
 * {@code src/test/resources/web-tests/links.test.mjs} against the compiled {@code web/js/links.js},
 * and checks that the item page shows the Linked media section and edits them with the details.
 */
class LinksJsTest {
    @Test
    void linksUnitTestsPass() throws Exception {
        NodeTests.run("links.test.mjs", Map.of("LINKS_JS", "web/js/links.js"));
    }

    @Test
    void itemPageUsesIt() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./links.js\"", "\"Linked media\"", "linkNames(", "addProblem(item.id",
                "moveLink(", "removeLink(", "linksBody(links)", "sameLinks(", "\"/links\"", "invalidLinkText(",
                "/api/imports/youtube", "/api/library?q=", "rel: \"noopener noreferrer\"")) {
            assertTrue(item.contains(s), s);
        }
    }
}
