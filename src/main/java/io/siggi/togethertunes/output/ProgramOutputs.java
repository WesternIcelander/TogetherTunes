package io.siggi.togethertunes.output;

import io.siggi.togethertunes.rooms.PackagerHook;
import io.siggi.togethertunes.rooms.Playback;
import io.siggi.togethertunes.rooms.PlaybackState;
import io.siggi.togethertunes.rooms.QueueEntry;
import io.siggi.togethertunes.rooms.RoomChange;
import io.siggi.togethertunes.rooms.RoomEvent;
import io.siggi.togethertunes.rooms.RoomRegistry;
import io.siggi.togethertunes.rooms.RoomState;
import io.siggi.togethertunes.rooms.Timeline;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The program outputs of every room: when each room's {@link Packager} runs, and what it plays
 * ({@code docs/program-output.md#lifecycle}).
 *
 * <ul>
 *   <li><b>Start</b>: when the owner enables an output ({@link #touch}, {@link #keepRunning}) or
 *       the first {@code .ts} / HLS request arrives ({@link #acquire}, {@link #touch}). Checking
 *       that the output is enabled (server switch, room switch, token) is the caller's job.</li>
 *   <li><b>Stop</b>: {@link #IDLE_STOP_MS} (60 s) after the last consumer left, checked every
 *       {@link #SWEEP_MS}, unless an RTMP push keeps it running ({@link #keepRunning}). A consumer
 *       is a {@link Lease} ({@code .ts} connection, RTMP pusher); an HLS fetch is a {@link #touch},
 *       which counts as activity at that moment. {@link #stop} stops at once (every output disabled).</li>
 *   <li>A new start resets the output clock (new {@link PacingClock}, unit builder and muxer); that
 *       only matters to new connections.</li>
 *   <li>A packager that stopped by itself (build or mux failure) is restarted by the next
 *       {@link #acquire} / {@link #touch}, or by the sweep while an RTMP push keeps it running.</li>
 * </ul>
 *
 * <p>As the {@link PackagerHook}, it follows the rooms' timelines: each committed change is turned
 * into a {@link Snapshot} on the room's executor thread (nothing blocks there) and, for a room
 * whose output exists, resolved into a {@link ProgramCue} (item kinds come from the database) on a
 * virtual thread and handed to the running output. Only the newest snapshot is applied. A room
 * output started without having seen a change takes a snapshot through the room's executor
 * ({@link #snapshotsFrom}), a read that changes nothing. The program output never feeds back into
 * the timeline (invariant 4).
 */
public final class ProgramOutputs implements PackagerHook, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProgramOutputs.class.getName());

    /** How long an output keeps running after its last consumer left. */
    public static final long IDLE_STOP_MS = 60_000;
    /** How often idle outputs are looked for. */
    public static final long SWEEP_MS = 5_000;

    /** One running program output (built by an {@link OutputFactory}). */
    public interface Output extends AutoCloseable {
        /** The started packager; consumers attach to it. */
        Packager packager();

        /** The room's timeline changed (any thread). */
        void update(ProgramCue cue);

        /** Stops the packager (closing its consumers) and frees what the output holds. */
        @Override
        void close();
    }

    /** Builds and starts a room's output ({@link ProgramPipeline} in production). */
    @FunctionalInterface
    public interface OutputFactory {
        Output start(String roomId, ProgramCue initial) throws IOException;
    }

    /** What the packager needs to know about an item. */
    public record ItemKind(boolean videoItem, boolean presentAsAudio) {
    }

    /** Looks up an item's kind; null if there is no such item. */
    @FunctionalInterface
    public interface ItemKinds {
        ItemKind lookup(String itemId) throws SQLException;
    }

    /** Takes a room's {@link Snapshot} on its executor (loading the room if needed). */
    @FunctionalInterface
    public interface RoomSnapshots {
        Snapshot snapshot(String roomId) throws Exception;
    }

    /** Told when a room's packager (re)started, e.g. to attach the HLS ring or the RTMP pusher. Must not block. */
    @FunctionalInterface
    public interface StartListener {
        void started(String roomId, Packager packager);
    }

    /**
     * What the program output needs from a room after a change, copied on the room's executor.
     *
     * @param version   the room version it was taken at (newer wins)
     * @param current   the current entry, or null
     * @param next      the next eligible entry ({@link Playback#nextEligible}), or null
     * @param playedOut see {@link ProgramCue#playedOut()}
     */
    public record Snapshot(long version, Timeline timeline, QueueEntry current, QueueEntry next, String playedOut) {
        public Snapshot {
            Objects.requireNonNull(timeline, "timeline");
        }
    }

    /** A consumer holding a room's output running; close it when the consumer leaves. */
    public final class Lease implements AutoCloseable {
        private final RoomOutput room;
        private final Packager packager;
        private boolean closed;

        private Lease(RoomOutput room, Packager packager) {
            this.room = room;
            this.packager = packager;
        }

        /** The packager running when the lease was taken. */
        public Packager packager() {
            return packager;
        }

        @Override
        public void close() {
            room.lock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                room.leases--;
                room.lastActiveMs = nowMs.getAsLong();
            } finally {
                room.lock.unlock();
            }
        }
    }

    /** One room's output and what keeps it running. */
    private static final class RoomOutput {
        final String roomId;
        /** Guards the fields below except the cue feed; held while starting (may block). */
        final ReentrantLock lock = new ReentrantLock();
        Output output;
        int leases;
        long lastActiveMs;
        boolean pinned;

        /** Cue feed, guarded by {@code feed}: the newest snapshot, the version applied, a drain running. */
        final Object feed = new Object();
        Snapshot latest;
        long appliedVersion = Long.MIN_VALUE;
        Output feeding;
        boolean draining;

        RoomOutput(String roomId) {
            this.roomId = roomId;
        }
    }

    private final OutputFactory factory;
    private final ItemKinds kinds;
    private final LongSupplier nowMs;
    private final ConcurrentHashMap<String, RoomOutput> rooms = new ConcurrentHashMap<>();
    /** room id → the entry the timeline last advanced away from as played ({@link ProgramCue#playedOut()}). */
    private final ConcurrentHashMap<String, String> playedOut = new ConcurrentHashMap<>();
    private final List<StartListener> startListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sweeper;
    private volatile RoomSnapshots snapshots;
    private volatile boolean closed;

    /** Sweeps every {@link #SWEEP_MS} on a virtual thread. */
    public ProgramOutputs(OutputFactory factory, ItemKinds kinds, LongSupplier nowMs) {
        this(factory, kinds, nowMs, SWEEP_MS);
    }

    /** {@code sweepEveryMs} ≤ 0: no automatic sweeps (tests call {@link #sweep}). */
    ProgramOutputs(OutputFactory factory, ItemKinds kinds, LongSupplier nowMs, long sweepEveryMs) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        if (sweepEveryMs > 0) {
            sweeper = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("program-outputs").factory());
            sweeper.scheduleWithFixedDelay(this::sweepQuietly, sweepEveryMs, sweepEveryMs, TimeUnit.MILLISECONDS);
        } else {
            sweeper = null;
        }
    }

    /** Item kinds from {@code items.kind} and {@code items.present_as_audio}. */
    public static ItemKinds itemKindsFrom(io.siggi.togethertunes.db.Database db) {
        return itemId -> db.read(c -> {
            try (var ps = c.prepareStatement("SELECT kind, present_as_audio FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new ItemKind("video".equals(rs.getString(1)), rs.getInt(2) != 0);
                }
            }
        });
    }

    /** Snapshots taken through {@code registry} (set once the registry, which needs this hook, exists). */
    public void snapshotsFrom(RoomRegistry registry) {
        this.snapshots = roomId -> registry.call(roomId, tx -> snapshot(tx.state()));
    }

    /** Sets where snapshots come from (tests). */
    void snapshots(RoomSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    public void addStartListener(StartListener listener) {
        startListeners.add(Objects.requireNonNull(listener));
    }

    // ------------------------------------------------------------------ PackagerHook (room executor threads)

    @Override
    public void roomChanged(RoomChange change) {
        // the executor calls the state-aware variant
    }

    @Override
    public void roomChanged(RoomState state, RoomChange change) {
        String roomId = change.roomId();
        if (change.timelineChanged()) {
            String played = null;
            for (RoomEvent e : change.events()) {
                if (e instanceof RoomEvent.EntryStateChanged sc && sc.state() == QueueEntry.State.PLAYED) {
                    played = sc.entryId();
                }
            }
            if (played == null) {
                playedOut.remove(roomId);
            } else {
                playedOut.put(roomId, played);
            }
        }
        RoomOutput ro = rooms.get(roomId);
        if (ro != null) {
            offer(ro, snapshot(state));
        }
    }

    @Override
    public void roomClosed(String roomId, boolean deleted) {
        if (deleted) {
            playedOut.remove(roomId);
            RoomOutput ro = rooms.remove(roomId);
            if (ro != null) {
                // not on the executor thread: a start may hold the room's lock while it waits for this executor
                Thread.ofVirtual().name("program-stop-" + roomId).start(() -> stopNow(ro, "stopped: room deleted"));
            }
        }
    }

    /** The snapshot of {@code state} (on its executor thread). */
    Snapshot snapshot(RoomState state) {
        Timeline t = state.timeline();
        QueueEntry current = t.state() == PlaybackState.IDLE ? null : state.current();
        return new Snapshot(state.version(), t, current, Playback.nextEligible(state), playedOut.get(state.id()));
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * A consumer ({@code .ts} connection, RTMP pusher) arrives: starts the room's output if it isn't
     * running and holds it running until the lease is closed.
     *
     * @throws Exception if the room can't be read (e.g. {@code 404}) or the output can't start
     */
    public Lease acquire(String roomId) throws Exception {
        RoomOutput ro = room(roomId);
        ro.lock.lock();
        try {
            Output out = ensureRunning(ro);
            ro.leases++;
            ro.lastActiveMs = nowMs.getAsLong();
            return new Lease(ro, out.packager());
        } finally {
            ro.lock.unlock();
        }
    }

    /**
     * Activity without a lasting consumer (an output enabled, an HLS playlist or segment fetch):
     * starts the output if needed; it then runs at least {@link #IDLE_STOP_MS} from now.
     */
    public Packager touch(String roomId) throws Exception {
        RoomOutput ro = room(roomId);
        ro.lock.lock();
        try {
            Output out = ensureRunning(ro);
            ro.lastActiveMs = nowMs.getAsLong();
            return out.packager();
        } finally {
            ro.lock.unlock();
        }
    }

    /**
     * An RTMP push was enabled ({@code true}: start and keep running regardless of consumers) or
     * disabled ({@code false}: the usual idle stop applies again, counted from now).
     */
    public void keepRunning(String roomId, boolean rtmpEnabled) throws Exception {
        if (!rtmpEnabled) {
            RoomOutput ro = rooms.get(roomId);
            if (ro == null) {
                return;
            }
            ro.lock.lock();
            try {
                if (ro.pinned) {
                    ro.pinned = false;
                    ro.lastActiveMs = nowMs.getAsLong();
                }
            } finally {
                ro.lock.unlock();
            }
            return;
        }
        RoomOutput ro = room(roomId);
        ro.lock.lock();
        try {
            ro.pinned = true;
            ensureRunning(ro);
            ro.lastActiveMs = nowMs.getAsLong();
        } finally {
            ro.lock.unlock();
        }
    }

    /** Stops the room's output now (every output disabled); consumers are disconnected. */
    public void stop(String roomId) {
        RoomOutput ro = rooms.get(roomId);
        if (ro != null) {
            ro.lock.lock();
            try {
                ro.pinned = false;
            } finally {
                ro.lock.unlock();
            }
            stopNow(ro, "stopped");
        }
    }

    /** The room's running packager, or null; never starts one. */
    public Packager running(String roomId) {
        RoomOutput ro = rooms.get(roomId);
        if (ro == null) {
            return null;
        }
        ro.lock.lock();
        try {
            Output out = ro.output;
            return out != null && out.packager().isRunning() ? out.packager() : null;
        } finally {
            ro.lock.unlock();
        }
    }

    /** Leases held on the room's output (for status and tests). */
    public int leases(String roomId) {
        RoomOutput ro = rooms.get(roomId);
        if (ro == null) {
            return 0;
        }
        ro.lock.lock();
        try {
            return ro.leases;
        } finally {
            ro.lock.unlock();
        }
    }

    /**
     * Stops outputs idle for {@link #IDLE_STOP_MS}, cleans up packagers that stopped by themselves
     * and restarts those an RTMP push keeps running. Runs every {@link #SWEEP_MS}.
     */
    public void sweep() {
        long now = nowMs.getAsLong();
        for (RoomOutput ro : new ArrayList<>(rooms.values())) {
            ro.lock.lock();
            try {
                Output out = ro.output;
                if (out != null && !out.packager().isRunning()) {
                    LOG.log(System.Logger.Level.INFO, "Program output of room {0} stopped by itself", ro.roomId);
                    detach(ro);
                    out.close();
                    out = null;
                }
                if (out == null) {
                    if (ro.pinned && !closed) {
                        try {
                            ensureRunning(ro);
                            ro.lastActiveMs = now;
                        } catch (Exception e) {
                            LOG.log(System.Logger.Level.WARNING, "Could not restart the program output of room "
                                    + ro.roomId, e);
                        }
                    }
                    continue;
                }
                if (ro.leases > 0) {
                    ro.lastActiveMs = now;
                } else if (!ro.pinned && now - ro.lastActiveMs >= IDLE_STOP_MS) {
                    LOG.log(System.Logger.Level.INFO, "Program output of room {0} stopped: no consumers for {1} s",
                            ro.roomId, IDLE_STOP_MS / 1000);
                    detach(ro);
                    out.close();
                }
            } finally {
                ro.lock.unlock();
            }
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Program output sweep failed", t);
        }
    }

    /** Stops every output. */
    @Override
    public void close() {
        closed = true;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        for (RoomOutput ro : new ArrayList<>(rooms.values())) {
            stopNow(ro, null);
        }
    }

    private RoomOutput room(String roomId) {
        Objects.requireNonNull(roomId, "roomId");
        if (closed) {
            throw new IllegalStateException("program outputs closed");
        }
        return rooms.computeIfAbsent(roomId, RoomOutput::new);
    }

    /** With {@code ro.lock} held: the running output, started if needed. */
    private Output ensureRunning(RoomOutput ro) throws Exception {
        Output out = ro.output;
        if (out != null) {
            if (out.packager().isRunning()) {
                return out;
            }
            detach(ro);
            out.close();
        }
        Snapshot snap;
        synchronized (ro.feed) {
            snap = ro.latest;
        }
        if (snap == null) {
            RoomSnapshots s = snapshots;
            if (s == null) {
                throw new IllegalStateException("no room snapshots");
            }
            offer(ro, s.snapshot(ro.roomId));
            synchronized (ro.feed) {
                snap = ro.latest;
            }
        }
        out = factory.start(ro.roomId, resolve(snap));
        ro.output = out;
        synchronized (ro.feed) {
            ro.feeding = out;
            ro.appliedVersion = snap.version();
        }
        LOG.log(System.Logger.Level.INFO, "Program output of room {0} started", ro.roomId);
        kick(ro); // a change that arrived while starting
        for (StartListener l : startListeners) {
            try {
                l.started(ro.roomId, out.packager());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A program output start listener failed", e);
            }
        }
        return out;
    }

    private void stopNow(RoomOutput ro, String why) {
        Output out;
        ro.lock.lock();
        try {
            out = ro.output;
            detach(ro);
        } finally {
            ro.lock.unlock();
        }
        if (out != null) {
            if (why != null) {
                LOG.log(System.Logger.Level.INFO, "Program output of room {0} {1}", ro.roomId, why);
            }
            out.close();
        }
    }

    /** With {@code ro.lock} held: forgets the output (the caller closes it). */
    private static void detach(RoomOutput ro) {
        ro.output = null;
        synchronized (ro.feed) {
            ro.feeding = null;
        }
    }

    // ------------------------------------------------------------------ cue feed

    private void offer(RoomOutput ro, Snapshot snap) {
        synchronized (ro.feed) {
            if (ro.latest != null && snap.version() <= ro.latest.version()) {
                return;
            }
            ro.latest = snap;
        }
        kick(ro);
    }

    /** Starts the room's drain thread if a running output hasn't seen the newest snapshot. */
    private void kick(RoomOutput ro) {
        synchronized (ro.feed) {
            if (ro.draining || ro.feeding == null || ro.latest == null
                    || ro.latest.version() == ro.appliedVersion) {
                return;
            }
            ro.draining = true;
        }
        Thread.ofVirtual().name("program-cue-" + ro.roomId).start(() -> drain(ro));
    }

    private void drain(RoomOutput ro) {
        while (true) {
            Snapshot snap;
            Output out;
            synchronized (ro.feed) {
                if (ro.feeding == null || ro.latest == null || ro.latest.version() == ro.appliedVersion) {
                    ro.draining = false;
                    return;
                }
                snap = ro.latest;
                out = ro.feeding;
            }
            ProgramCue cue = resolve(snap);
            // outside the monitor: the room executor offers under it and must never wait
            out.update(cue);
            synchronized (ro.feed) {
                if (ro.feeding == out && snap.version() > ro.appliedVersion) {
                    ro.appliedVersion = snap.version();
                }
            }
        }
    }

    /**
     * The cue for a snapshot. An entry whose item can't be looked up makes the cue unknown (Paused
     * filler) if it is the current one, and drops it if it is the next one.
     */
    ProgramCue resolve(Snapshot s) {
        Timeline t = s.timeline();
        ProgramCue.Entry next = null;
        if (s.next() != null) {
            try {
                next = entry(s.next());
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Program output: can't look up item " + s.next().itemId(), e);
            }
        }
        if (t.state() == PlaybackState.IDLE) {
            return ProgramCue.of(t, null, next, s.playedOut());
        }
        QueueEntry cur = s.current();
        if (cur == null || !cur.id().equals(t.entryId())) {
            return new ProgramCue(null, null, 0, 0, null, null);
        }
        try {
            return ProgramCue.of(t, entry(cur), next, s.playedOut());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Program output: can't look up item " + cur.itemId(), e);
            return new ProgramCue(null, null, 0, 0, null, null);
        }
    }

    private ProgramCue.Entry entry(QueueEntry q) throws SQLException {
        ItemKind kind = kinds.lookup(q.itemId());
        if (kind == null) {
            throw new SQLException("no item " + q.itemId());
        }
        return new ProgramCue.Entry(q.id(), q.itemId(), kind.videoItem(), kind.presentAsAudio(),
                q.durationMs() == null ? 0 : q.durationMs());
    }
}
