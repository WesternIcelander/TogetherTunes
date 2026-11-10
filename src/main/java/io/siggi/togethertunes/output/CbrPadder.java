package io.siggi.togethertunes.output;

import java.util.ArrayList;
import java.util.List;

/**
 * Pads a program output's video to a constant rate with H.264 filler data NAL units (type 12)
 * while it is sent ({@code docs/program-output.md#constant-rate-padding}). The encodes on disk are
 * only capped at the encode rate {@code R} (no filler, no HRD in the SPS); this brings the stream
 * up to exactly the target {@code T}.
 *
 * <p>A VBV-style model per output: after each access unit, the bytes sent so far are compared with
 * {@code T × (end of that frame − first frame) / 8}, where a frame ends at the next frame's PTS (or
 * the unit's {@link Unit#boundaryPts() boundary} for its last frame). When the output is behind,
 * a filler NAL unit of the missing size is appended to the access unit, after its slices (valid
 * Annex B order; the muxer copies it like any other NAL unit, and {@code -c copy} to FLV keeps
 * it). A frame bigger than its share (an IDR) puts the output ahead, which the following frames
 * pay back by getting less padding. Since every encode obeys the cap {@code R} with a VBV buffer,
 * the output never runs ahead of {@code T ≥ R} by more than that buffer; below {@code R} (only
 * sensible for card-only output, as in audio-only rooms) it runs ahead whenever the encode does,
 * and is padded again once the cards bring it back behind.
 *
 * <p>Sizes count the AVCC sample bytes (length prefixes included). The muxer's Annex B form differs
 * by a few bytes per frame (an AUD, SPS and PPS before IDRs), the same every GOP, so the transport
 * stream is constant-rate as well. This adds padding only: nothing is decoded or encoded.
 *
 * <p>Not thread-safe: one per packager, used on the packager thread.
 */
public final class CbrPadder {
    /** H.264 filler data: {@code forbidden_zero_bit 0}, {@code nal_ref_idc 0}, type 12. */
    static final byte FILLER_HEADER = 0x0C;
    /** Filler payload byte (0xFF, which never forms a start code or needs emulation prevention). */
    static final byte FILLER_BYTE = (byte) 0xFF;
    /** The RBSP trailing bits ending a filler NAL unit. */
    static final byte TRAILING = (byte) 0x80;

    private final int lengthSize;
    private long bitrateBps;
    private final int minFiller;
    private final int maxNal;

    private boolean started;
    /** Where the current rate applies from, and the bytes due up to there. */
    private long firstPts;
    private long baseBytes;
    private long sentBytes;

    /**
     * @param nalLengthSize the avcC's NAL length prefix size (1, 2 or 4)
     * @param bitrateBps    the target video rate {@code T} in bits per second
     */
    public CbrPadder(int nalLengthSize, long bitrateBps) {
        if (nalLengthSize != 1 && nalLengthSize != 2 && nalLengthSize != 4) {
            throw new IllegalArgumentException("NAL length size " + nalLengthSize);
        }
        if (bitrateBps <= 0) {
            throw new IllegalArgumentException("bitrate " + bitrateBps);
        }
        this.lengthSize = nalLengthSize;
        this.bitrateBps = bitrateBps;
        this.minFiller = nalLengthSize + 2;
        this.maxNal = nalLengthSize == 4 ? Integer.MAX_VALUE - 16 : (1 << (8 * nalLengthSize)) - 1;
    }

    /** The target rate in bits per second. */
    public long bitrateBps() {
        return bitrateBps;
    }

    /**
     * Changes the target rate from {@code atPts} on (the start of the next unit to pad), e.g. when
     * an audio-only room's output reaches an item that plays in the other mode. The bytes due so
     * far stay due; from {@code atPts} they grow at the new rate, so an output ahead or behind at
     * the change pays that back at the new rate.
     */
    public void bitrateBps(long newBitrateBps, long atPts) {
        if (newBitrateBps <= 0) {
            throw new IllegalArgumentException("bitrate " + newBitrateBps);
        }
        if (newBitrateBps == bitrateBps) {
            return;
        }
        if (started) {
            baseBytes = budget(atPts);
            firstPts = atPts;
        }
        bitrateBps = newBitrateBps;
    }

    /** Video bytes (AVCC, padding included) sent so far. */
    public long sentBytes() {
        return sentBytes;
    }

    /**
     * {@code unit} with filler appended to its access units as needed (the same unit if none
     * needed padding). Units must come in output order.
     */
    public Unit pad(Unit unit) {
        List<Unit.Video> in = unit.video();
        if (in.isEmpty()) {
            return unit;
        }
        if (!started) {
            started = true;
            firstPts = in.get(0).pts();
        }
        List<Unit.Video> out = null;
        for (int i = 0; i < in.size(); i++) {
            Unit.Video v = in.get(i);
            long end = i + 1 < in.size() ? in.get(i + 1).pts() : Math.max(unit.boundaryPts(), v.pts());
            sentBytes += v.data().length;
            long deficit = budget(end) - sentBytes;
            Unit.Video padded = v;
            if (deficit >= minFiller) {
                padded = new Unit.Video(withFiller(v.data(), deficit), v.pts(), v.idr());
                sentBytes += deficit;
            }
            if (padded != v && out == null) {
                out = new ArrayList<>(in.subList(0, i));
            }
            if (out != null) {
                out.add(padded);
            }
        }
        if (out == null) {
            return unit;
        }
        return new Unit(unit.startPts(), unit.boundaryPts(), unit.audioEndPts(), out, unit.audio(),
                unit.source(), unit.sourceEnded());
    }

    /**
     * Bytes due from the first frame up to {@code pts}: {@code T × ticks / (8 × 90000)}, rounded
     * down (plus what was due before the last rate change).
     */
    long budget(long pts) {
        long ticks = pts - firstPts;
        long seconds = Math.floorDiv(ticks, OutputClock.HZ);
        long rest = Math.floorMod(ticks, OutputClock.HZ);
        // whole seconds and the rest apart, so this doesn't overflow in any realistic uptime
        long bits = seconds * bitrateBps + bitrateBps * rest / OutputClock.HZ;
        return baseBytes + Math.floorDiv(bits, 8);
    }

    /**
     * {@code sample} followed by filler NAL units of exactly {@code bytes} bytes in all (length
     * prefixes included; split into several units where the prefix size can't hold one).
     */
    private byte[] withFiller(byte[] sample, long bytes) {
        if (bytes > Integer.MAX_VALUE - 64 - sample.length) {
            throw new IllegalStateException("filler of " + bytes + " bytes");
        }
        byte[] out = new byte[sample.length + (int) bytes];
        System.arraycopy(sample, 0, out, 0, sample.length);
        int pos = sample.length;
        long left = bytes;
        while (left > 0) {
            long nal = Math.min(left - lengthSize, maxNal);
            // never leave a remainder too small for a NAL unit of its own
            long rest = left - lengthSize - nal;
            if (rest > 0 && rest < minFiller) {
                nal -= minFiller - rest;
            }
            for (int k = lengthSize - 1; k >= 0; k--) {
                out[pos++] = (byte) (nal >>> (8 * k));
            }
            out[pos++] = FILLER_HEADER;
            for (long k = 0; k < nal - 2; k++) {
                out[pos++] = FILLER_BYTE;
            }
            out[pos++] = TRAILING;
            left -= lengthSize + nal;
        }
        return out;
    }
}
