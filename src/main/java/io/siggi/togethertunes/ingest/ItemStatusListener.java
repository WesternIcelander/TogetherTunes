package io.siggi.togethertunes.ingest;

/**
 * Told when an item's {@code items.status} changed ({@code pending}, {@code processing},
 * {@code ready}, {@code failed}, {@code purged}), after the change was committed. The rooms use
 * it to refresh the entries that play the item and to start an idle room
 * ({@code docs/rooms.md#room-state}: a not-ready entry becomes eligible as soon as its item is
 * ready).
 *
 * <p>Called on the thread that committed the change (an ingest worker, a request handler, the
 * retention sweeper), so an implementation must return quickly and must not throw.
 */
@FunctionalInterface
public interface ItemStatusListener {
    /** Does nothing. */
    ItemStatusListener NONE = itemId -> {
    };

    void itemStatusChanged(String itemId);
}
