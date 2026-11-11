package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.siggi.togethertunes.media.AnnexB.H264FormatException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link AnnexBReader}: splitting an Annex B stream into NAL units. */
class AnnexBReaderTest {
    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static List<byte[]> readAll(InputStream in) throws IOException {
        AnnexBReader r = new AnnexBReader(in);
        List<byte[]> out = new ArrayList<>();
        byte[] nal;
        while ((nal = r.next()) != null) {
            out.add(nal);
        }
        assertNull(r.next(), "stays at the end");
        assertEquals(out.size(), r.nalCount());
        return out;
    }

    /** Hands out one byte per read, so every start code straddles a read boundary somewhere. */
    private static InputStream trickle(byte[] data) {
        return new FilterInputStream(new ByteArrayInputStream(data)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    @Test
    void splitsOnThreeAndFourByteStartCodes() throws Exception {
        byte[] stream = bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x1F,
                0, 0, 1, 0x68, 0xEE,
                0, 0, 0, 1, 0x65, 0x88, 0x00, 0x00, 0x03, 0x01, 0x80);
        for (InputStream in : List.of(new ByteArrayInputStream(stream), trickle(stream))) {
            List<byte[]> nals = readAll(in);
            assertEquals(3, nals.size());
            assertArrayEquals(bytes(0x67, 0x64, 0x00, 0x1F), nals.get(0));
            assertArrayEquals(bytes(0x68, 0xEE), nals.get(1));
            // emulation prevention bytes stay in (the NAL unit is stored as is)
            assertArrayEquals(bytes(0x65, 0x88, 0x00, 0x00, 0x03, 0x01, 0x80), nals.get(2));
        }
    }

    @Test
    void stripsTrailingZerosAndSkipsEmptyNalUnits() throws Exception {
        byte[] stream = bytes(0, 0, 0, 0, 0, 1, 0x09, 0xF0, 0, 0, // leading zero_bytes, trailing zeros
                0, 0, 1, 0, 0, 1, // an empty NAL unit
                0x06, 0x05, 0x80, 0, 0, 0, 0);
        List<byte[]> nals = readAll(trickle(stream));
        assertEquals(2, nals.size());
        assertArrayEquals(bytes(0x09, 0xF0), nals.get(0));
        assertArrayEquals(bytes(0x06, 0x05, 0x80), nals.get(1));
    }

    @Test
    void emptyAndZeroOnlyStreamsHaveNoNalUnits() throws Exception {
        assertEquals(0, readAll(new ByteArrayInputStream(new byte[0])).size());
        assertEquals(0, readAll(new ByteArrayInputStream(new byte[5])).size());
    }

    @Test
    void rejectsDataBeforeTheFirstStartCode() {
        assertThrows(H264FormatException.class,
                () -> readAll(new ByteArrayInputStream(bytes(0x47, 0, 0, 1, 0x09, 0xF0))));
        assertThrows(H264FormatException.class, () -> readAll(new ByteArrayInputStream(bytes(1, 2, 3, 4))));
    }

    @Test
    void rejectsNalUnitsOverTheLimit() {
        byte[] big = new byte[4 + 100];
        big[3] = 1;
        for (int i = 4; i < big.length; i++) {
            big[i] = 0x41;
        }
        assertThrows(H264FormatException.class, () -> {
            AnnexBReader r = new AnnexBReader(new ByteArrayInputStream(big), 50);
            while (r.next() != null) {
                // read on
            }
        });
    }

    @Test
    void randomStreamsRoundTripAcrossBufferBoundaries() throws Exception {
        Random rnd = new Random(42);
        for (int round = 0; round < 20; round++) {
            List<byte[]> expected = new ArrayList<>();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            int count = 1 + rnd.nextInt(40);
            for (int n = 0; n < count; n++) {
                // sizes around the reader's 64 KiB buffer, payloads without 00 00 0x (as after emulation prevention)
                int size = 1 + rnd.nextInt(rnd.nextBoolean() ? 50 : 150_000);
                byte[] nal = new byte[size];
                for (int i = 0; i < size; i++) {
                    int v = rnd.nextInt(256);
                    if (v == 0 && i >= 1 && nal[i - 1] == 0) {
                        v = 3;
                    }
                    nal[i] = (byte) v;
                }
                if (nal[size - 1] == 0) {
                    nal[size - 1] = (byte) 0x80;
                }
                expected.add(nal);
                stream.write(rnd.nextBoolean() ? bytes(0, 0, 0, 1) : bytes(0, 0, 1));
                stream.write(nal);
            }
            byte[] data = stream.toByteArray();
            InputStream in = rnd.nextBoolean() ? new ByteArrayInputStream(data) : new FilterInputStream(
                    new ByteArrayInputStream(data)) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return super.read(b, off, Math.min(len, 1 + rnd.nextInt(5000)));
                }
            };
            List<byte[]> got = readAll(in);
            assertEquals(expected.size(), got.size(), "round " + round);
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), got.get(i), "round " + round + " NAL " + i);
            }
        }
    }
}
