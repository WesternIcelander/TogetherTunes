package io.siggi.togethertunes.output;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Makes the running outputs match the {@code stream_outputs} rows and the server switches
 * ({@code outputs.*.enabled}): the effective state of an output is server switch AND row enabled.
 *
 * <ul>
 *   <li><b>RTMP:</b> {@link #reconcile} runs one push per room with an enabled RTMP row (and a
 *       URL) while {@code outputs.rtmp.enabled} is on, restarts it when the URL or key changed, and
 *       stops pushes whose row is gone or off (also after a room was deleted).</li>
 *   <li><b>Auto turn-off:</b> with an {@link OutputAutoOff} ({@link #autoOff}), each reconcile
 *       tells it which outputs are active ({@link OutputAutoOff#sync}), and each timer run first
 *       turns off the outputs whose room was idle, or that nobody listened to, for the limit
 *       ({@link #checkIdle}), then reconciles, which stops those pushes and connections.</li>
 *   <li><b>{@code .ts} and MP3:</b> {@link #reconcile} ends {@code .ts} and MP3 connections whose
 *       URL no longer opens the room (token rotated, slug changed, visibility changed, disabled,
 *       deleted, server switch off). HLS needs nothing: every request checks its URL.</li>
 *   <li><b>Program output:</b> enabling an output starts the room's output at once
 *       ({@link #roomChanged} with {@code started}); a room left with no active output has it
 *       stopped at once.</li>
 * </ul>
 *
 * <p>{@link #reconcile} runs after every change made through the API, after the admin changed
 * settings, at startup and every {@link #PERIOD_MS} ms (to catch anything else, e.g. a deleted
 * room). Thread-safe.
 */
public final class StreamOutputControl implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StreamOutputControl.class.getName());

    public static final long PERIOD_MS = 10_000;

    /** The RTMP pushes ({@link RtmpPushers}). */
    public interface Pushers {
        void start(String roomId, String rtmpUrl, String key);

        void stop(String roomId);

        RtmpPusher.Status status(String roomId);
    }

    /** The rooms' program outputs ({@link ProgramOutputs}). */
    public interface Outputs {
        /** Starts the room's output (or counts as activity if it runs). */
        void start(String roomId) throws Exception;

        /** Stops it at once, closing its consumers. */
        void stop(String roomId);
    }

    /** The server switches. */
    public interface Switches {
        boolean rtmp() throws SQLException;

        boolean ts() throws SQLException;

        boolean hls() throws SQLException;

        boolean mp3() throws SQLException;
    }

    /** What a running push was started with: restarted when either changes. */
    private record Running(String outputId, String rtmpUrl, String rtmpKey) {
    }

    private final StreamOutputs rows;
    private final Switches switches;
    private final Outputs outputs;
    private final Pushers pushers; // null: no ffmpeg, no RTMP
    private final Runnable revalidateTs;
    private final Map<String, Running> running = new HashMap<>();
    private ScheduledExecutorService timer;
    private volatile OutputAutoOff autoOff;
    private volatile Runnable reconcileListener = () -> {
    };

    public StreamOutputControl(StreamOutputs rows, Switches switches, Outputs outputs, Pushers pushers,
                               Runnable revalidateTs) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.switches = Objects.requireNonNull(switches, "switches");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.pushers = pushers;
        this.revalidateTs = Objects.requireNonNull(revalidateTs, "revalidateTs");
    }

    /** Whether RTMP pushes can run at all (ffmpeg configured). */
    public boolean rtmpAvailable() {
        return pushers != null;
    }

    /** Whether MP3 outputs can be encoded (ffmpeg configured, as for RTMP). */
    public boolean mp3Available() {
        return pushers != null;
    }

    /** The room's push status, or null if it has no push running. */
    public RtmpPusher.Status rtmpStatus(String roomId) {
        return pushers == null ? null : pushers.status(roomId);
    }

    /** Reconciles now, then every {@link #PERIOD_MS} ms on a daemon thread. */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        reconcile();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stream-outputs");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::reconcileQuietly, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Turns outputs off automatically ({@link OutputAutoOff}); set before {@link #start}. */
    public void autoOff(OutputAutoOff autoOff) {
        this.autoOff = autoOff;
    }

    /**
     * Turns off every output whose room was idle, or that nobody listened to, for its limit and
     * brings the pushes, connections and program outputs in line ({@link #roomChanged}). Runs on
     * the timer before each reconcile.
     *
     * @return the outputs turned off
     */
    public List<OutputAutoOff.Stop> checkIdle() {
        OutputAutoOff a = autoOff;
        if (a == null) {
            return List.of();
        }
        List<OutputAutoOff.Stop> stopped = a.check();
        for (String roomId : new java.util.LinkedHashSet<>(stopped.stream().map(OutputAutoOff.Stop::roomId).toList())) {
            roomChanged(roomId, false);
        }
        return stopped;
    }

    private void reconcileQuietly() {
        try {
            checkIdle();
            reconcile();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not reconcile the stream outputs", e);
        }
    }

    /**
     * Told after every {@link #reconcile} (the outputs' status feed re-checks every room). Must
     * not block.
     */
    public void reconcileListener(Runnable listener) {
        this.reconcileListener = Objects.requireNonNull(listener);
    }

    /** Brings RTMP pushes and {@code .ts} connections in line with the rows and switches. */
    public void reconcile() {
        try {
            reconcileRtmp();
            syncAutoOff();
            revalidateTs.run();
        } finally {
            reconcileListener.run();
        }
    }

    /** Tells the auto turn-off which outputs are active now. */
    private synchronized void syncAutoOff() {
        OutputAutoOff a = autoOff;
        if (a == null) {
            return;
        }
        List<OutputAutoOff.Key> active = new java.util.ArrayList<>();
        try {
            for (StreamOutputs.Output o : rows.enabled()) {
                if (serverEnabled(o.kind())) {
                    active.add(new OutputAutoOff.Key(o.roomId(), o.kind()));
                }
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the outputs for the auto turn-off", e);
            return;
        }
        a.sync(active);
    }

    private synchronized void reconcileRtmp() {
        if (pushers == null) {
            return;
        }
        Map<String, StreamOutputs.Output> desired = new HashMap<>();
        try {
            if (switches.rtmp()) {
                for (StreamOutputs.Output o : rows.enabledRtmp()) {
                    if (o.rtmpUrl() != null) {
                        desired.put(o.roomId(), o);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the RTMP outputs; pushes left as they are", e);
            return;
        }
        for (String roomId : List.copyOf(running.keySet())) {
            if (!desired.containsKey(roomId)) {
                running.remove(roomId);
                pushers.stop(roomId);
                LOG.log(System.Logger.Level.INFO, "RTMP push of room " + roomId + " stopped");
            }
        }
        for (StreamOutputs.Output o : desired.values()) {
            Running want = new Running(o.id(), o.rtmpUrl(), o.rtmpKey());
            if (want.equals(running.get(o.roomId()))) {
                continue;
            }
            running.put(o.roomId(), want);
            pushers.start(o.roomId(), o.rtmpUrl(), o.rtmpKey());
            LOG.log(System.Logger.Level.INFO, "RTMP push of room " + o.roomId() + " started");
        }
    }

    /**
     * After the API changed the room's outputs: reconciles, starts the room's program output if
     * an output was just turned on ({@code started}), and stops it if the room has no active
     * output left.
     */
    public void roomChanged(String roomId, boolean started) {
        reconcile();
        boolean active;
        try {
            active = hasActiveOutput(roomId);
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the outputs of room " + roomId, e);
            return;
        }
        if (!active) {
            outputs.stop(roomId);
        } else if (started) {
            try {
                outputs.start(roomId);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "The program output of room " + roomId + " could not start", e);
            }
        }
    }

    /**
     * After the room's slug or visibility changed ({@code docs/program-output.md#stream-urls}): a
     * room that became private gets fresh tokens ({@link StreamOutputs#reissueTokens}), then
     * {@link #roomChanged} ends the connections whose URL stopped working (the old slug, slug URLs
     * of a now private room, token URLs of a now public or unlisted one).
     */
    public void roomEdited(String roomId, boolean becamePrivate) {
        if (becamePrivate) {
            try {
                rows.reissueTokens(roomId);
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not issue new stream tokens for room " + roomId, e);
            }
        }
        roomChanged(roomId, false);
    }

    /** Whether one of the room's outputs is on, server switch included. */
    public boolean hasActiveOutput(String roomId) throws SQLException {
        for (StreamOutputs.Output o : rows.list(roomId)) {
            if (o.enabled() && serverEnabled(o.kind())) {
                return true;
            }
        }
        return false;
    }

    /** The server switch of this kind. */
    public boolean serverEnabled(StreamOutputs.Kind kind) throws SQLException {
        return switch (kind) {
            case RTMP -> switches.rtmp();
            case TS -> switches.ts();
            case HLS -> switches.hls();
            case MP3 -> switches.mp3();
        };
    }

    /** Stops the timer and every push this started. */
    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        if (pushers != null) {
            for (String roomId : List.copyOf(running.keySet())) {
                pushers.stop(roomId);
            }
        }
        running.clear();
    }
}
