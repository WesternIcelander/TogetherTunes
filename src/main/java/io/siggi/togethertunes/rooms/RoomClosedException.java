package io.siggi.togethertunes.rooms;

/**
 * A {@link RoomExecutor} no longer takes work: the room was deleted, the server is stopping, or
 * the executor gave up after its state couldn't be reloaded. {@link RoomRegistry#call} retries
 * on a freshly loaded executor, which turns a deleted room into {@code 404 room_not_found}.
 */
public final class RoomClosedException extends RuntimeException {
    public RoomClosedException(String roomId) {
        super("Room " + roomId + " is closed");
    }
}
