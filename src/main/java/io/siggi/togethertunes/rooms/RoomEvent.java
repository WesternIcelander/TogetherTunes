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

    /**
     * A waiting entry moved to another section of the queue ({@code docs/rooms.md#autoplay}), e.g.
     * an autoplay entry turned into a request.
     */
    record EntrySectionChanged(String entryId, QueueEntry.Section section) implements RoomEvent {
    }

    /** A queued entry was moved before {@code beforeEntryId} ({@code null}: to the end) of a manual queue. */
    record EntryMoved(String entryId, String beforeEntryId) implements RoomEvent {
    }

    /** The presence counts changed (someone joined, left, switched mode or got an identity). */
    record PresenceChanged(RoomPresence.Counts counts) implements RoomEvent {
    }

    /**
     * A ban or timeout in the room was put in place or lifted ({@link RoomBans}; a user's, a
     * guest's, and the IP address it covers). The WebSocket layer re-checks every socket in the
     * room: who is read-only now, and its {@code you}. Nobody is kicked: banned people may listen.
     */
    record RestrictionsChanged() implements RoomEvent {
    }

    /**
     * A guest was merged into an account ({@code POST /api/me/merge-guest}): its entries and votes in
     * this room are now the user's; the new values are in the {@link RoomState}.
     */
    record GuestMerged(String guestId, String userId) implements RoomEvent {
    }

    /**
     * A user's role in the room was set or removed (a member added, promoted, demoted, banned,
     * unbanned or gone). The WebSocket layer re-checks that user's sockets: new capabilities, or
     * {@code kicked} if it may no longer see the room.
     */
    record MemberChanged(String userId) implements RoomEvent {
    }
}
