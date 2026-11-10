package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Shared set-up and helpers of {@link IngestPipelineTest} and {@link IngestPipelineSlowTest}: a
 * SQLite database, a data directory with the 720p30 reference card, real ffmpeg/ffprobe (the
 * tests are skipped without them) and real {@link IngestQueue}s.
 */
abstract class IngestPipelineTestBase {
    @TempDir
    Path dir;

    Tools tools;
    DataDir data;
    Database db;
    IngestJobs jobs;
    IngestFixtures fixtures;
    ReferenceCard reference;
    final List<IngestQueue> queues = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        tools = CardEncoderTest.tools();
        data = DataDir.create(dir.resolve("data"));
        db = Database.open("jdbc:sqlite:" + data.db().resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        jobs = new IngestJobs(db);
        fixtures = new IngestFixtures(db);
        reference = ReferenceCard.ensure(tools, data.cards(), OutputEncoding.defaultFor(OutputProfile.P720_30), null);
    }

    @AfterEach
    void tearDown() {
        for (IngestQueue q : queues) {
            q.close();
        }
        db.close();
    }

    IngestQueue queue(Tools tools, ReferenceCard reference, int maxSeconds) throws Exception {
        return queue(tools, reference, maxSeconds, () -> null);
    }

    IngestQueue queue(Tools tools, ReferenceCard reference, int maxSeconds,
                      java.util.function.Supplier<String> cookies) throws Exception {
        return queue(tools, reference, maxSeconds, cookies, 1);
    }

    /** A queue with {@code workers} workers, for tests that run independent jobs side by side. */
    IngestQueue queue(Tools tools, ReferenceCard reference, int maxSeconds,
                      java.util.function.Supplier<String> cookies, int workers) throws Exception {
        IngestPipeline pipeline = new IngestPipeline(tools, data, new IngestItems(db), reference, maxSeconds, cookies);
        IngestQueue q = new IngestQueue(jobs, workers, pipeline, Clock.systemUTC(), Duration.ofHours(1), Duration.ZERO);
        queues.add(q);
        q.start();
        return q;
    }

    /** A queue whose pipeline uses {@code presets}; the jobs table queues quality jobs with them. */
    IngestPipeline presetQueue(EncodePresets presets) throws Exception {
        for (IngestQueue q : queues) {
            q.close();
        }
        IngestPipeline pipeline = new IngestPipeline(tools, data, new IngestItems(db), reference, 1800, () -> null,
                presets);
        jobs.qualityPreset(presets::quality);
        IngestQueue q = new IngestQueue(jobs, 1, pipeline, Clock.systemUTC(), Duration.ofMillis(200), Duration.ZERO);
        queues.add(q);
        q.start();
        return pipeline;
    }

    /** Waits until the item's latest job has finished, and returns it. */
    IngestJob awaitLatest(String itemId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            IngestJob j = jobs.latestForItem(itemId).orElseThrow();
            if (j.state().finished()) {
                return j;
            }
            Thread.sleep(50);
        }
        fail("job did not finish: " + jobs.latestForItem(itemId).orElseThrow());
        return null;
    }

    IngestJob awaitJob(String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (System.nanoTime() < deadline) {
            IngestJob j = jobs.get(jobId).orElseThrow();
            if (j.state().finished()) {
                return j;
            }
            Thread.sleep(50);
        }
        fail("job did not finish: " + jobs.get(jobId).orElseThrow());
        return null;
    }

    /** Stages a short video upload for {@code item}. */
    void stageVideo(String item) throws Exception {
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", stage(item, "source.mp4").toString());
    }

    IngestJob run(Kind kind, String itemId) throws Exception {
        return run(queue(tools, reference, 1800), kind, itemId);
    }

    IngestJob run(IngestQueue q, Kind kind, String itemId) throws Exception {
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

    void ffmpeg(String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("-v", "error", "-y"));
        all.addAll(List.of(args));
        tools.ffmpeg(all).run();
    }

    Path stage(String itemId, String name) throws Exception {
        Path staging = IngestPipeline.stagingDir(data, itemId);
        Files.createDirectories(staging);
        return staging.resolve(name);
    }

    Object[] itemRow(String id) throws Exception {
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
    Object[] originalRow(String id) throws Exception {
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
    void assertNoOriginal(String id) throws Exception {
        assertArrayEquals(new Object[] {null, null, 0, null}, originalRow(id));
        for (String n : layout(id)) {
            assertFalse(n.startsWith("original"), "unexpected " + n);
        }
    }

    /**
     * Asserts that an item kept {@code file} only for re-encodes (a video item's or a YouTube
     * download's original: not web-playable, no gain), byte for byte {@code content} if given.
     */
    void assertKeptOnly(String id, String file, String codec, byte[] content) throws Exception {
        assertArrayEquals(new Object[] {file, codec, 0, null}, originalRow(id));
        Path kept = IngestPipeline.itemDir(data, id).resolve(file);
        assertTrue(Files.isRegularFile(kept), file + " missing");
        if (content != null) {
            assertArrayEquals(content, Files.readAllBytes(kept), file + " is the source, unchanged");
        }
    }

    /** The directory of the item's current media version. */
    Path cur(String item) throws Exception {
        return MediaVersions.currentDir(db, data, item);
    }

    /** The item's current files as one list: its current version's files and its kept original. */
    List<String> layout(String item) throws Exception {
        Path itemDir = IngestPipeline.itemDir(data, item);
        List<String> out = new ArrayList<>(files(cur(item)));
        for (String n : files(itemDir)) {
            if (n.startsWith("original") && !out.contains(n)) {
                out.add(n);
            }
        }
        out.removeIf(n -> MediaVersions.parseVersionName(n).isPresent());
        java.util.Collections.sort(out);
        return out;
    }

    List<String> files(Path d) throws Exception {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    void assertNoLeftovers() throws Exception {
        for (String n : files(data.media())) {
            assertFalse(n.startsWith("."), "left in media/: " + n);
        }
        Path jobsDir = data.tmp().resolve("jobs");
        if (Files.exists(jobsDir)) {
            assertEquals(List.of(), files(jobsDir));
        }
    }

    /** {@code codec_type} of every stream in a file, in order. */
    List<String> streamTypes(Path file) throws Exception {
        ToolResult r = tools.ffprobe("-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0",
                file.toString()).stdoutToString(4096).run();
        return r.stdoutString().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /** Number of frames of a file's first video stream (decoded). */
    int videoFrames(Path file) throws Exception {
        ToolResult r = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries",
                "stream=nb_read_frames", "-of", "csv=p=0", file.toString()).stdoutToString(4096).run();
        return Integer.parseInt(r.stdoutString().strip());
    }

    void assertAudioLayout(Path itemDir) throws Exception {
        SampleIndex index = SampleIndex.read(itemDir.resolve("index.bin"));
        assertEquals(List.of("card.mp4", "audio.m4a"), index.files());
        assertEquals(List.of("audio"), streamTypes(itemDir.resolve("audio.m4a")));
        assertEquals(List.of("video"), streamTypes(itemDir.resolve("card.mp4")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")));
        assertEquals(60, videoFrames(itemDir.resolve("card.mp4")));
        assertEquals(60, index.track(SampleIndex.Kind.VIDEO).sampleCount());
        assertFalse(Files.exists(itemDir.resolve("mezz.mp4")));
    }

    String[] videoSize(Path file) throws Exception {
        return tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                "-of", "csv=p=0", file.toString()).stdoutToString(4096).run().stdoutString().strip().split(",");
    }

    void makeYoutube(String item) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET source = 'youtube', youtube_id = ?"
                    + " WHERE id = ?")) {
                ps.setString(1, "dQw4w9WgXcQ");
                ps.setString(2, item);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A fake yt-dlp that copies {@code fixture} to the requested output, or fails if it is null. */
    Tools fakeYtdlp(Path fixture, String name) throws Exception {
        Path script = dir.resolve(name);
        String body = fixture == null
                ? "echo 'ERROR: [youtube] dQw4w9WgXcQ: Video unavailable' >&2\nexit 1\n"
                : "out=''\n"
                + "while [ $# -gt 0 ]; do if [ \"$1\" = -o ]; then out=\"$2\"; shift; fi; shift; done\n"
                + "cp '" + fixture + "' \"$(echo \"$out\" | sed 's/%(ext)s/mkv/')\"\n";
        Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return new Tools(CardEncoderTest.onPath("ffmpeg"), CardEncoderTest.onPath("ffprobe"), script.toString());
    }

    String itemProfile(String item) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT output_profile FROM items WHERE id = ?")) {
                ps.setString(1, item);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    void assertSameAvcC(String name, String item, boolean video) throws Exception {
        assertEquals("ready", itemRow(item)[0], name);
        assertEquals(video ? "video" : "audio", itemRow(item)[1], name);
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("card.mp4")), name + " card");
        assertArrayEquals(reference.avcC(), SampleIndex.read(cur(item).resolve("index.bin")).avcC(), name + " index");
        assertEquals(video, Files.exists(cur(item).resolve("mezz.mp4")), name);
        if (video) {
            assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("mezz.mp4")), name + " mezz");
        }
    }
}
