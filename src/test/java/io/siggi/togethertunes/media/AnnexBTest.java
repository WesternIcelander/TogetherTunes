package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AnnexB.H264FormatException;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class AnnexBTest {
    @TempDir
    Path dir;

    private static final HexFormat HEX = HexFormat.of();
    /** A real 720p30 avcC: 4-byte lengths, one 24-byte SPS, one 4-byte PPS. */
    private static final byte[] AVCC = HEX.parseHex("0164001fffe100186764001facb402802dd80880000003"
            + "008000001e078c19500100" + "0468ef0fcbfdf8f800");
    private static final byte[] SPS = HEX.parseHex("6764001facb402802dd80880000003008000001e078c1950");
    private static final byte[] PPS = HEX.parseHex("68ef0fcb");
    private static final byte[] AUD = HEX.parseHex("0000000109f0");
    private static final byte[] SC = HEX.parseHex("00000001");

    private static final byte[] SEI = HEX.parseHex("0605ff00112233");
    private static final byte[] IDR = HEX.parseHex("65888400aabbcc");
    private static final byte[] SLICE = HEX.parseHex("419a0011");

    /** NAL units behind {@code lengthSize}-byte length prefixes. */
    private static byte[] avcc(int lengthSize, byte[]... nals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            for (int i = lengthSize - 1; i >= 0; i--) {
                out.write(nal.length >>> (8 * i));
            }
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] avcC(int lengthSize, List<byte[]> sps, List<byte[]> pps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 0x64, 0, 0x1F, (byte) (0xFC | (lengthSize - 1)), (byte) (0xE0 | sps.size())});
        for (byte[] s : sps) {
            out.write(s.length >>> 8);
            out.write(s.length);
            out.writeBytes(s);
        }
        out.write(pps.size());
        for (byte[] p : pps) {
            out.write(p.length >>> 8);
            out.write(p.length);
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void parsesARealAvcC() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        assertEquals(4, a.nalLengthSize());
        assertEquals(1, a.sps().size());
        assertArrayEquals(SPS, a.sps().get(0));
        assertEquals(1, a.pps().size());
        assertArrayEquals(PPS, a.pps().get(0));
    }

    @Test
    void idrGetsAudThenSpsPpsThenItsNals() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        byte[] sample = avcc(4, SEI, IDR);
        assertTrue(a.isIdr(sample, 0, sample.length));
        assertArrayEquals(concat(AUD, SC, SPS, SC, PPS, SC, SEI, SC, IDR), a.accessUnit(sample));
    }

    @Test
    void nonIdrGetsOnlyAud() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        byte[] sample = avcc(4, SLICE);
        assertFalse(a.isIdr(sample, 0, sample.length));
        assertArrayEquals(concat(AUD, SC, SLICE), a.accessUnit(sample));
    }

    @Test
    void inBandAudSpsPpsAreReplaced() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        byte[] otherSps = HEX.parseHex("67640028aa");
        byte[] otherPps = HEX.parseHex("68ee3c80");
        byte[] sample = avcc(4, HEX.parseHex("0910"), otherSps, otherPps, SEI, IDR);
        assertArrayEquals(concat(AUD, SC, SPS, SC, PPS, SC, SEI, SC, IDR), a.accessUnit(sample));
        byte[] p = avcc(4, HEX.parseHex("0930"), SLICE);
        assertArrayEquals(concat(AUD, SC, SLICE), a.accessUnit(p));
    }

    @Test
    void honoursOffsetAndLength() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        byte[] sample = avcc(4, SLICE);
        byte[] padded = concat(HEX.parseHex("deadbeef"), sample, HEX.parseHex("ffff"));
        assertArrayEquals(concat(AUD, SC, SLICE), a.accessUnit(padded, 4, sample.length));
    }

    @Test
    void oneAndTwoByteLengthPrefixesAndSeveralParameterSets() throws Exception {
        byte[] sps2 = HEX.parseHex("6742c01e");
        byte[] pps2 = HEX.parseHex("68ce3c80");
        for (int lengthSize : new int[] {1, 2, 4}) {
            AnnexB a = new AnnexB(avcC(lengthSize, List.of(SPS, sps2), List.of(PPS, pps2)));
            assertEquals(lengthSize, a.nalLengthSize());
            assertEquals(2, a.sps().size());
            assertArrayEquals(concat(AUD, SC, SPS, SC, sps2, SC, PPS, SC, pps2, SC, IDR, SC, IDR),
                    a.accessUnit(avcc(lengthSize, IDR, IDR)), "length size " + lengthSize);
            assertArrayEquals(concat(AUD, SC, SLICE), a.accessUnit(avcc(lengthSize, SLICE)));
        }
    }

    @Test
    void returnedParameterSetsAreCopies() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        a.sps().get(0)[1] = 0;
        a.pps().get(0)[1] = 0;
        assertArrayEquals(SPS, a.sps().get(0));
        assertArrayEquals(PPS, a.pps().get(0));
        assertArrayEquals(concat(AUD, SC, SPS, SC, PPS, SC, IDR), a.accessUnit(avcc(4, IDR)));
    }

    @Test
    void rejectsMalformedSamples() throws Exception {
        AnnexB a = new AnnexB(AVCC);
        assertThrows(H264FormatException.class, () -> a.accessUnit(new byte[0]), "empty");
        assertThrows(H264FormatException.class, () -> a.accessUnit(avcc(4, SEI)), "no slice");
        assertThrows(H264FormatException.class, () -> a.accessUnit(HEX.parseHex("000000")), "short prefix");
        assertThrows(H264FormatException.class, () -> a.accessUnit(concat(avcc(4, SLICE), HEX.parseHex("00000000"))),
                "zero length");
        assertThrows(H264FormatException.class, () -> a.accessUnit(HEX.parseHex("00000005419a00")), "overrun");
        assertThrows(H264FormatException.class, () -> a.accessUnit(HEX.parseHex("ffffffff419a00")), "huge length");
        byte[] s = avcc(4, SLICE);
        assertThrows(H264FormatException.class, () -> a.accessUnit(s, 0, s.length - 1), "cut by len");
        assertThrows(H264FormatException.class, () -> a.isIdr(HEX.parseHex("00000005419a00"), 0, 7));
    }

    @Test
    void rejectsMalformedAvcC() {
        assertThrows(H264FormatException.class, () -> new AnnexB(new byte[0]));
        byte[] v2 = AVCC.clone();
        v2[0] = 2;
        assertThrows(H264FormatException.class, () -> new AnnexB(v2), "version");
        byte[] len3 = AVCC.clone();
        len3[4] = (byte) 0xFE;
        assertThrows(H264FormatException.class, () -> new AnnexB(len3), "length size 3");
        assertThrows(H264FormatException.class, () -> new AnnexB(avcC(4, List.of(), List.of(PPS))), "no SPS");
        assertThrows(H264FormatException.class, () -> new AnnexB(avcC(4, List.of(SPS), List.of())), "no PPS");
        assertThrows(H264FormatException.class, () -> new AnnexB(avcC(4, List.of(PPS), List.of(PPS))), "SPS type");
        assertThrows(H264FormatException.class, () -> new AnnexB(avcC(4, List.of(SPS), List.of(SPS))), "PPS type");
        for (int cut = 7; cut < AVCC.length - 4; cut++) {
            byte[] t = java.util.Arrays.copyOf(AVCC, cut);
            assertThrows(H264FormatException.class, () -> new AnnexB(t), "truncated at " + cut);
        }
    }

    // ---- against a real encode ----

    private static String onPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String candidate : List.of(entry, "/opt/homebrew/bin", "/usr/local/bin")) {
                Path p = Path.of(candidate, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    static Tools tools() {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        return new Tools(ffmpeg, ffprobe, null);
    }

    /**
     * A profile encode (5 s, so three IDRs) converted sample by sample: the Annex B stream
     * decodes to exactly the same frames as the MP4, starts every access unit with an AUD, and
     * has SPS/PPS before every IDR and nowhere else.
     */
    @Test
    @Timeout(120)
    void realEncodeDecodesToTheSameFrames() throws Exception {
        Tools tools = tools();
        OutputProfile profile = OutputProfile.P720_30;
        Path mezz = dir.resolve("mezz.mp4");
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30",
                "-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "5", "-vf", EncodeProfile.scaleFilter(profile)));
        enc.addAll(EncodeProfile.videoArgs(profile));
        enc.addAll(EncodeProfile.AUDIO_ARGS);
        enc.add(mezz.toString());
        tools.ffmpeg(enc).run();
        SampleIndex index = IndexBuilder.build(tools, profile, mezz, mezz, dir.resolve("index.bin"), new JobLog());
        AnnexB annexB = new AnnexB(index.avcC());
        Track v = index.track(Kind.VIDEO);
        assertEquals(150, v.sampleCount());

        Path h264 = dir.resolve("out.h264");
        int idrs = 0;
        try (FileChannel in = FileChannel.open(mezz); FileChannel out = FileChannel.open(h264,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            for (int i = 0; i < v.sampleCount(); i++) {
                ByteBuffer b = ByteBuffer.allocate(v.size(i));
                while (b.hasRemaining() && in.read(b, v.offset(i) + b.position()) > 0) {
                    // read fully
                }
                byte[] sample = b.array();
                assertEquals(v.isKeyframe(i), annexB.isIdr(sample, 0, sample.length), "IDR flag " + i);
                byte[] au = annexB.accessUnit(sample);
                assertArrayEquals(AUD, java.util.Arrays.copyOf(au, AUD.length), "AUD first in " + i);
                List<Integer> types = nalTypes(au);
                assertEquals(AnnexB.NAL_AUD, types.get(0));
                assertEquals(1, types.stream().filter(t -> t == AnnexB.NAL_AUD).count());
                if (v.isKeyframe(i)) {
                    idrs++;
                    assertEquals(List.of(AnnexB.NAL_AUD, AnnexB.NAL_SPS, AnnexB.NAL_PPS), types.subList(0, 3),
                            "IDR " + i);
                } else {
                    assertFalse(types.contains(AnnexB.NAL_SPS) || types.contains(AnnexB.NAL_PPS), "sample " + i);
                }
                out.write(ByteBuffer.wrap(au));
            }
        }
        assertEquals(3, idrs);

        String fromMp4 = framemd5(tools, "-i", mezz.toString(), "-map", "0:v");
        String fromAnnexB = framemd5(tools, "-f", "h264", "-i", h264.toString());
        assertEquals(150, fromMp4.lines().filter(l -> !l.startsWith("#")).count());
        assertEquals(fromMp4, fromAnnexB);
    }

    /** Frame hashes only (the columns after pts/duration), so timestamps don't matter. */
    private static String framemd5(Tools tools, String... input) throws Exception {
        List<String> args = new ArrayList<>(List.of("-v", "error"));
        args.addAll(List.of(input));
        args.addAll(List.of("-f", "framemd5", "pipe:1"));
        String out = new String(tools.ffmpeg(args).stdoutToString(1 << 22).run().stdout(), StandardCharsets.UTF_8);
        StringBuilder hashes = new StringBuilder();
        for (String line : out.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",");
            hashes.append(f[f.length - 2].trim()).append(',').append(f[f.length - 1].trim()).append('\n');
        }
        return hashes.toString();
    }

    /** NAL types of an Annex B buffer that uses 4-byte start codes. */
    private static List<Integer> nalTypes(byte[] au) {
        List<Integer> types = new ArrayList<>();
        for (int i = 0; i + 4 < au.length; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                types.add(au[i + 4] & 0x1F);
                i += 3;
            }
        }
        return types;
    }
}
