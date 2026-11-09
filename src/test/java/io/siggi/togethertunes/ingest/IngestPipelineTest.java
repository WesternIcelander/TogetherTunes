package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ingest job steps end to end with real ffmpeg/ffprobe (skipped without them), on a SQLite
 * database and a real {@link IngestQueue}: audio and video uploads, a YouTube job with a fake
 * yt-dlp, re-ingest into an existing directory, and the failures that must leave nothing behind.
 */
@Timeout(180)
class IngestPipelineTest {
    @TempDir
    Path dir;

    private Tools tools;
    private DataDir data;
    private Database db;
    private IngestJobs jobs;
    private IngestFixtures fixtures;
    private ReferenceCard reference;
    private final List<IngestQueue> queues = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tools = CardEncoderTest.tools();
        data = DataDir.create(dir.resolve("data"));
        db = Database.open("jdbc:sqlite:" + data.db().resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        jobs = new IngestJobs(db);
        fixtures = new IngestFixtures(db);
        reference = ReferenceCard.ensure(tools, data.cards(), OutputProfile.P720_30, null);
    }

    @AfterEach
    void tearDown() {
        for (IngestQueue q : queues) {
            q.close();
        }
        db.close();
    }

    private IngestQueue queue(Tools tools, ReferenceCard reference, int maxSeconds) throws Exception {
        IngestPipeline pipeline = new IngestPipeline(tools, data, new IngestItems(db), reference, maxSeconds);
        IngestQueue q = new IngestQueue(jobs, 1, pipeline, Clock.systemUTC(), Duration.ofHours(1), Duration.ZERO);
        queues.add(q);
        q.start();
        return q;
    }

    private IngestJob run(Kind kind, String itemId) throws Exception {
        return run(queue(tools, reference, 1800), kind, itemId);
    }

    private IngestJob run(IngestQueue q, Kind kind, String itemId) throws Exception {
        IngestJob job = q.enqueue(itemId, kind);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            IngestJob j = jobs.get(job.id()).orElseThrow();
            if (j.state().finished()) {
                return j;
            }
            Thread.sleep(50);
        }
        fail("job did not finish: " + jobs.get(job.id()).orElseThrow());
        return null;
    }

    private void ffmpeg(String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("-v", "error", "-y"));
        all.addAll(List.of(args));
        tools.ffmpeg(all).run();
    }

    private Path stage(String itemId, String name) throws Exception {
        Path staging = IngestPipeline.stagingDir(data, itemId);
        Files.createDirectories(staging);
        return staging.resolve(name);
    }

    private Object[] itemRow(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, kind, duration_ms, output_profile,"
                    + " loudness_lufs, error FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Object lufs = rs.getObject(5);
                    return new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            lufs == null ? null : rs.getDouble(5), rs.getString(6)};
                }
            }
        });
    }

    /** {original_file, original_codec, original_web, original_gain_db} of an item. */
    private Object[] originalRow(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT original_file, original_codec, original_web,"
                    + " original_gain_db FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Object gain = rs.getObject(4);
                    return new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3),
                            gain == null ? null : rs.getDouble(4)};
                }
            }
        });
    }

    /** Asserts that an item kept no original. */
    private void assertNoOriginal(String id) throws Exception {
        assertArrayEquals(new Object[] {null, null, 0, null}, originalRow(id));
        for (String n : files(IngestPipeline.itemDir(data, id))) {
            assertFalse(n.startsWith("original"), "unexpected " + n);
        }
    }

    private List<String> files(Path d) throws Exception {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void assertNoLeftovers() throws Exception {
        for (String n : files(data.media())) {
            assertFalse(n.startsWith("."), "left in media/: " + n);
        }
        Path jobsDir = data.tmp().resolve("jobs");
        if (Files.exists(jobsDir)) {
            assertEquals(List.of(), files(jobsDir));
        }
    }

    /** A FLAC upload with an embedded cover: an audio item with audio.m4a, card.mp4, cover.jpg and index.bin. */
    @Test
    void audioUploadWithEmbeddedCover() throws Exception {
        String item = fixtures.item("pending");
        Path png = dir.resolve("art.png");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=300x300", "-frames:v", "1", png.toString());
        Path source = stage(item, "source.flac");
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-i", png.toString(), "-t", "4", "-map", "0:a", "-map", "1:v",
                "-c:a", "flac", "-c:v", "png", "-disposition:v", "attached_pic", source.toString());
        byte[] uploaded = Files.readAllBytes(source);
        Loudness.Measurement in = Loudness.measure(tools, source.toString(), "0:a:0", null, null);

        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.flac"), files(itemDir));
        Object[] original = originalRow(item);
        assertEquals("original.flac", original[0]);
        assertEquals("flac/flac", original[1]);
        assertEquals(1, original[2], "a FLAC within 50 ms of audio.m4a is web-playable");
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("audio", row[1]);
        assertTrue(Math.abs((Long) row[2] - 4000) <= 50, "duration " + row[2]);
        assertEquals("720p30", row[3]);
        assertNotNull(row[4]);
        assertTrue((Double) row[4] < -14 && (Double) row[4] > -30, "loudness " + row[4]);
        assertNull(row[5]);

        SampleIndex index = SampleIndex.read(itemDir.resolve("index.bin"));
        assertEquals(List.of("card.mp4", "audio.m4a"), index.files());
        assertEquals(60, index.track(SampleIndex.Kind.VIDEO).sampleCount());
        assertArrayEquals(reference.avcC(), index.avcC());
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")));
        assertNotNull(CardRenderer.readImage(itemDir.resolve("cover.jpg")));
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)), "staging dir is deleted after publish");
        assertArrayEquals(uploaded, Files.readAllBytes(itemDir.resolve("original.flac")), "the original is kept as uploaded");
        double expectedGain = Math.min(-14 - in.inputI(), -1 - in.inputTp());
        assertEquals(expectedGain, (Double) original[3], 0.011);
        assertTrue(job.log().contains("kept the original as original.flac (flac/flac, web-playable"), job.log());
        assertNoLeftovers();
        for (String step : List.of("step: probe", "step: loudnorm pass 1", "step: encode", "step: card", "step: index",
                "step: verify", "step: publish", "verified: audio")) {
            assertTrue(job.log().contains(step), step + " missing from the log:\n" + job.log());
        }
        assertFalse(job.log().contains("step: download"));
        assertFalse(job.log().contains("step: extract audio.m4a"));
        assertEquals(1.0, job.progress());

        // The normalised audio is at about -14 LUFS.
        Loudness.Measurement out = Loudness.measure(tools, itemDir.resolve("audio.m4a").toString(), "0:a:0", null, null);
        assertTrue(Math.abs(out.inputI() + 14) < 1, "output loudness " + out.inputI());
    }

    /**
     * A 24 fps video upload whose audio starts 0.3 s late, re-ingested over an existing item
     * directory: mezz.mp4 + audio.m4a + card.mp4 + index.bin, avcC identical to the reference,
     * IDRs every 2 s, audio padded to start at 0.
     */
    @Test
    void videoUploadReplacesTheOldDirectory() throws Exception {
        String item = fixtures.item("ready");
        Path old = IngestPipeline.itemDir(data, item);
        Files.createDirectories(old);
        Files.writeString(old.resolve("stale.txt"), "old files");
        Path video = dir.resolve("v.mkv");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=24", "-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "5",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", video.toString());
        Path source = stage(item, "source.mkv");
        ffmpeg("-i", video.toString(), "-itsoffset", "0.3", "-i", video.toString(), "-map", "0:v", "-map", "1:a",
                "-c", "copy", source.toString());

        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4"), files(old));
        assertNoOriginal(item);
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)), "the video upload is deleted after ingest");
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("video", row[1]);
        assertTrue(Math.abs((Long) row[2] - 5300) <= 50, "duration " + row[2]);
        SampleIndex index = SampleIndex.read(old.resolve("index.bin"));
        assertEquals(List.of("mezz.mp4", "audio.m4a"), index.files());
        assertArrayEquals(reference.avcC(), index.avcC());
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(old.resolve("card.mp4")));
        SampleIndex.Track v = index.track(SampleIndex.Kind.VIDEO);
        assertTrue(v.sampleCount() >= 150, "frames " + v.sampleCount());
        IngestVerifier.checkKeyframes(index, "mezz.mp4");
        assertEquals(0, index.track(SampleIndex.Kind.AUDIO).pts(0));
        assertTrue(job.log().contains("step: extract audio.m4a"));
        // audio.m4a is the mezzanine's audio, copied
        ToolResult probe = tools.ffprobe("-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0",
                old.resolve("audio.m4a").toString()).stdoutToString(4096).run();
        assertEquals("audio", probe.stdoutString().strip());
        assertNoLeftovers();
    }

    /** A YouTube job downloads from the canonical URL (fake yt-dlp) and ingests the result. */
    @Test
    void youtubeJobDownloadsThenIngests() throws Exception {
        String item = fixtures.item("pending");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET source = 'youtube', youtube_id = ?,"
                    + " kind = 'video' WHERE id = ?")) {
                ps.setString(1, "dQw4w9WgXcQ");
                ps.setString(2, item);
                ps.executeUpdate();
            }
            return null;
        });
        Path fixture = dir.resolve("yt.mkv");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=426x240:r=30", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "libopus", fixture.toString());
        Path argsFile = dir.resolve("ytdlp-args.txt");
        Path script = dir.resolve("fake-yt-dlp");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                + "out=''\n"
                + "while [ $# -gt 0 ]; do if [ \"$1\" = -o ]; then out=\"$2\"; shift; fi; shift; done\n"
                + "echo '[youtube] dQw4w9WgXcQ: Downloading webpage'\n"
                + "echo '[download]  42.0% of 1.00MiB at 1.00MiB/s ETA 00:01'\n"
                + "echo '[download] 100% of 1.00MiB'\n"
                + "cp '" + fixture + "' \"$(echo \"$out\" | sed 's/%(ext)s/mkv/')\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Tools withYtdlp = new Tools(CardEncoderTest.onPath("ffmpeg"), CardEncoderTest.onPath("ffprobe"), script.toString());

        IngestJob job = run(queue(withYtdlp, reference, 1800), Kind.YOUTUBE, item);
        assertEquals(State.DONE, job.state(), job.log());
        List<String> args = Files.readAllLines(argsFile);
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", args.get(args.size() - 1));
        assertTrue(job.log().contains("step: download"));
        assertTrue(job.log().contains("[youtube] dQw4w9WgXcQ: Downloading webpage"), job.log());
        assertFalse(job.log().contains("42.0%"), "progress lines become progress, not log lines");
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4"), files(IngestPipeline.itemDir(data, item)));
        assertEquals("ready", itemRow(item)[0]);
        assertNoOriginal(item);
        assertNoLeftovers();
    }

    /** Digital silence can't be measured; it is encoded without normalisation and no loudness is stored. */
    @Test
    void silenceIsEncodedWithoutLoudness() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "3", stage(item, "source.wav").toString());
        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        Object[] row = itemRow(item);
        assertEquals("audio", row[1]);
        assertNull(row[4]);
        assertTrue(job.log().contains("encoding without normalisation"));
        assertFalse(Files.exists(IngestPipeline.itemDir(data, item).resolve("cover.jpg")));
        // the original is kept, but without a gain (nothing to measure)
        assertArrayEquals(new Object[] {"original.wav", "pcm_s16le/wav", 1, null}, originalRow(item));
    }

    /**
     * Originals of other audio formats: MP3 (also with no extension on the upload), Opus in Ogg
     * and in WebM, AAC in M4A and ADTS are web-playable; Vorbis, AIFF, ALAC and Opus in a .mkv are
     * kept but not web-playable.
     */
    @Test
    void originalsOfAudioFormats() throws Exception {
        record Case(String staged, String[] encode, String file, String codec, int web) {
        }
        List<Case> cases = List.of(
                new Case("source.mp3", new String[] {"-c:a", "libmp3lame", "-b:a", "128k"}, "original.mp3", "mp3/mp3", 1),
                new Case("source", new String[] {"-c:a", "libmp3lame", "-f", "mp3"}, "original.mp3", "mp3/mp3", 1),
                new Case("source.ogg", new String[] {"-c:a", "libopus"}, "original.opus", "opus/ogg", 1),
                new Case("source.webm", new String[] {"-c:a", "libopus"}, "original.webm", "opus/matroska,webm", 1),
                new Case("source.mkv", new String[] {"-c:a", "libopus"}, "original.mkv", "opus/matroska,webm", 0),
                new Case("source.m4a", new String[] {"-c:a", "aac"}, "original.m4a", "aac/mov,mp4,m4a,3gp,3g2,mj2", 1),
                new Case("source.aac", new String[] {"-c:a", "aac", "-f", "adts"}, "original.aac", "aac/aac", 1),
                new Case("source.ogg", new String[] {"-c:a", "libvorbis"}, "original.ogg", "vorbis/ogg", 0),
                new Case("source.aiff", new String[] {"-c:a", "pcm_s16be"}, "original.aiff", "pcm_s16be/aiff", 0),
                new Case("source.M4A", new String[] {"-c:a", "alac"}, "original.m4a", "alac/mov,mp4,m4a,3gp,3g2,mj2", 0));
        IngestQueue q = queue(tools, reference, 1800);
        for (Case c : cases) {
            String item = fixtures.item("pending");
            Path source = stage(item, c.staged());
            List<String> args = new ArrayList<>(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "3"));
            args.addAll(List.of(c.encode()));
            args.add(source.toString());
            ffmpeg(args.toArray(String[]::new));
            byte[] uploaded = Files.readAllBytes(source);
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), c + "\n" + job.log());
            Object[] original = originalRow(item);
            assertEquals(c.file(), original[0], c.staged());
            assertEquals(c.codec(), original[1], c.staged());
            assertEquals(c.web(), original[2], c.staged() + "\n" + job.log());
            assertNotNull(original[3], c.staged());
            assertTrue((Double) original[3] > 0 && (Double) original[3] < 20, c.staged() + " gain " + original[3]);
            Path itemDir = IngestPipeline.itemDir(data, item);
            assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", c.file()), files(itemDir));
            assertArrayEquals(uploaded, Files.readAllBytes(itemDir.resolve(c.file())), c.staged());
            assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)));
        }
        assertNoLeftovers();
    }

    /** Re-ingesting an item as a video upload drops the original an earlier audio ingest kept. */
    @Test
    void videoReingestDropsTheOriginal() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "2", stage(item, "source.wav").toString());
        assertEquals(State.DONE, run(q, Kind.UPLOAD, item).state());
        assertEquals("original.wav", originalRow(item)[0]);

        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=320x240:r=30", "-f", "lavfi", "-i", "sine=r=48000", "-t", "2",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", stage(item, "source.mp4").toString());
        IngestJob job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("video", itemRow(item)[1]);
        assertNoOriginal(item);
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)));
    }

    @Test
    void fileWithoutAudioFailsAndKeepsTheUpload() throws Exception {
        String item = fixtures.item("pending");
        Path source = stage(item, "source.mp4");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=320x240:r=30", "-t", "1", "-c:v", "libx264", "-preset", "ultrafast",
                source.toString());
        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertEquals(List.of("failed", "The file has no audio"), List.of(fixtures.itemStatus(item)));
        assertFalse(Files.exists(IngestPipeline.itemDir(data, item)));
        assertTrue(Files.exists(source), "the upload stays for a retry");
        assertNoLeftovers();
    }

    @Test
    void tooLongAndUnreadableAndMissingFilesFail() throws Exception {
        IngestQueue q = queue(tools, reference, 2);
        String longItem = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "3", stage(longItem, "source.wav").toString());
        assertEquals(State.FAILED, run(q, Kind.UPLOAD, longItem).state());
        assertTrue(fixtures.itemStatus(longItem)[1].startsWith("The file is too long (3.000 s; the limit is 2 s)"),
                fixtures.itemStatus(longItem)[1]);

        String junk = fixtures.item("pending");
        Files.writeString(stage(junk, "source.mp3"), "this is not audio");
        assertEquals(State.FAILED, run(q, Kind.UPLOAD, junk).state());
        assertTrue(fixtures.itemStatus(junk)[1].startsWith("The file can't be read as audio or video"),
                fixtures.itemStatus(junk)[1]);

        String missing = fixtures.item("pending");
        assertEquals(State.FAILED, run(q, Kind.UPLOAD, missing).state());
        assertEquals("The uploaded file is missing", fixtures.itemStatus(missing)[1]);
        assertNoLeftovers();
    }

    /** An encode whose avcC differs from the stored reference is never published. */
    @Test
    void avcCMismatchPublishesNothing() throws Exception {
        Path otherCards = dir.resolve("other-cards");
        Path profileDir = ReferenceCard.profileDir(otherCards, OutputProfile.P720_30);
        Files.createDirectories(profileDir);
        byte[] tampered = reference.avcC();
        tampered[tampered.length - 1] ^= 1;
        Files.write(profileDir.resolve(ReferenceCard.AVCC_FILE), tampered);
        ReferenceCard changed = ReferenceCard.ensure(tools, otherCards, OutputProfile.P720_30, null);
        assertTrue(changed.encoderChanged());

        String item = fixtures.item("ready");
        Path itemDir = IngestPipeline.itemDir(data, item);
        Files.createDirectories(itemDir);
        Files.writeString(itemDir.resolve("audio.m4a"), "current");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "2", stage(item, "source.wav").toString());
        IngestJob job = run(queue(tools, changed, 1800), Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertTrue(fixtures.itemStatus(item)[1].contains("avcC differs"), fixtures.itemStatus(item)[1]);
        assertEquals(List.of("audio.m4a"), files(itemDir));
        assertEquals("current", Files.readString(itemDir.resolve("audio.m4a")));
        assertNoLeftovers();
    }

    @Test
    void withoutAReferenceCardOrForReencodesJobsFail() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "1", stage(item, "source.wav").toString());
        IngestJob job = run(queue(tools, null, 1800), Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertTrue(fixtures.itemStatus(item)[1].startsWith("Ingest is unavailable"));

        String ready = fixtures.item("ready");
        IngestJob reencode = run(Kind.REENCODE, ready);
        assertEquals(State.FAILED, reencode.state());
        assertTrue(reencode.log().contains("isn't implemented yet"));
        assertEquals("ready", fixtures.itemStatus(ready)[0]);
    }
}
