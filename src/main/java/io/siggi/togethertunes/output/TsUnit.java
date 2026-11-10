package io.siggi.togethertunes.output;

/**
 * One muxed unit as handed to the consumers ({@code .ts} clients, HLS segmenter, RTMP pusher).
 *
 * @param sequence    0, 1, 2, … for the packager's lifetime (HLS media sequence numbers)
 * @param startPts    where the unit starts (unwrapped output clock); the next unit starts at its
 *                    {@code boundaryPts} or, after a source switch, its {@code audioEndPts}
 * @param boundaryPts see {@link Unit#boundaryPts()}
 * @param audioEndPts see {@link Unit#audioEndPts()}
 * @param ts          the unit's MPEG-TS bytes, shared by every consumer: never modify them
 */
public record TsUnit(long sequence, long startPts, long boundaryPts, long audioEndPts, byte[] ts) {
}
