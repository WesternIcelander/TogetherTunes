package io.siggi.togethertunes.rooms;

import java.util.Locale;
import java.util.Objects;

/**
 * A row of {@code queue_entries} as the room executor holds it, together with what the timeline
 * needs to know about its item (status and duration). Immutable; the executor replaces it when
 * something changes.
 *
 * @param playedAt   when the entry started playing (epoch ms), or {@code null} if it never did
 * @param itemStatus the item's {@code items.status} when last read ({@code ready}, {@code pending},
 *                   {@code processing}, {@code failed}, {@code purged})
 * @param durationMs the item's duration, or {@code null} if not known yet
 * @param filesUsable whether the program output can play the item's files
 *                   ({@link io.siggi.togethertunes.ingest.PlayableFiles}; only meaningful while the
 *                   item is {@code ready}): {@code false} for files of an older output encoding
 *                   waiting for their re-encode, or an unreadable {@code index.bin}
 */
public record QueueEntry(String id, String itemId, String addedByUserId, String addedByGuestId, long addedAt,
                         State state, double position, boolean pinned, Long playedAt, String itemStatus,
                         Long durationMs, boolean filesUsable) {
    public QueueEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(itemStatus, "itemStatus");
    }

    /** An entry whose item's files are usable (if it is ready). */
    public QueueEntry(String id, String itemId, String addedByUserId, String addedByGuestId, long addedAt,
                      State state, double position, boolean pinned, Long playedAt, String itemStatus,
                      Long durationMs) {
        this(id, itemId, addedByUserId, addedByGuestId, addedAt, state, position, pinned, playedAt, itemStatus,
                durationMs, true);
    }

    /** {@code queue_entries.state}. */
    public enum State {
        QUEUED, SUGGESTED, PLAYING, PLAYED, SKIPPED, REMOVED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The state with this wire name, or {@code null} if there is none. */
        public static State parse(String s) {
            for (State v : values()) {
                if (v.wire().equals(s)) {
                    return v;
                }
            }
            return null;
        }
    }

    /**
     * Whether the item can be played: it is {@code ready} with a known, positive duration, and
     * the program output can play its files ({@link #filesUsable}). An entry that isn't stays in
     * the queue ("processing") and is skipped over when choosing the next entry
     * ({@code docs/rooms.md#room-state}).
     */
    public boolean ready() {
        return "ready".equals(itemStatus) && durationMs != null && durationMs > 0 && filesUsable;
    }

    public QueueEntry withState(State newState, Long newPlayedAt) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, newState, position, pinned,
                newPlayedAt, itemStatus, durationMs, filesUsable);
    }

    public QueueEntry withPlace(double newPosition, boolean newPinned) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, state, newPosition, newPinned,
                playedAt, itemStatus, durationMs, filesUsable);
    }

    public QueueEntry withItem(String newStatus, Long newDurationMs, boolean newFilesUsable) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, state, position, pinned, playedAt,
                newStatus, newDurationMs, newFilesUsable);
    }
}
