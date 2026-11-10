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
    private static Piece keep(int from, int to) {
        return new Piece(Source.REUSE, from, to);
    }

    private static Piece encode(int from, int to) {
        return new Piece(Source.ENCODE, from, to);
    }

    @Test
    void frames() {
        assertEquals(195, TrimPlan.frames(6500));
        assertEquals(196, TrimPlan.frames(6513));
        assertEquals(1, TrimPlan.frames(1));
        assertEquals(0, TrimPlan.frames(0));
    }

    @Test
    void endEarlierCutsOnly() {
        TrimPlan.Plan p = TrimPlan.plan(0, 390, 0, 11_500);
        assertEquals(Mode.SMART, p.mode());
        assertEquals(List.of(keep(0, 345)), p.pieces());
        assertTrue(p.cutOnly());
        assertEquals(0, p.encodedFrames());
    }

    @Test
    void endLaterEncodesFromTheLastGop() {
        // the last GOP was cut short (345 frames: GOP 5 has 45)
        TrimPlan.Plan p = TrimPlan.plan(0, 345, 0, 12_500);
        assertEquals(List.of(keep(0, 300), encode(300, 375)), p.pieces());
        assertEquals(75, p.encodedFrames());
        // a whole last GOP is encoded again too
        assertEquals(List.of(keep(0, 120), encode(120, 240)), TrimPlan.plan(0, 180, 0, 8000).pieces());
    }

    @Test
    void startOnTheGridKeepsTheGrid() {
        assertEquals(List.of(keep(120, 375)), TrimPlan.plan(0, 375, 4000, 12_500).pieces());
        TrimPlan.Plan earlier = TrimPlan.plan(4000, 255, 2000, 12_500);
        assertEquals(List.of(encode(-60, 0), keep(0, 255)), earlier.pieces());
        assertEquals(1000, earlier.pieces().get(0).originalStartMs(3000), "a whole GOP before 3 s");
        assertEquals(List.of(encode(-60, 0), keep(0, 240), encode(240, 270)),
                TrimPlan.plan(4000, 255, 2000, 13_000).pieces());
        // with a non-zero current start, the grid is curStart + 2 s × k
        assertEquals(List.of(keep(60, 150)), TrimPlan.plan(1500, 300, 3500, 6500).pieces());
    }

    @Test
    void offTheGridOrNothingLeftIsFull() {
        TrimPlan.Plan off = TrimPlan.plan(0, 390, 2500, 9000);
        assertEquals(Mode.FULL, off.mode());
        assertEquals(195, off.encodedFrames());
        assertTrue(off.reason().contains("grid"));
        assertFalse(off.cutOnly());
        // the new range starts after the current encode's last whole GOP
        assertEquals(Mode.FULL, TrimPlan.plan(0, 130, 4000, 9000).mode());
        // the new range ends before the current encode starts
        assertEquals(Mode.FULL, TrimPlan.plan(10_000, 90, 2000, 6000).mode());
        assertThrows(IllegalArgumentException.class, () -> TrimPlan.plan(0, 90, 3000, 3000));
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
