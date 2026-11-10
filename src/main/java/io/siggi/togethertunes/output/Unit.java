package io.siggi.togethertunes.output;

import java.util.List;

/**
 * About 2 s of program output, starting with an IDR frame ({@code docs/program-output.md#units}).
 * Built by {@link UnitBuilder}; the MPEG-TS muxer turns it into bytes.
 *
 * <p>All timestamps are on the 90 kHz output clock, unwrapped ({@link OutputClock}); use
 * {@link Video#pts33()} and friends for what goes on the wire. There are no B-frames, so every
 * sample's DTS equals its PTS.
 *
 * @param startPts    where the unit starts: its first video frame (an IDR) is exactly here
 * @param boundaryPts where the next unit starts if this source keeps playing: the source's next
 *                    IDR (normally {@code startPts + 180000}). Every audio frame here has
 *                    {@code pts < boundaryPts}, every video frame {@code pts < boundaryPts}.
 * @param audioEndPts where this unit's audio ends: the last audio frame's PTS + 1920, or
 *                    {@code startPts} if it has none. The last frame usually straddles
 *                    {@code boundaryPts}, so this is in {@code [boundaryPts, boundaryPts + 1920)}
 *                    unless the source ended. When the builder switches sources after this unit,
 *                    the next unit starts here (audio drives the boundary: {@code S_{n+1} = E_n}),
 *                    so this unit then lasts up to one audio frame (21.3 ms) longer than 2 s.
 * @param video       1..60 frames, the first an IDR at {@code startPts}, PTS strictly increasing
 * @param audio       AAC frames exactly 1920 ticks apart, PTS in {@code [startPts, boundaryPts)}
 * @param source      the source the unit came from (for status and logs; don't pull from it)
 * @param sourceEnded true if the source ended in this unit: then
 *                    {@code boundaryPts == audioEndPts == E_n} (which may lie up to 3000 + 1919
 *                    ticks past the 2 s mark, when the source's audio ended less than a frame
 *                    interval after its next IDR), and the next unit needs a new source. No video
 *                    frame is then less than 3000 ticks before {@code E_n}, apart from the IDR
 */
public record Unit(long startPts, long boundaryPts, long audioEndPts, List<Video> video, List<Audio> audio,
        ProgramSource source, boolean sourceEnded) {
    public Unit {
        video = List.copyOf(video);
        audio = List.copyOf(audio);
    }

    /**
     * A video access unit.
     *
     * @param data AVCC sample bytes (shared, never modified)
     * @param pts  output clock, unwrapped
     */
    public record Video(byte[] data, long pts, boolean idr) {
        /** DTS = PTS: there are no B-frames. */
        public long dts() {
            return pts;
        }

        public long pts33() {
            return OutputClock.wrap(pts);
        }

        public long dts33() {
            return OutputClock.wrap(pts);
        }
    }

    /**
     * A raw AAC frame (no ADTS header).
     *
     * @param data shared, never modified
     * @param pts  output clock, unwrapped
     */
    public record Audio(byte[] data, long pts) {
        /** DTS = PTS. */
        public long dts() {
            return pts;
        }

        public long pts33() {
            return OutputClock.wrap(pts);
        }

        public long dts33() {
            return OutputClock.wrap(pts);
        }
    }

    /** The unit's span if its source continues, in ticks ({@code boundaryPts − startPts}). */
    public long nominalDuration() {
        return boundaryPts - startPts;
    }
}
