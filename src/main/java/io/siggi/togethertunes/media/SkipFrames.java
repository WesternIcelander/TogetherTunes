package io.siggi.togethertunes.media;

import java.io.ByteArrayOutputStream;

/**
 * Writes H.264 "nothing changed" frames: P slices in which every macroblock is skipped
 * ({@code P_Skip}), for the 59 frames after a card's IDR ({@code docs/library-and-ingest.md#cards}).
 * Nothing is encoded and no pixels are processed: a skipped macroblock with no moving neighbours
 * copies the reference picture, so each frame decodes to exactly the IDR's picture and costs a few
 * bytes instead of x264 refining the still for 2 s.
 *
 * <p>The slice header is built from our own SPS and PPS (the reference avcC's, invariant 1), not
 * hard-coded: {@code frame_num} counts up (each frame is a reference picture, sliding-window
 * marking), the picture order count follows {@code pic_order_cnt_type} (types 0 and 2, and 1 with
 * {@code delta_pic_order_always_zero_flag}), {@code num_ref_idx_active_override} sets one
 * reference, a {@code pred_weight_table} with no weights is written if the PPS enables weighted
 * prediction, deblocking is turned off for the slice where the PPS allows saying so, and
 * {@code slice_qp_delta} is 0. The slice data is {@code mb_skip_flag = 1} for every macroblock
 * through a CABAC encoder (H.264 §9.3.4) when the PPS selects CABAC, or one {@code mb_skip_run}
 * covering the picture with CAVLC. Interlaced (field or MBAFF) streams, slice groups and
 * separate colour planes are not supported ({@link UnsupportedStreamException}); the caller then
 * encodes the card the normal way.
 *
 * <p>Immutable once constructed; safe to share between threads.
 */
public final class SkipFrames {
    /** {@code nal_ref_idc} of the written frames: they are reference pictures. */
    static final int NAL_REF_IDC = 2;
    /** {@code cabac_init_idc} of the written slices (context table 0 of H.264 Table 9-13). */
    static final int CABAC_INIT_IDC = 0;

    /** The SPS/PPS use a feature the skip-frame writer doesn't handle, or don't parse. */
    public static final class UnsupportedStreamException extends Exception {
        public UnsupportedStreamException(String message) {
            super(message);
        }
    }

    private final Sps sps;
    private final Pps pps;

    /**
     * @param spsNal the SPS NAL unit (with its header byte, without a start code or length prefix)
     * @param ppsNal the PPS NAL unit, likewise
     * @throws UnsupportedStreamException if either doesn't parse or uses an unsupported feature
     */
    public SkipFrames(byte[] spsNal, byte[] ppsNal) throws UnsupportedStreamException {
        try {
            if (spsNal.length < 2 || (spsNal[0] & 0x1F) != AnnexB.NAL_SPS) {
                throw new UnsupportedStreamException("not an SPS NAL unit");
            }
            if (ppsNal.length < 2 || (ppsNal[0] & 0x1F) != AnnexB.NAL_PPS) {
                throw new UnsupportedStreamException("not a PPS NAL unit");
            }
            this.sps = Sps.parse(new BitReader(unescape(spsNal, 1)));
            this.pps = Pps.parse(new BitReader(unescape(ppsNal, 1)));
        } catch (IndexOutOfBoundsException e) {
            throw new UnsupportedStreamException("truncated parameter set");
        }
        if (pps.spsId != sps.id) {
            throw new UnsupportedStreamException("the PPS refers to SPS " + pps.spsId + ", not " + sps.id);
        }
    }

    /** Macroblocks per picture. */
    public int macroblocks() {
        return sps.picSizeInMbs;
    }

    /** Whether the slices are CABAC coded (the PPS's {@code entropy_coding_mode_flag}). */
    public boolean cabac() {
        return pps.cabac;
    }

    /**
     * The NAL unit (header byte, slice header and data, emulation prevention applied; no start code
     * or length prefix) of frame {@code index} of a GOP whose frame 0 is the IDR.
     *
     * @param index 1 or more: the frame's position after the IDR, in decode and display order
     */
    public byte[] frame(int index) {
        if (index < 1) {
            throw new IllegalArgumentException("frame " + index + " (frame 0 is the IDR)");
        }
        BitWriter w = new BitWriter();
        w.bits(NAL_REF_IDC << 5 | AnnexB.NAL_SLICE, 8);
        // slice_header()
        w.ue(0); // first_mb_in_slice
        w.ue(5); // slice_type: P, and every slice of the picture is P
        w.ue(pps.id);
        w.bits(index & ((1 << sps.log2MaxFrameNum) - 1), sps.log2MaxFrameNum); // frame_num
        // frame_mbs_only_flag is 1 (checked), so no field_pic_flag; not an IDR, so no idr_pic_id
        if (sps.pocType == 0) {
            w.bits((2 * index) & ((1 << sps.log2MaxPocLsb) - 1), sps.log2MaxPocLsb); // pic_order_cnt_lsb
            if (pps.bottomFieldPicOrderInFramePresent) {
                w.se(0); // delta_pic_order_cnt_bottom
            }
        }
        // pocType 1 is accepted only with delta_pic_order_always_zero_flag: nothing to write
        if (pps.redundantPicCntPresent) {
            w.ue(0); // redundant_pic_cnt
        }
        w.bit(1); // num_ref_idx_active_override_flag
        w.ue(0); // num_ref_idx_l0_active_minus1: one reference, the previous frame
        w.bit(0); // ref_pic_list_modification_flag_l0
        if (pps.weightedPred) {
            // pred_weight_table(): default weights
            w.ue(0); // luma_log2_weight_denom
            if (sps.chromaArrayType != 0) {
                w.ue(0); // chroma_log2_weight_denom
            }
            w.bit(0); // luma_weight_l0_flag[0]
            if (sps.chromaArrayType != 0) {
                w.bit(0); // chroma_weight_l0_flag[0]
            }
        }
        // dec_ref_pic_marking() (nal_ref_idc != 0, not IDR)
        w.bit(0); // adaptive_ref_pic_marking_mode_flag: sliding window
        if (pps.cabac) {
            w.ue(CABAC_INIT_IDC);
        }
        w.se(0); // slice_qp_delta
        if (pps.deblockingFilterControlPresent) {
            w.ue(1); // disable_deblocking_filter_idc: off (nothing to filter anyway)
        }
        // slice_data()
        if (pps.cabac) {
            while (!w.aligned()) {
                w.bit(1); // cabac_alignment_one_bit
            }
            int sliceQp = Math.max(0, Math.min(51, 26 + pps.picInitQpMinus26));
            Cabac c = new Cabac(w, SKIP_FLAG_M, SKIP_FLAG_N, sliceQp);
            for (int mb = 0; mb < sps.picSizeInMbs; mb++) {
                // mb_skip_flag: neither neighbour is a non-skipped macroblock, so ctxIdxInc is 0
                c.decision(1);
                c.terminate(mb == sps.picSizeInMbs - 1 ? 1 : 0); // end_of_slice_flag
            }
            // the flush wrote the rbsp_stop_one_bit
        } else {
            w.ue(sps.picSizeInMbs); // mb_skip_run: the whole picture
            w.bit(1); // rbsp_stop_one_bit
        }
        while (!w.aligned()) {
            w.bit(0); // rbsp_alignment_zero_bit
        }
        return escape(w.toByteArray());
    }

    // ------------------------------------------------------------------ parameter sets

    private record Sps(int id, int chromaArrayType, int log2MaxFrameNum, int pocType, int log2MaxPocLsb,
                       int picSizeInMbs) {
        static Sps parse(BitReader r) throws UnsupportedStreamException {
            int profileIdc = r.bits(8);
            r.bits(8); // constraint flags, reserved bits
            r.bits(8); // level_idc
            int id = r.ue();
            int chromaFormatIdc = 1;
            boolean separateColourPlane = false;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44
                    || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128
                    || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
                chromaFormatIdc = r.ue();
                if (chromaFormatIdc == 3) {
                    separateColourPlane = r.bit() == 1;
                }
                r.ue(); // bit_depth_luma_minus8
                r.ue(); // bit_depth_chroma_minus8
                r.bit(); // qpprime_y_zero_transform_bypass_flag
                if (r.bit() == 1) { // seq_scaling_matrix_present_flag
                    int lists = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < lists; i++) {
                        if (r.bit() == 1) {
                            skipScalingList(r, i < 6 ? 16 : 64);
                        }
                    }
                }
            }
            if (separateColourPlane) {
                throw new UnsupportedStreamException("separate colour planes");
            }
            int log2MaxFrameNum = r.ue() + 4;
            int pocType = r.ue();
            int log2MaxPocLsb = 0;
            if (pocType == 0) {
                log2MaxPocLsb = r.ue() + 4;
            } else if (pocType == 1) {
                if (r.bit() == 0) {
                    throw new UnsupportedStreamException("pic_order_cnt_type 1 without delta_pic_order_always_zero_flag");
                }
                r.se(); // offset_for_non_ref_pic
                r.se(); // offset_for_top_to_bottom_field
                int cycle = r.ue();
                for (int i = 0; i < cycle; i++) {
                    r.se();
                }
            } else if (pocType != 2) {
                throw new UnsupportedStreamException("pic_order_cnt_type " + pocType);
            }
            int maxRefFrames = r.ue();
            if (maxRefFrames < 1) {
                throw new UnsupportedStreamException("max_num_ref_frames 0");
            }
            r.bit(); // gaps_in_frame_num_value_allowed_flag
            int widthMbs = r.ue() + 1;
            int heightMapUnits = r.ue() + 1;
            if (r.bit() == 0) {
                throw new UnsupportedStreamException("interlaced (frame_mbs_only_flag 0)");
            }
            if (log2MaxFrameNum > 16 || log2MaxPocLsb > 16) {
                throw new UnsupportedStreamException("bad log2_max_frame_num or log2_max_pic_order_cnt_lsb");
            }
            return new Sps(id, chromaFormatIdc, log2MaxFrameNum, pocType, log2MaxPocLsb, widthMbs * heightMapUnits);
        }

        private static void skipScalingList(BitReader r, int size) {
            int last = 8;
            int next = 8;
            for (int j = 0; j < size; j++) {
                if (next != 0) {
                    next = (last + r.se() + 256) % 256;
                }
                last = next == 0 ? last : next;
            }
        }
    }

    private record Pps(int id, int spsId, boolean cabac, boolean bottomFieldPicOrderInFramePresent,
                       boolean weightedPred, int picInitQpMinus26, boolean deblockingFilterControlPresent,
                       boolean redundantPicCntPresent) {
        static Pps parse(BitReader r) throws UnsupportedStreamException {
            int id = r.ue();
            int spsId = r.ue();
            boolean cabac = r.bit() == 1;
            boolean bottomField = r.bit() == 1;
            if (r.ue() != 0) {
                throw new UnsupportedStreamException("slice groups");
            }
            r.ue(); // num_ref_idx_l0_default_active_minus1 (overridden per slice)
            r.ue(); // num_ref_idx_l1_default_active_minus1
            boolean weightedPred = r.bit() == 1;
            r.bits(2); // weighted_bipred_idc
            int picInitQpMinus26 = r.se();
            r.se(); // pic_init_qs_minus26
            r.se(); // chroma_qp_index_offset
            boolean deblocking = r.bit() == 1;
            r.bit(); // constrained_intra_pred_flag
            boolean redundant = r.bit() == 1;
            return new Pps(id, spsId, cabac, bottomField, weightedPred, picInitQpMinus26, deblocking, redundant);
        }
    }

    // ------------------------------------------------------------------ RBSP <-> NAL payload

    /** The RBSP of a NAL unit from byte {@code from} on, emulation prevention bytes removed. */
    static byte[] unescape(byte[] nal, int from) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = from; i < nal.length; i++) {
            int b = nal[i] & 0xFF;
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * A NAL unit from its header byte and RBSP: an emulation prevention byte {@code 03} after every
     * two zero bytes that are followed by a byte of 0 to 3 (H.264 §7.4.1).
     */
    static byte[] escape(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 16);
        out.write(rbsp[0]); // the NAL header byte
        int zeros = 0;
        for (int i = 1; i < rbsp.length; i++) {
            int b = rbsp[i] & 0xFF;
            if (zeros == 2 && b <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /** Reads bits, Exp-Golomb codes MSB first from an RBSP. */
    static final class BitReader {
        private final byte[] data;
        private int pos;

        BitReader(byte[] data) {
            this.data = data;
        }

        int bit() {
            int b = (data[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return b;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                zeros++;
                if (zeros > 31) {
                    throw new IndexOutOfBoundsException("bad Exp-Golomb code");
                }
            }
            return (int) ((1L << zeros) - 1 + bits(zeros));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    /** Writes bits and Exp-Golomb codes MSB first. */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur;
        private int n;

        void bit(int b) {
            cur = (cur << 1) | (b & 1);
            if (++n == 8) {
                out.write(cur);
                cur = 0;
                n = 0;
            }
        }

        void bits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                bit(value >>> i);
            }
        }

        void ue(int value) {
            long v = value + 1L;
            int len = 64 - Long.numberOfLeadingZeros(v);
            for (int i = 1; i < len; i++) {
                bit(0);
            }
            for (int i = len - 1; i >= 0; i--) {
                bit((int) (v >>> i));
            }
        }

        void se(int value) {
            ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        boolean aligned() {
            return n == 0;
        }

        byte[] toByteArray() {
            if (n != 0) {
                throw new IllegalStateException("not byte aligned");
            }
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ CABAC (H.264 §9.3)

    /** m and n of ctxIdx 11 ({@code mb_skip_flag} of a P slice, ctxIdxInc 0) for cabac_init_idc 0. */
    private static final int SKIP_FLAG_M = 23;
    private static final int SKIP_FLAG_N = 33;

    /** rangeTabLPS[pStateIdx][qCodIRangeIdx], H.264 Table 9-44. */
    private static final int[][] RANGE_TAB_LPS = {
            {128, 176, 208, 240}, {128, 167, 197, 227}, {128, 158, 187, 216}, {123, 150, 178, 205},
            {116, 142, 169, 195}, {111, 135, 160, 185}, {105, 128, 152, 175}, {100, 122, 144, 166},
            {95, 116, 137, 158}, {90, 110, 130, 150}, {85, 104, 123, 142}, {81, 99, 117, 135},
            {77, 94, 111, 128}, {73, 89, 105, 122}, {69, 85, 100, 116}, {66, 80, 95, 110},
            {62, 76, 90, 104}, {59, 72, 86, 99}, {56, 69, 81, 94}, {53, 65, 77, 89},
            {51, 62, 73, 85}, {48, 59, 69, 80}, {46, 56, 66, 76}, {43, 53, 63, 72},
            {41, 50, 59, 69}, {39, 48, 56, 65}, {37, 45, 54, 62}, {35, 43, 51, 59},
            {33, 41, 48, 56}, {32, 39, 46, 53}, {30, 37, 43, 50}, {29, 35, 41, 48},
            {27, 33, 39, 45}, {26, 31, 37, 43}, {24, 30, 35, 41}, {23, 28, 33, 39},
            {22, 27, 32, 37}, {21, 26, 30, 35}, {20, 24, 29, 33}, {19, 23, 27, 31},
            {18, 22, 26, 30}, {17, 21, 25, 28}, {16, 20, 23, 27}, {15, 19, 22, 25},
            {14, 18, 21, 24}, {14, 17, 20, 23}, {13, 16, 19, 22}, {12, 15, 18, 21},
            {12, 14, 17, 20}, {11, 14, 16, 19}, {11, 13, 15, 18}, {10, 12, 15, 17},
            {10, 12, 14, 16}, {9, 11, 13, 15}, {9, 11, 12, 14}, {8, 10, 12, 14},
            {8, 9, 11, 13}, {7, 9, 11, 12}, {7, 9, 10, 12}, {7, 8, 10, 11},
            {6, 8, 9, 11}, {6, 7, 9, 10}, {6, 7, 8, 9}, {2, 2, 2, 2}};

    /** transIdxLPS[pStateIdx], H.264 Table 9-45 (transIdxMPS is min(pStateIdx + 1, 62)). */
    private static final int[] TRANS_IDX_LPS = {
            0, 0, 1, 2, 2, 4, 4, 5, 6, 7, 8, 9, 9, 11, 11, 12,
            13, 13, 15, 15, 16, 16, 18, 18, 19, 19, 21, 21, 22, 22, 23, 24,
            24, 25, 26, 26, 27, 27, 28, 29, 29, 30, 30, 30, 31, 32, 32, 33,
            33, 33, 34, 34, 35, 35, 35, 36, 36, 36, 37, 37, 37, 38, 38, 63};

    /**
     * The arithmetic encoder of H.264 §9.3.4.2 with one context variable (the slice data only ever
     * codes {@code mb_skip_flag} in one context, plus {@code end_of_slice_flag}, which uses none).
     */
    static final class Cabac {
        private final BitWriter w;
        private int low;
        private int range = 510;
        private boolean firstBit = true;
        private int outstanding;
        private int state;
        private int mps;

        /** Initialises the context from m, n and the slice QP (§9.3.1.1). */
        Cabac(BitWriter w, int m, int n, int sliceQp) {
            this.w = w;
            int pre = Math.max(1, Math.min(126, ((m * sliceQp) >> 4) + n));
            if (pre <= 63) {
                state = 63 - pre;
                mps = 0;
            } else {
                state = pre - 64;
                mps = 1;
            }
        }

        /** EncodeDecision (§9.3.4.2). */
        void decision(int bin) {
            int lps = RANGE_TAB_LPS[state][(range >> 6) & 3];
            range -= lps;
            if (bin != mps) {
                low += range;
                range = lps;
                if (state == 0) {
                    mps = 1 - mps;
                }
                state = TRANS_IDX_LPS[state];
            } else {
                state = Math.min(state + 1, 62);
            }
            renorm();
        }

        /** EncodeTerminate (§9.3.4.5), with the flush after a 1. */
        void terminate(int bin) {
            range -= 2;
            if (bin != 0) {
                low += range;
                // EncodeFlush
                range = 2;
                renorm();
                putBit((low >> 9) & 1);
                int last = ((low >> 7) & 3) | 1;
                w.bit(last >> 1);
                w.bit(last);
            } else {
                renorm();
            }
        }

        private void renorm() {
            while (range < 256) {
                if (low < 256) {
                    putBit(0);
                } else if (low >= 512) {
                    low -= 512;
                    putBit(1);
                } else {
                    low -= 256;
                    outstanding++;
                }
                range <<= 1;
                low <<= 1;
            }
        }

        private void putBit(int b) {
            if (firstBit) {
                firstBit = false;
            } else {
                w.bit(b);
            }
            while (outstanding > 0) {
                w.bit(1 - b);
                outstanding--;
            }
        }
    }
}
