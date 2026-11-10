package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.SampleIndex.IndexFormatException;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleIndexTest {
    @TempDir
    Path dir;

    static final byte[] AVCC = HexFormat.of().parseHex("01640028ffe1000467640028");

    /** Video: 4 frames at 15360 Hz, IDR on 0 and 2; audio: 3 AAC frames at 48 kHz. */
    static SampleIndex sample() {
        Track video = new Track.Builder(Kind.VIDEO, 15360, 0)
                .add(48, 3000, 0, 512, 1)
                .add(3048, 400, 512, 512, 0)
                .add(3448, 2900, 1024, 512, 1)
                .add(6348, 350, 1536, 512, 0)
                .build();
        Track audio = new Track.Builder(Kind.AUDIO, 48000, 1)
                .add(44, 397, 0, 1024, 1)
                .add(441, 287, 1024, 1024, 1)
                .add(6_000_000_000L, 319, 2048, 640, 1)
                .build();
        return new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P720_30), List.of("mezz.mp4", "audio.m4a"), List.of(video, audio), AVCC);
    }

    @Test
    void roundTrip() throws Exception {
        SampleIndex a = sample();
        Path file = dir.resolve(SampleIndex.FILE_NAME);
        a.write(file);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(SampleIndex.FILE_NAME), files.map(p -> p.getFileName().toString()).toList());
        }
        SampleIndex b = SampleIndex.read(file);
        assertEquals(OutputEncoding.defaultFor(OutputProfile.P720_30), b.encoding());
        assertEquals(List.of("mezz.mp4", "audio.m4a"), b.files());
        assertArrayEquals(AVCC, b.avcC());
        assertEquals(2, b.tracks().size());
        for (int t = 0; t < 2; t++) {
            Track x = a.tracks().get(t);
            Track y = b.tracks().get(t);
            assertEquals(x.kind(), y.kind());
            assertEquals(x.timescale(), y.timescale());
            assertEquals(x.fileRef(), y.fileRef());
            assertEquals(x.sampleCount(), y.sampleCount());
            for (int i = 0; i < x.sampleCount(); i++) {
                assertEquals(x.offset(i), y.offset(i));
                assertEquals(x.size(i), y.size(i));
                assertEquals(x.pts(i), y.pts(i));
                assertEquals(x.duration(i), y.duration(i));
                assertEquals(x.flags(i), y.flags(i));
            }
        }
        assertEquals("audio.m4a", b.file(b.track(Kind.AUDIO)));
        assertEquals(6_000_000_000L, b.track(Kind.AUDIO).offset(2));
        assertArrayEquals(a.toBytes(), b.toBytes());
    }

    @Test
    void byteLayoutFollowsTheDoc() {
        byte[] bytes = sample().toBytes();
        ByteBuffer in = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[4];
        in.get(magic);
        assertArrayEquals("MSIX".getBytes(), magic);
        assertEquals(2, in.getShort()); // version
        assertEquals(1, in.getShort()); // 720p30
        assertEquals(3000, in.getInt()); // kb/s
        assertEquals(2, in.get()); // files
        assertEquals(8, in.getShort());
        in.position(in.position() + 8);
        assertEquals(9, in.getShort());
        in.position(in.position() + 9);
        assertEquals(2, in.get()); // tracks
        assertEquals(0, in.get()); // video
        assertEquals(15360, in.getInt());
        assertEquals(0, in.get()); // file-ref
        assertEquals(4, in.getInt());
        assertEquals(48, in.getLong());
        assertEquals(3000, in.getInt());
        assertEquals(0, in.getLong());
        assertEquals(512, in.getInt());
        assertEquals(1, in.get());
        in.position(in.position() + 3 * SampleIndex.SAMPLE_BYTES);
        assertEquals(1, in.get()); // audio
        assertEquals(48000, in.getInt());
        assertEquals(1, in.get());
        assertEquals(3, in.getInt());
        in.position(in.position() + 3 * SampleIndex.SAMPLE_BYTES);
        assertEquals(AVCC.length, in.getShort());
        byte[] avcC = new byte[AVCC.length];
        in.get(avcC);
        assertArrayEquals(AVCC, avcC);
        assertFalse(in.hasRemaining());
        assertEquals(4 + 2 + 2 + 4 + 1 + 2 + 8 + 2 + 9 + 1 + 2 * 10 + 7 * 25 + 2 + AVCC.length, bytes.length);
    }

    @Test
    void profileCodesAndBitrate() throws Exception {
        assertEquals(1, SampleIndex.profileCode(OutputProfile.P720_30));
        assertEquals(2, SampleIndex.profileCode(OutputProfile.P1080_30));
        OutputEncoding encoding = new OutputEncoding(OutputProfile.P1080_30, 4500);
        SampleIndex a = new SampleIndex(encoding, List.of("card.mp4"), List.of(), new byte[0]);
        SampleIndex b = SampleIndex.parse(a.toBytes());
        assertEquals(encoding, b.encoding());
        assertEquals(4500, b.encoding().videoBitrateKbps());
        assertEquals(0, b.avcC().length);
        assertNull(b.track(Kind.VIDEO));
    }

    @Test
    void lookups() {
        Track v = sample().track(Kind.VIDEO);
        assertEquals(-1, v.sampleAtOrBefore(-1));
        assertEquals(0, v.sampleAtOrBefore(0));
        assertEquals(0, v.sampleAtOrBefore(511));
        assertEquals(1, v.sampleAtOrBefore(512));
        assertEquals(3, v.sampleAtOrBefore(1_000_000));
        assertEquals(2048, v.endPts());
        assertTrue(v.isKeyframe(0));
        assertFalse(v.isKeyframe(1));
        assertEquals(0, v.keyframeAtOrBefore(1));
        assertEquals(2, v.keyframeAtOrBefore(3));
        assertEquals(2, v.keyframeAtOrAfter(1));
        assertEquals(-1, v.keyframeAtOrAfter(3));
        assertEquals(0, new Track.Builder(Kind.AUDIO, 48000, 0).build().endPts());
    }

    @Test
    void avcCIsCopied() {
        SampleIndex a = sample();
        a.avcC()[0] = 9;
        assertArrayEquals(AVCC, a.avcC());
    }

    @Test
    void builderRejectsBadSamples() {
        Track.Builder b = new Track.Builder(Kind.VIDEO, 15360, 0).add(0, 10, 100, 512, 1);
        assertThrows(IllegalArgumentException.class, () -> b.add(10, 10, 100, 512, 0)); // same pts
        assertThrows(IllegalArgumentException.class, () -> b.add(10, 10, 50, 512, 0)); // earlier pts
        assertThrows(IllegalArgumentException.class, () -> b.add(-1, 10, 200, 512, 0));
        assertThrows(IllegalArgumentException.class, () -> b.add(10, -1, 200, 512, 0));
        assertThrows(IllegalArgumentException.class, () -> b.add(10, 1L << 31, 200, 512, 0));
        assertThrows(IllegalArgumentException.class, () -> b.add(10, 10, 200, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> b.add(10, 10, 200, 512, 256));
        assertEquals(1, b.count());
        assertThrows(IllegalArgumentException.class, () -> new Track.Builder(Kind.VIDEO, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Track.Builder(Kind.VIDEO, 1L << 32, 0));
        assertThrows(IllegalArgumentException.class, () -> new Track.Builder(Kind.VIDEO, 90000, 255));
        // grows past its initial capacity
        Track.Builder big = new Track.Builder(Kind.AUDIO, 48000, 0);
        for (int i = 0; i < 5000; i++) {
            big.add(i * 300L, 300, i * 1024L, 1024, 1);
        }
        Track t = big.build();
        assertEquals(5000, t.sampleCount());
        assertEquals(4999 * 1024L, t.pts(4999));
    }

    @Test
    void constructorRejectsBadTables() {
        Track t = new Track.Builder(Kind.VIDEO, 15360, 1).build();
        assertThrows(IllegalArgumentException.class,
                () -> new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P720_30), List.of("mezz.mp4"), List.of(t), AVCC));
        for (String name : List.of("", ".", "..", "a/b", "a\\b", "../mezz.mp4")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P720_30), List.of(name), List.of(), AVCC), name);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P720_30), List.of("a"), List.of(), new byte[0x10000]));
    }

    @Test
    void parseRejectsMalformedFiles() {
        byte[] good = sample().toBytes();
        assertBad(Arrays.copyOf(new byte[0], 0), "truncated");
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertBad(magic, "bad magic");
        byte[] version = good.clone();
        version[5] = 3;
        assertBad(version, "version 3");
        // version 1 (before CBR, no bitrate): not playable until re-encoded
        byte[] v1 = new byte[good.length - 4];
        System.arraycopy(good, 0, v1, 0, 8);
        System.arraycopy(good, 12, v1, 8, good.length - 12);
        v1[5] = 1;
        assertBad(v1, "unsupported index version 1");
        byte[] rate = good.clone();
        ByteBuffer.wrap(rate).putInt(8, 0);
        assertBad(rate, "bad video bitrate 0");
        ByteBuffer.wrap(rate).putInt(8, -1);
        assertBad(rate, "bad video bitrate 4294967295");
        byte[] profile = good.clone();
        profile[7] = 9;
        assertBad(profile, "profile code 9");
        assertBad(Arrays.copyOf(good, good.length - 1), "truncated");
        assertBad(Arrays.copyOf(good, good.length + 1), "1 trailing bytes");

        int videoHeader = 4 + 2 + 2 + 4 + 1 + 2 + 8 + 2 + 9 + 1;
        byte[] kind = good.clone();
        kind[videoHeader] = 7;
        assertBad(kind, "track kind 7");
        byte[] timescale = good.clone();
        Arrays.fill(timescale, videoHeader + 1, videoHeader + 5, (byte) 0);
        assertBad(timescale, "timescale 0");
        byte[] fileRef = good.clone();
        fileRef[videoHeader + 5] = 2;
        assertBad(fileRef, "file-ref 2 of 2");
        byte[] count = good.clone();
        ByteBuffer.wrap(count).putInt(videoHeader + 6, 0x7FFFFFFF);
        assertBad(count, "don't fit");
        byte[] order = good.clone();
        // second video sample's pts (offset 8 + size 4 into the record) back to 0
        ByteBuffer.wrap(order).putLong(videoHeader + 10 + SampleIndex.SAMPLE_BYTES + 12, 0);
        assertBad(order, "not after the previous");
        byte[] size = good.clone();
        ByteBuffer.wrap(size).putInt(videoHeader + 10 + 8, -1);
        assertBad(size, "size out of range");
        byte[] name = good.clone();
        name[4 + 2 + 2 + 4 + 1 + 2] = '/';
        assertBad(name, "bad file name");
        byte[] utf8 = good.clone();
        utf8[4 + 2 + 2 + 4 + 1 + 2] = (byte) 0xC3;
        assertBad(utf8, "bad file name");
    }

    @Test
    void readNamesTheFile() throws Exception {
        Path file = dir.resolve("index.bin");
        Files.write(file, new byte[] {'M', 'S', 'I', 'X', 0, 2});
        IndexFormatException e = assertThrows(IndexFormatException.class, () -> SampleIndex.read(file));
        assertTrue(e.getMessage().startsWith(file.toString()), e.getMessage());
    }

    @Test
    void writeReplacesAnExistingIndex() throws Exception {
        Path file = dir.resolve("index.bin");
        Files.write(file, new byte[] {1, 2, 3});
        sample().write(file);
        assertArrayEquals(sample().toBytes(), Files.readAllBytes(file));
    }

    private static void assertBad(byte[] data, String message) {
        IndexFormatException e = assertThrows(IndexFormatException.class, () -> SampleIndex.parse(data));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
