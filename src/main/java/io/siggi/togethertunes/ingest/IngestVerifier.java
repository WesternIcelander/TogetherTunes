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
 *       start padded with silence). Where the source's audio ends is what its container says
 *       ({@link MediaProbe#audioEndUs()}); if the encode doesn't match that but does match the
 *       length the loudness pass actually decoded, it is accepted too: some decoders (WMA) output
 *       more than the container declares, and the encode then rightly has that length;</li>
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
     * @param sourceAudioUs  where the source's audio ends according to its container, in microseconds
     *                       ({@link MediaProbe#audioEndUs()} or {@link MediaProbe#readAudioEndUs}), -1 if unknown
     * @param decodedAudioUs where the audio decoded by the loudness pass ended (its last
     *                       {@code out_time}), in microseconds, -1 if unknown
     * @param videoFile     the file the video track came from, for messages ({@code mezz.mp4}/{@code card.mp4})
     * @return where the source's audio ends, as the encode matched it ({@code sourceAudioUs}, or
     *         {@code decodedAudioUs} if it only matched that)
     * @throws IngestFailure                       if the duration or the GOP structure is wrong
     * @throws ReferenceCard.AvcCMismatchException if the avcC differs from the reference
     */
    public static long verify(SampleIndex index, ReferenceCard reference, long sourceAudioUs, long decodedAudioUs,
                              String videoFile) throws IngestFailure, ReferenceCard.AvcCMismatchException {
        long matched = checkAudioDuration(index, sourceAudioUs, decodedAudioUs);
        reference.verify(index.avcC(), videoFile);
        checkKeyframes(index, videoFile);
        return matched;
    }

    /** Where the audio track ends, in microseconds (its first sample is at 0). */
    static long audioDurationUs(SampleIndex index) throws IngestFailure {
        Track audio = index.track(Kind.AUDIO);
        if (audio == null || audio.sampleCount() == 0) {
            throw new IngestFailure("Verify: the encode has no audio");
        }
        return Math.round(audio.endPts() * 1_000_000.0 / audio.timescale());
    }

    /**
     * Checks the audio length; see the class comment.
     *
     * @return the reference the encode matched: {@code sourceAudioUs}, or {@code decodedAudioUs}
     *         if it only matched that
     */
    static long checkAudioDuration(SampleIndex index, long sourceAudioUs, long decodedAudioUs) throws IngestFailure {
        if (sourceAudioUs <= 0 && decodedAudioUs <= 0) {
            throw new IngestFailure("Verify: the length of the source's audio is unknown");
        }
        long encoded = audioDurationUs(index);
        if (sourceAudioUs > 0 && Math.abs(encoded - sourceAudioUs) <= MAX_AUDIO_DRIFT_US) {
            return sourceAudioUs;
        }
        if (decodedAudioUs > 0 && Math.abs(encoded - decodedAudioUs) <= MAX_AUDIO_DRIFT_US) {
            return decodedAudioUs;
        }
        String source = sourceAudioUs > 0 ? String.format(Locale.ROOT, "%.3f s", sourceAudioUs / 1e6) : "an unknown time";
        String decoded = decodedAudioUs > 0
                ? String.format(Locale.ROOT, " (decoded: %.3f s)", decodedAudioUs / 1e6) : "";
        throw new IngestFailure(String.format(Locale.ROOT,
                "Verify: the encoded audio ends at %.3f s, but the source's ends at %s%s (more than %d ms apart)",
                encoded / 1e6, source, decoded, MAX_AUDIO_DRIFT_US / 1000));
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
