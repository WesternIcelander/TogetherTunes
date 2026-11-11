package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Track;
import io.siggi.togethertunes.output.ProgramCue;
import io.siggi.togethertunes.output.VideoOverlay;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Schedules the music-video overlay re-encodes ({@code docs/overlay.md#scheduling-and-caching}) on
 * the {@link OverlayPool}, for the program outputs that play entries as video.
 *
 * <ul>
 *   <li>The <b>next</b> entry gets a job from GOP 0 at {@link OverlayPool.Priority#NORMAL} as soon
 *       as the output has its files loaded; the entry <b>playing</b> (current) gets one at
 *       {@link OverlayPool.Priority#HIGH} when it starts, from GOP 0 if it starts at the beginning
 *       and from the GOP after the one it starts in otherwise (that one is already on its way out).
 *       A job that already covers what is wanted (it started at or before that GOP, and if it is
 *       still running, it is no more than {@link #FRONTIER_SLACK_GOPS} GOPs behind it) is shared,
 *       and a queued one is raised to the higher priority.</li>
 *   <li>Only entries whose recorded decision is {@code video} ({@link OverlayDecisions}) get one.</li>
 *   <li>An entry that is no longer wanted (neither current, next nor playing in any output) gives up
 *       its job, which is <b>cancelled unless it is more than half done</b>
 *       ({@link #KEEP_PROGRESS}); a job some other entry or output still uses keeps running.</li>
 *   <li><b>Busy pool:</b> if {@link OverlayPool#busy()}, nothing is queued and the item plays
 *       without the overlay; it is tried again when a job ends, and for the entry playing at every
 *       GOP (at most every {@link #RETRY_MS}), so the overlay appears later if a worker frees up.</li>
 *   <li>The packager never waits: the {@link VideoOverlay} handed to a play gives a GOP from any
 *       encode of the same item, media version, overlay and encoding in which that GOP is complete,
 *       else null (the original GOP plays).</li>
 *   <li>A job whose output doesn't match the reference or the mezzanine
 *       ({@link OverlayVideoEncoder.MismatchException}) isn't tried again for that item, version,
 *       overlay and encoding while the server runs; any other failure is retried after
 *       {@link #RETRY_MS}.</li>
 * </ul>
 *
 * <p>Each job writes to its own directory,
 * {@code rooms/<roomId>/ov/<itemId>/<version>-<overlayHash>-<encoding>-g<g0>/} ({@value OverlayGops#H264_FILE},
 * {@value OverlayGops#INDEX_FILE} and the {@code overlay.png} it was made with), plus the
 * {@value OverlayCache#DONE_FILE} marker once it finished. Finished encodes are kept in memory (the
 * last {@link #KEEP_DONE}) and on disk ({@link OverlayCache}: least recently used evicted), so a
 * song that repeats reuses them, also after a restart (read back when the entry is planned). A
 * cancelled or failed encode's directory is deleted when the job ends.
 *
 * <p>Thread-safe. Nothing here blocks the caller: overlay lookups (decision, room, image) run on
 * the executor, encodes on the pool.
 */
public final class OverlayVideoJobs {
    private static final System.Logger LOG = System.getLogger(OverlayVideoJobs.class.getName());

    /** A failed lookup or job is retried no sooner than this; also the busy-pool retry interval of a play. */
    public static final long RETRY_MS = 5000;
    /** A job more than this far done is kept when its entry is no longer wanted. */
    public static final double KEEP_PROGRESS = 0.5;
    /** A running job this many GOPs behind the GOP wanted is still good enough (it outruns playback). */
    static final int FRONTIER_SLACK_GOPS = 2;
    /** Finished encodes kept in memory for reuse. */
    static final int KEEP_DONE = 64;
    static final String OV_DIR = "ov";
    static final String PNG = "overlay.png";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Runs one re-encode ({@link OverlayVideoEncoder#encode}; tests replace it). */
    @FunctionalInterface
    public interface Encoder {
        void encode(OverlayVideoEncoder.Request request, OverlayGops gops, DoubleConsumer progress) throws Exception;
    }

    /** The x264 preset of a job, chosen when it starts with the priority it has then. */
    @FunctionalInterface
    public interface Presets {
        X264Preset preset(OverlayPool.Priority priority);
    }

    /** What an entry's re-encode is made with: the room's overlay panel, its position and hash. */
    public record Plan(String overlayHash, BufferedImage panel, int x, int y) {
        public Plan {
            Objects.requireNonNull(overlayHash);
            Objects.requireNonNull(panel);
        }
    }

    /**
     * Looks up an entry's overlay (may block: database, overlay render); null when the entry gets
     * no video overlay (its decision isn't {@code video}, or the room is gone).
     */
    @FunctionalInterface
    public interface Resolver {
        Plan resolve(ProgramCue.Entry entry) throws Exception;
    }

    /** How long {@link #stopRoom}/{@link #stopItem} wait for cancelled jobs to end. */
    static final long STOP_WAIT_MS = 10_000;

    private final Path roomsDir;
    private final OverlayPool pool;
    private final Encoder encoder;
    private final Presets presets;
    private final Executor executor;
    private final LongSupplier nowMs;

    private final Object lock = new Object();
    // guarded by lock
    private final Map<String, List<Encode>> encodes = new HashMap<>();
    /** Finished encodes, oldest first. */
    private final LinkedHashSet<Encode> done = new LinkedHashSet<>();
    private final Set<String> broken = new HashSet<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    /** The sessions with wants. */
    private final Set<Session> sessions = new LinkedHashSet<>();
    /** Rooms deleted in this run: no new jobs. */
    private final Set<String> deletedRooms = new HashSet<>();
    private int submitted;
    private volatile OverlayCache cache;

    /**
     * @param roomsDir {@code <data>/rooms}
     * @param tools    the ffmpeg to encode with
     * @param presets  the preset per priority ({@code docs/overlay.md}: quick for the entry playing;
     *                 the next one's may use the quality preset if the admin allows it)
     */
    public OverlayVideoJobs(Path roomsDir, OverlayPool pool, Tools tools, Presets presets, Executor executor) {
        this(roomsDir, pool, (request, gops, progress) -> OverlayVideoEncoder.encode(
                Objects.requireNonNull(tools), request, gops, progress, null), presets, executor,
                System::currentTimeMillis);
    }

    OverlayVideoJobs(Path roomsDir, OverlayPool pool, Encoder encoder, Presets presets, Executor executor,
            LongSupplier nowMs) {
        this.roomsDir = Objects.requireNonNull(roomsDir).toAbsolutePath().normalize();
        this.pool = Objects.requireNonNull(pool);
        this.encoder = Objects.requireNonNull(encoder);
        this.presets = Objects.requireNonNull(presets);
        this.executor = Objects.requireNonNull(executor);
        this.nowMs = Objects.requireNonNull(nowMs);
    }

    /**
     * The scheduling of one program output of a room.
     *
     * @param encoding the output encoding
     * @param avcC     its reference avcC (invariant 1: the encoder checks against it)
     * @param resolver the room's overlay for an entry
     */
    public Session session(String roomId, OutputEncoding encoding, byte[] avcC, Resolver resolver) {
        safe(roomId, "room id");
        return new Session(roomId, Objects.requireNonNull(encoding), avcC.clone(), Objects.requireNonNull(resolver));
    }

    /** Counts finished encodes in the overlay cache and marks their use (null: none). */
    public void cache(OverlayCache cache) {
        this.cache = cache;
    }

    /** Jobs submitted to the pool so far (tests). */
    int submitted() {
        synchronized (lock) {
            return submitted;
        }
    }

    // ------------------------------------------------------------------ state

    /** One re-encode: a pool job while it runs, then a finished encode. */
    private static final class Encode {
        final String baseKey;
        final String poolKey;
        final int g0;
        final OverlayGops gops;
        // guarded by the scheduler's lock
        OverlayPool.Job job;
        boolean finished;
        int users;
        // guarded by this (and only set with the scheduler's lock held too)
        boolean removed;

        Encode(String baseKey, String poolKey, int g0, OverlayGops gops) {
            this.baseKey = baseKey;
            this.poolKey = poolKey;
            this.g0 = g0;
            this.gops = gops;
        }

        /** The first GOP from {@link #g0} on that isn't complete yet. */
        int frontier() {
            return gops.completed().nextClearBit(g0);
        }
    }

    /** One entry wanted by one output. Guarded by the scheduler's lock. */
    private final class Want {
        final Session session;
        final ProgramCue.Entry entry;
        final Path itemDir;
        final SampleIndex index;
        final Track video;
        final String version;
        OverlayPool.Priority priority;
        /** The GOP the encode must start at or before. */
        int target;
        /** Whether {@link #target} is known (a play's is, from its first GOP on). */
        boolean ready;
        boolean resolving;
        boolean resolved;
        long resolveFailedAt;
        String loggedFailure;
        Plan plan;
        String baseKey;
        Encode encode;
        boolean released;

        Want(Session session, ProgramCue.Entry entry, Path itemDir, SampleIndex index, OverlayPool.Priority priority,
                int target) {
            this.session = session;
            this.entry = entry;
            this.itemDir = itemDir;
            this.index = index;
            this.video = index.track(SampleIndex.Kind.VIDEO);
            String name = itemDir.getFileName() == null ? "" : itemDir.getFileName().toString();
            this.version = SAFE.matcher(name).matches() && !name.contains("..") ? name : "v";
            this.priority = priority;
            this.target = target;
        }
    }

    /** One program output's view: its wanted entries. */
    public final class Session {
        private final String roomId;
        private final OutputEncoding encoding;
        private final byte[] avcC;
        private final Resolver resolver;
        // guarded by the scheduler's lock
        private final Map<String, Want> wants = new HashMap<>();

        private Session(String roomId, OutputEncoding encoding, byte[] avcC, Resolver resolver) {
            this.roomId = roomId;
            this.encoding = encoding;
            this.avcC = avcC;
            this.resolver = resolver;
        }

        /**
         * The next entry (played as video), with the files the output loaded: re-encode it from
         * the start ahead of time. Doesn't block.
         */
        public void next(ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
            synchronized (lock) {
                ensure(want(entry, itemDir, index, OverlayPool.Priority.NORMAL, 0));
            }
        }

        /**
         * An entry starts playing as video from these files: the overlay its source asks at every
         * GOP start. The re-encode it needs is planned at the first GOP (when the start is known).
         * Doesn't block, and neither does the returned overlay.
         */
        public VideoOverlay play(ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
            synchronized (lock) {
                return new Play(want(entry, itemDir, index, OverlayPool.Priority.HIGH, -1));
            }
        }

        /**
         * The entries this output still wants (the cue's current and next, and the one playing);
         * the others give up their jobs. Doesn't block.
         */
        public void wanted(Set<String> entryIds) {
            boolean released = false;
            synchronized (lock) {
                for (Iterator<Want> it = wants.values().iterator(); it.hasNext(); ) {
                    Want w = it.next();
                    if (!entryIds.contains(w.entry.entryId())) {
                        it.remove();
                        released |= w.encode != null;
                        release(w);
                    }
                }
                if (wants.isEmpty()) {
                    sessions.remove(this);
                }
            }
            OverlayCache c = cache;
            if (released && c != null) {
                c.trimSoon(); // encodes nobody uses any more may now be evicted
            }
        }

        /** The entry's want, made or updated (lock held). */
        private Want want(ProgramCue.Entry entry, Path itemDir, SampleIndex index, OverlayPool.Priority priority,
                int target) {
            sessions.add(this);
            Want w = wants.get(entry.entryId());
            if (w != null && (!w.itemDir.equals(itemDir)
                    || w.video.sampleCount() != index.track(SampleIndex.Kind.VIDEO).sampleCount())) {
                wants.remove(entry.entryId());
                release(w);
                w = null;
            }
            if (w == null) {
                w = new Want(this, entry, itemDir, index, priority, Math.max(0, target));
                w.ready = target >= 0;
                wants.put(entry.entryId(), w);
            } else if (priority.compareTo(w.priority) < 0) {
                w.priority = priority;
            }
            return w;
        }
    }

    /** The overlay a play reads from: any encode of its item with the GOP complete. */
    private final class Play implements VideoOverlay {
        private final Want want;
        private final int frameCount;
        private final int[] gopStarts;
        private boolean planned;
        private long lastTryMs;

        Play(Want want) {
            this.want = want;
            this.frameCount = want.video.sampleCount();
            this.gopStarts = OverlayGops.gopStarts(want.video);
        }

        @Override
        public int frameCount() {
            return frameCount;
        }

        @Override
        public byte[][] gop(int keyframe) throws IOException {
            int g = java.util.Arrays.binarySearch(gopStarts, keyframe);
            if (g < 0) {
                return null;
            }
            Encode use = null;
            synchronized (lock) {
                Want w = want;
                long now = nowMs.getAsLong();
                if (!planned) {
                    planned = true;
                    lastTryMs = now;
                    // the GOP starting now is already playing: an encode from it would be late
                    w.target = g == 0 ? 0 : Math.min(g + 1, gopStarts.length - 1);
                    w.ready = true;
                    ensure(w);
                } else if (!w.released && now - lastTryMs >= RETRY_MS && (!w.resolved && !w.resolving
                        || w.plan != null && (w.encode == null || !usable(w.encode, g)))) {
                    // busy pool, a failure, or an encode that fell behind: try again from the next GOP
                    lastTryMs = now;
                    w.target = Math.min(g + 1, gopStarts.length - 1);
                    ensure(w);
                }
                if (w.baseKey != null) {
                    for (Encode e : encodes.getOrDefault(w.baseKey, List.of())) {
                        if (e.gops.isComplete(g)) {
                            use = e;
                            break;
                        }
                    }
                }
            }
            if (use == null) {
                return null;
            }
            byte[][] frames;
            synchronized (use) {
                frames = use.removed ? null : use.gops.gop(keyframe);
            }
            OverlayCache c = cache;
            if (frames != null && c != null) {
                c.used(use.gops.dir());
            }
            return frames;
        }
    }

    // ------------------------------------------------------------------ scheduling (lock held)

    /** Whether {@code e} serves GOP {@code target} soon enough. */
    private static boolean usable(Encode e, int target) {
        if (e.removed || e.g0 > target) {
            return false;
        }
        return e.finished || e.frontier() <= target + FRONTIER_SLACK_GOPS;
    }

    /** Makes sure the want has a job (or knows why not). Lock held; never blocks. */
    private void ensure(Want w) {
        if (w.released || deletedRooms.contains(w.session.roomId)) {
            return;
        }
        long now = nowMs.getAsLong();
        if (!w.resolved) {
            if (!w.resolving && (w.resolveFailedAt == 0 || now - w.resolveFailedAt >= RETRY_MS)) {
                w.resolving = true;
                try {
                    executor.execute(() -> resolve(w));
                } catch (RuntimeException e) {
                    w.resolving = false;
                    w.resolveFailedAt = now;
                }
            }
            return;
        }
        if (w.plan == null || !w.ready || broken.contains(w.baseKey)) {
            return;
        }
        Encode assigned = w.encode;
        if (assigned != null && usable(assigned, w.target)) {
            raise(assigned, w.priority);
            return;
        }
        for (Encode e : encodes.getOrDefault(w.baseKey, List.of())) {
            if (usable(e, w.target)) {
                assign(w, e);
                raise(e, w.priority);
                return;
            }
        }
        Long failed = failedAt.get(w.baseKey);
        if (failed != null && now - failed < RETRY_MS) {
            return;
        }
        if (pool.busy()) {
            return; // plays without the overlay; tried again when a job ends
        }
        String dirName = w.baseKey.substring(w.baseKey.lastIndexOf('/') + 1) + "-g" + w.target;
        String poolKey = w.session.roomId + "/" + w.entry.itemId() + "/" + dirName;
        if (pool.job(poolKey).isPresent()) {
            return; // a cancelled job of that directory is still stopping
        }
        List<Encode> list = encodes.computeIfAbsent(w.baseKey, k -> new ArrayList<>());
        for (Iterator<Encode> it = list.iterator(); it.hasNext(); ) {
            Encode old = it.next();
            if (old.poolKey.equals(poolKey)) {
                it.remove();
                done.remove(old);
                markRemoved(old);
            }
        }
        Path dir = roomsDir.resolve(w.session.roomId).resolve(OV_DIR).resolve(w.entry.itemId()).resolve(dirName);
        Encode e = new Encode(w.baseKey, poolKey, w.target, new OverlayGops(dir, w.video));
        Plan plan = w.plan;
        Path mezz = w.itemDir.resolve(IngestPipeline.MEZZ);
        Track video = w.video;
        OutputEncoding encoding = w.session.encoding;
        byte[] avcC = w.session.avcC;
        int g0 = w.target;
        OverlayPool.Job job;
        try {
            job = pool.submit(poolKey, w.priority, j -> run(j, e, plan, mezz, video, encoding, avcC, g0));
        } catch (IllegalStateException closed) {
            return;
        }
        e.job = job;
        submitted++;
        list.add(e);
        assign(w, e);
        LOG.log(System.Logger.Level.DEBUG, "Overlay job " + poolKey + " queued (" + w.priority + ")");
        job.end().whenComplete((state, t) -> ended(e, state));
    }

    /** Raises a queued job to {@code priority} if that is higher. */
    private void raise(Encode e, OverlayPool.Priority priority) {
        OverlayPool.Job job = e.job;
        if (job != null) {
            pool.raise(e.poolKey, priority);
        }
    }

    private void assign(Want w, Encode e) {
        if (w.encode == e) {
            return;
        }
        Encode old = w.encode;
        w.encode = e;
        e.users++;
        if (old != null) {
            unuse(old);
        }
    }

    /** The want is gone: its job is cancelled if nobody else uses it and it isn't more than half done. */
    private void release(Want w) {
        w.released = true;
        Encode e = w.encode;
        w.encode = null;
        if (e != null) {
            unuse(e);
        }
    }

    private void unuse(Encode e) {
        e.users--;
        if (e.users <= 0 && e.job != null && e.job.currentProgress() <= KEEP_PROGRESS) {
            LOG.log(System.Logger.Level.DEBUG, "Overlay job " + e.poolKey + " no longer wanted: cancelled");
            pool.cancel(e.poolKey);
        }
    }

    private static void markRemoved(Encode e) {
        synchronized (e) {
            e.removed = true;
        }
    }

    // ------------------------------------------------------------------ background

    /** Runs on the executor: the entry's decision and overlay. */
    private void resolve(Want w) {
        Plan plan;
        String baseKey = null;
        try {
            plan = w.session.resolver.resolve(w.entry);
            if (plan != null) {
                baseKey = w.session.roomId + "/" + safe(w.entry.itemId(), "item id") + "/" + w.version + "-"
                        + safe(plan.overlayHash(), "overlay hash") + "-" + safe(w.session.encoding.id(), "encoding");
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (lock) {
                w.resolving = false;
                w.resolveFailedAt = nowMs.getAsLong();
                String msg = String.valueOf(e.getMessage());
                if (!msg.equals(w.loggedFailure)) {
                    w.loggedFailure = msg;
                    LOG.log(System.Logger.Level.WARNING, "Overlay of entry " + w.entry.entryId() + " (item "
                            + w.entry.itemId() + ") can't be planned, playing it without: " + e);
                }
            }
            return;
        }
        List<Encode> stored = baseKey == null ? List.of() : loadStored(w, baseKey);
        List<Path> reused = new ArrayList<>();
        synchronized (lock) {
            if (!stored.isEmpty() && !w.released && !deletedRooms.contains(w.session.roomId)) {
                List<Encode> list = encodes.computeIfAbsent(baseKey, k -> new ArrayList<>());
                for (Encode e : stored) {
                    if (list.stream().noneMatch(o -> o.poolKey.equals(e.poolKey))) {
                        e.finished = true;
                        list.add(e);
                        done.add(e);
                        reused.add(e.gops.dir());
                    }
                }
                trimDone();
            }
            w.resolving = false;
            w.resolved = true;
            w.plan = plan;
            w.baseKey = baseKey;
            ensure(w);
        }
        OverlayCache c = cache;
        if (c != null) {
            for (Path dir : reused) {
                c.used(dir);
            }
        }
    }

    /**
     * The finished encodes of {@code baseKey} on disk (an earlier run's, kept by the overlay cache)
     * that aren't in memory. One that can't be read back completely is deleted.
     */
    private List<Encode> loadStored(Want w, String baseKey) {
        String dirPrefix = baseKey.substring(baseKey.lastIndexOf('/') + 1) + "-g";
        Path itemOv = roomsDir.resolve(w.session.roomId).resolve(OV_DIR).resolve(w.entry.itemId());
        if (!Files.isDirectory(itemOv)) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        synchronized (lock) {
            for (Encode e : encodes.getOrDefault(baseKey, List.of())) {
                known.add(e.poolKey);
            }
        }
        List<Encode> out = new ArrayList<>();
        int gopCount = OverlayGops.gopStarts(w.video).length;
        try (java.nio.file.DirectoryStream<Path> dirs = Files.newDirectoryStream(itemOv, dirPrefix + "*")) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                String poolKey = w.session.roomId + "/" + w.entry.itemId() + "/" + name;
                int g0;
                try {
                    g0 = Integer.parseInt(name.substring(dirPrefix.length()));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (known.contains(poolKey) || !Files.isRegularFile(dir.resolve(OverlayCache.DONE_FILE))
                        || pool.job(poolKey).isPresent()) {
                    continue;
                }
                try {
                    if (g0 < 0 || g0 >= gopCount) {
                        throw new IOException("starts at GOP " + g0 + " of " + gopCount);
                    }
                    OverlayGops gops = OverlayGops.load(dir.toAbsolutePath().normalize(), w.video);
                    if (!gops.completeFrom(g0)) {
                        throw new IOException("incomplete");
                    }
                    out.add(new Encode(baseKey, poolKey, g0, gops));
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "Overlay re-encode " + dir + " can't be reused, deleting it: "
                            + e.getMessage());
                    try {
                        evict(dir);
                    } catch (IOException ignored) {
                        // the cache deletes it later
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Can't list " + itemOv + ": " + e);
        }
        return out;
    }

    /** Runs on a pool worker. */
    private void run(OverlayPool.Job job, Encode e, Plan plan, Path mezz, Track video, OutputEncoding encoding,
            byte[] avcC, int g0) throws Exception {
        Path dir = e.gops.dir();
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve(OverlayCache.DONE_FILE));
        Path png = dir.resolve(PNG);
        if (!ImageIO.write(plan.panel(), "png", png.toFile())) {
            throw new IOException("no PNG writer");
        }
        X264Preset preset = presets.preset(job.priority());
        OverlayVideoEncoder.Request request = new OverlayVideoEncoder.Request(mezz, video, png, plan.x(), plan.y(),
                encoding, preset, avcC, g0);
        encoder.encode(request, e.gops, job::progress);
        if (!e.gops.completeFrom(g0)) {
            return; // can't happen with the real encoder (it fails instead); kept in memory only
        }
        Files.write(dir.resolve(OverlayCache.DONE_FILE), new byte[0]);
        OverlayCache c = cache;
        if (c != null) {
            c.added(dir);
        }
    }

    /** A job ended (any thread). */
    private void ended(Encode e, OverlayPool.State state) {
        synchronized (lock) {
            OverlayPool.Job job = e.job;
            e.job = null;
            if (e.removed) {
                // stopped for a purge or room delete (whose clean-up deletes the files), or replaced
                retryAll();
                return;
            }
            if (state == OverlayPool.State.DONE) {
                e.finished = true;
                failedAt.remove(e.baseKey);
                done.add(e);
                trimDone();
            } else {
                if (state == OverlayPool.State.FAILED) {
                    Throwable f = job == null ? null : job.failure();
                    if (f instanceof OverlayVideoEncoder.MismatchException) {
                        broken.add(e.baseKey);
                        LOG.log(System.Logger.Level.WARNING, "Overlay re-encode " + e.poolKey + " doesn't match the"
                                + " video, playing it without the overlay: " + f.getMessage());
                    } else {
                        failedAt.put(e.baseKey, nowMs.getAsLong());
                    }
                }
                drop(e);
                deletePartial(e);
            }
            retryAll();
        }
    }

    /**
     * Deletes the directory of an encode that didn't finish (lock held), unless a new job for it
     * was already set up.
     */
    private void deletePartial(Encode e) {
        for (Encode other : encodes.getOrDefault(e.baseKey, List.of())) {
            if (other.poolKey.equals(e.poolKey)) {
                return;
            }
        }
        try {
            OverlayCache.deleteTree(e.gops.dir());
            Files.deleteIfExists(e.gops.dir().getParent()); // the item's ov directory, if now empty
        } catch (IOException ignored) {
            // a non-empty item directory, or deleted at the next start
        }
    }

    // ------------------------------------------------------------------ cache, purge, room delete

    /**
     * Deletes an encode's directory for the overlay cache, unless it is in use (running, or
     * assigned to an entry that is current, next or playing).
     *
     * @return true if it is gone
     */
    public boolean evict(Path dir) throws IOException {
        Path d = dir.toAbsolutePath().normalize();
        synchronized (lock) {
            Encode found = null;
            for (List<Encode> list : encodes.values()) {
                for (Encode e : list) {
                    if (e.gops.dir().toAbsolutePath().normalize().equals(d)) {
                        found = e;
                    }
                }
            }
            if (found != null) {
                if (found.job != null || found.users > 0) {
                    return false;
                }
                drop(found); // waits for a read in progress
            } else if (d.startsWith(roomsDir) && d.getNameCount() == roomsDir.getNameCount() + 4) {
                Path rel = roomsDir.relativize(d);
                if (pool.job(rel.getName(0) + "/" + rel.getName(2) + "/" + rel.getName(3)).isPresent()) {
                    return false;
                }
            }
            OverlayCache.deleteTree(d);
            return true;
        }
    }

    /**
     * The room was deleted: its jobs are cancelled (waiting up to {@link #STOP_WAIT_MS} for them to
     * end), its encodes forgotten, and no new ones start. Blocks.
     */
    public void stopRoom(String roomId) {
        stop(e -> e.poolKey.startsWith(roomId + "/"), w -> w.session.roomId.equals(roomId), roomId);
    }

    /** The item was purged: its jobs are cancelled (waiting for them to end) and encodes forgotten. Blocks. */
    public void stopItem(String itemId) {
        stop(e -> e.poolKey.split("/", 3)[1].equals(itemId), w -> w.entry.itemId().equals(itemId), null);
    }

    private void stop(java.util.function.Predicate<Encode> encodeMatches, java.util.function.Predicate<Want> wantMatches,
            String deletedRoom) {
        List<CompletableFuture<OverlayPool.State>> ends = new ArrayList<>();
        synchronized (lock) {
            if (deletedRoom != null) {
                deletedRooms.add(deletedRoom);
            }
            for (Session s : sessions) {
                for (Want w : s.wants.values()) {
                    if (wantMatches.test(w)) {
                        release(w); // its play reads nothing more, and it plans no new job
                    }
                }
            }
            for (List<Encode> list : new ArrayList<>(encodes.values())) {
                for (Encode e : new ArrayList<>(list)) {
                    if (!encodeMatches.test(e)) {
                        continue;
                    }
                    if (e.job != null) {
                        ends.add(e.job.end());
                        pool.cancel(e.poolKey);
                    }
                    drop(e);
                }
            }
        }
        for (CompletableFuture<OverlayPool.State> end : ends) {
            try {
                end.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
                LOG.log(System.Logger.Level.WARNING, "An overlay re-encode didn't stop in time: " + e);
            }
        }
    }

    /** Whether {@code s} is usable as a file name in the overlay directories. */
    static boolean isSafeName(String s) {
        return s != null && SAFE.matcher(s).matches() && !s.contains("..");
    }

    /** Forgets an encode (lock held); its users plan again. */
    private void drop(Encode e) {
        List<Encode> list = encodes.get(e.baseKey);
        if (list != null) {
            list.remove(e);
            if (list.isEmpty()) {
                encodes.remove(e.baseKey);
            }
        }
        done.remove(e);
        markRemoved(e);
        for (Session s : sessions) {
            for (Want w : s.wants.values()) {
                if (w.encode == e) {
                    w.encode = null;
                }
            }
        }
    }

    private void trimDone() {
        Iterator<Encode> it = done.iterator();
        while (done.size() > KEEP_DONE && it.hasNext()) {
            Encode e = it.next();
            if (e.users > 0) {
                continue;
            }
            it.remove();
            List<Encode> list = encodes.get(e.baseKey);
            if (list != null) {
                list.remove(e);
                if (list.isEmpty()) {
                    encodes.remove(e.baseKey);
                }
            }
            markRemoved(e);
        }
    }

    /** A worker may be free: the wants without a job try again (lock held). */
    private void retryAll() {
        for (Session s : new ArrayList<>(sessions)) {
            for (Want w : new ArrayList<>(s.wants.values())) {
                if (w.resolved && w.plan != null && w.ready && w.encode == null) {
                    ensure(w);
                }
            }
        }
    }

    private static String safe(String s, String what) {
        if (s == null || !SAFE.matcher(s).matches() || s.contains("..")) {
            throw new IllegalArgumentException("bad " + what);
        }
        return s;
    }
}
