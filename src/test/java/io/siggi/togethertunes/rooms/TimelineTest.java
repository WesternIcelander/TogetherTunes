package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The timeline math ({@code docs/rooms.md#room-state}). */
class TimelineTest {
    private static final long T0 = 1_800_000_000_000L;

    @Test
    void playingPositionMovesWithTheServerClock() {
        Timeline t = Timeline.playing("e", T0, 12_000);
        assertEquals(12_000, t.positionAt(T0));
        assertEquals(17_500, t.positionAt(T0 + 5_500));
        assertEquals(11_000, t.positionAt(T0 - 1_000));
        assertEquals(OptionalLong.of(T0 + 48_000), t.endsAtServerMs(60_000));
    }

    @Test
    void pausedPositionIsFrozenAndNeverEnds() {
        Timeline t = Timeline.paused("e", T0, 12_000);
        assertEquals(12_000, t.positionAt(T0));
        assertEquals(12_000, t.positionAt(T0 + 3_600_000));
        assertEquals(OptionalLong.empty(), t.endsAtServerMs(60_000));
    }

    @Test
    void idleHasNoEntryAndNoPosition() {
        assertEquals(0, Timeline.IDLE.positionAt(T0));
        assertEquals(OptionalLong.empty(), Timeline.IDLE.endsAtServerMs(60_000));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(PlaybackState.IDLE, "e", T0, 0));
        assertThrows(NullPointerException.class, () -> new Timeline(PlaybackState.PLAYING, null, T0, 0));
        assertThrows(NullPointerException.class, () -> new Timeline(PlaybackState.PAUSED, null, T0, 0));
        assertThrows(NullPointerException.class, () -> new Timeline(null, null, T0, 0));
    }

    @Test
    void clampedPositionStaysInsideTheEntry() {
        Timeline t = Timeline.playing("e", T0, 0);
        assertEquals(60_000, t.positionAt(T0 + 90_000, 60_000));
        assertEquals(0, t.positionAt(T0 - 5_000, 60_000));
        assertEquals(30_000, t.positionAt(T0 + 30_000, 60_000));
    }

    @Test
    void reAnchoringKeepsThePositionContinuous() {
        Timeline playing = Timeline.playing("e", T0, 10_000);
        long pauseAt = T0 + 7_000;
        Timeline paused = Timeline.paused("e", pauseAt, playing.positionAt(pauseAt));
        assertEquals(17_000, paused.positionAt(pauseAt + 60_000));
        long resumeAt = pauseAt + 60_000;
        Timeline resumed = Timeline.playing("e", resumeAt, paused.positionAt(resumeAt));
        assertEquals(19_000, resumed.positionAt(resumeAt + 2_000));
        // Negative positions are clamped to the start.
        assertEquals(0, Timeline.playing("e", T0, -5).anchorPositionMs());
        assertEquals(0, Timeline.paused("e", T0, -5).anchorPositionMs());
    }

    @Test
    void wireNamesRoundTripAndUnknownIsNull() {
        for (PlaybackState s : PlaybackState.values()) {
            assertEquals(s, PlaybackState.parse(s.wire()));
        }
        assertEquals(null, PlaybackState.parse("live"));
        for (QueueEntry.State s : QueueEntry.State.values()) {
            assertEquals(s, QueueEntry.State.parse(s.wire()));
        }
        assertEquals(null, QueueEntry.State.parse("bogus"));
    }
}
