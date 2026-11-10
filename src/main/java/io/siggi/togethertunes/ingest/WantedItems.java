package io.siggi.togethertunes.ingest;

import java.util.Map;

/**
 * The items the rooms are waiting on, ranked by how soon they play
 * ({@code docs/library-and-ingest.md#priorities}): the encode jobs of these items go before every
 * job for an item no room wants ({@link IngestJobs#claimNext}). The server uses the rooms' live
 * state ({@code rooms.LivePlayingItems}), read again at every claim, so queueing an item later
 * moves its pending job forward.
 */
@FunctionalInterface
public interface WantedItems {
    /**
     * Item id to rank: 0 for a room's current item, then by how soon the item plays (its place in
     * its room's play order, the suggestions after the queue), the earliest across rooms. Called
     * from ingest workers, so it must be cheap and thread-safe; the map is not changed afterwards.
     */
    Map<String, Integer> ranks();

    /** No room wants anything (no rooms wired, tests). */
    WantedItems NONE = Map::of;
}
