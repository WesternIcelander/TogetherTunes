package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StaticFiles} on a real loopback server, through our {@link Router}. Requests go over a
 * raw socket so the request line (e.g. {@code /../secret.txt}) reaches the server exactly as
 * written.
 */
class StaticFilesTest {
    private static final String INDEX = "<!doctype html><title>t</title>";
    private static final String CSS = "body { color: red; }";
    private static final String JS = "export const x = 1;";
    private static final String SECRET = "top secret";
    private static final long MTIME = 1_700_000_000_000L;

    @TempDir
    Path tmp;

    private Path root;
    private WebServer server;

    /** A parsed HTTP/1.1 response. */
    private record Reply(int status, Map<String, String> headers, byte[] body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("web");
        write(root.resolve("index.html"), INDEX);
        write(root.resolve("css/main.css"), CSS);
        write(root.resolve("js/main.js"), JS);
        write(root.resolve("js/main.js.map"), "{}");
        write(root.resolve("img/logo.svg"), "<svg/>");
        write(root.resolve("img/photo.png"), "png");
        write(root.resolve("data.bin"), "bin");
        write(root.resolve("noext"), "x");
        write(root.resolve("docs/index.html"), "docs index");
        write(root.resolve("my dir/index.html"), "spaced");
        write(root.resolve("nodir/file.txt"), "no index here");
        write(root.resolve(".hidden"), "hidden");
        write(tmp.resolve("secret.txt"), SECRET);
        Files.createSymbolicLink(root.resolve("escape.txt"), tmp.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("escapedir"), tmp);
        Files.createSymbolicLink(root.resolve("inside.css"), root.resolve("css/main.css"));

        Router router = new Router();
        StaticFiles files = StaticFiles.of(root);
        router.get("/items/{id}", files.page("index.html"));
        router.get("/gone/{id}", files.page("missing.html"));
        router.get("/out/{id}", files.page("../secret.txt"));
        router.get("/hidden/{id}", files.page(".hidden"));
        files.register(router);
        router.get("/api/ping", (rq, rs) -> rs.text(200, "pong"));
        server = WebServer.start("127.0.0.1", 0, router, tmp.toFile());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(MTIME));
    }

    private Reply request(String method, String target, String... headerLines) throws IOException {
        return request(method, target, null, headerLines);
    }

    private Reply request(String method, String target, byte[] body, String... headerLines) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
            head.append("Host: 127.0.0.1:").append(server.port()).append("\r\n");
            head.append("Connection: close\r\n");
            for (String line : headerLines) {
                head.append(line).append("\r\n");
            }
            if (body != null) {
                head.append("Content-Length: ").append(body.length).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            return parse(socket.getInputStream().readAllBytes(), method.equals("HEAD"));
        }
    }

    private static Reply parse(byte[] raw, boolean head) {
        int end = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                end = i;
                break;
            }
        }
        assertTrue(end > 0, "No complete response: " + new String(raw, StandardCharsets.ISO_8859_1));
        String[] lines = new String(raw, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        byte[] rest = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
        if (head) {
            return new Reply(status, headers, rest);
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            rest = dechunk(rest);
        }
        return new Reply(status, headers, rest);
    }

    private static byte[] dechunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (true) {
            int lineEnd = pos;
            while (data[lineEnd] != '\r') {
                lineEnd++;
            }
            int size = Integer.parseInt(new String(data, pos, lineEnd - pos, StandardCharsets.ISO_8859_1).split(";")[0].trim(), 16);
            pos = lineEnd + 2;
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(data, pos, size);
            pos += size + 2;
        }
    }

    private void assertServed(String target, String type, String content) throws IOException {
        Reply r = request("GET", target);
        assertEquals(200, r.status(), target + " " + r.headers());
        assertEquals(type, r.header("Content-Type"), target);
        assertEquals(content, r.text(), target);
        assertEquals(String.valueOf(content.getBytes(StandardCharsets.UTF_8).length), r.header("Content-Length"), target);
        assertEquals("no-cache", r.header("Cache-Control"), target);
        assertEquals("nosniff", r.header("X-Content-Type-Options"), target);
        assertEquals("bytes", r.header("Accept-Ranges"), target);
        assertNotNull(r.header("ETag"), target);
        assertNotNull(r.header("Last-Modified"), target);
    }

    private void assertNotServed(String target) throws IOException {
        Reply r = request("GET", target);
        assertTrue(r.status() == 404 || r.status() == 400, target + " -> " + r.status() + " " + r.headers());
        assertFalse(r.text().contains(SECRET), target);
        assertFalse(r.text().contains("hidden"), target);
    }

    // ---- content types, index, caching ----

    @Test
    void servesFilesWithContentTypes() throws IOException {
        assertServed("/", "text/html; charset=utf-8", INDEX);
        assertServed("/index.html", "text/html; charset=utf-8", INDEX);
        assertServed("/css/main.css", "text/css; charset=utf-8", CSS);
        assertServed("/js/main.js", "text/javascript; charset=utf-8", JS);
        assertServed("/js/main.js.map", "application/json; charset=utf-8", "{}");
        assertServed("/img/logo.svg", "image/svg+xml; charset=utf-8", "<svg/>");
        assertServed("/img/photo.png", "image/png", "png");
        assertServed("/data.bin", "application/octet-stream", "bin");
        assertServed("/noext", "application/octet-stream", "x");
        assertServed("/docs/", "text/html; charset=utf-8", "docs index");
        assertServed("/my%20dir/", "text/html; charset=utf-8", "spaced");
        assertServed("/inside.css", "text/css; charset=utf-8", CSS); // symlink inside the root
        assertServed("/js/main.js?v=123", "text/javascript; charset=utf-8", JS);
    }

    @Test
    void contentTypeTable() {
        assertEquals("text/javascript; charset=utf-8", StaticFiles.contentType("A.MJS"));
        assertEquals("font/woff2", StaticFiles.contentType("f.woff2"));
        assertEquals("application/octet-stream", StaticFiles.contentType("Makefile"));
        assertEquals("application/octet-stream", StaticFiles.contentType("x.unknownext"));
    }

    @Test
    void moreSpecificRoutesWin() throws IOException {
        Reply r = request("GET", "/api/ping");
        assertEquals(200, r.status());
        assertEquals("pong", r.text());
    }

    @Test
    void directoryWithoutSlashRedirects() throws IOException {
        Reply r = request("GET", "/docs");
        assertEquals(308, r.status());
        assertEquals("/docs/", r.header("Location"));
        r = request("GET", "/my%20dir");
        assertEquals(308, r.status());
        assertEquals("/my%20dir/", r.header("Location"));
        // A directory without index.html is just not found (no listings).
        assertNotServed("/nodir");
        assertNotServed("/nodir/");
    }

    @Test
    void missingFilesAre404() throws IOException {
        Reply r = request("GET", "/nope.js");
        assertEquals(404, r.status());
        assertEquals(Response.JSON, r.header("Content-Type"));
        assertNotServed("/css/nope.css");
        assertNotServed("/index.html/extra");
    }

    @Test
    void conditionalRequestsGet304() throws IOException {
        Reply first = request("GET", "/css/main.css");
        String etag = first.header("ETag");
        Reply byEtag = request("GET", "/css/main.css", "If-None-Match: " + etag);
        assertEquals(304, byEtag.status());
        assertEquals(0, byEtag.body().length);
        assertEquals("no-cache", byEtag.header("Cache-Control"));
        Reply byDate = request("GET", "/css/main.css", "If-Modified-Since: " + first.header("Last-Modified"));
        assertEquals(304, byDate.status());
    }

    @Test
    void headAndRange() throws IOException {
        Reply head = request("HEAD", "/css/main.css");
        assertEquals(200, head.status());
        assertEquals(String.valueOf(CSS.length()), head.header("Content-Length"));
        assertEquals("text/css; charset=utf-8", head.header("Content-Type"));
        assertEquals(0, head.body().length);

        Reply range = request("GET", "/css/main.css", "Range: bytes=0-3");
        assertEquals(206, range.status());
        assertEquals("body", range.text());
        assertEquals("bytes 0-3/" + CSS.length(), range.header("Content-Range"));
    }

    // ---- directory traversal ----

    @Test
    void noDirectoryTraversal() throws IOException {
        for (String target : List.of(
                "/../secret.txt",
                "/css/../../secret.txt",
                "/%2e%2e/secret.txt",
                "/css/%2e%2e/%2e%2e/secret.txt",
                "/..%5csecret.txt",
                "/css%5c..%5c..%5csecret.txt",
                "/./index.html",
                "//etc/passwd",
                "/%2fetc/passwd",
                "/escape.txt",
                "/escapedir/secret.txt",
                "/.hidden",
                "/css/../.hidden",
                "/%00",
                "/index.html%00.css")) {
            assertNotServed(target);
        }
    }

    @Test
    void resolveRefusesUnsafePaths() throws IOException {
        StaticFiles files = StaticFiles.of(root);
        Path realRoot = root.toRealPath();
        assertEquals(realRoot.resolve("index.html"), files.resolve("/"));
        assertEquals(realRoot.resolve("css/main.css"), files.resolve("/css/main.css"));
        assertEquals(realRoot.resolve("docs"), files.resolve("/docs"));
        for (String path : new String[] {
                null, "", "relative", "/..", "/../secret.txt", "/css/../../secret.txt", "/css/../index.html",
                "/./index.html", "/css//main.css", "/..\\secret.txt", "/a\0b", "/.hidden", "/escape.txt",
                "/escapedir/secret.txt", "/missing"}) {
            assertNull(files.resolve(path), String.valueOf(path));
        }
    }

    // ---- methods other than GET/HEAD never touch files ----

    private record Snapshot(byte[] content, FileTime mtime) {
    }

    private static Map<Path, Snapshot> snapshot(Path dir) throws IOException {
        Map<Path, Snapshot> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted().toList()) {
                if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    out.put(dir.relativize(p), new Snapshot(Files.readAllBytes(p), Files.getLastModifiedTime(p)));
                } else {
                    out.put(dir.relativize(p), null);
                }
            }
        }
        return out;
    }

    private static void assertSameTree(Map<Path, Snapshot> before, Map<Path, Snapshot> after) {
        assertEquals(before.keySet(), after.keySet(), "files were added or removed");
        for (Map.Entry<Path, Snapshot> e : before.entrySet()) {
            Snapshot a = e.getValue();
            Snapshot b = after.get(e.getKey());
            if (a == null) {
                assertNull(b, e.getKey().toString());
                continue;
            }
            assertArrayEquals(a.content(), b.content(), e.getKey().toString());
            assertEquals(a.mtime(), b.mtime(), e.getKey().toString());
        }
    }

    @Test
    void pageServesOneFixedFileForEveryPathItIsMountedOn() throws IOException {
        Reply page = request("GET", "/items/0190abcd-ef01-7000-8000-000000000001");
        assertEquals(200, page.status());
        assertEquals(INDEX, page.text());
        assertEquals("text/html; charset=utf-8", page.header("Content-Type"));
        assertEquals("no-cache", page.header("Cache-Control"));
        assertEquals("nosniff", page.header("X-Content-Type-Options"));
        assertEquals(200, request("HEAD", "/items/x").status());
        assertEquals(304, request("GET", "/items/y", "If-None-Match: " + page.header("ETag")).status());
        // Only GET/HEAD reach it; the file is never written.
        assertEquals(405, request("PUT", "/items/x", "data".getBytes(StandardCharsets.UTF_8)).status());
        assertEquals(405, request("DELETE", "/items/x").status());
        assertEquals(INDEX, Files.readString(root.resolve("index.html")));
        // Missing files, paths out of the root and hidden files are 404s, as for any static file.
        assertEquals(404, request("GET", "/gone/x").status());
        assertEquals(404, request("GET", "/out/x").status());
        assertEquals(404, request("GET", "/hidden/x").status());
    }

    @Test
    void optionsPutDeleteLeaveFilesUntouched() throws IOException {
        Map<Path, Snapshot> before = snapshot(tmp);
        byte[] payload = "overwritten!".getBytes(StandardCharsets.UTF_8);
        for (String target : List.of("/css/main.css", "/index.html", "/", "/new-file.txt", "/docs/", "/nodir/file.txt")) {
            Reply options = request("OPTIONS", target);
            assertEquals(204, options.status(), "OPTIONS " + target);
            String allow = options.header("Allow");
            assertNotNull(allow, "OPTIONS " + target);
            assertFalse(allow.contains("PUT"), allow);
            assertFalse(allow.contains("DELETE"), allow);
            assertTrue(allow.contains("GET"), allow);

            // OPTIONS with a body: the request that truncated files in SiggiHTTP 1.10.
            Reply optionsWithBody = request("OPTIONS", target, payload);
            assertEquals(204, optionsWithBody.status(), "OPTIONS+body " + target);

            Reply put = request("PUT", target, payload, "Content-Type: text/plain");
            assertEquals(405, put.status(), "PUT " + target);
            assertEquals(allow, put.header("Allow"));

            Reply putEmpty = request("PUT", target, new byte[0]);
            assertEquals(405, putEmpty.status(), "PUT (empty) " + target);

            Reply delete = request("DELETE", target);
            assertEquals(405, delete.status(), "DELETE " + target);

            Reply post = request("POST", target, payload);
            assertEquals(405, post.status(), "POST " + target);

            Reply patch = request("PATCH", target, payload);
            assertEquals(405, patch.status(), "PATCH " + target);
        }
        // Traversal targets with writing methods too.
        for (String target : List.of("/../secret.txt", "/escape.txt", "/%2e%2e/secret.txt")) {
            for (String method : List.of("OPTIONS", "PUT", "DELETE")) {
                Reply r = request(method, target, method.equals("PUT") ? payload : null);
                assertTrue(r.status() >= 200 && r.status() < 500, method + " " + target + " " + r.status());
                assertFalse(r.text().contains(SECRET));
            }
        }
        assertSameTree(before, snapshot(tmp));
        // Files still serve their original content.
        assertServed("/css/main.css", "text/css; charset=utf-8", CSS);
        assertNotServed("/new-file.txt");
    }

    @Test
    void handlerRefusesOtherMethodsWhenCalledDirectly() throws IOException {
        // Routed through register() only GET/HEAD reach the handler; mounted for PUT/DELETE by
        // mistake, it must still not write or delete anything.
        server.close();
        Router router = new Router();
        StaticFiles files = StaticFiles.of(root);
        router.put("/{path...}", files);
        router.delete("/{path...}", files);
        router.add("OPTIONS", "/{path...}", files);
        server = WebServer.start("127.0.0.1", 0, router, tmp.toFile());
        Map<Path, Snapshot> before = snapshot(tmp);
        for (String method : List.of("PUT", "DELETE", "OPTIONS")) {
            Reply r = request(method, "/css/main.css", method.equals("PUT") ? "x".getBytes(StandardCharsets.UTF_8) : null);
            assertEquals(405, r.status(), method);
            assertEquals("GET, HEAD, OPTIONS", r.header("Allow"), method);
        }
        assertSameTree(before, snapshot(tmp));
    }

    // ---- classpath / jar ----

    @Test
    void fromClasspathServesTheBuiltUiInPlace() throws IOException {
        // target/classes/web (WebBuildTest checks its contents)
        try (StaticFiles files = StaticFiles.fromClasspath(getClass().getClassLoader(), tmp.resolve("extract"))) {
            assertTrue(Files.isRegularFile(files.root().resolve("index.html")));
            assertTrue(Files.isRegularFile(files.root().resolve("js/main.js")));
            assertFalse(Files.exists(tmp.resolve("extract")), "nothing extracted for a directory on disk");
        }
        assertTrue(Files.isRegularFile(root.resolve("index.html")));
    }

    @Test
    void fromClasspathExtractsFromAJar() throws IOException {
        Path jar = tmp.resolve("ui.jar");
        long time = 1_600_000_000_000L;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String[] entry : new String[][] {
                    {"web/", null}, {"web/index.html", INDEX}, {"web/js/", null}, {"web/js/main.js", JS},
                    {"other/secret.txt", SECRET}}) {
                JarEntry e = new JarEntry(entry[0]);
                e.setTime(time);
                out.putNextEntry(e);
                if (entry[1] != null) {
                    out.write(entry[1].getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        Path parent = tmp.resolve("extract");
        Path extracted;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
             StaticFiles files = StaticFiles.fromClasspath(loader, parent)) {
            extracted = files.root();
            assertTrue(extracted.startsWith(parent), extracted.toString());
            assertEquals(INDEX, Files.readString(extracted.resolve("index.html")));
            assertEquals(JS, Files.readString(extracted.resolve("js/main.js")));
            assertFalse(Files.exists(extracted.resolve("secret.txt")));
            assertFalse(Files.exists(extracted.resolve("other")));
            // Modification times survive, so ETags are stable across restarts of the same build.
            assertEquals(time / 2000, Files.getLastModifiedTime(extracted.resolve("js/main.js")).toMillis() / 2000);

            server.close();
            Router router = new Router();
            files.register(router);
            server = WebServer.start("127.0.0.1", 0, router, tmp.toFile());
            assertServed("/", "text/html; charset=utf-8", INDEX);
            assertServed("/js/main.js", "text/javascript; charset=utf-8", JS);
        }
        assertFalse(Files.exists(extracted), "close() deletes the extracted copy");
    }

    @Test
    void fromClasspathWithoutTheUiFails() throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertThrows(IOException.class, () -> StaticFiles.fromClasspath(empty, tmp.resolve("x")));
        }
    }

    @Test
    void ofRequiresADirectory() {
        assertThrows(IOException.class, () -> StaticFiles.of(tmp.resolve("missing")));
        assertThrows(IOException.class, () -> StaticFiles.of(tmp.resolve("secret.txt")));
    }
}
