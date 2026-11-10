package io.siggi.togethertunes.rooms;

/**
 * Tells the program output ({@code docs/program-output.md}) about committed room changes. Called
 * on the room's executor thread after the listeners; it must only hand the change over (for
 * example into a queue the packager thread reads) and return. The program output follows the
 * timeline and <b>never feeds back into it</b> (invariant 4): implementations don't call into the
 * room executor.
 *
 * <p>Implemented by {@code output.ProgramOutputs}; {@link #NONE} ignores everything (tests, and
 * code that has no program output).
 */
@FunctionalInterface
public interface PackagerHook {
    /** A change was committed in a room. */
    void roomChanged(RoomChange change);

    /**
     * A change was committed in a room; {@code state} is the room's live state after it, readable
     * only during this call (it belongs to the executor thread). The executor calls this one; by
     * default it forwards to {@link #roomChanged(RoomChange)}.
     */
    default void roomChanged(RoomState state, RoomChange change) {
        roomChanged(change);
    }

    /**
     * Where an entry started abruptly now (a skip, a start from idle; not a natural end) should
     * start, so the program output plays it from 0:00 ({@code docs/rooms.md#room-state}, abrupt
     * starts): the server time (epoch ms) of the output's first unit it hasn't built yet, or
     * {@code nowMs} when the room's program output isn't running. Called on the room's executor
     * thread: it must answer from what the output already knows, without waiting. This only reads
     * the output's schedule; the output still never changes the timeline (invariant 4).
     */
    default long itemStartAt(String roomId, long nowMs) {
        return nowMs;
    }

    /** The room was deleted or the server is stopping. */
    default void roomClosed(String roomId, boolean deleted) {
    }

    /** Ignores every change. */
    PackagerHook NONE = change -> {
    };
}
