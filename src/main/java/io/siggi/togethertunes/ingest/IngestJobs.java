package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
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
 */
public final class IngestJobs {
    /** A job is started at most this many times; a crash during the last start fails it. */
    public static final int MAX_ATTEMPTS = 3;
    /** {@code items.error} is cut to this many characters. */
    static final int MAX_ERROR_CHARS = 2000;

    private static final String COLUMNS =
            "id, item_id, kind, state, attempts, progress, created_at, started_at, finished_at, log";

    private final Database db;
    private final Clock clock;
    private volatile ItemStatusListener statusListener = ItemStatusListener.NONE;

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
                    + " (id, item_id, kind, state, attempts, progress, created_at) VALUES (?, ?, ?, ?, 0, 0, ?)")) {
                ps.setString(1, id);
                ps.setString(2, itemId);
                ps.setString(3, kind.wire());
                ps.setString(4, State.QUEUED.wire());
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            if (kind.ownsItemStatus()) {
                setItemStatus(c, itemId, "pending", null);
            }
            return new IngestJob(id, itemId, kind, State.QUEUED, 0, 0, now, null, null, null);
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
     * Takes the oldest queued job: sets it {@code running}, counts the attempt, resets its
     * progress and marks its item {@code processing}. Returns null if nothing is queued. Safe
     * to call from several workers (and processes): a job is handed to exactly one of them.
     * {@code reencode} jobs come after every other kind, so that a library re-encode after an
     * output profile change doesn't hold up new uploads and imports.
     */
    public IngestJob claimNext() throws SQLException {
        while (true) {
            IngestJob candidate = db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                        + " FROM ingest_jobs WHERE state = ?"
                        + " ORDER BY CASE WHEN kind = ? THEN 1 ELSE 0 END, created_at, id LIMIT 1")) {
                    ps.setString(1, State.QUEUED.wire());
                    ps.setString(2, Kind.REENCODE.wire());
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
                        candidate.attempts() + 1, 0, candidate.createdAt(), now, null, candidate.log());
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
     * {@code ready}. Returns false if the job wasn't running.
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
                rs.getString("log"));
    }
}
