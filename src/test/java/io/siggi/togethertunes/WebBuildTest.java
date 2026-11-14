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
        assertTrue(read("web/index.html").contains("src=\"/js/app.js\""));
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
        String page = read("web/index.html");
        assertTrue(page.contains("<template id=\"view-setup\""), page);
        assertTrue(read("web/js/router.js").contains("module: \"setup\""));
        assertTrue(read("web/js/setup.js").contains("from \"./api.js\""));
        assertTrue(read("web/js/api.js").contains("\"/api/setup\""));
    }

    @Test
    void accountPagesArePackaged() throws IOException {
        String index = read("web/index.html");
        String router = read("web/js/router.js");
        for (String page : List.of("login", "register", "password", "account")) {
            assertTrue(index.contains("<template id=\"view-" + page + "\""), page);
            assertTrue(router.contains("module: \"" + page + "\""), page);
            assertTrue(read("web/js/" + page + ".js").contains("from \"./session.js\""), page);
        }
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
        assertTrue(session.contains("\"/password\""), session);
        assertTrue(session.contains("\"/login\""), session);
        // Administration is linked for any admin.* permission.
        assertTrue(session.contains("\"admin.\""), session);
        assertTrue(read("web/js/login.js").contains("mustChangePassword"));
        for (String script : List.of("main", "admin", "account")) {
            assertTrue(read("web/js/" + script + ".js").contains("from \"./session.js\""), script);
        }
        assertTrue(read("web/js/admin.js").contains("requireUser"));
        assertTrue(read("web/js/account.js").contains("requireUser"));
        assertTrue(read("web/index.html").contains("<template id=\"view-admin\""));
    }

    @Test
    void libraryPagesArePackaged() throws IOException {
        Map<String, String> pages = Map.of(
                "view-library", "library",
                "view-playlists", "playlists",
                "view-encodes", "encodes",
                "view-item", "item",
                "view-room", "room",
                "view-rooms", "rooms");
        String index = read("web/index.html");
        String router = read("web/js/router.js");
        for (Map.Entry<String, String> e : pages.entrySet()) {
            assertTrue(index.contains("<template id=\"" + e.getKey() + "\""), e.getKey());
            assertTrue(router.contains("template: \"" + e.getKey() + "\", module: \"" + e.getValue() + "\""), e.getKey());
            assertTrue(read("web/js/" + e.getValue() + ".js").contains("from \"./session.js\""), e.getValue());
        }
        // Library and playlists need an account; the item page is open to anyone with the link.
        assertTrue(read("web/js/library.js").contains("requireUser"));
        assertTrue(read("web/js/playlists.js").contains("requireUser"));
        assertTrue(read("web/js/encodes.js").contains("requireUser"));
        assertFalse(read("web/js/item.js").contains("requireUser"));
        assertTrue(read("web/js/library.js").contains("\"/api/library?\""));
        assertTrue(read("web/js/item.js").contains("/api/items/"));
        // The room page is open to anyone with the link, and honours ?mode=remote from /r/{slug}.
        assertFalse(read("web/js/room.js").contains("requireUser"));
        assertTrue(read("web/js/room.js").contains("\"/api/rooms/\""));
        assertTrue(read("web/js/room.js").contains("\"remote\""));
        // The room page talks to the room over /ws: every action of docs/api.md#websocket the page
        // offers, the request search, and the guest identity flow.
        String room = read("web/js/room.js");
        for (String type : List.of("queue.add", "queue.addPlaylist", "queue.remove", "queue.move", "queue.pin",
                "vote", "suggest.accept", "suggest.reject", "play", "pause", "seek", "next", "previous",
                "voteSkip")) {
            assertTrue(room.contains("type: \"" + type + "\""), type);
        }
        assertTrue(room.contains("/search?"), room);
        assertTrue(room.contains("uploadId"), room);
        assertTrue(room.contains("youtubeUrl"), room);
        String client = read("web/js/roomclient.js");
        assertTrue(client.contains("\"/ws\""), client);
        assertTrue(client.contains("type: \"join\""), client);
        assertTrue(client.contains("type: \"sync\""), client); // a missed delta asks for the full state
        assertTrue(client.contains("\"/api/me/guest\""), client);
        assertTrue(client.contains("type: \"identity\""), client);
        assertTrue(client.contains("identity_required"), client);
        assertTrue(index.contains("id=\"room-queue\""));
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

    /**
     * CSS features newer than the browser baseline (Safari 15.0). An unsupported declaration or
     * rule is silently dropped (a whole rule for an unknown selector), so these would only show up
     * as wrong styling on the iOS 15 devices. Matched on the CSS with its comments removed.
     */
    private static final Map<String, Pattern> UNSUPPORTED_CSS = Map.ofEntries(
            Map.entry("color-mix() (Safari 16.2)", Pattern.compile("\\bcolor-mix\\s*\\(", Pattern.CASE_INSENSITIVE)),
            Map.entry("light-dark() (Safari 17.5)", Pattern.compile("\\blight-dark\\s*\\(", Pattern.CASE_INSENSITIVE)),
            Map.entry("@layer (Safari 15.4)", Pattern.compile("@layer\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("@container (Safari 16)", Pattern.compile("@container\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("@property (Safari 16.4)", Pattern.compile("@property\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("@scope (Safari 17.4)", Pattern.compile("@scope\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("@starting-style (Safari 17.5)", Pattern.compile("@starting-style\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("nesting & (Safari 16.5)", Pattern.compile("&")),
            Map.entry(":has() (Safari 15.4)", Pattern.compile(":has\\s*\\(", Pattern.CASE_INSENSITIVE)),
            Map.entry(":focus-visible (Safari 15.4)", Pattern.compile(":focus-visible\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry(":user-valid/:user-invalid (Safari 16.5)", Pattern.compile(":user-(in)?valid\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("overflow-wrap: anywhere (Safari 15.4)", Pattern.compile("overflow-wrap\\s*:\\s*anywhere", Pattern.CASE_INSENSITIVE)),
            Map.entry("accent-color (Safari 15.4)", Pattern.compile("(?<![-\\w])accent-color\\s*:", Pattern.CASE_INSENSITIVE)),
            Map.entry("text-wrap (Safari 17.4)", Pattern.compile("(?<![-\\w])text-wrap\\s*:", Pattern.CASE_INSENSITIVE)),
            Map.entry("subgrid (Safari 16)", Pattern.compile("\\bsubgrid\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("dynamic viewport units dvh/svh/lvh (Safari 15.4)",
                    Pattern.compile("\\d[dsl]v(h|w|i|b|min|max)\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("container query units cqw/cqh (Safari 16)",
                    Pattern.compile("\\dcq(w|h|i|b|min|max)\\b", Pattern.CASE_INSENSITIVE)),
            Map.entry("media query range syntax (Safari 16.4)",
                    Pattern.compile("@media[^{]*\\([^)]*[<>]", Pattern.CASE_INSENSITIVE)),
            Map.entry("CSS math functions round()/mod()/rem()/trig/pow() (Safari 15.4)", Pattern.compile(
                    "(?<![-\\w])(round|mod|rem|sin|cos|tan|asin|acos|atan|atan2|pow|sqrt|hypot|log|exp|abs|sign)\\s*\\(",
                    Pattern.CASE_INSENSITIVE)),
            Map.entry("LinkText/VisitedText/ActiveText system colours (unreliable in Safari 15)",
                    Pattern.compile("\\b(LinkText|VisitedText|ActiveText)\\b", Pattern.CASE_INSENSITIVE)));

    /** Returns one line per use of an {@link #UNSUPPORTED_CSS} feature in {@code css}, ignoring comments. */
    static List<String> unsupportedCssFeatures(String name, String css) {
        // Blank out comments but keep their newlines, so the line numbers stay right.
        Matcher comments = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(css);
        StringBuilder sb = new StringBuilder();
        while (comments.find()) {
            comments.appendReplacement(sb, Matcher.quoteReplacement(comments.group().replaceAll("[^\\n]", " ")));
        }
        comments.appendTail(sb);
        String code = sb.toString();
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : UNSUPPORTED_CSS.entrySet()) {
            Matcher m = e.getValue().matcher(code);
            while (m.find()) {
                int line = 1;
                for (int i = 0; i < m.start(); i++) {
                    if (code.charAt(i) == '\n') line++;
                }
                found.add(name + ":" + line + ": " + e.getKey());
            }
        }
        return found;
    }

    @Test
    void unsupportedCssCheckDetectsEachFeature() {
        for (String css : List.of(
                "a { border: 1px solid color-mix(in srgb, currentColor 25%, transparent); }",
                "a { color: light-dark(#000, #fff); }",
                "@layer base { a { color: red; } }",
                "@container (min-width: 30rem) { a { color: red; } }",
                "@property --x { syntax: '<length>'; inherits: false; initial-value: 0px; }",
                "@scope (.card) { a { color: red; } }",
                "@starting-style { a { opacity: 0; } }",
                ".a { .b & { color: red; } }",
                "li:has(> a) { color: red; }",
                "a:focus-visible { outline: 1px solid; }",
                "input:user-invalid { color: red; }",
                "p { overflow-wrap: anywhere; }",
                "input { accent-color: red; }",
                "h1 { text-wrap: balance; }",
                ".g { grid-template-columns: subgrid; }",
                ".o { height: 100dvh; }",
                ".o { width: 50cqw; }",
                "@media (width >= 40rem) { a { color: red; } }",
                "a { width: round(nearest, 10.5px, 1px); }",
                "button.link { color: LinkText; }")) {
            assertEquals(1, unsupportedCssFeatures("a.css", css).size(), css);
        }
        assertEquals(List.of("a.css:3: :has() (Safari 15.4)"),
                unsupportedCssFeatures("a.css", "/* :has( and\n color-mix( in a comment */\nli:has(a) {}"));
        // Fine in Safari 15.0: custom properties, prefers-color-scheme, aspect-ratio, inset, gap,
        // :is()/:where(), word-break, max-width media queries, rgba(), calc(), min()/max()/clamp().
        assertTrue(unsupportedCssFeatures("a.css", """
                :root { color-scheme: light dark; --link: #0b57d0; }
                @media (prefers-color-scheme: dark) { :root { --link: #8ab4f8; } }
                @media (max-width: 40rem) { .x { display: none; } }
                a:link, :is(a, b):where(.c) { color: var(--link); word-break: break-word; }
                .o { inset: 0; gap: 0.5rem; aspect-ratio: 1 / 1; border: 1px solid rgba(128, 128, 128, 0.4); }
                .w { width: calc(100% - 1rem); max-width: min(40rem, 90vw); font-size: clamp(1rem, 2vw, 2rem); }
                .r { border-radius: 0.5rem; text-decoration: underline; }
                """).isEmpty());
    }

    @Test
    void shippedCssStaysWithinBrowserBaseline() throws IOException, URISyntaxException {
        URL dir = WebBuildTest.class.getClassLoader().getResource("web/css");
        assertNotNull(dir, "web/css is missing from the classpath");
        Path root = Path.of(dir.toURI());
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".css")).sorted().toList()) {
                files++;
                String name = root.relativize(p).toString().replace('\\', '/');
                found.addAll(unsupportedCssFeatures(name, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(files >= 2, "expected main.css and test.css under " + root + ", found " + files + " .css files");
        assertFalse(found.size() > 0,
                "shipped CSS uses features newer than Safari 15.0:\n  " + String.join("\n  ", found));
    }

    @Test
    void linksHaveTheirOwnColoursInLightAndDarkMode() throws IOException {
        String css = read("web/css/main.css");
        for (String s : List.of("--link:", "--link-visited:", "--link-current:", "@media (prefers-color-scheme: dark)",
                "a:visited", "color: var(--link)", "nav a[aria-current=\"page\"]")) {
            assertTrue(css.contains(s), s);
        }
    }
}
