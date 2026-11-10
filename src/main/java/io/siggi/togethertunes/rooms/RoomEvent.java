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

    /**
     * The room's settings changed; the new ones are {@link RoomState#settings()}, {@code changed}
     * names the settings that changed (dotted, e.g. {@code guests.canVote}).
     */
    record SettingsChanged(java.util.List<String> changed) implements RoomEvent {
        public SettingsChanged {
            changed = java.util.List.copyOf(changed);
        }
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

    /**
     * The items of these entries (queued, suggested or the current one) changed status or
     * duration, e.g. their ingest finished or failed, or the item was purged; the new values are
     * in the {@link RoomState}'s entries.
     */
    record EntryItemsChanged(java.util.List<String> entryIds) implements RoomEvent {
        public EntryItemsChanged {
            entryIds = java.util.List.copyOf(entryIds);
        }
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

    /** An entry joined the queue ({@code queued}) or the suggestions ({@code suggested}). */
    record EntryAdded(String entryId, QueueEntry.State state) implements RoomEvent {
    }

    /**
     * Someone other than the owner (or a moderator in {@code owner} mode) requested an item:
     * {@code via} is {@code library}, {@code upload}, {@code youtube} or {@code history}. The
     * WebSocket layer tells the owner and moderators ({@code request.new}).
     */
    record RequestAdded(String entryId, String via) implements RoomEvent {
    }

    /** A queued entry was moved before {@code beforeEntryId} ({@code null}: to the end) of a manual queue. */
    record EntryMoved(String entryId, String beforeEntryId) implements RoomEvent {
    }

    /** The presence counts changed (someone joined, left, switched mode or got an identity). */
    record PresenceChanged(RoomPresence.Counts counts) implements RoomEvent {
    }

    /**
     * A guest was banned from the room: {@code connectionIds} are its connections that were
     * removed from the presence (the WebSocket layer closes them with {@code kicked}). In IP mode
     * one guest is everyone sharing that address.
     */
    record GuestBanned(String guestId, java.util.List<String> connectionIds) implements RoomEvent {
        public GuestBanned {
            connectionIds = java.util.List.copyOf(connectionIds);
        }
    }

    /** A guest's ban from the room was lifted. */
    record GuestUnbanned(String guestId) implements RoomEvent {
    }

    /**
     * A user's role in the room was set or removed (a member added, promoted, demoted, banned,
     * unbanned or gone). The WebSocket layer re-checks that user's sockets: new capabilities, or
     * {@code kicked} if it may no longer see the room.
     */
    record MemberChanged(String userId) implements RoomEvent {
    }
}
