package io.siggi.togethertunes.rooms;

/**
 * The items that are the <b>current</b> or the <b>next</b> entry in the queue of any room right
 * now ({@code docs/libraries.md#media-access-rules}). Guests and anonymous visitors may fetch the
 * media of these items even when {@code guests.download} is off: the current one to play it, the
 * next one so clients can preload it.
 *
 * <p>The check runs on every media request, so implementations answer from memory and must be
 * thread-safe. The server uses {@link LivePlayingItems} ({@link RoomRegistry#playingItems()}),
 * the in-memory {@code Set<itemId>} the room executors keep up to date.
 */
@FunctionalInterface
public interface PlayingItems {
    /** Whether {@code itemId} is the current or the next entry of any room's queue right now. */
    boolean isCurrentOrNext(String itemId);

    /** Nothing is playing anywhere (tests that don't involve rooms). */
    PlayingItems NO_ROOMS = itemId -> false;
}
