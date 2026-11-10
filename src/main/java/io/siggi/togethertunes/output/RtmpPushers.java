package io.siggi.togethertunes.output;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's RTMP pushes, at most one per room ({@code docs/program-output.md#rtmp-push-vrcdn-etc}).
 * A push pins the room's program output running ({@link ProgramOutputs#keepRunning}) and, for each
 * attempt, holds a lease on it with a {@code join=latest} queue (the newest unit, which starts with
 * an IDR, goes to ffmpeg at once). Checking the server and room switches and decrypting the key stay
 * with the caller (the outputs API).
 */
public final class RtmpPushers implements StreamOutputControl.Pushers, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RtmpPushers.class.getName());

    /** Told about every status change of a room's push (any thread; must not block). */
    @FunctionalInterface
    public interface StatusListener {
        void changed(String roomId, RtmpPusher.Status status);
    }

    private final ProgramOutputs outputs;
    private final String ffmpeg;
    private final RtmpPusher.Launcher launcher;
    private final ConcurrentHashMap<String, RtmpPusher> pushers = new ConcurrentHashMap<>();
    private volatile StatusListener listener;
    private volatile boolean closed;

    /** @param ffmpeg the ffmpeg executable ({@code tools.ffmpeg}) */
    public RtmpPushers(ProgramOutputs outputs, String ffmpeg) {
        this(outputs, ffmpeg, RtmpPusher.processLauncher());
    }

    RtmpPushers(ProgramOutputs outputs, String ffmpeg, RtmpPusher.Launcher launcher) {
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    public void statusListener(StatusListener l) {
        this.listener = l;
    }

    /**
     * Starts pushing the room's program output to {@code <rtmpUrl>/<key>}, replacing a push the room
     * already has (e.g. after the URL or key changed).
     */
    public synchronized void start(String roomId, String rtmpUrl, String key) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        RtmpPusher old = pushers.remove(roomId);
        if (old != null) {
            old.close();
        }
        try {
            outputs.keepRunning(roomId, true);
        } catch (Exception e) {
            // Still pinned; the pusher's attempts report the failure and the sweep keeps trying.
            LOG.log(System.Logger.Level.WARNING, "The program output of room " + roomId + " could not start", e);
        }
        RtmpPusher pusher = new RtmpPusher(roomId, ffmpeg, rtmpUrl, key, () -> subscribe(roomId), launcher,
                Thread::sleep, System::currentTimeMillis, s -> {
                    StatusListener l = listener;
                    if (l != null) {
                        l.changed(roomId, s);
                    }
                });
        pushers.put(roomId, pusher);
        pusher.start();
    }

    /** Stops the room's push (if any); the output then stops after the usual idle time. */
    public synchronized void stop(String roomId) {
        RtmpPusher p = pushers.remove(roomId);
        if (p != null) {
            p.close();
            try {
                outputs.keepRunning(roomId, false);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Could not unpin the program output of room " + roomId, e);
            }
        }
    }

    /** The room's push status, or null if it has no push. */
    public RtmpPusher.Status status(String roomId) {
        RtmpPusher p = pushers.get(roomId);
        return p == null ? null : p.status();
    }

    private RtmpPusher.Subscription subscribe(String roomId) throws Exception {
        ProgramOutputs.Lease lease = outputs.acquire(roomId);
        ConsumerQueue queue = lease.packager().subscribe(true);
        return new RtmpPusher.Subscription() {
            @Override
            public ConsumerQueue queue() {
                return queue;
            }

            @Override
            public void close() {
                queue.leave();
                lease.close();
            }
        };
    }

    /** Stops every push (server shutdown: before the program outputs close). */
    @Override
    public synchronized void close() {
        closed = true;
        for (String roomId : new ArrayList<>(pushers.keySet())) {
            RtmpPusher p = pushers.remove(roomId);
            if (p != null) {
                p.close();
            }
        }
    }
}
