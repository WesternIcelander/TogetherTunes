package io.siggi.togethertunes.rooms;

/**
 * Something that changed in a room, carried by a {@link RoomChange} to the {@link RoomListener}s
 * (the WebSocket layer turns these into {@code docs/api.md#websocket} deltas). Mutations add
 * them with {@link RoomTx#event}. Events never contain IP addresses or anything derived from
 * them.
 */
public interface RoomEvent {
    /**
     * The room record changed (name, slug, visibility, control mode or owner); the new values are
     * in the {@link RoomState}.
     */
    record RoomUpdated() implements RoomEvent {
    }

    /** The timeline was re-anchored; the new anchor is {@link RoomChange#timeline()}. */
    record TimelineChanged() implements RoomEvent {
    }

    /**
     * A queue entry moved to another state (for example {@code queued -> playing} when the
     * timeline advanced to it, {@code playing -> played} when it ended).
     */
    record EntryStateChanged(String entryId, QueueEntry.State state) implements RoomEvent {
    }

    /** The votes on a queued entry changed; {@code total} is their new sum (the play order may have changed). */
    record VotesChanged(String entryId, int total) implements RoomEvent {
    }

    /** A queued entry was pinned or unpinned ({@code vote} mode). */
    record EntryPinned(String entryId, boolean pinned) implements RoomEvent {
    }

    /** Queue positions were rewritten (the control mode changed and the old play order was kept). */
    record QueueReordered() implements RoomEvent {
    }

    /** Someone voted to skip the current entry: {@code votes} of {@code needed} skip votes. */
    record SkipVotesChanged(String entryId, int votes, int needed) implements RoomEvent {
    }
}
