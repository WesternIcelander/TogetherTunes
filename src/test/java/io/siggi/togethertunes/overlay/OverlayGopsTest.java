package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link OverlayGops}: the GOP layout, completion, and reading an encode back from its files. */
class OverlayGopsTest {
    @TempDir
    Path dir;

    /** A 30 fps video track of {@code frames} frames with keyframes at {@code keyframes}. */
    static Track track(int frames, int... keyframes) {
        Track.Builder b = new Track.Builder(SampleIndex.Kind.VIDEO, 15360, 0);
        for (int i = 0; i < frames; i++) {
            final int frame = i;
            int flags = Arrays.stream(keyframes).anyMatch(k -> k == frame) ? SampleIndex.FLAG_KEYFRAME : 0;
            b.add(i * 1000L, 1000, i * 512L, 512, flags);
        }
        return b.build();
    }

    @Test
    void gopLayoutFollowsTheKeyframes() {
        OverlayGops g = new OverlayGops(dir, track(135, 0, 60, 120));
        assertEquals(3, g.gopCount());
        assertEquals(135, g.frameCount());
        assertEquals(60, g.gopStart(1));
        assertEquals(120, g.gopEnd(1));
        assertEquals(135, g.gopEnd(2));
        assertEquals(0, g.gopOf(59));
        assertEquals(1, g.gopOf(60));
        assertEquals(2, g.gopOf(134));
        assertThrows(IndexOutOfBoundsException.class, () -> g.gopOf(135));
        // a smart trim's short first GOP
        assertArrayEquals(new int[] {0, 20, 80}, OverlayGops.gopStarts(track(100, 0, 20, 80)));
        assertEquals(20, OverlayVideoEncoder.firstGopFrames(new int[] {0, 20, 80}));
        assertEquals(60, OverlayVideoEncoder.firstGopFrames(new int[] {0, 60}));
        assertEquals(60, OverlayVideoEncoder.firstGopFrames(new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> new OverlayGops(dir, track(10, 3)));
    }

    @Test
    void completionIsPublishedPerGop() {
        OverlayGops g = new OverlayGops(dir, track(135, 0, 60, 120));
        assertFalse(g.isComplete(1));
        g.markComplete(1);
        g.markComplete(2);
        assertTrue(g.isComplete(1));
        assertFalse(g.isComplete(0));
        assertTrue(g.completeFrom(1));
        assertFalse(g.completeFrom(0));
        BitSet copy = g.completed();
        copy.set(0);
        assertFalse(g.isComplete(0), "completed() is a copy");
    }

    /** Writes an index with the given entries ({frame, offset, size, flags}) and a data file of {@code dataBytes}. */
    private void write(int frameCount, long dataBytes, int truncate, long[]... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(OverlayGops.MAGIC);
        out.writeInt(OverlayGops.VERSION);
        out.writeInt(frameCount);
        for (long[] e : entries) {
            out.writeInt((int) e[0]);
            out.writeLong(e[1]);
            out.writeInt((int) e[2]);
            out.writeByte((int) e[3]);
        }
        byte[] b = bytes.toByteArray();
        Files.write(dir.resolve(OverlayGops.INDEX_FILE), Arrays.copyOf(b, b.length - truncate));
        Files.write(dir.resolve(OverlayGops.H264_FILE), new byte[(int) dataBytes]);
    }

    /** Entries for frames {@code from..to-1}, 10 bytes each from {@code offset}, IDR where the track has keyframes. */
    private static long[][] frames(Track t, int from, int to, long offset) {
        long[][] e = new long[to - from][];
        for (int i = from; i < to; i++) {
            e[i - from] = new long[] {i, offset + (i - from) * 10L, 10, t.isKeyframe(i) ? OverlayGops.FLAG_IDR : 0};
        }
        return e;
    }

    @Test
    void loadsCompleteGopsAndIgnoresATornTail() throws Exception {
        Track t = track(135, 0, 60, 120);
        // an encode from GOP 1: frames 60..134, the last entry torn
        write(135, 750, 3, frames(t, 60, 135, 0));
        OverlayGops g = OverlayGops.load(dir, t);
        assertTrue(g.isComplete(1));
        assertFalse(g.isComplete(2), "its last frame's entry is torn");
        assertFalse(g.isComplete(0));
        assertEquals(0, g.offset(60));
        assertEquals(10, g.offset(61));
        assertEquals(10, g.size(119));

        write(135, 750, 0, frames(t, 60, 135, 0));
        assertTrue(OverlayGops.load(dir, t).completeFrom(1));
    }

    @Test
    void loadStopsAtBytesMissingFromTheDataFile() throws Exception {
        Track t = track(135, 0, 60, 120);
        // GOP 0 fits, GOP 1 runs past the data written (the job was killed before the bytes reached disk)
        write(135, 600 + 100, 0, frames(t, 0, 120, 0));
        OverlayGops g = OverlayGops.load(dir, t);
        assertTrue(g.isComplete(0));
        assertFalse(g.isComplete(1));
    }

    @Test
    void loadStopsAtGapsAndWrongIdrFlags() throws Exception {
        Track t = track(135, 0, 60, 120);
        long[][] e = frames(t, 0, 135, 0);
        long[][] gap = new long[134][];
        System.arraycopy(e, 0, gap, 0, 70);
        System.arraycopy(e, 71, gap, 70, 64); // frame 70 missing
        write(135, 1350, 0, gap);
        OverlayGops g = OverlayGops.load(dir, t);
        assertTrue(g.isComplete(0));
        assertFalse(g.isComplete(1));
        assertFalse(g.isComplete(2), "nothing after a gap counts");

        e[61][3] = OverlayGops.FLAG_IDR; // an IDR where the video has none
        write(135, 1350, 0, e);
        g = OverlayGops.load(dir, t);
        assertTrue(g.isComplete(0));
        assertFalse(g.isComplete(1));
    }

    @Test
    void loadRejectsAnotherVideoOrFormat() throws Exception {
        Track t = track(135, 0, 60, 120);
        write(136, 0, 0);
        assertThrows(IOException.class, () -> OverlayGops.load(dir, t));
        Files.write(dir.resolve(OverlayGops.INDEX_FILE), new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertThrows(IOException.class, () -> OverlayGops.load(dir, t));
        Files.write(dir.resolve(OverlayGops.INDEX_FILE), new byte[] {1, 2});
        assertThrows(IOException.class, () -> OverlayGops.load(dir, t));
    }
}
