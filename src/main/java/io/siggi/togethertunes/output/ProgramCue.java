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
 * @param audioOnly        the room's {@code audioOnly} setting now: the stream rate of fillers
 *                         ({@code docs/program-output.md#constant-rate-padding}); entries carry
 *                         their own mode ({@link Entry#audioOnly()})
 * @param gap              the room's transition between items now ({@code transitionCard} and
 *                         {@code gapMs}, {@code docs/program-output.md#transitions}): what the
 *                         output plays between an item's natural end and the next item
 */
public record ProgramCue(PlaybackState state, Entry current, long anchorServerMs, long anchorPositionMs,
                         Entry next, String playedOut, boolean audioOnly, Gap gap) {
    /** Nothing playing. */
    public static final ProgramCue IDLE = new ProgramCue(PlaybackState.IDLE, null, 0, 0, null, null);

    /** A cue of a room that isn't audio-only. */
    public ProgramCue(PlaybackState state, Entry current, long anchorServerMs, long anchorPositionMs, Entry next,
                      String playedOut) {
        this(state, current, anchorServerMs, anchorPositionMs, next, playedOut, false);
    }

    /** A cue of a room without a transition between items. */
    public ProgramCue(PlaybackState state, Entry current, long anchorServerMs, long anchorPositionMs, Entry next,
                      String playedOut, boolean audioOnly) {
        this(state, current, anchorServerMs, anchorPositionMs, next, playedOut, audioOnly, Gap.NONE);
    }

    public ProgramCue {
        gap = gap == null ? Gap.NONE : gap;
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
        return of(timeline, current, next, playedOut, false);
    }

    /** {@link #of(Timeline, Entry, Entry, String)} for a room with this {@code audioOnly} setting. */
    public static ProgramCue of(Timeline timeline, Entry current, Entry next, String playedOut, boolean audioOnly) {
        return of(timeline, current, next, playedOut, audioOnly, Gap.NONE);
    }

    /** {@link #of(Timeline, Entry, Entry, String, boolean)} with the room's transition between items. */
    public static ProgramCue of(Timeline timeline, Entry current, Entry next, String playedOut, boolean audioOnly,
                                Gap gap) {
        if (current != null && !current.entryId().equals(timeline.entryId())) {
            throw new IllegalArgumentException("current entry " + current.entryId() + " isn't the timeline's "
                    + timeline.entryId());
        }
        return new ProgramCue(timeline.state(), current, timeline.anchorServerMs(), timeline.anchorPositionMs(),
                next, playedOut, audioOnly, gap);
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
     * The transition between items ({@code docs/program-output.md#transitions}): after an item's
     * natural end the room starts the next entry {@link #ms} later, and the output plays that gap
     * as silence with the next item's card ({@link #card}, the transition card) or the blank card.
     *
     * @param ms   the gap's length (the room's {@code RoomSettings.transitionGapMs()}), 0 for none
     * @param card whether the gap shows the next item's card
     */
    public record Gap(long ms, boolean card) {
        /** No gap: the next item starts right where the previous one ends. */
        public static final Gap NONE = new Gap(0, false);

        public Gap {
            if (ms < 0) {
                throw new IllegalArgumentException("ms < 0");
            }
        }

        /**
         * The gap as whole AAC frames (nearest), or 0 when it is shorter than one video frame (the
         * output can't show a picture for less, so such a gap is left out).
         */
        public long audioFrames() {
            long frames = Math.round(ms * 90.0 / ProgramSource.AUDIO_FRAME_TICKS);
            return frames * ProgramSource.AUDIO_FRAME_TICKS < ProgramSource.VIDEO_FRAME_TICKS ? 0 : frames;
        }
    }

    /**
     * A queue entry and what the packager needs to know about its item.
     *
     * @param videoItem      {@code items.kind = 'video'}
     * @param presentAsAudio a video item of media type {@code audio} (ignored for audio items)
     * @param durationMs     how long the entry plays (what the room's timeline uses: the audio-only
     *                       file's length when it plays that)
     * @param audioOnly      the entry plays audio-only ({@code docs/rooms.md#audio-only-rooms}): a
     *                       video item as audio, and the stream padded to the audio-only rate
     * @param audioOnlyFile  with {@code audioOnly}, a video item plays its separate audio-only file
     *                       ({@code audio-only.m4a} with {@code index-audio-only.bin}) instead of
     *                       its {@code audio.m4a}
     */
    public record Entry(String entryId, String itemId, boolean videoItem, boolean presentAsAudio, long durationMs,
                        boolean audioOnly, boolean audioOnlyFile) {
        public Entry {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(itemId, "itemId");
            audioOnlyFile = audioOnlyFile && audioOnly && videoItem;
        }

        /** An entry that doesn't play audio-only. */
        public Entry(String entryId, String itemId, boolean videoItem, boolean presentAsAudio, long durationMs) {
            this(entryId, itemId, videoItem, presentAsAudio, durationMs, false, false);
        }

        /** Whether the item plays as audio: an audio item, or a video shown as audio or in audio-only mode. */
        public boolean playsAsAudio() {
            return !videoItem || presentAsAudio || audioOnly;
        }
    }
}
