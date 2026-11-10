package io.siggi.togethertunes.output;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A card held in memory: one closed 2 s GOP of {@value EncodeProfile#GOP_FRAMES} AVCC samples, the
 * first an IDR and the rest P-frames ({@code docs/library-and-ingest.md#cards}). Sources repeat it
 * for audio items, {@code presentAsAudio} video items and fillers. A card is 40–100 KB, so it is
 * kept in memory rather than read per repeat. Immutable; {@link #frame} hands out the shared array.
 */
public final class CardGop {
    private final byte[] avcC;
    private final byte[][] frames;

    private CardGop(byte[] avcC, byte[][] frames) {
        this.avcC = avcC;
        this.frames = frames;
    }

    /**
     * @param avcC   the card's avcC (must parse; identical to the profile's reference by invariant 1)
     * @param frames exactly {@value EncodeProfile#GOP_FRAMES} AVCC samples in decode order
     * @throws IOException if there aren't {@value EncodeProfile#GOP_FRAMES} frames, the avcC doesn't
     *                     parse, the first frame isn't an IDR or a later one is
     */
    public static CardGop of(byte[] avcC, List<byte[]> frames) throws IOException {
        AnnexB annexB = new AnnexB(avcC);
        if (frames.size() != EncodeProfile.GOP_FRAMES) {
            throw new IOException("a card has " + EncodeProfile.GOP_FRAMES + " frames, not " + frames.size());
        }
        byte[][] copy = new byte[frames.size()][];
        for (int i = 0; i < copy.length; i++) {
            byte[] f = frames.get(i).clone();
            if (f.length == 0 || annexB.isIdr(f, 0, f.length) != (i == 0)) {
                throw new IOException(i == 0 ? "a card's first frame must be an IDR"
                        : "card frame " + i + " is " + (f.length == 0 ? "empty" : "an IDR"));
            }
            copy[i] = f;
        }
        return new CardGop(avcC.clone(), copy);
    }

    /** The card in an index's video track (an audio item's {@code card.mp4}). */
    public static CardGop fromTrack(SampleIndex index, Track track, SampleData data) throws IOException {
        if (track == null || track.kind() != Kind.VIDEO) {
            throw new IOException("not a video track");
        }
        List<byte[]> frames = new ArrayList<>(track.sampleCount());
        for (int i = 0; i < track.sampleCount(); i++) {
            frames.add(data.read(track.fileRef(), track.offset(i), track.size(i)));
        }
        return of(index.avcC(), frames);
    }

    /**
     * Loads a card file that has no index (a filler card, a room card, a video item's
     * {@code card.mp4}): indexes it with ffprobe once and reads its frames. Meant for caching, not
     * for each play.
     */
    public static CardGop load(Tools tools, OutputProfile profile, Path card, JobLog log)
            throws IOException, ToolException, InterruptedException {
        SampleIndex index = IndexBuilder.build(tools, profile,
                List.of(new IndexBuilder.Source(card, Kind.VIDEO)), log);
        try (FileSampleData data = new FileSampleData(card.toAbsolutePath().getParent(), index)) {
            return fromTrack(index, index.track(Kind.VIDEO), data);
        }
    }

    /** A copy of the avcC. */
    public byte[] avcC() {
        return avcC.clone();
    }

    public int frameCount() {
        return frames.length;
    }

    /** Frame {@code i} (shared; never modify it). */
    public byte[] frame(int i) {
        return frames[i];
    }

    /** Total size of the frames in bytes. */
    public long size() {
        long n = 0;
        for (byte[] f : frames) {
            n += f.length;
        }
        return n;
    }

    /**
     * The card repeated forever as video: frame {@code n} is {@code frame(n % 60)} at
     * {@code n·3000} ticks, so every repeat starts with the IDR on a 2 s boundary. The unit builder
     * truncates the last GOP at the item's end.
     */
    public final class Repeat {
        private long n;

        public VideoSample next() {
            int i = (int) (n % frames.length);
            VideoSample s = new VideoSample(frames[i], n * ProgramSource.VIDEO_FRAME_TICKS, i == 0);
            n++;
            return s;
        }
    }

    public Repeat repeat() {
        return new Repeat();
    }
}
