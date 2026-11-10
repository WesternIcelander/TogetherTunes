package io.siggi.togethertunes.output;

import static io.siggi.togethertunes.output.SourcesTest.AVCC;
import static io.siggi.togethertunes.output.SourcesTest.audioFrame;
import static io.siggi.togethertunes.output.SourcesTest.audioNumber;
import static io.siggi.togethertunes.output.SourcesTest.cardFrames;
import static io.siggi.togethertunes.output.SourcesTest.frame;
import static io.siggi.togethertunes.output.SourcesTest.frameNumber;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.SourcesTest.Media;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit builder and the timestamp rules ({@code docs/program-output.md#timestamp-rules}). */
class UnitBuilderTest {
    static final long S0 = OutputClock.START;
    static final long U = UnitBuilder.UNIT_TICKS;
    static final byte[] SILENCE = {0x21, 0x10, 0x04};

    static CardGop card(int tag) throws IOException {
        return CardGop.of(AVCC, cardFrames(tag));
    }

    static FillerSource filler(Filler f) throws IOException {
        return new FillerSource(f, card(5000), SILENCE);
    }

    /** Checks the rules that hold for any unit sequence and remembers what came before. */
    static final class Checker {
        final List<Unit> units = new ArrayList<>();
        long lastVideo = Long.MIN_VALUE;
        long lastAudio = Long.MIN_VALUE;
        Unit prev;
        boolean switched;

        Unit add(Unit u) {
            assertNotNull(u);
            assertFalse(u.video().isEmpty(), "a unit has video");
            assertTrue(u.video().size() <= 60, "at most one GOP");
            Unit.Video first = u.video().get(0);
            assertTrue(first.idr(), "a unit starts with an IDR");
            assertEquals(u.startPts(), first.pts(), "the IDR is at the unit start");
            for (int i = 1; i < u.video().size(); i++) {
                assertFalse(u.video().get(i).idr(), "one IDR per unit");
            }
            if (prev != null) {
                long expected = switched || prev.sourceEnded() ? prev.audioEndPts() : prev.boundaryPts();
                assertEquals(expected, u.startPts(), "units are contiguous");
            }
            assertTrue(u.boundaryPts() > u.startPts());
            // 2 s at most; a unit can only run on by the part of its last audio frame past the 2 s mark
            // (when its source ends there, or the next unit is a new source)
            assertTrue(u.sourceEnded() ? u.boundaryPts() - u.startPts() < U + 1920
                    : u.boundaryPts() - u.startPts() <= U, "unit length");
            assertTrue(u.audioEndPts() - u.startPts() < U + 1920, "unit length incl. its audio");
            for (Unit.Video v : u.video()) {
                assertTrue(v.pts() > lastVideo, "video PTS strictly increasing");
                assertTrue(v.pts() < u.boundaryPts(), "video before the boundary");
                assertEquals(v.pts(), v.dts(), "DTS = PTS");
                assertEquals(v.pts33(), v.dts33());
                assertEquals(v.pts() & ((1L << 33) - 1), v.pts33());
                lastVideo = v.pts();
            }
            for (Unit.Audio a : u.audio()) {
                if (lastAudio != Long.MIN_VALUE) {
                    assertEquals(1920, a.pts() - lastAudio, "audio frames exactly 1920 apart");
                }
                assertTrue(a.pts() >= u.startPts() && a.pts() < u.boundaryPts(), "audio in the unit span");
                assertEquals(a.pts(), a.dts());
                assertEquals(a.pts() & ((1L << 33) - 1), a.pts33());
                lastAudio = a.pts();
            }
            if (!u.audio().isEmpty()) {
                assertEquals(lastAudio + 1920, u.audioEndPts());
            }
            assertTrue(u.audioEndPts() >= u.boundaryPts() && u.audioEndPts() < u.boundaryPts() + 1920);
            if (u.sourceEnded()) {
                assertEquals(u.boundaryPts(), u.audioEndPts());
            }
            prev = u;
            switched = false;
            units.add(u);
            return u;
        }

        void switched() {
            switched = true;
        }
    }

    @Test
    void clockStartsAtTenSeconds() throws Exception {
        assertEquals(900_000, OutputClock.START);
        try (UnitBuilder b = new UnitBuilder()) {
            assertEquals(900_000, b.nextUnitStart());
            assertEquals(900_000, b.switchPoint());
            b.play(filler(Filler.IDLE));
            Unit u = b.next();
            assertEquals(900_000, u.startPts());
            assertEquals(900_000, u.video().get(0).pts());
            assertEquals(900_000, u.audio().get(0).pts());
        }
    }

    @Test
    void fillerIsEmittedInWhole2sUnitsWithContinuousSilence() throws Exception {
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            FillerSource f = filler(Filler.PAUSED);
            b.play(f);
            for (int i = 0; i < 10; i++) {
                Unit u = c.add(b.next());
                assertEquals(S0 + i * U, u.startPts());
                assertEquals(U, u.nominalDuration());
                assertEquals(60, u.video().size());
                for (int n = 0; n < 60; n++) {
                    assertEquals(S0 + i * U + n * 3000L, u.video().get(n).pts());
                    assertEquals(5000 + n, frameNumber(u.video().get(n).data()));
                }
                // 180000 / 1920 = 93.75: units get 94 frames, except every 4th gets 93
                long firstK = (i * U + 1919) / 1920;
                long endK = ((i + 1) * U + 1919) / 1920;
                assertEquals(endK - firstK, u.audio().size());
                assertEquals(S0 + endK * 1920, u.audioEndPts());
                assertSame(f, u.source());
                assertFalse(u.sourceEnded());
                assertEquals(u.boundaryPts(), b.nextUnitStart());
            }
        }
    }

    @Test
    void audioItemEndsMidUnitAndTruncatesTheCardGop() throws Exception {
        Media m = SourcesTest.audioItem(); // 235 AAC frames, 5.0133 s
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            AudioItemSource src = new AudioItemSource("a", m.index(), m.data(), card(1000), 0, false);
            b.play(src);
            Unit u1 = c.add(b.next());
            Unit u2 = c.add(b.next());
            Unit u3 = c.add(b.next());
            assertEquals(94, u1.audio().size());
            assertEquals(94, u2.audio().size());
            assertEquals(47, u3.audio().size());
            assertFalse(u2.sourceEnded());
            assertTrue(u3.sourceEnded());
            assertTrue(b.sourceEnded());
            long end = S0 + 235 * 1920L; // E_n
            assertEquals(end, u3.boundaryPts());
            assertEquals(end, u3.audioEndPts());
            assertEquals(end, b.nextUnitStart());
            assertEquals(end, b.switchPoint());
            // card frames n·3000 < 451200: frames 120..150 of the repeated card, i.e. card frames 0..30
            assertEquals(31, u3.video().size());
            assertEquals(1000, frameNumber(u3.video().get(0).data()));
            assertEquals(1030, frameNumber(u3.video().get(30).data()));
            assertEquals(234, audioNumber(u3.audio().get(46).data()));
            assertThrows(IllegalStateException.class, b::next);

            // the next source starts at E_n with a fresh IDR, audio continuing the cadence
            b.play(filler(Filler.IDLE));
            Unit f = c.add(b.next());
            assertEquals(end, f.startPts());
            assertEquals(end, f.audio().get(0).pts());
            assertEquals(5000, frameNumber(f.video().get(0).data()));
            assertEquals(end + U, f.boundaryPts());
        }
    }

    @Test
    void videoItemFollowsAudio() throws Exception {
        Media m = SourcesTest.videoItem(); // 300 frames (10 s), 469 AAC frames (10.0053 s)
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            b.play(filler(Filler.LOADING));
            c.add(b.next());
            assertEquals(S0 + U, b.nextUnitStart());
            long s = b.switchPoint(); // the filler's emitted audio ends past the 2 s boundary
            assertEquals(S0 + 94 * 1920L, s);
            c.switched();
            b.play(new VideoItemSource("v", m.index(), m.data(), 0));
            int frames = 0;
            int audio = 0;
            Unit u;
            do {
                u = c.add(b.next());
                for (Unit.Video v : u.video()) {
                    int n = frameNumber(v.data());
                    assertEquals(s + n * 3000L, v.pts(), "video PTS = S + relative PTS");
                    assertEquals(n % 60 == 0, v.idr());
                    frames++;
                }
                for (Unit.Audio a : u.audio()) {
                    assertEquals(s + audioNumber(a.data()) * 1920L, a.pts(), "audio frame k at S + k·1920");
                    audio++;
                }
            } while (!u.sourceEnded());
            assertEquals(300, frames);
            assertEquals(469, audio);
            assertEquals(5, c.units.size() - 1);
            assertEquals(s + 469 * 1920L, u.boundaryPts());
        }
    }

    @Test
    void videoLongerThanAudioIsDroppedAtTheEnd() throws Exception {
        Media m = new Media();
        List<byte[]> v = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            v.add(frame(i % 60 == 0, i));
        }
        m.add("mezz.mp4", Kind.VIDEO, SourcesTest.VIDEO_TS, v, 512, 60);
        List<byte[]> a = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            a.add(audioFrame(i));
        }
        m.add("audio.m4a", Kind.AUDIO, SourcesTest.AUDIO_TS, a, 1024, 1);
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            b.play(new VideoItemSource("v", m.index(), m.data(), 0));
            Unit u;
            do {
                u = c.add(b.next());
            } while (!u.sourceEnded());
            assertEquals(3, c.units.size());
            long end = S0 + 200 * 1920L; // 384000 after S
            assertEquals(end, u.boundaryPts());
            assertEquals(8, u.video().size()); // frames 120..127, 127·3000 = 381000 < 384000
            assertEquals(127, frameNumber(u.video().get(7).data()));
        }
    }

    /** A scripted source: video frames at 3000-tick steps with an IDR every {@code gop}, then audio. */
    static final class Scripted implements ProgramSource {
        final int videoFrames;
        final int gop;
        final int audioFrames;
        final boolean firstIdr;
        int v;
        int a;
        boolean closed;

        Scripted(int videoFrames, int gop, int audioFrames, boolean firstIdr) {
            this.videoFrames = videoFrames;
            this.gop = gop;
            this.audioFrames = audioFrames;
            this.firstIdr = firstIdr;
        }

        @Override
        public VideoSample nextVideo() {
            if (v >= videoFrames) {
                return null;
            }
            int i = v++;
            boolean idr = i % gop == 0 && (i > 0 || firstIdr);
            return new VideoSample(frame(idr, i), i * 3000L, idr);
        }

        @Override
        public byte[] nextAudio() {
            return a < audioFrames ? audioFrame(a++) : null;
        }

        @Override
        public String describe() {
            return "scripted";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void videoShorterThanAudioHoldsTheLastPicture() throws Exception {
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            // 1 s of video, 4.5 s of audio
            b.play(new Scripted(30, 60, 211, true)); // 211·1920 = 405120
            Unit u1 = c.add(b.next());
            assertEquals(30, u1.video().size());
            assertEquals(S0 + U, u1.boundaryPts(), "the last frame stays on screen to the 2 s mark");
            assertEquals(94, u1.audio().size());
            Unit u2 = c.add(b.next());
            assertEquals(1, u2.video().size(), "a hold unit repeats the last IDR");
            assertEquals(0, frameNumber(u2.video().get(0).data()));
            assertEquals(S0 + U, u2.video().get(0).pts());
            assertFalse(u2.sourceEnded());
            Unit u3 = c.add(b.next());
            assertTrue(u3.sourceEnded());
            assertEquals(S0 + 211 * 1920L, u3.boundaryPts());
            assertEquals(1, u3.video().size());
        }
    }

    @Test
    void switchingAtAUnitBoundaryStartsWhereTheAudioEnds() throws Exception {
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            Scripted first = new Scripted(600, 60, 100_000, true);
            b.play(first);
            c.add(b.next());
            Unit u = c.add(b.next());
            assertEquals(S0 + 2 * U, u.boundaryPts());
            assertEquals(S0 + 188 * 1920L, u.audioEndPts()); // 360960, past the boundary
            assertEquals(u.boundaryPts(), b.nextUnitStart());
            assertEquals(u.audioEndPts(), b.switchPoint());
            c.switched();
            Scripted second = new Scripted(600, 60, 100_000, true);
            b.play(second);
            assertTrue(first.closed, "the builder closes the source it switches away from");
            assertEquals(u.audioEndPts(), b.nextUnitStart());
            Unit n = c.add(b.next());
            assertEquals(u.audioEndPts(), n.startPts());
            assertEquals(0, audioNumber(n.audio().get(0).data()));
            assertEquals(n.startPts() + U, n.boundaryPts());
            assertFalse(second.closed);
        }
    }

    @Test
    void manySwitchesKeepTimestampsSmooth() throws Exception {
        Media audioItem = SourcesTest.audioItem();
        Media videoItem = SourcesTest.videoItem();
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder()) {
            for (int round = 0; round < 6; round++) {
                ProgramSource src = switch (round % 3) {
                    case 0 -> new VideoItemSource("v", videoItem.index(), videoItem.data(), round * 1700L);
                    case 1 -> new AudioItemSource("a", audioItem.index(), audioItem.data(), card(1000),
                            round * 900L, false);
                    default -> filler(Filler.PAUSED);
                };
                b.play(src);
                c.switched();
                for (int i = 0; i < 3 && !b.sourceEnded(); i++) {
                    c.add(b.next());
                }
            }
            assertTrue(c.units.size() >= 15);
        }
    }

    @Test
    void timestampsWrapAt33BitsOnTheWire() throws Exception {
        long start = OutputClock.WRAP - 500_000;
        Checker c = new Checker();
        try (UnitBuilder b = new UnitBuilder(start)) {
            b.play(filler(Filler.IDLE));
            boolean wrapped = false;
            for (int i = 0; i < 5; i++) {
                Unit u = c.add(b.next());
                for (Unit.Video v : u.video()) {
                    assertTrue(v.pts33() < OutputClock.WRAP);
                    if (v.pts() >= OutputClock.WRAP) {
                        assertEquals(v.pts() - OutputClock.WRAP, v.pts33());
                        wrapped = true;
                    } else {
                        assertEquals(v.pts(), v.pts33());
                    }
                }
            }
            assertTrue(wrapped);
            assertTrue(c.lastVideo > OutputClock.WRAP, "kept unwrapped internally");
        }
        assertEquals(OutputClock.WRAP - 63_000, OutputClock.wrap(-63_000));
    }

    @Test
    void sourceWithoutAudioGivesNoUnit() throws Exception {
        try (UnitBuilder b = new UnitBuilder()) {
            b.play(new Scripted(60, 60, 0, true));
            assertNull(b.next());
            assertTrue(b.sourceEnded());
            assertEquals(S0, b.switchPoint());
            b.play(filler(Filler.IDLE));
            assertEquals(S0, b.next().startPts());
        }
    }

    @Test
    void contractViolationsAreRejected() throws Exception {
        try (UnitBuilder b = new UnitBuilder()) {
            assertThrows(IllegalStateException.class, b::next);
            b.play(new Scripted(60, 60, 100, false));
            assertThrows(IOException.class, b::next);
            assertThrows(IllegalArgumentException.class, () -> b.play(null));
        }
        try (UnitBuilder b = new UnitBuilder()) {
            b.play(new ProgramSource() {
                int n;

                @Override
                public VideoSample nextVideo() {
                    n++;
                    return n > 3 ? null : new VideoSample(frame(n == 1, n), n == 1 ? 0 : 3000, n == 1);
                }

                @Override
                public byte[] nextAudio() {
                    return SILENCE;
                }

                @Override
                public String describe() {
                    return "bad";
                }
            });
            assertThrows(IOException.class, b::next);
        }
    }
}
