package io.siggi.togethertunes.rooms;

/**
 * Tells the program output ({@code docs/program-output.md}) about committed room changes. Called
 * on the room's executor thread after the listeners; it must only hand the change over (for
 * example into a queue the packager thread reads) and return. The program output follows the
 * timeline and <b>never feeds back into it</b> (invariant 4): implementations don't call into the
 * room executor.
 *
 * <p>The packager comes in M4; until then {@link #NONE} ignores everything.
 */
@FunctionalInterface
public interface PackagerHook {
    /** A change was committed in a room. */
    void roomChanged(RoomChange change);

    /** The room was deleted or the server is stopping. */
    default void roomClosed(String roomId, boolean deleted) {
    }

    /** No packager yet (until M4). */
    PackagerHook NONE = change -> {
    };
}
