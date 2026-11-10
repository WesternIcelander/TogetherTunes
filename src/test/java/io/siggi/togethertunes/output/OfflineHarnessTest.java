package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.siggi.togethertunes.rooms.PlaybackState;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline harness ({@code docs/program-output.md#testing-the-packager}): a scripted session of
 * video items, audio items, a video item played as audio, pauses, seeks, skips, an unrecognised
 * state and empty-queue periods, run through the real follower, builder and muxer over real profile
 * encodes, written to a file without pacing and checked with ffmpeg and ffprobe (needs both).
 *
 * <p>One script cycle is 60 s. For a longer session (the M4 "done when" check), run e.g.
 * {@code mvn test -Dtest=OfflineHarnessTest -Dtt.harness.minutes=30 -Dtt.harness.out=/path/out.ts};
 * the file is kept when {@code tt.harness.out} is set.
 */
class OfflineHarnessTest {
    @TempDir
    Path dir;

    static final long VIDEO_MS = 10_300;
    static final long AUDIO_MS = 7_500;

    private static void encode(Tools tools, Path out, String video, double seconds, boolean audio)
            throws Exception {
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", video + "=s=640x360:r=30"));
        if (audio) {
            enc.addAll(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000"));
        }
        enc.addAll(List.of("-t", Double.toString(seconds), "-vf", EncodeProfile.scaleFilter(OutputProfile.P720_30)));
        enc.addAll(EncodeProfile.videoArgs(OutputProfile.P720_30));
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

    @Test
    void scriptedTimelineActsLikeARoom() {
        Entry v = new Entry("v", "video1", true, false, 10_000);
        Entry a = new Entry("a", "audio1", false, false, 5_000);
        ScriptedTimeline s = new ScriptedTimeline(1_000_000).at(0).add(v, a).at(4_000).pause().at(6_000).play()
                .at(7_000).seek(9_000).at(20_000).add(v).at(22_000).unknownState().at(23_000).skip();
        assertEquals(1, s.advanceTo(0).size());
        ProgramCue c = s.cue();
        assertEquals(PlaybackState.PLAYING, c.state());
        assertEquals(v, c.current());
        assertEquals(a, c.next());
        assertEquals(1_000_000, c.anchorServerMs());
        assertEquals(0, s.advanceTo(0).size(), "already there");

        List<ScriptedTimeline.Change> ch = s.advanceTo(9_999);
        assertEquals(List.of("pause at 4000 ms", "play from 4000 ms", "seek to 9000 ms", "natural end of v"),
                ch.stream().map(ScriptedTimeline.Change::what).toList());
        ProgramCue paused = ch.get(0).cue();
        assertEquals(PlaybackState.PAUSED, paused.state());
        assertEquals(4_000, paused.positionAt(1_005_000));
        // seek at 7 s to 9 s: natural end at 8 s
        ScriptedTimeline.Change end = ch.get(3);
        assertEquals(8_000, end.atMs());
        assertEquals(a, end.cue().current());
        assertEquals("v", end.cue().playedOut());
        assertEquals(null, end.cue().next());

        ch = s.advanceTo(20_000);
        assertEquals(List.of("natural end of a", "add [v]"), ch.stream().map(ScriptedTimeline.Change::what).toList());
        assertEquals(13_000, ch.get(0).atMs());
        assertEquals(ProgramCue.IDLE, ch.get(0).cue(), "empty queue → idle");
        assertEquals(PlaybackState.PLAYING, ch.get(1).cue().state());
        assertEquals(1_020_000, ch.get(1).cue().anchorServerMs(), "added while idle → plays from 0 now");
        assertEquals(null, ch.get(1).cue().playedOut());

        ch = s.advanceTo(40_000);
        assertEquals(List.of("unknown state", "skip v"), ch.stream().map(ScriptedTimeline.Change::what).toList());
        assertEquals(null, ch.get(0).cue().state(), "unrecognised state");
        assertEquals(ProgramCue.IDLE, ch.get(1).cue());
        assertThrows(IllegalStateException.class, () -> s.pause(), "script already running");
        assertThrows(IllegalArgumentException.class, () -> new ScriptedTimeline(0).at(5).at(4));
    }

    @Test
    @Timeout(3600)
    void scriptedSessionToFileIsClean() throws Exception {
        Tools tools = RealSourcesTest.tools();
        OutputProfile profile = OutputProfile.P720_30;
        JobLog log = new JobLog();
        int minutes = Integer.getInteger("tt.harness.minutes", 1);
        String keep = System.getProperty("tt.harness.out");

        // media laid out as ingest does: media/<itemId>/{mezz.mp4|audio.m4a,card.mp4,index.bin}
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
        SilenceFrame silence = SilenceFrame.generate(tools, log);

        ScriptedTimeline script = new ScriptedTimeline(1_700_000_000_000L);
        for (int i = 0; i < minutes; i++) {
            cycle(script, i * 60_000L, i);
        }

        Path out = keep != null ? Path.of(keep) : dir.resolve("out.ts");
        OfflineHarness.Result r;
        try (ProgramMedia sources = new ProgramMedia(media, profile, vIndex.avcC(),
                ProgramMedia.ffprobeCardLoader(tools, profile), fillers, silence.frame(), Runnable::run, 0,
                script::startServerMs);
                OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            r = OfflineHarness.run(sources, vIndex.avcC(), script, minutes * 60_000L, os);
        }
        assertTrue(r.durationMs() >= minutes * 60_000L && r.durationMs() < minutes * 60_000L + 2100,
                "duration " + r.durationMs());
        assertEquals(Files.size(out), r.bytes());

        // what played, in order, per cycle
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
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < minutes; i++) {
            // the unrecognised state shows as paused; the skip and the clear as idle
            expected.addAll(List.of("video", "audio", "paused", "audio", "as-audio", "idle", "video", "paused",
                    "video", "audio", "idle", "video", "idle"));
        }
        List<String> merged = new ArrayList<>();
        for (String k : expected) {
            if (merged.isEmpty() || !merged.get(merged.size() - 1).equals(k)) {
                merged.add(k);
            }
        }
        assertFalse(kinds.contains("loading"), "everything was loadable: " + kinds);
        assertEquals(merged, kinds, String.join("\n", r.log()));
        assertTrue(r.log().stream().anyMatch(l -> l.matches("unit \\d+ .*video item as audio video1@[1-9]\\d*ms")),
                "the seek in the video item played as audio");
        assertTrue(r.log().stream().anyMatch(l -> l.matches("unit \\d+ .*video item video1@[1-9]\\d*ms")),
                "the seek in the video item");

        ToolResult decode = tools.ffmpeg("-v", "error", "-i", out.toString(), "-f", "null", "-")
                .timeout(java.time.Duration.ofMinutes(30)).run();
        assertEquals("", decode.stderrTail().trim(), "ffmpeg -v error prints nothing");

        String packets = tools.ffprobe("-v", "error", "-show_entries", "packet=codec_type,pts,dts",
                "-of", "csv=p=0", out.toString()).stdoutToString(1 << 30)
                .timeout(java.time.Duration.ofMinutes(30)).run().stdoutString();
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
