package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
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
        return new ProgramMedia(media, OutputEncoding.defaultFor(OutputProfile.P720_30), SourcesTest.AVCC, this::loadCard, fillers,
                SourcesTest.audioFrame(9999), queued::add, 0, now::get);
    }

    /** Loads run on virtual threads; {@code item} waits up to 5 s. */
    ProgramMedia asyncMedia() {
        return new ProgramMedia(media, OutputEncoding.defaultFor(OutputProfile.P720_30), SourcesTest.AVCC, this::loadCard, fillers,
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

    /**
     * Audio-only rooms: a video entry that plays audio-only uses its separate audio-only file
     * ({@code index-audio-only.bin}: {@code audio-only.m4a} + {@code card.mp4}) when it has one,
     * else its {@code card.mp4} with the audio of {@code index.bin}; both are separate slots of
     * the same item, and neither touches {@code mezz.mp4}.
     */
    @Test
    void audioOnlyEntriesPlayTheAudioOnlyFile() throws IOException {
        write("video1", SourcesTest.videoItem());
        SourcesTest.Media variant = new SourcesTest.Media();
        List<byte[]> audio = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            audio.add(SourcesTest.audioFrame(5000 + i));
        }
        variant.add("audio-only.m4a", SampleIndex.Kind.AUDIO, 48_000, audio, 1024, 1);
        variant.add("card.mp4", SampleIndex.Kind.VIDEO, 15_360, SourcesTest.cardFrames(2000), 512, 60);
        Path dir = media.resolve("video1");
        for (int i = 0; i < variant.names.size(); i++) {
            Files.write(dir.resolve(variant.names.get(i)), variant.files.get(i));
        }
        variant.index().write(dir.resolve("index-audio-only.bin"));
        Files.delete(dir.resolve("mezz.mp4")); // never read in audio-only mode

        Entry withFile = new Entry("e5", "video1", true, false, 2133, true, true);
        Entry withoutFile = new Entry("e6", "video1", true, false, 10_000, true, false);
        assertTrue(withFile.playsAsAudio());
        try (ProgramMedia pm = asyncMedia()) {
            ProgramSource a = pm.item(withFile, 0);
            AudioItemSource as = assertInstanceOf(AudioItemSource.class, a);
            assertTrue(as.presentAsAudio());
            assertEquals(2000, SourcesTest.frameNumber(a.nextVideo().data()), "the card in the variant's index");
            assertEquals(5000, SourcesTest.audioNumber(a.nextAudio()), "audio-only.m4a");
            assertEquals(99, SourcesTest.drainAudio(a));
            assertEquals(0, cardLoads.get());

            ProgramSource b = pm.item(withoutFile, 0);
            assertInstanceOf(AudioItemSource.class, b);
            assertEquals(7000, SourcesTest.frameNumber(b.nextVideo().data()), "card.mp4, loaded");
            assertEquals(0, SourcesTest.audioNumber(b.nextAudio()), "audio.m4a");
            assertEquals(468, SourcesTest.drainAudio(b));
            assertEquals(1, cardLoads.get());

            // both modes of the same item wanted at once (current and next): two slots
            pm.prepare(cue(withFile, withoutFile));
            assertNotNull(pm.item(withFile, 1000));
            assertNotNull(pm.item(withoutFile, 1000));
            assertEquals(Set.of("video1"), pm.loadedItems());
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
        new SampleIndex(OutputEncoding.defaultFor(OutputProfile.P1080_30), m.names, m.tracks, SourcesTest.AVCC)
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
        try (ProgramMedia pm = new ProgramMedia(media, OutputEncoding.defaultFor(OutputProfile.P720_30), new byte[] {1, 2, 3}, this::loadCard,
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
    void badItemIdIsRejected() throws IOException {
        try (ProgramMedia pm = queuedMedia()) {
            // the load fails, so nothing is played (the follower shows the Loading filler)
            assertNull(pm.item(new Entry("e", "../x", false, false, 1), 0));
            runLoads();
            assertNull(pm.item(new Entry("e", "../x", false, false, 1), 0));
            assertTrue(pm.loadedItems().isEmpty());
        }
    }

    /**
     * Every load asks where the item's current files are (its media version): a loaded item keeps
     * the files it opened after a re-encode switched the item, and its next load uses the new ones.
     */
    @Test
    void eachLoadUsesTheCurrentVersion() throws IOException {
        write("audio1", SourcesTest.audioItem());
        java.util.concurrent.atomic.AtomicReference<String> current = new java.util.concurrent.atomic.AtomicReference<>("audio1");
        List<String> asked = new ArrayList<>();
        ProgramMedia.ItemDirs dirs = id -> {
            asked.add(id);
            return media.resolve(current.get());
        };
        try (ProgramMedia pm = new ProgramMedia(dirs, OutputEncoding.defaultFor(OutputProfile.P720_30), SourcesTest.AVCC,
                this::loadCard, fillers, SourcesTest.audioFrame(9999), queued::add, 0, now::get)) {
            pm.prepare(cue(AUDIO, null));
            runLoads();
            assertNotNull(pm.item(AUDIO, 0));
            write("audio1-v2", SourcesTest.audioItem());
            current.set("audio1-v2"); // a re-encode was published
            assertNotNull(pm.item(AUDIO, 2000), "the loaded item keeps its files");
            assertEquals(List.of("audio1"), asked, "no reload while loaded");

            pm.prepare(ProgramCue.IDLE);
            pm.filler(Filler.IDLE);
            pm.filler(Filler.IDLE);
            assertEquals(Set.of(), pm.loadedItems());
            io.siggi.togethertunes.ingest.IngestPipeline.deleteTree(media.resolve("audio1")); // the old version's clean-up
            pm.prepare(cue(AUDIO, null));
            runLoads();
            assertEquals(Set.of("audio1"), pm.loadedItems());
            assertNotNull(pm.item(AUDIO, 0), "loaded from the new version");
            assertEquals(List.of("audio1", "audio1"), asked);
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

    // ---- overlay cards (docs/overlay.md#cards-cheap-path) ----

    /** A fake room overlay: card files named {@code ov-<tag>.mp4}, loaded as cards with that tag. */
    static final class FakeOverlay implements ProgramMedia.OverlayCards {
        volatile Map<Filler, ProgramMedia.CardFile> fillers;
        final Map<String, ProgramMedia.CardFile> items = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> itemsAsked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger fillerLooks = new AtomicInteger();
        volatile IOException failItems;

        @Override
        public Map<Filler, ProgramMedia.CardFile> fillers() {
            fillerLooks.incrementAndGet();
            return fillers;
        }

        @Override
        public ProgramMedia.CardFile item(Entry entry) throws IOException {
            itemsAsked.add(entry.entryId());
            if (failItems != null) {
                throw failItems;
            }
            return items.get(entry.entryId());
        }

        /** Video re-encodes by entry id; every call recorded as "entryId dirName frameCount". */
        final Map<String, VideoOverlay> videos = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> videosAsked = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public java.util.function.Supplier<VideoOverlay> video(Entry entry, Path itemDir, SampleIndex index) {
            videosAsked.add(entry.entryId() + " " + itemDir.getFileName() + " "
                    + index.track(SampleIndex.Kind.VIDEO).sampleCount());
            return () -> videos.get(entry.entryId());
        }

        /** {@code videoNext} calls ("entryId dirName frameCount") and {@code videoWanted} sets, in order. */
        final List<String> nextAsked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Set<String>> wantedSent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void videoNext(Entry entry, Path itemDir, SampleIndex index) {
            nextAsked.add(entry.entryId() + " " + itemDir.getFileName() + " "
                    + index.track(SampleIndex.Kind.VIDEO).sampleCount());
        }

        @Override
        public void videoWanted(Set<String> entryIds) {
            wantedSent.add(entryIds);
        }

        static ProgramMedia.CardFile card(int tag) {
            return new ProgramMedia.CardFile(Path.of("ov-" + tag + ".mp4"), "k" + tag);
        }

        static Map<Filler, ProgramMedia.CardFile> fillerCards(int base) {
            Map<Filler, ProgramMedia.CardFile> m = new EnumMap<>(Filler.class);
            for (Filler f : Filler.values()) {
                m.put(f, card(base + f.ordinal() * 100));
            }
            return m;
        }
    }

    final FakeOverlay overlay = new FakeOverlay();
    final AtomicInteger overlayLoads = new AtomicInteger();

    CardGop loadAnyCard(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.startsWith("ov-")) {
            overlayLoads.incrementAndGet();
            return CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(Integer.parseInt(name.substring(3, name.length() - 4))));
        }
        return loadCard(file);
    }

    ProgramMedia overlayMedia() {
        return new ProgramMedia(flatDir(media), OutputEncoding.defaultFor(OutputProfile.P720_30),
                SourcesTest.AVCC, this::loadAnyCard, fillers, SourcesTest.audioFrame(9999), queued::add, 0, now::get,
                overlay);
    }

    static ProgramMedia.ItemDirs flatDir(Path media) {
        return id -> media.resolve(id);
    }

    /** The tags of a source's next {@code n} video frames' GOP starts. */
    static int nextGopTag(ProgramSource src) throws IOException {
        int tag = SourcesTest.frameNumber(src.nextVideo().data());
        for (int i = 1; i < 60; i++) {
            src.nextVideo();
        }
        return tag;
    }

    @Test
    void overlaidItemCardTakesOverOnceReady() throws IOException {
        write("audio1", SourcesTest.audioItem());
        overlay.items.put(AUDIO.entryId(), FakeOverlay.card(8000));
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(AUDIO, VIDEO));
            assertEquals(List.of(), overlay.itemsAsked, "nothing asked on the cue's thread");
            // run only the item's load: the overlaid card isn't made yet
            queued.remove(0).run();
            ProgramSource a = pm.item(AUDIO, 0);
            assertInstanceOf(AudioItemSource.class, a);
            assertEquals(1000, nextGopTag(a), "plain card while the overlaid one is being made");
            runLoads();
            assertEquals(List.of(AUDIO.entryId()), overlay.itemsAsked, "only the entry played as audio");
            assertEquals(8000, nextGopTag(a), "overlaid card from the next GOP");
            assertEquals(8000, nextGopTag(a));

            // a second source for the same entry (e.g. after a seek) has it from its first GOP
            assertEquals(8000, nextGopTag(pm.item(AUDIO, 2000)));
            assertEquals(1, overlayLoads.get(), "loaded once");
        }
    }

    @Test
    void entryWithoutOverlayKeepsThePlainCard() throws IOException {
        write("audio1", SourcesTest.audioItem());
        write("video1", SourcesTest.videoItem());
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(AUDIO, AS_AUDIO));
            runLoads();
            assertEquals(Set.of(AUDIO.entryId(), AS_AUDIO.entryId()), Set.copyOf(overlay.itemsAsked));
            ProgramSource a = pm.item(AUDIO, 0);
            assertEquals(1000, nextGopTag(a));
            assertEquals(1000, nextGopTag(a));
            runLoads();
            assertEquals(2, overlay.itemsAsked.size(), "a decided entry isn't asked again");
            ProgramSource v = pm.item(AS_AUDIO, 0);
            runLoads();
            assertEquals(7000, nextGopTag(pm.item(AS_AUDIO, 0)), "the video's card.mp4");
            assertEquals(2, overlay.itemsAsked.size());
            assertNotNull(v);
        }
    }

    @Test
    void failedOverlayCardPlaysPlainAndIsRetried() throws IOException {
        write("audio1", SourcesTest.audioItem());
        overlay.items.put(AUDIO.entryId(), FakeOverlay.card(8000));
        overlay.failItems = new IOException("encode failed");
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(AUDIO, null));
            runLoads();
            ProgramSource a = pm.item(AUDIO, 0);
            runLoads();
            assertEquals(1000, nextGopTag(a));
            assertEquals(1, overlay.itemsAsked.size(), "not retried at once");
            overlay.failItems = null;
            now.addAndGet(ProgramMedia.RETRY_MS);
            pm.prepare(cue(AUDIO, null));
            runLoads();
            assertEquals(2, overlay.itemsAsked.size());
            assertEquals(8000, nextGopTag(a), "the playing source picks it up");
        }
    }

    @Test
    void videoPlayedAsVideoAsksForNoCard() throws IOException {
        write("video1", SourcesTest.videoItem());
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(VIDEO, null));
            runLoads();
            assertInstanceOf(VideoItemSource.class, pm.item(VIDEO, 0));
            runLoads();
            assertEquals(List.of(), overlay.itemsAsked);
        }
    }

    @Test
    void videoPlayedAsVideoGetsTheOverlayReencode() throws IOException {
        write("video1", SourcesTest.videoItem());
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(VIDEO, AS_AUDIO));
            runLoads();
            ProgramSource v = pm.item(VIDEO, 0);
            assertInstanceOf(VideoItemSource.class, v);
            assertEquals(List.of("e1 video1 300"), overlay.videosAsked, "asked with the version dir and index");
            assertEquals(0, nextGopTag(v), "no re-encode yet: the original GOP");
            SourcesTest.FakeVideoOverlay job = new SourcesTest.FakeVideoOverlay(300, i -> i % 60 == 0, 1000);
            job.complete.addAll(List.of(60, 120));
            overlay.videos.put(VIDEO.entryId(), job);
            assertEquals(1060, nextGopTag(v), "the overlay GOP once complete");
            assertEquals(1120, nextGopTag(v));
            assertEquals(180, nextGopTag(v), "the original where the re-encode isn't complete");

            ProgramSource a = pm.item(AS_AUDIO, 0);
            runLoads();
            assertInstanceOf(AudioItemSource.class, a);
            assertEquals(1, overlay.videosAsked.size(), "not for an entry played as audio");
        }
    }

    @Test
    void theNextVideoIsHandedOverOnceLoadedAndUnwantedEntriesAreReported() throws IOException {
        write("video1", SourcesTest.videoItem());
        write("audio1", SourcesTest.audioItem());
        Entry video2 = new Entry("e5", "video1", true, false, 10_000);
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(cue(AUDIO, VIDEO));
            assertEquals(List.of(Set.of("e2", "e1")), overlay.wantedSent);
            assertEquals(List.of(), overlay.nextAsked, "not loaded yet");
            runLoads();
            assertEquals(List.of("e1 video1 300"), overlay.nextAsked, "once its files are loaded");
            pm.prepare(cue(AUDIO, VIDEO));
            assertEquals(1, overlay.wantedSent.size(), "sent only when it changes");
            assertEquals(2, overlay.nextAsked.size(), "handed over again at every cue (the scheduler dedups)");

            // the output plays audio1; the next entry changes (vote): e1 is no longer wanted
            assertInstanceOf(AudioItemSource.class, pm.item(AUDIO, 0));
            pm.prepare(cue(AUDIO, AS_AUDIO));
            assertEquals(Set.of("e2", "e4"), overlay.wantedSent.get(overlay.wantedSent.size() - 1));
            runLoads();
            assertEquals(2, overlay.nextAsked.size(), "not for a video played as audio");

            // the room moves on while the output still plays e2: it stays wanted until the switch
            pm.prepare(cue(video2, null));
            assertEquals(Set.of("e2", "e5"), overlay.wantedSent.get(overlay.wantedSent.size() - 1));
            runLoads();
            assertInstanceOf(VideoItemSource.class, pm.item(video2, 0));
            assertEquals(Set.of("e5"), overlay.wantedSent.get(overlay.wantedSent.size() - 1));
            pm.filler(Filler.IDLE);
            pm.prepare(ProgramCue.IDLE);
            assertEquals(Set.of(), overlay.wantedSent.get(overlay.wantedSent.size() - 1));
            pm.prepare(cue(VIDEO, null));
        }
        assertEquals(Set.of(), overlay.wantedSent.get(overlay.wantedSent.size() - 1), "closing gives everything up");
    }

    @Test
    void roomFillerCardsFollowTheRoomsOverlay() throws IOException {
        overlay.fillers = FakeOverlay.fillerCards(6000);
        try (ProgramMedia pm = overlayMedia()) {
            pm.prepare(ProgramCue.IDLE);
            runLoads();
            assertEquals(1, overlay.fillerLooks.get());
            ProgramSource idle = pm.filler(Filler.IDLE);
            assertEquals(6000 + Filler.IDLE.ordinal() * 100, nextGopTag(idle), "overlaid filler card");
            assertEquals(6000 + Filler.PAUSED.ordinal() * 100, nextGopTag(pm.filler(Filler.PAUSED)));
            runLoads();
            assertEquals(1, overlay.fillerLooks.get(), "not looked up again within the recheck interval");

            // the overlay is turned off while the filler plays: plain from the GOP after the recheck
            overlay.fillers = null;
            now.addAndGet(ProgramMedia.FILLER_RECHECK_MS);
            assertEquals(6000 + Filler.IDLE.ordinal() * 100, nextGopTag(idle), "this GOP asks for the recheck");
            runLoads();
            assertEquals(2, overlay.fillerLooks.get());
            assertEquals(5000 + Filler.IDLE.ordinal() * 100, nextGopTag(idle), "plain server card");

            // and on again (another overlayHash): a cue change looks again
            overlay.fillers = FakeOverlay.fillerCards(7000);
            now.addAndGet(ProgramMedia.FILLER_CUE_RECHECK_MS);
            pm.prepare(ProgramCue.IDLE);
            runLoads();
            assertEquals(3, overlay.fillerLooks.get());
            assertEquals(7000 + Filler.IDLE.ordinal() * 100, nextGopTag(idle));
        }
    }

    @Test
    void withoutOverlayNothingIsAsked() throws IOException {
        write("audio1", SourcesTest.audioItem());
        try (ProgramMedia pm = queuedMedia()) {
            pm.prepare(cue(AUDIO, null));
            assertEquals(1, queued.size(), "only the item load");
            runLoads();
            assertEquals(1000, nextGopTag(pm.item(AUDIO, 0)));
            assertEquals(5000, nextGopTag(pm.filler(Filler.PAUSED)));
            assertTrue(queued.isEmpty());
        }
    }
}
