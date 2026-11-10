package io.siggi.togethertunes.rooms;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * A room's timeline anchor ({@code docs/rooms.md#room-state}): the playback state, the current
 * queue entry ({@code null} when {@link PlaybackState#IDLE}), and the anchor (server time in
 * epoch ms, and the entry position at that time). Immutable, so it can be handed to other
 * threads (the packager) as is.
 *
 * <p>Every change (play, pause, seek, skip, advance) <b>re-anchors</b>: it makes a new timeline
 * with {@code anchorServerMs = now} and {@code anchorPositionMs = newPosition}. The position at
 * any other time is worked out from the latest anchor ({@link #positionAt}).
 *
 * <p>The switches on {@link PlaybackState} here are exhaustive with no {@code default}, so a new
 * state (for example {@code LIVE}) doesn't compile until each one decides what it means.
 */
public record Timeline(PlaybackState state, String entryId, long anchorServerMs, long anchorPositionMs) {
    /** Nothing playing. */
    public static final Timeline IDLE = new Timeline(PlaybackState.IDLE, null, 0, 0);

    public Timeline {
        Objects.requireNonNull(state, "state");
        switch (state) {
            case IDLE -> {
                if (entryId != null) {
                    throw new IllegalArgumentException("an idle timeline has no entry");
                }
            }
            case PLAYING, PAUSED -> Objects.requireNonNull(entryId, "entryId");
        }
    }

    /** Playing {@code entryId}, re-anchored: at {@code nowMs} the position is {@code positionMs}. */
    public static Timeline playing(String entryId, long nowMs, long positionMs) {
        return new Timeline(PlaybackState.PLAYING, entryId, nowMs, Math.max(0, positionMs));
    }

    /**
     * Playing {@code entryId} from its start at server time {@code startServerMs}, which may be in
     * the future (an abrupt start put off until the program output can play the entry from 0:00,
     * {@code docs/rooms.md#room-state}): until then {@link #positionAt(long)} is negative, and
     * clients keep the entry silent at 0.
     */
    public static Timeline startingAt(String entryId, long startServerMs) {
        return new Timeline(PlaybackState.PLAYING, entryId, startServerMs, 0);
    }

    /** Paused in {@code entryId} at {@code positionMs}, re-anchored at {@code nowMs}. */
    public static Timeline paused(String entryId, long nowMs, long positionMs) {
        return new Timeline(PlaybackState.PAUSED, entryId, nowMs, Math.max(0, positionMs));
    }

    /**
     * The position in the current entry at server time {@code serverNowMs}:
     * {@code anchorPositionMs + (serverNowMs − anchorServerMs)} while playing, the anchor position
     * otherwise (paused: frozen; idle: 0). Negative before an entry's delayed start
     * ({@link #startingAt}). Not clamped to the entry's duration (see
     * {@link #positionAt(long, long)}).
     */
    public long positionAt(long serverNowMs) {
        return switch (state) {
            case PLAYING -> anchorPositionMs + (serverNowMs - anchorServerMs);
            case PAUSED -> anchorPositionMs;
            case IDLE -> 0;
        };
    }

    /** {@link #positionAt(long)} clamped to {@code [0, durationMs]}. */
    public long positionAt(long serverNowMs, long durationMs) {
        return Math.max(0, Math.min(durationMs, positionAt(serverNowMs)));
    }

    /**
     * The server time at which the current entry (of {@code durationMs}) ends, if the timeline is
     * moving towards it: only while {@link PlaybackState#PLAYING}. Paused and idle timelines never
     * end by themselves.
     */
    public OptionalLong endsAtServerMs(long durationMs) {
        return switch (state) {
            case PLAYING -> OptionalLong.of(anchorServerMs + (durationMs - anchorPositionMs));
            case PAUSED, IDLE -> OptionalLong.empty();
        };
    }
}
