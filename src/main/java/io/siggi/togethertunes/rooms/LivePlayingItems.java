package io.siggi.togethertunes.rooms;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live {@link PlayingItems}: the in-memory {@code Set<itemId>} of every loaded room's current
 * and next items ({@code docs/libraries.md#media-access-rules}), kept up to date by the room
 * executors. Each {@link RoomExecutor} publishes its room's items after every mutation it runs (and
 * after a reload) and withdraws them when it closes. Owned by {@link RoomRegistry}.
 *
 * <p>Reads ({@link #isCurrentOrNext}) are lock-free lookups in a concurrent map of item id to the
 * number of rooms listing it; updates are serialised by a lock and add a room's new items before
 * dropping its old ones, so an item that stays current or next is never briefly missing.
 *
 * <p>Only loaded rooms count. A room is loaded by anything that opens it (the room view, a member
 * joining) and every playing room is loaded at server start, so a paused room nobody has opened
 * since a restart lists nothing until someone does.
 */
public final class LivePlayingItems implements PlayingItems {
    /** item id -> how many rooms list it (absent: none). */
    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    /** room id -> what the room published and which executor published it. Guarded by {@link #lock}. */
    private final Map<String, Published> byRoom = new HashMap<>();
    /** A ReentrantLock, not synchronized: the critical sections are short but run on virtual threads. */
    private final ReentrantLock lock = new ReentrantLock();

    private record Published(Object owner, Set<String> items) {
    }

    @Override
    public boolean isCurrentOrNext(String itemId) {
        return itemId != null && counts.containsKey(itemId);
    }

    /**
     * The items of a room that guests may fetch right now: the current entry's item (playing or
     * paused), the first queued entry in play order, and the first queued entry that is ready to
     * play ({@link Playback#nextEligible}; it differs from the first one when that one is still
     * being ingested and would be skipped over). Suggestions don't count: they don't play until
     * accepted. Read on the room's executor thread.
     */
    static Set<String> of(RoomState state) {
        Set<String> out = new LinkedHashSet<>(4);
        QueueEntry current = state.current();
        if (current != null) {
            out.add(current.itemId());
        }
        List<QueueEntry> order = Playback.playOrder(state);
        if (!order.isEmpty()) {
            out.add(order.get(0).itemId());
            for (QueueEntry e : order) {
                if (e.ready()) {
                    out.add(e.itemId());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Replaces what room {@code roomId} lists with {@code items}, published by {@code owner} (its
     * executor). A later {@link #remove} only withdraws the items if the same owner still holds the
     * room, so a closing executor can't wipe what its replacement already published.
     */
    void update(String roomId, Object owner, Set<String> items) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(owner, "owner");
        Set<String> copy = Set.copyOf(items);
        lock.lock();
        try {
            Published old = byRoom.get(roomId);
            if (old != null && old.owner == owner && old.items.equals(copy)) {
                return;
            }
            byRoom.put(roomId, new Published(owner, copy));
            for (String item : copy) {
                if (old == null || !old.items.contains(item)) {
                    counts.merge(item, 1, Integer::sum);
                }
            }
            if (old != null) {
                for (String item : old.items) {
                    if (!copy.contains(item)) {
                        decrement(item);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Withdraws what room {@code roomId} lists, if {@code owner} published it (the executor closed). */
    void remove(String roomId, Object owner) {
        lock.lock();
        try {
            Published old = byRoom.get(roomId);
            if (old == null || old.owner != owner) {
                return;
            }
            byRoom.remove(roomId);
            for (String item : old.items) {
                decrement(item);
            }
        } finally {
            lock.unlock();
        }
    }

    /** How many distinct items are listed (tests). */
    int size() {
        return counts.size();
    }

    private void decrement(String item) {
        counts.computeIfPresent(item, (k, n) -> n <= 1 ? null : n - 1);
    }
}
