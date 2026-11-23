package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * The one-time pass that trims letterbox bars off the covers of items made before covers were
 * trimmed ({@link CoverBars}; {@code docs/library-and-ingest.md#cards}, "Letterbox bars"): every
 * ready item with {@code items.cover_bars_checked} 0. Its current {@code cover.jpg} is read; when it
 * has bars, the cropped picture becomes the item's waiting new cover ({@link IngestPipeline#PENDING_COVER})
 * and the card-only re-encode Replace artwork uses is queued ({@link IngestJobs#enqueueCover}), whose
 * publish tells the rooms ({@code ItemStatusListener.itemDetailsChanged}). Each item is recorded as
 * checked (1) once done, so the pass resumes where it stopped and never runs twice.
 *
 * <p>Background work, after people's: before each item it waits until no ingest job is queued or
 * running, so its own re-encodes run one at a time. A busy item (a job of it unfinished, or a new
 * cover already waiting) is tried again later; after {@link #MAX_ROUNDS} rounds it is left for the
 * next start. Runs on a low-priority daemon thread at startup.
 */
public final class CoverBarsPass {
    private static final System.Logger LOG = System.getLogger(CoverBarsPass.class.getName());
    /** How often busy items are tried again before the pass gives up until the next start. */
    static final int MAX_ROUNDS = 20;

    /** What happened to one item. */
    enum Outcome { TRIMMED, NO_BARS, BUSY, SKIPPED }

    /** Whether the ingest queue has nothing left to do (no job queued or running). */
    interface Idle {
        boolean idle() throws SQLException;
    }

    private final Database db;
    private final DataDir dataDir;
    private final IngestJobs jobs;
    private volatile Idle idle;
    private volatile Duration retryDelay = Duration.ofSeconds(30);
    private volatile Duration idlePoll = Duration.ofSeconds(5);

    public CoverBarsPass(Database db, DataDir dataDir, IngestJobs jobs) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.jobs = Objects.requireNonNull(jobs);
        this.idle = () -> jobs.list(IngestJob.State.QUEUED, 1).isEmpty() && jobs.list(IngestJob.State.RUNNING, 1).isEmpty();
    }

    /** For tests: when the queue counts as idle, and how long to wait between tries. */
    void timing(Idle idle, Duration retryDelay, Duration idlePoll) {
        this.idle = Objects.requireNonNull(idle);
        this.retryDelay = Objects.requireNonNull(retryDelay);
        this.idlePoll = Objects.requireNonNull(idlePoll);
    }

    /** Starts {@link #runLogged} on a daemon thread of minimum priority. */
    public Thread start() {
        Thread t = new Thread(this::runLogged, "cover-bars-pass");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return t;
    }

    /** Runs the pass and logs what it did. */
    public void runLogged() {
        try {
            int n = run();
            if (n > 0) {
                LOG.log(System.Logger.Level.INFO, "Covers: queued cropped covers (letterbox bars removed) for " + n + " items");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Trimming bars off older covers stopped", e);
        }
    }

    /**
     * Checks every unchecked ready item.
     *
     * @return how many got a cropped cover queued
     */
    public int run() throws SQLException, InterruptedException {
        List<String> ids = candidates();
        int trimmed = 0;
        for (int round = 0; round < MAX_ROUNDS && !ids.isEmpty(); round++) {
            if (round > 0) {
                Thread.sleep(retryDelay.toMillis());
            }
            List<String> busy = new ArrayList<>();
            for (String id : ids) {
                waitIdle();
                Outcome o = check(id);
                if (o == Outcome.TRIMMED) {
                    trimmed++;
                } else if (o == Outcome.BUSY) {
                    busy.add(id);
                }
            }
            ids = busy;
        }
        return trimmed;
    }

    private void waitIdle() throws SQLException, InterruptedException {
        while (!idle.idle()) {
            Thread.sleep(idlePoll.toMillis());
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private List<String> candidates() throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM items WHERE status = 'ready'"
                    + " AND cover_bars_checked = 0 ORDER BY id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    /** The item's current media version if it is ready and still unchecked, else null. */
    private Integer waitingVersion(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT media_version FROM items WHERE id = ?"
                    + " AND status = 'ready' AND cover_bars_checked = 0")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : null;
                }
            }
        });
    }

    Outcome check(String id) throws SQLException {
        Integer version = waitingVersion(id);
        if (version == null) {
            return Outcome.SKIPPED;
        }
        Path itemDir = IngestPipeline.itemDir(dataDir, id);
        Path pending = itemDir.resolve(IngestPipeline.PENDING_COVER);
        if (Files.exists(pending)) {
            return Outcome.BUSY; // someone's new cover waits for its re-encode; check what that makes
        }
        Path cover = MediaVersions.versionDir(itemDir, version).resolve(IngestPipeline.COVER);
        BufferedImage image;
        try {
            image = Files.isRegularFile(cover) ? ImageIO.read(cover.toFile()) : null;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the cover of item " + id + ": " + e);
            image = null;
        }
        BufferedImage trimmed = image == null ? null : CoverBars.trim(image);
        if (trimmed == null || trimmed == image) {
            markChecked(id);
            return Outcome.NO_BARS;
        }
        Path tmp = itemDir.resolve(".cover-next-" + UUID.randomUUID() + ".jpg");
        try {
            CoverBars.writeJpeg(trimmed, tmp);
            Files.move(tmp, pending, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not write the cropped cover of item " + id + ": " + e);
            return Outcome.SKIPPED;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // a leftover dot file; harmless
            }
        }
        try {
            db.write(c -> {
                jobs.enqueueCover(id);
                markChecked(c, id);
                return null;
            });
        } catch (IngestJobs.ItemBusy e) {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException ignored) {
                // a leftover is used by the item's next re-encode
            }
            return Outcome.BUSY;
        }
        LOG.log(System.Logger.Level.INFO, "Covers: item " + id + " gets its cover without bars ("
                + image.getWidth() + "x" + image.getHeight() + " -> " + trimmed.getWidth() + "x" + trimmed.getHeight() + ")");
        return Outcome.TRIMMED;
    }

    private void markChecked(String id) throws SQLException {
        db.write(c -> {
            markChecked(c, id);
            return null;
        });
    }

    private static void markChecked(java.sql.Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET cover_bars_checked = 1 WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }
}
