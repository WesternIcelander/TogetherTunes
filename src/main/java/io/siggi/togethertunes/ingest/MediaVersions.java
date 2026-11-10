package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.storage.DiskSpace;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
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
 *
 * <p><b>Undo</b> ({@code docs/library-and-ingest.md#undo}). The version an item switched away from
 * is also kept for {@code media.undoHours} ({@link #undoWindow}; 0 = no undo), one step back: when
 * a newer switch retires another version, the one kept before only waits for its plays. The
 * database knows the kept version ({@code media_undo}) and what every kept version was made with
 * ({@code media_versions}), so it survives a restart: the startup clean-up keeps it while it is
 * within the window. {@link #undo} goes back to it without re-encoding: its files are hard-linked
 * into a new version directory (numbers keep growing, so versioned URLs stay immutable), the
 * {@code items} columns and the edit settings it was made with come back, and the version undone
 * becomes the kept one (so an undo can be undone within the window). Only a person's change
 * ({@code media_undo.undoable}) can be undone; a re-encode made by the system keeps the previous
 * version without Undo. While the free space is below the minimum ({@link #diskSpace}), kept
 * versions whose plays are over are deleted early, oldest first.
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

    /**
     * An old version waiting to be deleted.
     *
     * @param undo whether it is the item's kept previous version, kept for the undo window too
     */
    record Retired(String itemId, int version, long switchedAtMs, long keepMs, boolean undo) {
        Retired withoutUndo() {
            return new Retired(itemId, version, switchedAtMs, keepMs, false);
        }
    }

    private final DataDir dataDir;
    private final Database db;
    private final LongSupplier nowMs;
    private final Duration grace;
    private volatile Predicate<String> inUse = id -> false;
    private volatile LongSupplier undoWindowMs = () -> 0;
    private volatile DiskSpace diskSpace;
    private volatile ItemStatusListener statusListener = ItemStatusListener.NONE;
    private final List<Retired> pending = new ArrayList<>();
    private ScheduledExecutorService scheduler;

    /** Without a database: no undo bookkeeping. */
    public MediaVersions(DataDir dataDir) {
        this(dataDir, null, System::currentTimeMillis, GRACE);
    }

    /** With undo ({@link #undo}; the window is set by {@link #undoWindow}). */
    public MediaVersions(DataDir dataDir, Database db) {
        this(dataDir, db, System::currentTimeMillis, GRACE);
    }

    /** Tests: another clock and grace period, no database. */
    MediaVersions(DataDir dataDir, LongSupplier nowMs, Duration grace) {
        this(dataDir, null, nowMs, grace);
    }

    /** Another clock and grace period (tests). */
    public MediaVersions(DataDir dataDir, Database db, LongSupplier nowMs, Duration grace) {
        this.dataDir = Objects.requireNonNull(dataDir);
        this.db = db;
        this.nowMs = Objects.requireNonNull(nowMs);
        this.grace = Objects.requireNonNull(grace);
    }

    /**
     * How long the previous version of an item is kept for undo, in ms ({@code media.undoHours},
     * read at each use); 0 (the default until set) turns undo off.
     */
    public void undoWindow(LongSupplier windowMs) {
        this.undoWindowMs = windowMs == null ? () -> 0 : windowMs;
    }

    /** The free-space check: kept undo versions are deleted early while the volume is below the minimum. */
    public void diskSpace(DiskSpace diskSpace) {
        this.diskSpace = diskSpace;
    }

    /** Who is told after an undo switched an item's files (the rooms). */
    public void statusListener(ItemStatusListener listener) {
        this.statusListener = listener == null ? ItemStatusListener.NONE : listener;
    }

    private long window() {
        try {
            return Math.max(0, undoWindowMs.getAsLong());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read media.undoHours; no undo for now: " + e);
            return 0;
        }
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
     * isn't current or next anywhere, {@code durationMs} plus the grace period have passed and the
     * undo window is over. It becomes the item's kept previous version; one kept before only waits
     * for its plays from now on.
     */
    public void retired(String itemId, int version, long durationMs) {
        synchronized (pending) {
            pending.replaceAll(r -> r.itemId().equals(itemId) && r.undo() ? r.withoutUndo() : r);
            pending.add(new Retired(itemId, version, nowMs.getAsLong(), Math.max(0, durationMs) + grace.toMillis(), true));
        }
    }

    /** The old versions not deleted yet (tests, status). */
    List<Retired> pending() {
        synchronized (pending) {
            return List.copyOf(pending);
        }
    }

    /**
     * Deletes the retired versions whose time has come (see the class comment), and while the free
     * space is below the minimum also kept undo versions whose plays are over, oldest first, until
     * enough is freed.
     *
     * @return how many versions were deleted
     */
    public int sweep() {
        long now = nowMs.getAsLong();
        long window = window();
        List<Retired> due = new ArrayList<>();
        List<Retired> kept = new ArrayList<>();
        synchronized (pending) {
            for (Iterator<Retired> it = pending.iterator(); it.hasNext(); ) {
                Retired r = it.next();
                if (now - r.switchedAtMs() < r.keepMs() || inUse.test(r.itemId())) {
                    continue;
                }
                if (r.undo() && now - r.switchedAtMs() < window) {
                    kept.add(r);
                } else {
                    due.add(r);
                    it.remove();
                }
            }
        }
        int deleted = 0;
        for (Retired r : due) {
            if (delete(r, "")) {
                deleted++;
            }
        }
        DiskSpace space = diskSpace;
        if (!kept.isEmpty() && space != null) {
            DiskSpace.Status status = space.status();
            long missing = status.low() ? status.minFreeBytes() - status.freeBytes() : 0;
            kept.sort(Comparator.comparingLong(Retired::switchedAtMs));
            for (Retired r : kept) {
                if (missing <= 0) {
                    break;
                }
                synchronized (pending) {
                    if (!pending.remove(r)) {
                        continue; // an undo took it meanwhile
                    }
                }
                long size = size(IngestPipeline.itemDir(dataDir, r.itemId()), r.version());
                if (delete(r, " early (kept for undo, but the free disk space is below the minimum)")) {
                    deleted++;
                    missing -= Math.max(1, size);
                }
            }
        }
        return deleted;
    }

    /** Deletes one retired version's files and its undo rows; false if it was gone or couldn't be deleted. */
    private boolean delete(Retired r, String why) {
        Path itemDir = IngestPipeline.itemDir(dataDir, r.itemId());
        boolean deleted = false;
        try {
            if (exists(itemDir, r.version())) {
                deleteVersion(itemDir, r.version());
                deleted = true;
                LOG.log(System.Logger.Level.INFO, "Deleted media version " + r.version() + " of item " + r.itemId() + why);
            }
        } catch (IOException e) {
            // left for the clean-up at the next start
            LOG.log(System.Logger.Level.WARNING, "Could not delete media version " + r.version() + " of item "
                    + r.itemId() + ": " + e.getMessage());
            return false;
        }
        if (db != null) {
            try {
                db.write(c -> {
                    forget(c, r.itemId(), r.version());
                    return null;
                });
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not forget media version " + r.version() + " of item "
                        + r.itemId() + ": " + e);
            }
        }
        return deleted;
    }

    /** The bytes of one version's files. */
    static long size(Path itemDir, int version) {
        long total = 0;
        try {
            for (Path f : files(itemDir, version)) {
                total += Files.size(f);
            }
        } catch (IOException e) {
            // an estimate is enough
        }
        return total;
    }

    /** One version's files: the regular files in {@code v<N>/}, or version 0's {@link #ENCODE_FILES} present. */
    static List<Path> files(Path itemDir, int version) throws IOException {
        List<Path> out = new ArrayList<>();
        if (version == 0) {
            for (String f : ENCODE_FILES) {
                Path p = itemDir.resolve(f);
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    out.add(p);
                }
            }
            return out;
        }
        Path dir = versionDir(itemDir, version);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(out::add);
        }
        return out;
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
     * restart) without undo: in every item directory of an item that exists and isn't purged,
     * deletes every version except the current {@code media_version}, including {@code v*}
     * directories a crash left between their rename and the database switch. Directories of
     * unknown or purged items are left alone (a purge deletes them itself).
     *
     * @return how many versions were deleted
     */
    public static int cleanUpAtStartup(Database db, DataDir dataDir) throws IOException, SQLException {
        return cleanUp(db, dataDir, 0, 0, null);
    }

    /**
     * The startup clean-up with undo: like {@link #cleanUpAtStartup(Database, DataDir)}, but an
     * item's kept previous version ({@code media_undo}) stays while it is within the undo window,
     * and is handed to the sweeper (deleted when the window ends). Kept rows past the window, or
     * whose files are gone, are forgotten.
     *
     * @return how many versions were deleted
     */
    public int cleanUpAtStartup() throws IOException, SQLException {
        if (db == null) {
            throw new IllegalStateException("no database");
        }
        long now = nowMs.getAsLong();
        return cleanUp(db, dataDir, now, window(), r -> {
            synchronized (pending) {
                pending.add(r);
            }
        });
    }

    private static int cleanUp(Database db, DataDir dataDir, long now, long window,
                               java.util.function.Consumer<Retired> register) throws IOException, SQLException {
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
            long[] kept = db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT version, switched_at FROM media_undo WHERE item_id = ?")) {
                    ps.setString(1, itemId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? new long[] {rs.getInt(1), rs.getLong(2)} : null;
                    }
                }
            });
            Integer keep = null;
            if (kept != null && window > 0 && now - kept[1] < window
                    && kept[0] != current && exists(itemDir, (int) kept[0])) {
                keep = (int) kept[0];
            }
            for (int v : versionsPresent(itemDir)) {
                if (v != current && (keep == null || v != keep)) {
                    deleteVersion(itemDir, v);
                    deleted++;
                }
            }
            if (current != 0 && (keep == null || keep != 0) && exists(itemDir, 0)) {
                deleteVersion(itemDir, 0);
                deleted++;
            }
            int cur = current;
            Integer k = keep;
            db.write(c -> {
                if (k == null) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_undo WHERE item_id = ?")) {
                        ps.setString(1, itemId);
                        ps.executeUpdate();
                    }
                }
                pruneVersionRows(c, itemId, cur, k);
                return null;
            });
            if (keep != null) {
                register.accept(new Retired(itemId, keep, kept[1], 0, true));
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------ undo bookkeeping (database)

    private static final String VERSION_COLUMNS = "kind, duration_ms, output_profile, loudness_lufs, encode_preset,"
            + " encode_quality, audio_only_ms, original_web, original_gain_db, card_version";

    /**
     * Records the item's current {@code items} columns as what {@code version} (its current one)
     * was made with, unless {@code media_versions} has it already (versions published before undo
     * existed).
     */
    static void snapshotIfMissing(Connection c, String itemId, int version, long atMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO media_versions (item_id, version, published_at, "
                + VERSION_COLUMNS + ", edit_json) SELECT id, ?, ?, " + VERSION_COLUMNS + ", NULL FROM items"
                + " WHERE id = ? AND media_version = ? AND NOT EXISTS"
                + " (SELECT 1 FROM media_versions WHERE item_id = ? AND version = ?)")) {
            ps.setInt(1, version);
            ps.setLong(2, atMs);
            ps.setString(3, itemId);
            ps.setInt(4, version);
            ps.setString(5, itemId);
            ps.setInt(6, version);
            ps.executeUpdate();
        }
    }

    /** Records the item's {@code items} columns, just switched to {@code version}, as what it was made with. */
    static void recordVersion(Connection c, String itemId, int version, long atMs, String editJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_versions WHERE item_id = ? AND version = ?")) {
            ps.setString(1, itemId);
            ps.setInt(2, version);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO media_versions (item_id, version, published_at, "
                + VERSION_COLUMNS + ", edit_json) SELECT id, ?, ?, " + VERSION_COLUMNS + ", ? FROM items WHERE id = ?")) {
            ps.setInt(1, version);
            ps.setLong(2, atMs);
            ps.setString(3, editJson);
            ps.setString(4, itemId);
            ps.executeUpdate();
        }
    }

    /**
     * Records {@code kept} (null: none) as the item's kept previous version, switched away from at
     * {@code atMs}, and forgets the rows of every other version than {@code current} and it.
     */
    static void recordKept(Connection c, String itemId, int current, Integer kept, boolean undoable, long atMs)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_undo WHERE item_id = ?")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
        if (kept != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO media_undo (item_id, version, switched_at, undoable) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, itemId);
                ps.setInt(2, kept);
                ps.setLong(3, atMs);
                ps.setInt(4, undoable ? 1 : 0);
                ps.executeUpdate();
            }
        }
        pruneVersionRows(c, itemId, current, kept);
    }

    private static void pruneVersionRows(Connection c, String itemId, int current, Integer kept) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_versions WHERE item_id = ? AND version <> ?"
                + (kept == null ? "" : " AND version <> ?"))) {
            ps.setString(1, itemId);
            ps.setInt(2, current);
            if (kept != null) {
                ps.setInt(3, kept);
            }
            ps.executeUpdate();
        }
    }

    /** After a version's files were deleted: forgets it as the kept version and its row (unless current). */
    static void forget(Connection c, String itemId, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_undo WHERE item_id = ? AND version = ?")) {
            ps.setString(1, itemId);
            ps.setInt(2, version);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_versions WHERE item_id = ? AND version = ?"
                + " AND version <> (SELECT media_version FROM items WHERE id = ?)")) {
            ps.setString(1, itemId);
            ps.setInt(2, version);
            ps.setString(3, itemId);
            ps.executeUpdate();
        }
    }

    /** The edit settings (JSON) {@code version} of the item was made with, or null. */
    public static String editJson(Connection c, String itemId, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT edit_json FROM media_versions WHERE item_id = ? AND version = ?")) {
            ps.setString(1, itemId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The edit settings (JSON) of the item's current version, or null. */
    public static String currentEditJson(Database db, String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v.edit_json FROM items i JOIN media_versions v"
                    + " ON v.item_id = i.id AND v.version = i.media_version WHERE i.id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** Called within an item's purge: nothing is kept for undo any more. */
    public static void forgetAll(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_undo WHERE item_id = ?")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM media_versions WHERE item_id = ?")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ undo

    /** Why an undo isn't possible: {@code code} is the API error code (409). */
    public static final class UndoRefused extends RuntimeException {
        private final String code;

        UndoRefused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * What an undo did.
     *
     * @param undoneVersion the version that was current (now the kept one)
     * @param restoredVersion the kept version gone back to
     * @param newVersion    the new version directory holding {@code restoredVersion}'s files
     * @param editJson      the edit settings {@code restoredVersion} was made with (now current), or null
     */
    public record Undone(int undoneVersion, int restoredVersion, int newVersion, String editJson) {
    }

    /** Runs in the undo's transaction, after the switch (the audit row). */
    @FunctionalInterface
    public interface InSwitch {
        void run(Connection c, Undone undone) throws SQLException;
    }

    /** What the database says about an item's kept version. */
    private record Kept(int current, String status, String outputProfile, int version, long switchedAt,
                        boolean undoable, String keptProfile, long currentDurationMs, String editJson, boolean busy) {
    }

    private static Kept kept(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT i.media_version, i.status, i.output_profile, u.version,"
                + " u.switched_at, u.undoable, v.output_profile, i.duration_ms, v.edit_json,"
                + " (SELECT COUNT(*) FROM ingest_jobs j WHERE j.item_id = i.id AND j.state IN ('queued', 'running'))"
                + " FROM items i JOIN media_undo u ON u.item_id = i.id"
                + " LEFT JOIN media_versions v ON v.item_id = i.id AND v.version = u.version WHERE i.id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Kept(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5),
                        rs.getInt(6) != 0, rs.getString(7), rs.getLong(8), rs.getString(9), rs.getLong(10) > 0);
            }
        }
    }

    /** Why {@code k} can't be undone now, or null if it can. */
    private UndoRefused refusal(Kept k, Path itemDir, long now, long window) {
        if (window <= 0) {
            return new UndoRefused("nothing_to_undo", "Undo is turned off on this server");
        }
        if (k == null || !k.undoable() || now - k.switchedAt() >= window || k.version() == k.current()
                || !exists(itemDir, k.version())) {
            return new UndoRefused("nothing_to_undo", "There is no change to undo");
        }
        if (k.keptProfile() == null || !k.keptProfile().equals(k.outputProfile())) {
            return new UndoRefused("nothing_to_undo", "The previous version was made for another output encoding");
        }
        if (!"ready".equals(k.status())) {
            return new UndoRefused("nothing_to_undo", "The item isn't ready");
        }
        if (k.busy()) {
            return new UndoRefused("item_busy",
                    "This item is being processed right now; try again when its ingest job has ended");
        }
        return null;
    }

    /**
     * When the change that Undo would take back was made (epoch ms), if Undo is possible now:
     * a person's change, within the window, its previous version still there, made for the
     * current encoding, and the item ready. Not checked: whether an ingest job runs (undo then
     * answers {@code item_busy}).
     */
    public OptionalLong undoableSince(Connection c, String itemId) throws SQLException {
        if (db == null) {
            return OptionalLong.empty();
        }
        Kept k = kept(c, itemId);
        UndoRefused why = refusal(k, IngestPipeline.itemDir(dataDir, itemId), nowMs.getAsLong(), window());
        return why == null || "item_busy".equals(why.code()) ? OptionalLong.of(k.switchedAt()) : OptionalLong.empty();
    }

    /**
     * Goes back to the item's kept previous version (see the class comment). The caller checked
     * that the actor may edit the item.
     *
     * @throws UndoRefused {@code nothing_to_undo} (none kept, not a person's change, past the
     *                     window, undo off, another encoding, not ready) or {@code item_busy} (an
     *                     ingest job is queued or running)
     */
    public Undone undo(String itemId, InSwitch inSwitch) throws IOException, SQLException {
        if (db == null) {
            throw new IllegalStateException("no database");
        }
        Path itemDir = IngestPipeline.itemDir(dataDir, itemId);
        long now = nowMs.getAsLong();
        long window = window();
        Kept k = db.read(c -> kept(c, itemId));
        UndoRefused why = refusal(k, itemDir, now, window);
        if (why != null) {
            throw why;
        }
        // Out of the sweeper's reach while the undo runs.
        Retired taken = null;
        synchronized (pending) {
            for (Iterator<Retired> it = pending.iterator(); it.hasNext(); ) {
                Retired r = it.next();
                if (r.itemId().equals(itemId) && r.version() == k.version()) {
                    taken = r;
                    it.remove();
                    break;
                }
            }
        }
        boolean done = false;
        try {
            if (!exists(itemDir, k.version())) {
                throw new UndoRefused("nothing_to_undo", "There is no change to undo");
            }
            Path work = dataDir.media().resolve(IngestPipeline.WORK_PREFIX + "undo-" + UuidV7.newId());
            Files.createDirectories(work);
            try {
                for (Path f : files(itemDir, k.version())) {
                    IngestPipeline.linkOrCopy(f, work.resolve(f.getFileName().toString()));
                }
            } catch (IOException e) {
                IngestPipeline.deleteTree(work);
                throw e;
            }
            Undone[] result = new Undone[1];
            int newVersion;
            try {
                newVersion = IngestPipeline.publish(work, itemDir, k.current(), null, v -> db.write(c -> {
                    Kept again = kept(c, itemId);
                    UndoRefused late = refusal(again, itemDir, now, window);
                    if (late != null) {
                        throw late;
                    }
                    if (again.current() != k.current() || again.version() != k.version()) {
                        throw new UndoRefused("nothing_to_undo", "The item changed meanwhile; reload and try again");
                    }
                    snapshotIfMissing(c, itemId, k.current(), now);
                    try (PreparedStatement ps = c.prepareStatement("UPDATE items SET (" + VERSION_COLUMNS
                            + ") = (SELECT " + VERSION_COLUMNS + " FROM media_versions WHERE item_id = ? AND version = ?),"
                            + " media_version = ? WHERE id = ?")) {
                        ps.setString(1, itemId);
                        ps.setInt(2, k.version());
                        ps.setInt(3, v);
                        ps.setString(4, itemId);
                        ps.executeUpdate();
                    }
                    recordVersion(c, itemId, v, now, k.editJson());
                    recordKept(c, itemId, v, k.current(), true, now);
                    result[0] = new Undone(k.current(), k.version(), v, k.editJson());
                    inSwitch.run(c, result[0]);
                    return null;
                }));
            } catch (IOException | SQLException | RuntimeException e) {
                IngestPipeline.deleteQuietly(work);
                throw e;
            }
            done = true;
            synchronized (pending) {
                // The restored files live on as links in the new version; the old directory only
                // waits for its plays now.
                pending.add(taken != null ? taken.withoutUndo() : new Retired(itemId, k.version(), now, 0, false));
            }
            retired(itemId, k.current(), k.currentDurationMs());
            LOG.log(System.Logger.Level.INFO, "Undo: item " + itemId + " is back on the files of version "
                    + k.version() + " (as version " + newVersion + "); version " + k.current() + " is kept for undo");
            statusListener.itemStatusChanged(itemId);
            return result[0];
        } finally {
            if (!done && taken != null) {
                synchronized (pending) {
                    pending.add(taken);
                }
            }
        }
    }
}
