package io.siggi.togethertunes.retention;

import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.library.Items;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The retention sweeper ({@code docs/libraries.md#retention}): once an hour it purges every
 * {@code ready} item that nothing references and whose {@code last_referenced_at} is older than
 * {@code retention.unreferencedDays} (read at each sweep; {@code 0} disables it).
 *
 * <p>Referenced means: in a library, in a playlist, queued/suggested/playing in a room, or with
 * a running ingest job. Candidates are selected outside any transaction, then each one is purged
 * through {@link Items#purge}, whose check re-runs the same predicate for that item inside the
 * purge transaction, after the item row is locked ({@code FOR UPDATE} on PostgreSQL; SQLite
 * serialises writers). An item that gained a reference, or was touched, in between is left
 * alone. The purge deletes {@code media/<itemId>} (and the item's overlay caches, through
 * {@code Items.purge}'s purge listener), marks the row {@code purged} with {@code purged_at}, and writes an
 * {@code item.purged} audit row without an actor ({@code reason: "retention"}).
 *
 * <p><b>Auto-delete</b> ({@code docs/media-types.md#views-auto-delete-and-pins}): the same sweep
 * also purges {@code ready} {@code movie}/{@code show} items that were never viewed and were added
 * more than {@code retention.videoUnviewedDays} ago, or whose most recent view is more than
 * {@code retention.videoAfterViewHours} old (each {@code 0} = off). Library and playlist entries
 * don't protect them; an entry current, queued or suggested in a room and a running ingest job
 * do. Same re-check inside the purge transaction, audit {@code reason: "auto-delete"}.
 *
 * <p><b>Pinned items</b> ({@code items.pinned_at}) are never purged by either rule.
 *
 * <p>Remaining race (both dialects, accepted): an add that read the item as {@code ready} just
 * before the sweep commits its library/playlist entry just after it; the entry then shows the
 * item as "media expired", as after a manual purge. It needs an item that was unreferenced for
 * the whole retention period being added in the same instant.
 */
public final class RetentionSweeper implements AutoCloseable {
    /** How often the sweeper runs. */
    public static final Duration INTERVAL = Duration.ofHours(1);
    /** Delay of the first sweep after start, so startup isn't slowed down by it. */
    public static final Duration FIRST_DELAY = Duration.ofMinutes(1);
    /** Details value of the sweeper's audit rows. */
    public static final String REASON = "retention";
    /** Details value of the auto-delete audit rows. */
    public static final String REASON_AUTO_DELETE = "auto-delete";

    static final int PAGE = 500;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long HOUR_MS = 60L * 60 * 1000;

    private static final System.Logger LOG = System.getLogger(RetentionSweeper.class.getName());

    /** The {@code retention.unreferencedDays} setting, read at each sweep. */
    @FunctionalInterface
    public interface Days {
        int get() throws SQLException;
    }

    /** The three retention settings, read at each sweep; each {@code 0} disables its rule. */
    public record Limits(int unreferencedDays, int videoUnviewedDays, int videoAfterViewHours) {
    }

    /** Reads the {@link Limits} at each sweep. */
    @FunctionalInterface
    public interface Policy {
        Limits get() throws SQLException;
    }

    /** Other housekeeping run after each scheduled sweep (e.g. trimming room request histories). */
    @FunctionalInterface
    public interface Chore {
        /** @return how many rows or files it removed (logged when not 0) */
        int run() throws Exception;
    }

    private record NamedChore(String name, Chore chore) {
    }

    private final List<NamedChore> chores = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** What one sweep did. */
    public record Result(int purged, int skipped, int failed) {
    }

    /** Thrown by the purge check to roll back the purge of an item that is referenced again. */
    private static final class StillReferenced extends RuntimeException {
        StillReferenced() {
            super(null, null, false, false);
        }
    }

    /**
     * The retention predicate, on {@code items i}: ready, unreferenced, and last referenced
     * before the cutoff (parameter 1).
     */
    private static final String ELIGIBLE = "i.status = 'ready' AND i.last_referenced_at < ?"
            + " AND NOT EXISTS (SELECT 1 FROM library_entries le WHERE le.item_id = i.id)"
            + " AND NOT EXISTS (SELECT 1 FROM playlist_entries pe WHERE pe.item_id = i.id)"
            + " AND NOT EXISTS (SELECT 1 FROM queue_entries qe WHERE qe.item_id = i.id"
            + " AND qe.state IN ('queued', 'suggested', 'playing'))"
            + " AND NOT EXISTS (SELECT 1 FROM ingest_jobs j WHERE j.item_id = i.id AND j.state = '"
            + IngestJob.State.RUNNING.wire() + "')"
            + " AND i.pinned_at IS NULL";

    /**
     * The auto-delete predicate, on {@code items i}: a ready, unpinned movie or show, never viewed
     * and added before cutoff 1, or last viewed before cutoff 2 (a disabled rule's cutoff is
     * {@link Long#MIN_VALUE}), not current/queued/suggested in a room and without a running job.
     */
    private static final String AUTO_DELETE = "i.status = 'ready' AND i.media_type IN ('movie', 'show')"
            + " AND i.pinned_at IS NULL"
            + " AND ((i.last_viewed_at IS NULL AND i.added_at < ?) OR i.last_viewed_at < ?)"
            + " AND NOT EXISTS (SELECT 1 FROM queue_entries qe WHERE qe.item_id = i.id"
            + " AND qe.state IN ('queued', 'suggested', 'playing'))"
            + " AND NOT EXISTS (SELECT 1 FROM ingest_jobs j WHERE j.item_id = i.id AND j.state = '"
            + IngestJob.State.RUNNING.wire() + "')";

    private final Database db;
    private final Items items;
    private final Policy policy;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    /** Tests: candidates per page. */
    int pageSize = PAGE;
    /** Tests: runs after each non-empty page of candidates is selected, before any of them is purged. */
    volatile SelectHook afterSelect = ids -> { };

    @FunctionalInterface
    interface SelectHook {
        void selected(List<String> ids) throws SQLException;
    }

    /** Only the unreferenced-items rule (auto-delete off). */
    public RetentionSweeper(Database db, Items items, Days days, Clock clock) {
        this(db, items, (Policy) () -> new Limits(days.get(), 0, 0), clock);
        Objects.requireNonNull(days);
    }

    public RetentionSweeper(Database db, Items items, Policy policy, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.items = Objects.requireNonNull(items);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Runs {@code chore} after each scheduled sweep (also when the sweep failed or is disabled);
     * a failing chore is logged and doesn't stop the others.
     *
     * @param name what it does, for the log (e.g. "trimmed room history entries")
     */
    public void alsoRun(String name, Chore chore) {
        chores.add(new NamedChore(Objects.requireNonNull(name), Objects.requireNonNull(chore)));
    }

    /** Starts sweeping: first after {@link #FIRST_DELAY}, then every {@link #INTERVAL}. */
    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("retention-sweeper").factory());
        scheduler.scheduleWithFixedDelay(this::sweepLogged, FIRST_DELAY.toMillis(), INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** Stops the schedule; a running sweep is interrupted and finishes its current item. */
    @Override
    public synchronized void close() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    private void sweepLogged() {
        try {
            Result r = sweep();
            if (r.purged() > 0 || r.failed() > 0) {
                LOG.log(System.Logger.Level.INFO, "Retention: purged " + r.purged() + " items"
                        + (r.failed() > 0 ? ", " + r.failed() + " failed" : ""));
            }
        } catch (Throwable t) {
            // Never let the schedule die; the next sweep tries again.
            LOG.log(System.Logger.Level.WARNING, "Retention sweep failed: " + t, t);
        }
        runChores();
    }

    /** Runs the chores added with {@link #alsoRun} once, each failure logged. */
    void runChores() {
        for (NamedChore c : chores) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                int n = c.chore().run();
                if (n > 0) {
                    LOG.log(System.Logger.Level.INFO, "Retention: " + c.name() + ": " + n);
                }
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Retention: " + c.name() + " failed: " + t, t);
            }
        }
    }

    /**
     * One sweep: the unreferenced-items rule, then auto-delete of movies and shows. A rule whose
     * setting is 0 does nothing. An item whose purge fails (a file error, a database error) is
     * logged and skipped; the next sweep tries again.
     *
     * @throws SQLException if the settings or the candidates can't be read
     */
    public Result sweep() throws SQLException {
        Limits limits = policy.get();
        long now = EpochMs.now(clock);
        int[] counts = new int[3];
        int unreferencedDays = limits.unreferencedDays();
        if (unreferencedDays > 0) {
            long cutoff = now - unreferencedDays * DAY_MS;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", REASON);
            extra.put("unreferencedDays", unreferencedDays);
            pass(ELIGIBLE, new long[] {cutoff}, extra, now, counts);
        }
        int unviewedDays = limits.videoUnviewedDays();
        int afterViewHours = limits.videoAfterViewHours();
        if (unviewedDays > 0 || afterViewHours > 0) {
            long unviewedCutoff = unviewedDays > 0 ? now - unviewedDays * DAY_MS : Long.MIN_VALUE;
            long viewedCutoff = afterViewHours > 0 ? now - afterViewHours * HOUR_MS : Long.MIN_VALUE;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", REASON_AUTO_DELETE);
            extra.put("videoUnviewedDays", unviewedDays);
            extra.put("videoAfterViewHours", afterViewHours);
            pass(AUTO_DELETE, new long[] {unviewedCutoff, viewedCutoff}, extra, now, counts);
        }
        return new Result(counts[0], counts[1], counts[2]);
    }

    /** Purges every item matching {@code predicate} (on {@code items i}, parameters {@code cutoffs}). */
    private void pass(String predicate, long[] cutoffs, Map<String, Object> extra, long now, int[] counts)
            throws SQLException {
        String after = "";
        while (!Thread.currentThread().isInterrupted()) {
            List<String> page = candidates(predicate, cutoffs, after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            afterSelect.selected(page);
            for (String itemId : page) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    items.purge(itemId, (c, id, status) -> {
                        if (!stillEligible(c, predicate, cutoffs, id)) {
                            throw new StillReferenced();
                        }
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("previousStatus", status);
                        details.putAll(extra);
                        AuditLog.write(c, now, null, null, null, Items.AUDIT_PURGED, id, details);
                    });
                    counts[0]++;
                } catch (StillReferenced | HttpError e) {
                    // Referenced (or viewed, or pinned) again since the selection (an HttpError: a job started).
                    counts[1]++;
                } catch (SQLException | IOException | RuntimeException e) {
                    counts[2]++;
                    LOG.log(System.Logger.Level.WARNING, "Retention: could not purge item " + itemId + ": " + e, e);
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.get(page.size() - 1);
        }
    }

    /** Item ids matching {@code predicate} after {@code after}, in id order. */
    private List<String> candidates(String predicate, long[] cutoffs, String after, int limit) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.id FROM items i WHERE " + predicate
                    + " AND i.id > ? ORDER BY i.id LIMIT ?")) {
                int n = 1;
                for (long cutoff : cutoffs) {
                    ps.setLong(n++, cutoff);
                }
                ps.setString(n++, after);
                ps.setInt(n, limit);
                List<String> ids = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
                return ids;
            }
        });
    }

    private static boolean stillEligible(Connection c, String predicate, long[] cutoffs, String itemId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items i WHERE " + predicate + " AND i.id = ?")) {
            int n = 1;
            for (long cutoff : cutoffs) {
                ps.setLong(n++, cutoff);
            }
            ps.setString(n, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
