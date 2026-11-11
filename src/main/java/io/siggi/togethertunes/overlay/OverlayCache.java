package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.ingest.IngestPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The overlay cache ({@code docs/overlay.md#scheduling-and-caching}): the rooms' music-video
 * re-encodes ({@code rooms/<roomId>/ov/<itemId>/<dir>/}) and overlaid cards
 * ({@code rooms/<roomId>/cards/<name>.mp4} with its {@code .key}), together limited to
 * {@code overlay.cacheMaxBytes} by evicting the least recently used.
 *
 * <ul>
 *   <li><b>Entries:</b> a finished re-encode directory (it has a {@value #DONE_FILE} marker,
 *       written when the encode completed) or a card file. Last use is kept in memory and persisted
 *       (at most every {@link #PERSIST_MS}) as the modification time of the marker or card, so the
 *       order survives a restart.</li>
 *   <li><b>Eviction</b> ({@link #trim}): least recently used first, until the total is within the
 *       limit. An entry used in the last {@link #GRACE_MS} is kept (a card that was just made is
 *       about to be loaded), and so is one in use (a running or playing re-encode: the
 *       {@link Evictor} refuses it). 0 keeps nothing beyond that.</li>
 *   <li><b>Stale entries</b> (made with another overlayHash than the room's current one,
 *       {@link #roomOverlay}) go at the next trim whatever the size, as soon as nothing uses them.</li>
 *   <li><b>Purge, room delete:</b> {@link #itemPurged} and {@link #roomDeleted} delete everything of
 *       the item or room at once (the evictors stop what still uses it).</li>
 *   <li><b>Restart</b> ({@link #start}): re-encodes without the marker (partial: the server stopped
 *       mid-encode), temporary files, cards without fingerprint and the directories of rooms that no
 *       longer exist are deleted; the rest is counted, and trimmed to the limit.</li>
 * </ul>
 *
 * <p>Thread-safe. {@link #used} never does I/O (the packager calls it); trims and timestamp writes
 * run on the executor, one trim at a time.
 */
public final class OverlayCache {
    private static final System.Logger LOG = System.getLogger(OverlayCache.class.getName());

    /** Marker file of a finished re-encode directory; its modification time is the last use. */
    public static final String DONE_FILE = "done";
    /** An entry used this recently is never evicted for size. */
    static final long GRACE_MS = 60_000;
    /** The last use is written to disk at most this often per entry. */
    static final long PERSIST_MS = 60_000;

    /** Deletes a cache entry unless it is in use. */
    @FunctionalInterface
    public interface Evictor {
        /**
         * @return true if {@code path} is gone (deleted, or wasn't there), false if it is in use
         */
        boolean evict(Path path) throws IOException;
    }

    private static final class Entry {
        final Path path;
        final String roomId;
        final boolean card;
        long size;
        long lastUsed;
        long persisted;

        Entry(Path path, String roomId, boolean card, long size, long lastUsed) {
            this.path = path;
            this.roomId = roomId;
            this.card = card;
            this.size = size;
            this.lastUsed = lastUsed;
            this.persisted = lastUsed;
        }
    }

    private final Path roomsDir;
    private final LongSupplier maxBytes;
    private final Executor executor;
    private final LongSupplier nowMs;
    private volatile Evictor videoEvictor = OverlayCache::deleteTree;
    private volatile Evictor cardEvictor = OverlayCache::deleteCardFiles;

    private final Object lock = new Object();
    // guarded by lock
    private final Map<Path, Entry> entries = new HashMap<>();
    private final Map<String, String> roomHashes = new HashMap<>();
    private long total;
    private final Object trimLock = new Object();
    private final AtomicBoolean trimQueued = new AtomicBoolean();

    /**
     * @param roomsDir {@code <data>/rooms}
     * @param maxBytes {@code overlay.cacheMaxBytes}, read at every trim
     * @param executor runs trims and timestamp writes
     */
    public OverlayCache(Path roomsDir, LongSupplier maxBytes, Executor executor) {
        this(roomsDir, maxBytes, executor, System::currentTimeMillis);
    }

    OverlayCache(Path roomsDir, LongSupplier maxBytes, Executor executor, LongSupplier nowMs) {
        this.roomsDir = Objects.requireNonNull(roomsDir).toAbsolutePath().normalize();
        this.maxBytes = Objects.requireNonNull(maxBytes);
        this.executor = Objects.requireNonNull(executor);
        this.nowMs = Objects.requireNonNull(nowMs);
    }

    /**
     * Who deletes entries: the re-encodes' scheduler (refuses one in use) and the cards (under the
     * card's lock). Defaults delete the files.
     */
    public void evictors(Evictor video, Evictor cards) {
        this.videoEvictor = Objects.requireNonNull(video);
        this.cardEvictor = Objects.requireNonNull(cards);
    }

    // ------------------------------------------------------------------ start

    /**
     * Scans the cache after a restart: deletes partial re-encodes, temporary files, cards without a
     * fingerprint (or fingerprints without a card) and, when {@code rooms} is given, the directories
     * of rooms not in it; counts the rest. Then trims to the limit (on this thread).
     *
     * @param rooms the ids of the rooms that exist, or null if unknown (nothing is deleted for that)
     * @return how many files or directories were discarded
     */
    public int start(Set<String> rooms) throws IOException {
        int discarded = 0;
        List<Entry> found = new ArrayList<>();
        if (Files.isDirectory(roomsDir)) {
            try (DirectoryStream<Path> roomDirs = Files.newDirectoryStream(roomsDir)) {
                for (Path roomDir : roomDirs) {
                    String roomId = roomDir.getFileName().toString();
                    if (!Files.isDirectory(roomDir, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (rooms != null && !rooms.contains(roomId)) {
                        deleteTree(roomDir);
                        LOG.log(System.Logger.Level.INFO, "Deleted the overlay files of deleted room " + roomId);
                        discarded++;
                        continue;
                    }
                    discarded += deleteTempFiles(roomDir);
                    discarded += scanVideos(roomId, roomDir.resolve(OverlayVideoJobs.OV_DIR), found);
                    discarded += scanCards(roomId, roomDir.resolve(OverlayCards.CARDS_DIR), found);
                }
            }
        }
        synchronized (lock) {
            for (Entry e : found) {
                if (!entries.containsKey(e.path)) {
                    entries.put(e.path, e);
                    total += e.size;
                }
            }
        }
        if (discarded > 0) {
            LOG.log(System.Logger.Level.INFO, "Overlay cache: discarded " + discarded
                    + " partial or orphaned files after the restart");
        }
        trim();
        return discarded;
    }

    private static int deleteTempFiles(Path dir) throws IOException {
        int n = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (f.getFileName().toString().startsWith(".") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(f);
                    n++;
                }
            }
        }
        return n;
    }

    private int scanVideos(String roomId, Path ov, List<Entry> found) throws IOException {
        if (!Files.isDirectory(ov, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int n = 0;
        try (DirectoryStream<Path> items = Files.newDirectoryStream(ov)) {
            for (Path itemDir : items) {
                if (!Files.isDirectory(itemDir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(itemDir);
                    n++;
                    continue;
                }
                try (DirectoryStream<Path> encodes = Files.newDirectoryStream(itemDir)) {
                    for (Path dir : encodes) {
                        Path done = dir.resolve(DONE_FILE);
                        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(done)) {
                            deleteTree(dir);
                            n++;
                            continue;
                        }
                        found.add(new Entry(dir.toAbsolutePath().normalize(), roomId, false, treeSize(dir),
                                Files.getLastModifiedTime(done).toMillis()));
                    }
                }
                deleteIfEmpty(itemDir);
            }
        }
        return n;
    }

    private int scanCards(String roomId, Path cards, List<Entry> found) throws IOException {
        if (!Files.isDirectory(cards, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int n = deleteTempFiles(cards);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cards)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.endsWith(".mp4")) {
                    Path key = keyFile(f);
                    if (!Files.isRegularFile(key)) {
                        Files.deleteIfExists(f); // the fingerprint is written last: an unfinished card
                        n++;
                        continue;
                    }
                    found.add(new Entry(f.toAbsolutePath().normalize(), roomId, true, Files.size(f) + Files.size(key),
                            Files.getLastModifiedTime(f).toMillis()));
                } else if (name.endsWith(".mp4" + OverlayCards.KEY_SUFFIX)
                        && !Files.exists(f.resolveSibling(name.substring(0, name.length()
                                - OverlayCards.KEY_SUFFIX.length())))) {
                    Files.deleteIfExists(f);
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ use

    /**
     * A finished re-encode directory (with its {@value #DONE_FILE} marker) or a card file was made
     * (or made again): counted as used now. A trim follows on the executor.
     */
    public void added(Path path) {
        Path p = path.toAbsolutePath().normalize();
        String roomId = roomOf(p);
        if (roomId == null) {
            return;
        }
        boolean card = isCard(p);
        long size;
        try {
            size = card ? Files.size(p) + sizeIfExists(keyFile(p)) : treeSize(p);
        } catch (IOException e) {
            return; // gone again
        }
        long now = nowMs.getAsLong();
        synchronized (lock) {
            Entry e = entries.get(p);
            if (e == null) {
                e = new Entry(p, roomId, card, size, now);
                entries.put(p, e);
                total += size;
            } else {
                total += size - e.size;
                e.size = size;
                e.lastUsed = now;
            }
            e.persisted = now; // the files were just written
        }
        trimSoon();
    }

    /**
     * An entry was used (a card handed to an output, a re-encode's GOP played). Never does I/O; an
     * unknown path is ignored.
     */
    public void used(Path path) {
        Path p = path.toAbsolutePath().normalize();
        long now = nowMs.getAsLong();
        Entry persist = null;
        synchronized (lock) {
            Entry e = entries.get(p);
            if (e == null) {
                return;
            }
            e.lastUsed = now;
            if (now - e.persisted >= PERSIST_MS) {
                e.persisted = now;
                persist = e;
            }
        }
        if (persist != null) {
            Entry e = persist;
            try {
                executor.execute(() -> persist(e, now));
            } catch (RuntimeException ignored) {
                // the next use tries again
            }
        }
    }

    /** Whether the cache counts {@code path} (tests, and a card reused after a restart). */
    public boolean contains(Path path) {
        synchronized (lock) {
            return entries.containsKey(path.toAbsolutePath().normalize());
        }
    }

    /** The bytes counted. */
    public long totalBytes() {
        synchronized (lock) {
            return total;
        }
    }

    private void persist(Entry e, long time) {
        Path target = e.card ? e.path : e.path.resolve(DONE_FILE);
        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(time));
        } catch (IOException ignored) {
            // evicted meanwhile, or not writable: only the order after a restart suffers
        }
    }

    // ------------------------------------------------------------------ deletions

    /**
     * The room's overlay is now {@code overlayHash}: its entries made with another one are stale,
     * deleted at the next trim (which follows) as soon as nothing uses them.
     */
    public void roomOverlay(String roomId, String overlayHash) {
        boolean changed;
        synchronized (lock) {
            changed = !overlayHash.equals(roomHashes.put(roomId, overlayHash));
        }
        if (changed) {
            trimSoon();
        }
    }

    /** The item was purged: its re-encodes and cards in every room are deleted now. */
    public void itemPurged(String itemId) {
        if (!OverlayVideoJobs.isSafeName(itemId)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        String cardPrefix = "item-" + itemId + "-";
        try (DirectoryStream<Path> roomDirs = Files.newDirectoryStream(roomsDir)) {
            for (Path roomDir : roomDirs) {
                Path itemOv = roomDir.resolve(OverlayVideoJobs.OV_DIR).resolve(itemId);
                if (Files.isDirectory(itemOv, LinkOption.NOFOLLOW_LINKS)) {
                    try (DirectoryStream<Path> encodes = Files.newDirectoryStream(itemOv)) {
                        for (Path dir : encodes) {
                            paths.add(dir);
                        }
                    }
                }
                Path cards = roomDir.resolve(OverlayCards.CARDS_DIR);
                if (Files.isDirectory(cards, LinkOption.NOFOLLOW_LINKS)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(cards, cardPrefix + "*.mp4")) {
                        for (Path f : files) {
                            paths.add(f);
                        }
                    }
                }
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not list the overlay files of purged item " + itemId + ": " + e);
        }
        for (Path p : paths) {
            Path abs = p.toAbsolutePath().normalize();
            try {
                boolean gone = isCard(abs) ? cardEvictor.evict(abs) : videoEvictor.evict(abs);
                if (!gone) {
                    deleteTree(abs); // purged: in use or not, its media is gone
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not delete " + abs + " of purged item " + itemId + ": " + e);
            }
            forget(abs);
        }
        try (DirectoryStream<Path> roomDirs = Files.newDirectoryStream(roomsDir)) {
            for (Path roomDir : roomDirs) {
                deleteIfEmpty(roomDir.resolve(OverlayVideoJobs.OV_DIR).resolve(itemId));
            }
        } catch (IOException ignored) {
            // an empty directory is left over
        }
    }

    /**
     * The room was deleted: forgets its entries and deletes {@code rooms/<roomId>/} (its overlay
     * image, cards and re-encodes). The caller stops the room's re-encodes first.
     */
    public void roomDeleted(String roomId) {
        if (!OverlayVideoJobs.isSafeName(roomId)) {
            return;
        }
        synchronized (lock) {
            entries.values().removeIf(e -> {
                if (e.roomId.equals(roomId)) {
                    total -= e.size;
                    return true;
                }
                return false;
            });
            roomHashes.remove(roomId);
        }
        try {
            deleteTree(roomsDir.resolve(roomId));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not delete the overlay files of deleted room " + roomId
                    + " (deleted at the next start): " + e);
        }
    }

    private void forget(Path p) {
        synchronized (lock) {
            Entry e = entries.remove(p);
            if (e != null) {
                total -= e.size;
            }
        }
    }

    // ------------------------------------------------------------------ trim

    /** Runs {@link #trim} on the executor (one queued at a time). */
    public void trimSoon() {
        if (!trimQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                trimQueued.set(false);
                trim();
            });
        } catch (RuntimeException e) {
            trimQueued.set(false);
        }
    }

    /**
     * Deletes stale entries nothing uses, then the least recently used ones until the total is within
     * {@code overlay.cacheMaxBytes} (skipping those in use or used in the last {@link #GRACE_MS}).
     *
     * @return how many entries were deleted
     */
    public int trim() {
        synchronized (trimLock) {
            long max;
            try {
                max = maxBytes.getAsLong();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Overlay cache: can't read overlay.cacheMaxBytes: " + e);
                return 0;
            }
            long now = nowMs.getAsLong();
            List<Entry> order;
            long bytes;
            synchronized (lock) {
                order = new ArrayList<>(entries.values());
                bytes = total;
                order.sort(Comparator.comparing((Entry e) -> !isStale(e)).thenComparingLong(e -> e.lastUsed));
            }
            int deleted = 0;
            for (Entry e : order) {
                boolean stale;
                synchronized (lock) {
                    if (entries.get(e.path) != e) {
                        continue;
                    }
                    stale = isStale(e);
                    if (!stale && (bytes <= max || now - e.lastUsed < GRACE_MS)) {
                        if (bytes <= max) {
                            break;
                        }
                        continue;
                    }
                }
                boolean gone;
                try {
                    gone = e.card ? cardEvictor.evict(e.path) : videoEvictor.evict(e.path);
                } catch (IOException | UncheckedIOException ex) {
                    LOG.log(System.Logger.Level.WARNING, "Overlay cache: could not delete " + e.path + ": " + ex);
                    continue;
                }
                if (!gone) {
                    continue; // in use
                }
                synchronized (lock) {
                    if (entries.get(e.path) == e) {
                        entries.remove(e.path);
                        total -= e.size;
                    }
                }
                bytes -= e.size;
                deleted++;
                if (!e.card) {
                    deleteIfEmpty(e.path.getParent());
                }
                LOG.log(System.Logger.Level.DEBUG, "Overlay cache: evicted " + e.path + (stale ? " (stale)" : ""));
            }
            return deleted;
        }
    }

    /**
     * Lock held. A custom card without the overlay ({@link OverlayCards#customCard}, {@code -plain-}
     * in the overlay hash's place) never depends on the overlay: never stale, only evicted by size.
     */
    private boolean isStale(Entry e) {
        String hash = roomHashes.get(e.roomId);
        String name = e.path.getFileName().toString();
        if (name.startsWith(OverlayCards.CUSTOM_PREFIX) && name.contains("-" + OverlayCards.PLAIN + "-")) {
            return false;
        }
        return hash != null && !name.contains("-" + hash + "-");
    }

    // ------------------------------------------------------------------ files

    /** The room an entry path belongs to, or null if it isn't one of the cache's. */
    private String roomOf(Path p) {
        if (!p.startsWith(roomsDir)) {
            return null;
        }
        Path rel = roomsDir.relativize(p);
        if (rel.getNameCount() == 3 && rel.getName(1).toString().equals(OverlayCards.CARDS_DIR)) {
            return rel.getName(0).toString();
        }
        if (rel.getNameCount() == 4 && rel.getName(1).toString().equals(OverlayVideoJobs.OV_DIR)) {
            return rel.getName(0).toString();
        }
        return null;
    }

    private boolean isCard(Path p) {
        Path rel = roomsDir.relativize(p);
        return rel.getNameCount() == 3 && rel.getName(1).toString().equals(OverlayCards.CARDS_DIR);
    }

    static Path keyFile(Path card) {
        return card.resolveSibling(card.getFileName() + OverlayCards.KEY_SUFFIX);
    }

    /** Deletes a card and its fingerprint (the default card evictor). */
    static boolean deleteCardFiles(Path card) throws IOException {
        Files.deleteIfExists(keyFile(card));
        Files.deleteIfExists(card);
        return true;
    }

    static boolean deleteTree(Path p) throws IOException {
        IngestPipeline.deleteTree(p);
        return true;
    }

    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // not empty (DirectoryNotEmptyException), or gone
        }
    }

    private static long sizeIfExists(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    static long treeSize(Path dir) throws IOException {
        long[] size = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }
}
