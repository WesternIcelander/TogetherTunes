package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.EncodeProfile;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CardEncoderTest {
    @TempDir
    Path dir;

    static String onPath(String name) {
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

    static List<String> tempFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).toList();
        }
    }

    /**
     * Checks a card's structure: one 2 s GOP at the profile size, 60 frames 1/30 s apart from 0,
     * an IDR first and P-frames only after it, no B-frames.
     */
    static void checkCard(Tools tools, Path card, OutputEncoding profile) throws Exception {
        String stream = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,profile,width,height,pix_fmt,has_b_frames,r_frame_rate,sample_aspect_ratio",
                "-of", "default=noprint_wrappers=1", card.toString()).stdoutToString(65536).run().stdoutString();
        assertTrue(stream.contains("codec_name=h264"), stream);
        assertTrue(stream.contains("profile=High"), stream);
        assertTrue(stream.contains("width=" + profile.width()), stream);
        assertTrue(stream.contains("height=" + profile.height()), stream);
        assertTrue(stream.contains("pix_fmt=yuv420p"), stream);
        assertTrue(stream.contains("has_b_frames=0"), stream);
        assertTrue(stream.contains("r_frame_rate=30/1"), stream);
        assertTrue(stream.contains("sample_aspect_ratio=1:1"), stream);

        String frames = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries",
                "frame=key_frame,pict_type,pts", "-of", "csv=p=0", card.toString())
                .stdoutToString(1 << 20).run().stdoutString().trim();
        List<String> lines = frames.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        assertEquals(EncodeProfile.GOP_FRAMES, lines.size(), frames);
        long firstPts = -1;
        long step = -1;
        for (int i = 0; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            // csv order follows ffprobe's frame field order: key_frame, pts, pict_type
            int key = Integer.parseInt(f[0]);
            long pts = Long.parseLong(f[1]);
            String type = f[2];
            if (i == 0) {
                assertEquals(1, key, "first frame is a keyframe");
                assertEquals("I", type);
                firstPts = pts;
                assertEquals(0, pts, "starts at 0");
            } else {
                assertEquals(0, key, "frame " + i + " is not a keyframe");
                assertEquals("P", type, "frame " + i);
                if (i == 1) {
                    step = pts - firstPts;
                    assertTrue(step > 0);
                } else {
                    assertEquals(firstPts + i * step, pts, "constant frame spacing at frame " + i);
                }
            }
        }
    }

    @Test
    void commandIsTheDocumentedCardCommand() {
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        List<String> args = CardEncoder.command(Path.of("card.png"), Path.of("card.mp4"), profile);
        String joined = String.join(" ", args);
        assertTrue(joined.startsWith("-y -loop 1 -framerate 30 -i card.png -t 2 -vf "
                + EncodeProfile.scaleFilter(profile) + " "), joined);
        assertTrue(joined.contains(String.join(" ", EncodeProfile.videoArgs(profile))), joined);
        assertTrue(joined.endsWith(" -an -f mp4 card.mp4"), joined);
        assertTrue(EncodeProfile.scaleFilter(profile).contains("setsar=1"));
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(120)
    void encodesAnItemCardMatchingTheReference(OutputProfile p) throws Exception {
        OutputEncoding profile = OutputEncoding.defaultFor(p);
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        BufferedImage image = new CardRenderer(profile.profile()).renderItem(
                new CardRenderer.ItemCard("A Song With A Reasonably Long Title", "Some Artist", "Some Album", null));

        Path out = dir.resolve("media").resolve("item").resolve("card.mp4");
        JobLog log = new JobLog();
        CardEncoder.encode(tools, image, out, ref, log);

        assertTrue(Files.isRegularFile(out));
        assertArrayEquals(ref.avcC(), ReferenceCard.readAvcC(out));
        checkCard(tools, out, profile);
        long size = Files.size(out);
        // CBR: a 2 s card is R × 2 s / 8 bytes (padded with filler NALs) plus a little MP4 overhead
        long expected = profile.videoBitrateBps() * 2 / 8;
        assertTrue(size > expected * 0.97 && size < expected * 1.03 + 10_000, "card size " + size + ", expected " + expected);
        assertEquals(List.of(), tempFiles(out.getParent()), "temporary files are cleaned up");
        assertTrue(log.tail().contains("-loop 1"), "ffmpeg command is logged");
    }

    @Test
    @Timeout(120)
    void otherImageSizesAreLetterboxedToTheProfile() throws Exception {
        Tools tools = tools();
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard ref = ReferenceCard.ensure(tools, dir.resolve("cards"), profile, null);
        BufferedImage square = new BufferedImage(501, 501, BufferedImage.TYPE_INT_RGB);
        Path out = dir.resolve("square.mp4");
        CardEncoder.encode(tools, square, out, ref, null);
        checkCard(tools, out, profile);
    }

    @Test
    @Timeout(120)
    void aMismatchingAvcCIsNotPublished() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        // Simulate an encoder change: the stored reference avcC differs from what ffmpeg makes now.
        ReferenceCard p720 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        Path avcFile = cards.resolve("720p30-3000k-v2").resolve(ReferenceCard.AVCC_FILE);
        byte[] other = p720.avcC();
        other[other.length - 1] ^= 0x01;
        Files.write(avcFile, other);
        ReferenceCard changed = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        assertTrue(changed.encoderChanged());

        Path out = dir.resolve("out").resolve("card.mp4");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "previous");
        BufferedImage image = new CardRenderer(OutputProfile.P720_30).renderFiller(CardRenderer.Filler.PAUSED);
        ReferenceCard.AvcCMismatchException e = assertThrows(ReferenceCard.AvcCMismatchException.class,
                () -> CardEncoder.encode(tools, image, out, changed, null));
        assertTrue(e.getMessage().startsWith("card.mp4: avcC differs"), e.getMessage());
        assertEquals("previous", Files.readString(out), "existing file untouched");
        assertEquals(List.of(), tempFiles(out.getParent()));
    }

    @Test
    void missingToolsLeaveNothingBehind() throws Exception {
        Tools tools = tools();
        ReferenceCard ref = ReferenceCard.ensure(tools, dir.resolve("cards"), OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        Tools none = new Tools("/nonexistent/ffmpeg", "/nonexistent/ffprobe", null);
        Path out = dir.resolve("x").resolve("card.mp4");
        ToolException e = assertThrows(ToolException.class,
                () -> CardEncoder.encode(none, new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), out, ref, null));
        assertEquals(ToolException.Kind.START_FAILED, e.kind());
        assertFalse(Files.exists(out));
        assertEquals(List.of(), tempFiles(out.getParent()));
    }
}
