package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Track;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.output.VideoOverlay;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OverlayVideoJobs}: current (high) / next (normal) scheduling, sharing, cancelling entries
 * that are no longer wanted unless more than half done, the busy pool, failures.
 */
class OverlayVideoJobsTest {
    @TempDir
    Path tmp;

    static final OutputEncoding ENC = OutputEncoding.DEFAULT;
    static final byte[] AVCC = {1, 100, 0, 31, (byte) 0xFF};
    /** 300 frames, a keyframe every 60: GOPs 0..4. */
    static final Track VIDEO = OverlayGopsTest.track(300, 0, 60, 120, 180, 240);
    static final SampleIndex INDEX = new SampleIndex(ENC, List.of("mezz.mp4"), List.of(VIDEO), AVCC);

    static final Entry E1 = new Entry("e1", "item1", true, false, 10_000);
    static final Entry E2 = new Entry("e2", "item2", true, false, 10_000);
    static final Entry E3 = new Entry("e3", "item1", true, false, 10_000);

    final AtomicLong now = new AtomicLong(1_000_000);
    final FakeEncoder encoder = new FakeEncoder();
    final AtomicInteger resolves = new AtomicInteger();
    final AtomicReference<Exception> resolveFailure = new AtomicReference<>();
    volatile boolean decisionVideo = true;
    OverlayPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /** One encode run: the test publishes GOPs and ends it. */
    static final class Run {
        final OverlayVideoEncoder.Request request;
        final OverlayGops gops;
        final DoubleConsumer progress;
        final CompletableFuture<Exception> outcome = new CompletableFuture<>();
        volatile boolean interrupted;

        Run(OverlayVideoEncoder.Request request, OverlayGops gops, DoubleConsumer progress) {
            this.request = request;
            this.gops = gops;
            this.progress = progress;
        }

        /** Writes GOP {@code g}'s frames (each filled with its GOP number + 100) and publishes it. */
        void completeGop(int g) throws IOException {
            long offset = Files.exists(gops.h264File()) ? Files.size(gops.h264File()) : 0;
            for (int f = gops.gopStart(g); f < gops.gopEnd(g); f++) {
                byte[] frame = new byte[10];
                Arrays.fill(frame, (byte) (100 + g));
                Files.write(gops.h264File(), frame, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                gops.frameWritten(f, offset, frame.length);
                offset += frame.length;
            }
            gops.markComplete(g);
        }

        void finish() {
            outcome.complete(null);
        }

        void fail(Exception e) {
            outcome.complete(e);
        }
    }

    static final class FakeEncoder implements OverlayVideoJobs.Encoder {
        final LinkedBlockingQueue<Run> runs = new LinkedBlockingQueue<>();

        @Override
        public void encode(OverlayVideoEncoder.Request request, OverlayGops gops, DoubleConsumer progress)
                throws Exception {
            Run run = new Run(request, gops, progress);
            runs.add(run);
            Exception e;
            try {
                e = run.outcome.get();
            } catch (InterruptedException ie) {
                run.interrupted = true;
                throw ie;
            }
            if (e != null) {
                throw e;
            }
        }

        Run next() throws InterruptedException {
            Run r = runs.poll(5, TimeUnit.SECONDS);
            assertNotNull(r, "no encode started");
            return r;
        }
    }

    OverlayVideoJobs jobs(int workers, boolean start) {
        pool = new OverlayPool(workers);
        if (start) {
            pool.start();
        }
        return new OverlayVideoJobs(tmp.resolve("rooms"), pool, encoder,
                p -> p == OverlayPool.Priority.HIGH ? X264Preset.VERYFAST : X264Preset.SLOW, Runnable::run, now::get);
    }

    OverlayVideoJobs.Session session(OverlayVideoJobs jobs) {
        return jobs.session("room1", ENC, AVCC, entry -> {
            resolves.incrementAndGet();
            Exception f = resolveFailure.get();
            if (f != null) {
                throw f;
            }
            return decisionVideo ? new OverlayVideoJobs.Plan("hash1", new BufferedImage(4, 4,
                    BufferedImage.TYPE_INT_ARGB), 10, 20) : null;
        });
    }

    Path itemDir(String itemId) {
        return tmp.resolve("media").resolve(itemId).resolve("v3");
    }

    static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached in time");
            Thread.sleep(5);
        }
    }

    static int tag(byte[][] gop) {
        return gop[0][0];
    }

    @Test
    void nextIsEncodedAheadFromTheStartAndThePlayUsesIt() throws Exception {
        OverlayVideoJobs jobs = jobs(1, true);
        OverlayVideoJobs.Session s = session(jobs);
        s.next(E1, itemDir("item1"), INDEX);
        Run run = encoder.next();
        assertEquals(0, run.request.g0());
        assertEquals(X264Preset.SLOW, run.request.preset(), "normal priority: the next entry's preset");
        assertEquals(itemDir("item1").resolve("mezz.mp4"), run.request.mezz());
        assertEquals(10, run.request.x());
        assertEquals(20, run.request.y());
        Path dir = tmp.resolve("rooms/room1/ov/item1/v3-hash1-" + ENC.id() + "-g0");
        assertEquals(dir, run.gops.dir());
        assertEquals(dir.resolve("overlay.png"), run.request.overlayPng());
        assertTrue(Files.isRegularFile(run.request.overlayPng()), "the panel the job is made with");

        s.next(E1, itemDir("item1"), INDEX); // later cues: nothing new
        VideoOverlay play = s.play(E1, itemDir("item1"), INDEX);
        assertEquals(300, play.frameCount());
        assertNull(play.gop(0), "GOP 0 not complete yet: the original plays");
        run.completeGop(0);
        run.completeGop(1);
        assertNull(play.gop(30), "not a GOP start");
        assertEquals(101, tag(play.gop(60)));
        assertNull(play.gop(120));
        for (int g = 2; g < 5; g++) {
            run.completeGop(g);
        }
        run.finish();
        eventually(() -> pool.running() == 0);
        assertEquals(104, tag(play.gop(240)));
        assertEquals(1, jobs.submitted(), "one job for the next entry and its play");
        assertEquals(1, resolves.get(), "the overlay is looked up once per entry");

        // the song again (another entry, same item, version, overlay and encoding): the finished encode
        s.wanted(Set.of(E3.entryId()));
        VideoOverlay again = s.play(E3, itemDir("item1"), INDEX);
        assertEquals(100, tag(again.gop(0)));
        assertEquals(1, jobs.submitted());
    }

    @Test
    void aPlayStartingMidItemGetsAHighPriorityJobFromTheNextGop() throws Exception {
        OverlayVideoJobs jobs = jobs(1, true);
        OverlayVideoJobs.Session s = session(jobs);
        VideoOverlay play = s.play(E1, itemDir("item1"), INDEX);
        assertNull(play.gop(120), "GOP 2 is already playing: original");
        Run run = encoder.next();
        assertEquals(3, run.request.g0());
        assertEquals(X264Preset.VERYFAST, run.request.preset(), "high priority: the quick preset");
        assertTrue(run.gops.dir().getFileName().toString().endsWith("-g3"));
        run.completeGop(3);
        assertEquals(103, tag(play.gop(180)), "the overlay from the next GOP");
        run.completeGop(4);
        run.finish();
        eventually(() -> pool.running() == 0);

        // played from the start later: the encode from GOP 3 doesn't cover it, a new one from 0
        s.wanted(Set.of(E3.entryId()));
        VideoOverlay fromStart = s.play(E3, itemDir("item1"), INDEX);
        assertNull(fromStart.gop(0));
        Run second = encoder.next();
        assertEquals(0, second.request.g0());
        assertEquals(103, tag(fromStart.gop(180)), "a GOP complete in any encode of the item is used");
        second.finish();
    }

    @Test
    void aQueuedNextJobIsRaisedWhenItsEntryPlays() throws Exception {
        OverlayVideoJobs jobs = jobs(2, false); // not started: jobs stay queued
        OverlayVideoJobs.Session s = session(jobs);
        s.next(E1, itemDir("item1"), INDEX);
        String key = "room1/item1/v3-hash1-" + ENC.id() + "-g0";
        assertEquals(OverlayPool.Priority.NORMAL, pool.job(key).orElseThrow().priority());
        VideoOverlay play = s.play(E1, itemDir("item1"), INDEX);
        assertNull(play.gop(0));
        assertEquals(OverlayPool.Priority.HIGH, pool.job(key).orElseThrow().priority());
        assertEquals(1, jobs.submitted());
        pool.start();
        Run run = encoder.next();
        assertEquals(X264Preset.VERYFAST, run.request.preset(), "the preset follows the priority at the start");
        run.finish();
    }

    @Test
    void anEntryNoLongerWantedIsCancelledUnlessMoreThanHalfDone() throws Exception {
        OverlayVideoJobs jobs = jobs(2, true);
        OverlayVideoJobs.Session s = session(jobs);
        s.next(E1, itemDir("item1"), INDEX);
        Run first = encoder.next();
        first.progress.accept(0.4);
        s.wanted(Set.of("other"));
        eventually(() -> first.interrupted);
        eventually(() -> pool.running() == 0);

        s.next(E2, itemDir("item2"), INDEX);
        Run second = encoder.next();
        second.progress.accept(0.6);
        s.wanted(Set.of());
        Thread.sleep(50);
        assertFalse(second.interrupted, "more than half done: kept");
        assertEquals(1, pool.running());
        second.finish();
        eventually(() -> pool.running() == 0);
    }

    @Test
    void aJobAnotherOutputStillWantsKeepsRunning() throws Exception {
        OverlayVideoJobs jobs = jobs(2, true);
        OverlayVideoJobs.Session a = session(jobs);
        OverlayVideoJobs.Session b = session(jobs);
        a.next(E1, itemDir("item1"), INDEX);
        b.next(E1, itemDir("item1"), INDEX);
        Run run = encoder.next();
        assertEquals(1, jobs.submitted(), "shared");
        a.wanted(Set.of());
        Thread.sleep(50);
        assertFalse(run.interrupted);
        b.wanted(Set.of());
        eventually(() -> run.interrupted);
    }

    @Test
    void aBusyPoolPlaysWithoutTheOverlayAndTriesAgainWhenAWorkerFrees() throws Exception {
        OverlayVideoJobs jobs = jobs(1, true);
        OverlayVideoJobs.Session s = session(jobs);
        s.next(E1, itemDir("item1"), INDEX);
        Run first = encoder.next();
        s.next(E2, itemDir("item2"), INDEX);
        VideoOverlay play = s.play(E2, itemDir("item2"), INDEX);
        assertNull(play.gop(0));
        assertEquals(1, jobs.submitted(), "busy: nothing queued, the item plays without the overlay");
        assertEquals(0, pool.queued());
        first.finish();
        Run second = encoder.next();
        assertEquals("item2", second.gops.dir().getParent().getFileName().toString());
        assertEquals(0, second.request.g0());
        second.finish();
    }

    @Test
    void onlyVideoDecisionsGetAJobAndFailedLookupsAreRetriedLater() throws Exception {
        OverlayVideoJobs jobs = jobs(1, true);
        OverlayVideoJobs.Session s = session(jobs);
        decisionVideo = false;
        s.next(E1, itemDir("item1"), INDEX);
        assertNull(s.play(E1, itemDir("item1"), INDEX).gop(0));
        assertEquals(0, jobs.submitted());
        assertEquals(1, resolves.get());

        decisionVideo = true;
        resolveFailure.set(new java.sql.SQLException("database down"));
        s.next(E2, itemDir("item2"), INDEX);
        s.next(E2, itemDir("item2"), INDEX);
        assertEquals(2, resolves.get(), "not retried at once");
        resolveFailure.set(null);
        now.addAndGet(OverlayVideoJobs.RETRY_MS);
        s.next(E2, itemDir("item2"), INDEX);
        assertEquals(3, resolves.get());
        encoder.next().finish();
        assertEquals(1, jobs.submitted());
    }

    @Test
    void aMismatchIsNotRetriedOtherFailuresAre() throws Exception {
        OverlayVideoJobs jobs = jobs(1, true);
        OverlayVideoJobs.Session s = session(jobs);
        VideoOverlay play = s.play(E1, itemDir("item1"), INDEX);
        assertNull(play.gop(0));
        encoder.next().fail(new IOException("ffmpeg crashed"));
        eventually(() -> pool.running() == 0);
        now.addAndGet(OverlayVideoJobs.RETRY_MS - 1);
        assertNull(play.gop(60));
        assertEquals(1, jobs.submitted(), "not retried at once");
        now.addAndGet(1);
        assertNull(play.gop(60));
        Run retry = encoder.next();
        assertEquals(2, retry.request.g0(), "from the GOP after the one playing");
        retry.fail(new OverlayVideoEncoder.MismatchException("SPS differs"));
        eventually(() -> pool.running() == 0);
        now.addAndGet(OverlayVideoJobs.RETRY_MS);
        assertNull(play.gop(120));
        s.next(E3, itemDir("item1"), INDEX);
        Thread.sleep(50);
        assertEquals(2, jobs.submitted(), "a mismatch isn't tried again");
        assertTrue(encoder.runs.isEmpty());
    }
}
