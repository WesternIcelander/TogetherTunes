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

    /** A zip file, read in place. */
    static final class Zip extends ImportSource {
        private final ZipFile zip;
        private final Map<String, Long> files = new LinkedHashMap<>();
        private final Map<String, ZipEntry> entries = new LinkedHashMap<>();

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
                files.put(name, Math.max(0, e.getSize()));
                entries.put(name, e);
            }
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
            return zip.getInputStream(e);
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }
}
