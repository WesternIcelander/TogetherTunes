package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.media.EncodeProfile;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SilenceFrameTest {
    @TempDir
    Path dir;

    /** An ADTS header for {@code payload}: AAC-LC, 48 kHz, stereo, one raw block, VBR fullness. */
    static byte[] adts(byte[] payload, int profile, int sfIndex, int channels, boolean crc) {
        int header = crc ? 9 : 7;
        int len = header + payload.length;
        byte[] out = new byte[len];
        out[0] = (byte) 0xFF;
        out[1] = (byte) (0xF0 | (crc ? 0 : 1));
        out[2] = (byte) ((profile << 6) | (sfIndex << 2) | (channels >> 2));
        out[3] = (byte) (((channels & 3) << 6) | (len >> 11));
        out[4] = (byte) (len >> 3);
        out[5] = (byte) (((len & 7) << 5) | 0x1F);
        out[6] = (byte) 0xFC;
        System.arraycopy(payload, 0, out, header, payload.length);
        return out;
    }

    static byte[] adts(byte[] payload) {
        return adts(payload, 1, 3, 2, false);
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void commandUsesTheSharedAudioArguments() {
        List<String> cmd = SilenceFrame.command();
        assertTrue(Collections.indexOfSubList(cmd, EncodeProfile.AUDIO_ARGS) > 0, cmd.toString());
        assertTrue(cmd.contains("anullsrc=r=48000:cl=stereo"), cmd.toString());
        assertEquals(List.of("-f", "adts", "pipe:1"), cmd.subList(cmd.size() - 3, cmd.size()));
        assertEquals(List.of("-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"), EncodeProfile.AUDIO_ARGS);
    }

    @Test
    void parsesAdtsWithAndWithoutCrc() {
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6, 7, 8};
        byte[] stream = concat(List.of(adts(a), adts(b, 1, 3, 2, true), adts(new byte[0])));
        List<byte[]> frames = SilenceFrame.parseAdts(stream);
        assertEquals(3, frames.size());
        assertArrayEquals(a, frames.get(0));
        assertArrayEquals(b, frames.get(1));
        assertArrayEquals(new byte[0], frames.get(2));
    }

    @Test
    void rejectsOtherAudioConfigsAndBrokenStreams() {
        byte[] p = {1, 2, 3};
        assertThrows(IllegalStateException.class, () -> SilenceFrame.parseAdts(adts(p, 0, 3, 2, false)), "Main");
        assertThrows(IllegalStateException.class, () -> SilenceFrame.parseAdts(adts(p, 1, 4, 2, false)), "44.1 kHz");
        assertThrows(IllegalStateException.class, () -> SilenceFrame.parseAdts(adts(p, 1, 3, 1, false)), "mono");
        byte[] ok = adts(p);
        byte[] twoBlocks = ok.clone();
        twoBlocks[6] |= 1;
        assertThrows(IllegalStateException.class, () -> SilenceFrame.parseAdts(twoBlocks));
        byte[] noSync = ok.clone();
        noSync[1] = 0x01;
        assertThrows(IllegalStateException.class, () -> SilenceFrame.parseAdts(noSync));
        assertThrows(IllegalStateException.class,
                () -> SilenceFrame.parseAdts(java.util.Arrays.copyOf(ok, ok.length - 1)), "truncated payload");
        assertThrows(IllegalStateException.class,
                () -> SilenceFrame.parseAdts(java.util.Arrays.copyOf(ok, 5)), "truncated header");
    }

    @Test
    void dropsThePrimingAndTheEdgesAndRequiresASteadyState() {
        byte[] priming = {9, 9, 9, 9};
        byte[] first = {7, 7};
        byte[] silence = {0x21, 0x10, 0x04, 0x60, (byte) 0x8C, 0x1C};
        byte[] last = {5};
        java.util.ArrayList<byte[]> parts = new java.util.ArrayList<>();
        parts.add(adts(priming));
        parts.add(adts(first));
        for (int i = 0; i < SilenceFrame.ENCODED_FRAMES - 2; i++) {
            parts.add(adts(silence));
        }
        parts.add(adts(last));
        assertArrayEquals(silence, SilenceFrame.extract(concat(parts)));

        // Too few frames.
        assertThrows(IllegalStateException.class, () -> SilenceFrame.extract(concat(parts.subList(0, 5))));
        // A middle frame differs.
        java.util.ArrayList<byte[]> odd = new java.util.ArrayList<>(parts);
        odd.set(6, adts(new byte[] {1, 2, 3}));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SilenceFrame.extract(concat(odd)));
        assertTrue(e.getMessage().contains("differ"), e.getMessage());
    }

    @Test
    @Timeout(60)
    void generatesAFrameThatDecodesToSilence() throws Exception {
        Tools tools = CardEncoderTest.tools();
        SilenceFrame silence = SilenceFrame.generate(tools, null);
        assertTrue(silence.length() > 0 && silence.length() < 64, silence.toString());
        assertEquals(silence, SilenceFrame.generate(tools, null), "deterministic");

        // Repeat it with the packager's fixed ADTS header and decode: exactly 1024 zero samples
        // per channel per frame.
        int repeats = 50;
        byte[] frame = silence.frame();
        java.util.ArrayList<byte[]> parts = new java.util.ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            parts.add(adts(frame));
        }
        Path in = dir.resolve("silence.aac");
        Files.write(in, concat(parts));
        Path pcm = dir.resolve("silence.pcm");
        tools.ffmpeg("-v", "error", "-f", "aac", "-i", in.toString(), "-f", "s16le", "-acodec", "pcm_s16le",
                "-ac", "2", "-ar", "48000", "-y", pcm.toString()).timeout(java.time.Duration.ofSeconds(30)).run();
        byte[] samples = Files.readAllBytes(pcm);
        assertEquals(repeats * EncodeProfile.AAC_FRAME_SAMPLES * EncodeProfile.AUDIO_CHANNELS * 2, samples.length);
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] != 0) {
                throw new AssertionError("non-zero sample byte at " + i);
            }
        }
    }

    @Test
    void missingFfmpegFails() {
        Tools tools = new Tools("/nonexistent/ffmpeg", "/nonexistent/ffprobe", null);
        ToolException e = assertThrows(ToolException.class, () -> SilenceFrame.generate(tools, null));
        assertEquals(ToolException.Kind.START_FAILED, e.kind());
    }
}
