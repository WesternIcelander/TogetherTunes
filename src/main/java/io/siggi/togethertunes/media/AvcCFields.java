package io.siggi.togethertunes.media;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decodes an avcC record (AVCDecoderConfigurationRecord) into named fields, the SPS in full
 * (including the VUI: colour description, chroma location, timing, bitstream restrictions), so a
 * mismatch with the reference avcC can be reported as the fields that differ instead of two hex
 * strings ({@code docs/library-and-ingest.md#the-mezzanine-profile}). Diagnostics only: nothing
 * depends on the decoded values.
 */
public final class AvcCFields {
    private AvcCFields() {
    }

    /**
     * The record's fields in bitstream order, as text. The SPS fields carry their H.264 syntax
     * element names; the colour description values get their meaning in brackets
     * ({@code colour_primaries=1 (BT.709)}). The PPS and anything after it are given as hex. If the
     * record can't be decoded completely, the fields read so far are returned plus {@code error}.
     */
    public static Map<String, String> decode(byte[] avcC) {
        Map<String, String> f = new LinkedHashMap<>();
        try {
            if (avcC == null || avcC.length < 7) {
                f.put("error", "not an avcC record");
                return f;
            }
            f.put("configurationVersion", Integer.toString(avcC[0] & 0xff));
            f.put("AVCProfileIndication", Integer.toString(avcC[1] & 0xff));
            f.put("profile_compatibility", Integer.toString(avcC[2] & 0xff));
            f.put("AVCLevelIndication", Integer.toString(avcC[3] & 0xff));
            f.put("lengthSizeMinusOne", Integer.toString(avcC[4] & 0x03));
            int numSps = avcC[5] & 0x1f;
            f.put("numOfSequenceParameterSets", Integer.toString(numSps));
            int pos = 6;
            for (int i = 0; i < numSps; i++) {
                int len = ((avcC[pos] & 0xff) << 8) | (avcC[pos + 1] & 0xff);
                pos += 2;
                byte[] nal = new byte[len];
                System.arraycopy(avcC, pos, nal, 0, len);
                pos += len;
                String prefix = i == 0 ? "" : "sps" + i + ".";
                decodeSps(nal, prefix, f);
            }
            byte[] rest = new byte[avcC.length - pos];
            System.arraycopy(avcC, pos, rest, 0, rest.length);
            f.put("pps_and_rest", HexFormat.of().formatHex(rest));
        } catch (RuntimeException e) {
            f.put("error", "could not decode the rest (" + e + ")");
        }
        return f;
    }

    /**
     * The fields that differ between two avcC records, e.g.
     * {@code colour_primaries 2 (unspecified) instead of 1 (BT.709)}, joined with ", "; empty when
     * the records are byte-identical. If they differ but no decoded field does, says so with both
     * hex strings.
     *
     * @param expected the reference
     * @param actual   the record compared with it
     */
    public static String differences(byte[] expected, byte[] actual) {
        if (java.util.Arrays.equals(expected, actual)) {
            return "";
        }
        Map<String, String> e = decode(expected);
        Map<String, String> a = decode(actual);
        Set<String> keys = new LinkedHashSet<>(e.keySet());
        keys.addAll(a.keySet());
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            String ev = e.get(key);
            String av = a.get(key);
            if (!Objects.equals(ev, av)) {
                out.add(key + " " + (av == null ? "missing" : av) + " instead of " + (ev == null ? "missing" : ev));
            }
        }
        if (out.isEmpty()) {
            HexFormat hex = HexFormat.of();
            return "the records differ (expected " + (expected == null ? "none" : hex.formatHex(expected)) + ", got "
                    + (actual == null ? "none" : hex.formatHex(actual)) + ")";
        }
        return String.join(", ", out);
    }

    private static void decodeSps(byte[] nal, String p, Map<String, String> f) {
        f.put(p + "nal_unit_header", Integer.toString(nal[0] & 0xff));
        SkipFrames.BitReader r = new SkipFrames.BitReader(SkipFrames.unescape(nal, 1));
        int profileIdc = r.bits(8);
        f.put(p + "profile_idc", Integer.toString(profileIdc));
        f.put(p + "constraint_flags", Integer.toString(r.bits(8)));
        f.put(p + "level_idc", Integer.toString(r.bits(8)));
        f.put(p + "seq_parameter_set_id", Integer.toString(r.ue()));
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44
                || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128
                || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
            int chromaFormatIdc = r.ue();
            f.put(p + "chroma_format_idc", Integer.toString(chromaFormatIdc));
            if (chromaFormatIdc == 3) {
                f.put(p + "separate_colour_plane_flag", Integer.toString(r.bit()));
            }
            f.put(p + "bit_depth_luma_minus8", Integer.toString(r.ue()));
            f.put(p + "bit_depth_chroma_minus8", Integer.toString(r.ue()));
            f.put(p + "qpprime_y_zero_transform_bypass_flag", Integer.toString(r.bit()));
            int scaling = r.bit();
            f.put(p + "seq_scaling_matrix_present_flag", Integer.toString(scaling));
            if (scaling == 1) {
                // the lists themselves aren't named one by one; their bits are compared as a whole
                StringBuilder lists = new StringBuilder();
                int n = chromaFormatIdc != 3 ? 8 : 12;
                for (int i = 0; i < n; i++) {
                    if (r.bit() == 1) {
                        int size = i < 6 ? 16 : 64;
                        int last = 8;
                        int next = 8;
                        for (int j = 0; j < size; j++) {
                            if (next != 0) {
                                next = (last + r.se() + 256) % 256;
                            }
                            last = next == 0 ? last : next;
                            lists.append(last).append(' ');
                        }
                    }
                    lists.append('|');
                }
                f.put(p + "scaling_lists", lists.toString());
            }
        }
        f.put(p + "log2_max_frame_num_minus4", Integer.toString(r.ue()));
        int pocType = r.ue();
        f.put(p + "pic_order_cnt_type", Integer.toString(pocType));
        if (pocType == 0) {
            f.put(p + "log2_max_pic_order_cnt_lsb_minus4", Integer.toString(r.ue()));
        } else if (pocType == 1) {
            f.put(p + "delta_pic_order_always_zero_flag", Integer.toString(r.bit()));
            f.put(p + "offset_for_non_ref_pic", Integer.toString(r.se()));
            f.put(p + "offset_for_top_to_bottom_field", Integer.toString(r.se()));
            int cycle = r.ue();
            StringBuilder offsets = new StringBuilder();
            for (int i = 0; i < cycle; i++) {
                offsets.append(r.se()).append(' ');
            }
            f.put(p + "offset_for_ref_frame", offsets.toString().trim());
        }
        f.put(p + "max_num_ref_frames", Integer.toString(r.ue()));
        f.put(p + "gaps_in_frame_num_value_allowed_flag", Integer.toString(r.bit()));
        f.put(p + "pic_width_in_mbs_minus1", Integer.toString(r.ue()));
        f.put(p + "pic_height_in_map_units_minus1", Integer.toString(r.ue()));
        int frameMbsOnly = r.bit();
        f.put(p + "frame_mbs_only_flag", Integer.toString(frameMbsOnly));
        if (frameMbsOnly == 0) {
            f.put(p + "mb_adaptive_frame_field_flag", Integer.toString(r.bit()));
        }
        f.put(p + "direct_8x8_inference_flag", Integer.toString(r.bit()));
        int cropping = r.bit();
        f.put(p + "frame_cropping_flag", Integer.toString(cropping));
        if (cropping == 1) {
            f.put(p + "frame_crop_left_offset", Integer.toString(r.ue()));
            f.put(p + "frame_crop_right_offset", Integer.toString(r.ue()));
            f.put(p + "frame_crop_top_offset", Integer.toString(r.ue()));
            f.put(p + "frame_crop_bottom_offset", Integer.toString(r.ue()));
        }
        int vui = r.bit();
        f.put(p + "vui_parameters_present_flag", Integer.toString(vui));
        if (vui == 1) {
            decodeVui(r, p, f);
        }
    }

    private static void decodeVui(SkipFrames.BitReader r, String p, Map<String, String> f) {
        int aspect = r.bit();
        f.put(p + "aspect_ratio_info_present_flag", Integer.toString(aspect));
        if (aspect == 1) {
            int idc = r.bits(8);
            f.put(p + "aspect_ratio_idc", Integer.toString(idc));
            if (idc == 255) {
                f.put(p + "sar_width", Integer.toString(r.bits(16)));
                f.put(p + "sar_height", Integer.toString(r.bits(16)));
            }
        }
        int overscan = r.bit();
        f.put(p + "overscan_info_present_flag", Integer.toString(overscan));
        if (overscan == 1) {
            f.put(p + "overscan_appropriate_flag", Integer.toString(r.bit()));
        }
        int signal = r.bit();
        f.put(p + "video_signal_type_present_flag", Integer.toString(signal));
        if (signal == 1) {
            f.put(p + "video_format", Integer.toString(r.bits(3)));
            f.put(p + "video_full_range_flag", Integer.toString(r.bit()));
            int colour = r.bit();
            f.put(p + "colour_description_present_flag", Integer.toString(colour));
            if (colour == 1) {
                f.put(p + "colour_primaries", named(r.bits(8), PRIMARIES));
                f.put(p + "transfer_characteristics", named(r.bits(8), TRANSFER));
                f.put(p + "matrix_coefficients", named(r.bits(8), MATRIX));
            }
        }
        int chromaLoc = r.bit();
        f.put(p + "chroma_loc_info_present_flag", Integer.toString(chromaLoc));
        if (chromaLoc == 1) {
            f.put(p + "chroma_sample_loc_type_top_field", Integer.toString(r.ue()));
            f.put(p + "chroma_sample_loc_type_bottom_field", Integer.toString(r.ue()));
        }
        int timing = r.bit();
        f.put(p + "timing_info_present_flag", Integer.toString(timing));
        if (timing == 1) {
            f.put(p + "num_units_in_tick", Long.toString(((long) r.bits(16) << 16) | r.bits(16)));
            f.put(p + "time_scale", Long.toString(((long) r.bits(16) << 16) | r.bits(16)));
            f.put(p + "fixed_frame_rate_flag", Integer.toString(r.bit()));
        }
        int nalHrd = r.bit();
        f.put(p + "nal_hrd_parameters_present_flag", Integer.toString(nalHrd));
        if (nalHrd == 1) {
            decodeHrd(r, p + "nal_hrd.", f);
        }
        int vclHrd = r.bit();
        f.put(p + "vcl_hrd_parameters_present_flag", Integer.toString(vclHrd));
        if (vclHrd == 1) {
            decodeHrd(r, p + "vcl_hrd.", f);
        }
        if (nalHrd == 1 || vclHrd == 1) {
            f.put(p + "low_delay_hrd_flag", Integer.toString(r.bit()));
        }
        f.put(p + "pic_struct_present_flag", Integer.toString(r.bit()));
        int restriction = r.bit();
        f.put(p + "bitstream_restriction_flag", Integer.toString(restriction));
        if (restriction == 1) {
            f.put(p + "motion_vectors_over_pic_boundaries_flag", Integer.toString(r.bit()));
            f.put(p + "max_bytes_per_pic_denom", Integer.toString(r.ue()));
            f.put(p + "max_bits_per_mb_denom", Integer.toString(r.ue()));
            f.put(p + "log2_max_mv_length_horizontal", Integer.toString(r.ue()));
            f.put(p + "log2_max_mv_length_vertical", Integer.toString(r.ue()));
            f.put(p + "max_num_reorder_frames", Integer.toString(r.ue()));
            f.put(p + "max_dec_frame_buffering", Integer.toString(r.ue()));
        }
    }

    private static void decodeHrd(SkipFrames.BitReader r, String p, Map<String, String> f) {
        int cpbCnt = r.ue() + 1;
        f.put(p + "cpb_cnt_minus1", Integer.toString(cpbCnt - 1));
        f.put(p + "bit_rate_scale", Integer.toString(r.bits(4)));
        f.put(p + "cpb_size_scale", Integer.toString(r.bits(4)));
        for (int i = 0; i < cpbCnt; i++) {
            f.put(p + "bit_rate_value_minus1[" + i + "]", Integer.toString(r.ue()));
            f.put(p + "cpb_size_value_minus1[" + i + "]", Integer.toString(r.ue()));
            f.put(p + "cbr_flag[" + i + "]", Integer.toString(r.bit()));
        }
        f.put(p + "initial_cpb_removal_delay_length_minus1", Integer.toString(r.bits(5)));
        f.put(p + "cpb_removal_delay_length_minus1", Integer.toString(r.bits(5)));
        f.put(p + "dpb_output_delay_length_minus1", Integer.toString(r.bits(5)));
        f.put(p + "time_offset_length", Integer.toString(r.bits(5)));
    }

    private static final Map<Integer, String> PRIMARIES = Map.of(1, "BT.709", 2, "unspecified", 4, "BT.470M",
            5, "BT.470BG", 6, "SMPTE 170M", 7, "SMPTE 240M", 8, "film", 9, "BT.2020");
    private static final Map<Integer, String> TRANSFER = Map.of(1, "BT.709", 2, "unspecified", 4, "gamma 2.2",
            5, "gamma 2.8", 6, "SMPTE 170M", 8, "linear", 13, "sRGB", 16, "PQ", 18, "HLG");
    private static final Map<Integer, String> MATRIX = Map.of(0, "identity/RGB", 1, "BT.709", 2, "unspecified",
            5, "BT.470BG", 6, "SMPTE 170M", 7, "SMPTE 240M", 9, "BT.2020 NCL");

    private static String named(int value, Map<Integer, String> names) {
        String name = names.get(value);
        return name == null ? Integer.toString(value) : value + " (" + name + ")";
    }
}
