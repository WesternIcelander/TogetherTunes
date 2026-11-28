package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The admin page's YouTube service box and audit log viewer: runs
 * {@code src/test/resources/web-tests/admin-tools.test.mjs} against the compiled
 * {@code web/js/youtubeservice.js} and {@code web/js/audit.js}, and checks the
 * admin page wires them to their APIs.
 */
class AdminToolsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void unitTestsPass() throws Exception {
        NodeTests.run("admin-tools.test.mjs", Map.of("AUDIT_JS", "web/js/audit.js",
                "YTSERVICE_JS", "web/js/youtubeservice.js"));
    }

    @Test
    void adminPageWiresTheYoutubeServiceBoxAndTheAuditTab() throws Exception {
        String admin = read("web/js/admin.js");
        for (String s : List.of("from \"./youtubeservice.js\"", "from \"./audit.js\"", "\"/api/admin/youtube-service\"",
                "\"/api/admin/youtube-service/update\"", "\"/api/admin/youtube-service/update-log\"",
                "\"/api/admin/youtube-service/test\"", "serviceTestText(", "versionLines(", "lastUpdateText(",
                "s:youtube.serviceUrl", "s:youtube.serviceToken", "auditPath(", "\"audit\"", "actorLabel(")) {
            assertTrue(admin.contains(s), s);
        }
        String page = NodeTests.view("view-admin");
        for (String s : List.of("data-tab=\"audit\"", "id=\"tab-audit\"", "id=\"audit-rows\"", "id=\"audit-filter\"",
                "id=\"audit-more\"", "id=\"ytservice-update\"", "id=\"ytservice-test\"", "id=\"ytservice-result\"",
                "id=\"ytservice-status\"", "id=\"ytservice-versions\"", "id=\"ytservice-log\"",
                "id=\"ytservice-log-button\"", "id=\"ytservice-last\"")) {
            assertTrue(page.contains(s), s);
        }
    }
}
