package io.siggi.togethertunes;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The data directory ({@code data.dir}) and its fixed subdirectories, as laid out in
 * {@code docs/architecture.md#storage-layout}:
 *
 * <pre>
 * &lt;data&gt;/
 *   db/      SQLite file (when using SQLite)
 *   media/   one directory per item
 *   cards/   server-wide filler cards
 *   rooms/   one directory per room
 *   tmp/     in-progress uploads and ingest scratch
 * </pre>
 *
 * <p>{@link #create} makes all of them on startup (existing ones are kept as they are) and checks
 * that each is a writable directory, so a misconfigured data dir fails at startup instead of on
 * the first upload.
 *
 * @param root  absolute, normalised data directory
 * @param db    {@code <data>/db}
 * @param media {@code <data>/media}
 * @param cards {@code <data>/cards}
 * @param rooms {@code <data>/rooms}
 * @param tmp   {@code <data>/tmp}
 */
public record DataDir(Path root, Path db, Path media, Path cards, Path rooms, Path tmp) {
    /** Names of the subdirectories, in the order {@link #create} makes them. */
    public static final List<String> SUBDIRECTORIES = List.of("db", "media", "cards", "rooms", "tmp");

    /** The layout under {@code root}, without touching the file system. */
    public static DataDir of(Path root) {
        Path r = root.toAbsolutePath().normalize();
        return new DataDir(r, r.resolve("db"), r.resolve("media"), r.resolve("cards"), r.resolve("rooms"),
                r.resolve("tmp"));
    }

    /**
     * Creates {@code root} and its subdirectories where missing.
     *
     * @throws IOException if a directory can't be created, a path exists but isn't a directory,
     *                     or a directory isn't writable
     */
    public static DataDir create(Path root) throws IOException {
        DataDir dir = of(root);
        for (Path path : dir.all()) {
            try {
                Files.createDirectories(path);
            } catch (FileAlreadyExistsException e) {
                throw new IOException(path + " exists but is not a directory", e);
            }
            // createDirectories accepts a symlink to a directory, which is fine (e.g. media/ on
            // another disk), but check the target really is one.
            if (!Files.isDirectory(path)) {
                throw new IOException(path + " exists but is not a directory");
            }
            if (!Files.isWritable(path)) {
                throw new IOException(path + " is not writable");
            }
        }
        return dir;
    }

    /** {@link #root} followed by the subdirectories. */
    public List<Path> all() {
        return List.of(root, db, media, cards, rooms, tmp);
    }
}
