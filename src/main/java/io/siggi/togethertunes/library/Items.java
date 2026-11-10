package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.ItemStatusListener;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One item by id ({@code docs/api.md#items-libraries-playlists-sharing}): its metadata page,
 * metadata edits, its latest ingest job, and purging it.
 *
 * <p><b>Who may edit</b> ({@code docs/libraries.md#items-vs-libraries}): the user who added the
 * item ({@code items.added_by_user_id}), while it is in their library, and anyone with
 * {@code library.editAny}. Guests have no library, so a guest who added an item can't edit it.
 * Editable: title, artist, album and {@code presentAsAudio} (video items only).
 *
 * <p><b>Purging</b> ({@code library.purge}, and the retention sweeper, {@code retention.RetentionSweeper}): the item becomes
 * {@code purged} (row and metadata kept, {@code purged_at} set) and its files are deleted. Queued
 * ingest jobs of the item are cancelled (marked {@code failed}); while a job is <em>running</em>,
 * the item can't be purged ({@code 409 item_busy}). In the same transaction the item directory
 * {@code media/<itemId>} and its upload staging directory are renamed aside
 * ({@link IngestPipeline#moveAside}), so a refetch or re-upload that starts after the commit
 * can't lose its new files to the delete; they are deleted after the commit (leftovers of a crash
 * by {@link IngestPipeline#recoverDirectories}). Library and playlist entries stay: the item
 * shows as "media expired". Purging an already purged item deletes any leftover files again.
 */
public final class Items {
    /** {@code audit_log} action of a metadata edit. */
    public static final String AUDIT_UPDATED = "item.updated";
    /** {@code audit_log} action of a purge by a person ({@code library.purge}). */
    public static final String AUDIT_PURGED = "item.purged";

    private static final System.Logger LOG = System.getLogger(Items.class.getName());

    private final Database db;
    private final DataDir dataDir;
    private final Clock clock;
    private volatile ItemStatusListener statusListener = ItemStatusListener.NONE;

    public Items(Database db, DataDir dataDir, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * An item page: the item, and what the caller can do with it.
     *
     * @param addedAt when the item was first added (epoch ms)
     * @param error   why the last ingest failed ({@code status = failed}), else null
     * @param you     the caller's relation to the item
     */
    public record ItemPage(String id, String kind, String title, String artist, String album, Long durationMs,
                           String source, String youtubeId, String status, boolean presentAsAudio, long addedAt,
                           String error, You you) {
    }

    /**
     * The caller's relation to an item. Nothing about other people (not even who added it).
     *
     * @param inLibrary whether the item is in the caller's library (always false for guests)
     * @param canEdit   whether the caller may edit its metadata
     * @param canPurge  whether the caller holds {@code library.purge}
     */
    public record You(boolean inLibrary, boolean canEdit, boolean canPurge) {
    }

    /**
     * An item's latest ingest job, for its status display.
     *
     * @param progress 0..1
     * @param log      the job log's tail; only for callers who may edit the item, else null
     */
    public record JobView(String id, String kind, String state, double progress, int attempts, long createdAt,
                          Long startedAt, Long finishedAt, String log) {
    }

    /**
     * A metadata edit; a null field is left unchanged. {@code artist}/{@code album} are cleared
     * by {@link #clearArtist}/{@link #clearAlbum}.
     */
    public record Edit(String title, String artist, boolean clearArtist, String album, boolean clearAlbum,
                       Boolean presentAsAudio) {
        public boolean isEmpty() {
            return title == null && artist == null && !clearArtist && album == null && !clearAlbum
                    && presentAsAudio == null;
        }
    }

    /** What a caller is allowed beyond being the adder. */
    public record Rights(boolean editAny, boolean purge) {
    }

    /** The columns {@link #page} and the checks need. */
    record Row(String id, String kind, String title, String artist, String album, Long durationMs, String source,
               String youtubeId, String status, boolean presentAsAudio, long addedAt, String error,
               String addedByUserId) {
    }

    private static final String ROW = "SELECT id, kind, title, artist, album, duration_ms, source, youtube_id, status,"
            + " present_as_audio, added_at, error, added_by_user_id FROM items WHERE id = ?";

    static Row row(Connection c, String itemId, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ROW + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long duration = rs.getLong(6);
                Long durationMs = rs.wasNull() ? null : duration;
                return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        durationMs, rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10) != 0,
                        rs.getLong(11), rs.getString(12), rs.getString(13));
            }
        }
    }

    private Row lockedRow(Connection c, String itemId) throws SQLException {
        // SQLite has no FOR UPDATE; its writes are serialised on one connection anyway.
        return row(c, itemId, db.dialect() instanceof PostgresDialect);
    }

    private static HttpError notFound() {
        return new HttpError(404, "item_not_found", "No such item");
    }

    private static boolean inLibrary(Connection c, Actor actor, String itemId) throws SQLException {
        if (!(actor instanceof Actor.User user)) {
            return false;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM library_entries WHERE user_id = ? AND item_id = ?")) {
            ps.setString(1, user.userId());
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static You you(Connection c, Actor actor, Row row, Rights rights) throws SQLException {
        boolean inLibrary = inLibrary(c, actor, row.id());
        boolean adder = actor instanceof Actor.User user && user.userId().equals(row.addedByUserId());
        boolean user = actor instanceof Actor.User;
        return new You(inLibrary, user && (rights.editAny() || adder && inLibrary), user && rights.purge());
    }

    private static ItemPage page(Row r, You you) {
        return new ItemPage(r.id(), r.kind(), r.title(), r.artist(), r.album(), r.durationMs(), r.source(),
                r.youtubeId(), r.status(), r.presentAsAudio(), r.addedAt(),
                "failed".equals(r.status()) ? r.error() : null, you);
    }

    /** The item page for {@code actor} (any actor: items are visible by direct link). {@code 404 item_not_found}. */
    public ItemPage get(Actor actor, String itemId, Rights rights) throws SQLException {
        return db.read(c -> {
            Row row = row(c, itemId, false);
            if (row == null) {
                throw notFound();
            }
            return page(row, you(c, actor, row, rights));
        });
    }

    /**
     * The item's most recent ingest job; the log only if {@code actor} may edit the item.
     * {@code 404 item_not_found}, {@code 404 job_not_found} (the item never had one).
     */
    public JobView job(Actor actor, String itemId, Rights rights) throws SQLException {
        return db.read(c -> {
            Row row = row(c, itemId, false);
            if (row == null) {
                throw notFound();
            }
            boolean showLog = you(c, actor, row, rights).canEdit();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, state, progress, attempts, created_at,"
                    + " started_at, finished_at, log FROM ingest_jobs WHERE item_id = ?"
                    + " ORDER BY created_at DESC, id DESC LIMIT 1")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new HttpError(404, "job_not_found", "This item has no ingest job");
                    }
                    long started = rs.getLong(7);
                    Long startedAt = rs.wasNull() ? null : started;
                    long finished = rs.getLong(8);
                    Long finishedAt = rs.wasNull() ? null : finished;
                    return new JobView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getInt(5),
                            rs.getLong(6), startedAt, finishedAt, showLog ? rs.getString(9) : null);
                }
            }
        });
    }

    /**
     * Edits the item's metadata. Guests and anonymous callers are refused by the caller (they can
     * never edit); a user who isn't allowed gets {@code 403 forbidden}.
     *
     * @throws HttpError {@code 404 item_not_found}, {@code 403 forbidden}, {@code 400 not_a_video}
     *                   ({@code presentAsAudio: true} on an audio item)
     */
    public ItemPage update(Actor.User actor, String itemId, Edit edit, Rights rights, String clientIp)
            throws SQLException {
        Objects.requireNonNull(edit);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            Row row = lockedRow(c, itemId);
            if (row == null) {
                throw notFound();
            }
            You you = you(c, actor, row, rights);
            if (!you.canEdit()) {
                throw HttpError.forbidden("Only the user who added this item (while it is in their library) or"
                        + " someone with library.editAny can edit it");
            }
            if (Boolean.TRUE.equals(edit.presentAsAudio()) && !"video".equals(row.kind())) {
                throw new HttpError(400, "not_a_video", "Only video items can be presented as audio");
            }
            String title = edit.title() != null ? edit.title() : row.title();
            String artist = edit.clearArtist() ? null : edit.artist() != null ? edit.artist() : row.artist();
            String album = edit.clearAlbum() ? null : edit.album() != null ? edit.album() : row.album();
            boolean presentAsAudio = edit.presentAsAudio() != null ? edit.presentAsAudio() : row.presentAsAudio();
            Map<String, Object> changed = new LinkedHashMap<>();
            if (!title.equals(row.title())) {
                changed.put("title", title);
            }
            if (!Objects.equals(artist, row.artist())) {
                changed.put("artist", artist);
            }
            if (!Objects.equals(album, row.album())) {
                changed.put("album", album);
            }
            if (presentAsAudio != row.presentAsAudio()) {
                changed.put("presentAsAudio", presentAsAudio);
            }
            if (changed.isEmpty()) {
                return page(row, you);
            }
            // TODO(M4): the item's card.mp4 still shows the old title/artist/album; re-render it
            // (and its index for audio items) before the packager shows cards.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET title = ?, artist = ?, album = ?, present_as_audio = ? WHERE id = ?")) {
                ps.setString(1, title);
                ps.setString(2, artist);
                ps.setString(3, album);
                ps.setInt(4, presentAsAudio ? 1 : 0);
                ps.setString(5, itemId);
                ps.executeUpdate();
            }
            AuditLog.write(c, now, actor.userId(), null, clientIp, AUDIT_UPDATED, itemId, changed);
            Row after = new Row(row.id(), row.kind(), title, artist, album, row.durationMs(), row.source(),
                    row.youtubeId(), row.status(), presentAsAudio, row.addedAt(), row.error(), row.addedByUserId());
            return page(after, you);
        });
    }

    /** Sets who is told (after the commit) when {@link #purge} marks an item {@code purged}: the rooms. */
    public void statusListener(ItemStatusListener listener) {
        this.statusListener = listener == null ? ItemStatusListener.NONE : listener;
    }

    /** Runs inside the purge transaction, after the item row was read; throws to refuse the purge. */
    @FunctionalInterface
    public interface PurgeCheck {
        void check(Connection c, String itemId, String status) throws SQLException;
    }

    /** What {@link #purge} did. */
    public record Purged(String previousStatus, int cancelledJobs) {
    }

    /**
     * Purges the item (see the class comment).
     *
     * @param check runs in the transaction before anything changes (the sweeper re-checks that the
     *              item is still unreferenced there; the API writes its audit row); throw to refuse
     * @throws HttpError {@code 404 item_not_found}, {@code 409 item_busy} (an ingest job is running)
     */
    public Purged purge(String itemId, PurgeCheck check) throws SQLException, IOException {
        long now = EpochMs.now(clock);
        List<Path[]> moved = new ArrayList<>(); // {aside, original}
        Purged purged;
        try {
            purged = db.write(c -> {
                Row row = lockedRow(c, itemId);
                if (row == null) {
                    throw notFound();
                }
                // Only now: an id from the URL could be anything, one from the table is a UUID.
                Path itemDir = IngestPipeline.itemDir(dataDir, row.id());
                Path staging = IngestPipeline.stagingDir(dataDir, row.id());
                check.check(c, itemId, row.status());
                int cancelled;
                try (PreparedStatement ps = c.prepareStatement("UPDATE ingest_jobs SET state = ?, finished_at = ?"
                        + " WHERE item_id = ? AND state = ?")) {
                    ps.setString(1, IngestJob.State.FAILED.wire());
                    ps.setLong(2, now);
                    ps.setString(3, itemId);
                    ps.setString(4, IngestJob.State.QUEUED.wire());
                    cancelled = ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM ingest_jobs WHERE item_id = ? AND state = ?")) {
                    ps.setString(1, itemId);
                    ps.setString(2, IngestJob.State.RUNNING.wire());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new HttpError(409, "item_busy",
                                    "This item is being processed right now; try again when its ingest job has ended");
                        }
                    }
                }
                // An item that already was purged keeps its purge time.
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET purged_at = CASE WHEN status = 'purged'"
                        + " THEN COALESCE(purged_at, ?) ELSE ? END, status = 'purged' WHERE id = ?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, itemId);
                    ps.executeUpdate();
                }
                db.afterCommit(() -> statusListener.itemStatusChanged(itemId));
                // Renamed inside the transaction: nothing can publish new files for the item until
                // the commit, and nothing can see it purged before they are gone from their place.
                try {
                    for (Path dir : List.of(itemDir, staging)) {
                        Path aside = IngestPipeline.moveAside(dir);
                        if (aside != null) {
                            moved.add(new Path[] {aside, dir});
                        }
                    }
                } catch (IOException e) {
                    throw new SQLException("Could not move the item's files aside: " + e.getMessage(), e);
                }
                return new Purged(row.status(), cancelled);
            });
        } catch (SQLException | RuntimeException e) {
            for (Path[] m : moved) {
                try {
                    IngestPipeline.moveBack(m[0], m[1]);
                } catch (IOException restore) {
                    e.addSuppressed(restore);
                }
            }
            throw e;
        }
        // TODO(M5): also delete the item's overlay caches.
        for (Path[] m : moved) {
            try {
                IngestPipeline.deleteTree(m[0]);
            } catch (IOException e) {
                // The row is purged; recoverDirectories deletes what is left at the next start.
                LOG.log(System.Logger.Level.WARNING, "Could not delete " + m[0] + " of purged item " + itemId
                        + ": " + e.getMessage());
            }
        }
        return purged;
    }

    /** The purge check of {@code DELETE /api/items/{id}}: an audit row by the acting user. */
    public PurgeCheck auditedBy(String userId, String clientIp) {
        long now = EpochMs.now(clock);
        return (c, itemId, status) -> AuditLog.write(c, now, userId, null, clientIp, AUDIT_PURGED, itemId,
                Map.of("previousStatus", status));
    }
}
