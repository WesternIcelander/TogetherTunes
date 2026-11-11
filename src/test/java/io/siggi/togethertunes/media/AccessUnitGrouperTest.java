package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.media.AccessUnitGrouper.AccessUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link AccessUnitGrouper}: NAL units to access units by {@code first_mb_in_slice}. */
class AccessUnitGrouperTest {
    private static final byte[] AUD = {0x09, (byte) 0xF0};
    private static final byte[] SPS = {0x67, 0x64, 0x00, 0x1F};
    private static final byte[] PPS = {0x68, (byte) 0xEE, 0x3C, (byte) 0x80};
    private static final byte[] SEI = {0x06, 0x05, 0x01, (byte) 0x80};

    /** An IDR slice; {@code firstMb0} sets the first slice-header bit ({@code first_mb_in_slice} = 0). */
    private static byte[] idr(boolean firstMb0) {
        return new byte[] {0x65, (byte) (firstMb0 ? 0x88 : 0x40), 0x11};
    }

    private static byte[] p(boolean firstMb0) {
        return new byte[] {0x41, (byte) (firstMb0 ? 0x9A : 0x22), 0x22};
    }

    private static List<AccessUnit> group(byte[]... nals) {
        AccessUnitGrouper g = new AccessUnitGrouper();
        List<AccessUnit> out = new ArrayList<>();
        for (byte[] nal : nals) {
            AccessUnit au = g.push(nal);
            if (au != null) {
                out.add(au);
            }
        }
        AccessUnit last = g.finish();
        if (last != null) {
            out.add(last);
        }
        assertNull(g.finish());
        return out;
    }

    @Test
    void firstMbInSliceIsTheFirstHeaderBit() {
        assertTrue(AccessUnitGrouper.firstMbIsZero(idr(true)));
        assertFalse(AccessUnitGrouper.firstMbIsZero(idr(false)));
        assertFalse(AccessUnitGrouper.firstMbIsZero(new byte[] {0x65}));
    }

    @Test
    void x264StreamSplitsIntoFrames() {
        // as x264 writes it: SPS, PPS, SEI and the IDR, then P-frames, a second IDR with its parameter sets
        byte[] idr1 = idr(true);
        byte[] p1 = p(true);
        byte[] p2 = p(true);
        byte[] idr2 = idr(true);
        List<AccessUnit> aus = group(SPS, PPS, SEI, idr1, p1, p2, SPS, PPS, idr2);
        assertEquals(4, aus.size());
        assertEquals(List.of(SPS, PPS, SEI, idr1), aus.get(0).nals());
        assertTrue(aus.get(0).isIdr());
        assertEquals(List.of(p1), aus.get(1).nals());
        assertFalse(aus.get(1).isIdr());
        assertEquals(List.of(p2), aus.get(2).nals());
        assertEquals(List.of(SPS, PPS, idr2), aus.get(3).nals());
        assertTrue(aus.get(3).isIdr());
        for (AccessUnit au : aus) {
            assertTrue(au.hasSlice());
        }
    }

    @Test
    void furtherSlicesOfAPictureStayInItsAccessUnit() {
        byte[] s0 = idr(true);
        byte[] s1 = idr(false);
        byte[] s2 = idr(false);
        byte[] q0 = p(true);
        byte[] q1 = p(false);
        List<AccessUnit> aus = group(AUD, s0, s1, s2, AUD, q0, q1);
        assertEquals(2, aus.size());
        assertEquals(List.of(AUD, s0, s1, s2), aus.get(0).nals());
        assertEquals(List.of(AUD, q0, q1), aus.get(1).nals());
    }

    @Test
    void seiAfterASliceBeginsTheNextAccessUnit() {
        byte[] s0 = p(true);
        byte[] s1 = p(true);
        List<AccessUnit> aus = group(s0, SEI, s1);
        assertEquals(2, aus.size());
        assertEquals(List.of(s0), aus.get(0).nals());
        assertEquals(List.of(SEI, s1), aus.get(1).nals());
        assertSame(SEI, aus.get(1).nals().get(0), "NAL arrays are shared, not copied");
    }

    @Test
    void otherNalUnitsJoinTheCurrentAccessUnit() {
        byte[] filler = {0x0C, (byte) 0xFF, (byte) 0x80};
        byte[] endOfSeq = {0x0A};
        byte[] s0 = p(true);
        List<AccessUnit> aus = group(s0, filler, endOfSeq);
        assertEquals(1, aus.size());
        assertEquals(List.of(s0, filler, endOfSeq), aus.get(0).nals());
    }

    @Test
    void trailingNonSliceNalUnitsComeBackWithoutASlice() {
        List<AccessUnit> aus = group(p(true), SPS, PPS);
        assertEquals(2, aus.size());
        assertFalse(aus.get(1).hasSlice());
        assertEquals(List.of(SPS, PPS), aus.get(1).nals());
    }

    @Test
    void emptyNalUnitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AccessUnitGrouper().push(new byte[0]));
    }
}
