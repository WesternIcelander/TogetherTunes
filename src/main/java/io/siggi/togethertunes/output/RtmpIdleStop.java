package io.siggi.togethertunes.output;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Turns a room's RTMP output off once the room has been idle for too long
 * ({@code docs/program-output.md#idle-stop}).
 *
 * <p><b>Idle</b> = nothing playing and nothing queued: the program output shows the Idle card
 * ({@link ProgramOutputs.IdleListener}). Paused with an item is not idle. The clock for a room
 * starts at the later of "went idle" and "its push started", so turning the output on again starts
 * a fresh timer even if the room is still idle; any activity (an item queued or playing) resets
 * it. Once a room's push has been running while the room was idle for the limit,
 * {@link StreamOutputs#stopIdle} turns the RTMP output off ({@code enabled = false}, URL and key
 * kept, the reason recorded, audited as the system), and it stays off until the owner turns it on.
 *
 * <p>The limit is the room's {@code outputs.rtmp.idleStopMinutes}, at most the server's
 * {@code outputs.rtmp.maxIdleStopMinutes} ({@link Limits}). Only RTMP: {@code .ts} and HLS are
 * pulled by viewers. {@link #check} runs from {@link StreamOutputControl}'s timer (every
 * {@link StreamOutputControl#PERIOD_MS}); the rest is called from any thread and never blocks.
 */
public final class RtmpIdleStop {
    private static final System.Logger LOG = System.getLogger(RtmpIdleStop.class.getName());

    /** The effective idle limit of a room, in minutes (room setting, capped by the server's maximum). */
    @FunctionalInterface
    public interface Limits {
        int minutes(String roomId) throws SQLException;
    }

    private final StreamOutputs rows;
    private final Limits limits;
    private final LongSupplier nowMs;
    /** Rooms that are idle now → since when (ms). */
    private final Map<String, Long> idleSince = new ConcurrentHashMap<>();
    /** Rooms whose RTMP push runs → since when (ms). */
    private final Map<String, Long> pushSince = new ConcurrentHashMap<>();

    public RtmpIdleStop(StreamOutputs rows, Limits limits, LongSupplier nowMs) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
    }

    /** The effective limit: {@code min(room, server maximum)}, at least 1. */
    public static int effectiveMinutes(int roomMinutes, int serverMaxMinutes) {
        return Math.max(1, Math.min(roomMinutes, serverMaxMinutes));
    }

    /** Whether the room is idle now ({@link ProgramOutputs.IdleListener}). */
    public void idle(String roomId, boolean idle) {
        if (idle) {
            idleSince.putIfAbsent(roomId, nowMs.getAsLong());
        } else {
            idleSince.remove(roomId);
        }
    }

    /** The room's RTMP push started (the output was turned on, or at startup): a fresh timer. */
    public void pushStarted(String roomId) {
        pushSince.put(roomId, nowMs.getAsLong());
    }

    /** The room's RTMP push stopped. */
    public void pushStopped(String roomId) {
        pushSince.remove(roomId);
    }

    /** When the room's push is turned off if nothing happens, ms; or -1 (not idle, no push). Tests. */
    long idleStartMs(String roomId) {
        Long push = pushSince.get(roomId);
        Long idle = idleSince.get(roomId);
        return push == null || idle == null ? -1 : Math.max(push, idle);
    }

    /**
     * Turns off the RTMP output of every room whose push ran while the room was idle for its limit.
     *
     * @return the rooms whose output was turned off (the caller stops their pushes)
     */
    public List<String> check() {
        long now = nowMs.getAsLong();
        List<String> stopped = new ArrayList<>();
        for (String roomId : List.copyOf(pushSince.keySet())) {
            long since = idleStartMs(roomId);
            if (since < 0) {
                continue;
            }
            try {
                int minutes = limits.minutes(roomId);
                if (now - since < minutes * 60_000L) {
                    continue;
                }
                if (rows.stopIdle(roomId, minutes) != null) {
                    LOG.log(System.Logger.Level.INFO, "RTMP output of room {0} turned off: idle for {1} min",
                            roomId, minutes);
                    stopped.add(roomId);
                }
                pushSince.remove(roomId);
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not check the idle time of room " + roomId, e);
            }
        }
        return stopped;
    }
}
