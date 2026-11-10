package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.rooms.PlaybackState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        Tools tools = HarnessFixture.tools();
        int minutes = Integer.getInteger("tt.harness.minutes", 1);
        String keep = System.getProperty("tt.harness.out");
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        Path out = keep != null ? Path.of(keep) : dir.resolve("out.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, minutes, out);
        assertTrue(r.durationMs() >= minutes * 60_000L && r.durationMs() < minutes * 60_000L + 2100,
                "duration " + r.durationMs());
        assertEquals(Files.size(out), r.bytes());

        List<String> kinds = HarnessFixture.kinds(r);
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

        HarnessFixture.assertDecodesClean(tools, out);
        HarnessFixture.assertPackets(tools, out, r);
    }
}
