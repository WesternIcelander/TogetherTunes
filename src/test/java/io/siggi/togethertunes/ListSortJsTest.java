package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sorting the lists by column ({@code docs/ui.md#library-rules}, "Sorting"): runs the unit tests of
 * the DOM-free rules ({@code src/test/resources/web-tests/listsort.test.mjs}) against the compiled
 * {@code web/js/listsort.js}, and checks that the lists use them (headings, API parameters, the
 * playlist's reordering only on #, the remembered sort).
 */
class ListSortJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void listSortUnitTestsPass() throws Exception {
        NodeTests.run("listsort.test.mjs", Map.of("LISTSORT_JS", "web/js/listsort.js"));
    }

    @Test
    void sortRulesArePureLogic() throws Exception {
        String js = read("web/js/listsort.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.", "localStorage")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void theListsUseThem() throws Exception {
        String list = read("web/js/libview.js");
        for (String s : List.of("sortParams(listSort)", "nextSort(", "ariaSort(", "\"aria-sort\"", "sortRows(",
                "ordered: canReorder(listSort)", "positioned: () => canReorder(listSort)", "sortStorageKey(",
                "localStorage.getItem(", "localStorage.setItem(", "sortableColumns(")) {
            assertTrue(list.contains(s), s);
        }
        String html = read("web/index.html");
        for (String s : List.of("data-col=\"num\"", "data-col=\"title artist\"", "data-col=\"album\"",
                "data-col=\"added\"", "data-col=\"duration\"")) {
            assertTrue(html.contains(s), s);
        }
        assertTrue(read("web/css/main.css").contains("button.sort-head"));
    }
}
