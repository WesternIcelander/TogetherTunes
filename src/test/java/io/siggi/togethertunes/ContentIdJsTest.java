package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The upload pre-check in the browser ({@code docs/library-and-ingest.md#pre-check}): runs
 * {@code src/test/resources/web-tests/contentid.test.mjs} against the compiled
 * {@code web/js/contentid.js} (the same IDs as the server's {@code ContentId}, computed with
 * {@code crypto.subtle} piece by piece), and checks {@code uploads.js} sends the ID only where the
 * pre-check is available and takes a deduplicated answer as done.
 */
class ContentIdJsTest {
    @Test
    void contentIdUnitTestsPass() throws Exception {
        NodeTests.run("contentid.test.mjs", Map.of("CONTENTID_JS", "web/js/contentid.js"));
    }

    @Test
    void uploadsUseThePrecheck() throws Exception {
        String uploads = Files.readString(NodeTests.resource("web/js/uploads.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./contentid.js\"", "precheckAvailable()", "crypto.subtle", "contentId",
                "created.deduplicated === true", "\"checking\"")) {
            assertTrue(uploads.contains(s), s);
        }
        String contentId = Files.readString(NodeTests.resource("web/js/contentid.js"), StandardCharsets.UTF_8);
        assertTrue(contentId.contains("subtle.digest(\"SHA-256\""), "hashing through crypto.subtle only");
    }
}
