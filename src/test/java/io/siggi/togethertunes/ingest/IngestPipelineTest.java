package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import io.siggi.togethertunes.ingest.IngestJob.State;
import io.siggi.togethertunes.library.Items;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.retention.RetentionSweeper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The ingest job steps end to end with real ffmpeg/ffprobe (skipped without them), on a SQLite
 * database and a real {@link IngestQueue}: audio and video uploads, a YouTube job with a fake
 * yt-dlp, re-ingest into an existing directory, and the failures that must leave nothing behind.
 * The inherently slow cases (1080p re-encodes, the quality-preset swap, loudness matching of
 * originals, the milestone source set) are in {@link IngestPipelineSlowTest}. Test methods run
 * concurrently (each has its own data directory and database).
 */
@Timeout(180)
// The class runs on its own (like every other class); its test methods run side by side.
@Execution(ExecutionMode.SAME_THREAD)
class IngestPipelineTest extends IngestPipelineTestBase {
    /** A FLAC upload with an embedded cover: an audio item with audio.m4a, card.mp4, cover.jpg and index.bin. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
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
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.flac"), layout(item));
        Object[] original = originalRow(item);
        assertEquals("original.flac", original[0]);
        assertEquals("flac/flac", original[1]);
        assertEquals(1, original[2], "a FLAC within 50 ms of audio.m4a is web-playable");
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("audio", row[1]);
        assertTrue(Math.abs((Long) row[2] - 4000) <= 50, "duration " + row[2]);
        assertEquals("720p30-3000k-v3", row[3]);
        assertNotNull(row[4]);
        assertTrue((Double) row[4] < -14 && (Double) row[4] > -30, "loudness " + row[4]);
        assertNull(row[5]);

        SampleIndex index = SampleIndex.read(cur(item).resolve("index.bin"));
        assertEquals(List.of("card.mp4", "audio.m4a"), index.files());
        assertEquals(60, index.track(SampleIndex.Kind.VIDEO).sampleCount());
        assertArrayEquals(reference.avcC(), index.avcC());
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("card.mp4")));
        assertNotNull(CardRenderer.readImage(cur(item).resolve("cover.jpg")));
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)), "staging dir is deleted after publish");
        assertArrayEquals(uploaded, Files.readAllBytes(itemDir.resolve("original.flac")), "the original is kept as uploaded");
        Loudness.Measurement out = Loudness.measure(tools, cur(item).resolve("audio.m4a").toString(), "0:a:0", null, null);
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
     * A 24 fps video upload whose audio starts 0.3 s late, re-ingested over an item with files in
     * the flat layout (version 0, from before versions): a new version v1 with mezz.mp4 + audio.m4a
     * + card.mp4 + index.bin, avcC identical to the reference, IDRs every 2 s, audio padded to start
     * at 0. The flat files stay until the old version's clean-up.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void videoUploadReplacesTheOldDirectory() throws Exception {
        String item = fixtures.item("ready");
        Path old = IngestPipeline.itemDir(data, item);
        Files.createDirectories(old);
        Files.writeString(old.resolve("audio.m4a"), "old files");
        Path video = dir.resolve("v.mkv");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=24", "-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "5",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", video.toString());
        Path source = stage(item, "source.mkv");
        ffmpeg("-i", video.toString(), "-itsoffset", "0.3", "-i", video.toString(), "-map", "0:v", "-map", "1:a",
                "-c", "copy", source.toString());
        byte[] uploaded = Files.readAllBytes(source);

        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4", "original.mkv"), layout(item));
        assertEquals(old.resolve("v1"), cur(item));
        assertEquals("old files", Files.readString(old.resolve("audio.m4a")), "version 0 stays until its clean-up");
        assertKeptOnly(item, "original.mkv", "aac/matroska,webm", uploaded);
        assertTrue(job.log().contains("kept the original as original.mkv (aac/matroska,webm, "), job.log());
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)), "the staging dir is deleted after ingest");
        Object[] row = itemRow(item);
        assertEquals("ready", row[0]);
        assertEquals("video", row[1]);
        assertTrue(Math.abs((Long) row[2] - 5300) <= 50, "duration " + row[2]);
        SampleIndex index = SampleIndex.read(cur(item).resolve("index.bin"));
        assertEquals(List.of("mezz.mp4", "audio.m4a"), index.files());
        assertArrayEquals(reference.avcC(), index.avcC());
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("card.mp4")));
        SampleIndex.Track v = index.track(SampleIndex.Kind.VIDEO);
        assertTrue(v.sampleCount() >= 150, "frames " + v.sampleCount());
        IngestVerifier.checkKeyframes(index, "mezz.mp4");
        assertEquals(0, index.track(SampleIndex.Kind.AUDIO).pts(0));
        assertTrue(job.log().contains("step: extract audio.m4a"));
        // audio.m4a is the mezzanine's audio, copied
        ToolResult probe = tools.ffprobe("-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0",
                cur(item).resolve("audio.m4a").toString()).stdoutToString(4096).run();
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
    @Execution(ExecutionMode.CONCURRENT)
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
            Path mezz = cur(item).resolve("mezz.mp4");

            assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(mezz), c.toString());

            String audioEnd = tools.ffprobe("-v", "error", "-select_streams", "a:0", "-show_entries",
                    "stream=duration", "-of", "csv=p=0", cur(item).resolve("audio.m4a").toString())
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
    @Execution(ExecutionMode.CONCURRENT)
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
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "mezz.mp4", "original.mkv"),
                layout(item));
        java.awt.image.BufferedImage cover = CardRenderer.readImage(cur(item).resolve("cover.jpg"));
        assertEquals(320, cover.getWidth());
        int rgb = cover.getRGB(160, 90);
        assertTrue(((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) < 60, "the thumbnail is the cover: " + Integer.toHexString(rgb));
        assertEquals("ready", itemRow(item)[0]);
        // the merged download is kept as the original, so a re-encode doesn't need YouTube
        assertKeptOnly(item, "original.mkv", "opus/matroska,webm", Files.readAllBytes(fixture));
        assertNoLeftovers();

        // a cookies setting that points nowhere is reported and ignored
        IngestJob again = run(queue(withYtdlp, reference, 1800, () -> dir.resolve("gone.txt").toString()),
                Kind.REFETCH, item);
        assertEquals(State.DONE, again.state(), again.log());
        assertFalse(Files.readAllLines(argsFile).contains("--cookies"));
        assertTrue(again.log().contains("gone.txt (ytdlp.cookiesFile) can't be read"), again.log());
        assertKeptOnly(item, "original.mkv", "opus/matroska,webm", Files.readAllBytes(fixture));
        assertNoLeftovers();
    }

    /** Digital silence can't be measured; it is encoded without normalisation and no loudness is stored. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void silenceIsEncodedWithoutLoudness() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "3", stage(item, "source.wav").toString());
        IngestJob job = run(Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        Object[] row = itemRow(item);
        assertEquals("audio", row[1]);
        assertNull(row[4]);
        assertTrue(job.log().contains("encoding without normalisation"));
        assertFalse(Files.exists(cur(item).resolve("cover.jpg")));
        // the original is kept, but without a gain (nothing to measure)
        assertArrayEquals(new Object[] {"original.wav", "pcm_s16le/wav", 1, null}, originalRow(item));
    }

    /**
     * Originals of other audio formats: MP3 (also with no extension on the upload), Opus in Ogg
     * and in WebM, AAC in M4A and ADTS are web-playable; Vorbis, AIFF, ALAC and Opus in a .mkv are
     * kept but not web-playable.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
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
        // the cases are independent: stage and enqueue them all, then check each (several workers)
        IngestQueue q = queue(tools, reference, 1800, () -> null, 4);
        List<String> items = new ArrayList<>();
        List<String> jobIds = new ArrayList<>();
        List<byte[]> uploads = new ArrayList<>();
        for (Case c : cases) {
            String item = fixtures.item("pending");
            Path source = stage(item, c.staged());
            List<String> args = new ArrayList<>(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000", "-t", "3"));
            args.addAll(List.of(c.encode()));
            args.add(source.toString());
            ffmpeg(args.toArray(String[]::new));
            uploads.add(Files.readAllBytes(source));
            items.add(item);
            jobIds.add(q.enqueue(item, Kind.UPLOAD).id());
        }
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            String item = items.get(i);
            byte[] uploaded = uploads.get(i);
            IngestJob job = awaitJob(jobIds.get(i));
            assertEquals(State.DONE, job.state(), c + "\n" + job.log());
            Object[] original = originalRow(item);
            assertEquals(c.file(), original[0], c.staged());
            assertEquals(c.codec(), original[1], c.staged());
            assertEquals(c.web(), original[2], c.staged() + "\n" + job.log());
            assertNotNull(original[3], c.staged());
            assertTrue((Double) original[3] > 0 && (Double) original[3] < 20, c.staged() + " gain " + original[3]);
            Path itemDir = IngestPipeline.itemDir(data, item);
            assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", c.file()), layout(item));
            assertArrayEquals(uploaded, Files.readAllBytes(itemDir.resolve(c.file())), c.staged());
            assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)));
        }
        assertNoLeftovers();
    }

    /**
     * Re-ingesting an item as a video upload replaces the original an earlier audio ingest kept
     * with the video's, which isn't web-playable.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void videoReingestReplacesTheOriginal() throws Exception {
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
        assertKeptOnly(item, "original.mp4", "aac/mov,mp4,m4a,3gp,3g2,mj2", null);
        assertFalse(Files.exists(IngestPipeline.itemDir(data, item).resolve("original.wav")));
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, item)));
    }

    /**
     * The item directory follows the kind, also when one item is re-ingested as the other kind:
     * an audio item is {@code audio.m4a} + {@code card.mp4} (+ index, cover, original) and never a
     * {@code mezz.mp4}; a video item is {@code mezz.mp4} + {@code audio.m4a} + {@code card.mp4}.
     * Cover art (an attached JPEG in MP3 and M4A) doesn't make a video item; Motion JPEG video
     * (a camera AVI) does.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
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
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.mp3"), layout(item));
        assertAudioLayout(cur(item));

        // the same item re-ingested from a Motion JPEG AVI: video item, the audio-item files are gone
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=320x240:r=25", "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "3",
                "-c:v", "mjpeg", "-c:a", "pcm_s16le", stage(item, "source.avi").toString());
        job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("video", itemRow(item)[1]);
        assertEquals(List.of("audio.m4a", "card.mp4", "index.bin", "mezz.mp4", "original.avi"), layout(item));
        assertKeptOnly(item, "original.avi", "pcm_s16le/avi", null);
        SampleIndex index = SampleIndex.read(cur(item).resolve("index.bin"));
        assertEquals(List.of("mezz.mp4", "audio.m4a"), index.files());
        assertEquals(List.of("video", "audio"), streamTypes(cur(item).resolve("mezz.mp4")));
        assertEquals(List.of("audio"), streamTypes(cur(item).resolve("audio.m4a")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("mezz.mp4")));
        IngestVerifier.checkKeyframes(index, "mezz.mp4");
        // the card is there for presentAsAudio and the transition card
        assertEquals(List.of("video"), streamTypes(cur(item).resolve("card.mp4")));
        assertArrayEquals(reference.avcC(), Mp4Boxes.readAvcC(cur(item).resolve("card.mp4")));
        assertEquals(60, videoFrames(cur(item).resolve("card.mp4")));

        // and back to audio from an M4A with an attached JPEG cover: no mezz.mp4 is left behind
        ffmpeg("-f", "lavfi", "-i", "sine=f=550:r=48000", "-i", jpg.toString(), "-t", "3", "-map", "0:a", "-map", "1:v",
                "-c:a", "aac", "-c:v", "copy", "-disposition:v", "attached_pic", stage(item, "source.m4a").toString());
        job = run(q, Kind.UPLOAD, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertEquals("audio", itemRow(item)[1]);
        assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin", "original.m4a"), layout(item));
        assertAudioLayout(cur(item));
        assertNoLeftovers();
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
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
    @Execution(ExecutionMode.CONCURRENT)
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
    @Execution(ExecutionMode.CONCURRENT)
    void avcCMismatchPublishesNothing() throws Exception {
        Path otherCards = dir.resolve("other-cards");
        Path profileDir = ReferenceCard.encodingDir(otherCards, OutputEncoding.defaultFor(OutputProfile.P720_30));
        Files.createDirectories(profileDir);
        byte[] tampered = reference.avcC();
        tampered[tampered.length - 1] ^= 1;
        Files.write(profileDir.resolve(ReferenceCard.AVCC_FILE), tampered);
        ReferenceCard changed = ReferenceCard.ensure(tools, otherCards, OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        assertTrue(changed.encoderChanged());

        String item = fixtures.item("ready");
        Path itemDir = IngestPipeline.itemDir(data, item);
        Files.createDirectories(itemDir);
        Files.writeString(cur(item).resolve("audio.m4a"), "current");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "2", stage(item, "source.wav").toString());
        IngestJob job = run(queue(tools, changed, 1800), Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertTrue(fixtures.itemStatus(item)[1].contains("avcC differs"), fixtures.itemStatus(item)[1]);
        assertEquals(List.of("audio.m4a"), layout(item));
        assertEquals("current", Files.readString(cur(item).resolve("audio.m4a")));
        assertNoLeftovers();
    }

    /**
     * When the startup self-check found that this ffmpeg's video encodes don't match the reference
     * (ReferenceCard#spliceProblem), video jobs fail up front with a clear error and keep the
     * upload; audio items (cards only, which match by construction) still work.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void unspliceableVideoEncodesAreRefusedUpFront() throws Exception {
        ReferenceCard bad = ReferenceCard.forTest(reference.encoding(), reference.card(), reference.avcC(),
                "a BT.709-tagged video is encoded with a different SPS than the 720p30-3000k-v3 reference card:"
                        + " colour_primaries 1 (BT.709) instead of 2 (unspecified)");
        IngestQueue q = queue(tools, bad, 1800);
        String video = fixtures.item("pending");
        stageVideo(video);
        assertEquals(State.FAILED, run(q, Kind.UPLOAD, video).state());
        assertEquals("This ffmpeg produces encodes that can't be spliced: a BT.709-tagged video is encoded with a"
                + " different SPS than the 720p30-3000k-v3 reference card: colour_primaries 1 (BT.709) instead of 2"
                + " (unspecified)", fixtures.itemStatus(video)[1]);
        assertTrue(Files.exists(stage(video, "source.mp4")), "the upload stays for a retry");

        String audio = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "1", stage(audio, "source.wav").toString());
        assertEquals(State.DONE, run(q, Kind.UPLOAD, audio).state());
        assertNoLeftovers();
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void withoutAReferenceCardJobsFail() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=r=48000", "-t", "1", stage(item, "source.wav").toString());
        IngestJob job = run(queue(tools, null, 1800), Kind.UPLOAD, item);
        assertEquals(State.FAILED, job.state());
        assertTrue(fixtures.itemStatus(item)[1].startsWith("Ingest is unavailable"));
    }

    /**
     * A bitrate change alone (same profile) re-encodes ready items: the new mezzanine and card are
     * capped at the new rate, with the new reference's avcC (the same as the old one: the bitrate
     * is not in the SPS), and the index records the new encoding.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void reencodeForANewBitrate() throws Exception {
        String item = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=f=220:r=44100", "-t", "4",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", stage(item, "source.mp4").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, item).state());
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertEquals("720p30-3000k-v3", itemRow(item)[3]);
        for (IngestQueue q : queues) {
            q.close();
        }
        OutputEncoding lower = new OutputEncoding(OutputProfile.P720_30, 2000);
        ReferenceCard ref = ReferenceCard.ensure(tools, data.cards(), lower, null);
        assertArrayEquals(reference.avcC(), ref.avcC(), "the bitrate is not part of the avcC");
        assertEquals(1, jobs.enqueueReencodes(lower));
        IngestQueue q = queue(tools, ref, 1800);
        IngestJob job = run(q, Kind.REENCODE, item);
        assertEquals(State.DONE, job.state(), job.log());
        assertTrue(job.log().contains("re-encoding for 720p30-2000k-v3 (was 720p30-3000k-v3, preset veryfast) from the original original.mp4"),
                job.log());
        assertEquals("720p30-2000k-v3", itemRow(item)[3]);
        Path mezz = cur(item).resolve("mezz.mp4");
        assertArrayEquals(ref.avcC(), Mp4Boxes.readAvcC(mezz));
        SampleIndex index = SampleIndex.read(cur(item).resolve("index.bin"));
        assertEquals(lower, index.encoding());
        // capped: no full 2 s GOP is above 2000 kb/s × 2 s = 500 000 bytes by more than the VBV
        // buffer (half a second) allows
        SampleIndex.Track v = index.track(SampleIndex.Kind.VIDEO);
        long gop = 0;
        int gops = 0;
        for (int i = 0; i < v.sampleCount(); i++) {
            gop += v.size(i);
            if ((i + 1) % EncodeProfile.GOP_FRAMES == 0) {
                assertTrue(gop <= 500_000 * 1.25, "GOP " + gops + ": " + gop);
                gop = 0;
                gops++;
            }
        }
        assertEquals(2, gops);
        assertEquals(0, jobs.enqueueReencodes(lower));
        assertNoLeftovers();
    }

    /**
     * Items whose card is in an older card format (before skip-frame cards) get a card-only
     * re-encode: a new version with a new skip-frame card and the indexes that use it, every other
     * file taken over unchanged (hard links), and the new card format recorded.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void cardOnlyReencodeForAnOldCardFormat() throws Exception {
        String audio = fixtures.item("pending");
        ffmpeg("-f", "lavfi", "-i", "sine=f=330:r=44100", "-t", "3", "-c:a", "aac",
                stage(audio, "source.m4a").toString());
        assertEquals(State.DONE, run(Kind.UPLOAD, audio).state());
        String video = fixtures.item("pending");
        stageVideo(video);
        assertEquals(State.DONE, run(Kind.UPLOAD, video).state());
        for (IngestQueue q : queues) {
            q.close();
        }
        IngestItems items = new IngestItems(db);
        assertEquals(CardEncoder.CARD_VERSION, items.cardVersion(audio), "a new item has a current card");
        // as items made before skip-frame cards
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET card_version = 1 WHERE id IN (?, ?)")) {
                ps.setString(1, audio);
                ps.setString(2, video);
                ps.executeUpdate();
            }
            return null;
        });
        Path oldAudio = cur(audio);
        Path oldVideo = cur(video);
        assertEquals(2, jobs.enqueueCardRemakes(reference.encoding()));
        IngestQueue q = queue(tools, reference, 1800);
        for (String item : List.of(audio, video)) {
            IngestJob job = run(q, Kind.REENCODE, item);
            assertEquals(State.DONE, job.state(), job.log());
            assertTrue(job.log().contains("[ingest] card only"), job.log());
            assertEquals(CardEncoder.CARD_VERSION, items.cardVersion(item));
            assertEquals("720p30-3000k-v3", itemRow(item)[3]);
            CardEncoderTest.checkSkipFrameCard(tools, cur(item).resolve("card.mp4"), reference.encoding());
            assertSameAvcC("card only", item, item.equals(video));
        }
        Path newAudio = cur(audio);
        Path newVideo = cur(video);
        assertFalse(newAudio.equals(oldAudio));
        assertFalse(newVideo.equals(oldVideo));
        // the audio and video files are the same files; the audio item's index is new and uses the new card
        assertTrue(Files.isSameFile(oldAudio.resolve("audio.m4a"), newAudio.resolve("audio.m4a")));
        assertFalse(Files.isSameFile(oldAudio.resolve("index.bin"), newAudio.resolve("index.bin")));
        SampleIndex index = SampleIndex.read(newAudio.resolve("index.bin"));
        assertEquals(SampleIndex.read(oldAudio.resolve("index.bin")).track(SampleIndex.Kind.AUDIO).endPts(),
                index.track(SampleIndex.Kind.AUDIO).endPts());
        SampleIndex.Track card = index.track(SampleIndex.Kind.VIDEO);
        assertEquals(EncodeProfile.GOP_FRAMES, card.sampleCount());
        assertTrue(card.size(1) < SkipFrameCardTest.MAX_SKIP_FRAME_BYTES, "the index points at the skip-frame card");
        for (String f : List.of("mezz.mp4", "audio.m4a", "index.bin")) {
            assertTrue(Files.isSameFile(oldVideo.resolve(f), newVideo.resolve(f)), f);
        }
        assertEquals(0, jobs.enqueueCardRemakes(reference.encoding()));
        // a re-encode job of an item with a current card does nothing
        IngestJob again = run(q, Kind.REENCODE, audio);
        assertTrue(again.log().contains("already encoded for"), again.log());
        assertEquals(newAudio, cur(audio));
        assertNoLeftovers();
    }

    /** With equal quick and quality presets, a quick encode is the quality encode: no second job. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void equalPresetsNeedNoQualityJob() throws Exception {
        presetQueue(EncodePresets.fixed(X264Preset.FASTER, X264Preset.FASTER));
        String item = fixtures.item("pending");
        stageVideo(item);
        fixtures.queue(item, "playing");
        fixtures.addToLibrary(item);
        IngestJob job = jobs.enqueue(item, Kind.UPLOAD, IngestJob.Tier.QUICK);
        assertEquals(State.DONE, awaitJob(job.id()).state());
        assertTrue(jobs.get(job.id()).orElseThrow().log().contains("x264 preset faster (quick, the same as the quality preset)"));
        assertArrayEquals(new Object[] {"faster", true}, fixtures.encode(item));
        assertEquals(job.id(), jobs.latestForItem(item).orElseThrow().id());
        assertFalse(jobs.enqueueQualityIfNeeded(item));

        // a quality job queued anyway (e.g. before the admin made the presets equal) does nothing
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET encode_quality = 0 WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        byte[] mezz = Files.readAllBytes(cur(item).resolve("mezz.mp4"));
        IngestJob noop = jobs.enqueue(item, Kind.QUALITY, IngestJob.Tier.QUALITY);
        IngestJob done = awaitJob(noop.id());
        assertEquals(State.DONE, done.state());
        assertTrue(done.log().contains("already encoded with the quality preset faster"), done.log());
        assertArrayEquals(new Object[] {"faster", true}, fixtures.encode(item));
        assertArrayEquals(mezz, Files.readAllBytes(cur(item).resolve("mezz.mp4")));
    }

    /**
     * A re-encode for a new encoding publishes at once even while its item is playing: the program
     * output can't splice the old encoding's files (it shows "Loading…" for them), so waiting for
     * the play to end would deadlock a room that has the item current or next.
     */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aReencodeOfAnOldEncodingDoesNotWaitForThePlay() throws Exception {
        IngestPipeline pipeline = presetQueue(EncodePresets.fixed(X264Preset.VERYFAST, X264Preset.VERYFAST));
        String item = fixtures.item("pending");
        stageVideo(item);
        IngestJob first = jobs.enqueue(item, Kind.UPLOAD, IngestJob.Tier.QUICK);
        assertEquals(State.DONE, awaitJob(first.id()).state());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET output_profile = '720p30' WHERE id = ?")) {
                ps.setString(1, item);
                ps.executeUpdate();
            }
            return null;
        });
        MediaVersions versions = new MediaVersions(data);
        versions.inUse(id -> true); // playing in a room, forever
        pipeline.mediaVersions(versions);

        IngestJob done = awaitJob(jobs.enqueue(item, Kind.REENCODE).id());
        assertEquals(State.DONE, done.state(), done.log());
        assertFalse(done.log().contains("publishing once it has finished"), done.log());
        assertEquals(reference.encoding().id(), itemProfile(item), "published for the current encoding");
        // the previous version is kept for undo, but a system re-encode offers no Undo
        assertEquals(List.of("1 0"), db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT version, undoable FROM media_undo WHERE item_id = ?")) {
                ps.setString(1, item);
                List<String> rows = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getInt(1) + " " + rs.getInt(2));
                    }
                }
                return rows;
            }
        }));
        assertNoLeftovers();
    }

    /** A quality job for an item without an original (and not from YouTube) leaves it as it is. */
    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void qualityWithoutAnOriginalKeepsTheQuickEncode() throws Exception {
        presetQueue(EncodePresets.fixed(X264Preset.VERYFAST, X264Preset.SLOW));
        String item = fixtures.item("pending");
        stageVideo(item);
        IngestJob quick = jobs.enqueue(item, Kind.UPLOAD, IngestJob.Tier.QUICK);
        assertEquals(State.DONE, awaitJob(quick.id()).state());
        Path itemDir = IngestPipeline.itemDir(data, item);
        Files.delete(itemDir.resolve("original.mp4"));
        byte[] mezz = Files.readAllBytes(cur(item).resolve("mezz.mp4"));
        fixtures.addToLibrary(item);
        assertTrue(jobs.enqueueQualityIfNeeded(item));
        IngestJob done = awaitLatest(item);
        assertEquals(State.DONE, done.state());
        assertTrue(done.log().contains("would only lose quality"), done.log());
        assertArrayEquals(mezz, Files.readAllBytes(cur(item).resolve("mezz.mp4")));
        assertArrayEquals(new Object[] {"veryfast", true}, fixtures.encode(item), "marked so it isn't queued again");
        assertFalse(jobs.enqueueQualityIfNeeded(item));
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
    @Execution(ExecutionMode.CONCURRENT)
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
        assertKeptOnly(yt, "original.webm", "opus/matroska,webm", Files.readAllBytes(ytSource));
        assertTrue(Files.exists(uploadDir.resolve("original.mp3")));
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
        assertTrue(Files.exists(cur(kept).resolve("audio.m4a")));
        assertFalse(Files.exists(uploadDir));
        long ytCallsBefore = Files.readAllLines(calls).stream().filter("----"::equals).count();

        // the YouTube link again: the same row, refetched
        YoutubeImports imports = new YoutubeImports(db, ytTools, jobs, q::wake, OutputEncoding.defaultFor(OutputProfile.P720_30), 1800,
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
        assertKeptOnly(yt, "original.webm", "opus/matroska,webm", Files.readAllBytes(ytSource));
        assertNoLeftovers();
        // the purged upload has nothing to refetch from; it stays purged
        assertEquals("purged", itemRow(upload)[0]);
        assertTrue(fixtures.lastReferencedAt(yt) >= clock.millis(), "the import counts as a reference");
    }
}
