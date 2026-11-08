package io.siggi.togethertunes.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Serves the web UI ({@code web/}: the static assets and the compiled TypeScript) from a
 * directory, at the root of the site: {@code /} is {@code index.html}, {@code /js/main.js} is
 * {@code <root>/js/main.js}.
 *
 * <ul>
 *   <li><b>Methods:</b> registered as a {@code GET} route only ({@code HEAD} runs it too), so the
 *       {@link Router} answers {@code OPTIONS} itself ({@code 204}, {@code Allow: GET, HEAD,
 *       OPTIONS}) and every other method with {@code 405}. The handler checks the method again
 *       before touching the file. Files are sent with SiggiHTTP's
 *       {@code returnFile(file, contentType)} (Range, {@code ETag}, {@code Last-Modified},
 *       {@code 304}); never {@code handleRequestWithFile} or {@code VirtualFileSystemResponder},
 *       which write and delete files on {@code PUT} / {@code DELETE} (see CLAUDE.md).</li>
 *   <li><b>Paths:</b> {@link #resolve} refuses {@code .} / {@code ..} segments, empty segments,
 *       backslashes, NUL, and dot-files, then checks that the normalised path, and after
 *       resolving symlinks the real path, is still inside the root. Anything else is a
 *       {@code 404}. A directory is served as its {@code index.html}; without the trailing slash
 *       it redirects ({@code 308}) to it, so relative URLs in the page work.</li>
 *   <li><b>Content types:</b> by extension ({@link #contentType}), {@code charset=utf-8} on text,
 *       {@code application/octet-stream} for unknown ones, plus
 *       {@code X-Content-Type-Options: nosniff}.</li>
 *   <li><b>Caching:</b> file names carry no content hash (no bundler), so every file is
 *       {@code Cache-Control: no-cache}: browsers keep it but revalidate each time, and an
 *       unchanged file costs a {@code 304} (via {@code ETag} / {@code If-Modified-Since}).</li>
 * </ul>
 *
 * <p>In the jar, the UI is a classpath resource directory ({@code /web/}); {@code returnFile}
 * needs real files, so {@link #fromClasspath} extracts it to a directory first (keeping the
 * entries' modification times, so {@code ETag}s stay stable across restarts of the same build).
 * Running from {@code target/classes} (IDE, tests) it serves the directory in place.
 */
public final class StaticFiles implements Handler, AutoCloseable {
    /** The classpath directory the Maven build puts the web UI in. */
    public static final String CLASSPATH_DIR = "web";

    private static final String INDEX = "index.html";
    private static final String CACHE_CONTROL = "no-cache";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("svg", "image/svg+xml; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"));

    private final Path root;
    private final Path realRoot;
    private final boolean deleteOnClose;

    private StaticFiles(Path root, boolean deleteOnClose) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = this.root.toRealPath();
        if (!Files.isDirectory(realRoot)) {
            throw new IOException("Not a directory: " + root);
        }
        this.deleteOnClose = deleteOnClose;
    }

    /**
     * Serves the files under {@code root} as they are.
     *
     * @throws IOException if {@code root} isn't an existing directory
     */
    public static StaticFiles of(Path root) throws IOException {
        return new StaticFiles(root, false);
    }

    /**
     * Serves the classpath directory {@link #CLASSPATH_DIR}: in place when it is a directory on
     * disk, otherwise (in the jar) extracted into a new directory under {@code extractParent},
     * which {@link #close()} deletes again.
     *
     * @throws IOException if the classpath has no such directory, or it can't be extracted
     */
    public static StaticFiles fromClasspath(ClassLoader loader, Path extractParent) throws IOException {
        return fromClasspath(loader, CLASSPATH_DIR, extractParent);
    }

    static StaticFiles fromClasspath(ClassLoader loader, String dir, Path extractParent) throws IOException {
        // index.html marks the directory: a bare directory URL isn't reliably available in jars.
        String marker = dir + "/" + INDEX;
        URL url = loader.getResource(marker);
        if (url == null) {
            throw new IOException("The web UI (" + marker + ") is not on the classpath; was it built (mvn package)?");
        }
        switch (url.getProtocol()) {
            case "file" -> {
                try {
                    return of(Path.of(url.toURI()).getParent());
                } catch (URISyntaxException e) {
                    throw new IOException("Bad resource URL " + url, e);
                }
            }
            case "jar" -> {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                Path target = Files.createTempDirectory(Files.createDirectories(extractParent), "web-");
                try (JarFile jar = connection.getJarFile()) {
                    extract(jar, dir + "/", target);
                } catch (IOException | RuntimeException e) {
                    deleteTree(target);
                    throw e;
                }
                return new StaticFiles(target, true);
            }
            default -> throw new IOException("Can't serve the web UI from " + url);
        }
    }

    /** Copies every entry of {@code jar} under {@code prefix} into {@code target}. */
    static void extract(JarFile jar, String prefix, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || name.length() == prefix.length()) {
                continue;
            }
            Path out = base.resolve(name.substring(prefix.length())).normalize();
            if (!out.startsWith(base) || out.equals(base)) {
                throw new IOException("Unsafe jar entry " + name);
            }
            if (entry.isDirectory()) {
                Files.createDirectories(out);
                continue;
            }
            Files.createDirectories(out.getParent());
            try (InputStream in = jar.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            long time = entry.getTime();
            if (time > 0) {
                Files.setLastModifiedTime(out, FileTime.fromMillis(time));
            }
        }
    }

    /** Registers this as the catch-all {@code GET /{path...}} route (more specific routes win). */
    public StaticFiles register(Router router, Hook... hooks) {
        router.get("/{path...}", this, hooks);
        return this;
    }

    /** The directory files are served from. */
    public Path root() {
        return root;
    }

    @Override
    public void handle(Request request, Response response) throws IOException {
        String method = request.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            // Not reachable through register() (the router only sends GET and HEAD here), but
            // never let another method near returnFile.
            response.header("Allow", "GET, HEAD, OPTIONS");
            throw new HttpError(405, "method_not_allowed", "Method " + method + " is not allowed here");
        }
        String path = request.path();
        Path file = resolve(path);
        if (file == null) {
            throw HttpError.notFound();
        }
        if (Files.isDirectory(file)) {
            if (!path.endsWith("/") && Files.isRegularFile(file.resolve(INDEX))) {
                response.header("Location", encodePath(path) + "/");
                response.empty(308);
                return;
            }
            throw HttpError.notFound();
        }
        response.header("Cache-Control", CACHE_CONTROL);
        response.header("X-Content-Type-Options", "nosniff");
        response.raw().returnFile(file.toFile(), contentType(file.getFileName().toString()));
    }

    /**
     * Maps a request path to a file or directory under the root, with {@code index.html} for a
     * path ending in {@code /}.
     *
     * @return the real path (symlinks resolved, inside the root), or {@code null} if the path is
     *         not allowed or nothing is there
     */
    Path resolve(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return null;
        }
        String relative = path.substring(1);
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += INDEX;
        }
        for (String segment : relative.split("/", -1)) {
            // also refuses "." and "..", and hidden files such as .DS_Store
            if (segment.isEmpty() || segment.startsWith(".")) {
                return null;
            }
        }
        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        } catch (RuntimeException e) { // InvalidPathException
            return null;
        }
        if (!candidate.startsWith(root)) {
            return null;
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null; // missing
        }
        if (!real.startsWith(realRoot)) {
            return null; // a symlink out of the root
        }
        if (Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            return real;
        }
        return null;
    }

    /** Percent-encodes a decoded path for a {@code Location} header, keeping the slashes. */
    static String encodePath(String path) {
        StringJoiner out = new StringJoiner("/");
        for (String segment : path.split("/", -1)) {
            out.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    /** The {@code Content-Type} for a file name, by extension. */
    static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return TYPES.getOrDefault(fileName.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    /** Deletes the extracted copy, if {@link #fromClasspath} made one. */
    @Override
    public void close() {
        if (deleteOnClose) {
            try {
                deleteTree(root);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
