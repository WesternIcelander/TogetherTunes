package io.siggi.togethertunes.output;

/**
 * One video access unit handed out by a {@link ProgramSource}: the AVCC sample (length-prefixed
 * NAL units, as stored in the MP4; the muxer converts it to Annex B), its PTS on the 90 kHz clock
 * <b>relative to the source's start</b> (the unit builder adds the item start {@code S_n}), and
 * whether it is an IDR.
 *
 * <p>The array is shared, not copied (a card's frames are handed out over and over): nobody may
 * modify it.
 *
 * @param data AVCC sample bytes (shared, never modified)
 * @param pts  90 kHz ticks since the source's start; strictly increasing within a source
 * @param idr  whether the sample starts a closed GOP
 */
public record VideoSample(byte[] data, long pts, boolean idr) {
}
