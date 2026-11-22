package io.siggi.togethertunes.ingest;

/**
 * Told when an item's {@code items.status} changed ({@code pending}, {@code processing},
 * {@code ready}, {@code failed}, {@code purged}), or a re-encode of a {@code ready} item published
 * new files (which may make it playable by the program output, {@link PlayableFiles}), after the
 * change was committed. The rooms use
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

    /**
     * What the rooms show of the item changed besides its status: its title, artist or album was
     * edited, or a re-encode or an undo published or restored other files (a new cover URL, which
     * carries the version, and a new card). Includes {@link #itemStatusChanged}; the rooms send the
     * item again to every page showing it (and so to the system media controls).
     */
    default void itemDetailsChanged(String itemId) {
        itemStatusChanged(itemId);
    }
}
