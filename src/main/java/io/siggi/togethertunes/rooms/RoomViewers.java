package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.IpKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Which rooms the guests and anonymous visitors are in right now, for the media access rule
 * ({@code docs/libraries.md#media-access-rules}, {@link GuestMediaAccess}): a guest or anonymous
 * visitor gets an item's media only while it is the current or next entry ({@link LivePlayingItems})
 * of a room they are <b>in</b>, that is a room one of their WebSocket connections has joined (in
 * listen or remote mode). Users are never tracked: they may fetch any item.
 *
 * <p>A connection is recorded under one <b>viewer key</b>: a guest's id ({@code g:…}), or for an
 * anonymous connection its client's {@link IpKeys IP key} ({@code ip:…}; nothing else tells an
 * anonymous media request apart). A media request from a guest matches its guest key and also its
 * IP key (it could drop its cookie and ask anonymously from the same address anyway). The keys
 * live only in memory and are never sent anywhere ({@code CLAUDE.md} invariant 5).
 *
 * <p>The room socket calls {@link #enter} when a connection joins a room or its identity changes
 * and {@link #exit} when it leaves the room or closes. Thread-safe: changes are serialised by a
 * lock, {@link #allowed} reads concurrent maps without it.
 */
public final class RoomViewers implements GuestMediaAccess {
    private final PlayingItems playing;
    /** connection id -> where it is recorded. Guarded by {@link #lock} for changes. */
    private final Map<String, Seat> byConnection = new ConcurrentHashMap<>();
    /** viewer key -> room id -> connections of that viewer in the room. */
    private final Map<String, Map<String, Integer>> rooms = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    private record Seat(String viewerKey, String roomId) {
    }

    public RoomViewers(PlayingItems playing) {
        this.playing = Objects.requireNonNull(playing);
    }

    /**
     * The viewer key a connection of {@code actor} is recorded under, or {@code null} if it isn't
     * recorded (a user, or an anonymous connection without a usable address).
     */
    static String viewerKey(Actor actor, String clientIp) {
        return switch (actor) {
            case Actor.User ignored -> null;
            case Actor.Guest g -> "g:" + g.guestId();
            case Actor.Anonymous ignored -> ipViewerKey(clientIp);
        };
    }

    private static String ipViewerKey(String clientIp) {
        String key = clientIp == null ? null : IpKeys.ipKey(clientIp);
        return key == null ? null : "ip:" + key;
    }

    /**
     * Connection {@code connectionId} of {@code actor} is in room {@code roomId} (replacing where
     * it was recorded before: a socket that joins another room, or gets an identity).
     */
    public void enter(String connectionId, Actor actor, String clientIp, String roomId) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(roomId, "roomId");
        String key = viewerKey(actor, clientIp);
        lock.lock();
        try {
            Seat old = byConnection.get(connectionId);
            if (old != null && old.viewerKey.equals(key) && old.roomId.equals(roomId)) {
                return;
            }
            // Add before removing, so a viewer that stays in the room is never briefly missing.
            if (key != null) {
                byConnection.put(connectionId, new Seat(key, roomId));
                rooms.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).merge(roomId, 1, Integer::sum);
            } else {
                byConnection.remove(connectionId);
            }
            if (old != null) {
                release(old);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Connection {@code connectionId} left its room (or closed); nothing if it wasn't recorded. */
    public void exit(String connectionId) {
        lock.lock();
        try {
            Seat old = byConnection.remove(connectionId);
            if (old != null) {
                release(old);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Under {@link #lock}. */
    private void release(Seat seat) {
        Map<String, Integer> in = rooms.get(seat.viewerKey);
        if (in == null) {
            return;
        }
        in.computeIfPresent(seat.roomId, (r, n) -> n <= 1 ? null : n - 1);
        if (in.isEmpty()) {
            rooms.remove(seat.viewerKey);
        }
    }

    @Override
    public boolean allowed(Actor actor, String clientIp, String itemId) {
        if (actor instanceof Actor.User) {
            return true;
        }
        if (itemId == null) {
            return false;
        }
        List<String> keys = new ArrayList<>(2);
        if (actor instanceof Actor.Guest g) {
            keys.add("g:" + g.guestId());
        }
        String ip = ipViewerKey(clientIp);
        if (ip != null) {
            keys.add(ip);
        }
        for (String key : keys) {
            Map<String, Integer> in = rooms.get(key);
            if (in == null) {
                continue;
            }
            for (String roomId : in.keySet()) {
                if (playing.isCurrentOrNextIn(roomId, itemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many connections are recorded (tests). */
    int size() {
        return byConnection.size();
    }
}
