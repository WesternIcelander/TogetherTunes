package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.Tools;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AdtsTest {
    @TempDir
    Path dir;

    /** The header's fields, read back bit by bit (ISO/IEC 14496-3, 1.A.2.2). */
    private record Fields(int sync, int id, int layer, int protectionAbsent, int profile, int sfIndex, int privateBit,
            int channels, int flags, int frameLength, int fullness, int rawBlocks) {
        static Fields of(byte[] h) {
            long v = 0;
            for (int i = 0; i < 7; i++) {
                v = (v << 8) | (h[i] & 0xFF);
            }
            return new Fields(bits(v, 0, 12), bits(v, 12, 1), bits(v, 13, 2), bits(v, 15, 1), bits(v, 16, 2),
                    bits(v, 18, 4), bits(v, 22, 1), bits(v, 23, 3), bits(v, 26, 4), bits(v, 30, 13),
                    bits(v, 43, 11), bits(v, 54, 2));
        }

        private static int bits(long v, int from, int n) {
            return (int) ((v >>> (56 - from - n)) & ((1L << n) - 1));
        }
    }

    @Test
    void headerFieldsMatchTheProfile() {
        for (int len : new int[] {1, 6, 371, 1024, 2041, 2042, Adts.MAX_PAYLOAD}) {
            Fields f = Fields.of(Adts.header(len));
            assertEquals(new Fields(0xFFF, 0, 0, 1, 1, 3, 0, 2, 0, 7 + len, 0x7FF, 0), f, "payload " + len);
        }
        assertEquals(3, Adts.SF_INDEX);
        assertEquals(EncodeProfile.AUDIO_CHANNELS, Adts.CHANNEL_CONFIG);
    }

    @Test
    void knownBytes() {
        // 7 + 371 = 378 = 0x17A
        assertArrayEquals(HexFormat.of().parseHex("fff14c802f5ffc"), Adts.header(371));
        assertArrayEquals(HexFormat.of().parseHex("fff14c83fffffc"), Adts.header(Adts.MAX_PAYLOAD));
    }

    @Test
    void wrapAndOffsets() {
        byte[] frame = {1, 2, 3, 4, 5};
        byte[] w = Adts.wrap(frame);
        assertArrayEquals(Adts.header(5), Arrays.copyOf(w, 7));
        assertArrayEquals(frame, Arrays.copyOfRange(w, 7, 12));
        assertArrayEquals(Adts.wrap(new byte[] {2, 3}), Adts.wrap(frame, 1, 2));
        byte[] dst = new byte[10];
        Arrays.fill(dst, (byte) 0x55);
        Adts.writeHeader(dst, 2, 5);
        assertArrayEquals(Adts.header(5), Arrays.copyOfRange(dst, 2, 9));
        assertEquals(0x55, dst[0]);
        assertEquals(0x55, dst[9]);
    }

    @Test
    void rejectsPayloadsThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> Adts.header(0));
        assertThrows(IllegalArgumentException.class, () -> Adts.header(-1));
        assertThrows(IllegalArgumentException.class, () -> Adts.header(Adts.MAX_PAYLOAD + 1));
        assertEquals(8184, Adts.MAX_PAYLOAD);
    }

    @Test
    void samplingFrequencyIndexTable() {
        assertEquals(3, Adts.samplingFrequencyIndex(48000));
        assertEquals(4, Adts.samplingFrequencyIndex(44100));
        assertEquals(12, Adts.samplingFrequencyIndex(7350));
        assertThrows(IllegalArgumentException.class, () -> Adts.samplingFrequencyIndex(47999));
    }

    /** Our headers are byte-identical to what ffmpeg's ADTS muxer writes for the profile's AAC. */
    @Test
    @Timeout(60)
    void matchesFfmpegsAdtsMuxer() throws Exception {
        Tools tools = AnnexBTest.tools();
        Path m4a = dir.resolve("audio.m4a");
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i",
                "anoisesrc=r=44100:a=0.5", "-t", "3"));
        enc.addAll(EncodeProfile.AUDIO_ARGS);
        enc.add(m4a.toString());
        tools.ffmpeg(enc).run();
        byte[] adts = tools.ffmpeg("-v", "error", "-i", m4a.toString(), "-c:a", "copy", "-f", "adts", "pipe:1")
                .stdoutToString(1 << 22).run().stdout();
        int pos = 0;
        int frames = 0;
        java.util.Set<Integer> lengths = new java.util.HashSet<>();
        while (pos < adts.length) {
            int frameLength = ((adts[pos + 3] & 0x03) << 11) | ((adts[pos + 4] & 0xFF) << 3)
                    | ((adts[pos + 5] & 0xFF) >>> 5);
            int payload = frameLength - Adts.HEADER_SIZE;
            assertArrayEquals(Arrays.copyOfRange(adts, pos, pos + frameLength),
                    Adts.wrap(adts, pos + Adts.HEADER_SIZE, payload), "frame " + frames);
            lengths.add(payload);
            pos += frameLength;
            frames++;
        }
        assertEquals(adts.length, pos);
        assertTrue(frames > 100, "frames " + frames);
        assertTrue(lengths.size() > 1, "frame sizes vary: " + lengths.size());
    }
}
