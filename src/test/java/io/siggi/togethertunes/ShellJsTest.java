package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The room page's shell, which keeps listen-mode playback going while other pages of the site are
 * shown in place: runs the unit tests of its navigation rules
 * ({@code src/test/resources/web-tests/router.test.mjs}) against the compiled {@code web/js/router.js},
 * and checks that the pages it shows can be mounted into the room page (they start when imported
 * after the load, their ids don't clash with the room page's, their window listeners end with the
 * page, and their own navigations go through {@code navigate()}).
 */
class ShellJsTest {
    /** The pages the shell shows in place: HTML file → module. */
    private static final Map<String, String> PAGES = Map.of(
            "web/index.html", "web/js/main.js",
            "web/rooms/index.html", "web/js/rooms.js",
            "web/library/index.html", "web/js/library.js",
            "web/playlists/index.html", "web/js/playlists.js",
            "web/encodes/index.html", "web/js/encodes.js",
            "web/account/index.html", "web/js/account.js",
            "web/admin/index.html", "web/js/admin.js",
            "web/item.html", "web/js/item.js",
            "web/room-manage.html", "web/js/roommanage.js");

    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");

    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    private static Set<String> ids(String html) {
        Set<String> ids = new HashSet<>();
        Matcher m = ID.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    @Test
    void routerUnitTestsPass() throws Exception {
        NodeTests.run("router.test.mjs", Map.of("ROUTER_JS", "web/js/router.js"));
    }

    @Test
    void routerIsPureLogic() throws Exception {
        // The code only, not the comments (which talk about windows and the browser's location).
        String router = read("web/js/router.js").replaceAll("(?m)^\\s*(//|/?\\*).*$", "");
        for (String s : List.of("document.", "window.", "location.", "history.", "fetch(")) {
            assertFalse(router.contains(s), s);
        }
    }

    @Test
    void pagesCanBeShownInsideTheRoomPage() throws Exception {
        Set<String> roomIds = ids(read("web/room.html"));
        for (Map.Entry<String, String> e : PAGES.entrySet()) {
            String html = read(e.getKey());
            Set<String> clash = ids(html);
            clash.retainAll(roomIds);
            assertEquals(Set.of("site-nav"), clash, e.getKey());
            for (String id : ids(html)) {
                assertFalse(id.equals("shell-page") || id.startsWith("mini-"), e.getKey() + ": " + id);
            }
            String module = read(e.getValue());
            // Imported again after the load: must not wait for a DOMContentLoaded that already fired.
            assertTrue(module.contains("onReady(start)"), e.getValue());
            assertFalse(module.contains("addEventListener(\"DOMContentLoaded\""), e.getValue());
            // Listeners on window/document outlive the page unless they end with its signal.
            Matcher m = Pattern.compile("(window|document)\\.addEventListener\\(([^;]*)\\);").matcher(module);
            while (m.find()) {
                assertTrue(m.group(2).contains("signal: pageSignal()"), e.getValue() + ": " + m.group());
            }
            // Their own navigations go through navigate(), so they don't unload a playing room.
            assertFalse(module.contains("location.href ="), e.getValue());
            assertFalse(module.contains("location.reload("), e.getValue());
        }
        assertTrue(read("web/js/playlists.js").contains("navigate(playlistHref(p.id))"));
        String manage = read("web/js/roommanage.js");
        assertTrue(manage.contains("reloadPage()"), manage);
        assertTrue(manage.contains("navigate(\"/\")"), manage);
    }

    @Test
    void roomPageRunsTheShell() throws Exception {
        String room = read("web/js/room.js");
        for (String s : List.of("from \"./shell.js\"", "new Shell(", "shell?.update()", "rememberPlaying(null)",
                "new URLSearchParams(PAGE_SEARCH)", "shell?.away !== true")) {
            assertTrue(room.contains(s), s);
        }
        assertFalse(room.contains("new URLSearchParams(location.search)"), room);
        String shell = read("web/js/shell.js");
        for (String s : List.of("history.pushState(", "\"popstate\"", "DOMParser", "searchParams.set(\"mount\"",
                "import(moduleUrl.href)", "nextPageSignal()", "setNavigator(", "\"mini-player\"", "\"mini-leave\"",
                "\"mini-audio-only\"", "scrollRestoration", "id === \"room-listen-overlay\"")) {
            assertTrue(shell.contains(s), s);
        }
        String nav = read("web/js/nav.js");
        assertTrue(nav.contains("sessionStorage"), nav);
        String session = read("web/js/session.js");
        assertTrue(session.contains("showStoppedNote()"), session);
        assertTrue(read("web/css/main.css").contains(".mini-player {"));
    }
}
