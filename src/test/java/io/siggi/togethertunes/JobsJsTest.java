package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The encode jobs page in the web UI ({@code docs/library-and-ingest.md#encode-jobs-page}): runs
 * {@code src/test/resources/web-tests/jobs.test.mjs} against the compiled {@code web/js/jobtext.js},
 * and checks that the admin page's Jobs tab and the Encodes page are wired to their APIs (the admin
 * one with Retry, Cancel and logs, the user one read only) and that the nav links the Encodes page.
 */
class JobsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    @Test
    void jobTextUnitTestsPass() throws Exception {
        NodeTests.run("jobs.test.mjs", Map.of("JOBTEXT_JS", "web/js/jobtext.js"));
    }

    @Test
    void adminTabAndEncodesPageUseTheirApis() throws Exception {
        String admin = read("web/js/admin.js");
        for (String s : List.of("from \"./jobs.js\"", "\"/api/admin/jobs\"", "admin: true", "\"jobs\"")) {
            assertTrue(admin.contains(s), s);
        }
        String adminPage = NodeTests.view("view-admin");
        assertTrue(adminPage.contains("data-tab=\"jobs\""), adminPage);
        assertTrue(adminPage.contains("id=\"tab-jobs\""), adminPage);
        assertTrue(adminPage.contains("id=\"jobs-root\""), adminPage);

        String encodes = read("web/js/encodes.js");
        for (String s : List.of("\"/api/jobs\"", "admin: false", "requireUser")) {
            assertTrue(encodes.contains(s), s);
        }
        String jobs = read("web/js/jobs.js");
        for (String s : List.of("/retry`", "/cancel`", "opts.admin ? actions(row, s) : null", "visibilityState",
                "signal: pageSignal()")) {
            assertTrue(jobs.contains(s), s);
        }
        assertFalse(jobs.contains("innerHTML"), "text only, never parsed as HTML");

        // the Request Link box and the queue use the same words for the lookup and ingest progress
        String room = read("web/js/room.js");
        for (String s : List.of("from \"./jobtext.js\"", "lookupText(stageText, Date.now() - started)",
                "window.clearInterval(ticker)")) {
            assertTrue(room.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        for (String s : List.of("from \"./jobtext.js\"", "ingestText(", "room.ingest()")) {
            assertTrue(frame.contains(s), s);
        }
        // a failed YouTube entry has a Retry button (queue.retry); the item page retries over REST
        assertTrue(read("web/js/panels.js").contains("canRetryEntry("));
        assertTrue(frame.contains("type: \"queue.retry\""));
        String item = read("web/js/item.js");
        for (String s : List.of("item.you.canRetry === true", "\"/retry\"", "can't be retried: upload the file again")) {
            assertTrue(item.contains(s), s);
        }
        String roomclient = read("web/js/roomclient.js");
        for (String s : List.of("case \"request.progress\"", "case \"ingest\"", "p.progress(")) {
            assertTrue(roomclient.contains(s), s);
        }
        // The room view's Request Link box shows yt-dlp's stages; queue entries show what ingest does.
        assertTrue(read("web/js/room.js").contains("lookupText("));
        assertTrue(read("web/js/frame.js").contains("ingestText("));
        assertTrue(read("web/js/item.js").contains("ingestBadge("));
        assertTrue(read("web/js/media.js").contains("ingestText("));

        String session = read("web/js/session.js");
        assertTrue(session.contains("\"/encodes/\"") && session.contains("\"Encodes\""), session);
    }
}
