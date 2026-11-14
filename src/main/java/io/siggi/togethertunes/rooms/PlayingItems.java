package io.siggi.togethertunes.rooms;

/**
 * The items that are the <b>current</b> or the <b>next</b> entry in the queue of a room right
 * now ({@code docs/libraries.md#media-access-rules}). A guest or anonymous visitor may fetch the
 * media of these items while they are in that room ({@link RoomViewers}): the current one to play
 * it, the next one so clients can preload it. Old media versions of these items are kept
 * ({@code ingest.MediaVersions}).
 *
 * <p>The check runs on every media request, so implementations answer from memory and must be
 * thread-safe. The server uses {@link LivePlayingItems} ({@link RoomRegistry#playingItems()}),
 * the in-memory sets the room executors keep up to date.
 */
public interface PlayingItems {
    /** Whether {@code itemId} is the current or the next entry of any room's queue right now. */
    boolean isCurrentOrNext(String itemId);

    /** Whether {@code itemId} is the current or the next entry of room {@code roomId}'s queue right now. */
    boolean isCurrentOrNextIn(String roomId, String itemId);

    /**
     * Whether room {@code roomId} forces requests-only mode ({@link RoomSettings#requestsOnly()}):
     * then being in it gives guests and anonymous visitors no media ({@link RoomViewers}).
     */
    default boolean requestsOnly(String roomId) {
        return false;
    }

    /** Nothing is playing anywhere (tests that don't involve rooms). */
    PlayingItems NO_ROOMS = new PlayingItems() {
        @Override
        public boolean isCurrentOrNext(String itemId) {
            return false;
        }

        @Override
        public boolean isCurrentOrNextIn(String roomId, String itemId) {
            return false;
        }
    };
}
