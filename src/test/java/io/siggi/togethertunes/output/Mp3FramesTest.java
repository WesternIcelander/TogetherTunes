package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** {@link Mp3Frames}: header parsing and cutting a byte stream into whole frames. */
class Mp3FramesTest {
    /** An MPEG-1 Layer III frame at 128 kb/s, 44.1 kHz (417 bytes, 418 with padding), filled with {@code fill}. */
    static byte[] frame(boolean padding, int fill) {
        byte[] f = new byte[padding ? 418 : 417];
        Arrays.fill(f, (byte) fill);
        f[0] = (byte) 0xff;
        f[1] = (byte) 0xfb; // MPEG-1, Layer III, no CRC
        f[2] = (byte) (0x90 | (padding ? 0x02 : 0)); // 128 kb/s, 44.1 kHz
        f[3] = (byte) 0x64;
        return f;
    }

    @Test
    void parsesLayer3Headers() {
        Mp3Frames.Header h = Mp3Frames.header(frame(false, 0), 0);
        assertNotNull(h);
        assertEquals(417, h.length());
        assertEquals(44_100, h.sampleRate());
        assertEquals(1152, h.samplesPerFrame());
        assertEquals(128, h.bitrateKbps());
        assertEquals(418, Mp3Frames.header(frame(true, 0), 0).length());
        // MPEG-2 Layer III, 64 kb/s, 22.05 kHz: 72 * 64000 / 22050 = 208
        Mp3Frames.Header m2 = Mp3Frames.header(new byte[] {(byte) 0xff, (byte) 0xf3, (byte) 0x80, 0}, 0);
        assertEquals(208, m2.length());
        assertEquals(576, m2.samplesPerFrame());
        // not Layer III, free format, bad sample rate, no sync
        assertNull(Mp3Frames.header(new byte[] {(byte) 0xff, (byte) 0xfd, (byte) 0x90, 0}, 0));
        assertNull(Mp3Frames.header(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x00, 0}, 0));
        assertNull(Mp3Frames.header(new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x9c, 0}, 0));
        assertNull(Mp3Frames.header(new byte[] {(byte) 0xfe, (byte) 0xfb, (byte) 0x90, 0}, 0));
    }

    @Test
    void cutsWholeFramesAcrossFeedsAndSkipsJunkAndId3() throws Exception {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 5, 1, 2, 3, 4, 5}); // ID3v2 with 5 bytes of tag
        in.write(new byte[] {1, 2, (byte) 0xff, 7}); // junk, incl. a lone sync byte
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) {
            byte[] f = frame(i % 2 == 1, 0x11 * (i + 1));
            in.write(f);
            frames.write(f);
        }
        byte[] stream = in.toByteArray();
        for (int step : new int[] {1, 7, 100, 417, 5000}) {
            Mp3Frames m = new Mp3Frames();
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            for (int pos = 0; pos < stream.length; pos += step) {
                byte[] out = m.feed(stream, pos, Math.min(step, stream.length - pos));
                if (out.length > 0) {
                    assertNotNull(Mp3Frames.header(out, 0), "a chunk starts with a frame");
                }
                got.write(out);
            }
            // the last frame waits for the next header, or the end of the stream
            assertEquals(4, m.frames());
            got.write(m.flush());
            assertArrayEquals(frames.toByteArray(), got.toByteArray(), "step " + step);
            assertEquals(5, m.frames());
            assertEquals(5 * 1152, m.samples());
        }
    }

    @Test
    void aFalseSyncInsideDataDoesNotStartAFrame() {
        byte[] junk = {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64, 1, 2, 3}; // a header, but no frame follows
        byte[] f = frame(false, 0x22);
        byte[] g = frame(false, 0x33);
        byte[] stream = new byte[junk.length + f.length + g.length];
        System.arraycopy(junk, 0, stream, 0, junk.length);
        System.arraycopy(f, 0, stream, junk.length, f.length);
        System.arraycopy(g, 0, stream, junk.length + f.length, g.length);
        Mp3Frames m = new Mp3Frames();
        byte[] out = m.feed(stream, 0, stream.length);
        assertArrayEquals(f, out);
        assertArrayEquals(g, m.flush());
    }
}
