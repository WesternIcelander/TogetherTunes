package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.rooms.PlaybackState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgramMediaTest {
    @TempDir
    Path media;

    final List<Runnable> queued = new ArrayList<>();
    final AtomicLong now = new AtomicLong(1_000_000);
    final AtomicInteger cardLoads = new AtomicInteger();
    final Map<Filler, CardGop> fillers = new EnumMap<>(Filler.class);

    static final Entry VIDEO = new Entry("e1", "video1", true, false, 10_000);
    static final Entry AUDIO = new Entry("e2", "audio1", false, false, 5000);
    static final Entry OTHER = new Entry("e3", "audio2", false, false, 5000);
    static final Entry AS_AUDIO = new Entry("e4", "video1", true, true, 10_000);

    ProgramMediaTest() throws IOException {
        for (Filler f : Filler.values()) {
            fillers.put(f, CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(5000 + f.ordinal() * 100)));
        }
    }

    void write(String itemId, SourcesTest.Media m) throws IOException {
        Path dir = Files.createDirectories(media.resolve(itemId));
        for (int i = 0; i < m.names.size(); i++) {
            Files.write(dir.resolve(m.names.get(i)), m.files.get(i));
        }
        m.index().write(dir.resolve(SampleIndex.FILE_NAME));
        if (!m.names.contains("card.mp4")) {
            Files.write(dir.resolve("card.mp4"), new byte[] {1}); // read by the fake card loader only
        }
    }

    /** Loads run when {@link #runLoads} is called; {@code item} doesn't wait. */
    ProgramMedia queuedMedia() {
        return new ProgramMedia(media, OutputProfile.P720_30, SourcesTest.AVCC, this::loadCard, fillers,
                SourcesTest.audioFrame(9999), queued::add, 0, now::get);
    }

    /** Loads run on virtual threads; {@code item} waits up to 5 s. */
    ProgramMedia asyncMedia() {
        return new ProgramMedia(media, OutputProfile.P720_30, SourcesTest.AVCC, this::loadCard, fillers,
                SourcesTest.audioFrame(9999), r -> Thread.ofVirtual().start(r), 5000, now::get);
    }

    CardGop loadCard(Path file) throws IOException {
        assertEquals("card.mp4", file.getFileName().toString());
        cardLoads.incrementAndGet();
        return CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(7000));
    }

    void runLoads() {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
        }
    }

    static ProgramCue cue(Entry current, Entry next) {
        return new ProgramCue(PlaybackState.PLAYING, current, 0, 0, next, null);
    }

    @Test
    void preparesCurrentAndNextOnly() throws IOException {
        write("video1", SourcesTest.videoItem());
        write("audio1", SourcesTest.audioItem());
        write("audio2", SourcesTest.audioItem());
        try (ProgramMedia pm = queuedMedia()) {
            pm.prepare(cue(VIDEO, AUDIO));
            assertEquals(2, queued.size());
            assertEquals(Set.of(), pm.loadedItems());
            assertNull(pm.item(VIDEO, 0), "not loaded yet → null (Loading filler)");
            runLoads();
            assertEquals(Set.of("video1", "audio1"), pm.loadedItems());

            ProgramSource v = pm.item(VIDEO, 4000);
            assertInstanceOf(VideoItemSource.class, v);
            assertEquals(4000, ((ItemSource) v).requestedStartMs());
            assertTrue(Math.abs(((ItemSource) v).mediaStartMs() - 4000) <= 11, "audio frame nearest the keyframe");
            assertEquals(120, SourcesTest.frameNumber(v.nextVideo().data()));

            // the room advances: audio1 current, audio2 next; video1 still plays in the output
            pm.prepare(cue(AUDIO, OTHER));
            runLoads();
            assertEquals(Set.of("video1", "audio1", "audio2"), pm.loadedItems());

            // the output switches to audio1: video1 is dropped at the next call
            ProgramSource a = pm.item(AUDIO, 0);
            assertInstanceOf(AudioItemSource.class, a);
            assertEquals(1000, SourcesTest.frameNumber(a.nextVideo().data()), "the indexed card");
            assertEquals(0, SourcesTest.audioNumber(a.nextAudio()));
            assertEquals(Set.of("video1", "audio1", "audio2"), pm.loadedItems());
            pm.prepare(cue(AUDIO, OTHER));
            assertEquals(Set.of("audio1", "audio2"), pm.loadedItems());
            assertTrue(queued.isEmpty(), "nothing reloaded");

            // idle: everything but the playing item goes; a filler releases that too
            pm.prepare(ProgramCue.IDLE);
            assertEquals(Set.of("audio1"), pm.loadedItems());
            ProgramSource f = pm.filler(Filler.IDLE);
            assertInstanceOf(FillerSource.class, f);
            assertEquals(5000 + Filler.IDLE.ordinal() * 100, SourcesTest.frameNumber(f.nextVideo().data()));
            assertEquals(9999, SourcesTest.audioNumber(f.nextAudio()));
            pm.filler(Filler.IDLE);
            assertEquals(Set.of(), pm.loadedItems());
        }
    }

    @Test
    void positionalReadsFromTheItemFiles() throws IOException {
        write("video1", SourcesTest.videoItem());
        try (ProgramMedia pm = asyncMedia()) {
            ProgramSource v = pm.item(VIDEO, 0);
            assertNotNull(v, "loaded on demand, within the wait");
            int frames = 0;
            VideoSample s;
            while ((s = v.nextVideo()) != null) {
                assertEquals(frames, SourcesTest.frameNumber(s.data()));
                frames++;
            }
            assertEquals(300, frames);
            assertEquals(469, SourcesTest.drainAudio(v));
            // a second source of the same item reads concurrently-safe from the same channels
            ProgramSource v2 = pm.item(VIDEO, 2000);
            assertEquals(60, SourcesTest.frameNumber(v2.nextVideo().data()));
        }
    }

    @Test
    void videoItemAsAudioLoadsItsCardOnce() throws IOException {
        write("video1", SourcesTest.videoItem());
        try (ProgramMedia pm = queuedMedia()) {
            pm.prepare(cue(VIDEO, null));
            runLoads();
            assertEquals(0, cardLoads.get());
            assertNotNull(pm.item(VIDEO, 0));
            // presentAsAudio switched on: the card is loaded in addition to the index
            assertNull(pm.item(AS_AUDIO, 0));
            assertEquals(1, queued.size());
            runLoads();
            ProgramSource a = pm.item(AS_AUDIO, 0);
            assertInstanceOf(AudioItemSource.class, a);
            assertEquals(7000, SourcesTest.frameNumber(a.nextVideo().data()));
            pm.prepare(cue(AS_AUDIO, null));
            assertNotNull(pm.item(AS_AUDIO, 1000));
            assertTrue(queued.isEmpty());
            assertEquals(1, cardLoads.get());
        }
    }

    @Test
    void missingIndexIsRetriedAfterTheBackoff() throws IOException {
        try (ProgramMedia pm = queuedMedia()) {
            pm.prepare(cue(AUDIO, null));
            runLoads();
            assertNull(pm.item(AUDIO, 0));
            assertTrue(queued.isEmpty(), "no retry within " + ProgramMedia.RETRY_MS + " ms");
            write("audio1", SourcesTest.audioItem());
            now.addAndGet(ProgramMedia.RETRY_MS);
            assertNull(pm.item(AUDIO, 0));
            assertEquals(1, queued.size());
            runLoads();
            assertNotNull(pm.item(AUDIO, 0));
        }
    }

    @Test
    void indexForAnotherProfileIsNotPlayed() throws IOException {
        SourcesTest.Media m = SourcesTest.audioItem();
        write("audio1", m);
        new SampleIndex(OutputProfile.P1080_30, m.names, m.tracks, SourcesTest.AVCC)
                .write(media.resolve("audio1").resolve(SampleIndex.FILE_NAME));
        try (ProgramMedia pm = asyncMedia()) {
            assertNull(pm.item(AUDIO, 0));
            assertEquals(Set.of(), pm.loadedItems());
        }
    }

    @Test
    void avcCMustMatchTheReference() throws IOException {
        SourcesTest.Media m = SourcesTest.audioItem();
        write("audio1", m);
        try (ProgramMedia pm = new ProgramMedia(media, OutputProfile.P720_30, new byte[] {1, 2, 3}, this::loadCard,
                fillers, SourcesTest.audioFrame(1), r -> Thread.ofVirtual().start(r), 5000, now::get)) {
            assertNull(pm.item(AUDIO, 0));
        }
    }

    @Test
    void droppedWhileLoadingIsDiscarded() throws IOException {
        write("audio1", SourcesTest.audioItem());
        write("audio2", SourcesTest.audioItem());
        try (ProgramMedia pm = queuedMedia()) {
            pm.prepare(cue(AUDIO, null));
            pm.prepare(cue(OTHER, null));
            runLoads();
            assertEquals(Set.of("audio2"), pm.loadedItems());
        }
    }

    @Test
    void closeReleasesEverything() throws IOException {
        write("audio1", SourcesTest.audioItem());
        ProgramMedia pm = asyncMedia();
        ProgramSource a = pm.item(AUDIO, 0);
        assertNotNull(a);
        pm.close();
        assertEquals(Set.of(), pm.loadedItems());
        assertThrows(IOException.class, () -> pm.item(AUDIO, 0));
        assertThrows(IOException.class, () -> {
            while (a.nextAudio() != null) {
                // reads hit the closed files
            }
        });
    }

    @Test
    void badItemIdIsRejected() {
        try (ProgramMedia pm = queuedMedia()) {
            assertThrows(IllegalArgumentException.class, () -> pm.item(new Entry("e", "../x", false, false, 1), 0));
        }
    }

    @Test
    void followerPlaysThroughProgramMedia() throws IOException {
        write("video1", SourcesTest.videoItem());
        write("audio1", SourcesTest.audioItem());
        try (ProgramMedia pm = queuedMedia();
             TimelineFollower tf = new TimelineFollower(new UnitBuilder(), pm, pts -> (pts - OutputClock.START) / 90, null)) {
            tf.update(cue(VIDEO, AUDIO));
            Unit u = tf.nextUnit();
            assertInstanceOf(FillerSource.class, u.source(), "Loading until the index is in");
            assertEquals(Filler.LOADING, tf.playingFiller());
            runLoads();
            u = tf.nextUnit();
            assertInstanceOf(VideoItemSource.class, u.source());
            assertEquals(Set.of("video1", "audio1"), pm.loadedItems());
        }
    }
}
