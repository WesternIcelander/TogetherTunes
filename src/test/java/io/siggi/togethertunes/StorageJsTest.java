package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Free disk space in the web UI ({@code docs/library-and-ingest.md#free-disk-space}): runs
 * {@code src/test/resources/web-tests/storage.test.mjs} against the compiled {@code web/js/storage.js}
 * (GiB input and the admin storage box) and {@code web/js/uploads.js} (a 507 stops an upload
 * without retrying), and checks the admin page wires them up.
 */
class StorageJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void storageUnitTestsPass() throws Exception {
        NodeTests.run("storage.test.mjs", Map.of("STORAGE_JS", "web/js/storage.js", "UPLOADS_JS", "web/js/uploads.js",
                "API_JS", "web/js/api.js"));
    }

    @Test
    void adminPageShowsTheStorageBox() throws Exception {
        String admin = read("web/js/admin.js");
        for (String s : List.of("from \"./storage.js\"", "/api/admin/storage", "case \"bytes\"", "storageText(",
                "parseGib(", "formatGib(")) {
            assertTrue(admin.contains(s), s);
        }
        String page = NodeTests.view("view-admin");
        for (String s : List.of("id=\"storage-line\"", "id=\"storage-warning\"")) {
            assertTrue(page.contains(s), s);
        }
    }
}
