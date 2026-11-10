package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.SilenceFrame;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.opentest4j.TestAbortedException;

/**
 * Shared setup and checks for the offline-harness tests ({@link OfflineHarnessTest},
 * {@link PackagerGoldenFileTest}): real profile encodes laid out as ingest does, the scripted
 * 60 s cycle, and the ffmpeg/ffprobe checks of a written session.
 */
final class HarnessFixture {
    static final long VIDEO_MS = 10_300;
    static final long AUDIO_MS = 7_500;
    static final long SCRIPT_START_MS = 1_700_000_000_000L;

    private HarnessFixture() {
    }

    /**
     * ffmpeg and ffprobe from the PATH. Without them the test is skipped, except in CI (environment
     * variable {@code CI=true}, as GitHub Actions, GitLab and most CI systems set it, or
     * {@code -Dtt.requireMediaTools=true}), where it fails: the golden-file check must not pass
     * there by being skipped.
     */
    static Tools tools() {
        try {
            return RealSourcesTest.tools();
        } catch (TestAbortedException e) {
            if (mediaToolsRequired()) {
                fail("ffmpeg/ffprobe are required here (CI or -Dtt.requireMediaTools=true) but not on PATH");
            }
            throw e;
        }
    }

    static boolean mediaToolsRequired() {
        return Boolean.getBoolean("tt.requireMediaTools") || "true".equalsIgnoreCase(System.getenv("CI"));
    }

    /** The media a session plays: {@code video1} (video item), {@code audio1} (audio item), fillers. */
    record Media(Path media, byte[] avcC, Map<Filler, CardGop> fillers, byte[] silence) {
    }

    static Media media(Tools tools, Path dir) throws Exception {
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        JobLog log = new JobLog();
        // laid out as ingest does: media/<itemId>/{mezz.mp4|audio.m4a,card.mp4,index.bin}
        Path media = Files.createDirectories(dir.resolve("media"));
        Path video = Files.createDirectories(media.resolve("video1"));
        encode(tools, video.resolve(IngestPipeline.MEZZ), "testsrc2", VIDEO_MS / 1000.0, true);
        encode(tools, video.resolve(IngestPipeline.CARD), "smptebars", 2, false);
        SampleIndex vIndex = IndexBuilder.build(tools, profile, video.resolve(IngestPipeline.MEZZ),
                video.resolve(IngestPipeline.MEZZ), video.resolve(SampleIndex.FILE_NAME), log);
        Path audio = Files.createDirectories(media.resolve("audio1"));
        encode(tools, audio.resolve(IngestPipeline.CARD), "rgbtestsrc", 2, false);
        encodeAudio(tools, audio.resolve(IngestPipeline.AUDIO), AUDIO_MS / 1000.0);
        IndexBuilder.build(tools, profile, audio.resolve(IngestPipeline.CARD), audio.resolve(IngestPipeline.AUDIO),
                audio.resolve(SampleIndex.FILE_NAME), log);
        Map<Filler, CardGop> fillers = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            Path card = dir.resolve(f.name().toLowerCase() + ".mp4");
            encode(tools, card, f == Filler.IDLE ? "mandelbrot" : "testsrc", 2, false);
            fillers.put(f, CardGop.load(tools, profile, card, log));
        }
        return new Media(media, vIndex.avcC(), fillers, SilenceFrame.generate(tools, log).frame());
    }

    /** Runs {@code minutes} script cycles through the harness into {@code out}. */
    static OfflineHarness.Result run(Tools tools, Media m, int minutes, Path out) throws Exception {
        ScriptedTimeline script = new ScriptedTimeline(SCRIPT_START_MS);
        for (int i = 0; i < minutes; i++) {
            cycle(script, i * 60_000L, i);
        }
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        try (ProgramMedia sources = new ProgramMedia(m.media(), profile, m.avcC(),
                ProgramMedia.ffprobeCardLoader(tools, profile), m.fillers(), m.silence(), Runnable::run, 0,
                script::startServerMs);
                OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            return OfflineHarness.run(sources, m.avcC(), script, minutes * 60_000L, os);
        }
    }

    private static void encode(Tools tools, Path out, String video, double seconds, boolean audio)
            throws Exception {
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", video + "=s=640x360:r=30"));
        if (audio) {
            enc.addAll(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000"));
        }
        enc.addAll(List.of("-t", Double.toString(seconds), "-vf", EncodeProfile.scaleFilter(OutputEncoding.defaultFor(OutputProfile.P720_30))));
        enc.addAll(EncodeProfile.videoArgs(OutputEncoding.defaultFor(OutputProfile.P720_30)));
        if (audio) {
            enc.addAll(EncodeProfile.AUDIO_ARGS);
        } else {
            enc.add("-an");
        }
        enc.add(out.toString());
        tools.ffmpeg(enc).run();
    }

    private static void encodeAudio(Tools tools, Path out, double seconds) throws Exception {
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "sine=f=660:r=48000",
                "-t", Double.toString(seconds)));
        enc.addAll(EncodeProfile.AUDIO_ARGS);
        enc.add(out.toString());
        tools.ffmpeg(enc).run();
    }

    /**
     * One 60 s cycle at {@code t}, starting and ending idle: video item to its natural end → audio
     * item with a pause → video item as audio, seeked → empty queue → video item seeked, an
     * unrecognised state, an audio item queued while it plays → skip → empty queue → add + clear.
     */
    static void cycle(ScriptedTimeline s, long t, int n) {
        Entry v = new Entry("v" + n, "video1", true, false, VIDEO_MS);
        Entry a = new Entry("a" + n, "audio1", false, false, AUDIO_MS);
        Entry asAudio = new Entry("s" + n, "video1", true, true, VIDEO_MS);
        Entry v2 = new Entry("w" + n, "video1", true, false, VIDEO_MS);
        Entry a2 = new Entry("b" + n, "audio1", false, false, AUDIO_MS);
        Entry v3 = new Entry("x" + n, "video1", true, false, VIDEO_MS);
        s.at(t).add(v, a, asAudio)
                .at(t + 13_000).pause()
                .at(t + 16_000).play()
                .at(t + 23_000).seek(6_000)
                .at(t + 32_000).add(v2)
                .at(t + 35_000).seek(2_000)
                .at(t + 37_000).unknownState()
                .at(t + 39_000).play()
                .at(t + 42_000).add(a2)
                .at(t + 46_000).skip()
                .at(t + 48_000).add(v3)
                .at(t + 50_000).clear();
    }

    /** What played, unit by unit, with consecutive repeats merged. */
    static List<String> kinds(OfflineHarness.Result r) {
        List<String> kinds = new ArrayList<>();
        for (String line : r.log()) {
            if (!line.startsWith("unit ")) {
                continue;
            }
            String k = line.contains("filler paused") ? "paused" : line.contains("filler idle") ? "idle"
                    : line.contains("filler loading") ? "loading" : line.contains("video item as audio") ? "as-audio"
                    : line.contains("audio item") ? "audio" : "video";
            if (kinds.isEmpty() || !kinds.get(kinds.size() - 1).equals(k)) {
                kinds.add(k);
            }
        }
        return kinds;
    }

    /** {@code ffmpeg -v error -i out.ts -f null -} prints nothing. */
    static void assertDecodesClean(Tools tools, Path out) throws Exception {
        ToolResult decode = tools.ffmpeg("-v", "error", "-i", out.toString(), "-f", "null", "-")
                .timeout(Duration.ofMinutes(30)).run();
        assertEquals("", decode.stderrTail().trim(), "ffmpeg -v error prints nothing");
    }

    /**
     * ffprobe's packets: both streams start at {@link OutputClock#START}, DTS = PTS, video PTS
     * strictly increasing, audio deltas exactly 1920 (90 kHz) everywhere, and every frame the
     * harness wrote is there.
     */
    static void assertPackets(Tools tools, Path out, OfflineHarness.Result r) throws Exception {
        String packets = tools.ffprobe("-v", "error", "-show_entries", "packet=codec_type,pts,dts",
                "-of", "csv=p=0", out.toString()).stdoutToString(1 << 30)
                .timeout(Duration.ofMinutes(30)).run().stdoutString();
        long lastVideo = Long.MIN_VALUE;
        long lastAudio = Long.MIN_VALUE;
        long nv = 0;
        long na = 0;
        for (String line : packets.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.trim().split(",");
            long pts = Long.parseLong(f[1]);
            assertEquals(f[1], f[2], "DTS = PTS");
            if (f[0].equals("video")) {
                if (nv == 0) {
                    assertEquals(OutputClock.START, pts);
                }
                assertTrue(pts > lastVideo, "video PTS strictly increasing at " + pts);
                lastVideo = pts;
                nv++;
            } else {
                assertEquals("audio", f[0], line);
                if (na == 0) {
                    assertEquals(OutputClock.START, pts);
                } else {
                    assertEquals(1920, pts - lastAudio, "audio delta at " + pts);
                }
                lastAudio = pts;
                na++;
            }
        }
        assertEquals(r.videoFrames(), nv, "every access unit");
        assertEquals(r.audioFrames(), na, "every audio frame, no gaps at splices");
    }
}
