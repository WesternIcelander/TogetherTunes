package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.TrimPlan.Mode;
import io.siggi.togethertunes.ingest.TrimPlan.Piece;
import io.siggi.togethertunes.ingest.TrimPlan.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link TrimPlan} and {@link EditSettings}: which frames are kept and which are encoded again. */
class TrimPlanTest {
    /** Frames {@code [from, to)} of a current encode starting at {@code curStartMs}, kept. */
    private static Piece keep(long curStartMs, int from, int to, int firstGop) {
        return new Piece(Source.REUSE, from, to - from, TrimPlan.frameUs(curStartMs, from), firstGop);
    }

    private static Piece keep(long curStartMs, int from, int to) {
        return keep(curStartMs, from, to, 60);
    }

    /** {@code frames} frames encoded from {@code startUs} of the original. */
    private static Piece encode(int frames, long startUs, int firstGop) {
        return new Piece(Source.ENCODE, -1, frames, startUs, firstGop);
    }

    private static Piece encode(int frames, long startUs) {
        return encode(frames, startUs, 60);
    }

    @Test
    void frames() {
        assertEquals(195, TrimPlan.frames(6500));
        assertEquals(196, TrimPlan.frames(6513));
        assertEquals(1, TrimPlan.frames(1));
        assertEquals(0, TrimPlan.frames(0));
        assertEquals(1_500_000, TrimPlan.frameUs(0, 45));
        assertEquals(2_533_333, TrimPlan.frameUs(2500, 1));
    }

    @Test
    void endEarlierCutsOnly() {
        TrimPlan.Plan p = TrimPlan.plan(0, 390, 0, 11_500);
        assertEquals(Mode.SMART, p.mode());
        assertEquals(List.of(keep(0, 0, 345)), p.pieces());
        assertTrue(p.cutOnly());
        assertEquals(0, p.encodedFrames());
        assertEquals(60, p.firstGop());
    }

    @Test
    void endLaterEncodesFromTheLastGop() {
        // the last GOP was cut short (345 frames: GOP 5 has 45)
        TrimPlan.Plan p = TrimPlan.plan(0, 345, 0, 12_500);
        assertEquals(List.of(keep(0, 0, 300), encode(75, 10_000_000)), p.pieces());
        assertEquals(75, p.encodedFrames());
        // a whole last GOP is encoded again too
        assertEquals(List.of(keep(0, 0, 120), encode(120, 4_000_000)), TrimPlan.plan(0, 180, 0, 8000).pieces());
        // with a short first GOP the GOPs after it are at 45 + 60·k: the last starts at frame 165
        assertEquals(List.of(keep(2500, 0, 165, 45), encode(60, 8_000_000)),
                TrimPlan.plan(2500, 195, 45, 2500, 10_000).pieces());
    }

    @Test
    void startOnTheGridKeepsTheGrid() {
        assertEquals(List.of(keep(0, 120, 375)), TrimPlan.plan(0, 375, 4000, 12_500).pieces());
        TrimPlan.Plan earlier = TrimPlan.plan(4000, 255, 2000, 12_500);
        assertEquals(List.of(encode(60, 2_000_000), keep(4000, 0, 255)), earlier.pieces());
        assertEquals(60, earlier.firstGop());
        assertEquals(List.of(encode(60, 2_000_000), keep(4000, 0, 240), encode(30, 12_000_000)),
                TrimPlan.plan(4000, 255, 2000, 13_000).pieces());
        // with a non-zero current start, the grid is curStart + 2 s × k
        assertEquals(List.of(keep(1500, 60, 150)), TrimPlan.plan(1500, 300, 3500, 6500).pieces());
        // two GOPs earlier: a whole 2 s first GOP, not a short one
        assertEquals(List.of(encode(120, 0), keep(4000, 0, 255)), TrimPlan.plan(4000, 255, 0, 12_500).pieces());
    }

    @Test
    void aStartOffTheGridEncodesOnlyUpToTheNextKeyframe() {
        // later: [2.5 s, 4 s) is encoded as a short first GOP of 45 frames, the rest is kept
        TrimPlan.Plan later = TrimPlan.plan(0, 390, 2500, 9000);
        assertEquals(Mode.SMART, later.mode());
        assertEquals(List.of(encode(45, 2_500_000, 45), keep(0, 120, 270)), later.pieces());
        assertEquals(45, later.firstGop());
        assertEquals(195, later.frames());
        // earlier: [3.5 s, 4 s) before the current start
        assertEquals(List.of(encode(15, 3_500_000, 15), keep(4000, 0, 255)),
                TrimPlan.plan(4000, 255, 3500, 12_500).pieces());
        // earlier by more than a GOP: a short first GOP, then whole ones
        assertEquals(List.of(encode(75, 1_500_000, 15), keep(4000, 0, 255)),
                TrimPlan.plan(4000, 255, 1500, 12_500).pieces());
        // within half a frame of a keyframe: that keyframe, nothing encoded
        assertTrue(TrimPlan.plan(0, 390, 1990, 9000).cutOnly());
        assertEquals(List.of(encode(1, 1_980_000, 1), keep(0, 60, 270)), TrimPlan.plan(0, 390, 1980, 9000).pieces());
    }

    @Test
    void aShortFirstGopIsKeptOnlyAtTheFront() {
        // the current encode starts at 2.5 s with a 45-frame first GOP: keyframes at 2.5, 4, 6, 8 s
        assertEquals(List.of(keep(2500, 0, 165, 45)), TrimPlan.plan(2500, 195, 45, 2500, 8000).pieces(),
                "same start: kept as it is");
        assertEquals(45, TrimPlan.plan(2500, 195, 45, 2500, 8000).firstGop());
        TrimPlan.Plan onto = TrimPlan.plan(2500, 195, 45, 4000, 9000);
        assertEquals(List.of(keep(2500, 45, 195)), onto.pieces(), "onto the keyframe at 4 s");
        assertTrue(onto.cutOnly());
        assertEquals(60, onto.firstGop());
        assertEquals(List.of(encode(30, 3_000_000, 30), keep(2500, 45, 195)),
                TrimPlan.plan(2500, 195, 45, 3000, 9000).pieces(), "later, off the grid");
        // earlier: the short first GOP is encoded again (with what is new), never kept mid-stream
        assertEquals(List.of(encode(60, 2_000_000), keep(2500, 45, 195)),
                TrimPlan.plan(2500, 195, 45, 2000, 9000).pieces());
        assertEquals(List.of(encode(50, 2_333_000, 50), keep(2500, 45, 166)),
                TrimPlan.plan(2500, 195, 45, 2333, 8000).pieces());
    }

    @Test
    void nothingLeftIsFull() {
        TrimPlan.Plan off = TrimPlan.plan(0, 130, 4000, 9000);
        assertEquals(Mode.FULL, off.mode(), "the new range starts at the current encode's last keyframe and ends later");
        assertEquals(150, off.encodedFrames());
        assertFalse(off.cutOnly());
        assertEquals(60, off.firstGop());
        // the new range ends before the current encode starts
        assertEquals(Mode.FULL, TrimPlan.plan(10_000, 90, 2000, 6000).mode());
        // the new start is past the current encode's last keyframe
        TrimPlan.Plan past = TrimPlan.plan(0, 345, 11_000, 13_000);
        assertEquals(Mode.FULL, past.mode());
        assertTrue(past.reason().contains("last keyframe"), past.reason());
        assertEquals(Mode.FULL, TrimPlan.plan(0, 0, 0, 3000).mode());
        assertThrows(IllegalArgumentException.class, () -> TrimPlan.plan(0, 90, 3000, 3000));
        assertThrows(IllegalArgumentException.class, () -> TrimPlan.plan(0, 90, 0, 0, 3000));
    }

    @Test
    void firstGopTravelsInTheEditSettings() {
        assertEquals(60, EditSettings.firstGopFrames(null));
        assertEquals(60, EditSettings.firstGopFrames("{\"firstGopFrames\":0}"));
        String json = EditSettings.withFirstGop("{\"videoStartMs\":2500}", 45);
        assertEquals("{\"videoStartMs\":2500,\"firstGopFrames\":45}", json);
        assertEquals(45, EditSettings.firstGopFrames(json));
        assertEquals(2500, EditSettings.parse(json).videoStartMs());
        assertEquals("{\"videoStartMs\":2500}", EditSettings.withFirstGop(json, 60), "a normal encode leaves it out");
        assertNull(EditSettings.withFirstGop("{\"firstGopFrames\":7}", 60));
        // a trim job's change keeps it (the video trim's keys only), so the plan sees the current encode
        assertEquals(45, EditSettings.firstGopFrames(EditSettings.applyJob(json, EditSettings.job(false, 0, null, true, 0L, null))));
    }

    @Test
    void editSettingsKeepOtherKeys() {
        assertEquals(EditSettings.NONE, EditSettings.parse(null));
        assertEquals(EditSettings.NONE, EditSettings.parse("not json"));
        assertNull(EditSettings.withVideoTrim(null, 0, null, null));
        String json = EditSettings.withVideoTrim("{\"audioStartMs\":5,\"videoStartMs\":9}", 2000, 8000L, null);
        assertEquals("{\"audioStartMs\":5,\"videoStartMs\":2000,\"videoEndMs\":8000}", json);
        assertEquals(new EditSettings(2000, 8000L, 13_000L, 5L, null),
                EditSettings.parse(EditSettings.withOriginalMs(json, 13_000)));
        assertTrue(EditSettings.parse(json).videoTrimmed());
        assertEquals("{\"audioStartMs\":5}", EditSettings.withVideoTrim(json, 0, null, null));
        assertEquals(new EditSettings(0, null, null), EditSettings.parse("{\"videoStartMs\":-5,\"videoEndMs\":-1}"));
    }

    @Test
    void audioTrimSettings() {
        assertFalse(EditSettings.NONE.audioTrimmed());
        String json = EditSettings.withAudioTrim("{\"videoStartMs\":2000}", 0L, null);
        assertEquals("{\"videoStartMs\":2000,\"audioStartMs\":0}", json);
        EditSettings e = EditSettings.parse(json);
        assertTrue(e.audioTrimmed(), "a start at 0 is an audio trim (a video's whole original)");
        assertEquals(new EditSettings(2000, null, null, 0L, null), e);
        assertEquals(new EditSettings(0, null, null, 1000L, 5000L),
                EditSettings.parse(EditSettings.withAudioTrim(null, 1000L, 5000L)));
        assertNull(EditSettings.withAudioTrim("{\"audioStartMs\":1,\"audioEndMs\":9}", null, 5000L), "removed");
        assertNull(EditSettings.parse("{\"audioEndMs\":9}").audioEndMs(), "no end without a start");
        assertTrue(e.sameVideoTrim(new EditSettings(2000, null, 1L)));
        assertFalse(e.sameAudioTrim(new EditSettings(2000, null, 1L)));
    }

    @Test
    void trimJobsCarryTheirParts() {
        assertEquals(java.util.Set.of(EditSettings.PART_VIDEO), EditSettings.parts("{\"videoStartMs\":2000}"),
                "jobs from before the audio trim");
        String video = EditSettings.job(true, 2000, 8000L, false, 7L, 8L);
        assertEquals("{\"videoStartMs\":2000,\"videoEndMs\":8000,\"parts\":[\"video\"]}", video);
        String audio = EditSettings.job(false, 0, null, true, 1000L, null);
        assertEquals("{\"audioStartMs\":1000,\"parts\":[\"audio\"]}", audio);
        String current = "{\"videoStartMs\":4000,\"audioStartMs\":500,\"audioEndMs\":900,\"originalMs\":13000}";
        // each part replaces only its own keys
        EditSettings v = EditSettings.parse(EditSettings.applyJob(current, video));
        assertEquals(new EditSettings(2000, 8000L, 13_000L, 500L, 900L), v);
        EditSettings a = EditSettings.parse(EditSettings.applyJob(current, audio));
        assertEquals(new EditSettings(4000, null, 13_000L, 1000L, null), a);
        String remove = EditSettings.job(false, 0, null, true, null, null);
        assertEquals(new EditSettings(4000, null, 13_000L),
                EditSettings.parse(EditSettings.applyJob(current, remove)));
        // a newer change merges into a queued job by part
        String merged = EditSettings.mergeJobs(video, audio);
        assertEquals(java.util.Set.of(EditSettings.PART_VIDEO, EditSettings.PART_AUDIO), EditSettings.parts(merged));
        assertEquals(new EditSettings(2000, 8000L, 13_000L, 1000L, null),
                EditSettings.parse(EditSettings.applyJob(current, merged)));
        String newer = EditSettings.mergeJobs(merged, EditSettings.job(true, 0, null, false, null, null));
        assertEquals(new EditSettings(0, null, 13_000L, 1000L, null),
                EditSettings.parse(EditSettings.applyJob(current, newer)));
        assertEquals(new EditSettings(0, null, 13_000L, null, null),
                EditSettings.parse(EditSettings.applyJob(current, EditSettings.mergeJobs(newer, remove))));
    }
}
