package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.ingest.WantedItems;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * <p>It is also the rooms' {@link WantedItems} for the ingest claim order
 * ({@code docs/library-and-ingest.md#priorities}): each room publishes its whole wish list
 * ranked by how soon each item plays ({@link #ranks(RoomState)}); {@link #ranks()} merges them
 * (the earliest across rooms) and is rebuilt after every change, and the change listener
 * ({@link #changeListener}) is told so the ingest workers look again.
 */
public final class LivePlayingItems implements PlayingItems, WantedItems {
    /** item id -> how many rooms list it (absent: none). */
    private final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    /** room id -> the items it lists, for the per-room check; replaced (never changed) under {@link #lock}. */
    private final ConcurrentHashMap<String, Set<String>> roomItems = new ConcurrentHashMap<>();
    /** room id -> what the room published and which executor published it. Guarded by {@link #lock}. */
    private final Map<String, Published> byRoom = new HashMap<>();
    /** A ReentrantLock, not synchronized: the critical sections are short but run on virtual threads. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Rooms that force requests-only mode ({@link #requestsOnly}); changed under {@link #lock}. */
    private final Set<String> requestsOnlyRooms = ConcurrentHashMap.newKeySet();

    private record Published(Object owner, Set<String> items, Map<String, Integer> ranks, boolean requestsOnly) {
    }

    /** Every loaded room's ranks merged (the lowest rank per item); replaced, never changed. */
    private volatile Map<String, Integer> merged = Map.of();
    private volatile Runnable changeListener = () -> { };

    /** The merged ranks of every loaded room's wanted items ({@link WantedItems}). */
    @Override
    public Map<String, Integer> ranks() {
        return merged;
    }

    /** Who is told (outside the lock) after the merged ranks changed: the ingest queue. */
    public void changeListener(Runnable listener) {
        this.changeListener = listener == null ? () -> { } : listener;
    }

    /**
     * Everything a room is waiting on, ranked by how soon it plays: the current entry's item 0,
     * the queued entries in play order 1, 2, …, then the suggestions oldest first (they would play
     * once accepted); an item listed twice keeps its earliest rank. Read on the room's executor
     * thread.
     */
    static Map<String, Integer> ranks(RoomState state) {
        Map<String, Integer> out = new LinkedHashMap<>();
        QueueEntry current = state.current();
        if (current != null) {
            out.put(current.itemId(), 0);
        }
        int rank = 1;
        for (QueueEntry e : Playback.playOrder(state)) {
            out.putIfAbsent(e.itemId(), rank++);
        }
        for (QueueEntry e : Playback.suggestionOrder(state)) {
            out.putIfAbsent(e.itemId(), rank++);
        }
        return out;
    }

    @Override
    public boolean isCurrentOrNext(String itemId) {
        return itemId != null && counts.containsKey(itemId);
    }

    @Override
    public boolean isCurrentOrNextIn(String roomId, String itemId) {
        if (roomId == null || itemId == null) {
            return false;
        }
        Set<String> items = roomItems.get(roomId);
        return items != null && items.contains(itemId);
    }

    @Override
    public boolean requestsOnly(String roomId) {
        return roomId != null && requestsOnlyRooms.contains(roomId);
    }

    /**
     * The items of a room that its guests and anonymous visitors may fetch right now: the current entry's item (playing or
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
        update(roomId, owner, items, Map.of(), false);
    }

    /** {@link #update(String, Object, Set)} with the room's ranked wanted items ({@link #ranks(RoomState)}). */
    void update(String roomId, Object owner, Set<String> items, Map<String, Integer> ranks) {
        update(roomId, owner, items, ranks, false);
    }

    /**
     * {@link #update(String, Object, Set)} with the room's ranked wanted items ({@link #ranks(RoomState)})
     * and whether it forces requests-only mode ({@link RoomSettings#requestsOnly()}).
     */
    void update(String roomId, Object owner, Set<String> items, Map<String, Integer> ranks, boolean requestsOnly) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(owner, "owner");
        Set<String> copy = Set.copyOf(items);
        Map<String, Integer> rankCopy = Map.copyOf(ranks);
        boolean ranksChanged;
        lock.lock();
        try {
            Published old = byRoom.get(roomId);
            if (old != null && old.owner == owner && old.items.equals(copy) && old.ranks.equals(rankCopy)
                    && old.requestsOnly == requestsOnly) {
                return;
            }
            ranksChanged = old == null ? !rankCopy.isEmpty() : !old.ranks.equals(rankCopy);
            byRoom.put(roomId, new Published(owner, copy, rankCopy, requestsOnly));
            // Set before the items are listed, so a forced room never briefly lets guests in.
            if (requestsOnly) {
                requestsOnlyRooms.add(roomId);
            }
            roomItems.put(roomId, copy);
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
            if (!requestsOnly) {
                requestsOnlyRooms.remove(roomId);
            }
            if (ranksChanged) {
                remerge();
            }
        } finally {
            lock.unlock();
        }
        if (ranksChanged) {
            notifyChanged();
        }
    }

    /** Withdraws what room {@code roomId} lists, if {@code owner} published it (the executor closed). */
    void remove(String roomId, Object owner) {
        boolean ranksChanged;
        lock.lock();
        try {
            Published old = byRoom.get(roomId);
            if (old == null || old.owner != owner) {
                return;
            }
            byRoom.remove(roomId);
            roomItems.remove(roomId);
            requestsOnlyRooms.remove(roomId);
            for (String item : old.items) {
                decrement(item);
            }
            ranksChanged = !old.ranks.isEmpty();
            if (ranksChanged) {
                remerge();
            }
        } finally {
            lock.unlock();
        }
        if (ranksChanged) {
            notifyChanged();
        }
    }

    /** Rebuilds {@link #merged}; under {@link #lock}. */
    private void remerge() {
        Map<String, Integer> out = new HashMap<>();
        for (Published p : byRoom.values()) {
            for (Map.Entry<String, Integer> e : p.ranks.entrySet()) {
                out.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        merged = Map.copyOf(out);
    }

    private void notifyChanged() {
        try {
            changeListener.run();
        } catch (RuntimeException e) {
            System.getLogger(LivePlayingItems.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not tell ingest that the rooms' wanted items changed", e);
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
