package io.siggi.togethertunes.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EncodeProfileTest {
    @TempDir
    Path dir;

    /** Value of the option {@code name} in an argument list (exactly one occurrence). */
    private static String option(List<String> args, String name) {
        int index = args.indexOf(name);
        assertTrue(index >= 0 && index + 1 < args.size(), name + " missing");
        assertEquals(index, args.lastIndexOf(name), name + " given twice");
        return args.get(index + 1);
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    void argumentsFixTheEncoder(OutputProfile profile) {
        List<String> args = EncodeProfile.videoArgs(profile);
        assertSame(args, EncodeProfile.videoArgs(profile), "one constant per profile");
        assertThrows(UnsupportedOperationException.class, () -> args.add("-tune"));
        assertEquals(0, args.size() % 2, "options come in name/value pairs");
        assertEquals("libx264", option(args, "-c:v"));
        assertEquals("veryfast", option(args, "-preset"));
        assertEquals("high", option(args, "-profile:v"));
        assertEquals("yuv420p", option(args, "-pix_fmt"));
        assertEquals("tv", option(args, "-color_range"));
        assertEquals("bt709", option(args, "-colorspace"));
        assertEquals("bt709", option(args, "-color_primaries"));
        assertEquals("bt709", option(args, "-color_trc"));
        assertEquals("left", option(args, "-chroma_sample_location"));
        assertEquals("0", option(args, "-bf"));
        assertEquals("60", option(args, "-g"));
        assertEquals("60", option(args, "-keyint_min"));
        assertEquals("0", option(args, "-sc_threshold"));
        assertEquals("expr:gte(t,n_forced*2)", option(args, "-force_key_frames"));
        assertEquals("open-gop=0", option(args, "-x264-params"));
        assertEquals(EncodeProfile.GOP_FRAMES, profile.fps() * EncodeProfile.GOP_MS / 1000);
        for (String arg : args) {
            assertFalse(arg.equals("-i") || arg.equals("-vf") || arg.equals("-r") || arg.equals("-s"),
                    "encoder options only: " + arg);
        }
    }

    @Test
    void levelAndRateCapPerProfile() {
        assertEquals("4.0", option(EncodeProfile.VIDEO_ARGS_720P30, "-level:v"));
        assertEquals("3M", option(EncodeProfile.VIDEO_ARGS_720P30, "-maxrate"));
        assertEquals("6M", option(EncodeProfile.VIDEO_ARGS_720P30, "-bufsize"));
        assertEquals("4.1", option(EncodeProfile.VIDEO_ARGS_1080P30, "-level:v"));
        assertEquals("6M", option(EncodeProfile.VIDEO_ARGS_1080P30, "-maxrate"));
        assertEquals("12M", option(EncodeProfile.VIDEO_ARGS_1080P30, "-bufsize"));
        assertSame(EncodeProfile.VIDEO_ARGS_720P30, EncodeProfile.videoArgs(OutputProfile.P720_30));
        assertSame(EncodeProfile.VIDEO_ARGS_1080P30, EncodeProfile.videoArgs(OutputProfile.P1080_30));
    }

    @Test
    void scaleFilter() {
        assertEquals("scale=1280:720:force_original_aspect_ratio=decrease:force_divisible_by=2"
                        + ":out_color_matrix=bt709:out_range=tv,"
                        + "pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                EncodeProfile.scaleFilter(OutputProfile.P720_30));
        assertEquals("scale=1920:1080:force_original_aspect_ratio=decrease:force_divisible_by=2"
                        + ":out_color_matrix=bt709:out_range=tv,"
                        + "pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                EncodeProfile.scaleFilter(OutputProfile.P1080_30));
    }

    // --- against the real ffmpeg (skipped when it isn't installed) ---

    private static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String d : path.split(File.pathSeparator)) {
            Path p = Path.of(d, name);
            if (!d.isEmpty() && Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
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

    /** A lavfi source scaled to the profile: a mezzanine-style encode. */
    private Path encodeVideo(Tools tools, OutputProfile profile, String name, String source) throws Exception {
        Path out = dir.resolve(name + ".mp4");
        List<String> args = new ArrayList<>(List.of("-f", "lavfi", "-i", source,
                "-vf", EncodeProfile.scaleFilter(profile)));
        args.addAll(EncodeProfile.videoArgs(profile));
        args.addAll(List.of("-an", out.toString()));
        tools.ffmpeg(args).run();
        return out;
    }

    /** A still image at the profile size, encoded as one 2 s card GOP. */
    private Path encodeCard(Tools tools, OutputProfile profile) throws Exception {
        Path png = dir.resolve("card-" + profile + ".png");
        tools.ffmpeg("-f", "lavfi", "-i", "color=c=0x336699:size=" + profile.width() + "x" + profile.height(),
                "-frames:v", "1", png.toString()).run();
        Path out = dir.resolve("card-" + profile + ".mp4");
        List<String> args = new ArrayList<>(List.of("-loop", "1", "-framerate", "30", "-i", png.toString(), "-t", "2"));
        args.addAll(EncodeProfile.videoArgs(profile));
        args.addAll(List.of("-an", out.toString()));
        tools.ffmpeg(args).run();
        return out;
    }

    private static String probe(Tools tools, Path file, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("-v", "error", "-select_streams", "v:0"));
        all.addAll(List.of(args));
        all.add(file.toString());
        ToolResult r = tools.ffprobe(all).stdoutToString(16 << 20).run();
        return r.stdoutString();
    }

    /** The H.264 extradata (avcC) as ffprobe's hex dump. */
    private static String extradata(Tools tools, Path file) throws Exception {
        String out = probe(tools, file, "-show_streams", "-show_data");
        int start = out.indexOf("extradata=");
        assertTrue(start >= 0, "no extradata");
        StringBuilder hex = new StringBuilder();
        for (String line : out.substring(start).split("\n")) {
            if (line.matches("^[0-9a-f]{8}: .*")) {
                hex.append(line, 10, Math.min(line.length(), 50)).append('\n');
            } else if (!line.startsWith("extradata=")) {
                break;
            }
        }
        assertTrue(hex.length() > 0, "empty extradata");
        return hex.toString();
    }

    /** Checks the stream parameters and that IDRs sit exactly on every 60th frame, nothing else. */
    private static void checkStructure(Tools tools, Path file, OutputProfile profile, int expectedFrames) throws Exception {
        String stream = probe(tools, file, "-show_entries",
                "stream=codec_name,profile,width,height,pix_fmt,has_b_frames,sample_aspect_ratio,r_frame_rate",
                "-of", "default=noprint_wrappers=1").trim();
        List<String> lines = List.of(stream.split("\n"));
        assertTrue(lines.contains("codec_name=h264"), stream);
        assertTrue(lines.contains("profile=High"), stream);
        assertTrue(lines.contains("width=" + profile.width()), stream);
        assertTrue(lines.contains("height=" + profile.height()), stream);
        assertTrue(lines.contains("pix_fmt=yuv420p"), stream);
        assertTrue(lines.contains("has_b_frames=0"), stream);
        assertTrue(lines.contains("sample_aspect_ratio=1:1"), stream);
        assertTrue(lines.contains("r_frame_rate=30/1"), stream);

        String frames = probe(tools, file, "-show_entries", "frame=key_frame,pict_type,pts", "-of", "csv=p=0").trim();
        String[] rows = frames.split("\n");
        assertEquals(expectedFrames, rows.length, "frame count");
        long firstPts = Long.MIN_VALUE;
        long step = 0;
        for (int i = 0; i < rows.length; i++) {
            String[] f = rows[i].split(",");
            boolean key = f[0].equals("1");
            long pts = Long.parseLong(f[1]);
            String type = f[2];
            assertFalse(type.equals("B"), "B-frame at " + i);
            if (i % EncodeProfile.GOP_FRAMES == 0) {
                assertTrue(key && type.equals("I"), "frame " + i + " must be an IDR: " + rows[i]);
            } else {
                assertTrue(!key && type.equals("P"), "frame " + i + " must be a P-frame: " + rows[i]);
            }
            if (i == 0) {
                firstPts = pts;
            } else if (i == 1) {
                step = pts - firstPts;
                assertTrue(step > 0);
            } else {
                assertEquals(firstPts + i * step, pts, "constant frame rate at " + i);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(180)
    void everyEncodeSharesTheExtradata(OutputProfile profile) throws Exception {
        Tools tools = tools();
        Path landscape24 = encodeVideo(tools, profile, "landscape24",
                "testsrc2=size=640x480:rate=24:duration=4.5");
        Path portrait60 = encodeVideo(tools, profile, "portrait60",
                "testsrc2=size=405x720:rate=60:duration=4.5");
        Path card = encodeCard(tools, profile);

        checkStructure(tools, landscape24, profile, 135);
        checkStructure(tools, portrait60, profile, 135);
        checkStructure(tools, card, profile, 60);

        String reference = extradata(tools, card);
        assertEquals(reference, extradata(tools, landscape24), "24 fps landscape vs card");
        assertEquals(reference, extradata(tools, portrait60), "60 fps portrait vs card");
    }

    /**
     * x264 writes the source's colour tags and chroma location into the VUI; the profile fixes
     * them, so sources tagged BT.709 (YouTube), BT.601 interlaced SD, full-range, Motion JPEG
     * (centre-sited chroma) and 10-bit HDR all get the card's extradata and BT.709 tags.
     */
    @Test
    @Timeout(180)
    void colourTagsOfTheSourceDontChangeTheExtradata() throws Exception {
        Tools tools = tools();
        OutputProfile profile = OutputProfile.P720_30;
        String reference = extradata(tools, encodeCard(tools, profile));
        String src = "testsrc2=size=640x360:rate=30:duration=2";
        List<List<String>> sources = List.of(
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-color_primaries", "bt709", "-color_trc", "bt709",
                        "-colorspace", "bt709"),
                List.of("-c:v", "mpeg2video", "-flags", "+ildct+ilme", "-top", "1", "-color_primaries", "smpte170m",
                        "-color_trc", "smpte170m", "-colorspace", "smpte170m"),
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuvj420p", "-color_range", "pc"),
                List.of("-c:v", "mjpeg"),
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-color_primaries",
                        "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc"));
        int n = 0;
        for (List<String> encode : sources) {
            Path in = dir.resolve("tagged" + n + (encode.contains("mjpeg") ? ".avi" : ".mkv"));
            List<String> args = new ArrayList<>(List.of("-f", "lavfi", "-i", src));
            args.addAll(encode);
            args.add(in.toString());
            tools.ffmpeg(args).run();
            Path out = dir.resolve("tagged" + n + "-out.mp4");
            args = new ArrayList<>(List.of("-i", in.toString(), "-vf", EncodeProfile.scaleFilter(profile)));
            args.addAll(EncodeProfile.videoArgs(profile));
            args.addAll(List.of("-an", out.toString()));
            tools.ffmpeg(args).run();
            assertEquals(reference, extradata(tools, out), encode.toString());
            String tags = probe(tools, out, "-show_entries",
                    "stream=color_range,color_space,color_primaries,color_transfer,chroma_location",
                    "-of", "default=noprint_wrappers=1");
            assertEquals(List.of("chroma_location=left", "color_primaries=bt709", "color_range=tv", "color_space=bt709",
                    "color_transfer=bt709"), tags.lines().map(String::strip).sorted().toList(), encode.toString());
            n++;
        }
    }

    @Test
    @Timeout(120)
    void profilesHaveDifferentExtradata() throws Exception {
        Tools tools = tools();
        assertNotEquals(extradata(tools, encodeCard(tools, OutputProfile.P720_30)),
                extradata(tools, encodeCard(tools, OutputProfile.P1080_30)));
    }
}
