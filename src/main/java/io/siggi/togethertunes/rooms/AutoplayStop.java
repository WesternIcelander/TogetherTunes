package io.siggi.togethertunes.rooms;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Autoplay stops when nobody is there ({@code docs/ui.md#autoplay-from-playlists}, "Autoplay stops
 * when nobody is there"; {@code docs/rooms.md#autoplay}).
 *
 * <p><b>Presence clock.</b> "Nobody is there" means no room WebSocket connection of anyone
 * (signed-in, guest or anonymous; an alone room or background room view counts, so does a hidden
 * tab): {@link RoomPresence#emptySince}, which starts when the last one leaves, or at the room's load
 * for a room loaded with nobody in it (server start), and resets when someone connects. With the
 * room setting {@code autoplay.pullListenersCount}, open {@code .ts}/MP3 connections and HLS
 * requests ({@link PullListening}) count as people too: the clock starts no earlier than the last
 * of them.
 *
 * <p><b>Stop.</b> Once a <b>playing</b> room has been empty for its limit ({@link #limitMinutes}:
 * the room's {@code autoplay.stopAfterMinutes}, at most the server's
 * {@code autoplay.maxStopAfterMinutes}, or {@code autoplay.maxStopAfterMinutesWithListeners} when
 * the room counts stream listeners), its autoplay stops topping up the queue ({@link Autoplay}):
 * what is queued and every request still play, then the room goes idle and its outputs turn off
 * by the idle rule. The stop is part of the room's state ({@link RoomState#autoplayStopped},
 * persisted with the room row, so a restart keeps it) and its WebSocket state
 * ({@code room.autoplayStopped}). A room whose autoplay is off ({@code autoplay.upcoming} 0), or
 * that is paused or idle, isn't stopped: autoplay adds nothing there anyway.
 *
 * <p><b>Resume.</b> When a web user connects to the room ({@link PresenceOps#join}; a stream
 * listener never resumes it), and by {@code autoplay.resume} / {@code POST
 * /api/rooms/{room}/autoplay/resume} from someone who may select autoplay playlists
 * ({@link AutoplaySelections#resume}). Changing the selections or settings doesn't resume it.
 *
 * <p>{@link #checkAll} runs every {@link #PERIOD_MS} ms ({@link #start}) and looks at every
 * loaded room on its executor; rooms that aren't loaded aren't playing.
 */
public final class AutoplayStop implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AutoplayStop.class.getName());
    /** How often the rooms are checked. */
    public static final long PERIOD_MS = 10_000;
    /** {@link Stopped#reason}: nobody was in the room for the limit. */
    public static final String REASON_NOBODY = "nobody";
    /** {@link PullListening#lastListenedAt}: somebody listens right now. */
    public static final long LISTENING_NOW = Long.MAX_VALUE;
    /** {@link PullListening#lastListenedAt}: nobody listened (since the server started). */
    public static final long NEVER = Long.MIN_VALUE;

    /**
     * Autoplay stopped at {@code at} (epoch ms) for {@code reason} ({@link #REASON_NOBODY}) after
     * {@code minutes}.
     */
    public record Stopped(long at, String reason, int minutes) {
        public Stopped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The server's caps on the room's minutes. */
    public record Caps(int maxMinutes, int maxMinutesWithListeners) {
    }

    /** Reads the server's caps ({@code autoplay.maxStopAfterMinutes*}). */
    @FunctionalInterface
    public interface CapsSource {
        Caps caps() throws SQLException;
    }

    /** When somebody last listened to one of a room's pull outputs ({@code .ts}, HLS, MP3). Must not block. */
    @FunctionalInterface
    public interface PullListening {
        PullListening NONE = roomId -> NEVER;

        /** {@link #LISTENING_NOW}, the epoch ms of the last listening, or {@link #NEVER}. */
        long lastListenedAt(String roomId);
    }

    private final RoomRegistry registry;
    private final CapsSource caps;
    private final PullListening pull;
    private ScheduledExecutorService timer;

    public AutoplayStop(RoomRegistry registry, CapsSource caps, PullListening pull) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.caps = Objects.requireNonNull(caps, "caps");
        this.pull = Objects.requireNonNull(pull, "pull");
    }

    /** The effective limit: {@code min(room, cap)}, at least 1. */
    public static int limitMinutes(int roomMinutes, int capMinutes) {
        return Math.max(1, Math.min(roomMinutes, capMinutes));
    }

    /** The room's limit with these server caps. */
    static int limitMinutes(RoomSettings settings, Caps caps) {
        int cap = settings.autoplayPullListenersCount() ? caps.maxMinutesWithListeners() : caps.maxMinutes();
        return limitMinutes(settings.autoplayStopAfterMinutes(), cap);
    }

    /** Runs {@link #checkAll} every {@link #PERIOD_MS} ms on a thread of its own. */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("autoplay-stop").factory());
        timer.scheduleWithFixedDelay(() -> {
            try {
                checkAll();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Autoplay stop check failed", t);
            }
        }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    /**
     * Checks every loaded room on its executor ({@link #check}).
     *
     * @return one future per room, completing with whether it stopped
     */
    public List<CompletableFuture<Boolean>> checkAll() throws SQLException {
        Caps c = caps.caps();
        List<CompletableFuture<Boolean>> out = new ArrayList<>();
        for (RoomExecutor e : registry.loadedExecutors()) {
            String roomId = e.roomId();
            out.add(e.submit(tx -> check(tx, c, pull.lastListenedAt(roomId))));
        }
        return out;
    }

    /**
     * Stops the room's autoplay if nobody has been there for its limit (see the class comment).
     * On the room's executor.
     *
     * @param lastListened {@link PullListening#lastListenedAt} for the room
     * @return whether it stopped now
     */
    static boolean check(RoomTx tx, Caps caps, long lastListened) {
        RoomState s = tx.state();
        RoomSettings settings = s.settings();
        if (s.autoplayStopped() != null || settings.autoplayUpcoming() == 0
                || s.timeline().state() != PlaybackState.PLAYING) {
            return false;
        }
        RoomPresence p = s.presence();
        Long empty = p.connections() > 0 ? null : p.emptySince();
        if (empty == null) {
            return false;
        }
        long since = empty;
        if (settings.autoplayPullListenersCount()) {
            if (lastListened == LISTENING_NOW) {
                return false;
            }
            since = Math.max(since, lastListened);
        }
        int minutes = limitMinutes(settings, caps);
        if (tx.now() - since < minutes * 60_000L) {
            return false;
        }
        s.autoplayStopped(new Stopped(tx.now(), REASON_NOBODY, minutes));
        tx.event(new RoomEvent.AutoplayStopChanged(true));
        LOG.log(System.Logger.Level.INFO, "Autoplay of room {0} stopped: nobody there for {1} min", s.id(), minutes);
        return true;
    }

    /**
     * Resumes a stopped autoplay: clears the stop and fills the autoplay section again. On the
     * room's executor.
     *
     * @return whether it was stopped
     */
    static boolean resume(RoomTx tx) {
        RoomState s = tx.state();
        if (s.autoplayStopped() == null) {
            return false;
        }
        s.autoplayStopped(null);
        tx.event(new RoomEvent.AutoplayStopChanged(false));
        tx.refreshAutoplay();
        return true;
    }
}
