package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The remaining screens of the new UI (docs/ui.md#existing-features-in-the-new-ui): runs the unit tests
 * of the DOM-free preferences store and invite rules ({@code prefs.test.mjs}, {@code invites.test.mjs})
 * against the compiled modules, and checks that the views use them: the user settings' tabs
 * (preferences, account, sessions with API tokens, invites), debug mode's "Send diagnostics" at the top
 * of the page, Manage Room's outputs and bans, Next episode in the frame and the show → season → episode
 * view.
 */
class SettingsJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    private static void assertPure(String path) throws Exception {
        String js = read(path).replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.", "navigator.")) {
            assertFalse(js.contains(s), path + ": " + s);
        }
    }

    @Test
    void prefsUnitTestsPass() throws Exception {
        NodeTests.run("prefs.test.mjs", Map.of("PREFS_JS", "web/js/prefs.js"));
    }

    @Test
    void invitesUnitTestsPass() throws Exception {
        NodeTests.run("invites.test.mjs", Map.of("INVITES_JS", "web/js/invites.js"));
    }

    @Test
    void theRulesArePureLogic() throws Exception {
        assertPure("web/js/prefs.js");
        assertPure("web/js/invites.js");
    }

    @Test
    void userSettingsHaveTheirTabs() throws Exception {
        String html = NodeTests.view("view-account");
        for (String s : List.of("href=\"#preferences\"", "href=\"#account\"", "href=\"#sessions\"", "href=\"#invites\"",
                "id=\"acct-tab-preferences\"", "id=\"acct-tab-account\"", "id=\"acct-tab-sessions\"",
                "id=\"acct-tab-invites\"", "id=\"pref-doubleClickAlone\"", "id=\"pref-webAudio\"", "id=\"pref-debug\"",
                "id=\"token-create\"", "id=\"invite-create\"", "id=\"identity-rows\"", "id=\"session-rows\"")) {
            assertTrue(html.contains(s), s);
        }
        String account = read("web/js/account.js");
        for (String s : List.of("from \"./prefsclient.js\"", "prefs.set(", "\"/api/auth/token\"", "\"/api/invites?\"",
                "\"/api/invites\"", "\"/api/invites/\"", "inviteBody(", "inviteStatusText(", "canRevoke(", "copyField(",
                "\"/api/me/sessions\"", "\"/api/me/identities\"", "canInvite(me)")) {
            assertTrue(account.contains(s), s);
        }
        // The token and the invite code are shown once and never stored in the page.
        for (String s : List.of("localStorage", "sessionStorage")) {
            assertFalse(account.contains(s), s);
        }
        String store = read("web/js/prefsclient.js");
        for (String s : List.of("\"GET\", \"/api/me/settings\"", "\"PATCH\", \"/api/me/settings\"")) {
            assertTrue(store.contains(s), s);
        }
        for (String path : List.of("web/js/session.js", "web/js/frame.js")) {
            String js = read(path);
            assertTrue(js.contains("canInvite(me)"), path);
            assertTrue(js.contains("INVITES_PAGE"), path);
        }
    }

    @Test
    void debugModeShowsSendDiagnosticsAtTheTop() throws Exception {
        String index = read("web/index.html");
        int header = index.indexOf("<header class=\"app-header\">");
        int debug = index.indexOf("id=\"app-debug\"");
        assertTrue(header >= 0 && debug > header && debug < index.indexOf("</header>"), "in the header");
        String frame = read("web/js/frame.js");
        for (String s : List.of("prefs.subscribe(", "p.debug", "prefs.load()", "prefs.reset()", "buildReport(",
                "\"/api/diagnostics\"")) {
            assertTrue(frame.contains(s), s);
        }
        // The room view no longer has its own Web Audio select or diagnostics button.
        String room = NodeTests.view("view-room");
        for (String s : List.of("id=\"room-webaudio\"", "id=\"room-diagnostics\"")) {
            assertFalse(room.contains(s), s);
        }
    }

    @Test
    void manageRoomHasOutputsBansAndLeave() throws Exception {
        String page = read("web/js/roommanage.js");
        for (String s : List.of("new OutputsPanel(element(\"outputs-root\"))", "currentRoom()", "\"/bans?\"",
                "\"Unban\"", "showMembership(", "\"Leave the room\"")) {
            assertTrue(page.contains(s), s);
        }
        // The room view keeps only the viewer URL and the quick buttons.
        String room = read("web/js/room.js");
        assertTrue(room.contains("new OutputsPanel(element(\"room-outputs\"), element(\"rv-outputs\"), false)"));
        String panel = read("web/js/outputspanel.js");
        assertTrue(panel.contains("#showManage"));
        String html = NodeTests.view("view-room-manage");
        for (String s : List.of("href=\"#outputs\"", "id=\"tab-outputs\"", "id=\"ban-table\"")) {
            assertTrue(html.contains(s), s);
        }
        String frame = read("web/js/frame.js");
        assertTrue(frame.contains("\"Membership\""));
    }

    @Test
    void nextEpisodeAndShows() throws Exception {
        String frame = read("web/js/frame.js");
        for (String s : List.of("offersNextEpisode(", "\"queue.nextEpisode\"", "#nextEpisodeButton(")) {
            assertTrue(frame.contains(s), s);
        }
        String list = read("web/js/libview.js");
        for (String s : List.of("showOneShow(", "\"/api/library/shows?name=\"", "seasonLabel(", "\"/shows?show=\"",
                "season-row", "loadFailed(", "\"Try again\"")) {
            assertTrue(list.contains(s), s);
        }
    }
}
