package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.EncodeProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReferenceCardTest {
    @TempDir
    Path dir;

    @Test
    void checksTheAvcCRecord() {
        byte[] good = HexFormat.of().parseHex("0164001fffe1");
        assertThrows(IOException.class, () -> ReferenceCard.checkAvcC(good), "too short");
        byte[] ok = HexFormat.of().parseHex("0164001fffe100");
        assertDoesNotThrow(() -> ReferenceCard.checkAvcC(ok));
        assertThrows(IOException.class, () -> ReferenceCard.checkAvcC(HexFormat.of().parseHex("0264001fffe100")));
        assertThrows(IOException.class, () -> ReferenceCard.checkAvcC(HexFormat.of().parseHex("0142001fffe100")));
        assertThrows(IOException.class, () -> ReferenceCard.checkAvcC(HexFormat.of().parseHex("0164001fffe000")));
    }

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

    /** A mezzanine-style encode of a moving lavfi source, with the profile constant. */
    private Path encodeVideo(Tools tools, OutputEncoding profile) throws Exception {
        Path out = dir.resolve("video-" + profile + ".mp4");
        List<String> args = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=3",
                "-vf", EncodeProfile.scaleFilter(profile)));
        args.addAll(EncodeProfile.videoArgs(profile));
        args.addAll(List.of("-an", out.toString()));
        tools.ffmpeg(args).run();
        return out;
    }

    private static List<String> tempFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).toList();
        }
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(120)
    void createsTheReferenceAndMatchesOtherEncodes(OutputProfile p) throws Exception {
        OutputEncoding profile = OutputEncoding.defaultFor(p);
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        JobLog log = new JobLog();
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, log);

        Path profileDir = cards.resolve(profile.id());
        assertEquals(profileDir.resolve("reference.mp4"), ref.card());
        assertTrue(Files.size(ref.card()) > 0);
        assertTrue(ref.created());
        assertFalse(ref.encoderChanged());
        assertArrayEquals(ref.avcC(), Files.readAllBytes(profileDir.resolve("reference.avcC")));
        assertEquals(List.of(), tempFiles(profileDir), "temporary files are cleaned up");
        assertTrue(log.tail().contains("libx264"), "ffmpeg command is logged");

        byte[] avcC = ref.avcC();
        assertEquals(1, avcC[0]);
        assertEquals(100, avcC[1] & 0xff, "High profile");
        assertEquals(profile.profile() == OutputProfile.P720_30 ? 40 : 41, avcC[3] & 0xff, "level");

        // The card is one 2 s GOP: 60 frames at the profile size.
        String probe = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-count_frames",
                "-show_entries", "stream=width,height,nb_read_frames,has_b_frames", "-of", "default=noprint_wrappers=1",
                ref.card().toString()).stdoutToString(65536).run().stdoutString();
        assertTrue(probe.contains("width=" + profile.width()), probe);
        assertTrue(probe.contains("height=" + profile.height()), probe);
        assertTrue(probe.contains("nb_read_frames=60"), probe);
        assertTrue(probe.contains("has_b_frames=0"), probe);

        // A mezzanine-style encode of a different source has the same avcC.
        byte[] video = ReferenceCard.readAvcC(encodeVideo(tools, profile));
        assertTrue(ref.matches(video));
        assertDoesNotThrow(() -> ref.verify(video, "video.mp4"));

        // Second start: the stored reference is kept and still reproduced.
        ReferenceCard again = ReferenceCard.ensure(tools, cards, profile, null);
        assertFalse(again.created());
        assertFalse(again.encoderChanged());
        assertArrayEquals(avcC, again.avcC());

        // A deleted card file is restored when the encoder still matches.
        Files.delete(ref.card());
        ReferenceCard restored = ReferenceCard.ensure(tools, cards, profile, null);
        assertTrue(Files.exists(restored.card()));
        assertFalse(restored.created());
    }

    @Test
    @Timeout(120)
    void profilesHaveDifferentReferences() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        ReferenceCard p720 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        ReferenceCard p1080 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P1080_30), null);
        assertFalse(Arrays.equals(p720.avcC(), p1080.avcC()));
        ReferenceCard.AvcCMismatchException e = assertThrows(ReferenceCard.AvcCMismatchException.class,
                () -> p720.verify(p1080.avcC(), "mezz.mp4"));
        assertTrue(e.getMessage().startsWith("mezz.mp4: avcC differs from the 720p30-3000k reference card"), e.getMessage());
        assertThrows(ReferenceCard.AvcCMismatchException.class, () -> p720.verify(null, "x"));
    }

    /** Each bitrate has its own reference (its own cards directory), and they differ. */
    @Test
    @Timeout(120)
    void bitratesHaveDifferentReferences() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        ReferenceCard r3000 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        ReferenceCard r4000 = ReferenceCard.ensure(tools, cards, new OutputEncoding(OutputProfile.P720_30, 4000), null);
        assertEquals(cards.resolve("720p30-3000k").resolve(ReferenceCard.CARD_FILE), r3000.card());
        assertEquals(cards.resolve("720p30-4000k").resolve(ReferenceCard.CARD_FILE), r4000.card());
        assertTrue(r4000.created());
        assertFalse(Arrays.equals(r3000.avcC(), r4000.avcC()));
        assertThrows(ReferenceCard.AvcCMismatchException.class, () -> r3000.verify(r4000.avcC(), "mezz.mp4"));
    }

    @Test
    @Timeout(120)
    void storedReferenceWinsWhenTheEncoderChanged() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        byte[] actual = ReferenceCard.ensure(tools, cards, profile, null).avcC();

        // Simulate an older encoder: the stored avcC differs from what ffmpeg makes now.
        Path avcFile = cards.resolve(profile.id()).resolve(ReferenceCard.AVCC_FILE);
        byte[] older = actual.clone();
        older[older.length - 1] ^= 0x01;
        Files.write(avcFile, older);
        byte[] cardBefore = Files.readAllBytes(cards.resolve(profile.id()).resolve(ReferenceCard.CARD_FILE));

        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        assertTrue(ref.encoderChanged());
        assertFalse(ref.created());
        assertArrayEquals(older, ref.avcC(), "the stored reference stays the reference");
        assertFalse(ref.matches(actual));
        assertArrayEquals(older, Files.readAllBytes(avcFile), "stored reference untouched");
        assertArrayEquals(cardBefore, Files.readAllBytes(ref.card()), "stored card untouched");

        // A malformed stored reference is an error, not silently replaced.
        Files.write(avcFile, new byte[] {9, 9});
        assertThrows(IOException.class, () -> ReferenceCard.ensure(tools, cards, profile, null));
    }

    @Test
    void missingToolsFail() {
        Tools none = new Tools("/nonexistent/ffmpeg", "/nonexistent/ffprobe", null);
        ToolException e = assertThrows(ToolException.class,
                () -> ReferenceCard.ensure(none, dir.resolve("cards"), OutputEncoding.defaultFor(OutputProfile.P720_30), null));
        assertEquals(ToolException.Kind.START_FAILED, e.kind());
        assertFalse(Files.exists(dir.resolve("cards").resolve("720p30-3000k").resolve(ReferenceCard.AVCC_FILE)));
    }
}
