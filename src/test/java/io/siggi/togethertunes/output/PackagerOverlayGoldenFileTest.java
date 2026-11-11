package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.overlay.OverlayGops;
import io.siggi.togethertunes.overlay.OverlayVideoEncoder;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packager golden-file session with overlay substitution ({@code docs/overlay.md#packager-substitution},
 * {@code docs/milestones.md#testing-strategy}): a scripted session through the {@link OfflineHarness}
 * in which video items play GOPs from a real overlay re-encode ({@link OverlayVideoEncoder}, a
 * three-colour panel) of the item's mezzanine, written to a {@code .ts} file:
 *
 * <ul>
 * <li>{@code v0} from the start, the re-encode complete only from GOP 2 (as a catch-up encode
 * started mid-item): original GOPs 0–1, then the overlay;</li>
 * <li>{@code a0}, an audio item: never asked for a re-encode;</li>
 * <li>{@code w0} with GOP 2 not complete yet: overlay, original, overlay (switches both ways);</li>
 * <li>{@code x0} complete, seeked mid-item to a GOP start: the overlay from the seek on;</li>
 * <li>{@code y0} given a re-encode made for another frame count: dropped, the original plays.</li>
 * </ul>
 *
 * <p>Checked as {@link PackagerGoldenFileTest} checks its session (decodes without a message,
 * timestamps, an IDR at every unit start and nowhere else), and: every decoded frame is tested for
 * the panel, every switch between original and overlay lies on an IDR, and the panel shows on
 * exactly as many frames as the overlay GOPs that were sent. The session's structure (harness
 * log, the re-encode's GOP requests and answers, and the panel's frame runs) must match
 * {@code src/test/resources/golden/packager-overlay-session.txt}. Regenerate after an intended
 * change with {@code mvn test -Dtest=PackagerOverlayGoldenFileTest -Dtt.golden.update=true} and
 * review the diff.
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class PackagerOverlayGoldenFileTest {
    static final String GOLDEN = "golden/packager-overlay-session.txt";
    static final long SESSION_MS = 48_000;
    /** The panel: three 80×120 stripes (red, green, blue), at a spot no test pattern matches. */
    private static final int OX = 1000;
    private static final int OY = 560;
    private static final int OW = 240;
    private static final int OH = 120;

    @TempDir
    Path dir;

    /** The re-encode as one entry's play sees it: only the GOPs {@code complete} says are done. */
    private static final class TestOverlay implements VideoOverlay {
        private final String entryId;
        private final OverlayGops gops;
        private final IntPredicate complete;
        private final int frameCount;
        private final List<String> log;

        TestOverlay(String entryId, OverlayGops gops, IntPredicate complete, int frameCount, List<String> log) {
            this.entryId = entryId;
            this.gops = gops;
            this.complete = complete;
            this.frameCount = frameCount;
            this.log = log;
        }

        @Override
        public int frameCount() {
            return frameCount;
        }

        @Override
        public byte[][] gop(int keyframe) throws IOException {
            int g = gops.gopOf(keyframe);
            byte[][] frames = complete.test(g) ? gops.gop(keyframe) : null;
            log.add(entryId + " GOP " + g + " (frame " + keyframe + "): "
                    + (frames == null ? "not complete" : frames.length + " frames"));
            return frames;
        }
    }

    @Test
    @Timeout(900)
    void overlaySessionMatchesTheGoldenFile() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        OutputEncoding enc = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path video1 = m.media().resolve("video1");
        SampleIndex.Track track = SampleIndex.read(video1.resolve(SampleIndex.FILE_NAME))
                .track(SampleIndex.Kind.VIDEO);

        // one real re-encode of the whole mezzanine; each play sees part of it as complete
        OverlayGops gops = new OverlayGops(Files.createDirectories(dir.resolve("ov")), track);
        OverlayVideoEncoder.encode(tools, new OverlayVideoEncoder.Request(video1.resolve(IngestPipeline.MEZZ), track,
                panelPng(), OX, OY, enc, X264Preset.VERYFAST, m.avcC(), 0), gops, p -> { }, null);
        assertTrue(gops.completeFrom(0));
        assertEquals(6, gops.gopCount(), "10.3 s: five full GOPs and a short one");

        List<String> overlayLog = Collections.synchronizedList(new ArrayList<>());
        Map<String, VideoOverlay> overlays = Map.of(
                "v0", new TestOverlay("v0", gops, g -> g >= 2, gops.frameCount(), overlayLog),
                "w0", new TestOverlay("w0", gops, g -> g != 2, gops.frameCount(), overlayLog),
                "x0", new TestOverlay("x0", gops, g -> true, gops.frameCount(), overlayLog),
                "y0", new TestOverlay("y0", gops, g -> true, gops.frameCount() - 1, overlayLog));
        ProgramMedia.OverlayCards cards = new ProgramMedia.OverlayCards() {
            @Override
            public Map<FillerSource.Filler, ProgramMedia.CardFile> fillers() {
                return null;
            }

            @Override
            public ProgramMedia.CardFile item(Entry entry) {
                return null;
            }

            @Override
            public Supplier<VideoOverlay> video(Entry entry, Path itemDir, SampleIndex index) {
                overlayLog.add("play " + entry.entryId() + " (" + entry.itemId() + ")");
                assertEquals(video1, itemDir);
                VideoOverlay o = overlays.get(entry.entryId());
                return o == null ? null : () -> o;
            }

            @Override
            public void videoNext(Entry entry, Path itemDir, SampleIndex index) {
            }

            @Override
            public void videoWanted(Set<String> entryIds) {
            }
        };

        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
        script.at(0).add(
                        new Entry("v0", "video1", true, false, HarnessFixture.VIDEO_MS),
                        new Entry("a0", "audio1", false, false, HarnessFixture.AUDIO_MS),
                        new Entry("w0", "video1", true, false, HarnessFixture.VIDEO_MS),
                        new Entry("x0", "video1", true, false, HarnessFixture.VIDEO_MS),
                        new Entry("y0", "video1", true, false, HarnessFixture.VIDEO_MS))
                .at(31_000).seek(6_000);
        Path out = dir.resolve("overlay.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, SESSION_MS, 0, 0, cards, out);

        HarnessFixture.assertDecodesClean(tools, out);
        HarnessFixture.assertPackets(tools, out, r);
        List<Long> keyframePts = PackagerGoldenFileTest.keyframes(tools, out);
        assertEquals(PackagerGoldenFileTest.unitStarts(r), keyframePts, "an IDR at the start of every unit and nowhere else");

        boolean[] panel = panelFrames(tools, out);
        boolean[] idr = idrFrames(tools, out);
        assertEquals(r.videoFrames(), panel.length, "every frame decodes");
        assertEquals(panel.length, idr.length);
        for (int i = 1; i < panel.length; i++) {
            if (panel[i] != panel[i - 1]) {
                assertTrue(idr[i], "switch " + (panel[i] ? "to" : "from") + " the overlay at frame " + i
                        + " is not on an IDR");
            }
        }
        int panelFrames = 0;
        for (boolean p : panel) {
            panelFrames += p ? 1 : 0;
        }
        int overlayFrames = 0;
        for (String line : overlayLog) {
            if (line.endsWith(" frames")) {
                overlayFrames += Integer.parseInt(line.substring(line.lastIndexOf(": ") + 2, line.length() - 7));
            }
        }
        assertTrue(panelFrames > 0, "the overlay shows");
        // every frame of every overlay GOP taken shows the panel, and only those, except x0's GOP 2:
        // the source reads it when it reaches frame 120, then the seek replaces the play before
        // that GOP is sent (the new play starts at GOP 3)
        assertEquals(overlayFrames - 60, panelFrames, "the panel shows on the overlay GOPs sent, and only there");
        assertTrue(overlayLog.stream().noneMatch(l -> l.startsWith("y0 ")), "the mismatched overlay is never read");
        assertTrue(overlayLog.stream().noneMatch(l -> l.contains("a0")), "audio items get no re-encode");

        String actual = describe(r, overlayLog, panel);
        if (Boolean.getBoolean("tt.golden.update")) {
            Path golden = Path.of("src", "test", "resources", GOLDEN);
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = PackagerOverlayGoldenFileTest.class.getClassLoader().getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN + " missing; create it with -Dtt.golden.update=true");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        PackagerGoldenFileTest.compare(GOLDEN, expected, actual);
    }

    private Path panelPng() throws IOException {
        Path png = dir.resolve("overlay.png");
        BufferedImage img = new BufferedImage(OW, OH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Color[] stripes = {Color.RED, Color.GREEN, Color.BLUE};
        for (int i = 0; i < 3; i++) {
            g.setColor(stripes[i]);
            g.fillRect(i * OW / 3, 0, OW / 3, OH);
        }
        g.dispose();
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    /**
     * For every video frame of {@code ts} in decode order, whether it shows the panel: the middle
     * of each stripe (40×60 of 80×120, away from the edges' chroma bleed) averaged to one pixel.
     */
    private static boolean[] panelFrames(Tools tools, Path ts) throws Exception {
        StringBuilder vf = new StringBuilder();
        String[] labels = {"r", "g", "b"};
        vf.append("[0:v]split=3[s0][s1][s2]");
        for (int i = 0; i < 3; i++) {
            vf.append(";[s").append(i).append("]crop=").append(OW / 6).append(':').append(OH / 2).append(':')
                    .append(OX + i * OW / 3 + OW / 12).append(':').append(OY + OH / 4)
                    .append(",scale=1:1:flags=area[").append(labels[i]).append(']');
        }
        vf.append(";[r][g][b]hstack=inputs=3,format=rgb24[out]");
        ToolResult res = tools.ffmpeg("-v", "error", "-i", ts.toString(), "-filter_complex", vf.toString(),
                "-map", "[out]", "-fps_mode", "passthrough", "-f", "rawvideo", "pipe:1")
                .stdoutToString(1 << 26).timeout(Duration.ofMinutes(10)).run();
        byte[] px = res.stdout();
        assertEquals(0, px.length % 9);
        boolean[] panel = new boolean[px.length / 9];
        for (int i = 0; i < panel.length; i++) {
            panel[i] = isPure(px, i * 9, 0) && isPure(px, i * 9 + 3, 1) && isPure(px, i * 9 + 6, 2);
        }
        return panel;
    }

    /** Whether the pixel at {@code off} is close to pure {@code channel} (0 red, 1 green, 2 blue). */
    private static boolean isPure(byte[] px, int off, int channel) {
        for (int c = 0; c < 3; c++) {
            int v = px[off + c] & 0xFF;
            if (c == channel ? v < 200 : v > 60) {
                return false;
            }
        }
        return true;
    }

    /** For every video packet of {@code ts}, whether it is a keyframe. */
    private static boolean[] idrFrames(Tools tools, Path ts) throws Exception {
        String packets = tools.ffprobe("-v", "error", "-select_streams", "v", "-show_entries", "packet=flags",
                "-of", "csv=p=0", ts.toString()).stdoutToString(1 << 26).run().stdoutString();
        List<Boolean> k = new ArrayList<>();
        for (String line : packets.split("\n")) {
            if (!line.isBlank()) {
                k.add(line.trim().startsWith("K"));
            }
        }
        boolean[] out = new boolean[k.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = k.get(i);
        }
        return out;
    }

    private static String describe(OfflineHarness.Result r, List<String> overlayLog, boolean[] panel) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Packager golden file with overlay substitution: PackagerOverlayGoldenFileTest, ")
                .append(SESSION_MS / 1000).append(" scripted seconds through OfflineHarness.\n");
        sb.append("# Regenerate after an intended change: ")
                .append("mvn test -Dtest=PackagerOverlayGoldenFileTest -Dtt.golden.update=true\n");
        sb.append("[harness]\n");
        sb.append("units=").append(r.units()).append(" videoFrames=").append(r.videoFrames())
                .append(" audioFrames=").append(r.audioFrames()).append(" endPts=").append(r.endPts())
                .append(" durationMs=").append(r.durationMs()).append('\n');
        sb.append("[log]\n");
        for (String line : r.log()) {
            sb.append(line).append('\n');
        }
        sb.append("[overlay]\n");
        synchronized (overlayLog) {
            for (String line : overlayLog) {
                sb.append(line).append('\n');
            }
        }
        sb.append("# output video frames in decode order, with or without the overlay panel\n");
        sb.append("[panel]\n");
        int start = 0;
        for (int i = 1; i <= panel.length; i++) {
            if (i == panel.length || panel[i] != panel[start]) {
                sb.append("frames ").append(start).append("..").append(i - 1)
                        .append(panel[start] ? " overlay" : " no overlay").append('\n');
                start = i;
            }
        }
        return sb.toString();
    }
}
