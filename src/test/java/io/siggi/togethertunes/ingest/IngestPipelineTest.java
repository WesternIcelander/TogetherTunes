package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.retention.RetentionSweeper;
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
import java.util.Locale;
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
        return queue(tools, reference, maxSeconds, () -> null);
    }

    private IngestQueue queue(Tools tools, ReferenceCard reference, int maxSeconds,
                              java.util.function.Supplier<String> cookies) throws Exception {
        IngestPipeline pipeline = new IngestPipeline(tools, data, new IngestItems(db), reference, maxSeconds, cookies);
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
        Loudness.Measurement out = Loudness.measure(tools, itemDir.resolve("audio.m4a").toString(), "0:a:0", null, null);
        double expectedGain = Math.min(out.inputI() - in.inputI(), -in.inputTp());
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

    /**
     * The verify step's three properties, checked on the published files with ffprobe rather than
     * through {@code index.bin}: for a 60 fps portrait video and a 29.97 fps video, the audio ends
     * within 50 ms of the source's, the mezzanine's avcC is the reference's, and its keyframes are
     * exactly the frames at 0, 2, 4, … s.
     */
    @Test
    void publishedFilesHoldTheVerifiedProperties() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        record Case(String size, String rate, double seconds) {
        }
        for (Case c : List.of(new Case("360x640", "60", 7.0), new Case("640x360", "30000/1001", 6.5))) {
            String item = fixtures.item("pending");
            ffmpeg("-f", "lavfi", "-i", "testsrc2=s=" + c.size() + ":r=" + c.rate(), "-f", "lavfi",
                    "-i", "sine=f=220:r=44100", "-t", Double.toString(c.seconds()), "-c:v", "libx264",
                    "-preset", "ultrafast", "-c:a", "aac", stage(item, "source.mp4").toString());
            IngestJob job = run(q, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), c + "\n" + job.log());
            assertTrue(job.log().contains("[ingest] verified: audio"), job.log());
            Path itemDir = IngestPipeline.itemDir(data, item);
            Path mezz = itemDir.resolve("mezz.mp4");

            assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(mezz), c.toString());

            String audioEnd = tools.ffprobe("-v", "error", "-select_streams", "a:0", "-show_entries",
                    "stream=duration", "-of", "csv=p=0", itemDir.resolve("audio.m4a").toString())
                    .stdoutToString(4096).run().stdoutString().strip();
            assertTrue(Math.abs(Double.parseDouble(audioEnd) - c.seconds()) <= 0.05, c + " audio " + audioEnd);

            String frames = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries",
                    "packet=pts_time,flags", "-of", "csv=p=0", mezz.toString())
                    .stdoutToString(1 << 20).run().stdoutString();
            List<Double> keyframes = new ArrayList<>();
            int count = 0;
            for (String line : frames.strip().split("\n")) {
                String[] f = line.split(",");
                count++;
                if (f[1].contains("K")) {
                    keyframes.add(Double.parseDouble(f[0]));
                }
            }
            int gops = (count + 59) / 60;
            assertEquals(gops, keyframes.size(), c + " keyframes " + keyframes);
            for (int k = 0; k < gops; k++) {
                assertEquals(2.0 * k, keyframes.get(k), 1e-6, c + " keyframes " + keyframes);
            }
        }
        assertNoLeftovers();
    }

    /**
     * A YouTube job downloads from the canonical URL (fake yt-dlp, with the cookies file) and
     * ingests the result; the downloaded thumbnail (WebP) becomes {@code cover.jpg}.
     */
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
        Path thumbnail = dir.resolve("thumb.webp");
        String thumbExt = "webp";
        try {
            ffmpeg("-f", "lavfi", "-i", "color=c=red:s=320x180", "-frames:v", "1", thumbnail.toString());
        } catch (ToolException e) { // no WebP encoder in this ffmpeg
            thumbnail = dir.resolve("thumb.png");
            thumbExt = "png";
            ffmpeg("-f", "lavfi", "-i", "color=c=red:s=320x180", "-frames:v", "1", thumbnail.toString());
        }
        Path cookies = Files.writeString(dir.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        Path argsFile = dir.resolve("ytdlp-args.txt");
        Path script = dir.resolve("fake-yt-dlp");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                + "out=''\nthumb=''\n"
                + "while [ $# -gt 0 ]; do if [ \"$1\" = -o ]; then case \"$2\" in thumbnail:*) thumb=\"${2#thumbnail:}\";;"
                + " *) out=\"$2\";; esac; shift; fi; shift; done\n"
                + "echo '[youtube] dQw4w9WgXcQ: Downloading webpage'\n"
                + "echo '[download]  42.0% of 1.00MiB at 1.00MiB/s ETA 00:01'\n"
                + "echo '[download] 100% of 1.00MiB'\n"
                + "cp '" + thumbnail + "' \"$(echo \"$thumb\" | sed 's/%(ext)s/" + thumbExt + "/')\"\n"
                + "cp '" + fixture + "' \"$(echo \"$out\" | sed 's/%(ext)s/mkv/')\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Tools withYtdlp = new Tools(CardEncoderTest.onPath("ffmpeg"), CardEncoderTest.onPath("ffprobe"), script.toString());

        IngestJob job = run(queue(withYtdlp, reference, 1800, cookies::toString), Kind.YOUTUBE, item);
        assertEquals(State.DONE, job.state(), job.log());
        List<String> args = Files.readAllLines(argsFile);
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", args.get(args.size() - 1));
        assertEquals(cookies.toString(), args.get(args.indexOf("--cookies") + 1));
        assertTrue(job.log().contains("thumbnail cover." + thumbExt), job.log());
        assertTrue(job.log().contains("step: download"));
        assertTrue(job.log().contains("[youtube] dQw4w9WgXcQ: Downloading webpage"), job.log());
        assertFalse(job.log().contains("42.0%"), "progress lines become progress, not log lines");
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "mezz.mp4"),
                files(IngestPipeline.itemDir(data, item)));
        java.awt.image.BufferedImage cover = CardRenderer.readImage(IngestPipeline.itemDir(data, item).resolve("cover.jpg"));
        assertEquals(320, cover.getWidth());
        int rgb = cover.getRGB(160, 90);
        assertTrue(((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) < 60, "the thumbnail is the cover: " + Integer.toHexString(rgb));
        assertEquals("ready", itemRow(item)[0]);
        assertNoOriginal(item);
        assertNoLeftovers();

        // a cookies setting that points nowhere is reported and ignored
        IngestJob again = run(queue(withYtdlp, reference, 1800, () -> dir.resolve("gone.txt").toString()),
                Kind.REFETCH, item);
        assertEquals(State.DONE, again.state(), again.log());
        assertFalse(Files.readAllLines(argsFile).contains("--cookies"));
        assertTrue(again.log().contains("gone.txt (ytdlp.cookiesFile) can't be read"), again.log());
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
     * An audio upload's original, played with its {@code original_gain_db}, is as loud as
     * {@code audio.m4a} (within 1 dB): a quiet source (linear loudnorm), a loud master (gain below
     * 0), a source with a loudness range above 11 LU (loudnorm compresses it, the gain follows the
     * result). A quiet source with high peaks, which loudnorm can only bring to −14 LUFS by
     * compressing it, can't be matched without clipping: its original is kept but not played.
     */
    @Test
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
            Loudness.Measurement m4a = Loudness.measure(tools, itemDir.resolve("audio.m4a").toString(), "0:a:0", null, null);
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
                new Case("source.M4A", new String[] {"-c:a", "alac"}, "original.m4a", "alac/mov,mp4,m4a,3gp,3g2,mj2", 0),
                // WMA decodes 72 ms longer than its container says; verify accepts the decoded length
                new Case("source.wma", new String[] {"-ar", "44100", "-c:a", "wmav2"}, "original.wma", "wmav2/asf", 0));
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

    /** {@code codec_type} of every stream in a file, in order. */
    private List<String> streamTypes(Path file) throws Exception {
        ToolResult r = tools.ffprobe("-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0",
                file.toString()).stdoutToString(4096).run();
        return r.stdoutString().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /** Number of frames of a file's first video stream (decoded). */
    private int videoFrames(Path file) throws Exception {
        ToolResult r = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries",
                "stream=nb_read_frames", "-of", "csv=p=0", file.toString()).stdoutToString(4096).run();
        return Integer.parseInt(r.stdoutString().strip());
    }

    /**
     * The item directory follows the kind, also when one item is re-ingested as the other kind:
     * an audio item is {@code audio.m4a} + {@code card.mp4} (+ index, cover, original) and never a
     * {@code mezz.mp4}; a video item is {@code mezz.mp4} + {@code audio.m4a} + {@code card.mp4}.
     * Cover art (an attached JPEG in MP3 and M4A) doesn't make a video item; Motion JPEG video
     * (a camera AVI) does.
     */
    @Test
    void itemLayoutFollowsTheKind() throws Exception {
        IngestQueue q = queue(tools, reference, 1800);
        String item = fixtures.item("pending");
        Path itemDir = IngestPipeline.itemDir(data, item);
        Path jpg = dir.resolve("art.jpg");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=300x300", "-frames:v", "1", jpg.toString());

        // MP3 with an attached JPEG cover: audio item
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-i", jpg.toString(), "-t", "3", "-map", "0:a", "-map", "1:v",
                "-c:a", "libmp3lame", "-c:v", "copy", "-disposition:v", "attached_pic", stage(item, "source.mp3").toString());
        IngestJob job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("audio", itemRow(item)[1]);
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.mp3"), files(itemDir));
        assertAudioLayout(itemDir);

        // the same item re-ingested from a Motion JPEG AVI: video item, the audio-item files are gone
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=320x240:r=25", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "mjpeg", "-c:a", "pcm_s16le", stage(item, "source.avi").toString());
        job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("video", itemRow(item)[1]);
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4"), files(itemDir));
        assertNoOriginal(item);
        SampleIndex index = SampleIndex.read(itemDir.resolve("index.bin"));
        assertEquals(List.of("mezz.mp4", "audio.m4a"), index.files());
        assertEquals(List.of("video", "audio"), streamTypes(itemDir.resolve("mezz.mp4")));
        assertEquals(List.of("audio"), streamTypes(itemDir.resolve("audio.m4a")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("mezz.mp4")));
        IngestVerifier.checkKeyframes(index, "mezz.mp4");
        // the card is there for presentAsAudio and the transition card
        assertEquals(List.of("video"), streamTypes(itemDir.resolve("card.mp4")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")));
        assertEquals(60, videoFrames(itemDir.resolve("card.mp4")));

        // and back to audio from an M4A with an attached JPEG cover: no mezz.mp4 is left behind
        ffmpeg("-f", "lavfi", "-i", "sine=f=550:r=48000", "-i", jpg.toString(), "-t", "3", "-map", "0:a", "-map", "1:v",
                "-c:a", "aac", "-c:v", "copy", "-disposition:v", "attached_pic", stage(item, "source.m4a").toString());
        job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("audio", itemRow(item)[1]);
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.m4a"), files(itemDir));
        assertAudioLayout(itemDir);
        assertNoLeftovers();
    }

    private void assertAudioLayout(Path itemDir) throws Exception {
        SampleIndex index = SampleIndex.read(itemDir.resolve("index.bin"));
        assertEquals(List.of("card.mp4", "audio.m4a"), index.files());
        assertEquals(List.of("audio"), streamTypes(itemDir.resolve("audio.m4a")));
        assertEquals(List.of("video"), streamTypes(itemDir.resolve("card.mp4")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")));
        assertEquals(60, videoFrames(itemDir.resolve("card.mp4")));
        assertEquals(60, index.track(SampleIndex.Kind.VIDEO).sampleCount());
        assertFalse(Files.exists(itemDir.resolve("mezz.mp4")));
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
    void withoutAReferenceCardJobsFail() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "1", stage(item, "source.wav").toString());
        IngestJob job = run(queue(tools, null, 1800), Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertTrue(fixtures.itemStatus(item)[1].startsWith("Ingest is unavailable"));
    }

    // ------------------------------------------------------------------ re-encodes

    private String[] videoSize(Path file) throws Exception {
        return tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                "-of", "csv=p=0", file.toString()).stdoutToString(4096).run().stdoutString().strip().split(",");
    }

    private void makeYoutube(String item) throws Exception {
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
    private Tools fakeYtdlp(Path fixture, String name) throws Exception {
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

    /**
     * After a change from 720p30 to 1080p30: an audio upload re-encodes from its kept original
     * (loudness measured again, original kept as it is), a video upload from its old mezzanine
     * (without loudnorm, keeping the stored loudness), each with the new profile's avcC and size;
     * the items stay ready throughout.
     */
    @Test
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
        Path audioDir = IngestPipeline.itemDir(data, audioItem);
        Path videoDir = IngestPipeline.itemDir(data, videoItem);
        byte[] original = Files.readAllBytes(audioDir.resolve("original.flac"));
        Object[] audioBefore = originalRow(audioItem);
        Object[] videoBefore = itemRow(videoItem);
        assertEquals(List.of("1280", "720"), List.of(videoSize(videoDir.resolve("mezz.mp4"))));

        for (IngestQueue q : queues) {
            q.close(); // the 720p30 server stops
        }
        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputProfile.P1080_30, null);
        assertEquals(2, jobs.enqueueReencodes(OutputProfile.P1080_30));
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
            Thread.sleep(50);
        }
        IngestJob audioJob = jobs.latestForItem(audioItem).orElseThrow();
        IngestJob videoJob = jobs.latestForItem(videoItem).orElseThrow();
        assertEquals(State.DONE, audioJob.state(), audioJob.log());
        assertEquals(State.DONE, videoJob.state(), videoJob.log());

        // audio upload: from its original
        assertTrue(audioJob.log().contains("re-encoding for 1080p30 (was 720p30) from the original original.flac"),
                audioJob.log());
        assertTrue(audioJob.log().contains("step: loudnorm pass 1"), audioJob.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.flac"), files(audioDir));
        assertArrayEquals(original, Files.readAllBytes(audioDir.resolve("original.flac")));
        assertArrayEquals(audioBefore, originalRow(audioItem), "same codec, web flag and gain");
        assertEquals("1080p30", itemRow(audioItem)[3]);
        assertEquals("ready", itemRow(audioItem)[0]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(audioDir.resolve("card.mp4")));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(audioDir.resolve("card.mp4"))));
        SampleIndex audioIndex = SampleIndex.read(audioDir.resolve("index.bin"));
        assertEquals(OutputProfile.P1080_30, audioIndex.profile());
        assertArrayEquals(hd.avcC(), audioIndex.avcC());

        // video upload: from its old mezzanine, without loudnorm, loudness kept
        assertTrue(videoJob.log().contains("from the previous encode"), videoJob.log());
        assertFalse(videoJob.log().contains("step: loudnorm pass 1"), videoJob.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4"), files(videoDir));
        Object[] videoAfter = itemRow(videoItem);
        assertEquals("ready", videoAfter[0]);
        assertEquals("video", videoAfter[1]);
        assertEquals("1080p30", videoAfter[3]);
        assertEquals(videoBefore[4], videoAfter[4], "the loudness measured at the first ingest is kept");
        assertTrue(Math.abs((Long) videoAfter[2] - (Long) videoBefore[2]) <= 50, "duration " + videoAfter[2]);
        assertNoOriginal(videoItem);
        Path mezz = videoDir.resolve("mezz.mp4");
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(mezz));
        assertEquals(List.of("1920", "1080"), List.of(videoSize(mezz)));
        SampleIndex videoIndex = SampleIndex.read(videoDir.resolve("index.bin"));
        assertEquals(OutputProfile.P1080_30, videoIndex.profile());
        IngestVerifier.checkKeyframes(videoIndex, "mezz.mp4");
        // already normalised: the level stays at about -14 LUFS
        Loudness.Measurement out = Loudness.measure(tools, videoDir.resolve("audio.m4a").toString(), "0:a:0", null, null);
        assertTrue(Math.abs(out.inputI() + 14) < 1, "output loudness " + out.inputI());
        assertNoLeftovers();

        // nothing left to do for this profile; a stale job for the current profile is a no-op
        assertEquals(0, jobs.enqueueReencodes(OutputProfile.P1080_30));
        IngestJob noop = run(q, Kind.REENCODE, videoItem);
        assertEquals(State.DONE, noop.state());
        assertTrue(noop.log().contains("already encoded for 1080p30"), noop.log());
        assertTrue(Files.exists(mezz));
    }

    /**
     * A YouTube item re-encodes by downloading it again; if the download fails, it falls back to
     * its old mezzanine. An item that is no longer ready isn't touched.
     */
    @Test
    void reencodeYoutubeItemsByRefetching() throws Exception {
        String item = fixtures.item("pending");
        makeYoutube(item);
        Path fixture = dir.resolve("yt.mkv");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=426x240:r=30", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "libopus", fixture.toString());
        Tools ok = fakeYtdlp(fixture, "yt-ok");
        assertEquals(State.DONE, run(queue(ok, reference, 1800), Kind.YOUTUBE, item).state());
        Path itemDir = IngestPipeline.itemDir(data, item);

        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputProfile.P1080_30, null);
        for (IngestQueue q : queues) {
            q.close();
        }
        IngestJob job = run(queue(ok, hd, 1800), Kind.REENCODE, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertTrue(job.log().contains("from a new download"), job.log());
        assertTrue(job.log().contains("step: download"), job.log());
        assertTrue(job.log().contains("step: loudnorm pass 1"), job.log());
        assertEquals("1080p30", itemRow(item)[3]);
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("mezz.mp4")));
        assertNoOriginal(item);

        // back to 720p30 with the video gone from YouTube: from the old mezzanine instead
        for (IngestQueue q : queues) {
            q.close();
        }
        Tools broken = fakeYtdlp(null, "yt-broken");
        Double lufs = (Double) itemRow(item)[4];
        IngestJob fallback = run(queue(broken, reference, 1800), Kind.REENCODE, item);
        assertEquals(State.DONE, fallback.state(), fallback.log());
        assertTrue(fallback.log().contains("the download failed"), fallback.log());
        assertTrue(fallback.log().contains("re-encoding from the previous mezz.mp4 instead"), fallback.log());
        assertFalse(fallback.log().contains("step: loudnorm pass 1"), fallback.log());
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("720p30", row[3]);
        assertEquals(lufs, row[4]);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("mezz.mp4")));
        assertEquals(List.of("1280", "720"), List.of(videoSize(itemDir.resolve("mezz.mp4"))));
        assertNoLeftovers();

        // a purged item is left alone
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged', output_profile = '1080p30'"
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

    /** An audio item without its original (e.g. ingested before originals were kept) re-encodes from audio.m4a. */
    @Test
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
        ReferenceCard hd = ReferenceCard.ensure(tools, data.cards(), OutputProfile.P1080_30, null);
        IngestJob job = run(queue(tools, hd, 1), Kind.REENCODE, item);
        assertEquals(State.DONE, job.state(), "the duration limit doesn't apply to re-encodes\n" + job.log());
        assertTrue(job.log().contains("from the previous encode"), job.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin"), files(itemDir));
        Object[] row = itemRow(item);
        assertEquals("audio", row[1]);
        assertEquals("1080p30", row[3]);
        assertEquals(lufs, row[4]);
        assertTrue(Math.abs((Long) row[2] - 3000) <= 50, "duration " + row[2]);
        assertArrayEquals(new Object[] {null, null, 0, null}, originalRow(item));
        assertArrayEquals(hd.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")));
    }

    /**
     * The M2 "done when" set on one queue: an MP3, a FLAC with cover art, 24 fps and 60 fps
     * videos, a portrait video and a YouTube download (fake yt-dlp) all become ready, and every
     * card, mezzanine and index carries the reference card's avcC byte for byte.
     */
    @Test
    void milestoneSetIngestsWithOneAvcC() throws Exception {
        Path png = dir.resolve("cover.png");
        ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=400x400", "-frames:v", "1", png.toString());
        Path ytSource = dir.resolve("yt.webm");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libvpx-vp9", "-deadline", "realtime", "-c:a", "libopus", ytSource.toString());
        IngestQueue uploads = queue(tools, reference, 1800);
        IngestQueue youtube = queue(fakeYtdlp(ytSource, "fake-yt-dlp-set"), reference, 1800);

        // original: the kept file and its codec for audio uploads, null for videos (none kept)
        record Case(String name, String staged, String original, String codec, List<String> encode) {
            boolean video() {
                return original == null;
            }
        }
        List<Case> cases = List.of(
                new Case("mp3", "source.mp3", "original.mp3", "mp3/mp3", List.of("-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "3",
                        "-c:a", "libmp3lame", "-b:a", "192k")),
                new Case("flac+cover", "source.flac", "original.flac", "flac/flac", List.of("-f", "lavfi", "-i", "sine=f=330:r=48000",
                        "-i", png.toString(), "-t", "3", "-map", "0:a", "-map", "1:v", "-c:a", "flac", "-c:v", "png",
                        "-disposition:v", "attached_pic")),
                new Case("24fps", "source.mp4", null, null, List.of("-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=24",
                        "-f", "lavfi", "-i", "sine=f=220:r=48000", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")),
                new Case("60fps", "source.mp4", null, null, List.of("-f", "lavfi", "-i", "testsrc2=s=1280x720:r=60",
                        "-f", "lavfi", "-i", "sine=f=250:r=44100", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")),
                new Case("portrait", "source.mp4", null, null, List.of("-f", "lavfi", "-i", "testsrc2=s=720x1280:r=30",
                        "-f", "lavfi", "-i", "sine=f=280:r=48000", "-t", "3", "-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac")));
        for (Case c : cases) {
            String item = fixtures.item("pending");
            Path source = stage(item, c.staged());
            List<String> args = new ArrayList<>(c.encode());
            args.add(source.toString());
            ffmpeg(args.toArray(String[]::new));
            byte[] uploaded = c.video() ? null : Files.readAllBytes(source);
            Loudness.Measurement in = c.video() ? null : Loudness.measure(tools, source.toString(), "0:a:0", null, null);
            IngestJob job = run(uploads, Kind.UPLOAD, item);
            assertEquals(State.DONE, job.state(), c.name() + "\n" + job.log());
            assertSameAvcC(c.name(), item, c.video());
            if (c.video()) {
                assertNoOriginal(item);
            } else {
                // kept as uploaded, web-playable, with the gain that plays it like audio.m4a
                Object[] original = originalRow(item);
                assertEquals(c.original(), original[0], c.name());
                assertEquals(c.codec(), original[1], c.name());
                assertEquals(1, original[2], c.name() + " web-playable");
                assertNotNull(original[3], c.name() + " gain");
                Loudness.Measurement out = Loudness.measure(tools,
                        IngestPipeline.itemDir(data, item).resolve("audio.m4a").toString(), "0:a:0", null, null);
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
        assertNoOriginal(yt);
        assertNoLeftovers();
    }

    /**
     * The M2 "done when" retention check, end to end: with {@code retention.unreferencedDays}
     * shortened to 1, a ready upload and a ready YouTube item that nothing references are kept
     * until a day after their last reference and purged by the sweeper after it (files gone, row
     * and metadata kept), while a library item stays. Importing the purged YouTube link again
     * queues a {@code refetch} into the same row (no new item, no metadata request) that downloads
     * and ingests it back to ready with the reference avcC.
     */
    @Test
    void unreferencedItemsArePurgedAndYoutubeRefetchesIntoTheSameRow() throws Exception {
        Path ytSource = dir.resolve("yt-retention.webm");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=25", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "libvpx-vp9", "-deadline", "realtime", "-c:a", "libopus", ytSource.toString());
        Path calls = dir.resolve("yt-calls.txt");
        Path script = dir.resolve("fake-yt-dlp-retention");
        Files.writeString(script, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" >> '" + calls + "'\n"
                + "echo '----' >> '" + calls + "'\n"
                + "out=''\n"
                + "while [ $# -gt 0 ]; do if [ \"$1\" = -o ]; then out=\"$2\"; shift; fi; shift; done\n"
                + "[ -n \"$out\" ] || { echo 'unexpected call' >&2; exit 1; }\n"
                + "cp '" + ytSource + "' \"$(echo \"$out\" | sed 's/%(ext)s/webm/')\"\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        Tools ytTools = new Tools(CardEncoderTest.onPath("ffmpeg"), CardEncoderTest.onPath("ffprobe"), script.toString());
        IngestQueue q = queue(ytTools, reference, 1800);

        // a ready upload, a ready YouTube item and a ready upload in someone's library
        String upload = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=f=440:r=44100", "-t", "3", "-c:a", "libmp3lame",
                stage(upload, "source.mp3").toString());
        assertEquals(State.DONE, run(q, Kind.UPLOAD, upload).state());
        String yt = fixtures.item("pending");
        makeYoutube(yt);
        assertEquals(State.DONE, run(q, Kind.YOUTUBE, yt).state());
        String kept = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "3", stage(kept, "source.wav").toString());
        assertEquals(State.DONE, run(q, Kind.UPLOAD, kept).state());
        String user = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users (id, username, display_name, created_at,"
                    + " disabled) VALUES (?, 'listener', 'listener', 1, 0)")) {
                ps.setString(1, user);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 1, 'manual')")) {
                ps.setString(1, user);
                ps.setString(2, kept);
                ps.executeUpdate();
            }
            return null;
        });
        for (String id : List.of(upload, yt, kept)) {
            assertEquals("ready", itemRow(id)[0]);
        }
        Path ytDir = IngestPipeline.itemDir(data, yt);
        Path uploadDir = IngestPipeline.itemDir(data, upload);
        Object[] ytBefore = itemRow(yt);
        // the finished job was the last reference
        long firstRef = Math.min(fixtures.lastReferencedAt(upload), fixtures.lastReferencedAt(yt));
        long lastRef = Math.max(fixtures.lastReferencedAt(upload), fixtures.lastReferencedAt(yt));

        TestClock clock = new TestClock(firstRef + 24L * 60 * 60 * 1000);
        Items items = new Items(db, data, clock);
        try (RetentionSweeper sweeper = new RetentionSweeper(db, items, () -> 1, clock)) {
            assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep(), "not yet a day");
            clock.set(lastRef + 24L * 60 * 60 * 1000 + 1);
            assertEquals(new RetentionSweeper.Result(2, 0, 0), sweeper.sweep());
        }
        for (String id : List.of(upload, yt)) {
            assertEquals("purged", itemRow(id)[0]);
            assertFalse(Files.exists(IngestPipeline.itemDir(data, id)), "files of " + id + " deleted");
        }
        assertEquals("Song", db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT title FROM items WHERE id = ?")) {
                ps.setString(1, yt);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }), "row and metadata kept");
        assertEquals("ready", itemRow(kept)[0]);
        assertTrue(Files.exists(IngestPipeline.itemDir(data, kept).resolve("audio.m4a")));
        assertFalse(Files.exists(uploadDir));
        long ytCallsBefore = Files.readAllLines(calls).stream().filter("----"::equals).count();

        // the YouTube link again: the same row, refetched
        YoutubeImports imports = new YoutubeImports(db, ytTools, jobs, q::wake, OutputProfile.P720_30, 1800,
                () -> null, clock);
        YoutubeImports.Imported again = imports.importUrl(new Actor.User(user, "s", Actor.Via.BEARER),
                "https://youtu.be/dQw4w9WgXcQ?t=7", null, null);
        assertEquals(new YoutubeImports.Imported(yt, false, "pending"), again);
        IngestJob refetch = jobs.latestForItem(yt).orElseThrow();
        assertEquals(Kind.REFETCH, refetch.kind());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
        while (!jobs.get(refetch.id()).orElseThrow().state().finished()) {
            assertTrue(System.nanoTime() < deadline, "refetch did not finish");
            Thread.sleep(50);
        }
        refetch = jobs.get(refetch.id()).orElseThrow();
        assertEquals(State.DONE, refetch.state(), refetch.log());
        assertTrue(refetch.log().contains("step: download"), refetch.log());
        assertEquals(Integer.valueOf(1), db.<Integer>read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM items WHERE youtube_id = ?")) {
                ps.setString(1, "dQw4w9WgXcQ");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }), "one row per video ID");
        List<String> after = Files.readAllLines(calls);
        assertEquals(ytCallsBefore + 1, after.stream().filter("----"::equals).count(), "only the download ran");
        assertFalse(after.contains("-J"), "no metadata request");
        assertTrue(after.contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "canonical URL: " + after);
        Object[] ytAfter = itemRow(yt);
        assertEquals("ready", ytAfter[0]);
        assertEquals(ytBefore[1], ytAfter[1]);
        assertEquals(ytBefore[3], ytAfter[3]);
        assertSameAvcC("refetched youtube", yt, true);
        assertNoOriginal(yt);
        assertNoLeftovers();
        // the purged upload has nothing to refetch from; it stays purged
        assertEquals("purged", itemRow(upload)[0]);
        assertTrue(fixtures.lastReferencedAt(yt) >= clock.millis(), "the import counts as a reference");
    }

    private void assertSameAvcC(String name, String item, boolean video) throws Exception {
        assertEquals("ready", itemRow(item)[0], name);
        assertEquals(video ? "video" : "audio", itemRow(item)[1], name);
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("card.mp4")), name + " card");
        assertArrayEquals(reference.avcC(), SampleIndex.read(itemDir.resolve("index.bin")).avcC(), name + " index");
        assertEquals(video, Files.exists(itemDir.resolve("mezz.mp4")), name);
        if (video) {
            assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(itemDir.resolve("mezz.mp4")), name + " mezz");
        }
    }
}
