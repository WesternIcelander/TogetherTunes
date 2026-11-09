package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.Tools;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
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

class Mp4BoxesTest {
    @TempDir
    Path dir;

    /** A real 720p30 avcC (43 bytes). */
    private static final byte[] AVCC = HexFormat.of().parseHex("0164001fffe100186764001facb402802dd80880000003"
            + "008000001e078c19500100" + "0468ef0fcbfdf8f800");

    // ---- synthetic files ----

    private static byte[] box(String type, byte[]... payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : payload) {
            body.writeBytes(p);
        }
        ByteBuffer b = ByteBuffer.allocate(8 + body.size());
        b.putInt(8 + body.size()).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body.toByteArray());
        return b.array();
    }

    private static byte[] largeBox(String type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length);
        b.putInt(1).put(type.getBytes(StandardCharsets.ISO_8859_1)).putLong(16 + payload.length).put(payload);
        return b.array();
    }

    private static byte[] zeros(int n) {
        return new byte[n];
    }

    private static byte[] stsd(byte[]... entries) {
        ByteBuffer head = ByteBuffer.allocate(8).putInt(0).putInt(entries.length);
        byte[][] parts = new byte[entries.length + 1][];
        parts[0] = head.array();
        System.arraycopy(entries, 0, parts, 1, entries.length);
        return box("stsd", parts);
    }

    private static byte[] avc1(byte[]... children) {
        byte[][] parts = new byte[children.length + 1][];
        parts[0] = zeros(78);
        System.arraycopy(children, 0, parts, 1, children.length);
        return box("avc1", parts);
    }

    private static byte[] trak(byte[] stsd) {
        return box("trak", box("tkhd", zeros(84)),
                box("mdia", box("mdhd", zeros(24)), box("hdlr", zeros(25)),
                        box("minf", box("vmhd", zeros(12)), box("stbl", stsd, box("stts", zeros(8))))));
    }

    private Path file(byte[]... boxes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : boxes) {
            out.writeBytes(b);
        }
        Path p = Files.createTempFile(dir, "synthetic", ".mp4");
        Files.write(p, out.toByteArray());
        return p;
    }

    private static final byte[] FTYP = box("ftyp", "isom".getBytes(StandardCharsets.ISO_8859_1), zeros(4));

    @Test
    void findsAvcCWithMoovFirst() throws IOException {
        Path p = file(FTYP, box("moov", box("mvhd", zeros(100)),
                trak(stsd(avc1(box("avcC", AVCC), box("pasp", zeros(8)))))), box("mdat", zeros(1000)));
        assertArrayEquals(AVCC, Mp4Boxes.readAvcC(p));
    }

    @Test
    void findsAvcCWithMoovAfterLargeMdatAndUuidBoxes() throws IOException {
        // A largesize (64-bit) mdat before moov, a uuid box, and avcC after another child box.
        byte[] uuid = box("uuid", zeros(16), zeros(5));
        Path p = file(FTYP, largeBox("mdat", zeros(5000)), uuid,
                box("moov", box("mvhd", zeros(100)), trak(stsd(avc1(box("colr", zeros(11)), box("avcC", AVCC))))));
        assertArrayEquals(AVCC, Mp4Boxes.readAvcC(p));
    }

    @Test
    void skipsTracksWithoutAvc1() throws IOException {
        byte[] audio = trak(stsd(box("mp4a", zeros(28), box("esds", zeros(30)))));
        byte[] other = HexFormat.of().parseHex("01640028ffe100");
        Path p = file(FTYP, box("moov", box("mvhd", zeros(100)), audio, trak(stsd(avc1(box("avcC", AVCC)))),
                trak(stsd(avc1(box("avcC", other))))));
        assertArrayEquals(AVCC, Mp4Boxes.readAvcC(p), "first avc1 track wins");
    }

    @Test
    void lastTopLevelBoxMayExtendToEndOfFile() throws IOException {
        byte[] moov = box("moov", trak(stsd(avc1(box("avcC", AVCC)))));
        ByteBuffer.wrap(moov).putInt(0, 0);
        assertArrayEquals(AVCC, Mp4Boxes.readAvcC(file(FTYP, moov)));
    }

    @Test
    void listsChildren() throws IOException {
        Path p = file(FTYP, largeBox("mdat", zeros(10)), box("moov", zeros(4)));
        try (FileChannel ch = FileChannel.open(p)) {
            List<Mp4Boxes.Box> boxes = Mp4Boxes.children(ch, 0, ch.size(), true);
            assertEquals(List.of("ftyp", "mdat", "moov"), boxes.stream().map(Mp4Boxes.Box::type).toList());
            Mp4Boxes.Box mdat = boxes.get(1);
            assertEquals(16, mdat.start());
            assertEquals(26, mdat.size());
            assertEquals(16, mdat.headerSize());
            assertEquals(32, mdat.payloadStart());
            assertEquals(10, mdat.payloadSize());
            assertEquals(42, mdat.end());
        }
    }

    @Test
    void rejectsMalformedFiles() throws IOException {
        // No moov.
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file(FTYP, box("mdat", zeros(9)))));
        // Empty file and truncated header.
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file()));
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file(FTYP, zeros(5))));
        // A box claiming more bytes than the file has.
        byte[] lying = box("moov", zeros(10));
        ByteBuffer.wrap(lying).putInt(0, 1000);
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file(FTYP, lying)));
        // A child overrunning its parent.
        byte[] child = box("trak", zeros(10));
        ByteBuffer.wrap(child).putInt(0, 40);
        assertThrows(Mp4Boxes.Mp4FormatException.class,
                () -> Mp4Boxes.readAvcC(file(FTYP, box("moov", child), box("free", zeros(40)))));
        // A size smaller than the header (would loop forever).
        byte[] tiny = box("free", zeros(4));
        ByteBuffer.wrap(tiny).putInt(0, 4);
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file(FTYP, tiny)));
        // Size 0 is only allowed at the top level.
        byte[] nested = box("moov", box("trak", zeros(4)));
        ByteBuffer.wrap(nested).putInt(8, 0);
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(file(FTYP, nested)));
        // avc1 without avcC; truncated sample entry; no video track at all.
        assertThrows(Mp4Boxes.Mp4FormatException.class,
                () -> Mp4Boxes.readAvcC(file(box("moov", trak(stsd(avc1(box("pasp", zeros(8)))))))));
        assertThrows(Mp4Boxes.Mp4FormatException.class,
                () -> Mp4Boxes.readAvcC(file(box("moov", trak(stsd(box("avc1", zeros(20))))))));
        Mp4Boxes.Mp4FormatException e = assertThrows(Mp4Boxes.Mp4FormatException.class,
                () -> Mp4Boxes.readAvcC(file(box("moov", trak(stsd(box("mp4a", zeros(28))))))));
        assertTrue(e.getMessage().contains("no avc1 track"), e.getMessage());
        assertTrue(e.getMessage().contains(dir.toString()), "message names the file");
    }

    // ---- real encodes, cross-checked against ffprobe ----

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

    private static Tools tools() {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        return new Tools(ffmpeg, ffprobe, null);
    }

    /** ffprobe's {@code extradata=} hex dump of the first video stream, as bytes. */
    private static byte[] ffprobeExtradata(Tools tools, Path mp4) throws Exception {
        String out = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=extradata",
                "-show_data", "-of", "default=noprint_wrappers=1", mp4.toString())
                .stdoutToString(1024 * 1024).run().stdoutString();
        int start = out.indexOf("extradata=");
        assertTrue(start >= 0, out);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String line : out.substring(start).split("\\R")) {
            if (line.matches("^[0-9a-f]{8}: .*")) {
                bytes.writeBytes(HexFormat.of().parseHex(line.substring(10, Math.min(line.length(), 49))
                        .replace(" ", "")));
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @Timeout(120)
    void matchesFfprobeOnRealEncodes() throws Exception {
        Tools tools = tools();
        OutputProfile profile = OutputProfile.P720_30;
        for (boolean faststart : new boolean[] {false, true}) {
            // Audio is mapped first so the video track is the second trak.
            Path out = dir.resolve("real-" + faststart + ".mp4");
            List<String> args = new ArrayList<>(List.of("-y",
                    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=2",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-map", "1:a", "-map", "0:v", "-vf", EncodeProfile.scaleFilter(profile)));
            args.addAll(EncodeProfile.videoArgs(profile));
            args.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
            if (faststart) {
                args.addAll(List.of("-movflags", "+faststart"));
            }
            args.add(out.toString());
            tools.ffmpeg(args).run();

            try (FileChannel ch = FileChannel.open(out)) {
                List<String> top = Mp4Boxes.children(ch, 0, ch.size(), true).stream().map(Mp4Boxes.Box::type).toList();
                assertTrue(top.indexOf("moov") >= 0 && top.indexOf("mdat") >= 0, top.toString());
                assertEquals(faststart, top.indexOf("moov") < top.indexOf("mdat"), top.toString());
            }
            byte[] avcC = Mp4Boxes.readAvcC(out);
            assertArrayEquals(ffprobeExtradata(tools, out), avcC);
            assertEquals(1, avcC[0]);
            assertEquals(100, avcC[1] & 0xff);
        }
    }

    @Test
    @Timeout(60)
    void audioOnlyFileHasNoAvcC() throws Exception {
        Tools tools = tools();
        Path out = dir.resolve("audio.m4a");
        tools.ffmpeg("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-c:a", "aac", out.toString()).run();
        assertThrows(Mp4Boxes.Mp4FormatException.class, () -> Mp4Boxes.readAvcC(out));
    }
}
