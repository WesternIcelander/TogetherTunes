package io.siggi.togethertunes.rooms;

import java.util.Objects;

/**
 * A room's timeline anchor ({@code docs/rooms.md#room-state}): the playback state, the current
 * queue entry ({@code null} when {@link PlaybackState#IDLE}), and the anchor (server time in
 * epoch ms, and the entry position at that time). Immutable, so it can be handed to other
 * threads (the packager) as is.
 */
public record Timeline(PlaybackState state, String entryId, long anchorServerMs, long anchorPositionMs) {
    /** Nothing playing. */
    public static final Timeline IDLE = new Timeline(PlaybackState.IDLE, null, 0, 0);

    public Timeline {
        Objects.requireNonNull(state, "state");
    }
}
