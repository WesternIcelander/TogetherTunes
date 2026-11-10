package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The program output of an audio-only room ({@code docs/program-output.md#audio-only-rooms}),
 * through the offline harness on real encodes: every item plays as its card with its audio-only
 * file (a video's {@code audio-only.m4a} when it has one, else its {@code audio.m4a}), padded to
 * the lower audio-only rate at a constant rate; and a switch while a video plays applies from the
 * next item (the video plays on at the normal rate, everything after it at the audio-only rate).
 * Needs ffmpeg and ffprobe.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AudioOnlyRoomOutputTest {
    /** The length of {@code video1}'s audio-only file (its audio trim). */
    static final long VARIANT_MS = 4_000;
    static final long AUDIO_ONLY_RATE = 800_000;
    private static final Pattern UNIT = Pattern.compile("unit \\d+ pts (\\d+)\\.\\.(\\d+) (.*)");

    @TempDir
    Path dir;

    /** The harness media plus {@code video1}'s audio-only file and its index, as ingest makes them. */
    private HarnessFixture.Media media(Tools tools) throws Exception {
        HarnessFixture.Media m = HarnessFixture.media(tools, dir, "smptebars");
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path video = m.media().resolve("video1");
        HarnessFixture.encodeAudio(tools, video.resolve(IngestPipeline.AUDIO_ONLY), VARIANT_MS / 1000.0);
        IndexBuilder.build(tools, profile, video.resolve(IngestPipeline.CARD), video.resolve(IngestPipeline.AUDIO_ONLY),
                video.resolve(IngestPipeline.AUDIO_ONLY_INDEX), new JobLog());
        return m;
    }

    private static Entry variant(String id) {
        return new Entry(id, "video1", true, false, VARIANT_MS, true, true);
    }

    private static Entry videoAsAudio(String id) {
        return new Entry(id, "video1", true, false, HarnessFixture.VIDEO_MS, true, false);
    }

    private static Entry audio(String id) {
        return new Entry(id, "audio1", false, false, HarnessFixture.AUDIO_MS, true, false);
    }

    private static double frameBytes(long rate) {
        return rate / 8.0 * ProgramSource.AUDIO_FRAME_TICKS / 90_000 + 1_000;
    }

    @Test
    @Timeout(300)
    void audioOnlyRoomPlaysCardsWithTheAudioOnlyFilesAtTheLowerRate() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = media(tools);
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS)
                .at(0).audioOnly(true).add(variant("f"), videoAsAudio("n"), audio("a"))
                .at(6_000).pause().at(8_000).play();
        long normal = OutputEncoding.defaultFor(OutputProfile.P720_30).videoBitrateBps();
        assertTrue(AUDIO_ONLY_RATE < normal);
        Path ts = dir.resolve("audio-only.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 36_000, normal, AUDIO_ONLY_RATE, ts);
        List<String> kinds = HarnessFixture.kinds(r);
        assertFalse(kinds.contains("video"), "no video in an audio-only room: " + kinds);
        assertTrue(kinds.containsAll(List.of("as-audio", "audio", "paused", "idle")), kinds.toString());

        // the first item is the video's audio-only file: it ends after VARIANT_MS, not VIDEO_MS
        Long firstEnd = null;
        for (String line : r.log()) {
            Matcher u = UNIT.matcher(line);
            if (u.matches() && line.endsWith("(ended)")) {
                firstEnd = Long.parseLong(u.group(2));
                break;
            }
        }
        assertNotNull(firstEnd, r.log().toString());
        assertEquals(VARIANT_MS, (firstEnd - OutputClock.START) / 90, 100, "first item's length (ms)");

        HarnessFixture.assertPackets(tools, ts, r);
        // the whole session, cards and fillers alike, on the audio-only rate
        CbrPaddingTest.checkConstantRate(CbrPaddingTest.videoPackets(tools, ts), AUDIO_ONLY_RATE, 90_000, 0.02, 2_000,
                frameBytes(AUDIO_ONLY_RATE), CbrPaddingTest.parameterSetBytes(m.avcC()));
    }

    @Test
    @Timeout(300)
    void switchWhileAVideoPlaysAppliesFromTheNextItem() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = media(tools);
        // the room turns audio-only 3 s into a video: the video keeps its mode, what follows is audio-only
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS)
                .at(0).add(new Entry("v", "video1", true, false, HarnessFixture.VIDEO_MS))
                .at(3_000).audioOnly(true).add(variant("f"), audio("a"), videoAsAudio("n"));
        long normal = OutputEncoding.defaultFor(OutputProfile.P720_30).videoBitrateBps();
        Path ts = dir.resolve("switch.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 46_000, normal, AUDIO_ONLY_RATE, ts);
        List<String> kinds = HarnessFixture.kinds(r);
        assertEquals("video", kinds.get(0), kinds.toString());
        assertFalse(kinds.subList(1, kinds.size()).contains("video"), kinds.toString());
        assertTrue(kinds.containsAll(List.of("as-audio", "audio", "idle")), kinds.toString());

        // where the video ended: the first unit from another source
        long switchPts = -1;
        for (String line : r.log()) {
            Matcher u = UNIT.matcher(line);
            if (u.matches() && !u.group(3).matches("video item \\S+@.*")) { // not "video item as audio …"
                switchPts = Long.parseLong(u.group(1));
                break;
            }
        }
        assertTrue(switchPts > OutputClock.START + (HarnessFixture.VIDEO_MS - 100) * 90,
                "the video played to its end: " + switchPts);
        HarnessFixture.assertPackets(tools, ts, r);

        List<CbrPaddingTest.Packet> packets = CbrPaddingTest.videoPackets(tools, ts);
        long wrapped = OutputClock.wrap(switchPts);
        List<CbrPaddingTest.Packet> before = packets.stream().filter(p -> p.pts() < wrapped).toList();
        List<CbrPaddingTest.Packet> after = packets.stream().filter(p -> p.pts() >= wrapped).toList();
        // the video at the normal rate (its units' total within 3 %)
        int keyOverhead = CbrPaddingTest.parameterSetBytes(m.avcC());
        long bytes = 0;
        for (CbrPaddingTest.Packet p : before) {
            bytes += p.size() - 6 - (p.key() ? keyOverhead : 0);
        }
        double target = normal / 8.0 * (wrapped - before.get(0).pts()) / 90_000;
        assertEquals(1.0, bytes / target, 0.03, "video at the normal rate");
        // The output may be ahead at the switch (an encode's VBV burst at the normal rate): the
        // first unit at the audio-only rate pays that back, so it is short by exactly that much.
        double surplus = bytes - target;
        assertTrue(surplus >= -frameBytes(normal) && surplus <= normal / 8.0 * 0.5, "ahead at the switch: " + surplus);
        int second = -1;
        for (int i = 1; i < after.size(); i++) {
            if (after.get(i).key()) {
                second = i;
                break;
            }
        }
        long firstBytes = 0;
        for (int i = 0; i < second; i++) {
            firstBytes += after.get(i).size() - 6 - (after.get(i).key() ? keyOverhead : 0);
        }
        double firstTarget = AUDIO_ONLY_RATE / 8.0 * (after.get(second).pts() - after.get(0).pts()) / 90_000;
        assertEquals(firstTarget - Math.max(0, surplus), firstBytes, frameBytes(normal),
                "the first audio-only unit at the audio-only rate, less what the output was ahead");
        // everything after it at the audio-only rate
        CbrPaddingTest.checkConstantRate(after.subList(second, after.size()), AUDIO_ONLY_RATE, 90_000, 0.02, 2_000,
                frameBytes(AUDIO_ONLY_RATE), keyOverhead);
    }
}
