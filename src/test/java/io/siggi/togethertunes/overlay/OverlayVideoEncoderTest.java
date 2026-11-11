package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The music-video overlay re-encode with real ffmpeg ({@code docs/overlay.md#the-encode}): from
 * the start and from a later GOP, with a smart trim's short first GOP, the SPS/PPS check, and
 * cancelling. The frames written must line up one for one with the mezzanine's.
 */
@Timeout(300)
@Execution(ExecutionMode.SAME_THREAD)
class OverlayVideoEncoderTest {
    private static final OutputEncoding ENC = OutputEncoding.DEFAULT;
    /** The panel: opaque red, outside the region compared with the mezzanine. */
    private static final int OX = 1000;
    private static final int OY = 500;
    private static final int OW = 160;
    private static final int OH = 120;

    @TempDir
    Path dir;

    /** A mezzanine like ingest makes one ({@code testsrc2} with a tone), and its index's video track. */
    private record Mezz(Path file, Track video, byte[] avcC) {
    }

    private Mezz mezz(Tools tools, double seconds, int firstGopFrames) throws Exception {
        Path file = dir.resolve("mezz-" + firstGopFrames + ".mp4");
        List<String> args = new ArrayList<>(List.of("-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", Double.toString(seconds), "-vf", EncodeProfile.scaleFilter(ENC)));
        args.addAll(EncodeProfile.videoArgs(ENC, X264Preset.VERYFAST, firstGopFrames));
        args.addAll(EncodeProfile.AUDIO_ARGS);
        args.addAll(List.of("-movflags", "+faststart", "-f", "mp4", file.toString()));
        tools.ffmpeg(args).run();
        SampleIndex index = IndexBuilder.build(tools, ENC,
                List.of(new IndexBuilder.Source(file, SampleIndex.Kind.VIDEO)), null);
        return new Mezz(file, index.track(SampleIndex.Kind.VIDEO), Mp4Boxes.readAvcC(file));
    }

    private Path overlayPng() throws Exception {
        Path png = dir.resolve("overlay.png");
        BufferedImage img = new BufferedImage(OW, OH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, OW, OH);
        g.dispose();
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    private OverlayVideoEncoder.Request request(Mezz m, int g0, byte[] avcC) throws Exception {
        return new OverlayVideoEncoder.Request(m.file(), m.video(), overlayPng(), OX, OY, ENC, X264Preset.VERYFAST,
                avcC, g0);
    }

    /** The written frames from {@code from} on as an Annex B stream (what the packager would send). */
    private static byte[] annexB(OverlayGops gops, byte[] avcC, int from) throws Exception {
        AnnexB annexB = new AnnexB(avcC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RandomAccessFile f = new RandomAccessFile(gops.h264File().toFile(), "r")) {
            for (int i = from; i < gops.frameCount(); i++) {
                byte[] sample = new byte[gops.size(i)];
                f.seek(gops.offset(i));
                f.readFully(sample);
                out.write(annexB.accessUnit(sample));
            }
        }
        return out.toByteArray();
    }

    /** Decodes {@code input} to gray frames (the top-left 640×360, away from the panel), {@code frames} of them from {@code skip}. */
    private static byte[][] gray(Tools tools, List<String> input, int skip, int frames) throws Exception {
        List<String> args = new ArrayList<>(List.of("-v", "error"));
        args.addAll(input);
        args.addAll(List.of("-vf", "select=gte(n\\," + skip + "),crop=640:360:0:0,format=gray",
                "-fps_mode", "passthrough", "-frames:v", Integer.toString(frames), "-f", "rawvideo", "pipe:1"));
        ToolResult r = tools.ffmpeg(args).stdoutToString(640 * 360 * frames).run();
        byte[] all = r.stdout();
        assertEquals(640 * 360 * frames, all.length);
        byte[][] out = new byte[frames][];
        for (int i = 0; i < frames; i++) {
            out[i] = java.util.Arrays.copyOfRange(all, i * 640 * 360, (i + 1) * 640 * 360);
        }
        return out;
    }

    private static double meanAbsDiff(byte[] a, byte[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return (double) sum / a.length;
    }

    /** The panel's centre pixel of the first decoded frame, as RGB. */
    private static int[] panelPixel(Tools tools, Path h264) throws Exception {
        ToolResult r = tools.ffmpeg("-v", "error", "-f", "h264", "-i", h264.toString(), "-frames:v", "1",
                "-vf", "crop=2:2:" + (OX + OW / 2) + ":" + (OY + OH / 2) + ",format=rgb24",
                "-f", "rawvideo", "pipe:1").stdoutToString(12).run();
        byte[] b = r.stdout();
        return new int[] {b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF};
    }

    private static int countFrames(Tools tools, Path h264) throws Exception {
        ToolResult r = tools.ffprobe("-v", "error", "-count_frames", "-select_streams", "v",
                "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", h264.toString())
                .stdoutToString(100).run();
        return Integer.parseInt(r.stdoutString().trim());
    }

    /** Checks the encode from GOP {@code g0}: completion, frame count, decodability, overlay, alignment. */
    private void checkEncode(Tools tools, Mezz m, int g0) throws Exception {
        Path out = dir.resolve("ov-" + m.file().getFileName() + "-" + g0);
        OverlayGops gops = new OverlayGops(out, m.video());
        List<Double> progress = new ArrayList<>();
        OverlayVideoEncoder.encode(tools, request(m, g0, m.avcC()), gops, progress::add, null);

        for (int g = 0; g < gops.gopCount(); g++) {
            assertEquals(g >= g0, gops.isComplete(g), "GOP " + g + " of an encode from GOP " + g0);
        }
        assertTrue(gops.completeFrom(g0));
        assertEquals(gops.gopCount() - g0, progress.size(), "progress after every GOP");
        assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);

        // the files read back the same
        OverlayGops loaded = OverlayGops.load(out, m.video());
        assertEquals(gops.completed(), loaded.completed());
        int start = gops.gopStart(g0);
        for (int i = start; i < gops.frameCount(); i++) {
            assertEquals(gops.offset(i), loaded.offset(i));
            assertEquals(gops.size(i), loaded.size(i));
        }

        // no parameter sets, AUD or SEI stored: every frame is slices only
        AnnexB parser = new AnnexB(m.avcC());
        try (RandomAccessFile f = new RandomAccessFile(gops.h264File().toFile(), "r")) {
            for (int i = start; i < gops.frameCount(); i++) {
                byte[] sample = new byte[gops.size(i)];
                f.seek(gops.offset(i));
                f.readFully(sample);
                assertEquals(m.video().isKeyframe(i), parser.isIdr(sample, 0, sample.length), "frame " + i);
                int pos = 0;
                while (pos < sample.length) {
                    int len = ((sample[pos] & 0xFF) << 24) | ((sample[pos + 1] & 0xFF) << 16)
                            | ((sample[pos + 2] & 0xFF) << 8) | (sample[pos + 3] & 0xFF);
                    int type = sample[pos + 4] & 0x1F;
                    assertTrue(type == 1 || type == 5, "frame " + i + " holds NAL type " + type);
                    pos += 4 + len;
                }
                assertEquals(sample.length, pos);
            }
        }

        // as the packager would send it: decodes to every frame, with the panel
        Path h264 = dir.resolve("check-" + m.file().getFileName() + "-" + g0 + ".h264");
        Files.write(h264, annexB(gops, m.avcC(), start));
        assertEquals(gops.frameCount() - start, countFrames(tools, h264));
        int[] px = panelPixel(tools, h264);
        assertTrue(px[0] > 200 && px[1] < 60 && px[2] < 60, "red panel, got " + java.util.Arrays.toString(px));

        // frame i of the encode is frame i of the mezzanine (compared away from the panel):
        // closer to the same frame than to its neighbours
        byte[][] enc = gray(tools, List.of("-f", "h264", "-i", h264.toString()), 0, 3);
        int from = Math.max(0, start - 1);
        byte[][] orig = gray(tools, List.of("-i", m.file().toString()), from, 4);
        for (int j = 0; j < 3; j++) {
            int same = start + j - from;
            double dSame = meanAbsDiff(enc[j], orig[same]);
            if (same > 0) {
                assertTrue(dSame < meanAbsDiff(enc[j], orig[same - 1]), "frame " + j + " vs the previous");
            }
            if (same + 1 < orig.length) {
                assertTrue(dSame < meanAbsDiff(enc[j], orig[same + 1]), "frame " + j + " vs the next");
            }
        }
    }

    @Test
    void encodesFromTheStartAndFromALaterGop() throws Exception {
        Tools tools = OverlayCardsTest.tools();
        Mezz m = mezz(tools, 4.5, 60);
        assertEquals(135, m.video().sampleCount());
        checkEncode(tools, m, 0);
        checkEncode(tools, m, 1);
        checkEncode(tools, m, 2); // the short last GOP alone
    }

    /**
     * The packager's substitution ({@code docs/overlay.md#packager-substitution}) over a real
     * encode started at GOP 1: the item plays GOP 0 from the mezzanine and the rest from the
     * overlay, and the spliced stream decodes to every frame without errors, the panel appearing
     * exactly at the GOP boundary.
     */
    @Test
    void splicedStreamDecodesWithThePanelFromTheFirstOverlayGop() throws Exception {
        Tools tools = OverlayCardsTest.tools();
        Mezz m = mezz(tools, 4.5, 60);
        Path out = dir.resolve("ov-splice");
        OverlayGops gops = new OverlayGops(out, m.video());
        OverlayVideoEncoder.encode(tools, request(m, 1, m.avcC()), gops, p -> { }, null);

        SampleIndex index = IndexBuilder.build(tools, ENC, List.of(
                new IndexBuilder.Source(m.file(), SampleIndex.Kind.VIDEO),
                new IndexBuilder.Source(m.file(), SampleIndex.Kind.AUDIO)), null);
        AnnexB annexB = new AnnexB(m.avcC());
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        List<Boolean> overlaid = new ArrayList<>();
        try (io.siggi.togethertunes.output.FileSampleData data =
                     new io.siggi.togethertunes.output.FileSampleData(dir, index)) {
            io.siggi.togethertunes.output.VideoItemSource src =
                    new io.siggi.togethertunes.output.VideoItemSource("item", index, data, () -> gops, 0);
            io.siggi.togethertunes.output.VideoSample v;
            while ((v = src.nextVideo()) != null) {
                overlaid.add(src.playingOverlay());
                stream.write(annexB.accessUnit(v.data()));
            }
            assertEquals(2, src.overlaidGops());
        }
        assertEquals(135, overlaid.size());
        for (int i = 0; i < 135; i++) {
            assertEquals(i >= 60, overlaid.get(i), "frame " + i);
        }
        Path h264 = dir.resolve("spliced.h264");
        Files.write(h264, stream.toByteArray());
        ToolResult r = tools.ffmpeg("-v", "error", "-f", "h264", "-i", h264.toString(),
                "-vf", "crop=2:2:" + (OX + OW / 2) + ":" + (OY + OH / 2) + ",format=rgb24",
                "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1").stdoutToString(12 * 200).run();
        byte[] px = r.stdout();
        assertEquals(135 * 12, px.length, "every frame decodes");
        assertEquals("", r.stderrTail().trim(), "no decode errors");
        // the mezzanine at the same spot (its picture there may be red too, now and then)
        byte[] mz = tools.ffmpeg("-v", "error", "-i", m.file().toString(),
                "-vf", "crop=2:2:" + (OX + OW / 2) + ":" + (OY + OH / 2) + ",format=rgb24",
                "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1").stdoutToString(12 * 200).run().stdout();
        assertEquals(135 * 12, mz.length);
        for (int i = 0; i < 135; i++) {
            int red = px[i * 12] & 0xFF;
            int green = px[i * 12 + 1] & 0xFF;
            int blue = px[i * 12 + 2] & 0xFF;
            if (i >= 60) {
                assertTrue(red > 200 && green < 60 && blue < 60, "frame " + i + ": panel, got rgb " + red + "," + green + "," + blue);
            } else {
                int d = Math.abs(red - (mz[i * 12] & 0xFF)) + Math.abs(green - (mz[i * 12 + 1] & 0xFF))
                        + Math.abs(blue - (mz[i * 12 + 2] & 0xFF));
                assertTrue(d < 12, "frame " + i + ": the mezzanine's own picture, off by " + d);
            }
        }
    }

    @Test
    void shortFirstGopKeepsTheMezzaninesKeyframes() throws Exception {
        Tools tools = OverlayCardsTest.tools();
        Mezz m = mezz(tools, 4.0, 20);
        assertEquals(List.of(0, 20, 80), List.of(OverlayGops.gopStarts(m.video())[0],
                OverlayGops.gopStarts(m.video())[1], OverlayGops.gopStarts(m.video())[2]));
        checkEncode(tools, m, 0);
        checkEncode(tools, m, 1);
    }

    @Test
    void commandSeeksToTheGopAndUsesTheProfileArguments() throws Exception {
        Track t = OverlayGopsTest.track(150, 0, 20, 80, 140);
        Path png = dir.resolve("o.png");
        OverlayVideoEncoder.Request r0 = new OverlayVideoEncoder.Request(dir.resolve("mezz.mp4"), t, png, 10, 20,
                ENC, X264Preset.FASTER, new byte[] {1}, 0);
        List<String> c0 = OverlayVideoEncoder.command(r0);
        assertFalse(c0.contains("-ss"));
        assertTrue(c0.contains("[0:v][1:v]overlay=10:20," + EncodeProfile.COLOUR_FILTER + "[v]"));
        assertEquals(EncodeProfile.videoArgs(ENC, X264Preset.FASTER, 20),
                c0.subList(c0.indexOf("-c:v"), c0.indexOf("-c:v") + EncodeProfile.videoArgs(ENC, X264Preset.FASTER).size()));
        assertEquals(List.of("-an", "-f", "h264", "pipe:1"), c0.subList(c0.size() - 4, c0.size()));

        OverlayVideoEncoder.Request r2 = new OverlayVideoEncoder.Request(dir.resolve("mezz.mp4"), t, png, 10, 20,
                ENC, X264Preset.FASTER, new byte[] {1}, 2);
        List<String> c2 = OverlayVideoEncoder.command(r2);
        // frame 80 at 80·512/15360 s = 2.6666… s, rounded down to the microsecond
        assertEquals("2666666us", c2.get(c2.indexOf("-ss") + 1));
        assertTrue(c2.indexOf("-ss") < c2.indexOf("-i"), "input seeking");
        assertEquals(EncodeProfile.videoArgs(ENC, X264Preset.FASTER),
                c2.subList(c2.indexOf("-c:v"), c2.indexOf("-c:v") + EncodeProfile.videoArgs(ENC, X264Preset.FASTER).size()));
        assertThrows(IllegalArgumentException.class, () -> OverlayVideoEncoder.command(
                new OverlayVideoEncoder.Request(dir.resolve("mezz.mp4"), t, png, 10, 20, ENC, X264Preset.FASTER,
                        new byte[] {1}, 4)));
    }

    @Test
    void parameterSetsDifferingFromTheReferenceAbortTheJob() throws Exception {
        Tools tools = OverlayCardsTest.tools();
        Mezz m = mezz(tools, 2.5, 60);
        byte[] other = m.avcC();
        other[8 + 3]++; // level_idc in the reference's SPS
        OverlayGops gops = new OverlayGops(dir.resolve("ov-bad"), m.video());
        OverlayVideoEncoder.MismatchException e = assertThrows(OverlayVideoEncoder.MismatchException.class,
                () -> OverlayVideoEncoder.encode(tools, request(m, 0, other), gops, null, null));
        assertTrue(e.getMessage().contains("SPS differs"), e.getMessage());
        assertTrue(gops.completed().isEmpty());
    }

    @Test
    void interruptStopsTheEncode() throws Exception {
        Tools tools = OverlayCardsTest.tools();
        Mezz m = mezz(tools, 20, 60);
        OverlayGops gops = new OverlayGops(dir.resolve("ov-cancel"), m.video());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                OverlayVideoEncoder.encode(tools, request(m, 0, m.avcC()), gops, null, null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!gops.isComplete(0) && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(gops.isComplete(0), "the first GOP is published while the encode goes on");
        worker.interrupt();
        worker.join(30_000);
        assertFalse(worker.isAlive());
        assertTrue(thrown.get() instanceof InterruptedException, "got " + thrown.get());
        assertFalse(gops.completeFrom(0));
    }
}
