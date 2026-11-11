package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The admin page's Fonts tab ({@code docs/library-and-ingest.md#card-fonts}): runs
 * {@code src/test/resources/web-tests/fonts.test.mjs} against the compiled {@code web/js/fonts.js}
 * and checks the admin page wires it up.
 */
class FontsJsTest {
    @Test
    void fontsUnitTestsPass() throws Exception {
        NodeTests.run("fonts.test.mjs", Map.of("FONTS_JS", "web/js/fonts.js"));
    }

    @Test
    void adminPageHasTheFontsTab() throws Exception {
        String admin = Files.readString(NodeTests.resource("web/js/admin.js"), StandardCharsets.UTF_8);
        for (String s : List.of("from \"./fonts.js\"", "/api/admin/fonts", "/api/admin/fonts/download-all",
                "/api/admin/fonts/uploads", "\"cards.font\"", "\"fonts.autoDownload\"", "\"fonts.cjkVariant\"")) {
            assertTrue(admin.contains(s), s);
        }
        String page = Files.readString(NodeTests.resource("web/admin/index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("data-tab=\"fonts\"") && page.contains("id=\"fonts-root\""));
    }
}
