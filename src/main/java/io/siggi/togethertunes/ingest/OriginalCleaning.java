package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The one-time pass that removes personal data from the originals of audio uploads made before
 * uploads were cleaned ({@code docs/library-and-ingest.md#looking-details-up-online}, "Removing
 * personal data from uploads"): every ready audio upload whose {@code original.m4a},
 * {@code original.mp3} or {@code original.flac} has {@code original_cleaned} null. Each is copied to
 * {@code <data>/tmp/clean/}, cleaned there ({@link OriginalCleaner}), probed (same codec, same
 * duration), and then renamed over the original while the item's job lock is held
 * ({@link IngestPipeline#itemLock}, {@code tryLock}: an item with a running job is retried later,
 * and a job never publishes an old link of the original back over the cleaned one). Cleaning
 * doesn't change the audio, so nothing is re-encoded and the loudness values stay. A failure sets
 * {@code original_cleaned} 0 and {@code original_web} 0 (never served); each item is done once.
 *
 * <p>Runs on a low-priority daemon thread at startup when TrackSplitter is installed; items it
 * can't get to (TrackSplitter missing) stay null and are cleaned at a later start.
 */
public final class OriginalCleaning {
    private static final System.Logger LOG = System.getLogger(OriginalCleaning.class.getName());
    /** How often an item whose job is running is tried again before the pass gives up until the next start. */
    static final int MAX_ROUNDS = 20;

    /** What happened to one item. */
    enum Outcome { CLEANED, FAILED, BUSY, SKIPPED }

    private final Database db;
    private final DataDir dataDir;
    private final Tools tools;
    private final IngestPipeline pipeline;
    private final OriginalCleaner cleaner;
    private volatile Duration retryDelay = Duration.ofSeconds(30);

    public OriginalCleaning(Database db, DataDir dataDir, Tools tools, IngestPipeline pipeline, OriginalCleaner cleaner) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.tools = Objects.requireNonNull(tools);
        this.pipeline = Objects.requireNonNull(pipeline);
        this.cleaner = Objects.requireNonNull(cleaner);
    }

    /** For tests: how long to wait before trying busy items again. */
    void retryDelay(Duration retryDelay) {
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    /** Runs the pass and logs what it did; for the startup thread. */
    public void runLogged() {
        try {
            int n = run();
            if (n > 0) {
                LOG.log(System.Logger.Level.INFO, "Personal data: cleaned the originals of " + n + " uploaded items");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cleaning the originals of uploaded items stopped", e);
        }
    }

    private record Row(String id, String originalFile) {
    }

    /**
     * Cleans every waiting original.
     *
     * @return how many were cleaned (failures not counted)
     */
    public int run() throws SQLException, InterruptedException {
        if (!cleaner.installed()) {
            return 0;
        }
        List<Row> rows = candidates();
        int cleaned = 0;
        for (int round = 0; round < MAX_ROUNDS && !rows.isEmpty(); round++) {
            if (round > 0) {
                Thread.sleep(retryDelay.toMillis());
            }
            List<Row> busy = new ArrayList<>();
            for (Row row : rows) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                Outcome o = clean(row);
                if (o == Outcome.CLEANED) {
                    cleaned++;
                } else if (o == Outcome.BUSY) {
                    busy.add(row);
                }
            }
            rows = busy;
        }
        return cleaned;
    }

    private List<Row> candidates() throws SQLException {
        return db.read(c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, original_file FROM items WHERE source = 'upload'"
                    + " AND kind = 'audio' AND status = 'ready' AND original_cleaned IS NULL"
                    + " AND original_file IN ('original.m4a', 'original.mp3', 'original.flac') ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getString(1), rs.getString(2)));
                    }
                }
            }
            return out;
        });
    }

    /** Whether the item still waits for this pass with this original. */
    private boolean stillWaiting(Row row) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items WHERE id = ? AND original_file = ?"
                    + " AND original_cleaned IS NULL AND status = 'ready' AND kind = 'audio'")) {
                ps.setString(1, row.id());
                ps.setString(2, row.originalFile());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    Outcome clean(Row row) throws SQLException, InterruptedException {
        Path file = IngestPipeline.itemDir(dataDir, row.id()).resolve(row.originalFile());
        String ext = OriginalCleaner.extension(row.originalFile());
        Path copy = dataDir.tmp().resolve("clean").resolve(row.id() + "." + ext);
        try {
            BasicFileAttributes before;
            try {
                before = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                return Outcome.SKIPPED;
            }
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            String why = cleaner.clean(copy, null);
            if (why == null) {
                why = sameAudio(file, copy);
            }
            ReentrantLock lock = pipeline.itemLock(row.id());
            if (!lock.tryLock()) {
                return Outcome.BUSY; // a job of the item is running: later
            }
            try {
                if (!stillWaiting(row)) {
                    return Outcome.SKIPPED;
                }
                BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class);
                if (!Objects.equals(before.fileKey(), now.fileKey()) || before.size() != now.size()
                        || !before.lastModifiedTime().equals(now.lastModifiedTime())) {
                    return Outcome.BUSY; // the original changed meanwhile: again from the start
                }
                if (why != null) {
                    LOG.log(System.Logger.Level.WARNING, "Personal data couldn't be removed from the original of item "
                            + row.id() + " (" + why + "); it won't be served");
                    record(row.id(), false);
                    return Outcome.FAILED;
                }
                replace(copy, file);
                record(row.id(), true);
                return Outcome.CLEANED;
            } finally {
                lock.unlock();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not clean the original of item " + row.id() + ": " + e);
            return Outcome.SKIPPED;
        } finally {
            try {
                Files.deleteIfExists(copy);
            } catch (IOException e) {
                // tmp/ is cleared at the next start
            }
        }
    }

    /** Null if the cleaned copy has the same audio codec and duration as the original; else why not. */
    private String sameAudio(Path original, Path cleaned) throws InterruptedException {
        try {
            MediaProbe a = MediaProbe.probe(tools, original, null);
            MediaProbe b = MediaProbe.probe(tools, cleaned, null);
            if (b.audio() == null || a.audio() == null || !Originals.codec(a).equals(Originals.codec(b))) {
                return "the cleaned file isn't the same audio any more";
            }
            if (Math.abs(a.durationUs() - b.durationUs()) > Originals.MAX_DURATION_DIFF_US) {
                return "the cleaned file's duration differs";
            }
            return null;
        } catch (ToolException | IngestFailure e) {
            return "the file can't be probed: " + e.getMessage();
        }
    }

    private static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void record(String id, boolean cleaned) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(cleaned
                    ? "UPDATE items SET original_cleaned = 1 WHERE id = ?"
                    : "UPDATE items SET original_cleaned = 0, original_web = 0 WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
