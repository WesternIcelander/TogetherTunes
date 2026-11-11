package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Transitions on real encodes ({@code docs/program-output.md#transitions}): gaps with the next
 * item's card (a video's {@code card.mp4}, an audio item's indexed card) and with the blank card,
 * spliced between videos and audio items, keep the output's timestamps continuous (audio 1920 ticks
 * apart, silence in the gaps), last the gap's length, and decode cleanly. Needs ffmpeg and ffprobe.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TransitionGapOutputTest {
    private static final Pattern UNIT = Pattern.compile("unit \\d+ pts (\\d+)\\.\\.(\\d+) (.*)");

    @TempDir
    Path dir;

    @Test
    @Timeout(300)
    void gapsBetweenItemsPlayTheirCardsAndDecodeCleanly() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir, "smptebars");
        Entry v1 = new Entry("v1", "video1", true, false, HarnessFixture.VIDEO_MS);
        Entry a1 = new Entry("a1", "audio1", false, false, HarnessFixture.AUDIO_MS);
        Entry v2 = new Entry("v2", "video1", true, false, HarnessFixture.VIDEO_MS);
        Entry a2 = new Entry("a2", "audio1", false, false, HarnessFixture.AUDIO_MS);
        // transition card, 3 s: v1 → [a1's card] → a1 → [v2's card.mp4] → v2; then a blank 1 s gap → a2
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS)
                .at(0).gap(new ProgramCue.Gap(3000, true)).add(v1, a1, v2)
                .at(25_000).gap(new ProgramCue.Gap(1000, false)).add(a2);
        long rate = OutputEncoding.defaultFor(OutputProfile.P720_30).videoBitrateBps();
        Path ts = dir.resolve("gaps.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 48_000, rate, ts);

        // the sources in order, with their spans on the output clock
        List<String> kinds = new ArrayList<>();
        List<long[]> spans = new ArrayList<>();
        for (String line : r.log()) {
            Matcher u = UNIT.matcher(line);
            if (!u.matches()) {
                continue;
            }
            String what = u.group(3);
            String k = what.startsWith("transition card") ? "card" : what.startsWith("gap") ? "gap"
                    : what.contains("filler idle") ? "idle" : what.contains("audio item") ? "audio" : "video";
            long start = Long.parseLong(u.group(1));
            long end = Long.parseLong(u.group(2));
            if (kinds.isEmpty() || !kinds.get(kinds.size() - 1).equals(k)) {
                kinds.add(k);
                spans.add(new long[] {start, end});
            } else {
                spans.get(spans.size() - 1)[1] = end;
            }
        }
        assertEquals(List.of("video", "card", "audio", "card", "video", "gap", "audio", "idle"), kinds,
                r.log().toString());
        long frame = ProgramSource.AUDIO_FRAME_TICKS;
        assertEquals(141 * frame, spans.get(1)[1] - spans.get(1)[0], "3 s gap, in AAC frames");
        assertEquals(141 * frame, spans.get(3)[1] - spans.get(3)[0]);
        assertEquals(47 * frame, spans.get(5)[1] - spans.get(5)[0], "1 s gap");
        for (int i = 1; i < spans.size(); i++) {
            assertEquals(spans.get(i - 1)[1], spans.get(i)[0], "contiguous at " + kinds.get(i));
        }
        assertTrue(r.log().stream().noneMatch(l -> l.contains("filler loading")), "never Loading");

        HarnessFixture.assertPackets(tools, ts, r);
        HarnessFixture.assertDecodesClean(tools, ts);
    }
}
