package io.siggi.togethertunes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Maven build put the web UI on the classpath under {@code /web/}: static
 * assets from {@code web/static/} and the tsc output of {@code web/src/} in {@code /web/js/}.
 */
class WebBuildTest {
    private static String read(String path) throws IOException {
        try (InputStream in = WebBuildTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void staticAssetsArePackaged() throws IOException {
        assertTrue(read("web/index.html").contains("src=\"/js/main.js\""));
        read("web/css/main.css");
    }

    @Test
    void typeScriptIsCompiledToEsModules() throws IOException {
        String main = read("web/js/main.js");
        // ES module output: the import survives as-is (no bundler, no CommonJS).
        assertTrue(main.contains("from \"./version.js\""), main);
        assertTrue(!main.contains("require("), main);
        read("web/js/version.js");
        read("web/js/main.js.map");
    }

    @Test
    void guestBadgeIsPackaged() throws IOException {
        assertTrue(read("web/js/actor.js").contains("badge-guest"));
        assertTrue(read("web/css/main.css").contains(".badge-guest"));
    }

    @Test
    void echoTestPageIsPackaged() throws IOException {
        String page = read("web/test/ws-echo.html");
        assertTrue(page.contains("src=\"/js/test/ws-echo.js\""), page);
        assertTrue(page.contains("href=\"/css/test.css\""), page);
        read("web/css/test.css");
        String script = read("web/js/test/ws-echo.js");
        assertTrue(script.contains("\"/ws/echo\""), script);
        assertTrue(read("web/index.html").contains("href=\"/test/ws-echo.html\""));
    }

    @Test
    void setupPageIsPackaged() throws IOException {
        String page = read("web/setup.html");
        assertTrue(page.contains("src=\"/js/setup.js\""), page);
        assertTrue(read("web/js/setup.js").contains("from \"./api.js\""));
        assertTrue(read("web/js/api.js").contains("\"/api/setup\""));
    }

    @Test
    void accountPagesArePackaged() throws IOException {
        for (String page : List.of("login", "register", "password")) {
            String html = read("web/" + page + ".html");
            assertTrue(html.contains("src=\"/js/" + page + ".js\""), html);
            assertTrue(html.contains("id=\"site-nav\""), html);
            assertTrue(read("web/js/" + page + ".js").contains("from \"./session.js\""), page);
        }
        String account = read("web/account/index.html");
        assertTrue(account.contains("src=\"/js/account.js\""), account);
        String accountJs = read("web/js/account.js");
        assertTrue(accountJs.contains("\"/api/me/sessions\""), accountJs);
        assertTrue(read("web/js/login.js").contains("\"/api/auth/login\""));
        assertTrue(read("web/js/register.js").contains("\"/api/auth/register\""));
        assertTrue(read("web/js/password.js").contains("\"/api/me/password\""));
    }

    @Test
    void pagesThatNeedAnAccountGoThroughTheLoginAndPasswordGate() throws IOException {
        String session = read("web/js/session.js");
        // A temporary password sends the user to the change-password page first.
        assertTrue(session.contains("mustChangePassword"), session);
        assertTrue(session.contains("\"/password.html\""), session);
        assertTrue(session.contains("\"/login.html\""), session);
        // Administration is linked for any admin.* permission.
        assertTrue(session.contains("\"admin.\""), session);
        assertTrue(read("web/js/login.js").contains("mustChangePassword"));
        for (String script : List.of("main", "admin", "account")) {
            assertTrue(read("web/js/" + script + ".js").contains("from \"./session.js\""), script);
        }
        assertTrue(read("web/js/admin.js").contains("requireUser"));
        assertTrue(read("web/js/account.js").contains("requireUser"));
        assertTrue(read("web/admin/index.html").contains("id=\"site-nav\""));
    }

    @Test
    void libraryPagesArePackaged() throws IOException {
        Map<String, String> pages = Map.of(
                "web/library/index.html", "library",
                "web/playlists/index.html", "playlists",
                "web/item.html", "item");
        for (Map.Entry<String, String> e : pages.entrySet()) {
            String html = read(e.getKey());
            assertTrue(html.contains("src=\"/js/" + e.getValue() + ".js\""), html);
            assertTrue(html.contains("id=\"site-nav\""), html);
            assertTrue(read("web/js/" + e.getValue() + ".js").contains("from \"./session.js\""), e.getValue());
        }
        // Library and playlists need an account; the item page is open to anyone with the link.
        assertTrue(read("web/js/library.js").contains("requireUser"));
        assertTrue(read("web/js/playlists.js").contains("requireUser"));
        assertFalse(read("web/js/item.js").contains("requireUser"));
        assertTrue(read("web/js/library.js").contains("\"/api/library?\""));
        assertTrue(read("web/js/item.js").contains("/api/items/"));
        assertTrue(read("web/js/media.js").contains("/job"));
        String uploads = read("web/js/uploads.js");
        assertTrue(uploads.contains("\"/api/uploads\""), uploads);
        assertTrue(uploads.contains("Content-Range"), uploads);
        assertTrue(uploads.contains("localStorage"), uploads); // resumable after a reload
        assertTrue(read("web/js/addmedia.js").contains("\"/api/imports/youtube\""));
        String session = read("web/js/session.js");
        assertTrue(session.contains("\"/library/\"") && session.contains("\"/playlists/\""), session);
    }

    /**
     * Features the browser baseline (iOS 15 / Safari 15.0, see docs/architecture.md) lacks and
     * tsc doesn't reject with {@code lib: ES2021}: class static blocks (Safari 16.4) pass through
     * with {@code target: ES2022}, and {@code structuredClone} comes from the DOM lib. The ES2022+
     * library calls are listed too, in case someone widens {@code lib} again.
     */
    private static final Map<String, Pattern> UNSUPPORTED = Map.of(
            "class static block (Safari 16.4)", Pattern.compile("\\bstatic\\s*\\{"),
            "Array/String .at() (Safari 15.4)", Pattern.compile("\\.at\\s*\\("),
            "Object.hasOwn (Safari 15.4)", Pattern.compile("\\bObject\\s*\\.\\s*hasOwn\\b"),
            "structuredClone (Safari 15.4)", Pattern.compile("\\bstructuredClone\\b"),
            "findLast/findLastIndex (Safari 15.4)", Pattern.compile("\\bfindLast(Index)?\\b"));

    /** Returns one line per use of an {@link #UNSUPPORTED} feature in {@code js}. */
    static List<String> unsupportedFeatures(String name, String js) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : UNSUPPORTED.entrySet()) {
            Matcher m = e.getValue().matcher(js);
            while (m.find()) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) {
                    if (js.charAt(i) == '\n') line++;
                }
                found.add(name + ":" + line + ": " + e.getKey());
            }
        }
        return found;
    }

    @Test
    void unsupportedFeatureCheckDetectsEachFeature() {
        assertEquals(1, unsupportedFeatures("a.js", "class A { static { init(); } }").size());
        assertEquals(1, unsupportedFeatures("a.js", "const last = list.at(-1);").size());
        assertEquals(1, unsupportedFeatures("a.js", "if (Object.hasOwn(o, \"k\")) {}").size());
        assertEquals(1, unsupportedFeatures("a.js", "const c = structuredClone(o);").size());
        assertEquals(1, unsupportedFeatures("a.js", "xs.findLast(f); ").size());
        assertEquals(1, unsupportedFeatures("a.js", "xs.findLastIndex(f);").size());
        assertEquals(List.of("a.js:2: class static block (Safari 16.4)"),
                unsupportedFeatures("a.js", "class A {\n  static {}\n}"));
        // Fine in Safari 15.0: static fields and methods, private members, top-level await.
        assertTrue(unsupportedFeatures("a.js", """
                class A { static x = 1; static f() {} #p = 2; #m() {} static #s = 3; }
                const r = await fetch("/"); const at = 1; o.atom(); format(at);
                """).isEmpty());
    }

    @Test
    void compiledJavaScriptStaysWithinBrowserBaseline() throws IOException, URISyntaxException {
        URL dir = WebBuildTest.class.getClassLoader().getResource("web/js");
        assertNotNull(dir, "web/js is missing from the classpath");
        Path root = Path.of(dir.toURI());
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".js")).sorted().toList()) {
                files++;
                String name = root.relativize(p).toString().replace('\\', '/');
                found.addAll(unsupportedFeatures(name, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(files >= 5, "expected the compiled web UI under " + root + ", found " + files + " .js files");
        assertFalse(found.size() > 0,
                "compiled JS uses features newer than Safari 15.0:\n  " + String.join("\n  ", found));
    }
}
