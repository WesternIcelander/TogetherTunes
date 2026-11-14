package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drag and drop in the new UI (docs/ui.md#library-rules): runs the unit tests of the DOM-free
 * rules ({@code src/test/resources/web-tests/dragdrop.test.mjs}) against the compiled
 * {@code web/js/dragdrop.js}, and checks that the lists, the sidebar, the queue and Recents use
 * them (playlist order, entry moves, queue moves, file and link drops).
 */
class DragDropJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void dragDropUnitTestsPass() throws Exception {
        NodeTests.run("dragdrop.test.mjs", Map.of("DRAGDROP_JS", "web/js/dragdrop.js"));
    }

    @Test
    void dragDropRulesArePureLogic() throws Exception {
        String js = read("web/js/dragdrop.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void theListsTheSidebarAndTheQueueUseThem() throws Exception {
        String list = read("web/js/libview.js");
        for (String s : List.of("draggable(", "dropZone(", "playlistDrop(", "/entries/move", "dropFiles(", "openAddLink(",
                "draggedKeys(", "moveIsNoop(")) {
            assertTrue(list.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("draggable(", "dropZone(", "\"/api/playlists/order\"", "playlistOrderBody(", "queueDrop(",
                "queueShowsPosition(", "\"queue.add\"", "\"recents\"", "dropFiles(", "openAddLink(",
                "#selRows(", "draggedKeys(", "clickSelect(")) {
            assertTrue(frame.contains(s), s);
        }
        String files = read("web/js/filedrop.js");
        for (String s : List.of("new Upload(", "beforeComplete", "askType(", "allowLeave: true", "\"/entries\"")) {
            assertTrue(files.contains(s), s);
        }
        assertTrue(read("web/js/uploads.js").contains("beforeComplete"));
        assertTrue(read("web/js/classifyprompt.js").contains("Leave uncategorized"));
        assertTrue(read("web/js/addlink.js").contains("beforeEntryId"));
        assertTrue(read("web/css/main.css").contains(".drop-before"));
    }
}
