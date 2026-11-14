package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Login without reload (docs/ui.md#single-page-app): runs the unit tests of the provider and popup
 * helpers ({@code src/test/resources/web-tests/providers.test.mjs}) against the compiled
 * {@code web/js/providers.js} and of the guest merge ({@code guestmerge.test.mjs} against
 * {@code web/js/guestmerge.js}), and checks the wiring: the login views re-identify the page instead of
 * reloading it, provider logins open a popup from the click with a full-page fallback, the popup-done
 * page reports to its opener (same origin) and closes, the shell re-identifies the frame and the room
 * socket, the guest merge is offered in place, and logout still reloads.
 */
class LoginPopupJsTest {
    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    private static void assertContains(String text, List<String> parts) {
        for (String s : parts) {
            assertTrue(text.contains(s), s);
        }
    }

    @Test
    void providerUnitTestsPass() throws Exception {
        NodeTests.run("providers.test.mjs", Map.of("PROVIDERS_JS", "web/js/providers.js"));
    }

    @Test
    void guestMergeUnitTestsPass() throws Exception {
        NodeTests.run("guestmerge.test.mjs", Map.of("GUESTMERGE_JS", "web/js/guestmerge.js"));
    }

    @Test
    void providerHelpersArePureLogic() throws Exception {
        String js = read("web/js/providers.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "fetch(", "history.")) {
            assertFalse(js.contains(s), s);
        }
    }

    @Test
    void loginViewsReidentifyInPlace() throws Exception {
        assertContains(read("web/js/login.js"), List.of("await identityChanged()", "offerGuestMerge()",
                "window.open(providerPopupUrl(", "LOGIN_POPUP_NAME", "ev.preventDefault()", "addEventListener(\"message\"",
                "parseLoginMessage(ev.data, ev.origin, location.origin)", "new BroadcastChannel(LOGIN_CHANNEL)",
                "The login window was blocked", "providerStartUrl(p.startUrl, next)"));
        assertContains(read("web/js/register.js"), List.of("await identityChanged()", "offerGuestMerge()"));
        assertContains(read("web/js/setup.js"), List.of("await identityChanged()"));
        String popup = read("web/js/popupdone.js");
        assertContains(popup, List.of("opener.postMessage(msg, location.origin)", "new BroadcastChannel(LOGIN_CHANNEL)",
                "window.close()", "popupResult(location.search)"));
        assertFalse(popup.contains("\"*\""), "never posts to any origin");

        String session = read("web/js/session.js");
        assertFalse(session.contains("window.confirm("), "the guest merge is asked in place");
        assertContains(session, List.of("merge-prompt", "mergeGuest()", "location.assign(\"/\")"));

        assertContains(read("web/js/nav.js"), List.of("export async function identityChanged()", "setIdentityHook"));
        assertContains(read("web/js/shell.js"), List.of("setIdentityHook(() => this.#identityChanged())",
                "this.#frame.identityChanged()", "room.reidentify()"));
        assertContains(read("web/js/frame.js"), List.of("identityChanged() {"));
        assertContains(read("web/js/room.js"), List.of("reidentify,", "conn.reconnect()", "\"/api/me/play-session/join\""));
        assertContains(read("web/js/roomclient.js"), List.of("reconnect() {"));
        assertTrue(read("web/index.html").contains("id=\"provider-status\""));
    }
}
