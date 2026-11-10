package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.media.SampleIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Audio trims end to end with real ffmpeg ({@code docs/library-and-ingest.md#audio-trim}): an
 * audio item's {@code audio.m4a} is encoded again from the original for the range (its length, its
 * loudness measured on the range), the original stays untouched and is no longer played in
 * browsers; a video's audio trim makes a separate audio-only file and index without touching its
 * video, follows a later video trim, can be removed; undo brings back the settings and files.
 */
@Timeout(300)
@Execution(ExecutionMode.SAME_THREAD)
class AudioTrimTest extends IngestPipelineTestBase {
    private IngestJob trim(IngestQueue q, String item, String jobJson) throws Exception {
        IngestJob job = jobs.enqueueTrim(item, jobJson);
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

    private static String audioJob(Long startMs, Long endMs) {
        return EditSettings.job(false, 0, null, true, startMs, endMs);
    }

    private Long audioOnlyMs(String item) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT audio_only_ms FROM items WHERE id = ?")) {
                ps.setString(1, item);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        });
    }

    private static byte[] sha256(Path f) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f));
    }

    private static void assertNear(long expectedMs, long actualUs, String what) {
        assertTrue(Math.abs(actualUs - expectedMs * 1000) <= IngestVerifier.MAX_AUDIO_DRIFT_US,
                what + ": " + actualUs + " µs, expected " + expectedMs + " ms");
    }

    /**
     * An audio upload whose first 3 s are loud and the rest 30 dB quieter, trimmed to the quiet
     * part: audio.m4a is the range, normalised as measured on it (a measurement of the whole file
     * would be dominated by the loud part), the original is byte for byte the upload and is no
     * longer played in browsers; undo brings back the untrimmed version and its original_web.
     */
    @Test
    void anAudioItemTrimIsEncodedFromTheOriginal() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = fixtures.item("pending");
        Path source = stage(item, "source.m4a");
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=48000:d=10", "-af",
                "volume='if(lt(t,3),1,0.0316)':eval=frame", "-c:a", "aac", "-b:a", "192k", source.toString());
        byte[] uploaded = sha256(source);
        IngestJob ingest = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, ingest.state(), ingest.log());
        Object[] before = originalRow(item);
        Path original = IngestPipeline.itemDir(data, item).resolve((String) before[0]);
        assertNear(10_000, IngestVerifier.audioDurationUs(SampleIndex.read(cur(item).resolve(SampleIndex.FILE_NAME))),
                "untrimmed");

        IngestJob j = trim(q, item, audioJob(4000L, 9000L));
        assertTrue(j.log().contains("audio trim: 4.000 s to 9.000 s"), j.log());
        Path cur = cur(item);
        SampleIndex index = SampleIndex.read(cur.resolve(SampleIndex.FILE_NAME));
        assertArrayEquals(reference.avcC(), index.avcC());
        assertNear(5000, IngestVerifier.audioDurationUs(index), "trimmed audio.m4a");
        assertEquals(IngestVerifier.audioDurationUs(index) / 1000, (long) itemRow(item)[2], "duration_ms");
        assertFalse(Files.exists(cur.resolve(IngestPipeline.AUDIO_ONLY)), "an audio item has no separate audio-only file");
        assertNull(audioOnlyMs(item));
        // loudness measured on the range: the quiet part, normalised to the target
        double lufs = (double) itemRow(item)[4];
        assertTrue(lufs < -25, "measured on the quiet range: " + lufs);
        Loudness.Measurement out = Loudness.measure(tools, cur.resolve(IngestPipeline.AUDIO).toString(), "0:a:0", null, null);
        assertTrue(Math.abs(out.inputI() - Loudness.TARGET_I) < 1.5, "audio.m4a at " + out.inputI() + " LUFS");
        // the original is untouched, kept, and not played in browsers while trimmed (the web plays audio.m4a)
        assertArrayEquals(uploaded, sha256(original), "the original is the upload");
        assertEquals(before[0], originalRow(item)[0]);
        assertEquals(0, originalRow(item)[2], "original_web");
        assertNull(originalRow(item)[3], "original_gain_db");
        EditSettings stored = EditSettings.parse(MediaVersions.currentEditJson(db, item));
        assertEquals(new EditSettings(0, null, 10_000L, 4000L, 9000L), stored);

        // undo: back to the whole original, played as before
        MediaVersions versions = new MediaVersions(data, db, System::currentTimeMillis, MediaVersions.GRACE);
        versions.undoWindow(() -> 3_600_000L);
        versions.undo(item, (c, u) -> { });
        assertNear(10_000, IngestVerifier.audioDurationUs(SampleIndex.read(cur(item).resolve(SampleIndex.FILE_NAME))),
                "after undo");
        assertEquals(10_000L, (long) itemRow(item)[2], 60);
        assertArrayEquals(before, originalRow(item), "original_web and gain come back");
        assertFalse(EditSettings.parse(MediaVersions.currentEditJson(db, item)).audioTrimmed());

        // a system re-encode of a trimmed item applies the trim again
        versions.undo(item, (c, u) -> { }); // redo
        assertTrue(EditSettings.parse(MediaVersions.currentEditJson(db, item)).audioTrimmed());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET output_profile = 'old' WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        j = run(q, Kind.REENCODE, item);
        assertEquals(State.DONE, j.state(), j.log());
        assertTrue(j.log().contains("audio trim: 4.000 s to 9.000 s"), j.log());
        assertNear(5000, IngestVerifier.audioDurationUs(SampleIndex.read(cur(item).resolve(SampleIndex.FILE_NAME))),
                "re-encoded");
        assertEquals(0, originalRow(item)[2], "still not played in browsers");
        assertArrayEquals(uploaded, sha256(original));
        assertNoLeftovers();
    }

    private void assertAudioOnly(String name, String item, long lengthMs) throws Exception {
        Path cur = cur(item);
        assertTrue(Files.isRegularFile(cur.resolve(IngestPipeline.AUDIO_ONLY)), name);
        SampleIndex index = SampleIndex.read(cur.resolve(IngestPipeline.AUDIO_ONLY_INDEX));
        assertArrayEquals(reference.avcC(), index.avcC(), name);
        IngestVerifier.checkKeyframes(index, name);
        long us = IngestVerifier.audioDurationUs(index);
        assertNear(lengthMs, us, name);
        assertEquals(us / 1000, audioOnlyMs(item), name + ": audio_only_ms");
    }

    /**
     * A video's audio trim: an audio-only file and index for audio-only rooms, the video and
     * audio.m4a untouched (linked from the version before); a later video trim keeps the audio
     * trim; removing it removes the file; undo brings it back.
     */
    @Test
    void aVideoAudioTrimMakesAnAudioOnlyVariant() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100",
                "-t", "13", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                stage(item, "source.mp4").toString());
        IngestJob ingest = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, ingest.state(), ingest.log());
        assertNull(audioOnlyMs(item));
        Path before = cur(item);
        byte[] mezz = sha256(before.resolve(IngestPipeline.MEZZ));
        byte[] audio = sha256(before.resolve(IngestPipeline.AUDIO));
        long duration = (long) itemRow(item)[2];

        IngestJob j = trim(q, item, audioJob(3000L, 8000L));
        assertTrue(j.log().contains("audio trim only"), j.log());
        assertFalse(j.log().contains("step: encode\n"), "no video encode");
        assertAudioOnly("audio trim", item, 5000);
        Path cur = cur(item);
        assertArrayEquals(mezz, sha256(cur.resolve(IngestPipeline.MEZZ)), "the video is kept");
        assertArrayEquals(audio, sha256(cur.resolve(IngestPipeline.AUDIO)), "audio.m4a follows the video, not the audio trim");
        assertEquals(duration, (long) itemRow(item)[2], "the item's length is the video's");
        assertEquals(new EditSettings(0, null, 13_000L, 3000L, 8000L),
                EditSettings.parse(MediaVersions.currentEditJson(db, item)));

        // a video trim keeps the audio trim (and makes the audio-only file again for the new version)
        j = trim(q, item, EditSettings.job(true, 0, 11_500L, false, null, null));
        assertTrue(j.log().contains("smart trim"), j.log());
        assertAudioOnly("after a video trim", item, 5000);
        EditSettings both = EditSettings.parse(MediaVersions.currentEditJson(db, item));
        assertEquals(new EditSettings(0, 11_500L, 13_000L, 3000L, 8000L), both);

        // the whole original's audio for audio-only rooms, while the video is trimmed
        j = trim(q, item, audioJob(0L, null));
        assertAudioOnly("whole original", item, 13_000);

        // removed: the audio-only file is audio.m4a again
        j = trim(q, item, audioJob(null, null));
        assertTrue(j.log().contains("no audio trim"), j.log());
        assertFalse(Files.exists(cur(item).resolve(IngestPipeline.AUDIO_ONLY)));
        assertFalse(Files.exists(cur(item).resolve(IngestPipeline.AUDIO_ONLY_INDEX)));
        assertNull(audioOnlyMs(item));

        // undo: the audio trim, its file and its length come back
        MediaVersions versions = new MediaVersions(data, db, System::currentTimeMillis, MediaVersions.GRACE);
        versions.undoWindow(() -> 3_600_000L);
        versions.undo(item, (c, u) -> { });
        assertAudioOnly("after undo", item, 13_000);
        assertEquals(0L, EditSettings.parse(MediaVersions.currentEditJson(db, item)).audioStartMs());
        assertNoLeftovers();
    }

    /**
     * A card-only re-encode (a card in an older card format) of a video with an audio trim makes
     * the audio-only index again around the new card; the audio-only file, the video and the edit
     * settings stay as they are.
     */
    @Test
    void aCardOnlyReencodeRebuildsTheAudioOnlyIndex() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100",
                "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                stage(item, "source.mp4").toString());
        assertEquals(State.DONE, run(q, Kind.UPLOAD, item).state());
        trim(q, item, audioJob(2000L, 6000L));
        assertAudioOnly("audio trim", item, 4000);
        String edit = MediaVersions.currentEditJson(db, item);
        Path before = cur(item);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET card_version = 1 WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        IngestJob j = run(q, Kind.REENCODE, item);
        assertEquals(State.DONE, j.state(), j.log());
        assertTrue(j.log().contains("[ingest] card only"), j.log());
        Path after = cur(item);
        assertFalse(after.equals(before));
        assertAudioOnly("after the card-only re-encode", item, 4000);
        for (String f : List.of(IngestPipeline.MEZZ, IngestPipeline.AUDIO, IngestPipeline.AUDIO_ONLY,
                SampleIndex.FILE_NAME)) {
            assertTrue(Files.isSameFile(before.resolve(f), after.resolve(f)), f);
        }
        SampleIndex index = SampleIndex.read(after.resolve(IngestPipeline.AUDIO_ONLY_INDEX));
        assertTrue(index.track(SampleIndex.Kind.VIDEO).size(1) < SkipFrameCardTest.MAX_SKIP_FRAME_BYTES,
                "the audio-only index uses the new card");
        CardEncoderTest.checkSkipFrameCard(tools, after.resolve(IngestPipeline.CARD), reference.encoding());
        assertEquals(edit, MediaVersions.currentEditJson(db, item), "the edit settings are kept");
        assertEquals(CardEncoder.CARD_VERSION, new IngestItems(db).cardVersion(item));
        assertNoLeftovers();
    }
}
