package io.siggi.togethertunes.output;

/**
 * A source that plays a library item: {@link VideoItemSource} or {@link AudioItemSource}. It knows
 * the media time it actually starts from (a video item starts at the keyframe at or before the
 * requested position), which the unit builder needs to compare the output with the timeline.
 */
public sealed interface ItemSource extends ProgramSource permits VideoItemSource, AudioItemSource {
    String itemId();

    /** The position asked for, in ms of media time. */
    long requestedStartMs();

    /** The media time the first audio frame starts at, in ms (rounded down). */
    long mediaStartMs();
}
