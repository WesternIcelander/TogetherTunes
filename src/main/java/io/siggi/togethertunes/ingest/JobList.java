package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.ingest.IngestJob.Tier;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The encode jobs page's lists ({@code docs/library-and-ingest.md#encode-jobs-page}): ingest jobs
 * with their item, for an admin (every job) or for one user (only items in that user's library).
 * Read only; {@link IngestJobs} changes jobs.
 *
 * <ul>
 *   <li>{@link View#ACTIVE}: running jobs (longest running first), then the queue in the order
 *       workers take it ({@link IngestJobs#CLAIM_ORDER}), each queued job with its 1-based
 *       position in the <em>whole</em> queue (also in a user's list, so "3rd in line" counts
 *       other people's jobs ahead of it without naming them).</li>
 *   <li>{@link View#FAILED}: failed jobs that are still their item's latest job (a failure that a
 *       later job replaced is history), of items that aren't purged; latest failure first.</li>
 *   <li>{@link View#DONE}: jobs that finished successfully in the last {@link #RECENT}, latest first.</li>
 * </ul>
 * Rows never carry the job log ({@link IngestJobs#get} does, for admins).
 */
public final class JobList {
    /** How far back {@link View#DONE} reaches. */
    public static final Duration RECENT = Duration.ofHours(24);

    /** Which jobs a list shows. */
    public enum View {
        ACTIVE, FAILED, DONE;

        /** The {@code ?state=} value. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The view with this {@code ?state=} value, if any. */
        public static Optional<View> fromWire(String s) {
            for (View v : values()) {
                if (v.wire().equals(s)) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The item of a job, as the jobs page shows it.
     *
     * @param error {@code items.error}, only while the item is {@code failed}
     */
    public record ItemRef(String id, String kind, String title, String artist, String source, String status,
                          String error) {
    }

    /**
     * One job in a list.
     *
     * @param job      the job, without its log
     * @param position 1-based place in the queue for a queued job, else null
     */
    public record Row(IngestJob job, Integer position, ItemRef item) {
    }

    /** A page of rows; {@code nextOffset} is null on the last page. */
    public record Page(List<Row> rows, Integer nextOffset) {
    }

    private static final String SELECT = "SELECT j.id, j.item_id, j.kind, j.state, j.attempts, j.progress,"
            + " j.created_at, j.started_at, j.finished_at, j.tier, q.pos,"
            + " i.kind AS item_kind, i.title, i.artist, i.source, i.status, i.error"
            + " FROM ingest_jobs j JOIN items i ON i.id = j.item_id"
            + " LEFT JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY " + IngestJobs.CLAIM_ORDER + ") AS pos"
            + " FROM ingest_jobs WHERE state = 'queued') q ON q.id = j.id";

    /** Only items in one user's library (one parameter: the user id). */
    private static final String IN_LIBRARY =
            " AND EXISTS (SELECT 1 FROM library_entries le WHERE le.item_id = j.item_id AND le.user_id = ?)";

    private final Database db;
    private final Clock clock;

    public JobList(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * A page of {@code view}, starting at row {@code offset}.
     *
     * @param userId only items in this user's library (any entry, shares included), or null for every job
     */
    public Page list(View view, String userId, int offset, int limit) throws SQLException {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset " + offset + ", limit " + limit);
        }
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ");
        List<Object> params = new ArrayList<>();
        switch (view) {
            case ACTIVE -> sql.append("j.state IN ('running', 'queued')");
            case FAILED -> sql.append("j.state = 'failed' AND i.status <> 'purged' AND NOT EXISTS (SELECT 1 FROM"
                    + " ingest_jobs k WHERE k.item_id = j.item_id AND (k.created_at > j.created_at"
                    + " OR (k.created_at = j.created_at AND k.id > j.id)))");
            case DONE -> {
                sql.append("j.state = 'done' AND j.finished_at >= ?");
                params.add(EpochMs.now(clock) - RECENT.toMillis());
            }
        }
        if (userId != null) {
            sql.append(IN_LIBRARY);
            params.add(userId);
        }
        sql.append(switch (view) {
            case ACTIVE -> " ORDER BY CASE WHEN j.state = 'running' THEN 0 ELSE 1 END,"
                    + " CASE WHEN j.state = 'running' THEN j.started_at ELSE 0 END, q.pos, j.id";
            case FAILED, DONE -> " ORDER BY j.finished_at DESC, j.id DESC";
        });
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit + 1);
        params.add(offset);
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                List<Row> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(row(rs));
                    }
                }
                Integer next = null;
                if (rows.size() > limit) {
                    rows.remove(rows.size() - 1);
                    next = offset + limit;
                }
                return new Page(rows, next);
            }
        });
    }

    /** One job (any state) with its item, or empty; without the log. */
    public Optional<Row> get(String jobId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE j.id = ?")) {
                ps.setString(1, jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    return Optional.ofNullable(rs.next() ? row(rs) : null);
                }
            }
        });
    }

    private static Row row(ResultSet rs) throws SQLException {
        IngestJob job = new IngestJob(rs.getString("id"), rs.getString("item_id"), Kind.fromWire(rs.getString("kind")),
                State.fromWire(rs.getString("state")), rs.getInt("attempts"), rs.getDouble("progress"),
                rs.getLong("created_at"), EpochMs.get(rs, "started_at"), EpochMs.get(rs, "finished_at"), null,
                Tier.fromWire(rs.getString("tier")));
        long pos = rs.getLong("pos");
        Integer position = rs.wasNull() ? null : (int) pos;
        String status = rs.getString("status");
        ItemRef item = new ItemRef(rs.getString("item_id"), rs.getString("item_kind"), rs.getString("title"),
                rs.getString("artist"), rs.getString("source"), status,
                "failed".equals(status) ? rs.getString("error") : null);
        return new Row(job, position, item);
    }
}
