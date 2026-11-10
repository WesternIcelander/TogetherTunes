package io.siggi.togethertunes.output;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * One room's HLS segments ({@code docs/program-output.md#hls}): one segment per unit, a ring of
 * the last {@link #RING} segments, a playlist of the {@link #LISTED} most recent ones.
 *
 * <ul>
 *   <li><b>Real {@code EXTINF}:</b> a unit's duration is known exactly only when the next unit
 *       arrives (the next unit starts at this one's {@code boundaryPts}, or at its
 *       {@code audioEndPts} after a source switch), so a unit becomes a segment when its successor
 *       arrives: {@code EXTINF = next.startPts − startPts} in seconds, 3 decimals. The packager emits
 *       units {@link Packager#LEAD_MS} ahead of real time, so a segment is listed about 1 s after
 *       its start in real time, never ahead of the live edge.</li>
 *   <li><b>Media sequence numbers</b> are this object's own, continuing across packager restarts,
 *       so a player that keeps polling never sees them go backwards or a segment URL reused. A
 *       restart resets the output clock, so the first segment after it carries
 *       {@code EXT-X-DISCONTINUITY} (with {@code EXT-X-DISCONTINUITY-SEQUENCE} once such a segment
 *       has left the playlist). When the packager stops, the ring is emptied (memory).</li>
 *   <li>{@code EXT-X-TARGETDURATION} is 2 (units are 2 s; at most 2.055 s after a switch or a
 *       natural end, which still rounds to 2; a video's short first GOP and its last make shorter
 *       segments). It never decreases; it only grows if a segment would
 *       round above it, which the timestamp rules don't allow.</li>
 * </ul>
 *
 * <p>The packager delivers units on its thread ({@link UnitSink}: never blocks; this object's lock
 * is held only briefly). Thread-safe.
 */
public final class HlsSegmenter {
    /** Segments kept (the listed ones plus 2 for slow fetchers). */
    public static final int RING = 8;
    /** Segments listed in the playlist. */
    public static final int LISTED = 6;
    /** The smallest {@code EXT-X-TARGETDURATION}, in seconds. */
    public static final int TARGET_DURATION = 2;

    /** A finished segment: a unit's TS bytes (shared, never modified) and its real duration. */
    public record Segment(long sequence, long durationTicks, boolean discontinuity, byte[] ts) {
        /** {@code EXTINF}'s value: seconds with 3 decimals. */
        public String extinf() {
            return String.format(Locale.ROOT, "%.3f", durationTicks / (double) OutputClock.HZ);
        }
    }

    private final ArrayDeque<Segment> ring = new ArrayDeque<>(RING);
    /** Sequence numbers of the segments with a discontinuity that are still in the ring. */
    private final ArrayDeque<Long> discontinuities = new ArrayDeque<>();
    /** Discontinuities that have left the ring. */
    private long discontinuitiesDropped;
    private long nextSequence;
    private int targetDuration = TARGET_DURATION;
    /** The sink attached to the current packager, or null. */
    private Sink sink;
    private Packager packager;
    /** The unit waiting for its successor (it then becomes a segment), from the current sink. */
    private TsUnit pending;
    /** The next segment starts after a restart or a gap (new output clock). */
    private boolean discontinuityNext;
    /** Whether any segment was ever made (the very first one is no discontinuity). */
    private boolean everSegmented;

    /**
     * Follows {@code packager} from now on (its most recent unit included, if any), replacing the
     * packager followed so far. Does nothing if it already follows {@code packager}.
     */
    public void attach(Packager packager) {
        Sink s;
        synchronized (this) {
            if (this.packager == packager && sink != null) {
                return;
            }
            dropPending();
            s = new Sink();
            sink = s;
            this.packager = packager;
        }
        packager.addSink(s);
        TsUnit latest = packager.latest();
        if (latest != null) {
            s.deliver(latest); // ignored if the sink already got it or a later one
        }
    }

    /** Whether it follows {@code packager} now. */
    public synchronized boolean attachedTo(Packager packager) {
        return sink != null && this.packager == packager;
    }

    /** A new sink that becomes the current one (tests; {@link #attach} in production). */
    synchronized UnitSink newSink() {
        dropPending();
        sink = new Sink();
        packager = null;
        return sink;
    }

    /** Forgets the pending unit of the old source of units; the next segment is a discontinuity. */
    private void dropPending() {
        pending = null;
        if (everSegmented) {
            discontinuityNext = true;
        }
    }

    private synchronized boolean deliver(Sink from, TsUnit unit) {
        if (from != sink) {
            return false; // replaced: the packager drops it
        }
        if (pending != null) {
            if (unit.sequence() <= pending.sequence()) {
                return true; // already have it (attach's priming raced with delivery)
            }
            if (unit.sequence() == pending.sequence() + 1 && unit.startPts() > pending.startPts()) {
                add(pending, unit.startPts() - pending.startPts());
            } else {
                discontinuityNext = everSegmented; // a gap: the pending unit's duration is unknown
            }
        }
        pending = unit;
        return true;
    }

    private void add(TsUnit unit, long durationTicks) {
        boolean disc = discontinuityNext;
        discontinuityNext = false;
        everSegmented = true;
        Segment seg = new Segment(nextSequence++, durationTicks, disc, unit.ts());
        if (ring.size() == RING) {
            evictOldest();
        }
        ring.addLast(seg);
        if (disc) {
            discontinuities.addLast(seg.sequence());
        }
        targetDuration = Math.max(targetDuration, (int) Math.round(durationTicks / (double) OutputClock.HZ));
        notifyAll();
    }

    private void evictOldest() {
        Segment old = ring.removeFirst();
        if (!discontinuities.isEmpty() && discontinuities.peekFirst() == old.sequence()) {
            discontinuities.removeFirst();
            discontinuitiesDropped++;
        }
    }

    private synchronized void closed(Sink from) {
        if (from != sink) {
            return;
        }
        sink = null;
        packager = null;
        dropPending();
        while (!ring.isEmpty()) {
            evictOldest();
        }
    }

    /** Segments in the ring now. */
    public synchronized int size() {
        return ring.size();
    }

    /** A segment still in the ring, or null (expired, not made yet, or never). */
    public synchronized Segment segment(long sequence) {
        for (Segment s : ring) {
            if (s.sequence() == sequence) {
                return s;
            }
        }
        return null;
    }

    /** Waits up to {@code timeoutMs} until the ring holds at least {@code n} segments; returns whether it does. */
    public synchronized boolean awaitSegments(int n, long timeoutMs) throws InterruptedException {
        long until = System.nanoTime() + timeoutMs * 1_000_000L;
        while (ring.size() < n) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            wait(Math.max(1, left / 1_000_000L));
        }
        return true;
    }

    /**
     * The media playlist: the {@link #LISTED} most recent segments, URIs {@code seg/{sequence}.ts}
     * (relative to {@code /live/{token}/index.m3u8}).
     */
    public synchronized String playlist() {
        List<Segment> listed = new ArrayList<>(LISTED);
        Iterator<Segment> it = ring.descendingIterator();
        while (it.hasNext() && listed.size() < LISTED) {
            listed.add(0, it.next());
        }
        long first = listed.isEmpty() ? nextSequence : listed.get(0).sequence();
        long discSeq = discontinuitiesDropped;
        for (long d : discontinuities) {
            if (d < first) {
                discSeq++;
            }
        }
        StringBuilder sb = new StringBuilder(64 + 32 * listed.size());
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        if (discSeq > 0) {
            sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discSeq).append('\n');
        }
        for (Segment s : listed) {
            if (s.discontinuity()) {
                sb.append("#EXT-X-DISCONTINUITY\n");
            }
            sb.append("#EXTINF:").append(s.extinf()).append(",\n");
            sb.append("seg/").append(s.sequence()).append(".ts\n");
        }
        return sb.toString();
    }

    private final class Sink implements UnitSink {
        @Override
        public boolean deliver(TsUnit unit) {
            return HlsSegmenter.this.deliver(this, unit);
        }

        @Override
        public void close() {
            closed(this);
        }
    }
}
