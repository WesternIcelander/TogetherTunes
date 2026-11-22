package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Song details in the web client ({@code docs/library-and-ingest.md#song-details}): runs
 * {@code src/test/resources/web-tests/songdetails.test.mjs} against the compiled
 * {@code web/js/songdetails.js}, and checks that the item page shows the details and edits the
 * artists as one list of names.
 */
class SongDetailsJsTest {
    @Test
    void songDetailsUnitTestsPass() throws Exception {
        NodeTests.run("songdetails.test.mjs", Map.of("SONGDETAILS_JS", "web/js/songdetails.js"));
    }

    @Test
    void itemPageUsesIt() throws Exception {
        String item = Files.readString(NodeTests.resource("web/js/item.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./songdetails.js\"", "detailRows(item)", "initialArtists(item)",
                "detailsBody(item, artists.names()", "moveName(", "removeName(", "addName(", "fieldOfError(")) {
            assertTrue(item.contains(s), s);
        }
        // one Artists field: the form never has a separate Artist input
        assertFalse(item.contains("name: \"artist\""), "no separate Artist field");
        assertTrue(item.contains("\"Artists\""));
    }
}
