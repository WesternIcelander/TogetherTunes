package io.siggi.togethertunes.library;

import io.siggi.togethertunes.http.HttpError;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where a library import reads from ({@code docs/library-and-ingest.md#library-import}): a
 * directory or a zip file, only ever read. Files are named by their path inside the source, with
 * {@code /} between the parts; those names are used only for matching and in messages, never to
 * build a path on disk (a directory source opens a file by the path its own walk found).
 */
public abstract sealed class ImportSource implements Closeable {
    /** The name every import looks for. */
    public static final String LIBRARY_XML = "Library.xml";

    /** The files in the source by their path inside it, with their sizes, in a stable order. */
    public abstract Map<String, Long> files();

    /**
     * Files of {@link #files()} that are not imported, by path, with the reason (a zip's entries
     * that look like zip bombs: {@code too_large} over {@link Zip#MAX_ENTRY_BYTES} expanded, or
     * {@code suspicious_compression} over {@link Zip#MAX_RATIO} to 1). Empty for a directory.
     */
    public Map<String, String> rejected() {
        return Map.of();
    }

    /** Reads one file of {@link #files()}. */
    public abstract InputStream open(String path) throws IOException;

    /** The one {@code Library.xml} in the source (its path inside it). */
    public String libraryXml() {
        List<String> found = new ArrayList<>();
        for (String p : files().keySet()) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            if (name.equalsIgnoreCase(LIBRARY_XML) && !p.startsWith("__MACOSX/")) {
                found.add(p);
            }
        }
        if (found.isEmpty()) {
            throw new HttpError(400, "no_library_xml", "There is no Library.xml in it");
        }
        if (found.size() > 1) {
            throw new HttpError(400, "several_library_xml", "There is more than one Library.xml in it");
        }
        return found.get(0);
    }

    /** Opens a directory or a {@code .zip} file. */
    public static ImportSource open(Path realPath) throws IOException {
        if (Files.isDirectory(realPath)) {
            return new Directory(realPath);
        }
        if (Files.isRegularFile(realPath) && realPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new Zip(realPath);
        }
        throw new HttpError(400, "invalid_source", "The path must be a folder or a .zip file");
    }

    /** A directory, walked without following symlinks. */
    static final class Directory extends ImportSource {
        private final Path root;
        private final Map<String, Long> files = new LinkedHashMap<>();
        private final Map<String, Path> paths = new LinkedHashMap<>();

        Directory(Path root) throws IOException {
            this.root = root;
            List<Path> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.forEach(p -> {
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        found.add(p);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            Collections.sort(found);
            for (Path p : found) {
                StringBuilder name = new StringBuilder();
                for (Path part : root.relativize(p)) {
                    if (!name.isEmpty()) {
                        name.append('/');
                    }
                    name.append(part);
                }
                String key = name.toString();
                files.put(key, Files.size(p));
                paths.put(key, p);
            }
        }

        @Override
        public Map<String, Long> files() {
            return Collections.unmodifiableMap(files);
        }

        @Override
        public InputStream open(String path) throws IOException {
            Path p = paths.get(path);
            if (p == null) {
                throw new java.nio.file.NoSuchFileException(path);
            }
            return Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return root.toString();
        }
    }

    /**
     * A zip file, read in place with {@link ZipFile} (ZIP64 included). Entry names are used only
     * for matching (and in messages), never to build a path on disk, so a name like {@code ../../x}
     * can't write anywhere: each file is copied to a server-chosen staging name. Entries that look
     * like zip bombs are listed in {@link #rejected()}, and no entry is ever read past the size its
     * directory declares.
     */
    static final class Zip extends ImportSource {
        /** Entries expanding to more than this are skipped (2 GiB). */
        static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
        /** Entries compressed more than this (expanded size to compressed size) are skipped. */
        static final long MAX_RATIO = 1000;
        /** The ratio check applies only above this expanded size (a tiny file can't be a bomb). */
        static final long RATIO_MIN_BYTES = 1024 * 1024;

        private final ZipFile zip;
        private final Map<String, Long> files = new LinkedHashMap<>();
        private final Map<String, ZipEntry> entries = new LinkedHashMap<>();
        private final Map<String, String> rejected = new LinkedHashMap<>();

        Zip(Path file) throws IOException {
            try {
                zip = new ZipFile(file.toFile());
            } catch (java.util.zip.ZipException e) {
                throw new HttpError(400, "invalid_zip", "The file isn't a readable zip file");
            }
            var all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.isEmpty() || files.containsKey(name)) {
                    continue;
                }
                long size = Math.max(0, e.getSize());
                files.put(name, size);
                entries.put(name, e);
                String reason = reject(size, e.getCompressedSize());
                if (reason != null) {
                    rejected.put(name, reason);
                }
            }
        }

        /** Why an entry of this expanded and compressed size is skipped, or null. */
        static String reject(long size, long compressed) {
            if (size > MAX_ENTRY_BYTES) {
                return "too_large";
            }
            if (size > RATIO_MIN_BYTES && (compressed <= 0 || size / compressed > MAX_RATIO)) {
                return "suspicious_compression";
            }
            return null;
        }

        @Override
        public Map<String, String> rejected() {
            return Collections.unmodifiableMap(rejected);
        }

        @Override
        public Map<String, Long> files() {
            return Collections.unmodifiableMap(files);
        }

        @Override
        public InputStream open(String path) throws IOException {
            ZipEntry e = entries.get(path);
            if (e == null) {
                throw new java.nio.file.NoSuchFileException(path);
            }
            return new Bounded(zip.getInputStream(e), Math.max(0, e.getSize()), path);
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /** An entry's stream that fails once it passes the size the zip's directory declares. */
    private static final class Bounded extends java.io.FilterInputStream {
        private final String path;
        private long left;

        Bounded(InputStream in, long size, String path) {
            super(in);
            this.left = size;
            this.path = path;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                // at the declared end: anything more means the entry lies about its size
                if (in.read() >= 0) {
                    throw new IOException("The zip entry " + path + " is larger than its declared size");
                }
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = in.skip(Math.min(n, left));
            left -= s;
            return s;
        }
    }
}
