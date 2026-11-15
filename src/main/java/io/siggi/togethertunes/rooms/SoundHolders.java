package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One window with sound per person ({@code docs/ui.md}, "One window with sound per person";
 * {@code docs/api.md#websocket}, {@code sound} / {@code sound.claim} / {@code sound.released}).
 *
 * <p>Per actor (a user, or a guest; never an anonymous visitor) at most one <b>sound holder</b>: a
 * browser window, named by the random window id the page keeps in its {@code sessionStorage} and
 * sends with {@code join}. A window may have several sockets (a reload, a reconnect, the shell's
 * background room); they all count as that window.
 *
 * <ul>
 *   <li>A window that opens while there is no holder and no other window of the actor is open takes
 *       the sound; one that opens while another window holds it (or while windows are open but none
 *       holds it, after the holder closed) is muted.</li>
 *   <li>{@code sound.claim} makes the sender's window the holder; every other open window of the
 *       actor (any room, any device) gets {@code sound.released}.</li>
 *   <li>When the holder's last socket closes, the window keeps the sound for a grace period (a
 *       reload or reconnect of the same window); after it there is no holder and the other windows
 *       stay muted until one claims.</li>
 * </ul>
 *
 * <p>In memory only. Thread-safe; the windows' {@link Window#sound} must not block (the room
 * socket's outbox).
 */
public final class SoundHolders {
    /** How long a holder window that lost its last socket keeps the sound (ms). */
    public static final long DEFAULT_GRACE_MS = 10_000;

    private static final Pattern WINDOW_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /** A socket of a window: told whether its window has the sound. */
    public interface Window {
        /**
         * {@code holder}: this window has the sound, else another window has it (or nobody does);
         * {@code released}: another window just claimed it ({@code sound.released}). Called under
         * the registry's lock: must not block.
         */
        void sound(boolean holder, boolean released);
    }

    private static final class Person {
        String holder;
        final Map<String, Set<Window>> windows = new HashMap<>();
        ScheduledFuture<?> release;
    }

    private final Object lock = new Object();
    private final Map<String, Person> people = new HashMap<>();
    /** One daemon thread for every registry's grace timers (they only flip a field). */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sound-holders");
        t.setDaemon(true);
        return t;
    });

    private final long graceMs;

    public SoundHolders() {
        this(DEFAULT_GRACE_MS);
    }

    public SoundHolders(long graceMs) {
        this.graceMs = graceMs;
    }

    /** Whether {@code id} is a well-formed window id (8 to 64 of {@code A-Z a-z 0-9 _ -}). */
    public static boolean validWindowId(String id) {
        return id != null && WINDOW_ID.matcher(id).matches();
    }

    /** The key sound holding is kept under: the user or the guest; {@code null} for anonymous. */
    public static String keyOf(Actor actor) {
        return switch (actor) {
            case Actor.User u -> "u:" + u.userId();
            case Actor.Guest g -> "g:" + g.guestId();
            case Actor.Anonymous ignored -> null;
        };
    }

    /**
     * A socket of window {@code windowId} of {@code key} opened (or the socket's actor became this
     * one). Tells it at once whether its window has the sound.
     */
    public void open(String key, String windowId, Window socket) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(windowId);
        Objects.requireNonNull(socket);
        synchronized (lock) {
            Person p = people.computeIfAbsent(key, k -> new Person());
            if (p.holder == null) {
                boolean othersOpen = false;
                for (Map.Entry<String, Set<Window>> e : p.windows.entrySet()) {
                    if (!e.getKey().equals(windowId) && !e.getValue().isEmpty()) {
                        othersOpen = true;
                        break;
                    }
                }
                if (!othersOpen) {
                    p.holder = windowId;
                }
            }
            if (windowId.equals(p.holder) && p.release != null) {
                // The holder came back within the grace period (a reload or reconnect).
                p.release.cancel(false);
                p.release = null;
            }
            p.windows.computeIfAbsent(windowId, w -> new HashSet<>()).add(socket);
            // Told under the lock, so a concurrent claim's message can't overtake this one.
            socket.sound(windowId.equals(p.holder), false);
        }
    }

    /** A socket of window {@code windowId} of {@code key} closed (or its actor changed). */
    public void close(String key, String windowId, Window socket) {
        synchronized (lock) {
            Person p = people.get(key);
            if (p == null) {
                return;
            }
            Set<Window> sockets = p.windows.get(windowId);
            if (sockets == null || !sockets.remove(socket)) {
                return;
            }
            if (!sockets.isEmpty()) {
                return;
            }
            p.windows.remove(windowId);
            if (windowId.equals(p.holder)) {
                if (p.release != null) {
                    p.release.cancel(false);
                }
                p.release = TIMER.schedule(() -> expire(key, p, windowId), graceMs, TimeUnit.MILLISECONDS);
            } else if (p.holder == null && p.windows.isEmpty()) {
                people.remove(key);
            }
        }
    }

    /** The grace period of a holder window that closed ended: nobody has the sound now. */
    private void expire(String key, Person p, String windowId) {
        synchronized (lock) {
            if (people.get(key) != p || !windowId.equals(p.holder) || p.windows.containsKey(windowId)) {
                return;
            }
            p.holder = null;
            p.release = null;
            if (p.windows.isEmpty()) {
                people.remove(key);
            }
        }
    }

    /**
     * {@code sound.claim}: window {@code windowId} of {@code key} takes the sound. Its sockets are
     * told it holds it, every other window's sockets get {@code sound.released}.
     */
    public void claim(String key, String windowId) {
        synchronized (lock) {
            Person p = people.get(key);
            if (p == null || !p.windows.containsKey(windowId)) {
                return; // not open (closed meanwhile)
            }
            if (p.release != null) {
                p.release.cancel(false);
                p.release = null;
            }
            p.holder = windowId;
            for (Map.Entry<String, Set<Window>> e : p.windows.entrySet()) {
                boolean mine = e.getKey().equals(windowId);
                for (Window w : e.getValue()) {
                    w.sound(mine, !mine);
                }
            }
        }
    }

    /** The holder window of {@code key}, or {@code null} (tests). */
    String holder(String key) {
        synchronized (lock) {
            Person p = people.get(key);
            return p == null ? null : p.holder;
        }
    }

    /** How many actors are tracked (tests: nothing is kept for people with no windows). */
    int size() {
        synchronized (lock) {
            return people.size();
        }
    }
}
