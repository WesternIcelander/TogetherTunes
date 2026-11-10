package io.siggi.togethertunes.output;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Turns a room's outputs off automatically ({@code docs/program-output.md#auto-turn-off}).
 *
 * <p>While the room's {@code outputs.autoOff} is on (or the server's {@code outputs.forceAutoOff}),
 * an active output is turned off ({@link StreamOutputs#stopAuto}: {@code enabled = false} as if the
 * owner had pressed Turn off, the reason recorded, audited as the system; it stays off until the
 * owner turns it on) once <b>either</b>
 *
 * <ul>
 *   <li>(a) the room has been <b>idle</b> (nothing playing, nothing queued: the program output shows
 *       the Idle card, {@link ProgramOutputs.IdleListener}) for the limit, reason
 *       {@link StreamOutputs#STOPPED_IDLE}; per room, applies to every output; or</li>
 *   <li>(b) for {@code .ts}, HLS and MP3, <b>nobody has listened</b> for the limit: no open
 *       {@code .ts}/MP3 connection ({@link #opened}/{@link #closed}) and no HLS request
 *       ({@link #touched}), reason {@link StreamOutputs#STOPPED_NO_LISTENERS}; per output. RTMP has
 *       no listeners of its own, so only (a) applies to it.</li>
 * </ul>
 *
 * <p>Both clocks start no earlier than the moment the output became active ({@link #sync}: turned
 * on, the server switch turned on, or the server started), so turning an output on gives it a fresh
 * timer even if the room is still idle, and an output nobody uses turns off too. Any activity (an
 * item queued or playing; a listener) resets the respective clock.
 *
 * <p>The limit is the room's {@code outputs.autoOffMinutes}, at most the server's
 * {@code outputs.maxAutoOffMinutes} ({@link #effectiveMinutes}, {@link Policies}). {@link #check}
 * runs from {@link StreamOutputControl}'s timer (every {@link StreamOutputControl#PERIOD_MS}); the
 * rest is called from any thread and never blocks.
 */
public final class OutputAutoOff implements OutputListeners {
    private static final System.Logger LOG = System.getLogger(OutputAutoOff.class.getName());

    /** A room's effective auto turn-off: whether it applies, and after how many minutes. */
    public record Policy(boolean enabled, int minutes) {
    }

    /** The effective policy of a room (room setting, server force and cap). */
    @FunctionalInterface
    public interface Policies {
        Policy policy(String roomId) throws SQLException;
    }

    /** One of a room's outputs. */
    public record Key(String roomId, StreamOutputs.Kind kind) {
        public Key {
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** An output that was turned off. */
    public record Stop(String roomId, StreamOutputs.Kind kind, String reason, int minutes) {
    }

    private final StreamOutputs rows;
    private final Policies policies;
    private final LongSupplier nowMs;
    /** Rooms that are idle now → since when (ms). */
    private final Map<String, Long> idleSince = new ConcurrentHashMap<>();
    /** Active outputs → since when (ms). */
    private final Map<Key, Long> activeSince = new ConcurrentHashMap<>();
    /** Open {@code .ts}/MP3 connections per output. */
    private final Map<Key, Integer> open = new ConcurrentHashMap<>();
    /** When somebody last listened (a connection closed, an HLS request), per output. */
    private final Map<Key, Long> lastListen = new ConcurrentHashMap<>();

    public OutputAutoOff(StreamOutputs rows, Policies policies, LongSupplier nowMs) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
    }

    /**
     * The effective policy: on if the room has it on or the server forces it; the minutes are the
     * room's, at most the server's maximum, at least 1.
     */
    public static Policy policy(boolean roomAutoOff, int roomMinutes, boolean serverForce, int serverMaxMinutes) {
        return new Policy(roomAutoOff || serverForce, effectiveMinutes(roomMinutes, serverMaxMinutes));
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

    /**
     * The outputs active now (row on and server switch on): a newly active one gets a fresh timer,
     * one no longer active is forgotten. Called after every reconcile.
     */
    public void sync(Collection<Key> active) {
        Set<Key> now = new HashSet<>(active);
        long t = nowMs.getAsLong();
        activeSince.keySet().removeIf(k -> !now.contains(k));
        lastListen.keySet().removeIf(k -> !now.contains(k));
        for (Key k : now) {
            activeSince.putIfAbsent(k, t);
        }
    }

    @Override
    public void opened(String roomId, StreamOutputs.Kind kind) {
        open.merge(new Key(roomId, kind), 1, Integer::sum);
    }

    @Override
    public void closed(String roomId, StreamOutputs.Kind kind) {
        Key k = new Key(roomId, kind);
        open.computeIfPresent(k, (key, n) -> n <= 1 ? null : n - 1);
        lastListen.put(k, nowMs.getAsLong());
    }

    @Override
    public void touched(String roomId, StreamOutputs.Kind kind) {
        lastListen.put(new Key(roomId, kind), nowMs.getAsLong());
    }

    /** Open connections of the output (tests). */
    int openConnections(String roomId, StreamOutputs.Kind kind) {
        return open.getOrDefault(new Key(roomId, kind), 0);
    }

    /**
     * Turns off every active output whose room was idle, or (not RTMP) that nobody listened to,
     * for its room's limit.
     *
     * @return the outputs turned off (the caller brings pushes and connections in line)
     */
    public List<Stop> check() {
        long now = nowMs.getAsLong();
        List<Stop> stopped = new ArrayList<>();
        Map<String, Policy> seen = new java.util.HashMap<>();
        for (Map.Entry<Key, Long> e : List.copyOf(activeSince.entrySet())) {
            Key k = e.getKey();
            long since = e.getValue();
            try {
                Policy p = seen.get(k.roomId());
                if (p == null) {
                    p = policies.policy(k.roomId());
                    seen.put(k.roomId(), p);
                }
                if (!p.enabled()) {
                    continue;
                }
                long limit = p.minutes() * 60_000L;
                String reason = null;
                Long idle = idleSince.get(k.roomId());
                if (idle != null && now - Math.max(since, idle) >= limit) {
                    reason = StreamOutputs.STOPPED_IDLE;
                } else if (k.kind() != StreamOutputs.Kind.RTMP && !open.containsKey(k)
                        && now - Math.max(since, lastListen.getOrDefault(k, 0L)) >= limit) {
                    reason = StreamOutputs.STOPPED_NO_LISTENERS;
                }
                if (reason == null) {
                    continue;
                }
                if (rows.stopAuto(k.roomId(), k.kind(), reason, p.minutes()) != null) {
                    LOG.log(System.Logger.Level.INFO, "{0} output of room {1} turned off: {2} for {3} min",
                            k.kind().wire(), k.roomId(), reason, p.minutes());
                    stopped.add(new Stop(k.roomId(), k.kind(), reason, p.minutes()));
                }
                activeSince.remove(k);
            } catch (SQLException | RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Could not check the auto turn-off of room " + k.roomId(), ex);
            }
        }
        return stopped;
    }
}
