package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.AuditLog;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.retention.References;
import io.siggi.togethertunes.storage.DiskSpace;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * YouTube imports with the video ID as the cache key
 * ({@code docs/library-and-ingest.md#youtube-caching}).
 *
 * <ol>
 *   <li>The link is normalised to the 11-character ID ({@link YoutubeIds}); anything else is
 *       {@code 400 invalid_youtube_url}. Nothing below ever sees the pasted URL: yt-dlp only gets
 *       the canonical {@code https://www.youtube.com/watch?v=<id>}.</li>
 *   <li>{@code items.youtube_id} is looked up:
 *     <ul>
 *       <li>{@code ready} → that item, {@code cached: true}; no network access at all;</li>
 *       <li>{@code pending} / {@code processing} → that item; the request attaches to the job
 *           that is already queued or running;</li>
 *       <li>{@code purged} → a {@code refetch} job on the same row (title, artist and history
 *           stay);</li>
 *       <li>{@code failed} → a new {@code youtube} job on the same row if the last attempt ended
 *           more than {@link #RETRY_FAILED_AFTER} ago, else {@code 422 youtube_failed} with
 *           {@code Retry-After};</li>
 *       <li>not found → {@code yt-dlp -J} ({@link #fetchMetadata}): live streams are refused
 *           ({@code 422 youtube_live}), so is anything longer than {@code ingest.maxDurationSeconds}
 *           ({@code 422 too_long}) and anything yt-dlp can't read ({@code 422
 *           youtube_unavailable}); otherwise a new {@code pending} video item with title, artist
 *           and album from the metadata ({@link YoutubeMetadata}) and a {@code youtube} job.</li>
 *     </ul></li>
 * </ol>
 * Every successful import makes the item referenced now ({@code last_referenced_at}) and, for a
 * user, adds it to their library ({@code via='youtube'}) unless {@code addToLibrary} is false.
 * {@code presentAsAudio} is applied when the import creates the item or brings a purged or failed
 * one back; for an item that already exists it is ignored (changing it is a metadata edit, with
 * its own permission rules). Imports of the same ID are serialised, so concurrent requests for a
 * new video run yt-dlp once and make one item. The item row, its job and the library entry are
 * written in one transaction. The download itself (and the thumbnail that becomes the cover) is
 * the ingest job's work ({@link IngestPipeline}).
 */
public final class YoutubeImports {
    /** A failed item is fetched again only once its last attempt is at least this old. */
    public static final Duration RETRY_FAILED_AFTER = Duration.ofMinutes(10);
    /** After a {@linkplain #retry retry}, the next retry of the same item waits this long. */
    public static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);
    /** {@code audit_log} action of a {@linkplain #retry retry}. */
    public static final String AUDIT_RETRIED = "item.ingest_retried";
    /** Recent retries remembered before old ones are dropped. */
    private static final int RETRY_MEMORY = 1024;
    static final Duration METADATA_TIMEOUT = Duration.ofMinutes(2);
    static final Duration METADATA_IDLE_TIMEOUT = Duration.ofSeconds(90);
    /** {@code -J} output with every format listed is a few hundred KiB; this is plenty. */
    static final int METADATA_MAX_BYTES = 32 * 1024 * 1024;
    private static final int LOCK_STRIPES = 64;
    /** {@code library_entries.via} of an entry made by an import. */
    static final String VIA_IMPORT = "youtube";

    private static final System.Logger LOG = System.getLogger(YoutubeImports.class.getName());

    private final Database db;
    private final Tools tools;
    private final IngestJobs jobs;
    private final Runnable wake;
    private volatile Supplier<OutputEncoding> profile;
    private volatile IntSupplier maxDurationSec;
    private final Supplier<String> cookiesFile;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private volatile LibraryAddListener libraryAddListener = LibraryAddListener.NONE;
    private volatile DiskSpace diskSpace = DiskSpace.unlimited();
    /** When each item was last {@linkplain #retry retried} (epoch ms); in memory only. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastRetry =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What an import produced.
     *
     * @param cached whether the item was ready (no download needed)
     * @param status the item's status after the import: {@code ready}, {@code pending} or
     *               {@code processing}
     */
    public record Imported(String itemId, boolean cached, String status) {
    }

    /** The item that holds a video ID. */
    private record Existing(String id, String status) {
    }

    /**
     * @param wake           called after a job was queued (the ingest queue's {@code wake})
     * @param profile        the output encoding new items are recorded with
     * @param maxDurationSec {@code ingest.maxDurationSeconds}
     * @param cookiesFile    the {@code ytdlp.cookiesFile} setting, read on every metadata request
     */
    public YoutubeImports(Database db, Tools tools, IngestJobs jobs, Runnable wake, OutputEncoding profile,
                          int maxDurationSec, Supplier<String> cookiesFile, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.tools = Objects.requireNonNull(tools);
        this.jobs = Objects.requireNonNull(jobs);
        this.wake = Objects.requireNonNull(wake);
        Objects.requireNonNull(profile);
        this.profile = () -> profile;
        this.maxDurationSec = () -> maxDurationSec;
        this.cookiesFile = Objects.requireNonNull(cookiesFile);
        this.clock = Objects.requireNonNull(clock);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Where the output encoding new items are recorded with comes from (the current one,
     * {@code ingest.OutputAssets}); replaces the fixed one given to the constructor.
     */
    public void outputEncoding(Supplier<OutputEncoding> encoding) {
        this.profile = Objects.requireNonNull(encoding);
    }

    /** Where {@code ingest.maxDurationSeconds} comes from (the admin setting), read for every import. */
    public void maxDurationSeconds(IntSupplier maxDurationSec) {
        this.maxDurationSec = Objects.requireNonNull(maxDurationSec);
    }

    /**
     * The free-space check ({@code storage.minFreeBytes}): every new fetch (a new video, a purged
     * item's refetch, a failed item's retry) is refused with {@code 507 insufficient_storage} while
     * the data volume is below the minimum; items that are ready or on their way are still handed
     * out. Unlimited until set.
     */
    public void diskSpace(DiskSpace diskSpace) {
        this.diskSpace = Objects.requireNonNull(diskSpace);
    }

    /**
     * Imports {@code url} for {@code actor} (a user or a guest); see the class comment.
     *
     * @param presentAsAudio the item's {@code presentAsAudio} if this import creates it (null = false)
     * @param addToLibrary   whether a user's library gets the item ({@code null} = yes)
     * @throws HttpError {@code 400 invalid_youtube_url}, {@code 422 youtube_live},
     *                   {@code 422 too_long}, {@code 422 youtube_unavailable},
     *                   {@code 422 youtube_failed}, {@code 503 ytdlp_unavailable},
     *                   {@code 504 youtube_timeout}, {@code 502 youtube_bad_metadata},
     *                   {@code 507 insufficient_storage}
     */
    public Imported importUrl(Actor actor, String url, Boolean presentAsAudio, Boolean addToLibrary)
            throws SQLException, InterruptedException {
        return importUrl(actor, url, presentAsAudio, addToLibrary, YoutubeLookupStage.Listener.NONE);
    }

    /**
     * Like {@link #importUrl(Actor, String, Boolean, Boolean)}, telling {@code progress} the
     * stages of the {@code yt-dlp -J} lookup while it runs ({@link YoutubeLookupStage}). Nothing is
     * reported when there is no lookup (an item that is already known answers at once).
     */
    public Imported importUrl(Actor actor, String url, Boolean presentAsAudio, Boolean addToLibrary,
                              YoutubeLookupStage.Listener progress) throws SQLException, InterruptedException {
        Objects.requireNonNull(progress);
        String userId = actor instanceof Actor.User u ? u.userId() : null;
        String guestId = actor instanceof Actor.Guest g ? g.guestId() : null;
        if (userId == null && guestId == null) {
            throw new IllegalArgumentException("YouTube imports are made by a user or a guest");
        }
        String videoId = YoutubeIds.parse(url).orElseThrow(() -> new HttpError(400, "invalid_youtube_url",
                "That isn't a link to a YouTube video"));
        boolean library = userId != null && !Boolean.FALSE.equals(addToLibrary);
        ReentrantLock lock = locks[Math.floorMod(videoId.hashCode(), locks.length)];
        lock.lockInterruptibly();
        try {
            Existing existing = db.read(c -> find(c, videoId));
            if (existing != null) {
                return existing(existing, userId, presentAsAudio, library, VIA_IMPORT);
            }
            diskSpace.requireRoomFor(0);
            YoutubeMetadata meta = fetchMetadata(videoId, progress);
            String itemId = UuidV7.newId();
            long now = EpochMs.now(clock);
            db.write(c -> {
                insertItem(c, itemId, videoId, meta, userId, guestId, Boolean.TRUE.equals(presentAsAudio), now);
                jobs.enqueue(itemId, IngestJob.Kind.YOUTUBE, library ? IngestJob.Tier.QUALITY : IngestJob.Tier.QUICK);
                if (library) {
                    addToLibrary(c, userId, itemId, now, VIA_IMPORT);
                }
                return null;
            });
            wake.run();
            return new Imported(itemId, false, "pending");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds the existing item that holds {@code videoId} to {@code user}'s library with {@code via},
     * through the same cache rules as an import of its link: a ready, pending or processing item is
     * just added; a purged one gets a {@code refetch} job, a failed one a new {@code youtube} job
     * (subject to {@link #RETRY_FAILED_AFTER}). Never runs yt-dlp here. Used by
     * {@code PUT /api/library/{itemId}} ({@link Library}).
     *
     * @throws HttpError {@code 404 item_not_found} if no item holds the ID (any more),
     *                   {@code 422 youtube_failed}
     */
    public Imported addExisting(Actor.User user, String videoId, String via)
            throws SQLException, InterruptedException {
        ReentrantLock lock = locks[Math.floorMod(videoId.hashCode(), locks.length)];
        lock.lockInterruptibly();
        try {
            Existing existing = db.read(c -> find(c, videoId));
            if (existing == null) {
                throw new HttpError(404, "item_not_found", "No such item");
            }
            return existing(existing, user.userId(), null, true, via);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Like {@link #addExisting}, but without a library entry: makes sure the item that holds
     * {@code videoId} is ready or on its way (a purged one gets a {@code refetch} job, a failed one
     * a new {@code youtube} job, subject to {@link #RETRY_FAILED_AFTER}) and counts it as
     * referenced now. Used when a playlist gets the item ({@link Playlists}).
     *
     * @throws HttpError {@code 404 item_not_found}, {@code 422 youtube_failed}
     */
    public Imported useExisting(String videoId) throws SQLException, InterruptedException {
        ReentrantLock lock = locks[Math.floorMod(videoId.hashCode(), locks.length)];
        lock.lockInterruptibly();
        try {
            Existing existing = db.read(c -> find(c, videoId));
            if (existing == null) {
                throw new HttpError(404, "item_not_found", "No such item");
            }
            return existing(existing, null, null, false, null);
        } finally {
            lock.unlock();
        }
    }

    private Imported existing(Existing existing, String userId, Boolean presentAsAudio, boolean library,
                              String via) throws SQLException {
        String itemId = existing.id();
        long now = EpochMs.now(clock);
        switch (existing.status()) {
            case "ready", "pending", "processing" -> {
                db.write(c -> {
                    References.touch(c, itemId, now);
                    if (library) {
                        addToLibrary(c, userId, itemId, now, via);
                    }
                    return null;
                });
                return new Imported(itemId, existing.status().equals("ready"), existing.status());
            }
            case "purged", "failed" -> {
                IngestJob.Kind kind;
                if (existing.status().equals("purged")) {
                    kind = IngestJob.Kind.REFETCH;
                } else {
                    kind = IngestJob.Kind.YOUTUBE;
                    long last = lastAttemptAt(itemId);
                    long retryAt = last + RETRY_FAILED_AFTER.toMillis();
                    if (now < retryAt) {
                        long waitSec = Math.max(1, (retryAt - now + 999) / 1000);
                        throw new HttpError(422, "youtube_failed", "Fetching this video failed "
                                + Math.max(0, (now - last) / 60_000) + " min ago; it can be tried again in "
                                + (waitSec + 59) / 60 + " min", waitSec);
                    }
                }
                diskSpace.requireRoomFor(0);
                db.write(c -> {
                    if (presentAsAudio != null) {
                        setPresentAsAudio(c, itemId, presentAsAudio);
                    }
                    References.touch(c, itemId, now);
                    jobs.enqueue(itemId, kind, library ? IngestJob.Tier.QUALITY : IngestJob.Tier.QUICK);
                    if (library) {
                        addToLibrary(c, userId, itemId, now, via);
                    }
                    return null;
                });
                wake.run();
                return new Imported(itemId, false, "pending");
            }
            default -> throw new IllegalStateException("Unknown item status " + existing.status());
        }
    }

    /** A retried item and the job that now fetches it ({@link #retry}). */
    public record Retried(String itemId, String jobId, String status) {
    }

    /**
     * Retries the failed ingest of a YouTube item now ({@code POST /api/items/{id}/retry}, the room's
     * {@code queue.retry}; {@code docs/library-and-ingest.md#retrying-a-failed-youtube-item}): a new
     * {@code youtube} job (a {@code refetch} if the failed job was one) on the same row, which turns
     * the item {@code pending} again. Unlike an import of the link, it doesn't wait
     * {@link #RETRY_FAILED_AFTER}; instead a successful retry blocks the next one of the same item for
     * {@link #RETRY_INTERVAL}, so a button can't hammer YouTube. The preset tier is decided when the
     * job starts (quick while a room waits for the item). Who may retry is the caller's check
     * ({@code ytdlp.enabled} included); this checks the item, the free space and the rate limit.
     * Audited ({@value #AUDIT_RETRIED}, target the item, details {@code jobId}, {@code kind} and
     * {@code roomId} for a retry from a room).
     *
     * @param actor    who retries: a user or a guest
     * @param clientIp for the audit row
     * @param roomId   the room it was retried from, or null (the item page)
     * @throws HttpError {@code 404 item_not_found}, {@code 409 not_youtube} (only YouTube items: a
     *                   failed upload's file isn't kept), {@code 409 item_purged},
     *                   {@code 409 ingest_in_progress} (a job is queued or running),
     *                   {@code 409 item_not_failed}, {@code 429 retry_too_soon} (with
     *                   {@code Retry-After}), {@code 507 insufficient_storage}
     */
    public Retried retry(String itemId, Actor actor, String clientIp, String roomId)
            throws SQLException, InterruptedException {
        Objects.requireNonNull(itemId);
        String userId = actor instanceof Actor.User u ? u.userId() : null;
        String guestId = actor instanceof Actor.Guest g ? g.guestId() : null;
        if (userId == null && guestId == null) {
            throw new IllegalArgumentException("Retries are made by a user or a guest");
        }
        String[] row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT source, youtube_id FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null;
                }
            }
        });
        if (row == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        if (!"youtube".equals(row[0]) || row[1] == null) {
            throw new HttpError(409, "not_youtube", "Only YouTube items can be retried; a failed upload's file"
                    + " isn't kept, so upload it again");
        }
        ReentrantLock lock = locks[Math.floorMod(row[1].hashCode(), locks.length)];
        lock.lockInterruptibly();
        try {
            // Status checks first: a second click while the retry runs is a 409, not a 429.
            String status = db.read(c -> status(c, itemId));
            if ("purged".equals(status)) {
                throw new HttpError(409, "item_purged", "The item was purged; add it again to fetch it");
            }
            IngestJob last = jobs.latestForItem(itemId).orElse(null);
            if (last != null && (last.state() == IngestJob.State.QUEUED || last.state() == IngestJob.State.RUNNING)) {
                throw new HttpError(409, "ingest_in_progress", "The item is already being fetched");
            }
            if (!"failed".equals(status)) {
                throw new HttpError(409, "item_not_failed", "Only a failed item can be retried");
            }
            long now = EpochMs.now(clock);
            Long previous = lastRetry.get(itemId);
            if (previous != null && now - previous < RETRY_INTERVAL.toMillis()) {
                long waitMs = previous + RETRY_INTERVAL.toMillis() - now;
                throw new HttpError(429, "retry_too_soon", "This item was retried a moment ago; try again in "
                        + Math.max(1, (waitMs + 999) / 1000) + " s", Math.max(1, (waitMs + 999) / 1000));
            }
            diskSpace.requireRoomFor(0);
            IngestJob.Kind kind = last != null && last.kind() == IngestJob.Kind.REFETCH
                    ? IngestJob.Kind.REFETCH : IngestJob.Kind.YOUTUBE;
            IngestJob queued = db.write(c -> {
                References.touch(c, itemId, now);
                IngestJob job = jobs.enqueue(itemId, kind, null);
                java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("jobId", job.id());
                details.put("kind", kind.wire());
                if (roomId != null) {
                    details.put("roomId", roomId);
                }
                AuditLog.write(c, now, userId, guestId, clientIp, AUDIT_RETRIED, itemId, details);
                return job;
            });
            lastRetry.put(itemId, now);
            if (lastRetry.size() > RETRY_MEMORY) {
                lastRetry.entrySet().removeIf(e -> now - e.getValue() >= RETRY_INTERVAL.toMillis());
            }
            wake.run();
            return new Retried(itemId, queued.id(), "pending");
        } finally {
            lock.unlock();
        }
    }

    private static String status(Connection c, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM items WHERE id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new HttpError(404, "item_not_found", "No such item");
                }
                return rs.getString(1);
            }
        }
    }

    /** When the item's last job ended (or was created, if it never finished); 0 if it has none. */
    private long lastAttemptAt(String itemId) throws SQLException {
        Optional<IngestJob> job = jobs.latestForItem(itemId);
        if (job.isEmpty()) {
            return 0;
        }
        return job.get().finishedAt() != null ? job.get().finishedAt() : job.get().createdAt();
    }

    /**
     * {@code yt-dlp -J --no-playlist} for {@code videoId}, checked: not live, not longer than
     * {@code ingest.maxDurationSeconds}.
     */
    YoutubeMetadata fetchMetadata(String videoId) throws InterruptedException {
        return fetchMetadata(videoId, YoutubeLookupStage.Listener.NONE);
    }

    /**
     * Like {@link #fetchMetadata(String)}, reporting the lookup's stages from yt-dlp's stderr
     * ({@code --verbose} makes {@code -J} print its progress lines there; {@link YoutubeLookupStage}).
     */
    YoutubeMetadata fetchMetadata(String videoId, YoutubeLookupStage.Listener progress) throws InterruptedException {
        if (!tools.hasYtdlp()) {
            throw unavailable();
        }
        JobLog log = new JobLog();
        Path cookies = IngestPipeline.cookiesFile(cookiesFile.get(), w -> LOG.log(System.Logger.Level.WARNING, w));
        YoutubeLookupStage.Tracker stages = new YoutubeLookupStage.Tracker(progress);
        // --verbose prints a traceback after an ERROR line, which can push it out of the stderr tail
        java.util.concurrent.atomic.AtomicReference<String> lastError = new java.util.concurrent.atomic.AtomicReference<>();
        ToolResult result;
        try {
            result = tools.ytdlp(IngestPipeline.metadataArgs(videoId, cookies)).timeout(METADATA_TIMEOUT)
                    .idleTimeout(METADATA_IDLE_TIMEOUT).log(log).stdoutToString(METADATA_MAX_BYTES)
                    .stderrLines(line -> {
                        if (line.strip().startsWith("ERROR:")) {
                            lastError.set(line);
                        }
                        stages.line(line);
                    }).run();
        } catch (ToolException e) {
            switch (e.kind()) {
                case NOT_AVAILABLE, START_FAILED -> throw unavailable();
                case TIMEOUT, IDLE_TIMEOUT -> throw new HttpError(504, "youtube_timeout",
                        "YouTube didn't answer in time; try again later");
                default -> {
                    LOG.log(System.Logger.Level.INFO, "yt-dlp -J failed for " + videoId + ":\n" + log.tail());
                    throw new HttpError(422, "youtube_unavailable",
                            errorMessage(lastError.get() != null ? lastError.get() : e.stderrTail()));
                }
            }
        }
        YoutubeMetadata meta;
        try {
            meta = YoutubeMetadata.parse(result.stdoutString(), videoId);
        } catch (IllegalArgumentException e) {
            throw new HttpError(502, "youtube_bad_metadata", "yt-dlp returned no usable metadata for this video");
        }
        if (meta.live()) {
            throw new HttpError(422, "youtube_live", "Live streams can't be imported"
                    + ("is_upcoming".equals(meta.liveStatus()) ? " (this one hasn't started yet)"
                    : "post_live".equals(meta.liveStatus()) ? " (this one has just ended and is still being processed)"
                    : ""));
        }
        int maxDurationSec = this.maxDurationSec.getAsInt();
        if (meta.durationSec() > maxDurationSec) {
            throw new HttpError(422, "too_long", String.format(Locale.ROOT,
                    "The video is too long (%d s; the limit is %d s)", Math.round(meta.durationSec()), maxDurationSec));
        }
        return meta;
    }

    /**
     * The message for a failed {@code -J}: yt-dlp's last {@code ERROR:} line without its prefix
     * and extractor tag, e.g. "Video unavailable"; a generic message if there is none.
     */
    static String errorMessage(String stderrTail) {
        String found = null;
        if (stderrTail != null) {
            for (String line : stderrTail.split("\n")) {
                String l = line.strip();
                if (l.startsWith("ERROR:")) {
                    found = l.substring("ERROR:".length()).strip();
                }
            }
        }
        if (found != null) {
            // "[youtube] dQw4w9WgXcQ: Video unavailable" → "Video unavailable"
            found = found.replaceFirst("^\\[[^\\]]*\\]\\s*[A-Za-z0-9_-]{11}:\\s*", "");
            found = UploadMetadata.clean(found);
        }
        if (found == null) {
            return "YouTube didn't return this video";
        }
        return "YouTube didn't return this video: " + (found.length() > 300 ? found.substring(0, 300) + "…" : found);
    }

    private static HttpError unavailable() {
        return new HttpError(503, "ytdlp_unavailable", "YouTube imports can't run: yt-dlp isn't available on this server");
    }

    // ------------------------------------------------------------------ SQL

    private static Existing find(Connection c, String videoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, status FROM items WHERE youtube_id = ?")) {
            ps.setString(1, videoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Existing(rs.getString(1), rs.getString(2)) : null;
            }
        }
    }

    private void insertItem(Connection c, String itemId, String videoId, YoutubeMetadata meta, String userId,
                            String guestId, boolean presentAsAudio, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, album, duration_ms,"
                + " source, youtube_id, added_by_user_id, added_by_guest_id, added_at, status, output_profile,"
                + " metadata_json, media_type, last_referenced_at)"
                + " VALUES (?, 'video', ?, ?, ?, ?, 'youtube', ?, ?, ?, ?, 'pending', ?, ?, ?, ?)")) {
            ps.setString(1, itemId);
            ps.setString(2, meta.title());
            ps.setString(3, meta.artist());
            ps.setString(4, meta.album());
            if (meta.durationMs() == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, meta.durationMs());
            }
            ps.setString(6, videoId);
            ps.setString(7, userId);
            ps.setString(8, guestId);
            ps.setLong(9, now);
            ps.setString(10, profile.get().id());
            ps.setString(11, meta.toItemMetadata().toString());
            ps.setString(12, MediaType.initial("video", presentAsAudio).wire());
            ps.setLong(13, now);
            ps.executeUpdate();
        }
    }

    /**
     * {@code presentAsAudio} at import: true makes the item's media type {@code audio} (show
     * details cleared), false turns an {@code audio} type back into {@code uncategorized}; other
     * types stay.
     */
    private static void setPresentAsAudio(Connection c, String itemId, boolean value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(value
                ? "UPDATE items SET media_type = 'audio', show_name = NULL, season_number = NULL,"
                        + " episode_number = NULL WHERE id = ?"
                : "UPDATE items SET media_type = 'uncategorized' WHERE id = ? AND media_type = 'audio'"
                        + " AND kind = 'video'")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
    }


    private void addToLibrary(Connection c, String userId, String itemId, long now, String via)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
            ps.setString(1, userId);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.setString(4, via);
            ps.executeUpdate();
        }
        libraryAddListener.addedToLibrary(itemId);
    }

    /** Sets who is told when this class adds an item to a library ({@link LibraryAddListener}). */
    public void libraryAddListener(LibraryAddListener listener) {
        this.libraryAddListener = listener == null ? LibraryAddListener.NONE : listener;
    }
}
