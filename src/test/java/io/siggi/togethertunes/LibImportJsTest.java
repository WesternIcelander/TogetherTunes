package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The admin user page's "Import a music library" section ({@code docs/ui.md}, admin pages;
 * {@code docs/library-and-ingest.md#library-import}): runs {@code web-tests/libimport.test.mjs}
 * against the compiled {@code web/js/libimport.js} and checks that the page code is wired to the
 * import API and the zip upload and that the admin page mounts it.
 */
class LibImportJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void libImportUnitTestsPass() throws Exception {
        NodeTests.run("libimport.test.mjs", Map.of("LIBIMPORT_JS", "web/js/libimport.js"));
    }

    @Test
    void pageCodeUsesTheApi() throws Exception {
        String ui = read("web/js/libimportui.js");
        for (String s : List.of("\"/api/admin/library-import/uploads\"", "/library-import\"", "\"/start\"", "\"/cancel\"",
                "/problems", "/chunks/", "/complete\"", "\"DELETE\"", "localStorage", "Import a music library",
                "Upload a zip file", "Folder or zip on the server", "Also import smart playlists", "\"Check\"",
                "Cancel import", "from \"./libimport.js\"", "isConnected")) {
            assertTrue(ui.contains(s), s);
        }
        assertFalse(ui.contains("innerHTML"), "text only, never parsed as HTML");
        String admin = read("web/js/admin.js");
        assertTrue(admin.contains("from \"./libimportui.js\""), admin);
        assertTrue(admin.contains("mountLibraryImport(user.id, VIEW.signal)"), admin);
    }
}
