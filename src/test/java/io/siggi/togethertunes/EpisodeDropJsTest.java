package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Video files dropped on Shows ({@code docs/ui.md#library-rules}, "Drag and drop"): runs the unit
 * tests of the dialog's DOM-free logic ({@code src/test/resources/web-tests/episodedrop.test.mjs})
 * against the compiled {@code web/js/episodedrop.js}, and checks that the drops on the library views
 * use it (filedrop.js), the sidebar and the lists take them, and Add from link gets the type preset.
 */
class EpisodeDropJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void episodeDropUnitTestsPass() throws Exception {
        NodeTests.run("episodedrop.test.mjs", Map.of("EPISODEDROP_JS", "web/js/episodedrop.js"));
    }

    @Test
    void episodeLogicIsPure() throws Exception {
        String js = read("web/js/episodedrop.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.", "(?<")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void theLibraryViewsUseIt() throws Exception {
        String files = read("web/js/filedrop.js");
        for (String s : List.of("export function dropFilesInLibrary(", "libraryDropPlan(", "episodeRows(", "episodeBodies(",
                "fillShowName(", "beforeComplete", "\"/api/library/\"")) {
            assertTrue(files.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("libraryTargetOf(", "dropFilesInLibrary(", "openAddLinkTo(")) {
            assertTrue(frame.contains(s), s);
        }
        String list = read("web/js/libview.js");
        for (String s : List.of("libraryDropZone(\"songs\")", "libraryDropZone(\"movies\")", "libraryDropZone(\"videos\")",
                "libraryDropZone(\"uncategorized\")", "libraryDropZone(\"shows\")", "dropFilesInLibrary(", "openAddLinkTo(")) {
            assertTrue(list.contains(s), s);
        }
        String link = read("web/js/addlink.js");
        for (String s : List.of("libraryLinkPreset(", "showFields", "mediaTypeBody(\"show\"")) {
            assertTrue(link.contains(s), s);
        }
    }
}
