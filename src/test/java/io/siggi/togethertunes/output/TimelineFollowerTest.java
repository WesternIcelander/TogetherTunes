package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.SourcesTest.audioFrame;
import static io.siggi.togethertunes.output.SourcesTest.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.output.UnitBuilderTest.Checker;
import io.siggi.togethertunes.rooms.PlaybackState;
import io.siggi.togethertunes.rooms.Timeline;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Applying timeline changes ({@code docs/program-output.md#applying-timeline-changes}). */
class TimelineFollowerTest {
    static final long S0 = OutputClock.START;
    /** Server time at output PTS {@link #S0}. */
    static final long T0 = 1_700_000_000_000L;

    /** A fake item: endless 2 s GOPs, audio from {@code startMs} to {@code mediaMs}. */
    static final class FakeItem implements ProgramSource {
        final Entry entry;
        final long startMs;
        final int audioFrames;
        int v;
        int a;
        boolean closed;

        FakeItem(Entry entry, long startMs, long mediaMs) {
            this.entry = entry;
            this.startMs = startMs;
            long samples = Math.max(0, (mediaMs - startMs) * 48);
            this.audioFrames = (int) ((samples + 1023) / 1024);
        }

        @Override
        public VideoSample nextVideo() {
            int i = v++;
            return new VideoSample(frame(i % 60 == 0, i & 0x7FFF), i * 3000L, i % 60 == 0);
        }

        @Override
        public byte[] nextAudio() {
            return a < audioFrames ? audioFrame(a++) : null;
        }

        @Override
        public String describe() {
            return "fake " + entry.entryId() + "@" + startMs;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    final class Factory implements TimelineFollower.SourceFactory {
        final List<FakeItem> started = new ArrayList<>();
        final Set<String> notLoaded = new HashSet<>();
        /** Media length per entry id when it differs from the entry's duration. */
        final java.util.Map<String, Long> mediaMs = new java.util.HashMap<>();

        @Override
        public ProgramSource item(Entry entry, long startMs) throws IOException {
            if (notLoaded.contains(entry.entryId())) {
                return null;
            }
            if (entry.entryId().equals("broken")) {
                throw new IOException("unreadable");
            }
            FakeItem f = new FakeItem(entry, startMs, mediaMs.getOrDefault(entry.entryId(), entry.durationMs()));
            started.add(f);
            return f;
        }

        @Override
        public ProgramSource filler(Filler filler) {
            try {
                return UnitBuilderTest.filler(filler);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        FakeItem last() {
            return started.get(started.size() - 1);
        }
    }

    static final Entry A = new Entry("eA", "iA", false, false, 5000);
    static final Entry B = new Entry("eB", "iB", true, false, 10_000);
    static final Entry C = new Entry("eC", "iC", false, false, 20_000);

    Factory factory;
    Checker checker;
    TimelineFollower follower;
    Unit lastUnit;
    /** Added to the server clock: lets the timeline get ahead of the output (an output stall). */
    long skewMs;

    @BeforeEach
    void setUp() {
        factory = new Factory();
        checker = new Checker();
        follower = new TimelineFollower(new UnitBuilder(), factory, pts -> serverMsAt(pts) + skewMs, null);
    }

    static long serverMsAt(long pts) {
        return T0 + Math.floorDiv(pts - S0, 90);
    }

    static ProgramCue playing(Entry e, long anchorMs, long posMs, Entry next) {
        return playing(e, anchorMs, posMs, next, null);
    }

    static ProgramCue playing(Entry e, long anchorMs, long posMs, Entry next, String playedOut) {
        return new ProgramCue(PlaybackState.PLAYING, e, anchorMs, posMs, next, playedOut);
    }

    static ProgramCue paused(Entry e, long anchorMs, long posMs, Entry next) {
        return new ProgramCue(PlaybackState.PAUSED, e, anchorMs, posMs, next, null);
    }

    Unit unit() throws IOException {
        Unit u = follower.nextUnit();
        if (lastUnit != null && u.source() != lastUnit.source()) {
            checker.switched();
        }
        lastUnit = checker.add(u);
        return u;
    }

    /**
     * Server time where a new source would start after the last unit: where its audio ends (the
     * switch point, up to 21 ms past the unit boundary).
     */
    long nextMs() {
        return lastUnit == null ? T0 : serverMsAt(lastUnit.audioEndPts());
    }

    @Test
    void nothingKnownOrUnknownStateIsPaused() throws Exception {
        Unit u = unit();
        assertSame(Filler.PAUSED, ((FillerSource) u.source()).filler());
        follower.update(new ProgramCue(null, A, T0, 1000, null, null)); // a state the packager doesn't know
        Unit u2 = unit();
        assertSame(u.source(), u2.source(), "still the same paused filler");
        follower.update(new ProgramCue(null, null, 0, 0, null, null));
        assertSame(u.source(), unit().source());
        assertTrue(factory.started.isEmpty());
    }

    @Test
    void idleTimelineGivesTheIdleFiller() throws Exception {
        follower.update(ProgramCue.IDLE);
        Unit u = unit();
        assertSame(Filler.IDLE, follower.playingFiller());
        assertSame(u.source(), unit().source());
        follower.update(ProgramCue.of(Timeline.paused("eA", T0, 1500), A, null, null));
        unit();
        assertSame(Filler.PAUSED, follower.playingFiller());
    }

    @Test
    void changesApplyAtTheNextUnitBoundary() throws Exception {
        follower.update(playing(C, T0, 0, null));
        Unit u1 = unit();
        FakeItem c1 = factory.last();
        assertSame(c1, u1.source());
        assertEquals(0, c1.startMs);
        assertSame(C, follower.playingEntry());

        // pause at 3.2 s (mid second unit): the second unit still plays the item, the third is the filler
        Unit u2 = unit();
        assertSame(c1, u2.source());
        follower.update(paused(C, T0 + 3200, 3200, null));
        Unit u3 = unit();
        assertInstanceOf(FillerSource.class, u3.source());
        assertSame(Filler.PAUSED, follower.playingFiller());
        assertTrue(c1.closed, "the builder closes the source it switched away from");
        assertEquals(u2.audioEndPts(), u3.startPts(), "the filler starts where the item's audio ends");
        Unit u4 = unit();
        assertSame(u3.source(), u4.source(), "paused: whole filler units");

        // resume at 3.2 s, anchored now: the item restarts at the timeline's position for the unit start
        long resumeAt = nextMs() - 700;
        follower.update(playing(C, resumeAt, 3200, null));
        long expected = 3200 + (nextMs() - resumeAt);
        Unit u5 = unit();
        FakeItem c2 = factory.last();
        assertSame(c2, u5.source());
        assertEquals(expected, c2.startMs);
    }

    @Test
    void seekRestartsAtTheNewPositionButAReanchorInPlaceDoesNot() throws Exception {
        follower.update(playing(C, T0, 0, null));
        unit();
        FakeItem c1 = factory.last();
        // re-anchored without moving (e.g. "play" while playing): not a seek
        follower.update(playing(C, T0 + 1500, 1500, null));
        assertSame(c1, unit().source());
        follower.update(playing(C, T0 + 1000, 1050, null)); // 50 ms: still the same motion
        assertSame(c1, unit().source());
        // a seek to 15 s, 300 ms before the next boundary
        long seekAt = nextMs() - 300;
        follower.update(playing(C, seekAt, 15_000, null));
        Unit u = unit();
        FakeItem c2 = factory.last();
        assertSame(c2, u.source());
        assertEquals(15_300, c2.startMs);
        assertEquals(1, factory.started.stream().filter(f -> f == c2).count());
        // seek back
        follower.update(playing(C, nextMs(), 2000, null));
        unit();
        assertEquals(2000, factory.last().startMs);
        assertEquals(3, factory.started.size());
    }

    @Test
    void skipToAnotherEntryAppliesAtTheBoundary() throws Exception {
        follower.update(playing(C, T0, 0, B));
        unit();
        follower.update(playing(B, T0 + 1200, 0, null));
        long expected = nextMs() - (T0 + 1200);
        assertEquals(805, expected, "the skip happened 800 ms before the boundary (+ 5 ms to the switch point)");
        unit();
        FakeItem b = factory.last();
        assertSame(B, b.entry);
        assertEquals(expected, b.startMs);
    }

    /** The first audio frame's number and the first picture's number of a unit (FakeItem payloads). */
    static int[] firstFrames(Unit u) {
        byte[] v = u.video().get(0).data();
        return new int[] {SourcesTest.audioNumber(u.audio().get(0).data()), ((v[5] & 0xFF) << 8) | (v[6] & 0xFF)};
    }

    /**
     * A skip while the output runs ({@code docs/rooms.md#room-state}, abrupt starts): the room starts
     * the next entry at the output's first unit it hasn't built. Until then the skipped item plays on;
     * that unit starts the new item at media position 0 (its first picture and first AAC frame), at
     * the timeline's start time (to the audio frame), with no discontinuity.
     */
    @Test
    void aDelayedSkipStartsTheNewItemFromZeroAtItsStartTime() throws Exception {
        follower.update(playing(C, T0, 0, null));
        unit();
        FakeItem c1 = factory.last();
        long start = serverMsAt(S0 + 2 * UnitBuilder.UNIT_TICKS); // the room's start for B: two units on
        follower.update(playing(B, start, 0, null));
        Unit u2 = unit();
        assertSame(c1, u2.source(), "the skipped item plays on until B starts");
        assertSame(C, follower.playingEntry());
        Unit u3 = unit();
        FakeItem b = factory.last();
        assertSame(B, b.entry);
        assertSame(b, u3.source());
        assertEquals(0, b.startMs, "from 0:00");
        assertEquals(u2.audioEndPts(), u3.startPts());
        long at = serverMsAt(u3.startPts());
        assertTrue(at >= start && at - start < 22, "starts at the timeline's start time: " + (at - start));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 0}, firstFrames(u3),
                "the item's first AAC frame and first picture");
        assertEquals(1, factory.started.stream().filter(f -> f.entry == B).count());
        // and it plays on as the timeline's entry
        assertSame(b, unit().source());
    }

    /**
     * The start isn't on a boundary the output reaches (e.g. the delay was capped): the item starts
     * from 0 at the first boundary at most half a unit before it; a second skip in the gap moves the
     * start again; a start from the idle filler keeps the filler until then.
     */
    @Test
    void delayedStartsOffTheBoundariesAndDoubleSkipsAndStartsFromIdle() throws Exception {
        follower.update(ProgramCue.IDLE);
        Unit u1 = unit();
        assertSame(Filler.IDLE, follower.playingFiller());
        // started from idle, 3.5 s after the next boundary: the filler runs on there
        follower.update(playing(A, nextMs() + 1500 + 2000, 0, C));
        Unit u2 = unit();
        assertSame(u1.source(), u2.source());
        // a second skip in the gap (to B, again later): the filler still runs on
        follower.update(playing(B, nextMs() + 2600, 0, null));
        Unit u3 = unit();
        assertSame(u1.source(), u3.source());
        assertTrue(factory.started.isEmpty(), "nothing started early");
        // the next boundary is 600 ms before B's start: B starts there, from 0
        Unit u4 = unit();
        FakeItem b = factory.last();
        assertSame(B, b.entry);
        assertEquals(0, b.startMs);
        assertSame(b, u4.source());
        assertEquals(1, factory.started.size());
    }

    /** The skipped item ends by itself before the start: the Loading filler bridges the gap. */
    @Test
    void aDelayedStartAfterTheSkippedItemEndsBridgesWithLoading() throws Exception {
        follower.update(playing(A, T0, 2000, null)); // A: 5 s, from 2 s: ends mid second unit
        unit();
        Unit u2 = unit();
        assertTrue(u2.sourceEnded());
        long start = serverMsAt(u2.audioEndPts()) + 4000; // the skip to B, 4 s past A's end
        follower.update(playing(B, start, 0, null));
        unit();
        assertSame(Filler.LOADING, follower.playingFiller());
        unit();
        assertSame(Filler.LOADING, follower.playingFiller(), "still 2 s before the start");
        Unit u5 = unit();
        assertSame(B, follower.playingEntry());
        assertEquals(0, factory.last().startMs);
        long at = serverMsAt(u5.startPts());
        assertTrue(at >= start && at - start < 100, "starts at the timeline's start time: " + (at - start));
    }

    @Test
    void naturalEndMidUnitStartsTheNextItemAtEnAndAdoptsTheRoomsAdvance() throws Exception {
        follower.update(playing(A, T0, 0, B)); // A: 5 s → 235 AAC frames, ends mid-unit
        Unit u1 = unit();
        unit();
        Unit u3 = unit();
        assertTrue(u3.sourceEnded());
        long en = S0 + 235 * 1920L;
        assertEquals(en, u3.audioEndPts());
        assertTrue(u3.audioEndPts() - u3.startPts() < UnitBuilder.UNIT_TICKS, "a short unit");
        assertSame(u1.source(), u3.source());

        // the room hasn't advanced yet (its timer fires a few ms later): the output starts B at 0 itself
        Unit u4 = unit();
        FakeItem b = factory.last();
        assertSame(B, b.entry);
        assertSame(b, u4.source());
        assertEquals(0, b.startMs);
        assertEquals(en, u4.startPts(), "S_{n+1} = E_n");

        // the room advances to B 12 ms after A's end: the output keeps its copy
        follower.update(playing(B, serverMsAt(en) + 12, 0, C, "eA"));
        assertSame(b, unit().source());
        assertSame(b, unit().source());
        assertFalse(b.closed);
        assertEquals(2, factory.started.size());
    }

    @Test
    void naturalEndWithNothingNextGivesTheIdleFiller() throws Exception {
        follower.update(playing(A, T0, 0, null));
        unit();
        unit();
        assertTrue(unit().sourceEnded());
        Unit idle = unit();
        assertSame(Filler.IDLE, follower.playingFiller());
        // the room goes idle a moment later: same filler
        follower.update(ProgramCue.IDLE);
        assertSame(idle.source(), unit().source());
        // still showing A's last cue (room's timer late): A isn't played again
        assertEquals(1, factory.started.size());
    }

    @Test
    void endedItemIsNotReplayedWhileTheRoomStillShowsIt() throws Exception {
        ProgramCue cue = playing(A, T0, 0, null);
        follower.update(cue);
        unit();
        unit();
        unit(); // A ended
        for (int i = 0; i < 3; i++) {
            unit();
            assertSame(Filler.IDLE, follower.playingFiller());
        }
        follower.update(playing(A, T0 + 4000, 4000, null)); // re-anchor, same motion
        unit();
        assertSame(Filler.IDLE, follower.playingFiller());
        assertEquals(1, factory.started.size());
        // a seek back into A plays it again
        follower.update(playing(A, nextMs(), 1000, null));
        unit();
        assertSame(A, follower.playingEntry());
        assertEquals(1000, factory.last().startMs);
    }

    @Test
    void outputBehindKeepsPlayingItsItemToTheEnd() throws Exception {
        // the output started A 0.9 s early (the timeline's start was put off by less than half a
        // unit past the output's boundary): the room advances first
        follower.update(playing(A, T0 + 900, 0, B));
        FakeItem a = null;
        unit();
        a = factory.last();
        unit();
        follower.update(playing(B, T0 + 5900, 0, C, "eA")); // A ended on the timeline at T0 + 5.9 s
        Unit u3 = unit();
        assertSame(a, u3.source(), "no correction mid-item");
        assertTrue(u3.sourceEnded());
        Unit u4 = unit();
        FakeItem b = factory.last();
        assertSame(B, b.entry);
        assertEquals(0, b.startMs, "0.9 s apart is within the 3 s allowance: B from 0");
        assertSame(b, u4.source());
    }

    @Test
    void outputMoreThan3sBehindResyncsAtTheItemBoundary() throws Exception {
        follower.update(playing(A, T0, 0, B));
        unit();
        unit();
        // the room's A ended long ago (e.g. the output stalled): B is at 4 s by A's end in the output
        long en = S0 + 235 * 1920L;
        long bAnchor = serverMsAt(en) - 4000;
        follower.update(playing(B, bAnchor, 0, C, "eA"));
        assertTrue(unit().sourceEnded());
        unit();
        assertSame(B, factory.last().entry);
        assertEquals(serverMsAt(en) - bAnchor, factory.last().startMs);
        assertTrue(factory.last().startMs > TimelineFollower.RESYNC_MS);
    }

    @Test
    void outputMoreThan3sBehindWhenTheRoomHasntAdvancedEither() throws Exception {
        follower.update(playing(A, T0, 0, B));
        unit();
        skewMs = 4000; // the output stalled for 4 s: no correction mid-item
        assertSame(A, unit().source() instanceof FakeItem f ? f.entry : null);
        assertTrue(unit().sourceEnded());
        unit();
        // at E_n the timeline is 4013 ms past A's end and the room's advance is late: B re-syncs
        assertSame(B, factory.last().entry);
        long en = S0 + 235 * 1920L;
        assertEquals(serverMsAt(en) + 4000 - T0 - 5000, factory.last().startMs);
        assertEquals(2, factory.started.size());
    }

    @Test
    void outputMoreThan3sAheadWaitsOnTheLoadingFiller() throws Exception {
        // A's media ends after 5 s, but the timeline gives it 10 s
        Entry longA = new Entry("eA", "iA", false, false, 10_000);
        factory.mediaMs.put("eA", 5000L);
        follower.update(playing(longA, T0, 0, B));
        unit();
        unit();
        assertTrue(unit().sourceEnded());
        unit();
        assertSame(Filler.LOADING, follower.playingFiller(), "5 s ahead: wait for the timeline");
        unit();
        unit();
        assertSame(Filler.LOADING, follower.playingFiller());
        assertEquals(1, factory.started.size(), "A isn't replayed");
        // the room advances to B (at T0 + 10 s): the output re-syncs to the timeline
        follower.update(playing(B, T0 + 10_000, 0, C, "eA"));
        long expected = nextMs() - (T0 + 10_000);
        unit();
        assertSame(B, follower.playingEntry());
        assertEquals(expected, factory.last().startMs);
    }

    @Test
    void naturalEndWithSmallLeadStartsTheNextItem() throws Exception {
        // the timeline still has 2 s of A when the output's A ends: within 3 s, B starts at 0 right away
        Entry longA = new Entry("eA", "iA", false, false, 7000);
        factory.mediaMs.put("eA", 5000L);
        follower.update(playing(longA, T0, 0, B));
        unit();
        unit();
        unit();
        unit();
        assertSame(B, follower.playingEntry());
        assertEquals(0, factory.last().startMs);
        // the room advances 2 s later: adopted (2 s apart is within 3 s)
        FakeItem b = factory.last();
        follower.update(playing(B, T0 + 7000, 0, C, "eA"));
        unit();
        assertSame(b, lastUnit.source());
        assertEquals(2, factory.started.size());
    }

    @Test
    void anItemThatIsntLoadedYetShowsTheLoadingFillerUntilItIs() throws Exception {
        factory.notLoaded.add("eC");
        follower.update(playing(C, T0, 0, null));
        Unit u1 = unit();
        assertSame(Filler.LOADING, follower.playingFiller());
        Unit u2 = unit();
        assertSame(u1.source(), u2.source());
        factory.notLoaded.clear();
        long expected = nextMs() - T0;
        unit();
        assertSame(C, follower.playingEntry());
        assertEquals(expected, factory.last().startMs, "starts where the timeline is by then");
    }

    @Test
    void anUnreadableItemShowsTheLoadingFiller() throws Exception {
        Entry broken = new Entry("broken", "iX", true, false, 10_000);
        follower.update(playing(broken, T0, 0, null));
        unit();
        assertSame(Filler.LOADING, follower.playingFiller());
        assertNull(follower.playingEntry());
    }

    @Test
    void seekPastTheEndOfTheMediaDoesNotLoop() throws Exception {
        Entry longA = new Entry("eA", "iA", false, false, 10_000);
        factory.mediaMs.put("eA", 5000L);
        follower.update(playing(longA, T0, 8000, null)); // the source has no audio there
        Unit u = unit();
        assertInstanceOf(FillerSource.class, u.source());
        for (int i = 0; i < 3; i++) {
            unit();
        }
    }

    @Test
    void timelineAtItsEndBeforeTheRoomAdvancesStartsTheNextItem() throws Exception {
        follower.update(playing(B, T0, 0, null));
        unit();
        // a seek to the very end of A: the room is about to advance to B
        follower.update(playing(A, nextMs(), 5000, B));
        unit();
        assertSame(B, follower.playingEntry());
        assertEquals(0, factory.last().startMs);
    }

    @Test
    void longRunKeepsTimestampsContinuous() throws Exception {
        // a scripted mix: items ending naturally, pauses, seeks, skips and idle periods
        long t = T0;
        follower.update(playing(A, t, 0, B));
        for (int i = 0; i < 200; i++) {
            long now = nextMs();
            switch (i % 23) {
                case 3 -> follower.update(paused(follower.cue().current() == null ? C : follower.cue().current(),
                        now, 1000, null));
                case 5 -> follower.update(playing(C, now, 7000, A));
                case 9 -> follower.update(playing(B, now - 4000, 0, C, "eC"));
                case 14 -> follower.update(ProgramCue.IDLE);
                case 16 -> follower.update(playing(A, now, 0, B));
                case 19 -> follower.update(new ProgramCue(null, A, now, 0, B, null));
                default -> {
                }
            }
            unit(); // the checker asserts contiguity, IDR at each start and 1920-tick audio throughout
        }
    }
}
