package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The single-page app's shell (docs/ui.md#single-page-app): runs the unit tests of its routes and
 * navigation rules ({@code src/test/resources/web-tests/router.test.mjs}) against the compiled
 * {@code web/js/router.js}, and checks that index.html holds every view the router names, and that
 * the views can be mounted in place (they start when imported after the load, their ids don't
 * clash with the room view's, which may stay in the document, their window listeners end with the
 * view, and their own navigations go through {@code navigate()}).
 */
class ShellJsTest {
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern VIEW = Pattern.compile(
            "(\\w+): \\{ template: \"(view-[a-z-]+)\"(?:, module: \"(\\w+)\")?");
    private static final Pattern TEMPLATE = Pattern.compile(
            "<template id=\"(view-[a-z-]+)\" data-title=\"[^\"]+\">(.*?)</template>", Pattern.DOTALL);

    private static String read(String path) throws Exception {
        return Files.readString(NodeTests.resource(path), StandardCharsets.UTF_8);
    }

    private static Set<String> ids(String html) {
        Set<String> ids = new HashSet<>();
        Matcher m = ID.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    /** index.html's templates: id → markup. */
    private static Map<String, String> templates() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = TEMPLATE.matcher(read("web/index.html"));
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    /** The router's views: template → module (null for none). */
    private static Map<String, String> views() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = VIEW.matcher(read("web/js/router.js"));
        while (m.find()) out.put(m.group(2), m.group(3));
        return out;
    }

    /** The argument list starting at the {@code (} at {@code open}, up to its matching {@code )}. */
    private static String callAt(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return code.substring(open, i + 1);
        }
        return code.substring(open);
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
    void indexHtmlIsTheOneDocumentWithEveryView() throws Exception {
        String index = read("web/index.html");
        assertEquals(1, index.split("<script", -1).length - 1, index);
        assertTrue(index.contains("<script type=\"module\" src=\"/js/app.js\"></script>"));
        assertTrue(index.contains("<nav id=\"site-nav\""));
        assertTrue(index.contains("<main id=\"app\"></main>"));
        Map<String, String> views = views();
        assertEquals(14, views.size(), views.toString());
        Map<String, String> templates = templates();
        assertEquals(views.keySet(), templates.keySet());
        for (Map.Entry<String, String> e : views.entrySet()) {
            if (e.getValue() != null) {
                assertNotNull(NodeTests.resource("web/js/" + e.getValue() + ".js"), e.getValue());
            }
            assertFalse(templates.get(e.getKey()).contains("<script"), e.getKey());
        }
        // The old multi-page documents are gone; /test/ keeps its own pages.
        for (String old : List.of("web/room.html", "web/item.html", "web/login.html", "web/rooms/index.html",
                "web/library/index.html", "web/admin/index.html")) {
            assertTrue(getClass().getClassLoader().getResource(old) == null, old);
        }
        assertNotNull(getClass().getClassLoader().getResource("web/test/ws-echo.html"));
    }

    @Test
    void viewsCanBeMountedNextToTheRoomView() throws Exception {
        Map<String, String> templates = templates();
        Set<String> roomIds = ids(templates.get("view-room"));
        assertTrue(roomIds.contains("room-body") && roomIds.contains("rv-video-slot"), roomIds.toString());
        // The frame's ids (header, sidebars, right panel, video panel, player bar) live outside the
        // templates and must not clash with a view's.
        String frame = TEMPLATE.matcher(read("web/index.html")).replaceAll("");
        Set<String> shellIds = new HashSet<>(ids(frame));
        shellIds.add("stopped-note");
        for (String id : List.of("site-nav", "app", "app-sidebar", "app-panel", "app-video", "room-player",
                "player-bar", "pb-play", "pb-volume", "pb-menu", "pb-audio-only", "pb-requests-only", "pb-now-view",
                "pb-fullscreen", "pb-mute-menu", "app-search", "room-listen-overlay")) {
            assertTrue(shellIds.contains(id), id);
        }
        for (Map.Entry<String, String> view : views().entrySet()) {
            Set<String> ids = ids(templates.get(view.getKey()));
            for (String id : ids) {
                assertFalse(shellIds.contains(id) || id.startsWith("app-") || id.startsWith("pb-")
                        || id.startsWith("view-"), view.getKey() + ": " + id);
            }
            if (view.getValue() == null) continue;
            String module = read("web/js/" + view.getValue() + ".js");
            if (!view.getKey().equals("view-room")) {
                Set<String> clash = new HashSet<>(ids);
                clash.retainAll(roomIds);
                assertEquals(Set.of(), clash, view.getKey());
                // Their own navigations go through navigate(), so they don't unload a playing room.
                assertFalse(module.contains("location.reload("), view.getValue());
            }
            // Imported after the load: must not wait for a DOMContentLoaded that already fired.
            assertTrue(module.contains("onReady(start)"), view.getValue());
            assertFalse(module.contains("addEventListener(\"DOMContentLoaded\""), view.getValue());
            assertFalse(module.contains("location.href ="), view.getValue());
            // Listeners on window/document outlive the view unless they end with its signal.
            Matcher m = Pattern.compile("(window|document)\\.addEventListener\\(").matcher(module);
            while (m.find()) {
                String call = callAt(module, m.end() - 1);
                assertTrue(call.contains("signal: VIEW.signal") || call.contains("signal: ROOM_SIGNAL"),
                        view.getValue() + ": " + call);
            }
            // The view's signal and path are taken at the module's start (after an await,
            // pageSignal() and location may be the next view's), and its elements are its own.
            if (!view.getKey().equals("view-room")) {
                assertTrue(module.contains("const VIEW = viewScope(import.meta.url)"), view.getValue());
                assertFalse(module.contains("pageSignal()"), view.getValue());
                assertFalse(module.contains("location.pathname)"), view.getValue());
                assertFalse(module.contains("new URLSearchParams(location.search)"), view.getValue());
                // (the frame's #app, a drop zone for the whole middle, is the one element outside a view)
                assertFalse(module.replace("document.getElementById(\"app\")", "").contains("document.getElementById("),
                        view.getValue() + " (VIEW.element)");
            }
        }
        String manage = read("web/js/roommanage.js");
        assertTrue(manage.contains("reloadPage()"), manage);
        assertTrue(manage.contains("navigate(\"/\")"), manage);
        String session = read("web/js/session.js");
        assertTrue(session.contains("navigate(withNext(LOGIN_PAGE, scope !== undefined ? scope.path + scope.search + location.hash : here()), true)"), session);
        assertTrue(session.contains("if (scope?.signal.aborted === true)"), session);
        assertTrue(session.contains("navigate(withNext(PASSWORD_PAGE, here()), true)"), session);
    }

    /**
     * Views stop when the shell takes them away (the song page fight): {@code viewscope.test.mjs}
     * runs nav.ts {@code viewScope} (elements inside the view only, timers ended by its signal) and
     * media.ts {@code watchItem} (stops polling and reporting on abort) with fake timers and fetch;
     * the item page and the jobs lists poll with the view's signal.
     */
    @Test
    void viewsStopWhenTakenAway() throws Exception {
        NodeTests.run("viewscope.test.mjs", Map.of("NAV_JS", "web/js/nav.js", "MEDIA_JS", "web/js/media.js"));
        String item = read("web/js/item.js");
        for (String s : List.of("}, SIGNAL);", "const element = VIEW.element;", "player?.pause()",
                "exec(VIEW.path)")) {
            assertTrue(item.contains(s), s);
        }
        assertTrue(read("web/js/shell.js").contains("signal: controller.signal, root: el }"));
        String jobs = read("web/js/jobs.js");
        assertFalse(jobs.contains("pageSignal"), jobs);
        assertTrue(read("web/js/encodes.js").contains("signal: VIEW.signal"));
        assertTrue(read("web/js/admin.js").contains("signal: VIEW.signal"));
    }

    @Test
    void shellMountsViewsAndKeepsThePlayingRoom() throws Exception {
        String room = read("web/js/room.js");
        for (String s : List.of("attachRoom(", "const ROOM_SIGNAL = START?.signal ?? pageSignal()", "shell?.update()",
                "rememberPlaying(null)", "new URLSearchParams(PAGE_SEARCH)", "shell?.away !== true",
                "player?.dispose()", "conn.stop()", "clock?.stop()")) {
            assertTrue(room.contains(s), s);
        }
        assertFalse(room.contains("new URLSearchParams(location.search)"), room);
        assertFalse(room.contains("window.setInterval(tick"), room);
        String shell = read("web/js/shell.js");
        for (String s : List.of("history.pushState(", "\"popstate\"", "searchParams.set(\"mount\"",
                "import(moduleUrl.href)", "startPageSignal()", "setNavigator(", "setRoomHost(", "new Frame(",
                "#followed()", "scrollRestoration", "HTMLTemplateElement",
                "controller.abort()")) {
            assertTrue(shell.contains(s), s);
        }
        assertFalse(shell.contains("DOMParser"), shell);
        assertTrue(read("web/js/app.js").contains("new Shell("));
        String nav = read("web/js/nav.js");
        assertTrue(nav.contains("sessionStorage"), nav);
        String session = read("web/js/session.js");
        assertTrue(session.contains("showStoppedNote()"), session);
        assertTrue(read("web/js/listen.js").contains("dispose()"));
        assertTrue(read("web/css/main.css").contains(".player-bar {"));
        // The frame follows the room view's room through nav.ts's ShellRoom: no socket of its own.
        String frame = read("web/js/frame.js");
        for (String s : List.of("setFrame(", "room.request(", "room.setVolume(", "\"queue.request\"", "\"queue.remove\"",
                "\"queue.retry\"", "\"suggest.accept\"", "\"voteSkip\"", "\"previous\"", "\"seek\"",
                "now-view-open", "requestFullscreen", "\"/api/me/likes/\"", "\"/api/library/uncategorized?limit=1\"",
                "/autoplay\"", "\"/api/rooms?\"")) {
            assertTrue(frame.contains(s), s);
        }
        assertFalse(frame.contains("new RoomConnection("), frame);
        assertFalse(frame.contains("new WebSocket("), frame);
        assertTrue(read("web/js/room.js").contains("playerContainer:"));
    }
}
