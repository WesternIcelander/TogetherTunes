package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CbrPadderTest {
    private static final long UNIT = UnitBuilder.UNIT_TICKS;
    private static final long FRAME = ProgramSource.VIDEO_FRAME_TICKS;

    /** An AVCC sample: one slice NAL unit of {@code size} bytes in all (prefix included). */
    private static byte[] sample(int lengthSize, int size, boolean idr) {
        byte[] b = new byte[size];
        int nal = size - lengthSize;
        for (int k = lengthSize - 1; k >= 0; k--) {
            b[lengthSize - 1 - k] = (byte) (nal >>> (8 * k));
        }
        b[lengthSize] = (byte) (idr ? 0x65 : 0x41);
        Arrays.fill(b, lengthSize + 1, size, (byte) 0x5A);
        return b;
    }

    /** A 60-frame unit at {@code start}: an IDR of {@code idrBytes}, P-frames of {@code pBytes}. */
    private static Unit unit(int lengthSize, long start, int idrBytes, int pBytes) {
        List<Unit.Video> v = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            v.add(new Unit.Video(sample(lengthSize, i == 0 ? idrBytes : pBytes, i == 0), start + i * FRAME, i == 0));
        }
        return new Unit(start, start + UNIT, start + UNIT, v, List.of(), null, false);
    }

    private static long videoBytes(Unit u) {
        long n = 0;
        for (Unit.Video v : u.video()) {
            n += v.data().length;
        }
        return n;
    }

    /**
     * Splits an AVCC sample into NAL units and checks every one after the first (the slice) is a
     * well-formed filler data NAL unit: type 12, nal_ref_idc 0, 0xFF payload, RBSP trailing bits.
     * Returns how many filler NAL units there were.
     */
    private static int checkFiller(int lengthSize, byte[] sample) {
        int pos = 0;
        int count = 0;
        boolean first = true;
        while (pos < sample.length) {
            long len = 0;
            for (int k = 0; k < lengthSize; k++) {
                len = (len << 8) | (sample[pos + k] & 0xFF);
            }
            pos += lengthSize;
            assertTrue(len >= 2 && pos + len <= sample.length, "NAL length " + len);
            if (!first) {
                assertEquals(0x0C, sample[pos] & 0xFF, "filler NAL header");
                for (int k = 1; k < len - 1; k++) {
                    assertEquals((byte) 0xFF, sample[pos + k]);
                }
                assertEquals((byte) 0x80, sample[(int) (pos + len - 1)], "RBSP trailing bits");
                count++;
            }
            first = false;
            pos += (int) len;
        }
        return count;
    }

    @Test
    void padsEveryUnitToExactlyTheRate() {
        CbrPadder p = new CbrPadder(4, 3_000_000);
        long start = OutputClock.START;
        for (int n = 0; n < 5; n++) {
            Unit in = unit(4, start, 60_000, 3_000);
            Unit out = p.pad(in);
            assertEquals(750_000, videoBytes(out), "unit " + n);
            assertEquals(in.startPts(), out.startPts());
            assertEquals(in.boundaryPts(), out.boundaryPts());
            assertEquals(in.audio(), out.audio());
            for (int i = 0; i < 60; i++) {
                Unit.Video v = out.video().get(i);
                assertEquals(in.video().get(i).pts(), v.pts());
                assertEquals(in.video().get(i).idr(), v.idr());
                // the frame itself is unchanged, the filler follows its slice
                byte[] orig = in.video().get(i).data();
                assertArrayEquals(orig, Arrays.copyOf(v.data(), orig.length));
                checkFiller(4, v.data());
            }
            start += UNIT;
        }
        assertEquals(5 * 750_000L, p.sentBytes());
    }

    /** An IDR bigger than its share is paid back by the following frames, and only by them. */
    @Test
    void aLargeIdrIsPaidBackByTheFollowingFrames() {
        CbrPadder p = new CbrPadder(4, 3_000_000);
        // the IDR alone is 150 KB, more than its 12.5 KB share and than the next 11 frames' shares
        Unit out = p.pad(unit(4, OutputClock.START, 150_000, 2_000));
        assertEquals(150_000, out.video().get(0).data().length, "no filler after an IDR that is ahead");
        long sent = 0;
        for (int i = 0; i < 60; i++) {
            sent += out.video().get(i).data().length;
            long due = 3_000_000L * (i + 1) * FRAME / (8 * OutputClock.HZ);
            assertTrue(sent >= due, "never behind after frame " + i);
            if (sent > due) {
                assertEquals(i == 0 ? 150_000 : 2_000, out.video().get(i).data().length,
                        "frames while ahead get no filler: " + i);
            }
        }
        assertEquals(750_000, videoBytes(out));
    }

    /** A unit bigger than the whole window (an encode above T) is not cut; the next ones pay it back. */
    @Test
    void anOutputAheadOfTheRateCatchesUp() {
        CbrPadder p = new CbrPadder(4, 800_000);
        // 800 kb/s is 200 000 bytes per unit; the first unit carries 300 000
        Unit first = p.pad(unit(4, OutputClock.START, 123_000, 3_000));
        assertEquals(300_000, videoBytes(first));
        Unit second = p.pad(unit(4, OutputClock.START + UNIT, 5_000, 1_000));
        assertEquals(400_000 - 300_000, videoBytes(second), "the second unit makes up the total");
        Unit third = p.pad(unit(4, OutputClock.START + 2 * UNIT, 5_000, 1_000));
        assertEquals(200_000, videoBytes(third));
    }

    @Test
    void nothingToPadReturnsTheSameUnit() {
        CbrPadder p = new CbrPadder(4, 500_000);
        Unit big = unit(4, OutputClock.START, 200_000, 20_000);
        assertSame(big, p.pad(big));
        Unit empty = new Unit(0, UNIT, UNIT, List.of(), List.of(), null, false);
        assertSame(empty, p.pad(empty));
    }

    /** With a 1- or 2-byte length prefix, filler is split into NAL units that fit it, still exact. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void lengthPrefixSizes(int lengthSize) {
        CbrPadder p = new CbrPadder(lengthSize, 3_000_000);
        Unit out = p.pad(unit(lengthSize, OutputClock.START, 200, 100));
        assertEquals(750_000, videoBytes(out));
        int fillers = 0;
        for (Unit.Video v : out.video()) {
            fillers += checkFiller(lengthSize, v.data());
        }
        int minimum = lengthSize == 4 ? 60 : (int) (750_000 / ((1L << (8 * lengthSize)) - 1 + lengthSize));
        assertTrue(fillers >= minimum, "filler NAL units: " + fillers);
    }

    /** A source that ended in the unit: its last frame runs to the unit's (shorter) end. */
    @Test
    void aShortUnitIsPaddedToItsOwnLength() {
        CbrPadder p = new CbrPadder(4, 3_000_000);
        long start = OutputClock.START;
        long end = start + 10 * FRAME + 1234;
        List<Unit.Video> v = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            v.add(new Unit.Video(sample(4, i == 0 ? 20_000 : 1_000, i == 0), start + i * FRAME, i == 0));
        }
        Unit out = p.pad(new Unit(start, end, end, v, List.of(), null, true));
        assertEquals(3_000_000L * (end - start) / (8 * OutputClock.HZ), videoBytes(out));
    }

    /**
     * A video with a short first GOP (a smart trim's): its short first unit (7 frames, the source
     * continues) is padded to its own length, and the 2 s units after it to theirs, so the output
     * is at exactly the rate at every unit boundary.
     */
    @Test
    void aShortFirstUnitKeepsTheRate() {
        CbrPadder p = new CbrPadder(4, 3_000_000);
        long first = OutputClock.START;
        List<Unit.Video> v = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            v.add(new Unit.Video(sample(4, i == 0 ? 20_000 : 1_000, i == 0), first + i * FRAME, i == 0));
        }
        long boundary = first + 7 * FRAME;
        p.pad(new Unit(first, boundary, boundary + 1200, v, List.of(), null, false));
        assertEquals(3_000_000L * (boundary - first) / (8 * OutputClock.HZ), p.sentBytes());
        long start = boundary;
        for (int n = 0; n < 3; n++) {
            p.pad(unit(4, start, 60_000, 3_000));
            start += UNIT;
            assertEquals(3_000_000L * (start - first) / (8 * OutputClock.HZ), p.sentBytes(), "after unit " + n);
        }
    }

    @Test
    void budgetDoesNotOverflow() {
        CbrPadder p = new CbrPadder(4, 25_000_000);
        p.pad(unit(4, 0, 100, 100));
        long year = 365L * 86_400 * OutputClock.HZ;
        assertEquals(25_000_000L / 8 * 365 * 86_400 * 10, p.budget(10 * year));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CbrPadder(3, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new CbrPadder(4, 0));
    }
}
