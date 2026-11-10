package io.siggi.togethertunes.output;

import java.io.IOException;

/**
 * Something the packager can play: a stream of video samples and AAC frames that the unit builder
 * turns into units ({@code docs/program-output.md#units}). Implementations: {@link VideoItemSource},
 * {@link AudioItemSource} and {@link FillerSource}.
 *
 * <p>The abstraction is pull-based and deliberately assumes neither a file nor a known duration,
 * so a future live input fits it: a source ends when {@link #nextAudio()} returns null, and a read
 * may block until data is available. Nothing here asks how long a source is.
 *
 * <p>Timing: audio drives everything. Audio frame <i>k</i> (counted from the source's start) is at
 * {@code k·1920} ticks relative to the source's start; video samples carry their own relative PTS
 * ({@link VideoSample#pts()}), with the first sample an IDR at 0. Video samples at or after the end
 * of the audio are dropped by the unit builder; when the video runs out first, the builder holds the
 * last picture. Not thread-safe: one packager thread pulls from a source.
 */
public interface ProgramSource extends AutoCloseable {
    /** Ticks of the 90 kHz clock per AAC frame (1024 samples at 48 kHz), exactly. */
    long AUDIO_FRAME_TICKS = 1920;
    /** Ticks of the 90 kHz clock per video frame at 30 fps. */
    long VIDEO_FRAME_TICKS = 3000;

    /**
     * The next video sample, or null when this source has no more video (the last picture is held
     * until the next source's IDR).
     */
    VideoSample nextVideo() throws IOException;

    /**
     * The next raw AAC frame (no ADTS header; shared, never modified), or null when the source has
     * ended. Frames are exactly {@link #AUDIO_FRAME_TICKS} apart.
     */
    byte[] nextAudio() throws IOException;

    /** A short description for logs and status, e.g. {@code video item 0190…@12000ms}. */
    String describe();

    /** Releases what the source holds of its own. Sources don't own shared media (indexes, files). */
    @Override
    default void close() {
    }
}
