package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.EditSettings;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.IngestQueue;
import io.siggi.togethertunes.ingest.JobList;
import io.siggi.togethertunes.ingest.MediaVersions;
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
    /** {@code audit_log} action of an undo of a media change ({@code docs/library-and-ingest.md#undo}). */
    public static final String AUDIT_MEDIA_UNDONE = "item.media_undone";
    /** {@code audit_log} action of a video trim ({@code docs/library-and-ingest.md#trim}). */
    public static final String AUDIT_TRIMMED = "item.trimmed";
    /** {@code audit_log} action of a purge by a person ({@code library.purge}). */
    public static final String AUDIT_PURGED = "item.purged";

    private static final System.Logger LOG = System.getLogger(Items.class.getName());

    private final Database db;
    private final DataDir dataDir;
    private final Clock clock;
    private volatile JobList.Steps steps = JobList.Steps.NONE;
    private volatile ItemStatusListener statusListener = ItemStatusListener.NONE;
    private volatile MediaVersions versions;
    private volatile IngestJobs trimJobs;

    public Items(Database db, DataDir dataDir, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.dataDir = Objects.requireNonNull(dataDir);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The database (for {@link ItemSearch}). */
    Database database() {
        return db;
    }

    /**
     * An item page: the item, and what the caller can do with it.
     *
     * @param addedAt when the item was first added (epoch ms)
     * @param error   why the last ingest failed ({@code status = failed}), else null
     * @param mediaVersion the current version of its files ({@code media_version}): the page plays
     *                     {@code /media/{id}/v/{mediaVersion}/…}
     * @param you     the caller's relation to the item
     * @param undo    the change the caller can undo now ({@code POST /api/items/{id}/undo}), else null
     * @param trim    the video trim, for callers who may edit a ready video item with its original
     *                ({@code POST /api/items/{id}/trim}), else null
     */
    public record ItemPage(String id, String kind, String title, String artist, String album, Long durationMs,
                           String source, String youtubeId, String status, boolean presentAsAudio, long addedAt,
                           String error, int mediaVersion, You you, UndoView undo, TrimView trim) {
        public ItemPage(String id, String kind, String title, String artist, String album, Long durationMs,
                        String source, String youtubeId, String status, boolean presentAsAudio, long addedAt,
                        String error, int mediaVersion, You you) {
            this(id, kind, title, artist, album, durationMs, source, youtubeId, status, presentAsAudio, addedAt, error,
                    mediaVersion, you, null, null);
        }
    }

    /**
     * The current trims ({@code docs/library-and-ingest.md#trim}, {@code #audio-trim}), on the
     * original's timeline.
     *
     * @param videoStartMs where the current encode starts (a video item; 0 for an audio item)
     * @param videoEndMs   where it ends, or null for the end of the original
     * @param originalMs   how long the original is (the timeline's length)
     * @param audioStartMs the audio trim's start, or null for none (an audio item plays the whole
     *                     original; a video's audio-only file is its {@code audio.m4a})
     * @param audioEndMs   the audio trim's end, or null for the end of the original
     * @param audioOnlyMs  a video's audio-only file's length, or null when it has none (it is then
     *                     {@code audio.m4a}, as long as the item)
     * @param firstGopFrames a video's current encode's first GOP in frames (1..60; less than 60
     *                     after a smart trim), which the page's trim plan needs; null for an audio item
     */
    public record TrimView(long videoStartMs, Long videoEndMs, long originalMs, Long audioStartMs, Long audioEndMs,
                           Long audioOnlyMs, Integer firstGopFrames) {
        public TrimView(long videoStartMs, Long videoEndMs, long originalMs) {
            this(videoStartMs, videoEndMs, originalMs, null, null, null, 60);
        }
    }

    /**
     * A media change that can be undone ({@code docs/library-and-ingest.md#undo}).
     *
     * @param changedAt when the change was made (epoch ms): "Undo the change from 14:32"
     */
    public record UndoView(long changedAt) {
    }

    /**
     * The caller's relation to an item. Nothing about other people (not even who added it).
     *
     * @param inLibrary whether the item is in the caller's library (always false for guests)
     * @param canEdit   whether the caller may edit its metadata
     * @param canPurge  whether the caller holds {@code library.purge}
     * @param canRetry  whether the caller may retry the item's failed ingest now
     *                  ({@code POST /api/items/{id}/retry}): a failed YouTube item, YouTube imports
     *                  on, and the caller may edit the item or holds {@code admin.jobs}
     */
    public record You(boolean inLibrary, boolean canEdit, boolean canPurge, boolean canRetry) {
        public You(boolean inLibrary, boolean canEdit, boolean canPurge) {
            this(inLibrary, canEdit, canPurge, false);
        }
    }

    /**
     * An item's latest ingest job, for its status display.
     *
     * @param progress 0..1
     * @param log      the job log's tail; only for callers who may edit the item, else null
     * @param step     a running job's step now ({@code download}, {@code encode}, {@code finish}),
     *                 live from the ingest queue; else null
     * @param stepProgress that step's own progress, 0..1, with {@code step}
     */
    public record JobView(String id, String kind, String state, double progress, int attempts, long createdAt,
                          Long startedAt, Long finishedAt, String log, String step, Double stepProgress) {
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

    /**
     * What a caller is allowed beyond being the adder.
     *
     * @param retryAny holds {@code admin.jobs}: may retry any failed YouTube item
     * @param youtube  YouTube imports are on ({@code ytdlp.enabled}); retries need it
     */
    public record Rights(boolean editAny, boolean purge, boolean retryAny, boolean youtube) {
        public Rights(boolean editAny, boolean purge) {
            this(editAny, purge, false, false);
        }
    }

    /** The columns {@link #page} and the checks need. */
    record Row(String id, String kind, String title, String artist, String album, Long durationMs, String source,
               String youtubeId, String status, boolean presentAsAudio, long addedAt, String error,
               String addedByUserId, int mediaVersion) {
    }

    private static final String ROW = "SELECT id, kind, title, artist, album, duration_ms, source, youtube_id, status,"
            + " present_as_audio, added_at, error, added_by_user_id, media_version FROM items WHERE id = ?";

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
                        rs.getLong(11), rs.getString(12), rs.getString(13), rs.getInt(14));
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
        boolean canEdit = user && (rights.editAny() || adder && inLibrary);
        boolean retryable = "youtube".equals(row.source()) && "failed".equals(row.status());
        return new You(inLibrary, canEdit, user && rights.purge(),
                retryable && rights.youtube() && (canEdit || user && rights.retryAny()));
    }

    private ItemPage page(Connection c, Row r, You you) throws SQLException {
        UndoView undo = null;
        MediaVersions v = versions;
        if (you.canEdit() && v != null) {
            java.util.OptionalLong since = v.undoableSince(c, r.id());
            if (since.isPresent()) {
                undo = new UndoView(since.getAsLong());
            }
        }
        TrimView trim = null;
        if (you.canEdit() && trimmable(c, r) == null) {
            String editJson = MediaVersions.editJson(c, r.id(), r.mediaVersion());
            EditSettings edit = EditSettings.parse(editJson);
            Long audioOnlyMs = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT audio_only_ms FROM items WHERE id = ?")) {
                ps.setString(1, r.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long ms = rs.getLong(1);
                        audioOnlyMs = rs.wasNull() ? null : ms;
                    }
                }
            }
            boolean video = "video".equals(r.kind());
            trim = new TrimView(video ? edit.videoStartMs() : 0, video ? edit.videoEndMs() : null, originalMs(edit, r),
                    edit.audioStartMs(), edit.audioEndMs(), video ? audioOnlyMs : null,
                    video ? EditSettings.firstGopFrames(editJson) : null);
        }
        return new ItemPage(r.id(), r.kind(), r.title(), r.artist(), r.album(), r.durationMs(), r.source(),
                r.youtubeId(), r.status(), r.presentAsAudio(), r.addedAt(),
                "failed".equals(r.status()) ? r.error() : null, r.mediaVersion(), you, undo, trim);
    }

    /**
     * The original's length: as recorded by the encode that applied the trim, else (never trimmed)
     * the item's duration, which is the original's.
     */
    private static long originalMs(EditSettings edit, Row r) {
        if (edit.originalMs() != null) {
            return edit.originalMs();
        }
        long duration = r.durationMs() == null ? 0 : r.durationMs();
        if ("video".equals(r.kind())) {
            return edit.videoTrimmed() ? edit.videoStartMs() + duration : duration;
        }
        return edit.audioTrimmed() ? edit.audioStartMs() + duration : duration;
    }

    /** Why the item can't be trimmed now ({@code 409} code), or null if it can. */
    private String trimmable(Connection c, Row r) throws SQLException {
        if (!"video".equals(r.kind()) && !"audio".equals(r.kind())) {
            return "item_not_ready";
        }
        if (!"ready".equals(r.status())) {
            return "item_not_ready";
        }
        String original;
        try (PreparedStatement ps = c.prepareStatement("SELECT original_file FROM items WHERE id = ?")) {
            ps.setString(1, r.id());
            try (ResultSet rs = ps.executeQuery()) {
                original = rs.next() ? rs.getString(1) : null;
            }
        }
        if (original == null || original.isEmpty() || original.startsWith(".") || original.contains("/")
                || original.contains("\\")
                || !java.nio.file.Files.isRegularFile(IngestPipeline.itemDir(dataDir, r.id()).resolve(original))) {
            return "no_original";
        }
        return null;
    }

    /**
     * A trim range's end as stored: null for the end of the original (also for an end at or after
     * it).
     *
     * @throws HttpError {@code 400 invalid_trim} if less than {@link IngestPipeline#MIN_TRIM_MS} is
     *                   left or the start isn't before the end
     */
    private static Long checkRange(long start, Long end, long originalMs) {
        if (end != null && originalMs > 0 && end >= originalMs) {
            end = null; // to the end of the original
        }
        long effectiveEnd = end != null ? end : originalMs;
        if (start < 0 || (end != null && end <= start) || effectiveEnd - start < IngestPipeline.MIN_TRIM_MS) {
            throw new HttpError(400, "invalid_trim", "The trim must keep at least "
                    + IngestPipeline.MIN_TRIM_MS / 1000 + " s of the original, with the start before the end");
        }
        return end;
    }

    /** Where trim jobs are queued ({@code POST /api/items/{id}/trim}); no trims until set. */
    public void trimJobs(IngestJobs jobs) {
        this.trimJobs = jobs;
    }

    /**
     * A new trim ({@code docs/library-and-ingest.md#trim}, {@code #audio-trim}) on the original's
     * timeline: the video trim, the audio trim, or both.
     *
     * @param video        whether the video trim is set
     * @param videoStartMs where the encode starts, ≥ 0
     * @param videoEndMs   where it ends, or null for the end of the original
     * @param audio        whether the audio trim is set
     * @param audioStartMs where the audio trim starts, or null to remove it
     * @param audioEndMs   where it ends, or null for the end of the original
     */
    public record Trim(boolean video, long videoStartMs, Long videoEndMs, boolean audio, Long audioStartMs,
                       Long audioEndMs) {
        /** A video trim only. */
        public Trim(long videoStartMs, Long videoEndMs) {
            this(true, videoStartMs, videoEndMs, false, null, null);
        }

        /** An audio trim only ({@code startMs} null: remove it). */
        public static Trim audio(Long startMs, Long endMs) {
            return new Trim(false, 0, null, true, startMs, endMs);
        }
    }

    /**
     * Queues a trim job for the item ({@link IngestJobs#enqueueTrim}) and returns the item page.
     * For users who may edit the item. Audited ({@value #AUDIT_TRIMMED}).
     *
     * @throws HttpError {@code 404 item_not_found}, {@code 403 forbidden}, {@code 400 invalid_trim},
     *                   {@code 409 not_a_video}, {@code item_not_ready}, {@code no_original},
     *                   {@code no_change}, {@code item_busy}, {@code trim_unavailable}
     */
    public ItemPage trim(Actor.User actor, String itemId, Trim trim, Rights rights, String clientIp)
            throws SQLException {
        Objects.requireNonNull(trim);
        IngestJobs jobs = trimJobs;
        long now = EpochMs.now(clock);
        return db.write(c -> {
            Row row = lockedRow(c, itemId);
            if (row == null) {
                throw notFound();
            }
            You you = you(c, actor, row, rights);
            if (!you.canEdit()) {
                throw HttpError.forbidden("Only the user who added this item (while it is in their library) or"
                        + " someone with library.editAny can trim it");
            }
            String why = trim.video() && !"video".equals(row.kind()) ? "not_a_video" : trimmable(c, row);
            if (why != null) {
                throw new HttpError(409, why, switch (why) {
                    case "not_a_video" -> "Only video items have a video trim";
                    case "item_not_ready" -> "The item isn't ready";
                    default -> "The item's original file isn't kept, so it can't be trimmed";
                });
            }
            if (jobs == null) {
                throw new HttpError(409, "trim_unavailable", "Trimming isn't available on this server");
            }
            String currentJson = MediaVersions.editJson(c, itemId, row.mediaVersion());
            EditSettings current = EditSettings.parse(currentJson);
            long originalMs = originalMs(current, row);
            boolean changed = false;
            long start = current.videoStartMs();
            Long end = current.videoEndMs();
            if (trim.video()) {
                start = trim.videoStartMs();
                end = checkRange(start, trim.videoEndMs(), originalMs);
                changed = start != current.videoStartMs() || !Objects.equals(end, current.videoEndMs());
            }
            Long audioStart = current.audioStartMs();
            Long audioEnd = current.audioEndMs();
            if (trim.audio()) {
                audioStart = trim.audioStartMs();
                audioEnd = audioStart == null ? null : checkRange(audioStart, trim.audioEndMs(), originalMs);
                if (audioStart != null && audioStart == 0 && audioEnd == null && !"video".equals(row.kind())) {
                    audioStart = null; // an audio item's whole original is no trim
                }
                changed |= !Objects.equals(audioStart, current.audioStartMs())
                        || !Objects.equals(audioEnd, current.audioEndMs());
            }
            if (!changed) {
                throw new HttpError(409, "no_change", "The item already has this trim");
            }
            IngestJob job;
            try {
                job = jobs.enqueueTrim(itemId, EditSettings.job(trim.video(), start, end, trim.audio(), audioStart, audioEnd));
            } catch (IngestJobs.ItemBusy e) {
                throw new HttpError(409, "item_busy", e.getMessage());
            }
            Map<String, Object> details = new LinkedHashMap<>();
            if (trim.video()) {
                details.put("videoStartMs", start);
                details.put("videoEndMs", end);
            }
            if (trim.audio()) {
                details.put("audioStartMs", audioStart);
                details.put("audioEndMs", audioEnd);
            }
            details.put("jobId", job.id());
            AuditLog.write(c, now, actor.userId(), null, clientIp, AUDIT_TRIMMED, itemId, details);
            return page(c, row, you);
        });
    }

    /** The media versions, for undo ({@code docs/library-and-ingest.md#undo}); no undo until set. */
    public void mediaVersions(MediaVersions versions) {
        this.versions = versions;
    }

    /**
     * Goes back to the item's previous media version ({@link MediaVersions#undo}), for a user who
     * may edit the item, and returns the item page. Audited ({@value #AUDIT_MEDIA_UNDONE}).
     *
     * @throws HttpError {@code 404 item_not_found}, {@code 403 forbidden}, {@code 409 nothing_to_undo},
     *                   {@code 409 item_busy}
     */
    public ItemPage undo(Actor.User actor, String itemId, Rights rights, String clientIp)
            throws SQLException, IOException {
        MediaVersions v = versions;
        db.read(c -> {
            Row row = row(c, itemId, false);
            if (row == null) {
                throw notFound();
            }
            if (!you(c, actor, row, rights).canEdit()) {
                throw HttpError.forbidden("Only the user who added this item (while it is in their library) or"
                        + " someone with library.editAny can undo its changes");
            }
            return null;
        });
        if (v == null) {
            throw new HttpError(409, "nothing_to_undo", "Undo isn't available on this server");
        }
        long now = EpochMs.now(clock);
        try {
            v.undo(itemId, (c, undone) -> {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("undoneVersion", undone.undoneVersion());
                details.put("restoredVersion", undone.restoredVersion());
                details.put("newVersion", undone.newVersion());
                AuditLog.write(c, now, actor.userId(), null, clientIp, AUDIT_MEDIA_UNDONE, itemId, details);
            });
        } catch (MediaVersions.UndoRefused e) {
            throw new HttpError(409, e.code(), e.getMessage());
        }
        return get(actor, itemId, rights);
    }

    /**
     * Throws unless {@code actor} may retry the item's failed ingest from its page: a user who may
     * edit it or holds {@code admin.jobs} ({@code 403 forbidden}); {@code 404 item_not_found}.
     * Whether the item can be retried at all is {@code YoutubeImports.retry}'s check.
     */
    public void requireRetry(Actor.User actor, String itemId, Rights rights) throws SQLException {
        db.read(c -> {
            Row row = row(c, itemId, false);
            if (row == null) {
                throw notFound();
            }
            You you = you(c, actor, row, rights);
            if (!you.canEdit() && !rights.retryAny()) {
                throw HttpError.forbidden("Only someone who may edit this item, or an administrator with"
                        + " admin.jobs, can retry it");
            }
            return null;
        });
    }

    /** The item page for {@code actor} (any actor: items are visible by direct link). {@code 404 item_not_found}. */
    public ItemPage get(Actor actor, String itemId, Rights rights) throws SQLException {
        return db.read(c -> {
            Row row = row(c, itemId, false);
            if (row == null) {
                throw notFound();
            }
            return page(c, row, you(c, actor, row, rights));
        });
    }

    /**
     * The item's most recent ingest job; the log only if {@code actor} may edit the item.
     * {@code 404 item_not_found}, {@code 404 job_not_found} (the item never had one).
     */
    /** Where running jobs' live steps come from (the ingest queue); none until set. */
    public void jobSteps(JobList.Steps steps) {
        this.steps = Objects.requireNonNull(steps);
    }

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
                    IngestQueue.LiveStep step = "running".equals(rs.getString(3))
                            ? steps.of(rs.getString(1)).orElse(null) : null;
                    return new JobView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getInt(5),
                            rs.getLong(6), startedAt, finishedAt, showLog ? rs.getString(9) : null,
                            step == null ? null : step.step(), step == null ? null : step.progress());
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
                return page(c, row, you);
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
                    row.youtubeId(), row.status(), presentAsAudio, row.addedAt(), row.error(), row.addedByUserId(),
                    row.mediaVersion());
            return page(c, after, you);
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
                // Nothing is kept for undo once the files are gone.
                MediaVersions.forgetAll(c, itemId);
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
