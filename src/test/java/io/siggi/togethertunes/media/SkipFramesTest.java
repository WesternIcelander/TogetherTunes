package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** The skip-frame writer without ffmpeg: bit writing, emulation prevention, parameter set checks. */
class SkipFramesTest {
    /** x264's 720p30 High profile SPS and PPS with the shared arguments (ffmpeg 6.1.1). */
    static final byte[] SPS = HexFormat.of().parseHex("67640028acb280a00b7602d40404050000030001000003003c0f183258");
    static final byte[] PPS = HexFormat.of().parseHex("68e9332c8b");

    @Test
    void writesExpGolombCodes() {
        SkipFrames.BitWriter w = new SkipFrames.BitWriter();
        w.ue(0); // 1
        w.ue(1); // 010
        w.ue(4); // 00101
        w.se(-1); // ue(2) = 011
        w.se(2); // ue(3) = 00100
        w.bits(0, 7); // 1 010 00101 011 00100 = 17 bits, padded to 3 bytes
        byte[] expected = {(byte) 0b1010_0010, (byte) 0b1011_0010, 0};
        assertArrayEquals(expected, w.toByteArray());
        SkipFrames.BitReader r = new SkipFrames.BitReader(expected);
        assertEquals(0, r.ue());
        assertEquals(1, r.ue());
        assertEquals(4, r.ue());
        assertEquals(-1, r.se());
        assertEquals(2, r.se());
    }

    @Test
    void escapesAndUnescapesStartCodePrefixes() {
        byte[] rbsp = {0x41, 0, 0, 0, 0, 0, 1, 0, 0, 3, 0, 0, 4};
        byte[] nal = SkipFrames.escape(rbsp);
        assertArrayEquals(new byte[] {0x41, 0, 0, 3, 0, 0, 3, 0, 1, 0, 0, 3, 3, 0, 0, 4}, nal);
        assertArrayEquals(java.util.Arrays.copyOfRange(rbsp, 1, rbsp.length), SkipFrames.unescape(nal, 1));
    }

    @Test
    void parsesTheProfileParameterSets() throws Exception {
        SkipFrames f = new SkipFrames(SPS, PPS);
        assertEquals(80 * 45, f.macroblocks());
        assertTrue(f.cabac());
        byte[] frame = f.frame(1);
        assertEquals(0x41, frame[0], "nal_ref_idc 2, non-IDR slice");
        assertTrue(frame.length < 100, "frame of " + frame.length + " bytes");
        for (int i = 0; i + 2 < frame.length; i++) {
            assertTrue(frame[i] != 0 || frame[i + 1] != 0 || (frame[i + 2] & 0xFF) > 2, "emulated start code at " + i);
        }
        // frame_num differs per frame
        assertTrue(!java.util.Arrays.equals(f.frame(1), f.frame(2)));
        assertThrows(IllegalArgumentException.class, () -> f.frame(0));
    }

    @Test
    void refusesWhatItCannotWrite() {
        assertThrows(SkipFrames.UnsupportedStreamException.class, () -> new SkipFrames(PPS, PPS));
        assertThrows(SkipFrames.UnsupportedStreamException.class, () -> new SkipFrames(SPS, SPS));
        assertThrows(SkipFrames.UnsupportedStreamException.class, () -> new SkipFrames(new byte[] {0x67, 0x64}, PPS));
        // a PPS for SPS 1
        assertThrows(SkipFrames.UnsupportedStreamException.class,
                () -> new SkipFrames(SPS, HexFormat.of().parseHex("68ab8f20")));
    }
}
