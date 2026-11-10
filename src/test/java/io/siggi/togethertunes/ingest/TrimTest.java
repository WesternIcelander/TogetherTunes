package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Video trims end to end with real ffmpeg ({@code docs/library-and-ingest.md#trim}): every kind of
 * smart trim and the full re-encode produce a valid encode (avcC, an IDR at 0 and every 2 s after the
 * first GOP, which only a smart trim makes shorter, the audio as long as the range) whose frames match a full re-encode of the same range from the original
 * (PSNR); a failing splice falls back to a full re-encode; the trim settings travel with the
 * version (undo).
 */
@Timeout(300)
@Execution(ExecutionMode.SAME_THREAD)
class TrimTest extends IngestPipelineTestBase {
    /** Length of the test source. */
    private static final int SOURCE_S = 13;

    /** A 13 s 30 fps video upload, ingested untrimmed. */
    private String ingestedVideo(IngestQueue q) throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100",
                "-t", Integer.toString(SOURCE_S), "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                stage(item, "source.mp4").toString());
        IngestJob job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        return item;
    }

    /** Runs a trim job that must succeed as planned (a smart trim that falls back fails the test). */
    private IngestJob trim(IngestQueue q, String item, long startMs, Long endMs) throws Exception {
        IngestJob j = trimAllowingFallback(q, item, startMs, endMs);
        assertTrue(!logSays(j, "the smart trim failed"), j.log());
        return j;
    }

    private IngestJob trimAllowingFallback(IngestQueue q, String item, long startMs, Long endMs) throws Exception {
        IngestJob job = jobs.enqueueTrim(item, EditSettings.withVideoTrim(null, startMs, endMs, null) == null ? "{}"
                : EditSettings.withVideoTrim(null, startMs, endMs, null));
        q.wake();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            IngestJob j = jobs.get(job.id()).orElseThrow();
            if (j.state().finished()) {
                assertEquals(State.DONE, j.state(), j.log());
                return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("trim did not finish");
    }

    /**
     * Checks the item's current version for the range: avcC, IDRs every 2 s from 0, frame count,
     * audio length, the stored settings, and its frames against a full encode of the range.
     */
    private void assertTrimmed(String name, String item, long startMs, long endMs) throws Exception {
        assertTrimmed(name, item, startMs, endMs, 60);
    }

    /** {@link #assertTrimmed(String, String, long, long)} for an encode whose first GOP has {@code firstGop} frames. */
    private void assertTrimmed(String name, String item, long startMs, long endMs, int firstGop) throws Exception {
        Path cur = cur(item);
        SampleIndex index = SampleIndex.read(cur.resolve(SampleIndex.FILE_NAME));
        assertArrayEquals(reference.avcC(), index.avcC(), name);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur.resolve(IngestPipeline.MEZZ)), name);
        IngestVerifier.checkKeyframes(index, name, firstGop);
        assertEquals(firstGop, IngestPipeline.firstGop(index.track(SampleIndex.Kind.VIDEO)), name);
        assertEquals(TrimPlan.frames(endMs - startMs), index.track(SampleIndex.Kind.VIDEO).sampleCount(), name);
        long audioUs = IngestVerifier.audioDurationUs(index);
        assertTrue(Math.abs(audioUs - (endMs - startMs) * 1000) <= IngestVerifier.MAX_AUDIO_DRIFT_US,
                name + ": audio " + audioUs + " µs");
        assertEquals(audioUs / 1000, (long) itemRow(item)[2], name + ": duration_ms");
        EditSettings stored = EditSettings.parse(MediaVersions.currentEditJson(db, item));
        assertEquals(startMs, stored.videoStartMs(), name);
        assertEquals(endMs >= SOURCE_S * 1000L ? null : Long.valueOf(endMs), stored.videoEndMs(), name);
        assertEquals(SOURCE_S * 1000L, stored.originalMs(), name);
        assertEquals(firstGop, EditSettings.firstGopFrames(MediaVersions.currentEditJson(db, item)), name);

        // the frames match a full encode of the same range from the original
        Path original = IngestPipeline.itemDir(data, item).resolve("original.mp4");
        MediaProbe probe = MediaProbe.probe(tools, original, null);
        Path full = dir.resolve("full-" + name.replace(' ', '-') + ".mp4");
        tools.ffmpeg(IngestPipeline.videoEncodeArgs(IngestPipeline.rangeArgs(startMs * 1000, endMs * 1000), original,
                probe, IngestPipeline.NOT_MEASURED, reference.encoding(), io.siggi.togethertunes.config.X264Preset.REFERENCE,
                full)).run();
        double min = minPsnr(cur.resolve(IngestPipeline.MEZZ), full);
        assertTrue(min >= 30, name + ": lowest frame PSNR " + min + " dB");
    }

    /** The lowest per-frame PSNR (average over the planes) of {@code a} against {@code b}. */
    private double minPsnr(Path a, Path b) throws Exception {
        Path stats = dir.resolve("psnr-" + System.nanoTime() + ".log");
        tools.ffmpeg(List.of("-v", "error", "-i", a.toString(), "-i", b.toString(), "-lavfi",
                "[0:v][1:v]psnr=stats_file=" + stats, "-f", "null", "-")).run();
        double min = Double.POSITIVE_INFINITY;
        int frames = 0;
        for (String line : Files.readAllLines(stats)) {
            for (String field : line.split(" ")) {
                if (field.startsWith("psnr_avg:")) {
                    String v = field.substring("psnr_avg:".length());
                    min = Math.min(min, v.equals("inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(v));
                    frames++;
                }
            }
        }
        assertTrue(frames > 0, "no frames compared");
        return min;
    }

    private static boolean logSays(IngestJob job, String text) {
        return job.log() != null && job.log().contains(text);
    }

    /**
     * One item through every case in turn, each against the encode before it: end earlier (cut
     * only), end later (the last GOP again), start later on the grid (cut only), start earlier on
     * the grid (GOPs added at the front), start later off the grid (a short first GOP up to the
     * next keyframe), start earlier off the grid (the short first GOP encoded again with what is
     * new), and once more later off the grid.
     */
    @Test
    void everyKindOfTrimMakesAValidEncode() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = ingestedVideo(q);

        IngestJob j = trim(q, item, 0, 11_500L);
        assertTrue(logSays(j, "no video is encoded"), j.log());
        assertTrimmed("end earlier", item, 0, 11_500);

        j = trim(q, item, 0, 12_500L);
        assertTrue(logSays(j, "smart trim: keep frames 0..299 (300), then encode 75 frames from 10.000 s"), j.log());
        assertTrimmed("end later", item, 0, 12_500);

        j = trim(q, item, 4000, 12_500L);
        assertTrue(logSays(j, "smart trim: keep frames 120..374") && logSays(j, "no video is encoded"), j.log());
        assertTrimmed("start later", item, 4000, 12_500);

        j = trim(q, item, 2000, null);
        assertTrue(logSays(j, "smart trim: encode 60 frames from 2.000 s, then keep frames 0..239 (240), then encode 30 frames from 12.000 s"),
                j.log());
        assertTrimmed("start earlier, end later", item, 2000, SOURCE_S * 1000L);

        j = trim(q, item, 2500, 9000L);
        assertTrue(logSays(j, "smart trim: encode 45 frames from 2.500 s (first GOP 45), then keep frames 60..209 (150)"),
                j.log());
        assertTrimmed("start later off the grid", item, 2500, 9000, 45);

        // the current first GOP (45 frames from 2.5 s) is encoded again: keyframes at 1.7 s, 2 s, 4 s, ...
        j = trim(q, item, 1700, 9000L);
        assertTrue(logSays(j, "smart trim: encode 69 frames from 1.700 s (first GOP 9), then keep frames 45..194 (150)"),
                j.log());
        assertTrimmed("start earlier off the grid", item, 1700, 9000, 9);

        j = trim(q, item, 3100, 9000L);
        assertTrue(logSays(j, "smart trim: encode 27 frames from 3.100 s (first GOP 27), then keep frames 69..218 (150)"),
                j.log());
        assertTrimmed("start later off the grid again", item, 3100, 9000, 27);

        // each was a person's change: Undo goes back to the one before, with its settings and first GOP
        MediaVersions versions = new MediaVersions(data, db, System::currentTimeMillis, MediaVersions.GRACE);
        versions.undoWindow(() -> 3_600_000L);
        MediaVersions.Undone undone = versions.undo(item, (c, u) -> { });
        assertEquals(1700, EditSettings.parse(undone.editJson()).videoStartMs());
        assertEquals(1700, EditSettings.parse(MediaVersions.currentEditJson(db, item)).videoStartMs());
        assertEquals(9, EditSettings.firstGopFrames(MediaVersions.currentEditJson(db, item)));
        SampleIndex restored = SampleIndex.read(cur(item).resolve(SampleIndex.FILE_NAME));
        IngestVerifier.checkKeyframes(restored, "undone", 9);
        // undo the undo: back to the 27-frame first GOP
        versions.undo(item, (c, u) -> { });
        assertEquals(27, EditSettings.firstGopFrames(MediaVersions.currentEditJson(db, item)));

        // a re-encode made by the system starts on the normal grid again
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE items SET output_profile = 'old' WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        j = run(q, Kind.REENCODE, item);
        assertEquals(State.DONE, j.state(), j.log());
        assertTrimmed("re-encode after off-grid trims", item, 3100, 9000, 60);
        assertNoLeftovers();
    }

    /** A splice that fails (the current version's video is broken) falls back to a full re-encode. */
    @Test
    void aFailingSpliceFallsBackToAFullReencode() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = ingestedVideo(q);
        Path mezz = cur(item).resolve(IngestPipeline.MEZZ);
        byte[] bytes = Files.readAllBytes(mezz);
        // keep the moov (faststart: at the front) and cut off most of the frames
        byte[] broken = java.util.Arrays.copyOf(bytes, bytes.length / 4);
        Files.delete(mezz);
        Files.write(mezz, broken);
        IngestJob j = trimAllowingFallback(q, item, 2000, 9000L);
        assertTrue(logSays(j, "the smart trim failed"), j.log());
        assertTrimmed("fallback", item, 2000, 9000);
        assertNoLeftovers();
    }

    /** A system re-encode of a trimmed item applies its trim again, from the original. */
    @Test
    void aReencodeKeepsTheTrim() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = ingestedVideo(q);
        trim(q, item, 2000, 9000L);
        IngestJob j = run(q, Kind.REENCODE, item); // already encoded for the profile: nothing to do
        assertEquals(State.DONE, j.state());
        // pretend the item is for another encoding
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE items SET output_profile = 'old' WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        j = run(q, Kind.REENCODE, item);
        assertEquals(State.DONE, j.state(), j.log());
        assertTrue(logSays(j, "video trim: 2.000 s to 9.000 s"), j.log());
        assertTrimmed("re-encode", item, 2000, 9000);
    }

    /** The plan in words matches what the job log shows. */
    @Test
    void describeNamesThePieces() {
        List<String> out = new ArrayList<>();
        out.add(IngestPipeline.describe(TrimPlan.plan(0, 390, 0, 11_500)));
        out.add(IngestPipeline.describe(TrimPlan.plan(0, 390, 2500, 9000)));
        assertEquals(List.of("keep frames 0..344 (345); no video is encoded",
                "encode 45 frames from 2.500 s (first GOP 45), then keep frames 120..269 (150)"), out);
    }
}
