package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
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
        assertNull(ref.spliceProblem(), "the video path matches the reference");
        assertDoesNotThrow(ref::requireSpliceableVideo);
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
        assertTrue(e.getMessage().startsWith("mezz.mp4: avcC differs from the 720p30-3000k-v3 reference card"), e.getMessage());
        assertThrows(ReferenceCard.AvcCMismatchException.class, () -> p720.verify(null, "x"));
    }

    /**
     * Each bitrate has its own reference (its own cards directory: the cap still re-encodes), but
     * the bitrate is not in the SPS (no HRD), so their avcC are the same.
     */
    @Test
    @Timeout(120)
    void bitratesHaveTheirOwnReferencesWithTheSameAvcC() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        ReferenceCard r3000 = ReferenceCard.ensure(tools, cards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        ReferenceCard r4000 = ReferenceCard.ensure(tools, cards, new OutputEncoding(OutputProfile.P720_30, 4000), null);
        assertEquals(cards.resolve("720p30-3000k-v3").resolve(ReferenceCard.CARD_FILE), r3000.card());
        assertEquals(cards.resolve("720p30-4000k-v3").resolve(ReferenceCard.CARD_FILE), r4000.card());
        assertTrue(r4000.created());
        assertArrayEquals(r3000.avcC(), r4000.avcC());
        r3000.verify(r4000.avcC(), "mezz.mp4");
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

    /**
     * The startup check of the configured presets against the real encoder: every preset matches
     * the reference; with a stored reference from "another encoder" none does, and
     * {@link EncodePresets} then falls back (the forced-mismatch case).
     */
    @Test
    @Timeout(180)
    void presetsMatchTheReference() throws Exception {
        Tools tools = tools();
        Path cards = dir.resolve("cards");
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard ref = ReferenceCard.ensure(tools, cards, profile, null);
        for (X264Preset preset : X264Preset.values()) {
            assertTrue(ref.presetMatches(tools, cards, preset), preset.id());
        }
        try (var files = Files.list(cards.resolve(profile.id()))) {
            assertEquals(List.of(ReferenceCard.AVCC_FILE, ReferenceCard.CARD_FILE),
                    files.map(f -> f.getFileName().toString()).sorted().toList(), "no temporary files left");
        }
        EncodePresets presets = EncodePresets.checkedAgainst(ref, tools, cards, () -> X264Preset.FASTER,
                () -> X264Preset.SLOWER);
        assertEquals(List.of(), presets.checkConfigured());
        assertEquals(X264Preset.SLOWER, presets.quality());

        // a stored reference this encoder doesn't reproduce: no preset matches, all fall back
        Path avcFile = cards.resolve(profile.id()).resolve(ReferenceCard.AVCC_FILE);
        byte[] other = ref.avcC();
        other[other.length - 1] ^= 0x01;
        Files.write(avcFile, other);
        ReferenceCard changed = ReferenceCard.ensure(tools, cards, profile, null);
        assertFalse(changed.presetMatches(tools, cards, X264Preset.SLOW));
        assertFalse(changed.presetMatches(tools, cards, X264Preset.VERYFAST));
        EncodePresets fallback = EncodePresets.checkedAgainst(changed, tools, cards, () -> X264Preset.FASTER,
                () -> X264Preset.SLOW);
        assertEquals(2, fallback.checkConfigured().size());
        assertEquals(X264Preset.REFERENCE, fallback.quick());
        assertEquals(X264Preset.REFERENCE, fallback.quality());
    }

    /**
     * The reference avcC of each profile as ffmpeg 6.1.1 wrote it before the colour description
     * was pinned in x264 and the filter chain (2026-11-11): the pins must not change a byte of it,
     * so existing servers keep their references and libraries. Every ffmpeg in the tested matrix
     * (5.1 to master) writes exactly these bytes now; before the pins, 7.x and later wrote
     * "unspecified" primaries/transfer ({@code d40808…}) for the card's untagged image.
     */
    static final String AVCC_720P = "01640028ffe1001d67640028acb280a00b7602d40404050000030001000003003c0f1832580100"
            + "0568e9332c8bfdf8f800";
    static final String AVCC_1080P = "01640029ffe1001d67640029acb280f0044fcb80b501010140000003004000000f03c60c960100"
            + "0568e9332c8bfdf8f800";

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(120)
    void theReferenceAvcCIsUnchanged(OutputProfile p) throws Exception {
        OutputEncoding encoding = OutputEncoding.defaultFor(p);
        ReferenceCard ref = ReferenceCard.ensure(tools(), dir.resolve("cards-" + p), encoding, null);
        assertEquals(p == OutputProfile.P720_30 ? AVCC_720P : AVCC_1080P, HexFormat.of().formatHex(ref.avcC()));
        assertNull(ref.spliceProblem());
    }

    /**
     * The video path self-check against a reference it doesn't match (the one ffmpeg 7.x wrote
     * before the pins: unspecified primaries and transfer): names the first test clip and the
     * differing SPS fields; ingest's up-front refusal carries that text.
     */
    @Test
    @Timeout(120)
    void videoPathCheckNamesTheDifferingFields() throws Exception {
        OutputEncoding encoding = OutputEncoding.DEFAULT;
        Path cards = Files.createDirectories(dir.resolve("check"));
        byte[] unpinned = HexFormat.of().parseHex(AVCC_720P.replace("d40404", "d40808"));
        String problem = ReferenceCard.checkVideoPath(tools(), cards, encoding, unpinned, null);
        assertEquals("a BT.709-tagged video is encoded with a different SPS than the 720p30-3000k-v3 reference card:"
                + " colour_primaries 1 (BT.709) instead of 2 (unspecified),"
                + " transfer_characteristics 1 (BT.709) instead of 2 (unspecified)", problem);
        assertEquals(List.of(), tempFiles(cards), "temporary files are cleaned up");
        assertNull(ReferenceCard.checkVideoPath(tools(), cards, encoding, HexFormat.of().parseHex(AVCC_720P), null));

        ReferenceCard ref = ReferenceCard.forTest(encoding, cards.resolve("x.mp4"), unpinned, problem);
        IngestFailure e = assertThrows(IngestFailure.class, ref::requireSpliceableVideo);
        assertEquals("This ffmpeg produces encodes that can't be spliced: " + problem, e.getMessage());
        assertTrue(ReferenceCard.spliceProblemMessage(problem).startsWith(
                "This ffmpeg produces encodes that can't be spliced: a BT.709-tagged video"));
    }

    @Test
    void missingToolsFail() {
        Tools none = new Tools("/nonexistent/ffmpeg", "/nonexistent/ffprobe", null);
        ToolException e = assertThrows(ToolException.class,
                () -> ReferenceCard.ensure(none, dir.resolve("cards"), OutputEncoding.defaultFor(OutputProfile.P720_30), null));
        assertEquals(ToolException.Kind.START_FAILED, e.kind());
        assertFalse(Files.exists(dir.resolve("cards").resolve("720p30-3000k-v3").resolve(ReferenceCard.AVCC_FILE)));
    }
}
