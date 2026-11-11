package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AvcCFieldsTest {
    /** The 720p30 reference avcC (ffmpeg 6.1.1, and every tested version with the colour pins). */
    private static final String REF = "01640028ffe1001d67640028acb280a00b7602d40404050000030001000003003c0f1832580100"
            + "0568e9332c8bfdf8f800";

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    @Test
    void decodesTheSpsWithItsVui() {
        Map<String, String> f = AvcCFields.decode(hex(REF));
        assertFalse(f.containsKey("error"), f.toString());
        assertEquals("100", f.get("profile_idc"));
        assertEquals("40", f.get("level_idc"));
        assertEquals("1", f.get("chroma_format_idc"));
        assertEquals("79", f.get("pic_width_in_mbs_minus1"), "1280 / 16 - 1");
        assertEquals("44", f.get("pic_height_in_map_units_minus1"), "720 / 16 - 1");
        assertEquals("4", f.get("max_num_ref_frames"));
        assertEquals("1", f.get("aspect_ratio_idc"), "SAR 1:1");
        assertEquals("5", f.get("video_format"));
        assertEquals("0", f.get("video_full_range_flag"));
        assertEquals("1 (BT.709)", f.get("colour_primaries"));
        assertEquals("1 (BT.709)", f.get("transfer_characteristics"));
        assertEquals("1 (BT.709)", f.get("matrix_coefficients"));
        assertEquals("0", f.get("chroma_loc_info_present_flag"), "left-sited: the default, not written");
        assertEquals("1", f.get("num_units_in_tick"));
        assertEquals("60", f.get("time_scale"));
        assertEquals("0", f.get("nal_hrd_parameters_present_flag"));
        assertEquals("0", f.get("vcl_hrd_parameters_present_flag"));
        assertEquals("1", f.get("bitstream_restriction_flag"));
        assertEquals("4", f.get("max_dec_frame_buffering"));
        assertEquals("0", f.get("max_num_reorder_frames"), "no B-frames");
        assertEquals("01000568e9332c8bfdf8f800", f.get("pps_and_rest"));
    }

    /** The mismatch reported from ffmpeg master (2026-11-11), decoded into the fields that differ. */
    @Test
    void namesTheDifferingFields() {
        byte[] card = hex(REF.replace("d40404", "d40808"));
        assertEquals("", AvcCFields.differences(hex(REF), hex(REF)));
        assertEquals("colour_primaries 1 (BT.709) instead of 2 (unspecified),"
                + " transfer_characteristics 1 (BT.709) instead of 2 (unspecified)",
                AvcCFields.differences(card, hex(REF)));
        // ffmpeg 7.1 before the pins: a full-range BT.601 source (primaries/transfer 6) and Motion JPEG
        // (centre-sited chroma)
        assertEquals("colour_primaries 6 (SMPTE 170M) instead of 1 (BT.709),"
                + " transfer_characteristics 6 (SMPTE 170M) instead of 1 (BT.709)",
                AvcCFields.differences(hex(REF), hex(REF.replace("d40404", "d41818"))));
        String mjpeg = "01640028ffe1001e67640028acb280a00b7602d408080694000003000400000300f03c60c96001000568e9332c8bfdf8f800";
        String diff = AvcCFields.differences(hex(REF), hex(mjpeg));
        assertTrue(diff.contains("chroma_loc_info_present_flag 1 instead of 0"), diff);
        assertTrue(diff.contains("chroma_sample_loc_type_top_field 1 instead of missing"), diff);
        assertTrue(diff.contains("colour_primaries 2 (unspecified) instead of 1 (BT.709)"), diff);
    }

    @Test
    void differencesOutsideTheSpsAndBadRecords() {
        byte[] other = hex(REF);
        other[other.length - 1] ^= 1;
        assertEquals("pps_and_rest 01000568e9332c8bfdf8f801 instead of 01000568e9332c8bfdf8f800",
                AvcCFields.differences(hex(REF), other));
        assertTrue(AvcCFields.decode(hex("0164")).containsKey("error"));
        assertTrue(AvcCFields.decode(hex("01640028ffe1001d6764")).containsKey("error"), "truncated");
        assertFalse(AvcCFields.differences(hex(REF), null).isEmpty());
    }
}
