package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The slow end-to-end ingest cases, split from {@link IngestPipelineTest} so that steps can leave
 * them out unless they touch these paths (they still run in {@code mvn test} and {@code mvn
 * verify}): re-encodes for 1080p30, the quality-preset swap (slow/medium encodes), loudness
 * matching of kept originals, and the milestone source set (1080p, 60 fps and portrait sources).
 * Test methods run concurrently.
 */
@Timeout(180)
// The class runs on its own (like every other class); its test methods run side by side.
@Execution(ExecutionMode.SAME_THREAD)
class IngestPipelineSlowTest extends IngestPipelineTestBase {
    /**
     * An audio upload's original, played with its {@code original_gain_db}, is as loud as
     * {@code audio.m4a} (within 1 dB): a quiet source (linear loudnorm), a loud master (gain below
     * 0), a source with a loudness range above 11 LU (loudnorm compresses it, the gain follows the
     * result). A quiet source with high peaks, which loudnorm can only bring to −14 LUFS by
     * compressing it, can't be matched without clipping: its original is kept but not played.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void originalPlaysAsLoudAsAudioM4a() throws Exception {
        record Case(String name, String[] generate, int web) {
        }
        List<Case> cases = List.of(
                new Case("quiet", new String[] {"-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.05:r=44100:d=12",
                        "-ac", "2"}, 1),
                new Case("loud", new String[] {"-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.9:r=44100:d=12",
                        "-af", "alimiter=limit=0.95:level=false", "-ac", "2"}, 1),
                new Case("wide range", new String[] {"-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.4:r=44100:d=12",
                        "-af", "volume='if(lt(mod(t,4),2),1,0.03)':eval=frame", "-ac", "2"}, 1),
                new Case("quiet with peaks", new String[] {"-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.1:r=44100:d=12",
                        "-f", "lavfi", "-i", "aevalsrc='if(lt(mod(t,3),0.005),0.95*sin(2*PI*1000*t),0)':s=44100:d=12",
                        "-filter_complex", "[0][1]amix=inputs=2:normalize=0,aformat=channel_layouts=stereo"}, 0));
        IngestQueue q = queue(tools, reference, 1800);
        for (Case c : cases) {
            String item = fixtures.item("pending");
            Path source = stage(item, "source.flac");
            List<String> args = new ArrayList<>(List.of(c.generate()));
            args.addAll(List.of("-c:a", "flac", source.toString()));
            ffmpeg(args.toArray(String[]::new));
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), c.name() + "\n" + job.log());
            Object[] original = originalRow(item);
            assertEquals(c.web(), original[2], c.name() + "\n" + job.log());
            double gain = (Double) original[3];
            Path itemDir = IngestPipeline.itemDir(data, item);
            Loudness.Measurement m4a = Loudness.measure(tools, cur(item).resolve("audio.m4a").toString(), "0:a:0", null, null);
            // the original as the browser plays it: the gain applied in float, nothing clipped
            Path played = dir.resolve("played-" + item + ".wav");
            ffmpeg("-i", itemDir.resolve("original.flac").toString(), "-af", String.format(Locale.ROOT, "volume=%.2fdB", gain),
                    "-c:a", "pcm_f32le", played.toString());
            Loudness.Measurement heard = Loudness.measure(tools, played.toString(), "0:a:0", null, null);
            if (c.web() == 1) {
                assertEquals(m4a.inputI(), heard.inputI(), 1.0, c.name() + ": audio.m4a " + m4a.inputI()
                        + " LUFS, original with " + gain + " dB " + heard.inputI() + " LUFS");
                assertTrue(heard.inputTp() <= 0.05, c.name() + ": peak " + heard.inputTp());
                assertFalse(job.log().contains("not played in browsers"), job.log());
            } else {
                assertTrue(m4a.inputI() - heard.inputI() > 1, c.name() + ": expected a mismatch, " + m4a.inputI()
                        + " vs " + heard.inputI());
                assertTrue(job.log().contains("not played in browsers: matching audio.m4a needs"), job.log());
            }
        }
    }

    /**
     * After a change from 720p30 to 1080p30: an audio upload and a video upload re-encode from
     * their kept originals (loudness measured again, originals kept as they are); a video upload
     * without its original (made before originals were kept for every item) from its old mezzanine
     * (without loudnorm, keeping the stored loudness); each with the new profile's avcC and size;
     * the items stay ready throughout.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void reencodeUploadsForANewProfile() throws Exception {
        String audioItem = fixtures.item("pending");
        Path png = dir.resolve("art.png");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=300x300", "-frames:v", "1", png.toString());
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-i", png.toString(), "-t", "3", "-map", "0:a", "-map", "1:v",
                "-c:a", "flac", "-c:v", "png", "-disposition:v", "attached_pic", stage(audioItem, "source.flac").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, audioItem).state());
        String videoItem = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "4",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", stage(videoItem, "source.mp4").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, videoItem).state());
        String keptVideo = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=480x360:r=25", "-f", "lavfi", "-i", "sine=f=260:r=44100", "-t", "4",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", stage(keptVideo, "source.mov").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, keptVideo).state());
        Path audioDir = IngestPipeline.itemDir(data, audioItem);
        Path videoDir = IngestPipeline.itemDir(data, videoItem);
        Path keptDir = IngestPipeline.itemDir(data, keptVideo);
        byte[] original = Files.readAllBytes(audioDir.resolve("original.flac"));
        byte[] keptOriginal = Files.readAllBytes(keptDir.resolve("original.mov"));
        Object[] audioBefore = originalRow(audioItem);
        // as if videoItem was ingested before originals were kept for video uploads
        Files.delete(videoDir.resolve("original.mp4"));
        Object[] videoBefore = itemRow(videoItem);
        assertEquals(List.of("1280", "720"), List.of(videoSize(cur(videoItem).resolve("mezz.mp4"))));

        for (IngestQueue q : queues) {
            q.close(); // the 720p30 server stops
        }
        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputEncoding.defaultFor(OutputProfile.P1080_30), null);
        assertEquals(3, jobs.enqueueReencodes(OutputEncoding.defaultFor(OutputProfile.P1080_30)));
        IngestQueue q = queue(tools, hd, 1800);
        for (IngestJob j : jobs.list(State.QUEUED, 10)) {
            assertEquals(Kind.REENCODE, j.kind());
        }
        q.wake();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (!jobs.list(State.QUEUED, 10).isEmpty() || !jobs.list(State.RUNNING, 10).isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "re-encodes did not finish");
            assertEquals("ready", fixtures.itemStatus(audioItem)[0]);
            assertEquals("ready", fixtures.itemStatus(videoItem)[0]);
            assertEquals("ready", fixtures.itemStatus(keptVideo)[0]);
            Thread.sleep(50);
        }
        IngestJob audioJob = jobs.latestForItem(audioItem).orElseThrow();
        IngestJob videoJob = jobs.latestForItem(videoItem).orElseThrow();
        IngestJob keptJob = jobs.latestForItem(keptVideo).orElseThrow();
        assertEquals(State.DONE, audioJob.state(), audioJob.log());
        assertEquals(State.DONE, videoJob.state(), videoJob.log());
        assertEquals(State.DONE, keptJob.state(), keptJob.log());

        // video upload with its original: from it, loudness measured again, original kept
        assertTrue(keptJob.log().contains("re-encoding for 1080p30-6000k-v3 (was 720p30-3000k-v3, preset veryfast) from the original original.mov"),
                keptJob.log());
        assertTrue(keptJob.log().contains("step: loudnorm pass 1"), keptJob.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4", "original.mov"), layout(keptVideo));
        assertKeptOnly(keptVideo, "original.mov", "aac/mov,mp4,m4a,3gp,3g2,mj2", keptOriginal);
        assertEquals("1080p30-6000k-v3", itemRow(keptVideo)[3]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(cur(keptVideo).resolve("mezz.mp4")));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(cur(keptVideo).resolve("mezz.mp4"))));
        IngestVerifier.checkKeyframes(SampleIndex.read(cur(keptVideo).resolve("index.bin")), "mezz.mp4");

        // audio upload: from its original
        assertTrue(audioJob.log().contains("re-encoding for 1080p30-6000k-v3 (was 720p30-3000k-v3, preset veryfast) from the original original.flac"),
                audioJob.log());
        assertTrue(audioJob.log().contains("step: loudnorm pass 1"), audioJob.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.flac"), layout(audioItem));
        assertArrayEquals(original, Files.readAllBytes(audioDir.resolve("original.flac")));
        assertArrayEquals(audioBefore, originalRow(audioItem), "same codec, web flag and gain");
        assertEquals("1080p30-6000k-v3", itemRow(audioItem)[3]);
        assertEquals("ready", itemRow(audioItem)[0]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(cur(audioItem).resolve("card.mp4")));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(cur(audioItem).resolve("card.mp4"))));
        SampleIndex audioIndex = SampleIndex.read(cur(audioItem).resolve("index.bin"));
        assertEquals(OutputEncoding.defaultFor(OutputProfile.P1080_30), audioIndex.encoding());
        assertArrayEquals(hd.avcC(), audioIndex.avcC());

        // video upload without its original: from its old mezzanine, without loudnorm, loudness kept
        assertTrue(videoJob.log().contains("from the previous encode"), videoJob.log());
        assertFalse(videoJob.log().contains("step: loudnorm pass 1"), videoJob.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4"), layout(videoItem));
        Object[] videoAfter = itemRow(videoItem);
        assertEquals("ready", videoAfter[0]);
        assertEquals("video", videoAfter[1]);
        assertEquals("1080p30-6000k-v3", videoAfter[3]);
        assertEquals(videoBefore[4], videoAfter[4], "the loudness measured at the first ingest is kept");
        assertTrue(Math.abs((Long) videoAfter[2] - (Long) videoBefore[2]) <= 50, "duration " + videoAfter[2]);
        assertNoOriginal(videoItem);
        Path mezz = cur(videoItem).resolve("mezz.mp4");
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(mezz));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(mezz)));
        SampleIndex videoIndex = SampleIndex.read(cur(videoItem).resolve("index.bin"));
        assertEquals(OutputEncoding.defaultFor(OutputProfile.P1080_30), videoIndex.encoding());
        IngestVerifier.checkKeyframes(videoIndex, "mezz.mp4");
        // already normalised: the level stays at about -14 LUFS
        Loudness.Measurement out = Loudness.measure(tools, cur(videoItem).resolve("audio.m4a").toString(), "0:a:0", null, null);
        assertTrue(Math.abs(out.inputI() + 14) < 1, "output loudness " + out.inputI());
        assertNoLeftovers();

        // nothing left to do for this profile; a stale job for the current profile is a no-op
        assertEquals(0, jobs.enqueueReencodes(OutputEncoding.defaultFor(OutputProfile.P1080_30)));
        IngestJob noop = run(q, Kind.REENCODE, videoItem);
        assertEquals(State.DONE, noop.state());
        assertTrue(noop.log().contains("already encoded for 1080p30-6000k-v3"), noop.log());
        assertTrue(Files.exists(mezz));
    }

    /**
     * A YouTube item re-encodes from its kept original, without YouTube (the fake yt-dlp fails).
     * One without its original (made before downloads were kept) downloads it again and keeps the
     * new download; if that download fails, it falls back to its old mezzanine. An item that is
     * no longer ready isn't touched.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void reencodeYoutubeItemsFromTheOriginal() throws Exception {
        String item = fixtures.item("pending");
        makeYoutube(item);
        Path fixture = dir.resolve("yt.mkv");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=426x240:r=30", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "libopus", fixture.toString());
        byte[] downloaded = Files.readAllBytes(fixture);
        Tools ok = fakeYtdlp(fixture, "yt-ok");
        Tools broken = fakeYtdlp(null, "yt-broken");
        assertEquals(State.DONE, run(queue(ok, reference, 1800), Kind.YOUTUBE, item).state());
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertKeptOnly(item, "original.mkv", "opus/matroska,webm", downloaded);

        // to 1080p30 with the video gone from YouTube: from the original, no download
        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputEncoding.defaultFor(OutputProfile.P1080_30), null);
        for (IngestQueue q : queues) {
            q.close();
        }
        IngestJob job = run(queue(broken, hd, 1800), Kind.REENCODE, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertTrue(job.log().contains("from the original original.mkv"), job.log());
        assertFalse(job.log().contains("step: download"), job.log());
        assertTrue(job.log().contains("step: loudnorm pass 1"), job.log());
        assertEquals("1080p30-6000k-v3", itemRow(item)[3]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("mezz.mp4")));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(cur(item).resolve("mezz.mp4"))));
        assertKeptOnly(item, "original.mkv", "opus/matroska,webm", downloaded);
        assertNoLeftovers();

        // back to 720p30 without the original (an item made before this): downloaded again, and kept
        Files.delete(itemDir.resolve("original.mkv"));
        for (IngestQueue q : queues) {
            q.close();
        }
        IngestJob refetched = run(queue(ok, reference, 1800), Kind.REENCODE, item);
        assertEquals(State.DONE, refetched.state(), refetched.log());
        assertTrue(refetched.log().contains("from a new download"), refetched.log());
        assertTrue(refetched.log().contains("step: download"), refetched.log());
        assertEquals("720p30-3000k-v3", itemRow(item)[3]);
        assertKeptOnly(item, "original.mkv", "opus/matroska,webm", downloaded);
        assertNoLeftovers();

        // to 1080p30 again, without the original and with the video gone: from the old mezzanine
        Files.delete(itemDir.resolve("original.mkv"));
        for (IngestQueue q : queues) {
            q.close();
        }
        Double lufs = (Double) itemRow(item)[4];
        IngestJob fallback = run(queue(broken, hd, 1800), Kind.REENCODE, item);
        assertEquals(State.DONE, fallback.state(), fallback.log());
        assertTrue(fallback.log().contains("the download failed"), fallback.log());
        assertTrue(fallback.log().contains("re-encoding from the previous mezz.mp4 instead"), fallback.log());
        assertFalse(fallback.log().contains("step: loudnorm pass 1"), fallback.log());
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("1080p30-6000k-v3", row[3]);
        assertEquals(lufs, row[4]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("mezz.mp4")));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(cur(item).resolve("mezz.mp4"))));
        assertNoOriginal(item);
        assertNoLeftovers();

        // a purged item is left alone
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged', output_profile = '720p30'"
                    + " WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        IngestJob skipped = run(queues.get(queues.size() - 1), Kind.REENCODE, item);
        assertEquals(State.DONE, skipped.state());
        assertTrue(skipped.log().contains("the item is purged; nothing to re-encode"), skipped.log());
        assertEquals("purged", fixtures.itemStatus(item)[0]);
    }

    /**
     * The preset tiers (docs/library-and-ingest.md#presets): a request in a room is encoded with
     * the quick preset even when its job asked for quality, and gets its quality re-encode (from
     * the original) once it is in a library; an item added straight to a library is encoded with
     * the quality preset at once and gets no second job. Both encodes have the reference avcC.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void quickForRoomsThenQualityForLibraries() throws Exception {
        presetQueue(EncodePresets.fixed(X264Preset.VERYFAST, X264Preset.SLOW));

        // requested in a room (queued there by the time the job starts)
        String requested = fixtures.item("pending");
        stageVideo(requested);
        fixtures.queue(requested, "queued");
        IngestJob quick = jobs.enqueue(requested, Kind.UPLOAD, IngestJob.Tier.QUALITY);
        assertEquals(State.DONE, awaitJob(quick.id()).state());
        IngestJob quickDone = jobs.get(quick.id()).orElseThrow();
        assertTrue(quickDone.log().contains("x264 preset veryfast (quick)"), quickDone.log());
        assertArrayEquals(new Object[] {"veryfast", false}, fixtures.encode(requested));
        Path dir1 = IngestPipeline.itemDir(data, requested);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(requested).resolve("mezz.mp4")));
        byte[] quickMezz = Files.readAllBytes(cur(requested).resolve("mezz.mp4"));
        assertEquals(quick.id(), jobs.latestForItem(requested).orElseThrow().id(), "not in a library: no quality job");

        // the room owner adds it to their library (what the library's listener does)
        fixtures.addToLibrary(requested);
        assertTrue(jobs.enqueueQualityIfNeeded(requested));
        IngestJob quality = awaitLatest(requested);
        assertEquals(Kind.QUALITY, quality.kind());
        assertEquals(State.DONE, quality.state(), quality.log());
        assertTrue(quality.log().contains("x264 preset slow (quality)"), quality.log());
        assertTrue(quality.log().contains("quality re-encode for 720p30-3000k-v3 (was 720p30-3000k-v3, preset veryfast)"
                + " from the original original.mp4"), quality.log());
        assertArrayEquals(new Object[] {"slow", true}, fixtures.encode(requested));
        assertEquals("ready", itemRow(requested)[0]);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(requested).resolve("mezz.mp4")), "same avcC as the quick encode");
        assertArrayEquals(reference.avcC(), SampleIndex.read(cur(requested).resolve("index.bin")).avcC());
        assertFalse(Arrays.equals(quickMezz, Files.readAllBytes(cur(requested).resolve("mezz.mp4"))), "a new encode");
        assertTrue(Files.exists(dir1.resolve("original.mp4")), "the original stays");
        assertFalse(jobs.enqueueQualityIfNeeded(requested), "never twice");

        // added straight to a library: the quality preset at once, no second job
        String libraryOnly = fixtures.item("pending");
        stageVideo(libraryOnly);
        fixtures.addToLibrary(libraryOnly);
        IngestJob direct = jobs.enqueue(libraryOnly, Kind.UPLOAD, IngestJob.Tier.QUALITY);
        assertEquals(State.DONE, awaitJob(direct.id()).state());
        assertTrue(jobs.get(direct.id()).orElseThrow().log().contains("x264 preset slow (quality)"));
        assertArrayEquals(new Object[] {"slow", true}, fixtures.encode(libraryOnly));
        assertEquals(direct.id(), jobs.latestForItem(libraryOnly).orElseThrow().id());
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(libraryOnly).resolve("mezz.mp4")));

        // a profile re-encode of a library item uses the quality preset; of a room-only item, quick
        assertEquals(IngestJob.Tier.QUALITY, new IngestPipeline(tools, data, new IngestItems(db), reference, 1800)
                .tier(jobs.enqueue(libraryOnly, Kind.REENCODE), new IngestItems(db).get(libraryOnly).orElseThrow()));
        assertNoLeftovers();
    }

    /**
     * Swap while playing: a quality re-encode of an item that is current or next in a room
     * publishes at once, as a new version; the old version's files stay as they were (a web player
     * that started on them finishes on them, and the program output holding them open keeps
     * reading the old bytes) until the item stops playing and its duration plus the grace period
     * have passed.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aQualitySwapPublishesAtOnceWhileTheItemIsPlaying() throws Exception {
        IngestPipeline pipeline = presetQueue(EncodePresets.fixed(X264Preset.VERYFAST, X264Preset.MEDIUM));
        java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        MediaVersions versions = new MediaVersions(data, now::get, Duration.ofMinutes(5));
        versions.inUse(id -> playing.get());
        pipeline.mediaVersions(versions);
        String item = fixtures.item("pending");
        stageVideo(item);
        IngestJob quick = jobs.enqueue(item, Kind.UPLOAD, IngestJob.Tier.QUICK);
        assertEquals(State.DONE, awaitJob(quick.id()).state());
        Path v1 = cur(item);
        assertEquals(IngestPipeline.itemDir(data, item).resolve("v1"), v1);
        Path mezzPath = v1.resolve("mezz.mp4");
        byte[] before = Files.readAllBytes(mezzPath);
        assertEquals(List.of(), versions.pending(), "a new item replaced nothing");

        try (java.nio.channels.FileChannel open = java.nio.channels.FileChannel.open(mezzPath)) {
            // the packager has the item open; someone adds it to a library
            fixtures.addToLibrary(item);
            assertTrue(jobs.enqueueQualityIfNeeded(item));
            IngestJob quality = jobs.latestForItem(item).orElseThrow();
            IngestJob done = awaitJob(quality.id());
            assertEquals(State.DONE, done.state(), done.log());
            assertFalse(done.log().contains("publishing once it has finished"), done.log());
            assertTrue(done.log().contains("published as version 2; version 1 is kept"), done.log());
            assertArrayEquals(new Object[] {"medium", true}, fixtures.encode(item));
            assertEquals(IngestPipeline.itemDir(data, item).resolve("v2"), cur(item), "switched while playing");
            assertFalse(Arrays.equals(before, Files.readAllBytes(cur(item).resolve("mezz.mp4"))), "a new encode");
            assertArrayEquals(before, Files.readAllBytes(mezzPath), "version 1 is untouched");

            // the old version is kept while the item plays, and for its duration + grace after the switch
            long keepMs = (Long) itemRow(item)[2] + Duration.ofMinutes(5).toMillis();
            assertEquals(0, versions.sweep());
            now.addAndGet(keepMs);
            assertEquals(0, versions.sweep(), "still current or next in a room");
            playing.set(false);
            now.addAndGet(-1);
            assertEquals(0, versions.sweep(), "the grace period hasn't passed");
            now.addAndGet(1);
            assertEquals(1, versions.sweep());
            assertFalse(Files.exists(v1));
            assertTrue(Files.isRegularFile(cur(item).resolve("mezz.mp4")));
            assertTrue(Files.isRegularFile(IngestPipeline.itemDir(data, item).resolve("original.mp4")),
                    "the original isn't part of a version");
            assertEquals(List.of(), versions.pending());

            // the open file still reads the old bytes from start to end
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(before.length);
            while (buf.hasRemaining() && open.read(buf, buf.position()) > 0) {
                // read on
            }
            assertArrayEquals(before, buf.array(), "the old file stays readable through its open handle");
        }
        assertNoLeftovers();
    }

    /** An audio item without its original (e.g. ingested before originals were kept) re-encodes from audio.m4a. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void reencodeAudioWithoutOriginalFromAudioM4a() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "3", stage(item, "source.wav").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, item).state());
        Path itemDir = IngestPipeline.itemDir(data, item);
        Files.delete(itemDir.resolve("original.wav"));
        Object lufs = itemRow(item)[4];
        for (IngestQueue q : queues) {
            q.close();
        }
        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputEncoding.defaultFor(OutputProfile.P1080_30), null);
        IngestJob job = run(queue(tools, hd, 1), Kind.REENCODE, item);
        assertEquals(State.DONE, job.state(), "the duration limit doesn't apply to re-encodes\n" + job.log());
        assertTrue(job.log().contains("from the previous encode"), job.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin"), layout(item));
        Object[] row = itemRow(item);
        assertEquals("audio", row[1]);
        assertEquals("1080p30-6000k-v3", row[3]);
        assertEquals(lufs, row[4]);
        assertTrue(Math.abs((Long) row[2] - 3000) <= 50, "duration " + row[2]);
        assertArrayEquals(new Object[] {null, null, 0, null}, originalRow(item));
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("card.mp4")));
    }

    /**
     * The M2 "done when" set on one queue: an MP3, a FLAC with cover art, 24 fps and 60 fps
     * videos, a portrait video and a YouTube download (fake yt-dlp) all become ready, and every
     * card, mezzanine and index carries the reference card's avcC byte for byte.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void milestoneSetIngestsWithOneAvcC() throws Exception {
        Path png = dir.resolve("cover.png");
        ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=400x400", "-frames:v", "1", png.toString());
        Path ytSource = dir.resolve("yt.webm");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libvpx-vp9", "-deadline", "realtime", "-c:a", "libopus", ytSource.toString());
        IngestQueue uploads = queue(tools, reference, 1800);
        IngestQueue youtube = queue(fakeYtdlp(ytSource, "fake-yt-dlp-set"), reference, 1800);

        // original: the kept file and its codec (web-playable for audio uploads, kept only for videos)
        record Case(String name, String staged, String original, String codec, List<String> encode) {
            boolean video() {
                return original.endsWith(".mp4");
            }
        }
        List<Case> cases = List.of(
                new Case("mp3", "source.mp3", "original.mp3", "mp3/mp3", List.of("-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "3",
                        "-c:a", "libmp3lame", "-b:a", "192k")),
                new Case("flac+cover", "source.flac", "original.flac", "flac/flac", List.of("-f", "lavfi", "-i", "sine=f=330:r=48000",
                        "-i", png.toString(), "-t", "3", "-map", "0:a", "-map", "1:v", "-c:a", "flac", "-c:v", "png",
                        "-disposition:v", "attached_pic")),
                new Case("24fps", "source.mp4", "original.mp4", "aac/mov,mp4,m4a,3gp,3g2,mj2", List.of("-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=24",
                        "-f", "lavfi", "-i", "sine=f=220:r=48000", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")),
                new Case("60fps", "source.mp4", "original.mp4", "aac/mov,mp4,m4a,3gp,3g2,mj2", List.of("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=60",
                        "-f", "lavfi", "-i", "sine=f=250:r=44100", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")),
                new Case("portrait", "source.mp4", "original.mp4", "aac/mov,mp4,m4a,3gp,3g2,mj2", List.of("-f", "lavfi", "-i", "testsrc2=s=720x1280:r=30",
                        "-f", "lavfi", "-i", "sine=f=280:r=48000", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")));
        for (Case c : cases) {
            String item = fixtures.item("pending");
            Path source = stage(item, c.staged());
            List<String> args = new ArrayList<>(c.encode());
            args.add(source.toString());
            ffmpeg(args.toArray(String[]::new));
            byte[] uploaded = Files.readAllBytes(source);
            Loudness.Measurement in = c.video() ? null : Loudness.measure(tools, source.toString(), "0:a:0", null, null);
            IngestJob job = run(uploads, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), c.name() + "\n" + job.log());
            assertSameAvcC(c.name(), item, c.video());
            if (c.video()) {
                assertKeptOnly(item, c.original(), c.codec(), uploaded);
            } else {
                // kept as uploaded, web-playable, with the gain that plays it like audio.m4a
                Object[] original = originalRow(item);
                assertEquals(c.original(), original[0], c.name());
                assertEquals(c.codec(), original[1], c.name());
                assertEquals(1, original[2], c.name() + " web-playable");
                assertNotNull(original[3], c.name() + " gain");
                Loudness.Measurement out = Loudness.measure(tools,
                        cur(item).resolve("audio.m4a").toString(), "0:a:0", null, null);
                assertEquals(Math.min(out.inputI() - in.inputI(), -in.inputTp()), (Double) original[3], 0.011,
                        c.name() + " gain");
                assertArrayEquals(uploaded, Files.readAllBytes(IngestPipeline.itemDir(data, item).resolve(c.original())),
                        c.name() + " kept unchanged");
            }
        }
        String yt = fixtures.item("pending");
        makeYoutube(yt);
        IngestJob job = run(youtube, Kind.YOUTUBE, yt);
        assertEquals(State.DONE, job.state(), job.log());
        assertSameAvcC("youtube", yt, true);
        assertKeptOnly(yt, "original.mkv", "opus/matroska,webm", Files.readAllBytes(ytSource));
        assertNoLeftovers();
    }
}
