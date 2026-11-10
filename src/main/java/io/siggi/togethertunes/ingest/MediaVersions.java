package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.media.SampleIndex;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Versioned media files ({@code docs/library-and-ingest.md#storage-layout}). Every encode of an
 * item publishes into its own directory {@code media/<itemId>/v<N>/} ({@code mezz.mp4},
 * {@code audio.m4a}, {@code card.mp4}, {@code index.bin}, {@code cover.jpg}), and
 * {@code items.media_version} names the current one. The kept original stays at
 * {@code media/<itemId>/original.<ext>}, outside the versions.
 *
 * <p><b>Version 0</b> is the flat layout of items published before versions existed: their files
 * lie directly in {@code media/<itemId>/}. They are not moved; the item's next encode becomes
 * {@code v1} and the flat files are deleted like any old version (by name, {@link #ENCODE_FILES},
 * so the original stays). Nothing has to survive a restart halfway.
 *
 * <p><b>Numbers only grow</b>: a publish takes {@code max(media_version, highest v* present) + 1},
 * and a purge keeps {@code media_version}, so a versioned URL ({@code /media/{id}/v/{N}/{file}})
 * never names other bytes and can be cached as immutable.
 *
 * <p><b>Clean-up.</b> After a publish switched an item to a new version, the old one is
 * {@link #retired}: it is deleted once the item isn't the current or next entry of any room
 * <b>and</b> at least the item's duration plus {@link #GRACE} has passed since the switch, so a web
 * player that started on the old version can finish on it. The program output is safe either way
 * (it keeps reading the files it opened). Pending deletions live in memory only: at startup
 * {@link #cleanUpAtStartup} deletes every version that isn't current (no player survives a
 * restart), and any {@code v*} directory a crash left without a database switch.
 */
public final class MediaVersions implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(MediaVersions.class.getName());

    /** The files of one encode; version 0's are deleted by these names. */
    public static final List<String> ENCODE_FILES = List.of(IngestPipeline.MEZZ, IngestPipeline.AUDIO,
            IngestPipeline.CARD, IngestPipeline.COVER, SampleIndex.FILE_NAME);

    /** How long after the end of a play that started just before the switch an old version is kept. */
    public static final Duration GRACE = Duration.ofMinutes(5);
    /** How often pending deletions are checked. */
    public static final Duration INTERVAL = Duration.ofMinutes(1);

    /** An old version waiting to be deleted. */
    record Retired(String itemId, int version, long switchedAtMs, long keepMs) {
    }

    private final DataDir dataDir;
    private final LongSupplier nowMs;
    private final Duration grace;
    private volatile Predicate<String> inUse = id -> false;
    private final List<Retired> pending = new ArrayList<>();
    private ScheduledExecutorService scheduler;

    public MediaVersions(DataDir dataDir) {
        this(dataDir, System::currentTimeMillis, GRACE);
    }

    /** Tests: another clock and grace period. */
    MediaVersions(DataDir dataDir, LongSupplier nowMs, Duration grace) {
        this.dataDir = Objects.requireNonNull(dataDir);
        this.nowMs = Objects.requireNonNull(nowMs);
        this.grace = Objects.requireNonNull(grace);
    }

    /**
     * Which items are playing now (the current or next entry of a room; the rooms'
     * {@code PlayingItems}): their old versions are kept. Until set, nothing counts as playing.
     */
    public void inUse(Predicate<String> playing) {
        this.inUse = playing == null ? id -> false : playing;
    }

    // ------------------------------------------------------------------ layout

    /** {@code media/<itemId>/v<version>}, or {@code media/<itemId>} itself for version 0. */
    public static Path versionDir(Path itemDir, int version) {
        if (version < 0) {
            throw new IllegalArgumentException("bad media version " + version);
        }
        return version == 0 ? itemDir : itemDir.resolve("v" + version);
    }

    /** See {@link #versionDir(Path, int)}. */
    public static Path versionDir(DataDir dataDir, String itemId, int version) {
        return versionDir(IngestPipeline.itemDir(dataDir, itemId), version);
    }

    /** The version of a {@code v<N>} directory name ({@code N >= 1}, no leading zeros), else empty. */
    static OptionalInt parseVersionName(String name) {
        if (name.length() < 2 || name.length() > 10 || name.charAt(0) != 'v' || name.charAt(1) == '0') {
            return OptionalInt.empty();
        }
        for (int i = 1; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < '0' || ch > '9') {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(Integer.parseInt(name.substring(1)));
    }

    /** The {@code v<N>} directories present in {@code itemDir}. */
    static List<Integer> versionsPresent(Path itemDir) throws IOException {
        List<Integer> out = new ArrayList<>();
        if (!Files.isDirectory(itemDir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(itemDir)) {
            for (Path p : entries) {
                OptionalInt v = parseVersionName(p.getFileName().toString());
                if (v.isPresent() && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    out.add(v.getAsInt());
                }
            }
        }
        return out;
    }

    /** The version a new encode publishes as: above the current one and above every {@code v*} present. */
    static int nextVersion(Path itemDir, int current) throws IOException {
        int max = current;
        for (int v : versionsPresent(itemDir)) {
            max = Math.max(max, v);
        }
        return max + 1;
    }

    /**
     * Deletes one version's files: the {@code v<N>} directory, or for version 0 the flat
     * {@link #ENCODE_FILES} (the original and the {@code v*} directories stay).
     */
    static void deleteVersion(Path itemDir, int version) throws IOException {
        if (version == 0) {
            for (String f : ENCODE_FILES) {
                Files.deleteIfExists(itemDir.resolve(f));
            }
        } else {
            IngestPipeline.deleteTree(versionDir(itemDir, version));
        }
    }

    /** Whether a version still has files. */
    static boolean exists(Path itemDir, int version) {
        if (version != 0) {
            return Files.isDirectory(versionDir(itemDir, version), LinkOption.NOFOLLOW_LINKS);
        }
        for (String f : ENCODE_FILES) {
            if (Files.exists(itemDir.resolve(f), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    /** The item's current {@code media_version}, or empty if there is no such item. */
    public static OptionalInt current(Database db, String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT media_version FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? OptionalInt.of(rs.getInt(1)) : OptionalInt.empty();
                }
            }
        });
    }

    /** The directory of the item's current version ({@code itemDir} itself if the item doesn't exist). */
    public static Path currentDir(Database db, DataDir dataDir, String itemId) throws SQLException {
        return versionDir(dataDir, itemId, current(db, itemId).orElse(0));
    }

    // ------------------------------------------------------------------ clean-up

    /**
     * Records that {@code itemId} switched away from {@code version}: it is deleted once the item
     * isn't current or next anywhere and {@code durationMs} plus the grace period have passed.
     */
    public void retired(String itemId, int version, long durationMs) {
        synchronized (pending) {
            pending.add(new Retired(itemId, version, nowMs.getAsLong(), Math.max(0, durationMs) + grace.toMillis()));
        }
    }

    /** The old versions not deleted yet (tests, status). */
    List<Retired> pending() {
        synchronized (pending) {
            return List.copyOf(pending);
        }
    }

    /**
     * Deletes the retired versions whose time has come (see the class comment).
     *
     * @return how many versions were deleted
     */
    public int sweep() {
        long now = nowMs.getAsLong();
        List<Retired> due = new ArrayList<>();
        synchronized (pending) {
            for (Iterator<Retired> it = pending.iterator(); it.hasNext(); ) {
                Retired r = it.next();
                if (now - r.switchedAtMs() >= r.keepMs() && !inUse.test(r.itemId())) {
                    due.add(r);
                    it.remove();
                }
            }
        }
        int deleted = 0;
        for (Retired r : due) {
            Path itemDir = IngestPipeline.itemDir(dataDir, r.itemId());
            try {
                if (exists(itemDir, r.version())) {
                    deleteVersion(itemDir, r.version());
                    deleted++;
                    LOG.log(System.Logger.Level.INFO, "Deleted media version " + r.version() + " of item " + r.itemId());
                }
            } catch (IOException e) {
                // left for the clean-up at the next start
                LOG.log(System.Logger.Level.WARNING, "Could not delete media version " + r.version() + " of item "
                        + r.itemId() + ": " + e.getMessage());
            }
        }
        return deleted;
    }

    /** Sweeps every {@link #INTERVAL}. */
    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("media-versions").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Media version clean-up failed", e);
            }
        }, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * The startup clean-up (before the ingest queue and the web server start; no player survives a
     * restart): in every item directory of an item that exists and isn't purged, deletes every
     * version except the current {@code media_version}, including {@code v*} directories a crash
     * left between their rename and the database switch. Directories of unknown or purged items
     * are left alone (a purge deletes them itself).
     *
     * @return how many versions were deleted
     */
    public static int cleanUpAtStartup(Database db, DataDir dataDir) throws IOException, SQLException {
        int deleted = 0;
        if (!Files.isDirectory(dataDir.media())) {
            return 0;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dataDir.media())) {
            for (Path d : entries) {
                String n = d.getFileName().toString();
                if (!n.startsWith(".") && UuidV7.isValid(n) && Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) {
                    dirs.add(d);
                }
            }
        }
        for (Path itemDir : dirs) {
            String itemId = itemDir.getFileName().toString();
            Integer current = db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT media_version, status FROM items WHERE id = ?")) {
                    ps.setString(1, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() && !"purged".equals(rs.getString(2)) ? rs.getInt(1) : null;
                    }
                }
            });
            if (current == null) {
                continue;
            }
            for (int v : versionsPresent(itemDir)) {
                if (v != current) {
                    deleteVersion(itemDir, v);
                    deleted++;
                }
            }
            if (current != 0 && exists(itemDir, 0)) {
                deleteVersion(itemDir, 0);
                deleted++;
            }
        }
        return deleted;
    }
}
