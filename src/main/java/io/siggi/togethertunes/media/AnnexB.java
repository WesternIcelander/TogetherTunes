package io.siggi.togethertunes.media;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts H.264 samples as stored in MP4 (AVCC: NAL units with a length prefix) into Annex B
 * access units for the MPEG-TS packager (see {@code docs/program-output.md#mpeg-ts-muxer}).
 *
 * <p>Each access unit becomes: an access unit delimiter ({@code 00 00 00 01 09 F0}), then, if the
 * access unit holds an IDR slice, the SPS and PPS from the avcC, then the sample's own NAL units,
 * each behind a 4-byte start code. AUD, SPS and PPS NAL units inside the sample are dropped: the
 * packager writes its own AUD, and the parameter sets come from the avcC, which is the same for
 * every encode of a profile (invariant 1). Everything else (SEI, slices) is copied unchanged.
 *
 * <p>An instance holds only the parsed avcC, so it is immutable and safe to share between threads.
 */
public final class AnnexB {
    /** The 4-byte Annex B start code used before every NAL unit. */
    private static final byte[] START_CODE = {0, 0, 0, 1};
    /**
     * Access unit delimiter with its start code: NAL type 9, {@code primary_pic_type} 7 (any slice
     * type), then the RBSP stop bit.
     */
    private static final byte[] AUD = {0, 0, 0, 1, 0x09, (byte) 0xF0};

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;
    public static final int NAL_AUD = 9;

    /** Malformed avcC or sample data. */
    public static final class H264FormatException extends IOException {
        public H264FormatException(String message) {
            super(message);
        }
    }

    private final int nalLengthSize;
    private final List<byte[]> sps;
    private final List<byte[]> pps;
    /** Start code + SPS, start code + PPS, ready to copy before an IDR. */
    private final byte[] parameterSets;

    /**
     * Parses an AVCDecoderConfigurationRecord (the payload of the {@code avcC} box, as read by
     * {@link Mp4Boxes#readAvcC} or stored in {@link SampleIndex#avcC()}).
     *
     * @throws H264FormatException if it is truncated, has an unknown version or length size, or
     *                             holds no SPS or no PPS
     */
    public AnnexB(byte[] avcC) throws H264FormatException {
        if (avcC.length < 7) {
            throw new H264FormatException("avcC too short: " + avcC.length + " bytes");
        }
        if (avcC[0] != 1) {
            throw new H264FormatException("unknown avcC configurationVersion " + (avcC[0] & 0xFF));
        }
        int lengthSize = (avcC[4] & 0x03) + 1;
        if (lengthSize == 3) {
            throw new H264FormatException("invalid NAL length size 3");
        }
        this.nalLengthSize = lengthSize;
        int pos = 5;
        int numSps = avcC[pos++] & 0x1F;
        List<byte[]> s = new ArrayList<>();
        pos = readParameterSets(avcC, pos, numSps, NAL_SPS, s);
        if (pos >= avcC.length) {
            throw new H264FormatException("avcC truncated before numOfPictureParameterSets");
        }
        int numPps = avcC[pos++] & 0xFF;
        List<byte[]> p = new ArrayList<>();
        readParameterSets(avcC, pos, numPps, NAL_PPS, p);
        // anything after the PPS (the High profile chroma/bit depth extension) isn't needed
        if (s.isEmpty()) {
            throw new H264FormatException("avcC has no SPS");
        }
        if (p.isEmpty()) {
            throw new H264FormatException("avcC has no PPS");
        }
        this.sps = List.copyOf(s);
        this.pps = List.copyOf(p);
        int size = 0;
        for (byte[] nal : s) {
            size += START_CODE.length + nal.length;
        }
        for (byte[] nal : p) {
            size += START_CODE.length + nal.length;
        }
        byte[] ps = new byte[size];
        int o = 0;
        for (List<byte[]> list : List.of(s, p)) {
            for (byte[] nal : list) {
                System.arraycopy(START_CODE, 0, ps, o, START_CODE.length);
                o += START_CODE.length;
                System.arraycopy(nal, 0, ps, o, nal.length);
                o += nal.length;
            }
        }
        this.parameterSets = ps;
    }

    private static int readParameterSets(byte[] avcC, int pos, int count, int type, List<byte[]> out)
            throws H264FormatException {
        String name = type == NAL_SPS ? "SPS" : "PPS";
        for (int i = 0; i < count; i++) {
            if (avcC.length - pos < 2) {
                throw new H264FormatException("avcC truncated at " + name + " " + i);
            }
            int len = ((avcC[pos] & 0xFF) << 8) | (avcC[pos + 1] & 0xFF);
            pos += 2;
            if (len == 0 || avcC.length - pos < len) {
                throw new H264FormatException("avcC " + name + " " + i + " has a bad length " + len);
            }
            if ((avcC[pos] & 0x1F) != type) {
                throw new H264FormatException("avcC " + name + " " + i + " has NAL type " + (avcC[pos] & 0x1F));
            }
            byte[] nal = new byte[len];
            System.arraycopy(avcC, pos, nal, 0, len);
            out.add(nal);
            pos += len;
        }
        return pos;
    }

    /** Bytes in each NAL unit's length prefix ({@code lengthSizeMinusOne + 1}): 1, 2 or 4. */
    public int nalLengthSize() {
        return nalLengthSize;
    }

    /** Copies of the SPS NAL units (without start codes). */
    public List<byte[]> sps() {
        return sps.stream().map(byte[]::clone).toList();
    }

    /** Copies of the PPS NAL units (without start codes). */
    public List<byte[]> pps() {
        return pps.stream().map(byte[]::clone).toList();
    }

    /** {@link #accessUnit(byte[], int, int)} on a whole array. */
    public byte[] accessUnit(byte[] sample) throws H264FormatException {
        return accessUnit(sample, 0, sample.length);
    }

    /**
     * Converts one AVCC sample (one access unit) to Annex B: AUD, SPS and PPS if the sample holds
     * an IDR slice, then its NAL units with start codes (in-band AUD/SPS/PPS dropped).
     *
     * @throws H264FormatException if a length prefix is zero or runs past the sample, or the
     *                             sample holds no slice
     */
    public byte[] accessUnit(byte[] sample, int off, int len) throws H264FormatException {
        // pass 1: validate, size the output and find out whether this is an IDR
        int end = off + len;
        int size = AUD.length;
        boolean idr = false;
        boolean slice = false;
        int pos = off;
        while (pos < end) {
            int nalLen = nalLength(sample, pos, end);
            pos += nalLengthSize;
            int type = sample[pos] & 0x1F;
            if (!dropped(type)) {
                size += START_CODE.length + nalLen;
            }
            if (type == NAL_IDR) {
                idr = true;
            }
            if (type >= NAL_SLICE && type <= NAL_IDR) {
                slice = true;
            }
            pos += nalLen;
        }
        if (!slice) {
            throw new H264FormatException("sample has no slice NAL unit");
        }
        if (idr) {
            size += parameterSets.length;
        }
        // pass 2: write
        byte[] out = new byte[size];
        System.arraycopy(AUD, 0, out, 0, AUD.length);
        int o = AUD.length;
        if (idr) {
            System.arraycopy(parameterSets, 0, out, o, parameterSets.length);
            o += parameterSets.length;
        }
        pos = off;
        while (pos < end) {
            int nalLen = nalLength(sample, pos, end);
            pos += nalLengthSize;
            if (!dropped(sample[pos] & 0x1F)) {
                System.arraycopy(START_CODE, 0, out, o, START_CODE.length);
                o += START_CODE.length;
                System.arraycopy(sample, pos, out, o, nalLen);
                o += nalLen;
            }
            pos += nalLen;
        }
        return out;
    }

    /**
     * Whether an AVCC sample holds an IDR slice (NAL type 5).
     *
     * @throws H264FormatException if a length prefix is zero or runs past the sample
     */
    public boolean isIdr(byte[] sample, int off, int len) throws H264FormatException {
        int end = off + len;
        int pos = off;
        while (pos < end) {
            int nalLen = nalLength(sample, pos, end);
            pos += nalLengthSize;
            if ((sample[pos] & 0x1F) == NAL_IDR) {
                return true;
            }
            pos += nalLen;
        }
        return false;
    }

    private static boolean dropped(int type) {
        return type == NAL_AUD || type == NAL_SPS || type == NAL_PPS;
    }

    /** Reads the length prefix at {@code pos} and checks the NAL unit fits before {@code end}. */
    private int nalLength(byte[] b, int pos, int end) throws H264FormatException {
        if (end - pos < nalLengthSize) {
            throw new H264FormatException("truncated NAL length prefix");
        }
        long n = 0;
        for (int i = 0; i < nalLengthSize; i++) {
            n = (n << 8) | (b[pos + i] & 0xFF);
        }
        if (n == 0) {
            throw new H264FormatException("zero-length NAL unit");
        }
        if (n > end - pos - nalLengthSize) {
            throw new H264FormatException("NAL unit of " + n + " bytes runs past the sample");
        }
        return (int) n;
    }
}
