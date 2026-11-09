package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class IndexBuilderTest {
    @TempDir
    Path dir;

    static Track parse(Kind kind, String... lines) {
        IndexBuilder.PacketCsv csv = new IndexBuilder.PacketCsv();
        for (String l : lines) {
            csv.accept(l);
        }
        return csv.toTrack(kind, 0);
    }

    @Test
    void dropsDiscardAndNegativePtsPackets() {
        IndexBuilder.PacketCsv csv = new IndexBuilder.PacketCsv();
        for (String l : List.of(
                "packet,-1024,-1024,1024,303,44,KD_,", // AAC priming: D, negative pts, side data field
                "packet,-512,-512,512,10,347,K__", // negative pts without D
                "packet,0,0,1024,397,357,K__",
                "packet,1024,1024,1024,287,754,K__",
                "packet,2048,2048,1024,300,1041,KD_", // D with a positive pts
                "packet,3072,3072,640,333,1341,K__",
                "",
                "stream,1/48000")) {
            csv.accept(l);
        }
        assertEquals(3, csv.dropped());
        Track t = csv.toTrack(Kind.AUDIO, 1);
        assertEquals(Kind.AUDIO, t.kind());
        assertEquals(48000, t.timescale());
        assertEquals(1, t.fileRef());
        assertEquals(3, t.sampleCount());
        assertEquals(0, t.pts(0));
        assertEquals(357, t.offset(0));
        assertEquals(397, t.size(0));
        assertEquals(1024, t.duration(0));
        assertTrue(t.isKeyframe(0));
        assertEquals(3072, t.pts(2));
        assertEquals(640, t.duration(2));
    }

    @Test
    void keyframeFlagAndStreamLineAnywhere() {
        Track t = parse(Kind.VIDEO, "stream,1/15360", "packet,0,0,512,3388,351,K__", "packet,512,512,512,382,4423,___",
                "side_data,whatever");
        assertEquals(15360, t.timescale());
        assertEquals(SampleIndex.FLAG_KEYFRAME, t.flags(0));
        assertEquals(0, t.flags(1));
    }

    @Test
    void missingDurationComesFromTheNextPacket() {
        Track t = parse(Kind.VIDEO, "packet,0,0,N/A,10,0,K__", "packet,500,500,,10,10,___",
                "packet,1000,1000,512,10,20,___", "stream,1/15360");
        assertEquals(500, t.duration(0));
        assertEquals(500, t.duration(1));
        assertEquals(512, t.duration(2));
        assertThrows(IllegalArgumentException.class,
                () -> parse(Kind.VIDEO, "stream,1/15360", "packet,0,0,512,10,0,K__", "packet,512,512,N/A,10,10,___"));
    }

    @Test
    void rejectsBadInput() {
        assertBad("no video stream", Kind.VIDEO, "packet,0,0,512,10,0,K__");
        assertBad("no audio packets", Kind.AUDIO, "stream,1/48000", "packet,-1024,-1024,1024,303,44,KD_,");
        assertBad("time base", Kind.VIDEO, "stream,1001/30000");
        assertBad("time base", Kind.VIDEO, "stream,0/0");
        assertBad("time base", Kind.VIDEO, "stream,N/A");
        assertBad("more than one stream", Kind.VIDEO, "stream,1/15360", "stream,1/15360");
        assertBad("6 packet fields", Kind.VIDEO, "packet,0,0,512");
        assertBad("bad pts", Kind.VIDEO, "packet,x,0,512,10,0,K__");
        assertBad("without pts", Kind.VIDEO, "packet,N/A,0,512,10,0,K__");
        assertBad("without size or pos", Kind.VIDEO, "packet,0,0,512,10,N/A,K__");
        assertBad("line 3: pts 0 not after", Kind.VIDEO, "stream,1/15360",
                "packet,0,0,512,10,0,K__", "packet,0,0,512,10,10,___");
    }

    private static void assertBad(String message, Kind kind, String... lines) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parse(kind, lines));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void probeArguments() {
        assertEquals(List.of("-v", "error", "-select_streams", "a:0", "-show_entries",
                "stream=time_base:packet=pts,dts,duration,size,pos,flags", "-of", "csv", "x/audio.m4a"),
                IndexBuilder.probeArgs(Path.of("x/audio.m4a"), Kind.AUDIO));
        assertEquals("v:0", IndexBuilder.probeArgs(Path.of("mezz.mp4"), Kind.VIDEO).get(3));
    }

    @Test
    void filesMustBeNextToTheIndex() throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        assertThrows(IllegalArgumentException.class, () -> IndexBuilder.build(new Tools(null, null, null),
                OutputProfile.P720_30, dir.resolve("sub/mezz.mp4"), dir.resolve("audio.m4a"),
                dir.resolve("index.bin"), new JobLog()));
    }

    /**
     * A real mezzanine-style encode (5 s, 24 fps source → 30 fps profile) and a separate
     * {@code audio.m4a}: the index has 150 video frames with an IDR exactly every 2 s, the AAC
     * priming packet is gone, every sample's bytes at its offset are what ffmpeg itself extracts,
     * and the avcC matches the box walker's.
     */
    @Test
    @Timeout(120)
    void indexesARealVideoItem() throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputProfile profile = OutputProfile.P720_30;
        Path mezz = dir.resolve("mezz.mp4");
        Path audio = dir.resolve("audio.m4a");
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=24",
                "-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "5", "-vf", EncodeProfile.scaleFilter(profile)));
        enc.addAll(EncodeProfile.videoArgs(profile));
        enc.addAll(EncodeProfile.AUDIO_ARGS);
        enc.add(mezz.toString());
        tools.ffmpeg(enc).run();
        List<String> aud = new ArrayList<>(List.of("-v", "error", "-i", mezz.toString(), "-vn"));
        aud.addAll(EncodeProfile.AUDIO_ARGS);
        aud.add(audio.toString());
        tools.ffmpeg(aud).run();

        Path out = dir.resolve(SampleIndex.FILE_NAME);
        SampleIndex built = IndexBuilder.build(tools, profile, mezz, audio, out, new JobLog());
        SampleIndex index = SampleIndex.read(out);
        assertArrayEquals(built.toBytes(), index.toBytes());
        assertEquals(profile, index.profile());
        assertEquals(List.of("mezz.mp4", "audio.m4a"), index.files());
        assertArrayEquals(Mp4Boxes.readAvcC(mezz), index.avcC());

        Track v = index.track(Kind.VIDEO);
        assertEquals("mezz.mp4", index.file(v));
        assertEquals(150, v.sampleCount());
        long frame = v.timescale() / 30;
        assertEquals(0, v.timescale() % 30);
        for (int i = 0; i < v.sampleCount(); i++) {
            assertEquals(i * frame, v.pts(i), "pts " + i);
            assertEquals(frame, v.duration(i), "duration " + i);
            assertEquals(i % EncodeProfile.GOP_FRAMES == 0, v.isKeyframe(i), "keyframe " + i);
        }
        // every video sample is a run of 4-byte length-prefixed NAL units filling it exactly
        try (FileChannel ch = FileChannel.open(mezz)) {
            for (int i = 0; i < v.sampleCount(); i++) {
                ByteBuffer b = read(ch, v.offset(i), v.size(i));
                while (b.hasRemaining()) {
                    int len = b.getInt();
                    assertTrue(len > 0 && len <= b.remaining(), "NAL length in sample " + i);
                    if (b.position() == 4) {
                        int type = b.get(b.position()) & 0x1F;
                        // first NAL: IDR (5) or SEI (6) on keyframes, non-IDR slice (1) otherwise
                        assertTrue(v.isKeyframe(i) ? type == 5 || type == 6 : type == 1, "NAL type " + type);
                    }
                    b.position(b.position() + len);
                }
            }
        }

        Track a = index.track(Kind.AUDIO);
        assertEquals("audio.m4a", index.file(a));
        assertEquals(EncodeProfile.AUDIO_SAMPLE_RATE, a.timescale());
        assertEquals(0, a.pts(0));
        for (int i = 0; i < a.sampleCount(); i++) {
            assertTrue(a.isKeyframe(i));
            assertEquals(i * (long) EncodeProfile.AAC_FRAME_SAMPLES, a.pts(i));
        }
        long total = a.endPts();
        assertTrue(Math.abs(total - 5 * 48000) < 1024, "audio length " + total);
        // ffmpeg's own stream copy to ADTS = the priming frame followed by exactly our samples
        List<byte[]> adts = adtsFrames(tools.ffmpeg("-v", "error", "-i", audio.toString(), "-c:a", "copy",
                "-f", "adts", "pipe:1").stdoutToString(1 << 24).run().stdout());
        assertEquals(a.sampleCount() + 1, adts.size());
        try (FileChannel ch = FileChannel.open(audio)) {
            for (int i = 0; i < a.sampleCount(); i++) {
                assertArrayEquals(adts.get(i + 1), read(ch, a.offset(i), a.size(i)).array(), "audio sample " + i);
            }
        }
    }

    /** An audio item: the card is the video file; one 2 s GOP. */
    @Test
    @Timeout(120)
    void indexesAnAudioItemWithItsCard() throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputProfile profile = OutputProfile.P720_30;
        Path card = dir.resolve("card.mp4");
        Path audio = dir.resolve("audio.m4a");
        CardEncoder.encodePng(tools, writeBlackPng(), card, profile, new JobLog());
        List<String> aud = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "sine=r=44100", "-t", "1"));
        aud.addAll(EncodeProfile.AUDIO_ARGS);
        aud.add(audio.toString());
        tools.ffmpeg(aud).run();

        SampleIndex index = IndexBuilder.build(tools, profile, card, audio, dir.resolve("index.bin"), new JobLog());
        assertEquals(List.of("card.mp4", "audio.m4a"), index.files());
        Track v = index.track(Kind.VIDEO);
        assertEquals(EncodeProfile.GOP_FRAMES, v.sampleCount());
        assertTrue(v.isKeyframe(0));
        assertEquals(-1, v.keyframeAtOrAfter(1));
        assertEquals(2 * v.timescale(), v.endPts());
        assertArrayEquals(Mp4Boxes.readAvcC(card), index.avcC());
        assertEquals(0, index.track(Kind.AUDIO).pts(0));
    }

    @Test
    void missingAudioStreamFails() throws Exception {
        Tools tools = CardEncoderTest.tools();
        Path card = dir.resolve("card.mp4");
        CardEncoder.encodePng(tools, writeBlackPng(), card, OutputProfile.P720_30, new JobLog());
        IOException e = assertThrows(IOException.class, () -> IndexBuilder.build(tools, OutputProfile.P720_30,
                card, card, dir.resolve("index.bin"), new JobLog()));
        assertTrue(e.getMessage().contains("no audio stream"), e.getMessage());
        assertTrue(Files.notExists(dir.resolve("index.bin")));
    }

    private Path writeBlackPng() throws IOException {
        Path png = dir.resolve("card.png");
        ReferenceCard.writeBlackPng(png, OutputProfile.P720_30);
        return png;
    }

    private static ByteBuffer read(FileChannel ch, long offset, int size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(size);
        while (b.hasRemaining()) {
            if (ch.read(b, offset + b.position()) < 0) {
                throw new IOException("EOF");
            }
        }
        return b.flip();
    }

    /** Raw AAC payloads of an ADTS stream (headers, with or without CRC, removed). */
    private static List<byte[]> adtsFrames(byte[] d) {
        List<byte[]> frames = new ArrayList<>();
        int i = 0;
        while (i < d.length) {
            int len = ((d[i + 3] & 3) << 11) | ((d[i + 4] & 0xFF) << 3) | ((d[i + 5] & 0xFF) >> 5);
            int header = (d[i + 1] & 1) == 0 ? 9 : 7;
            frames.add(Arrays.copyOfRange(d, i + header, i + len));
            i += len;
        }
        return frames;
    }
}
