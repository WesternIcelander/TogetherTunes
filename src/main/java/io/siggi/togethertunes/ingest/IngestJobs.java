package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code ingest_jobs} table: the DB-backed ingest queue. {@link IngestQueue} runs the jobs;
 * this class only moves rows between states, so every transition is one short transaction (none
 * hold a transaction open while a tool runs).
 *
 * <p>States: {@code queued → running → done | failed}. A running job interrupted by a shutdown
 * goes back to {@code queued} ({@link #release}); one left {@code running} by a crash is
 * requeued at the next start ({@link #recoverInterrupted}), up to {@link #MAX_ATTEMPTS} starts.
 *
 * <p>For kinds that own the item's status ({@link Kind#ownsItemStatus()}: everything but
 * {@code reencode}), the item follows the job: {@code pending} when queued, {@code processing}
 * while running, {@code ready} when done and {@code failed} (with {@code items.error}) when it
 * fails. A {@code reencode} job leaves the item alone.
 *
 * <p>Retention: a running job references its item, so a job that finishes or fails (of any
 * kind) sets the item's {@code last_referenced_at} to now ({@link References#touch}).
 *
 * <p>Quality re-encodes ({@code docs/library-and-ingest.md#presets}): a ready item in a library
 * whose files aren't the quality encode gets a {@code quality} job ({@link #enqueueQualityIfNeeded}),
 * checked when a job for it finishes, when it is added to a library, and at startup.
 */
public final class IngestJobs {
    /** A job is started at most this many times; a crash during the last start fails it. */
    public static final int MAX_ATTEMPTS = 3;
    /** {@code items.error} is cut to this many characters. */
    static final int MAX_ERROR_CHARS = 2000;

    private static final String COLUMNS =
            "id, item_id, kind, state, attempts, progress, created_at, started_at, finished_at, log, tier";

    private final Database db;
    private final Clock clock;
    private volatile ItemStatusListener statusListener = ItemStatusListener.NONE;
    private volatile Runnable enqueueListener = () -> { };
    private volatile Supplier<X264Preset> qualityPreset;

    public IngestJobs(Database db) {
        this(db, Clock.systemUTC());
    }

    public IngestJobs(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Queues a job for {@code itemId}, or returns the item's job that is already queued or
     * running (of any kind): an item has at most one unfinished job, and a second request for
     * it attaches to that one. Joins the caller's transaction, so an item row and its job can
     * be created together.
     */
    public IngestJob enqueue(String itemId, Kind kind) throws SQLException {
        return enqueue(itemId, kind, null);
    }

    /**
     * {@link #enqueue(String, Kind)} with the preset tier the caller asks for: {@code QUICK} for an
     * item wanted in a room now, {@code QUALITY} for one added straight to a library, null to
     * decide when the job starts ({@code docs/library-and-ingest.md#presets}). If the item already
     * has an unfinished job, that one is returned unchanged.
     */
    public IngestJob enqueue(String itemId, Kind kind, Tier tier) throws SQLException {
        Objects.requireNonNull(itemId);
        Objects.requireNonNull(kind);
        return db.write(c -> {
            IngestJob active = activeForItem(c, itemId);
            if (active != null) {
                return active;
            }
            String id = UuidV7.newId();
            long now = EpochMs.now(clock);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ingest_jobs"
                    + " (id, item_id, kind, state, attempts, progress, created_at, tier) VALUES (?, ?, ?, ?, 0, 0, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, itemId);
                ps.setString(3, kind.wire());
                ps.setString(4, State.QUEUED.wire());
                ps.setLong(5, now);
                ps.setString(6, tier == null ? null : tier.wire());
                ps.executeUpdate();
            }
            if (kind.ownsItemStatus()) {
                setItemStatus(c, itemId, "pending", null);
            }
            db.afterCommit(enqueueListener);
            return new IngestJob(id, itemId, kind, State.QUEUED, 0, 0, now, null, null, null, tier);
        });
    }

    /**
     * Who is told after a job was queued (once the transaction has committed): the ingest queue,
     * which wakes a worker or makes a re-encode give way ({@link IngestQueue}).
     */
    public void enqueueListener(Runnable listener) {
        this.enqueueListener = listener == null ? () -> { } : listener;
    }

    /**
     * Sets where the current quality preset comes from (the effective {@code ingest.qualityPreset},
     * {@link EncodePresets#quality()}). Until it is set, no {@code quality} jobs are queued.
     */
    public void qualityPreset(Supplier<X264Preset> preset) {
        this.qualityPreset = preset;
    }

    /**
     * Queues a {@code quality} job for {@code itemId} if it needs one: the item is a {@code ready}
     * video item (an audio item has no video to improve), in at least one library, its files aren't the quality encode ({@code encode_quality = 0}) and
     * weren't made with the current quality preset anyway, and it has no unfinished job (an
     * unfinished one checks again when it finishes). Joins the caller's transaction.
     *
     * @return whether a job was queued
     */
    public boolean enqueueQualityIfNeeded(String itemId) throws SQLException {
        Objects.requireNonNull(itemId);
        Supplier<X264Preset> preset = qualityPreset;
        if (preset == null) {
            return false;
        }
        String quality = preset.get().id();
        return db.write(c -> {
            if (activeForItem(c, itemId) != null) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items i WHERE id = ? AND "
                    + NEEDS_QUALITY)) {
                ps.setString(1, itemId);
                ps.setString(2, quality);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            enqueue(itemId, Kind.QUALITY, Tier.QUALITY);
            return true;
        });
    }

    /** The items (alias {@code i}) that need a quality encode; one parameter, the quality preset. */
    private static final String NEEDS_QUALITY = "i.status = 'ready' AND i.kind = 'video' AND i.encode_quality = 0"
            + " AND (i.encode_preset IS NULL OR i.encode_preset <> ?)"
            + " AND EXISTS (SELECT 1 FROM library_entries l WHERE l.item_id = i.id)";

    /**
     * Run at startup, after {@link #enqueueReencodes}: queues a {@code quality} job for every item
     * that needs one ({@link #enqueueQualityIfNeeded}), e.g. one whose quality job failed or was
     * cut short by a crash before it could be queued again.
     *
     * @return the number of jobs queued
     */
    public int enqueueQualityReencodes() throws SQLException {
        Supplier<X264Preset> preset = qualityPreset;
        if (preset == null) {
            return 0;
        }
        String quality = preset.get().id();
        return db.write(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM items i WHERE " + NEEDS_QUALITY
                    + " AND NOT EXISTS (SELECT 1 FROM ingest_jobs j WHERE j.item_id = i.id AND j.state IN (?, ?))"
                    + " ORDER BY added_at, id")) {
                ps.setString(1, quality);
                ps.setString(2, State.QUEUED.wire());
                ps.setString(3, State.RUNNING.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            for (String itemId : ids) {
                enqueue(itemId, Kind.QUALITY, Tier.QUALITY);
            }
            return ids.size();
        });
    }

    /**
     * Whether a job of a kind that owns its item's status (an upload, import or refetch: someone
     * is waiting for it) is queued. Re-encodes give way to such a job ({@link IngestQueue}).
     */
    public boolean hasQueuedNewItemJob() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ingest_jobs WHERE state = ?"
                    + " AND kind NOT IN (?, ?) LIMIT 1")) {
                ps.setString(1, State.QUEUED.wire());
                ps.setString(2, Kind.REENCODE.wire());
                ps.setString(3, Kind.QUALITY.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Queues a {@code reencode} job for every {@code ready} item whose files were encoded for
     * another output encoding (profile or video bitrate) than {@code encoding}, unless the item already has an
     * unfinished job. Purged items are skipped (a refetch or new upload encodes for the current
     * profile anyway), and so are items that are pending, processing or failed (their next ingest
     * does too). Run at startup, before the workers start; a re-encode that failed is therefore
     * tried again at the next start.
     *
     * @return the number of jobs queued
     */
    public int enqueueReencodes(OutputEncoding encoding) throws SQLException {
        Objects.requireNonNull(encoding);
        return db.write(c -> {
            List<String> stale = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM items i WHERE status = 'ready'"
                    + " AND output_profile <> ?"
                    + " AND NOT EXISTS (SELECT 1 FROM ingest_jobs j WHERE j.item_id = i.id AND j.state IN (?, ?))"
                    + " ORDER BY added_at, id")) {
                ps.setString(1, encoding.id());
                ps.setString(2, State.QUEUED.wire());
                ps.setString(3, State.RUNNING.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stale.add(rs.getString(1));
                    }
                }
            }
            for (String itemId : stale) {
                enqueue(itemId, Kind.REENCODE);
            }
            return stale.size();
        });
    }

    /**
     * Sets who is told when this class changes an item's status (after the commit): the rooms,
     * once they exist ({@code Main} starts ingest before them).
     */
    public void statusListener(ItemStatusListener listener) {
        this.statusListener = listener == null ? ItemStatusListener.NONE : listener;
    }

    /** The job with this id. */
    public Optional<IngestJob> get(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM ingest_jobs WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return Optional.ofNullable(rs.next() ? row(rs) : null);
                }
            }
        });
    }

    /** The item's most recent job (the one {@code GET /api/items/{id}/job} reports). */
    public Optional<IngestJob> latestForItem(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                    + " FROM ingest_jobs WHERE item_id = ? ORDER BY created_at DESC, id DESC LIMIT 1")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return Optional.ofNullable(rs.next() ? row(rs) : null);
                }
            }
        });
    }

    /** Jobs in {@code state}, oldest first, at most {@code limit}. */
    public List<IngestJob> list(State state, int limit) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                    + " FROM ingest_jobs WHERE state = ? ORDER BY created_at, id LIMIT ?")) {
                ps.setString(1, state.wire());
                ps.setInt(2, limit);
                List<IngestJob> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(row(rs));
                    }
                }
                return out;
            }
        });
    }

    /**
     * The order in which workers take queued jobs ({@link #claimNext}), as an SQL {@code ORDER BY}
     * list over {@code ingest_jobs} columns (unqualified): new-item jobs, then {@code reencode},
     * then {@code quality}; oldest first within each. The jobs page lists the queue in this order
     * ({@link JobList}), so whatever changes the claim order must change both.
     */
    static final String CLAIM_ORDER = "CASE WHEN kind = '" + Kind.QUALITY.wire() + "' THEN 2 WHEN kind = '"
            + Kind.REENCODE.wire() + "' THEN 1 ELSE 0 END, created_at, id";

    /**
     * Takes the oldest queued job: sets it {@code running}, counts the attempt, resets its
     * progress and marks its item {@code processing}. Returns null if nothing is queued. Safe
     * to call from several workers (and processes): a job is handed to exactly one of them.
     * {@code reencode} jobs come after every other kind, so that a library re-encode after an
     * output profile change doesn't hold up new uploads and imports, and {@code quality} jobs come
     * last of all.
     */
    public IngestJob claimNext() throws SQLException {
        while (true) {
            IngestJob candidate = db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                        + " FROM ingest_jobs WHERE state = ?"
                        + " ORDER BY " + CLAIM_ORDER + " LIMIT 1")) {
                    ps.setString(1, State.QUEUED.wire());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? row(rs) : null;
                    }
                }
            });
            if (candidate == null) {
                return null;
            }
            long now = EpochMs.now(clock);
            IngestJob claimed = db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE ingest_jobs"
                        + " SET state = ?, attempts = attempts + 1, progress = 0, started_at = ?, finished_at = NULL"
                        + " WHERE id = ? AND state = ?")) {
                    ps.setString(1, State.RUNNING.wire());
                    ps.setLong(2, now);
                    ps.setString(3, candidate.id());
                    ps.setString(4, State.QUEUED.wire());
                    if (ps.executeUpdate() != 1) {
                        return null; // another worker took it first
                    }
                }
                if (candidate.kind().ownsItemStatus()) {
                    setItemStatus(c, candidate.itemId(), "processing", null);
                }
                return new IngestJob(candidate.id(), candidate.itemId(), candidate.kind(), State.RUNNING,
                        candidate.attempts() + 1, 0, candidate.createdAt(), now, null, candidate.log(), candidate.tier());
            });
            if (claimed != null) {
                return claimed;
            }
        }
    }

    /** Stores progress (clamped to 0..1) and the log tail of a running job. */
    public void saveProgress(String id, double progress, String log) throws SQLException {
        double p = Double.isNaN(progress) ? 0 : Math.max(0, Math.min(1, progress));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_jobs SET progress = ?, log = ? WHERE id = ? AND state = ?")) {
                ps.setDouble(1, p);
                ps.setString(2, log);
                ps.setString(3, id);
                ps.setString(4, State.RUNNING.wire());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Marks a running job {@code done} (progress 1) and, for kinds that own it, its item
     * {@code ready}; then queues a quality re-encode if the item needs one
     * ({@link #enqueueQualityIfNeeded}). Returns false if the job wasn't running.
     */
    public boolean complete(String id, String log) throws SQLException {
        return finish(id, State.DONE, null, log);
    }

    /**
     * Marks a running job {@code failed} and, for kinds that own it, its item {@code failed}
     * with {@code error}. Returns false if the job wasn't running.
     */
    public boolean fail(String id, String error, String log) throws SQLException {
        return finish(id, State.FAILED, error == null || error.isBlank() ? "Ingest failed" : error, log);
    }

    private boolean finish(String id, State state, String error, String log) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            IngestJob job = lock(c, id);
            if (job == null || job.state() != State.RUNNING) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_jobs SET state = ?, progress = ?, finished_at = ?, log = ? WHERE id = ?")) {
                ps.setString(1, state.wire());
                ps.setDouble(2, state == State.DONE ? 1 : job.progress());
                ps.setLong(3, now);
                ps.setString(4, log);
                ps.setString(5, id);
                ps.executeUpdate();
            }
            if (job.kind().ownsItemStatus()) {
                setItemStatus(c, job.itemId(), state == State.DONE ? "ready" : "failed", error);
            }
            // A running job references its item; it stops doing so now.
            References.touch(c, job.itemId(), now);
            if (state == State.DONE) {
                enqueueQualityIfNeeded(job.itemId());
            }
            return true;
        });
    }

    /**
     * Puts a running job back in the queue because the server is stopping, not because the job
     * failed: the attempt isn't counted. Returns false if the job wasn't running.
     */
    public boolean release(String id, String log) throws SQLException {
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE ingest_jobs"
                    + " SET state = ?, attempts = CASE WHEN attempts > 0 THEN attempts - 1 ELSE 0 END,"
                    + " progress = 0, log = ? WHERE id = ? AND state = ?")) {
                ps.setString(1, State.QUEUED.wire());
                ps.setString(2, log);
                ps.setString(3, id);
                ps.setString(4, State.RUNNING.wire());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Writes the audit row of an admin's job action, in the action's transaction. */
    @FunctionalInterface
    public interface AuditHook {
        void write(Connection c, IngestJob job) throws SQLException;
    }

    /** What {@link #retry} did. */
    public enum RetryOutcome {
        /** A new job was queued (or the item's unfinished job returned). */
        QUEUED,
        /** No job with this id. */
        NOT_FOUND,
        /** The job isn't {@code failed}. */
        NOT_FAILED,
        /** The item has a newer job than this one (retried already, or queued again another way). */
        SUPERSEDED,
        /** The item was purged since; a YouTube item comes back by adding it to a library again. */
        ITEM_PURGED
    }

    /** {@link #retry}'s outcome and, for {@link RetryOutcome#QUEUED}, the new job. */
    public record Retry(RetryOutcome outcome, IngestJob job) {
    }

    /**
     * Retries a failed job ({@code docs/library-and-ingest.md#encode-jobs-page}): queues a new job of
     * the same kind and tier for its item, which (for kinds that own it) sets the item
     * {@code pending} again. Only the item's latest job can be retried, and not once the item is
     * purged. An upload retries from its staging directory, which a failed job leaves in place;
     * a YouTube job downloads again. {@code audit} runs in the same transaction when a job is queued.
     */
    public Retry retry(String jobId, AuditHook audit) throws SQLException {
        Objects.requireNonNull(jobId);
        return db.write(c -> {
            IngestJob job = lock(c, jobId);
            if (job == null) {
                return new Retry(RetryOutcome.NOT_FOUND, null);
            }
            if (job.state() != State.FAILED) {
                return new Retry(RetryOutcome.NOT_FAILED, null);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM ingest_jobs WHERE item_id = ?"
                    + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
                ps.setString(1, job.itemId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !job.id().equals(rs.getString(1))) {
                        return new Retry(RetryOutcome.SUPERSEDED, null);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT status FROM items WHERE id = ?")) {
                ps.setString(1, job.itemId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || "purged".equals(rs.getString(1))) {
                        return new Retry(RetryOutcome.ITEM_PURGED, null);
                    }
                }
            }
            IngestJob queued = enqueue(job.itemId(), job.kind(), job.tier());
            if (audit != null) {
                audit.write(c, job);
            }
            return new Retry(RetryOutcome.QUEUED, queued);
        });
    }

    /** What {@link #cancel} did. */
    public enum CancelOutcome {
        CANCELLED, NOT_FOUND,
        /** The job is running or finished: only a queued job can be cancelled. */
        NOT_QUEUED
    }

    /** {@code items.error} of an item whose ingest an admin cancelled. */
    public static final String CANCELLED_ERROR = "Cancelled by an administrator";

    /**
     * Cancels a queued job: it becomes {@code failed} (with a log line saying so), so it can be
     * {@linkplain #retry retried} like any failed job. For a kind that owns the item's status (a new
     * item's ingest) the item becomes {@code failed} with {@link #CANCELLED_ERROR}; a
     * {@code reencode} or {@code quality} job is only dropped and the item stays {@code ready}
     * (both come back at the next start, like failed ones, while the item still needs them).
     * {@code audit} runs in the same transaction.
     */
    public CancelOutcome cancel(String jobId, AuditHook audit) throws SQLException {
        Objects.requireNonNull(jobId);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            IngestJob job = lock(c, jobId);
            if (job == null) {
                return CancelOutcome.NOT_FOUND;
            }
            if (job.state() != State.QUEUED) {
                return CancelOutcome.NOT_QUEUED;
            }
            JobLog log = JobLog.resume(job.log());
            log.append("[ingest] Cancelled by an administrator at " + EpochMs.format(now));
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_jobs SET state = ?, finished_at = ?, log = ? WHERE id = ? AND state = ?")) {
                ps.setString(1, State.FAILED.wire());
                ps.setLong(2, now);
                ps.setString(3, log.tail());
                ps.setString(4, jobId);
                ps.setString(5, State.QUEUED.wire());
                if (ps.executeUpdate() != 1) {
                    return CancelOutcome.NOT_QUEUED;
                }
            }
            if (job.kind().ownsItemStatus()) {
                setItemStatus(c, job.itemId(), "failed", CANCELLED_ERROR);
            }
            if (audit != null) {
                audit.write(c, job);
            }
            return CancelOutcome.CANCELLED;
        });
    }

    /** What {@link #recoverInterrupted} did. */
    public record Recovered(int requeued, int failed) {
    }

    /**
     * Run at startup, before any worker: every job still {@code running} was interrupted by a
     * crash (or a kill). It is queued again, or failed if it has already been started
     * {@link #MAX_ATTEMPTS} times (so a job that brings the server down can't do so forever).
     * A line saying so is added to its log.
     */
    public Recovered recoverInterrupted() throws SQLException {
        return db.write(c -> {
            List<IngestJob> running = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                    + " FROM ingest_jobs WHERE state = ? ORDER BY created_at, id")) {
                ps.setString(1, State.RUNNING.wire());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        running.add(row(rs));
                    }
                }
            }
            int requeued = 0;
            int failed = 0;
            for (IngestJob job : running) {
                JobLog log = JobLog.resume(job.log());
                if (job.attempts() >= MAX_ATTEMPTS) {
                    String error = "Interrupted " + job.attempts() + " times (the server stopped during the job)";
                    log.append("[ingest] " + error + "; giving up");
                    long now = EpochMs.now(clock);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ingest_jobs SET state = ?, finished_at = ?, log = ? WHERE id = ?")) {
                        ps.setString(1, State.FAILED.wire());
                        ps.setLong(2, now);
                        ps.setString(3, log.tail());
                        ps.setString(4, job.id());
                        ps.executeUpdate();
                    }
                    if (job.kind().ownsItemStatus()) {
                        setItemStatus(c, job.itemId(), "failed", error);
                    }
                    References.touch(c, job.itemId(), now);
                    failed++;
                } else {
                    log.append("[ingest] Interrupted (the server stopped during attempt " + job.attempts()
                            + "); queued again");
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE ingest_jobs SET state = ?, progress = 0, log = ? WHERE id = ?")) {
                        ps.setString(1, State.QUEUED.wire());
                        ps.setString(2, log.tail());
                        ps.setString(3, job.id());
                        ps.executeUpdate();
                    }
                    if (job.kind().ownsItemStatus()) {
                        setItemStatus(c, job.itemId(), "pending", null);
                    }
                    requeued++;
                }
            }
            return new Recovered(requeued, failed);
        });
    }

    private IngestJob activeForItem(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                + " FROM ingest_jobs WHERE item_id = ? AND state IN (?, ?) ORDER BY created_at, id LIMIT 1")) {
            ps.setString(1, itemId);
            ps.setString(2, State.QUEUED.wire());
            ps.setString(3, State.RUNNING.wire());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    /** Reads a job inside a write transaction (SQLite: the write lock is held already). */
    private IngestJob lock(Connection c, String id) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM ingest_jobs WHERE id = ?"
                + (db.dialect() instanceof PostgresDialect ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    /**
     * Sets the item's status and error; the {@link #statusListener} hears about it once the
     * transaction (the caller's, if {@link #enqueue} joined one) has committed.
     */
    private void setItemStatus(Connection c, String itemId, String status, String error) throws SQLException {
        String e = error != null && error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) + "…" : error;
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = ?, error = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, e);
            ps.setString(3, itemId);
            ps.executeUpdate();
        }
        db.afterCommit(() -> statusListener.itemStatusChanged(itemId));
    }

    private static IngestJob row(ResultSet rs) throws SQLException {
        return new IngestJob(rs.getString("id"), rs.getString("item_id"), Kind.fromWire(rs.getString("kind")),
                State.fromWire(rs.getString("state")), rs.getInt("attempts"), rs.getDouble("progress"),
                rs.getLong("created_at"), EpochMs.get(rs, "started_at"), EpochMs.get(rs, "finished_at"),
                rs.getString("log"), Tier.fromWire(rs.getString("tier")));
    }
}
