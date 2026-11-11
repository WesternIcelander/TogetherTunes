package io.siggi.togethertunes.output;

import java.io.IOException;

/**
 * A video item's GOPs re-encoded with the room's overlay ({@code docs/overlay.md#packager-substitution}),
 * played by {@link VideoItemSource} instead of the item's own GOP whenever the re-encoded GOP is
 * complete. Frame numbers are the mezzanine's video sample numbers: frame <i>i</i> replaces sample
 * <i>i</i> one for one (same timing, same IDR positions, same avcC by invariant 1). Implemented by
 * {@code overlay.OverlayGops}.
 */
public interface VideoOverlay {
    /** The mezzanine's frame count this overlay was made for (a mismatch means another video). */
    int frameCount();

    /**
     * The AVCC samples of the GOP that starts at mezzanine frame {@code keyframe}, in decode order
     * up to the next keyframe (or the end), if that GOP is complete; null if it isn't (yet).
     * Called on the packager thread at every GOP start: it may read the GOP's bytes from disk, but
     * never waits for the encoder.
     *
     * @throws IOException if a complete GOP can't be read (the source then plays the original)
     */
    byte[][] gop(int keyframe) throws IOException;
}
