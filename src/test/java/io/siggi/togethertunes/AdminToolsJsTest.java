package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The admin page's yt-dlp update and audit log viewer: runs
 * {@code src/test/resources/web-tests/admin-tools.test.mjs} against the compiled {@code web/js/ytdlp.js}
 * and {@code web/js/audit.js}, and checks the admin page wires them to their APIs.
 */
class AdminToolsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void unitTestsPass() throws Exception {
        NodeTests.run("admin-tools.test.mjs", Map.of("YTDLP_JS", "web/js/ytdlp.js", "AUDIT_JS", "web/js/audit.js"));
    }

    @Test
    void adminPageWiresTheYtdlpBoxAndTheAuditTab() throws Exception {
        String admin = read("web/js/admin.js");
        for (String s : List.of("from \"./ytdlp.js\"", "from \"./audit.js\"", "\"/api/admin/ytdlp\"",
                "\"/api/admin/ytdlp/update\"", "auditPath(", "\"audit\"", "actorLabel(")) {
            assertTrue(admin.contains(s), s);
        }
        String page = read("web/admin/index.html");
        for (String s : List.of("data-tab=\"audit\"", "id=\"tab-audit\"", "id=\"audit-rows\"", "id=\"audit-filter\"",
                "id=\"audit-more\"", "id=\"ytdlp-update\"", "id=\"ytdlp-version\"", "id=\"ytdlp-log\"")) {
            assertTrue(page.contains(s), s);
        }
    }
}
