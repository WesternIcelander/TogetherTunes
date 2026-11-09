package io.siggi.togethertunes.rooms;

/**
 * A unit of work on a room, run on the room's executor thread ({@link RoomExecutor#call},
 * {@link RoomExecutor#submit}, {@link RoomExecutor#schedule}).
 *
 * <p>A mutation checks first and changes afterwards: it throws (for example an
 * {@link io.siggi.togethertunes.http.HttpError}) <b>before</b> touching the state. It changes the
 * {@link RoomState}, registers the matching database writes with {@link RoomTx#persist} and
 * describes the change with {@link RoomTx#event}. A mutation that registers neither is read-only:
 * nothing is persisted, the version stays and nothing is broadcast. If it fails after changing
 * something, or the writes fail, the executor reloads the state from the database.
 */
@FunctionalInterface
public interface RoomMutation<T> {
    T apply(RoomTx tx) throws Exception;
}
