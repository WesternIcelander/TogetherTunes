package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@link HlsSegmenter}: ring of 8, playlist of 6, real {@code EXTINF}, sequence numbers and restarts. */
class HlsSegmenterTest {
    static final long U = 180_000;

    static TsUnit unit(long seq, long start) {
        return new TsUnit(seq, start, start + U, start + U, new byte[] {(byte) seq});
    }

    /** Delivers {@code n} 2 s units numbered from {@code firstSeq}, starting at {@code start}. */
    static long feed(UnitSink sink, long firstSeq, long start, int n) {
        long pts = start;
        for (int i = 0; i < n; i++) {
            assertTrue(sink.deliver(unit(firstSeq + i, pts)));
            pts += U;
        }
        return pts;
    }

    record Entry(String extinf, String uri, boolean discontinuity) {
    }

    record Parsed(List<String> header, List<Entry> entries) {
        String tag(String name) {
            for (String h : header) {
                if (h.startsWith(name + ":")) {
                    return h.substring(name.length() + 1);
                }
            }
            return null;
        }
    }

    static Parsed parse(String playlist) {
        assertTrue(playlist.startsWith("#EXTM3U\n"), playlist);
        assertTrue(playlist.endsWith("\n"));
        assertFalse(playlist.contains("\r"));
        List<String> header = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        String[] lines = playlist.split("\n");
        boolean disc = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.equals("#EXT-X-DISCONTINUITY")) {
                disc = true;
            } else if (l.startsWith("#EXTINF:")) {
                Matcher m = Pattern.compile("#EXTINF:(\\d+\\.\\d{3}),").matcher(l);
                assertTrue(m.matches(), l);
                entries.add(new Entry(m.group(1), lines[++i], disc));
                disc = false;
            } else {
                assertTrue(entries.isEmpty(), "header tag after a segment: " + l);
                header.add(l);
            }
        }
        return new Parsed(header, entries);
    }

    @Test
    void emptyPlaylistBeforeTheFirstSegment() {
        HlsSegmenter h = new HlsSegmenter();
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n", h.playlist());
        UnitSink s = h.newSink();
        s.deliver(unit(0, OutputClock.START));
        assertEquals(0, h.size(), "a unit becomes a segment only when its successor arrives");
        s.deliver(unit(1, OutputClock.START + U));
        assertEquals(1, h.size());
    }

    @Test
    void exactFormatOfTheDocsExample() {
        HlsSegmenter h = new HlsSegmenter();
        feed(h.newSink(), 0, OutputClock.START, 3);
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:2.000,\nseg/0.ts\n#EXTINF:2.000,\nseg/1.ts\n", h.playlist());
    }

    @Test
    void ringOfEightPlaylistOfSix() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        feed(s, 0, OutputClock.START, 21); // 20 segments made (0..19), unit 20 pending
        assertEquals(HlsSegmenter.RING, h.size());
        Parsed p = parse(h.playlist());
        assertEquals("14", p.tag("#EXT-X-MEDIA-SEQUENCE"));
        assertEquals(HlsSegmenter.LISTED, p.entries().size());
        for (int i = 0; i < 6; i++) {
            assertEquals("seg/" + (14 + i) + ".ts", p.entries().get(i).uri());
        }
        // 2 more kept for slow fetchers, older ones gone, the pending unit not yet available
        assertNotNull(h.segment(12));
        assertNotNull(h.segment(13));
        assertNull(h.segment(11));
        assertNull(h.segment(20));
        assertArrayEquals(new byte[] {19}, h.segment(19).ts());
    }

    @Test
    void extinfIsTheRealDurationWithThreeDecimals() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        long t = OutputClock.START;
        s.deliver(unit(0, t));
        // after a source switch the next unit starts at the audio end: up to 1919 ticks past 2 s
        t += U + 1919;
        s.deliver(unit(1, t));
        // a source that ended early (natural end mid-unit)
        t += 45_000;
        s.deliver(unit(2, t));
        // the longest a unit may be: 2 s + 3000 + 1919 ticks
        t += U + 3000 + 1919;
        s.deliver(unit(3, t));
        t += U;
        s.deliver(unit(4, t));
        Parsed p = parse(h.playlist());
        assertEquals(List.of("2.021", "0.500", "2.055", "2.000"),
                p.entries().stream().map(Entry::extinf).toList());
        assertEquals("2", p.tag("#EXT-X-TARGETDURATION"), "every EXTINF rounds to at most 2");
        assertEquals(U + 1919, h.segment(0).durationTicks());
    }

    @Test
    void targetDurationNeverDecreases() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        s.deliver(unit(0, 0));
        s.deliver(unit(1, 3 * OutputClock.HZ)); // 3 s: not allowed by the unit rules, but handled
        long end = feed(s, 2, 3 * OutputClock.HZ + U, 20);
        assertTrue(end > 0);
        assertEquals("3", parse(h.playlist()).tag("#EXT-X-TARGETDURATION"));
    }

    @Test
    void duplicateUnitsAreIgnoredAndGapsDropThePendingUnit() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        feed(s, 0, OutputClock.START, 3);
        assertTrue(s.deliver(unit(2, OutputClock.START + 2 * U)), "a repeat is ignored, the sink stays");
        assertTrue(s.deliver(unit(1, OutputClock.START + U)));
        assertEquals(2, h.size());
        // unit 3 missing: unit 2's duration can't be known, so it's dropped; the next segment is marked
        s.deliver(unit(4, OutputClock.START + 4 * U));
        s.deliver(unit(5, OutputClock.START + 5 * U));
        Parsed p = parse(h.playlist());
        assertEquals(3, p.entries().size());
        assertEquals("seg/2.ts", p.entries().get(2).uri());
        assertTrue(p.entries().get(2).discontinuity());
        assertArrayEquals(new byte[] {4}, h.segment(2).ts());
    }

    @Test
    void restartContinuesTheSequenceWithADiscontinuity() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink first = h.newSink();
        feed(first, 0, OutputClock.START + 777, 5); // segments 0..3
        // the packager restarts: a new sink, a new output clock and new unit numbers
        UnitSink second = h.newSink();
        assertFalse(first.deliver(unit(5, OutputClock.START + 777 + 5 * U)), "the old sink is dropped");
        first.close(); // closing the replaced sink doesn't touch the ring
        assertEquals(4, h.size());
        feed(second, 0, OutputClock.START, 3); // segments 4, 5
        Parsed p = parse(h.playlist());
        assertEquals("0", p.tag("#EXT-X-MEDIA-SEQUENCE"));
        assertNull(p.tag("#EXT-X-DISCONTINUITY-SEQUENCE"));
        assertEquals(6, p.entries().size());
        for (int i = 0; i < 6; i++) {
            assertEquals("seg/" + i + ".ts", p.entries().get(i).uri());
            assertEquals(i == 4, p.entries().get(i).discontinuity(), "entry " + i);
        }
        // once the discontinuity leaves the playlist, EXT-X-DISCONTINUITY-SEQUENCE counts it
        feed(second, 3, OutputClock.START + 3 * U, 4); // segments 6..9
        p = parse(h.playlist());
        assertEquals("4", p.tag("#EXT-X-MEDIA-SEQUENCE"));
        assertNull(p.tag("#EXT-X-DISCONTINUITY-SEQUENCE"));
        assertTrue(p.entries().get(0).discontinuity());
        second.deliver(unit(7, OutputClock.START + 7 * U)); // segment 10
        p = parse(h.playlist());
        assertEquals("5", p.tag("#EXT-X-MEDIA-SEQUENCE"));
        assertEquals("1", p.tag("#EXT-X-DISCONTINUITY-SEQUENCE"));
        assertTrue(p.entries().stream().noneMatch(Entry::discontinuity));
        // ... and still after it left the ring
        feed(second, 8, OutputClock.START + 8 * U, 10);
        assertEquals("1", parse(h.playlist()).tag("#EXT-X-DISCONTINUITY-SEQUENCE"));
    }

    @Test
    void packagerStopEmptiesTheRingButKeepsTheSequence() {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        feed(s, 0, OutputClock.START, 4); // 0..2
        s.close();
        assertEquals(0, h.size());
        assertNull(h.segment(2));
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:3\n", h.playlist());
        assertFalse(s.deliver(unit(4, OutputClock.START + 4 * U)));
        UnitSink again = h.newSink();
        feed(again, 0, OutputClock.START, 2);
        Parsed p = parse(h.playlist());
        assertEquals("3", p.tag("#EXT-X-MEDIA-SEQUENCE"));
        assertEquals(1, p.entries().size());
        assertTrue(p.entries().get(0).discontinuity());
        assertNull(p.tag("#EXT-X-DISCONTINUITY-SEQUENCE"), "no earlier segment had a discontinuity");
    }

    @Test
    void awaitSegmentsWakesUpOnTheFirstSegment() throws Exception {
        HlsSegmenter h = new HlsSegmenter();
        UnitSink s = h.newSink();
        assertFalse(h.awaitSegments(1, 20));
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            feed(s, 0, OutputClock.START, 2);
        });
        long t0 = System.nanoTime();
        assertTrue(h.awaitSegments(1, 5000));
        assertTrue(System.nanoTime() - t0 < 4_000_000_000L);
        t.join();
    }

    @Test
    void attachFollowsARealPackagerIncludingItsLatestUnit() throws Exception {
        TimeSource time = TimeSource.system(java.time.Clock.systemUTC());
        PackagerTest.FakeProducer p = new PackagerTest.FakeProducer(time, 90);
        try (Packager pk = new Packager("hls-test", p, u -> new byte[] {(byte) u.startPts()},
                new PacingClock(time), time)) {
            pk.start();
            p.permits.release(1);
            PackagerTest.await(() -> pk.latest() != null);
            HlsSegmenter h = new HlsSegmenter();
            h.attach(pk);
            assertTrue(h.attachedTo(pk));
            h.attach(pk); // no-op
            assertEquals(1, pk.consumerCount());
            p.permits.release(3);
            PackagerTest.await(() -> h.size() == 3);
            Parsed parsed = parse(h.playlist());
            assertEquals("0", parsed.tag("#EXT-X-MEDIA-SEQUENCE"));
            assertEquals(List.of("0.001", "0.001", "0.001"), parsed.entries().stream().map(Entry::extinf).toList());
            assertFalse(parsed.entries().get(0).discontinuity());
            // the packager's first unit (emitted before attach) is segment 0
            assertEquals((byte) PackagerTest.S0, h.segment(0).ts()[0]);
        }
    }
}
