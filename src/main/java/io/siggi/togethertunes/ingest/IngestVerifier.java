package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.util.Locale;

/**
 * The verify step of an ingest job ({@code docs/library-and-ingest.md#ingest-job-states}), run on
 * the finished {@code index.bin} before anything is published:
 *
 * <ul>
 *   <li>the audio ({@code audio.m4a}) ends where the source's audio ends, within
 *       {@link #MAX_AUDIO_DRIFT_US} (the encode starts at 0 like the source, with a late audio
 *       start padded with silence);</li>
 *   <li>the video track's avcC (the mezzanine's, or the card's for an audio item) is
 *       byte-identical to the reference card's (invariant 1);</li>
 *   <li>the video track starts at 0 and has an IDR exactly every 2 s and nowhere else, every 60th
 *       frame (invariant 2).</li>
 * </ul>
 */
public final class IngestVerifier {
    /** Allowed difference between the source's and {@code audio.m4a}'s audio duration. */
    public static final long MAX_AUDIO_DRIFT_US = 50_000;

    private IngestVerifier() {
    }

    /**
     * Runs all checks.
     *
     * @param sourceAudioUs where the source's audio ends, in microseconds ({@link MediaProbe#audioEndUs()},
     *                      or the length decoded by the loudness pass)
     * @param videoFile     the file the video track came from, for messages ({@code mezz.mp4}/{@code card.mp4})
     * @throws IngestFailure                       if the duration or the GOP structure is wrong
     * @throws ReferenceCard.AvcCMismatchException if the avcC differs from the reference
     */
    public static void verify(SampleIndex index, ReferenceCard reference, long sourceAudioUs, String videoFile)
            throws IngestFailure, ReferenceCard.AvcCMismatchException {
        checkAudioDuration(index, sourceAudioUs);
        reference.verify(index.avcC(), videoFile);
        checkKeyframes(index, videoFile);
    }

    /** Where the audio track ends, in microseconds (its first sample is at 0). */
    static long audioDurationUs(SampleIndex index) throws IngestFailure {
        Track audio = index.track(Kind.AUDIO);
        if (audio == null || audio.sampleCount() == 0) {
            throw new IngestFailure("Verify: the encode has no audio");
        }
        return Math.round(audio.endPts() * 1_000_000.0 / audio.timescale());
    }

    static void checkAudioDuration(SampleIndex index, long sourceAudioUs) throws IngestFailure {
        if (sourceAudioUs <= 0) {
            throw new IngestFailure("Verify: the length of the source's audio is unknown");
        }
        long encoded = audioDurationUs(index);
        if (Math.abs(encoded - sourceAudioUs) > MAX_AUDIO_DRIFT_US) {
            throw new IngestFailure(String.format(Locale.ROOT,
                    "Verify: the encoded audio ends at %.3f s, but the source's ends at %.3f s (more than %d ms apart)",
                    encoded / 1e6, sourceAudioUs / 1e6, MAX_AUDIO_DRIFT_US / 1000));
        }
    }

    static void checkKeyframes(SampleIndex index, String videoFile) throws IngestFailure {
        Track video = index.track(Kind.VIDEO);
        if (video == null || video.sampleCount() == 0) {
            throw new IngestFailure("Verify: " + videoFile + " has no video frames");
        }
        long gopTicks = video.timescale() * EncodeProfile.GOP_MS / 1000;
        if (video.timescale() * EncodeProfile.GOP_MS % 1000 != 0) {
            throw new IngestFailure("Verify: " + videoFile + " has a time base (1/" + video.timescale()
                    + ") that can't express 2 s exactly");
        }
        for (int i = 0; i < video.sampleCount(); i++) {
            boolean gopStart = i % EncodeProfile.GOP_FRAMES == 0;
            if (video.isKeyframe(i) != gopStart) {
                throw new IngestFailure("Verify: " + videoFile + " frame " + i
                        + (gopStart ? " should be an IDR but isn't" : " is a keyframe outside the 2 s grid"));
            }
            if (gopStart) {
                long expected = (long) (i / EncodeProfile.GOP_FRAMES) * gopTicks;
                if (video.pts(i) != expected) {
                    throw new IngestFailure("Verify: " + videoFile + " IDR " + (i / EncodeProfile.GOP_FRAMES)
                            + " is at " + video.pts(i) + "/" + video.timescale() + " s instead of " + expected
                            + "/" + video.timescale() + " s");
                }
            }
        }
    }
}
