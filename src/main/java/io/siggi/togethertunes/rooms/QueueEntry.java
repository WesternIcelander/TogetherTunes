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
 * @param audioOnlyMs the length of a video item's audio-only file ({@code items.audio_only_ms}),
 *                    or {@code null} when it has none (its audio-only file is then {@code audio.m4a},
 *                    {@code durationMs} long)
 * @param audioOnly   whether the entry plays audio-only ({@code docs/rooms.md#audio-only-rooms}):
 *                    waiting entries follow the room's {@code audioOnly} setting; the current entry
 *                    keeps the mode it started with until it ends ({@link RoomState} keeps both)
 */
public record QueueEntry(String id, String itemId, String addedByUserId, String addedByGuestId, long addedAt,
                         State state, double position, boolean pinned, Long playedAt, String itemStatus,
                         Long durationMs, boolean filesUsable, Long audioOnlyMs, boolean audioOnly) {
    public QueueEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(itemStatus, "itemStatus");
    }

    /** An entry that doesn't play audio-only (or whose item has no separate audio-only file). */
    public QueueEntry(String id, String itemId, String addedByUserId, String addedByGuestId, long addedAt,
                      State state, double position, boolean pinned, Long playedAt, String itemStatus,
                      Long durationMs, boolean filesUsable) {
        this(id, itemId, addedByUserId, addedByGuestId, addedAt, state, position, pinned, playedAt, itemStatus,
                durationMs, filesUsable, null, false);
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
        Long length = lengthMs();
        return "ready".equals(itemStatus) && length != null && length > 0 && filesUsable;
    }

    /**
     * How long the entry plays: the audio-only file's length ({@link #audioOnlyMs}) when it plays
     * audio-only and its item has one, else the item's duration; {@code null} if not known yet.
     * This is what the timeline uses.
     */
    public Long lengthMs() {
        return audioOnly && audioOnlyMs != null && durationMs != null ? audioOnlyMs : durationMs;
    }

    /** Whether it plays its item's separate audio-only file ({@code audio-only.m4a}). */
    public boolean playsAudioOnlyFile() {
        return audioOnly && audioOnlyMs != null;
    }

    public QueueEntry withState(State newState, Long newPlayedAt) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, newState, position, pinned,
                newPlayedAt, itemStatus, durationMs, filesUsable, audioOnlyMs, audioOnly);
    }

    public QueueEntry withPlace(double newPosition, boolean newPinned) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, state, newPosition, newPinned,
                playedAt, itemStatus, durationMs, filesUsable, audioOnlyMs, audioOnly);
    }

    public QueueEntry withItem(String newStatus, Long newDurationMs, boolean newFilesUsable) {
        return withItem(newStatus, newDurationMs, newFilesUsable, audioOnlyMs);
    }

    public QueueEntry withItem(String newStatus, Long newDurationMs, boolean newFilesUsable, Long newAudioOnlyMs) {
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, state, position, pinned, playedAt,
                newStatus, newDurationMs, newFilesUsable, newAudioOnlyMs, audioOnly);
    }

    /** This entry playing audio-only, or not. */
    public QueueEntry withAudioOnly(boolean newAudioOnly) {
        if (newAudioOnly == audioOnly) {
            return this;
        }
        return new QueueEntry(id, itemId, addedByUserId, addedByGuestId, addedAt, state, position, pinned, playedAt,
                itemStatus, durationMs, filesUsable, audioOnlyMs, newAudioOnly);
    }
}
