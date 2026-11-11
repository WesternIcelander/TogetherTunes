package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic indexes and in-memory sample data for the program output sources. */
class SourcesTest {
    /** A minimal avcC: 4-byte NAL lengths, one SPS, one PPS. */
    static final byte[] AVCC = {1, 0x64, 0, 0x1F, (byte) 0xFF, (byte) 0xE1, 0, 4, 0x67, 0x64, 0, 0x1F,
            1, 0, 2, 0x68, (byte) 0xEE};

    static final long VIDEO_TS = 15360;
    static final long AUDIO_TS = 48000;

    @TempDir
    Path dir;

    /** An AVCC sample: one NAL (IDR or non-IDR slice) carrying {@code n}. */
    static byte[] frame(boolean idr, int n) {
        return new byte[] {0, 0, 0, 3, (byte) (idr ? 0x65 : 0x41), (byte) (n >> 8), (byte) n};
    }

    static byte[] audioFrame(int n) {
        return new byte[] {0x21, (byte) (n >> 8), (byte) n};
    }

    static int audioNumber(byte[] f) {
        return ((f[1] & 0xFF) << 8) | (f[2] & 0xFF);
    }

    static int frameNumber(byte[] f) {
        return ((f[5] & 0xFF) << 8) | (f[6] & 0xFF);
    }

    static List<byte[]> cardFrames(int tag) {
        List<byte[]> l = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            l.add(frame(i == 0, tag + i));
        }
        return l;
    }

    /** In-memory files, one per file-ref. */
    static final class Media {
        final List<byte[]> files = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();

        Track.Builder add(String name, Kind kind, long ts, List<byte[]> samples, long duration, int keyEvery) {
            return add(name, kind, ts, samples, duration, i -> keyEvery > 0 && i % keyEvery == 0);
        }

        Track.Builder add(String name, Kind kind, long ts, List<byte[]> samples, long duration,
                          java.util.function.IntPredicate key) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Track.Builder b = new Track.Builder(kind, ts, names.size());
            for (int i = 0; i < samples.size(); i++) {
                int flags = key.test(i) ? SampleIndex.FLAG_KEYFRAME : 0;
                b.add(out.size(), samples.get(i).length, i * duration, duration, flags);
                out.writeBytes(samples.get(i));
            }
            names.add(name);
            files.add(out.toByteArray());
            tracks.add(b.build());
            return b;
        }

        SampleIndex index() {
            return new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P720_30), names, tracks, AVCC);
        }

        SampleData data() {
            return (ref, off, size) -> {
                byte[] f = files.get(ref);
                if (off + size > f.length) {
                    throw new EOFException();
                }
                return java.util.Arrays.copyOfRange(f, (int) off, (int) off + size);
            };
        }
    }

    /** A 10 s video item: 300 frames (IDR every 60), 469 AAC frames. */
    static Media videoItem() {
        Media m = new Media();
        List<byte[]> v = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            v.add(frame(i % 60 == 0, i));
        }
        m.add("mezz.mp4", Kind.VIDEO, VIDEO_TS, v, 512, 60);
        List<byte[]> a = new ArrayList<>();
        for (int i = 0; i < 469; i++) {
            a.add(audioFrame(i));
        }
        m.add("audio.m4a", Kind.AUDIO, AUDIO_TS, a, 1024, 1);
        return m;
    }

    /** Whether frame {@code i} is an IDR when the first GOP has {@code firstGop} frames (then every 60). */
    static boolean idrAt(int i, int firstGop) {
        return i == 0 || (i >= firstGop && (i - firstGop) % 60 == 0);
    }

    /**
     * A 10 s video item whose first GOP is {@code firstGop} frames (a smart trim's): 300 frames,
     * IDRs at 0 and {@code firstGop + 60·k}, 469 AAC frames.
     */
    static Media videoItem(int firstGop) {
        Media m = new Media();
        List<byte[]> v = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            v.add(frame(idrAt(i, firstGop), i));
        }
        m.add("mezz.mp4", Kind.VIDEO, VIDEO_TS, v, 512, i -> idrAt(i, firstGop));
        List<byte[]> a = new ArrayList<>();
        for (int i = 0; i < 469; i++) {
            a.add(audioFrame(i));
        }
        m.add("audio.m4a", Kind.AUDIO, AUDIO_TS, a, 1024, 1);
        return m;
    }

    /** A 5 s audio item: audio.m4a (235 frames) + card.mp4 (60 frames). */
    static Media audioItem() {
        Media m = new Media();
        List<byte[]> a = new ArrayList<>();
        for (int i = 0; i < 235; i++) {
            a.add(audioFrame(i));
        }
        m.add("audio.m4a", Kind.AUDIO, AUDIO_TS, a, 1024, 1);
        m.add("card.mp4", Kind.VIDEO, VIDEO_TS, cardFrames(1000), 512, 60);
        return m;
    }

    static int drainAudio(ProgramSource s) throws IOException {
        int n = 0;
        while (s.nextAudio() != null) {
            n++;
        }
        return n;
    }

    // ---- VideoItemSource ----

    @Test
    void videoItemFromTheStart() throws Exception {
        Media m = videoItem();
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), 0);
        assertEquals(0, s.mediaStartMs());
        for (int i = 0; i < 300; i++) {
            VideoSample v = s.nextVideo();
            assertEquals(i, frameNumber(v.data()));
            assertEquals(i * 3000L, v.pts());
            assertEquals(i % 60 == 0, v.idr());
        }
        assertNull(s.nextVideo());
        assertNull(s.nextVideo());
        assertEquals(0, audioNumber(s.nextAudio()));
        assertEquals(468, drainAudio(s));
        assertNull(s.nextAudio());
    }

    @Test
    void videoItemStartsAtTheKeyframeBeforeAndAudioAtTheSameTime() throws Exception {
        Media m = videoItem();
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), 3000);
        assertEquals(3000, s.requestedStartMs());
        VideoSample first = s.nextVideo();
        assertTrue(first.idr());
        assertEquals(60, frameNumber(first.data()));
        assertEquals(0, first.pts());
        VideoSample second = s.nextVideo();
        assertEquals(61, frameNumber(second.data()));
        assertEquals(3000, second.pts());
        // keyframe at 2.000 s = 96000 audio ticks; frames at 93·1024=95232 and 94·1024=96256 → 94 is nearer
        assertEquals(94, audioNumber(s.nextAudio()));
        assertEquals(96256 * 1000 / 48000, s.mediaStartMs());
        assertEquals(469 - 95, drainAudio(s));
    }

    @Test
    void videoItemOnAKeyframeAndPastTheEnd() throws Exception {
        Media m = videoItem();
        VideoItemSource on = new VideoItemSource("item", m.index(), m.data(), 4000);
        assertEquals(120, frameNumber(on.nextVideo().data()));
        VideoItemSource just = new VideoItemSource("item", m.index(), m.data(), 3999);
        assertEquals(60, frameNumber(just.nextVideo().data()));
        VideoItemSource past = new VideoItemSource("item", m.index(), m.data(), 60_000);
        assertEquals(240, frameNumber(past.nextVideo().data()));
        assertTrue(drainAudio(past) <= 469 - 187);
        VideoItemSource negative = new VideoItemSource("item", m.index(), m.data(), -500);
        assertEquals(0, frameNumber(negative.nextVideo().data()));
        assertEquals(0, audioNumber(negative.nextAudio()));
    }

    @Test
    void videoItemNeedsBothTracks() {
        Media m = audioItem();
        Media noVideo = new Media();
        noVideo.add("audio.m4a", Kind.AUDIO, AUDIO_TS, List.of(audioFrame(0)), 1024, 1);
        assertThrows(IOException.class, () -> new VideoItemSource("x", noVideo.index(), noVideo.data(), 0));
        Media noKey = new Media();
        noKey.add("mezz.mp4", Kind.VIDEO, VIDEO_TS, List.of(frame(false, 0)), 512, 0);
        noKey.add("audio.m4a", Kind.AUDIO, AUDIO_TS, List.of(audioFrame(0)), 1024, 1);
        assertThrows(IOException.class, () -> new VideoItemSource("x", noKey.index(), noKey.data(), 0));
        assertNotNull(m);
    }

    @Test
    void videoPtsAreRescaledToNinetyKilohertz() throws Exception {
        // 25 fps at timescale 12800: 512 ticks a frame = 3600 at 90 kHz
        Media m = new Media();
        List<byte[]> v = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            v.add(frame(i % 50 == 0, i));
        }
        m.add("mezz.mp4", Kind.VIDEO, 12800, v, 512, 50);
        m.add("audio.m4a", Kind.AUDIO, AUDIO_TS, List.of(audioFrame(0), audioFrame(1)), 1024, 1);
        VideoItemSource s = new VideoItemSource("x", m.index(), m.data(), 2500);
        assertEquals(50, frameNumber(s.nextVideo().data()));
        assertEquals(3600, s.nextVideo().pts());
    }

    // ---- overlay substitution (docs/overlay.md#packager-substitution) ----

    /**
     * A fake overlay re-encode of a 300-frame video with IDRs where {@code idr} says: frame
     * {@code i} carries {@code tag + i}; GOPs in {@link #complete} (by keyframe) are ready.
     */
    static final class FakeVideoOverlay implements VideoOverlay {
        final int frames;
        final java.util.function.IntPredicate idr;
        final int tag;
        final java.util.Set<Integer> complete = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<Integer> asked = new ArrayList<>();
        IOException fail;
        int shortBy;
        Integer wrongIdrAt;

        FakeVideoOverlay(int frames, java.util.function.IntPredicate idr, int tag) {
            this.frames = frames;
            this.idr = idr;
            this.tag = tag;
        }

        @Override
        public int frameCount() {
            return frames;
        }

        @Override
        public byte[][] gop(int keyframe) throws IOException {
            asked.add(keyframe);
            if (fail != null) {
                throw fail;
            }
            if (!complete.contains(keyframe)) {
                return null;
            }
            int end = keyframe + 1;
            while (end < frames && !idr.test(end)) {
                end++;
            }
            List<byte[]> l = new ArrayList<>();
            for (int i = keyframe; i < end - shortBy; i++) {
                boolean key = wrongIdrAt != null && wrongIdrAt == i ? !idr.test(i) : idr.test(i);
                l.add(frame(key, tag + i));
            }
            return l.toArray(new byte[0][]);
        }
    }

    /** Plays a source's video to the end: each frame's tag, with PTS and IDR flags checked against the mezzanine's. */
    static List<Integer> playVideo(ProgramSource s, int firstFrame, java.util.function.IntPredicate idr) throws IOException {
        List<Integer> tags = new ArrayList<>();
        VideoSample v;
        int i = firstFrame;
        while ((v = s.nextVideo()) != null) {
            assertEquals((i - firstFrame) * 3000L, v.pts(), "frame " + i);
            assertEquals(idr.test(i), v.idr(), "frame " + i);
            tags.add(frameNumber(v.data()));
            i++;
        }
        return tags;
    }

    /** The tags expected when GOPs starting at {@code overlaid} come from an overlay tagged {@code tag}. */
    static List<Integer> expected(int from, int to, java.util.function.IntPredicate idr, int tag, java.util.Set<Integer> overlaid) {
        List<Integer> l = new ArrayList<>();
        boolean ov = false;
        for (int i = from; i < to; i++) {
            if (idr.test(i)) {
                ov = overlaid.contains(i);
            }
            l.add(ov ? tag + i : i);
        }
        return l;
    }

    @Test
    void overlayGopsReplaceCompleteGopsOnly() throws Exception {
        Media m = videoItem();
        java.util.function.IntPredicate idr = i -> i % 60 == 0;
        FakeVideoOverlay ov = new FakeVideoOverlay(300, idr, 1000);
        ov.complete.addAll(List.of(60, 180));
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), () -> ov, 0);
        assertEquals(expected(0, 300, idr, 1000, java.util.Set.of(60, 180)), playVideo(s, 0, idr));
        assertEquals(List.of(0, 60, 120, 180, 240), ov.asked, "asked once per GOP, at its start");
        assertEquals(2, s.overlaidGops());
        assertEquals(469, drainAudio(s), "audio is the item's own");

        // all complete, started mid-item: every GOP from the start one
        ov.complete.addAll(List.of(0, 120, 240));
        VideoItemSource mid = new VideoItemSource("item", m.index(), m.data(), () -> ov, 5000);
        assertEquals(expected(120, 300, idr, 1000, java.util.Set.of(120, 180, 240)), playVideo(mid, 120, idr));
        assertEquals(3, mid.overlaidGops());
    }

    @Test
    void overlayCompletedMidGopTakesOverAtTheNextGop() throws Exception {
        Media m = videoItem();
        java.util.function.IntPredicate idr = i -> i % 60 == 0;
        FakeVideoOverlay ov = new FakeVideoOverlay(300, idr, 1000);
        java.util.concurrent.atomic.AtomicReference<VideoOverlay> job = new java.util.concurrent.atomic.AtomicReference<>();
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), job::get, 0);
        for (int i = 0; i < 30; i++) {
            assertEquals(i, frameNumber(s.nextVideo().data()));
        }
        // the job appears and its first two GOPs complete while GOP 0 plays: GOP 0 stays original
        job.set(ov);
        ov.complete.addAll(List.of(0, 60));
        for (int i = 30; i < 60; i++) {
            assertEquals(i, frameNumber(s.nextVideo().data()));
            assertFalse(s.playingOverlay());
        }
        for (int i = 60; i < 120; i++) {
            assertEquals(1000 + i, frameNumber(s.nextVideo().data()));
            assertTrue(s.playingOverlay());
        }
        // GOP 120 isn't complete yet: original; 180 is by then
        assertEquals(120, frameNumber(s.nextVideo().data()));
        assertFalse(s.playingOverlay());
        ov.complete.add(180);
        for (int i = 121; i < 180; i++) {
            assertEquals(i, frameNumber(s.nextVideo().data()));
        }
        assertEquals(1180, frameNumber(s.nextVideo().data()));
        assertEquals(List.of(60, 120, 180), ov.asked);
    }

    @Test
    void overlayFollowsAShortFirstGop() throws Exception {
        Media m = videoItem(20);
        java.util.function.IntPredicate idr = i -> idrAt(i, 20);
        FakeVideoOverlay ov = new FakeVideoOverlay(300, idr, 2000);
        ov.complete.addAll(List.of(0, 80, 260));
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), () -> ov, 0);
        assertEquals(expected(0, 300, idr, 2000, java.util.Set.of(0, 80, 260)), playVideo(s, 0, idr));
        assertEquals(3, s.overlaidGops());
        assertEquals(List.of(0, 20, 80, 140, 200, 260), ov.asked);
    }

    @Test
    void unusableOverlayIsDroppedForThePlay() throws Exception {
        Media m = videoItem();
        java.util.function.IntPredicate idr = i -> i % 60 == 0;
        java.util.Set<Integer> all = java.util.Set.of(0, 60, 120, 180, 240);
        List<Integer> original = expected(0, 300, idr, 0, java.util.Set.of());

        // another video (frame count): never used, never read
        FakeVideoOverlay other = new FakeVideoOverlay(299, idr, 1000);
        other.complete.addAll(all);
        VideoItemSource s = new VideoItemSource("item", m.index(), m.data(), () -> other, 0);
        assertEquals(original, playVideo(s, 0, idr));
        assertEquals(List.of(), other.asked);

        // a read failure at GOP 60: original from there on, the overlay isn't asked again
        FakeVideoOverlay failing = new FakeVideoOverlay(300, idr, 1000);
        failing.complete.addAll(all);
        java.util.concurrent.atomic.AtomicInteger gops = new java.util.concurrent.atomic.AtomicInteger();
        VideoOverlay failAt60 = new VideoOverlay() {
            @Override
            public int frameCount() {
                return 300;
            }

            @Override
            public byte[][] gop(int keyframe) throws IOException {
                if (gops.incrementAndGet() == 2) {
                    throw new IOException("disk gone");
                }
                return failing.gop(keyframe);
            }
        };
        s = new VideoItemSource("item", m.index(), m.data(), () -> failAt60, 0);
        assertEquals(expected(0, 300, idr, 1000, java.util.Set.of(0)), playVideo(s, 0, idr));
        assertEquals(2, gops.get());

        // a GOP with a frame missing, or an IDR in the wrong place: the original GOP plays instead
        FakeVideoOverlay shortGop = new FakeVideoOverlay(300, idr, 1000);
        shortGop.complete.addAll(all);
        shortGop.shortBy = 1;
        s = new VideoItemSource("item", m.index(), m.data(), () -> shortGop, 0);
        assertEquals(original, playVideo(s, 0, idr));
        assertEquals(List.of(0), shortGop.asked);
        for (int at : new int[] {60, 75}) {
            FakeVideoOverlay wrongIdr = new FakeVideoOverlay(300, idr, 1000);
            wrongIdr.complete.addAll(all);
            wrongIdr.wrongIdrAt = at;
            s = new VideoItemSource("item", m.index(), m.data(), () -> wrongIdr, 0);
            assertEquals(expected(0, 300, idr, 1000, java.util.Set.of(0)), playVideo(s, 0, idr), "wrong IDR at " + at);
        }
    }

    @Test
    void itemSourcesPassTheVideoOverlay() throws Exception {
        Media m = videoItem();
        FakeVideoOverlay ov = new FakeVideoOverlay(300, i -> i % 60 == 0, 1000);
        ov.complete.add(0);
        ProgramSource v = ItemSources.forItem("v", true, false, m.index(), m.data(), null, null, () -> ov, 0);
        assertEquals(1000, frameNumber(v.nextVideo().data()));
        CardGop card = CardGop.of(AVCC, cardFrames(0));
        ProgramSource a = ItemSources.forItem("v", true, true, m.index(), m.data(), card, null, () -> ov, 0);
        assertInstanceOf(AudioItemSource.class, a);
        assertEquals(0, frameNumber(a.nextVideo().data()), "played as audio: the card, no overlay GOPs");
        assertEquals(List.of(0), ov.asked);
    }

    // ---- AudioItemSource ----

    @Test
    void audioItemRepeatsItsCardAndStartsAtTheFrameContainingTheStart() throws Exception {
        Media m = audioItem();
        ItemSource src = ItemSources.forItem("a", false, false, m.index(), m.data(), null, 1000);
        AudioItemSource s = assertInstanceOf(AudioItemSource.class, src);
        assertFalse(s.presentAsAudio());
        // 1 s = 48000 ticks; frame 46 is at 47104, frame 47 at 48128
        assertEquals(46, audioNumber(s.nextAudio()));
        assertEquals(47104 * 1000 / 48000, s.mediaStartMs());
        for (int n = 0; n < 200; n++) {
            VideoSample v = s.nextVideo();
            assertEquals(n * 3000L, v.pts());
            assertEquals(n % 60 == 0, v.idr());
            assertEquals(1000 + n % 60, frameNumber(v.data()));
            assertSame(s.card().frame(n % 60), v.data());
        }
        assertEquals(235 - 47, drainAudio(s));
        assertNotNull(s.nextVideo(), "the card never runs out");
    }

    @Test
    void presentAsAudioPlaysAVideoItemWithItsCard() throws Exception {
        Media m = videoItem();
        CardGop card = CardGop.of(AVCC, cardFrames(2000));
        ItemSource src = ItemSources.forItem("v", true, true, m.index(), m.data(), card, 2000);
        AudioItemSource s = assertInstanceOf(AudioItemSource.class, src);
        assertTrue(s.presentAsAudio());
        assertTrue(s.describe().contains("as audio"));
        VideoSample v = s.nextVideo();
        assertTrue(v.idr());
        assertEquals(2000, frameNumber(v.data()));
        // audio from the frame containing 2.000 s (93 at 95232), not snapped to a keyframe
        assertEquals(93, audioNumber(s.nextAudio()));
        assertThrows(IllegalArgumentException.class,
                () -> ItemSources.forItem("v", true, true, m.index(), m.data(), null, 0));
        assertInstanceOf(VideoItemSource.class, ItemSources.forItem("v", true, false, m.index(), m.data(), card, 0));
        // presentAsAudio is ignored for audio items, and a passed card (e.g. the overlaid one) wins
        AudioItemSource a = (AudioItemSource) ItemSources.forItem("a", false, true, audioItem().index(),
                audioItem().data(), card, 0);
        assertFalse(a.presentAsAudio());
        assertSame(card, a.card());
    }

    // ---- card override (overlaid cards) ----

    @Test
    void cardOverrideTakesOverAtGopBoundariesOnly() throws Exception {
        CardGop plain = CardGop.of(AVCC, cardFrames(0));
        CardGop overlaid = CardGop.of(AVCC, cardFrames(500));
        java.util.concurrent.atomic.AtomicReference<CardGop> ov = new java.util.concurrent.atomic.AtomicReference<>();
        FillerSource f = new FillerSource(FillerSource.Filler.PAUSED, plain, ov::get, new byte[] {1});
        for (int n = 0; n < 30; n++) {
            assertEquals(n, frameNumber(f.nextVideo().data()));
        }
        ov.set(overlaid); // mid-GOP: the plain GOP finishes first
        for (int n = 30; n < 60; n++) {
            assertEquals(n, frameNumber(f.nextVideo().data()));
        }
        for (int n = 60; n < 120; n++) {
            VideoSample v = f.nextVideo();
            assertEquals(500 + n % 60, frameNumber(v.data()), "frame " + n);
            assertEquals(n * 3000L, v.pts());
            assertEquals(n % 60 == 0, v.idr());
            if (n == 100) {
                ov.set(null); // gone again: back to the plain card at the next GOP
            }
        }
        assertSame(overlaid, f.showing());
        VideoSample v120 = f.nextVideo();
        assertEquals(0, frameNumber(v120.data()));
        assertTrue(v120.idr());
        assertSame(plain, f.showing());

        Media m = audioItem();
        AudioItemSource a = new AudioItemSource("a", m.index(), m.data(), plain, () -> overlaid, 0, false);
        assertEquals(500, frameNumber(a.nextVideo().data()), "ready at the start: the first GOP is overlaid");
        assertSame(overlaid, a.showing());
        assertSame(plain, a.card());
    }

    // ---- FillerSource ----

    @Test
    void fillerRepeatsCardAndSilenceForever() throws Exception {
        CardGop card = CardGop.of(AVCC, cardFrames(0));
        byte[] silence = {0x21, 0x10, 0x05, 0x20};
        FillerSource f = new FillerSource(FillerSource.Filler.PAUSED, card, silence);
        silence[0] = 0;
        byte[] first = f.nextAudio();
        assertEquals(0x21, first[0], "the silence frame is copied");
        for (int i = 0; i < 1000; i++) {
            assertSame(first, f.nextAudio());
        }
        for (int n = 0; n < 1000; n++) {
            VideoSample v = f.nextVideo();
            assertEquals(n * 3000L, v.pts());
            assertEquals(n % 60 == 0, v.idr());
        }
        assertEquals("filler paused", f.describe());
        assertEquals(CardRenderer.Filler.QUEUE_EMPTY, FillerSource.Filler.IDLE.serverCard());
        assertEquals(CardRenderer.Filler.LOADING, FillerSource.Filler.LOADING.serverCard());
        assertThrows(IllegalArgumentException.class, () -> new FillerSource(FillerSource.Filler.IDLE, card, new byte[0]));
    }

    // ---- CardGop ----

    @Test
    void cardGopIsChecked() throws Exception {
        assertThrows(IOException.class, () -> CardGop.of(AVCC, cardFrames(0).subList(0, 59)));
        List<byte[]> noIdr = new ArrayList<>(cardFrames(0));
        noIdr.set(0, frame(false, 0));
        assertThrows(IOException.class, () -> CardGop.of(AVCC, noIdr));
        List<byte[]> twoIdr = new ArrayList<>(cardFrames(0));
        twoIdr.set(30, frame(true, 30));
        assertThrows(IOException.class, () -> CardGop.of(AVCC, twoIdr));
        assertThrows(IOException.class, () -> CardGop.of(new byte[] {1, 2, 3}, cardFrames(0)));
        List<byte[]> frames = cardFrames(0);
        CardGop c = CardGop.of(AVCC, frames);
        frames.get(0)[6] = 99;
        assertEquals(0, frameNumber(c.frame(0)), "frames are copied");
        assertArrayEquals(AVCC, c.avcC());
        assertEquals(60, c.frameCount());
        assertEquals(60 * 7, c.size());
    }

    // ---- FileSampleData ----

    @Test
    void fileSampleDataReadsPositionally() throws Exception {
        Media m = videoItem();
        SampleIndex index = m.index();
        Files.write(dir.resolve("mezz.mp4"), m.files.get(0));
        Files.write(dir.resolve("audio.m4a"), m.files.get(1));
        try (FileSampleData data = new FileSampleData(dir, index)) {
            VideoItemSource s = new VideoItemSource("item", index, data, 5000);
            assertEquals(120, frameNumber(s.nextVideo().data()));
            // keyframe at 4 s = 192000: frames 187 (191488) and 188 (192512) tie, the earlier wins
            assertEquals(187, audioNumber(s.nextAudio()));
            assertThrows(EOFException.class, () -> data.read(0, m.files.get(0).length - 2, 5));
            assertThrows(IOException.class, () -> data.read(7, 0, 1));
            data.close();
            assertThrows(IOException.class, () -> data.read(1, 0, 1));
        }
    }
}
