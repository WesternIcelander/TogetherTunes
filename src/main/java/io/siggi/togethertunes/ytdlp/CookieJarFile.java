package io.siggi.togethertunes.ytdlp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The shared yt-dlp cookies file ({@code ytdlp.cookiesFile}, {@code docs/library-and-ingest.md}
 * "YouTube caching"), used through a private copy per call.
 *
 * <p>yt-dlp rewrites its whole cookie jar when it ends, so concurrent calls on one file would
 * overwrite each other's refreshed cookies and could read a half-written file. Instead,
 * {@link #open} copies the shared file (under a lock per real path) to a private file (mode 0600)
 * that the call passes as {@code --cookies}; {@link Call#close()} then merges only that call's
 * changes (cookies added, changed or removed relative to its starting copy) into the shared file
 * as it is now, and writes it by an atomic rename. Cookie values and the file's contents are never
 * logged.
 */
public final class CookieJarFile {

    /** The header yt-dlp and browsers write; used when the shared file has no comment lines. */
    static final String HEADER = "# Netscape HTTP Cookie File";
    private static final String HTTP_ONLY = "#HttpOnly_";
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");

    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final AtomicBoolean WARNED_NOT_WRITABLE = new AtomicBoolean();
    private static volatile Path serverTempDir;

    private CookieJarFile() {
    }

    // ------------------------------------------------------------------ format

    /** A cookie's identity: domain (without the {@code #HttpOnly_} prefix), path and name. */
    public record Key(String domain, String path, String name) {
    }

    /**
     * One cookie line: the seven tab-separated Netscape fields as written (the value may hold
     * tabs), plus whether the line had the {@code #HttpOnly_} prefix.
     */
    public record Cookie(boolean httpOnly, String domain, String includeSubdomains, String path, String secure,
                         String expiry, String name, String value) {
        public Key key() {
            return new Key(domain, path, name);
        }

        String line() {
            return (httpOnly ? HTTP_ONLY : "") + String.join("\t", domain, includeSubdomains, path, secure, expiry,
                    name, value);
        }
    }

    /** A parsed cookies file: its comment lines (in order) and its cookies by key (in order). */
    public record Jar(List<String> comments, LinkedHashMap<Key, Cookie> cookies) {
        public Jar {
            comments = List.copyOf(comments);
        }
    }

    /** The file isn't in Netscape cookies format. The message never quotes the file. */
    public static final class MalformedException extends Exception {
        MalformedException(String message) {
            super(message);
        }
    }

    /**
     * Parses a Netscape-format cookies file: blank lines are skipped, {@code #HttpOnly_<domain>…}
     * lines are cookies, other {@code #} lines are comments, every other line must have seven
     * tab-separated fields (the last, the value, may contain tabs). A cookie that appears twice
     * keeps its last line.
     */
    public static Jar parse(String text) throws MalformedException {
        List<String> comments = new ArrayList<>();
        LinkedHashMap<Key, Cookie> cookies = new LinkedHashMap<>();
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) {
                continue;
            }
            boolean httpOnly = line.startsWith(HTTP_ONLY);
            if (!httpOnly && line.startsWith("#")) {
                comments.add(line);
                continue;
            }
            String fields = httpOnly ? line.substring(HTTP_ONLY.length()) : line;
            String[] f = fields.split("\t", 7);
            if (f.length != 7 || f[0].isEmpty()) {
                throw new MalformedException("line " + lineNo + " isn't a cookie in Netscape format");
            }
            Cookie c = new Cookie(httpOnly, f[0], f[1], f[2], f[3], f[4], f[5], f[6]);
            cookies.remove(c.key());
            cookies.put(c.key(), c);
        }
        return new Jar(comments, cookies);
    }

    /** Writes a jar: its comment lines (or the standard header if it has none), then one line per cookie. */
    public static String write(Jar jar) {
        StringBuilder sb = new StringBuilder();
        if (jar.comments().isEmpty()) {
            sb.append(HEADER).append('\n');
        }
        for (String c : jar.comments()) {
            sb.append(c).append('\n');
        }
        if (!jar.cookies().isEmpty()) {
            sb.append('\n');
        }
        for (Cookie c : jar.cookies().values()) {
            sb.append(c.line()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Applies one call's changes ({@code start} → {@code end}) to {@code current}: cookies added or
     * changed in {@code end} replace or join those of {@code current}, cookies {@code end} lacks
     * that {@code start} had are removed; everything else in {@code current} stays.
     *
     * @return the merged jar, or null if it equals {@code current} (nothing to write)
     */
    public static Jar merge(Jar start, Jar end, Jar current) {
        LinkedHashMap<Key, Cookie> out = new LinkedHashMap<>(current.cookies());
        boolean changed = false;
        for (Cookie c : end.cookies().values()) {
            if (c.equals(start.cookies().get(c.key()))) {
                continue;
            }
            Cookie before = out.put(c.key(), c);
            changed |= !c.equals(before);
        }
        for (Key k : start.cookies().keySet()) {
            if (!end.cookies().containsKey(k)) {
                changed |= out.remove(k) != null;
            }
        }
        return changed ? new Jar(current.comments(), out) : null;
    }

    // ------------------------------------------------------------------ calls

    /**
     * The directory for private copies of calls that have no job directory ({@code <data>/tmp/cookies});
     * leftovers in it (a crash during a call) are deleted. Without it, the system temp dir is used.
     */
    public static void serverTempDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "cookies-*")) {
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
        }
        serverTempDir = dir;
    }

    /**
     * The cookies file named by the {@code ytdlp.cookiesFile} setting, if it is a readable regular
     * file; null if the setting is empty. A file that is missing or unreadable is reported to
     * {@code warn} and ignored (yt-dlp then runs without cookies).
     */
    public static Path sharedFile(String setting, Consumer<String> warn) {
        if (setting == null || setting.isBlank()) {
            return null;
        }
        Path file;
        try {
            file = Path.of(setting.strip());
        } catch (RuntimeException e) {
            warn.accept("the cookies file setting (ytdlp.cookiesFile) isn't a valid path; continuing without cookies");
            return null;
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            warn.accept("the cookies file " + file + " (ytdlp.cookiesFile) can't be read; continuing without cookies");
            return null;
        }
        return file;
    }

    /**
     * Starts a yt-dlp call with the cookies file of {@code setting}: copies it to a private file
     * in {@code tempDir} (null: the server's temp dir). Returns a call with no file when there is
     * no usable cookies file (see {@link #sharedFile}); close the call when yt-dlp has ended
     * (also when it failed).
     */
    public static Call open(String setting, Path tempDir, Consumer<String> warn) {
        Path shared = sharedFile(setting, warn);
        if (shared == null) {
            return new Call(null, null, null, null, warn);
        }
        Path real;
        try {
            real = shared.toRealPath();
        } catch (IOException e) {
            warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) can't be read; continuing without cookies");
            return new Call(null, null, null, null, warn);
        }
        ReentrantLock lock = lockFor(real);
        Path copy = null;
        try {
            Path dir = tempDir != null ? tempDir : serverTempDir;
            if (dir != null) {
                Files.createDirectories(dir);
            }
            copy = createPrivate(dir);
            byte[] bytes;
            lock.lock();
            try {
                bytes = Files.readAllBytes(real);
            } finally {
                lock.unlock();
            }
            Files.write(copy, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Jar start;
            try {
                start = parse(decode(bytes));
            } catch (MalformedException e) {
                warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) isn't in Netscape format ("
                        + e.getMessage() + "); this call's cookie changes won't be saved");
                start = null;
            }
            return new Call(real, copy, bytes, start, warn);
        } catch (IOException e) {
            deleteQuietly(copy);
            warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) couldn't be copied (" + e.getClass()
                    .getSimpleName() + "); continuing without cookies");
            return new Call(null, null, null, null, warn);
        }
    }

    /**
     * One yt-dlp call's private cookies file. {@link #args()} is {@code --cookies <private file>}
     * (empty without cookies); {@link #close()} merges the call's changes and deletes the file.
     */
    public static final class Call implements AutoCloseable {
        private final Path shared;
        private final Path file;
        private final byte[] startBytes;
        private final Jar start;
        private final Consumer<String> warn;
        private boolean closed;

        private Call(Path shared, Path file, byte[] startBytes, Jar start, Consumer<String> warn) {
            this.shared = shared;
            this.file = file;
            this.startBytes = startBytes;
            this.start = start;
            this.warn = warn;
        }

        /** The private cookies file to pass to yt-dlp, or null for none. */
        public Path file() {
            return file;
        }

        /** {@code --cookies <private file>}, or nothing. */
        public List<String> args() {
            return file == null ? List.of() : List.of("--cookies", file.toString());
        }

        /** Merges this call's cookie changes into the shared file, then deletes the private file. */
        @Override
        public void close() {
            if (closed || file == null) {
                return;
            }
            closed = true;
            try {
                mergeBack();
            } finally {
                deleteQuietly(file);
            }
        }

        private void mergeBack() {
            if (start == null) {
                return;
            }
            Jar end;
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (Arrays.equals(bytes, startBytes)) {
                    return;
                }
                if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
                    throw new MalformedException("it ends in the middle of a line");
                }
                end = parse(decode(bytes));
            } catch (IOException | MalformedException e) {
                warn.accept("yt-dlp left a cookies file that can't be read (" + describe(e)
                        + "); its cookie changes weren't saved");
                return;
            }
            ReentrantLock lock = lockFor(shared);
            lock.lock();
            try {
                if (!writable(shared)) {
                    if (WARNED_NOT_WRITABLE.compareAndSet(false, true)) {
                        warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) or its directory isn't"
                                + " writable; refreshed cookies aren't saved (warned once)");
                    }
                    return;
                }
                Jar current;
                try {
                    current = parse(decode(Files.readAllBytes(shared)));
                } catch (MalformedException e) {
                    warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) isn't in Netscape format ("
                            + e.getMessage() + "); this call's cookie changes weren't saved");
                    return;
                }
                Jar merged = merge(start, end, current);
                if (merged != null) {
                    writeAtomically(shared, write(merged));
                }
            } catch (IOException e) {
                warn.accept("the cookies file " + shared + " (ytdlp.cookiesFile) couldn't be updated (" + describe(e)
                        + ")");
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Replaces {@code real} (a real path, no symlink) by a temp file in its directory renamed over
     * it, keeping its POSIX permissions; a reader sees the old or the new file, never a partial one.
     */
    static void writeAtomically(Path real, String content) throws IOException {
        Path dir = real.getParent();
        Set<PosixFilePermission> perms = null;
        try {
            perms = Files.getPosixFilePermissions(real);
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system
        }
        Path tmp = Files.createTempFile(dir, "." + real.getFileName() + ".", ".tmp");
        try {
            if (perms != null) {
                Files.setPosixFilePermissions(tmp, perms);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            try {
                Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, real, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Whether the merge can replace {@code real}: the file and its directory are writable. */
    private static boolean writable(Path real) {
        return Files.isWritable(real) && Files.isWritable(real.getParent());
    }

    private static Path createPrivate(Path dir) throws IOException {
        try {
            return dir == null
                    ? Files.createTempFile("cookies-", ".txt", PosixFilePermissions.asFileAttribute(PRIVATE))
                    : Files.createTempFile(dir, "cookies-", ".txt", PosixFilePermissions.asFileAttribute(PRIVATE));
        } catch (UnsupportedOperationException e) {
            return dir == null ? Files.createTempFile("cookies-", ".txt") : Files.createTempFile(dir, "cookies-", ".txt");
        }
    }

    private static ReentrantLock lockFor(Path real) {
        return LOCKS.computeIfAbsent(real, p -> new ReentrantLock());
    }

    private static String decode(byte[] bytes) throws MalformedException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new MalformedException("it isn't UTF-8 text");
        }
    }

    private static String describe(Exception e) {
        if (e instanceof MalformedException) {
            return e.getMessage();
        }
        if (e instanceof FileSystemException fse && fse.getReason() != null) {
            return fse.getReason();
        }
        return e.getClass().getSimpleName();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // a private copy left behind is deleted with its directory or at the next start
        }
    }

    /** For tests: forget that the not-writable warning was given. */
    static void resetWarnings() {
        WARNED_NOT_WRITABLE.set(false);
    }
}
