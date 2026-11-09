package io.siggi.togethertunes.rooms;

/**
 * Receives a room's committed changes, for broadcasting them (the WebSocket members of the room).
 * Registered with {@link RoomExecutor#addListener}.
 *
 * <p>Both methods run <b>on the room's executor thread</b>, after the change was persisted and the
 * version bumped. They must not block (queue the outgoing message and return) and must not call
 * back into the executor synchronously ({@link RoomExecutor#call} would deadlock and is refused);
 * {@link RoomExecutor#submit} is fine. The {@link RoomState} may be read during the call only.
 * Exceptions are logged and otherwise ignored.
 */
public interface RoomListener {
    /** A change was committed. */
    void onChange(RoomState state, RoomChange change);

    /** The room's executor closed: the room was deleted, or the server is stopping. */
    default void onClosed(String roomId, boolean deleted) {
    }
}
