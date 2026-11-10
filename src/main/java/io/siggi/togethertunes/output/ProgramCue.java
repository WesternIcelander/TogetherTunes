package io.siggi.togethertunes.output;

import io.siggi.togethertunes.rooms.PlaybackState;
import io.siggi.togethertunes.rooms.Timeline;
import java.util.Objects;

/**
 * What the room's timeline says the program output should play: an immutable copy the room hands
 * to the packager ({@link TimelineFollower#update}) after every committed change. The program
 * output only reads it and never feeds anything back (invariant 4).
 *
 * @param state            the timeline's state, or {@code null} for a state the packager doesn't
 *                         recognise (treated as {@link PlaybackState#PAUSED},
 *                         {@code docs/program-output.md#applying-timeline-changes})
 * @param current          the current entry; null when idle (or in an unrecognised state without one)
 * @param anchorServerMs   the timeline anchor: server time (epoch ms) ...
 * @param anchorPositionMs ... and the position in {@code current} at that time
 * @param next             the entry that plays after {@code current} if nothing changes (the next
 *                         eligible queued entry), or null if the room would go idle. The output
 *                         starts it by itself when its copy of {@code current} ends before the
 *                         room advances.
 * @param playedOut        the entry {@code current} followed, if the timeline advanced to
 *                         {@code current} because that entry reached its end ({@code played}, not
 *                         skipped); null otherwise. It stays set for as long as the timeline is that
 *                         advance's (later cues for unrelated changes keep it). The output keeps
 *                         playing that entry to its own end instead of cutting it: drift isn't
 *                         corrected mid-item.
 */
public record ProgramCue(PlaybackState state, Entry current, long anchorServerMs, long anchorPositionMs,
                         Entry next, String playedOut) {
    /** Nothing playing. */
    public static final ProgramCue IDLE = new ProgramCue(PlaybackState.IDLE, null, 0, 0, null, null);

    public ProgramCue {
        if (state == PlaybackState.PLAYING || state == PlaybackState.PAUSED) {
            Objects.requireNonNull(current, "current");
        }
        if (state == PlaybackState.IDLE && current != null) {
            throw new IllegalArgumentException("an idle cue has no entry");
        }
    }

    /**
     * The cue for {@code timeline}.
     *
     * @param current the timeline's entry ({@link Timeline#entryId()}; ids must match), or null when idle
     */
    public static ProgramCue of(Timeline timeline, Entry current, Entry next, String playedOut) {
        if (current != null && !current.entryId().equals(timeline.entryId())) {
            throw new IllegalArgumentException("current entry " + current.entryId() + " isn't the timeline's "
                    + timeline.entryId());
        }
        return new ProgramCue(timeline.state(), current, timeline.anchorServerMs(), timeline.anchorPositionMs(),
                next, playedOut);
    }

    /**
     * The position in {@link #current} at {@code serverMs} while playing (not clamped); the anchor
     * position otherwise.
     */
    public long positionAt(long serverMs) {
        return state == PlaybackState.PLAYING ? anchorPositionMs + (serverMs - anchorServerMs) : anchorPositionMs;
    }

    /** Whether the cue is playing {@code entryId}. */
    boolean playing(String entryId) {
        return state == PlaybackState.PLAYING && current.entryId().equals(entryId);
    }

    /**
     * A queue entry and what the packager needs to know about its item.
     *
     * @param videoItem      {@code items.kind = 'video'}
     * @param presentAsAudio {@code items.present_as_audio} (ignored for audio items)
     * @param durationMs     the item's duration (what the room's timeline uses)
     */
    public record Entry(String entryId, String itemId, boolean videoItem, boolean presentAsAudio, long durationMs) {
        public Entry {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(itemId, "itemId");
        }
    }
}
