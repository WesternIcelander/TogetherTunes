package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SampleIndex.Kind;
import io.siggi.togethertunes.media.SampleIndex.Track;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of the ingest pipeline that need no tools: probe and loudnorm output parsing, the
 * verify checks on synthetic indexes, commands, staging lookup, publish swap and crash cleanup.
 */
class IngestStepsTest {
    @TempDir
    Path dir;

    // ------------------------------------------------------------------ probe

    @Test
    void probeFindsAudioVideoAndCover() throws Exception {
        MediaProbe p = MediaProbe.parse("""
                {"streams": [
                  {"index": 0, "codec_type": "video", "codec_name": "h264", "start_time": "0.040000", "duration": "N/A",
                   "disposition": {"attached_pic": 0}},
                  {"index": 1, "codec_type": "audio", "codec_name": "opus", "start_time": "0.007000", "duration": "12.500000"},
                  {"index": 2, "codec_type": "audio", "codec_name": "aac"},
                  {"index": 3, "codec_type": "video", "codec_name": "mjpeg", "disposition": {"attached_pic": 1}},
                  {"index": 4, "codec_type": "subtitle", "codec_name": "ass"}
                ],
                "format": {"format_name": "matroska,webm", "start_time": "0.007000", "duration": "12.600000"}}
                """);
        assertTrue(p.isVideo());
        assertEquals(0, p.video().index());
        assertEquals("0:1", p.audio().map());
        assertEquals("opus", p.audio().codec());
        assertEquals(3, p.cover().index());
        assertEquals(12_600_000, p.durationUs());
        assertEquals(7_000, p.startUs());
        assertEquals(12_500_000, p.audioEndUs());
        assertEquals("matroska,webm", p.formatName());
    }

    @Test
    void attachedPicturesAndStillImagesAreCoversNotVideo() throws Exception {
        MediaProbe p = MediaProbe.parse("""
                {"streams": [
                  {"index": 0, "codec_type": "audio", "codec_name": "mp3", "start_time": "0.025057", "duration": "3.000000"},
                  {"index": 1, "codec_type": "video", "codec_name": "png", "disposition": {"attached_pic": 0}},
                  {"index": 2, "codec_type": "video", "codec_name": "h264", "disposition": {"attached_pic": 1}}
                ],
                "format": {"format_name": "mp3", "start_time": "0.025057", "duration": "3.000000"}}
                """);
        assertFalse(p.isVideo());
        assertEquals(1, p.cover().index());
        assertEquals(3_000_000, p.audioEndUs());
    }

    @Test
    void imageCodecsAreVideoOnlyWithMovingPictures() throws Exception {
        record Case(String stream, boolean video) {
        }
        List<Case> cases = List.of(
                // Motion JPEG from a camera (AVI/MOV report a frame count)
                new Case("\"codec_name\": \"mjpeg\", \"nb_frames\": \"75\", \"duration\": \"3.000000\"", true),
                // one JPEG frame muxed as a plain stream into MP4/AVI
                new Case("\"codec_name\": \"mjpeg\", \"nb_frames\": \"1\", \"duration\": \"0.040000\"", false),
                // Matroska: no nb_frames, the statistics tags decide
                new Case("\"codec_name\": \"mjpeg\", \"tags\": {\"DURATION\": \"00:00:03.000000000\"}", true),
                new Case("\"codec_name\": \"mjpeg\", \"tags\": {\"DURATION\": \"00:00:00.040000000\"}", false),
                new Case("\"codec_name\": \"png\", \"tags\": {\"NUMBER_OF_FRAMES-eng\": \"90\", \"DURATION-eng\": \"00:00:03.000000000\"}", true),
                new Case("\"codec_name\": \"png\", \"tags\": {\"NUMBER_OF_FRAMES-eng\": \"1\", \"DURATION-eng\": \"00:00:03.000000000\"}", false),
                // nothing known: a still
                new Case("\"codec_name\": \"gif\"", false),
                new Case("\"codec_name\": \"mjpeg\", \"duration\": \"N/A\", \"tags\": {\"DURATION\": \"garbage\"}", false),
                // an attached picture is a cover whatever it says
                new Case("\"codec_name\": \"mjpeg\", \"nb_frames\": \"75\", \"disposition\": {\"attached_pic\": 1}", false),
                // a real video codec is video even with one frame
                new Case("\"codec_name\": \"h264\", \"nb_frames\": \"1\"", true));
        for (Case c : cases) {
            MediaProbe p = MediaProbe.parse("{\"streams\": [{\"index\": 0, \"codec_type\": \"audio\", \"codec_name\": \"aac\"},"
                    + " {\"index\": 1, \"codec_type\": \"video\", " + c.stream() + "}], \"format\": {}}");
            assertEquals(c.video(), p.isVideo(), c.stream());
            assertEquals(c.video() ? null : 1, p.cover() == null ? null : p.cover().index(), c.stream());
        }
        assertEquals(3_000_000, MediaProbe.clockMicros("00:00:03.000000000"));
        assertEquals(3_723_500_000L, MediaProbe.clockMicros("01:02:03.5"));
        assertEquals(-1, MediaProbe.clockMicros("3.0"));
        assertEquals(-1, MediaProbe.clockMicros("00:61:00"));
    }

    @Test
    void lateAudioStartCountsTowardsItsEnd() throws Exception {
        MediaProbe p = MediaProbe.parse("""
                {"streams": [{"index": 0, "codec_type": "audio", "codec_name": "aac", "start_time": "1.500000", "duration": "2.000000"},
                  {"index": 1, "codec_type": "video", "codec_name": "h264", "start_time": "1.000000", "duration": "3.000000"}],
                 "format": {"start_time": "1.000000", "duration": "3.000000"}}
                """);
        assertEquals(2_500_000, p.audioEndUs());
    }

    @Test
    void probeWithoutDurationsOrAudio() throws Exception {
        MediaProbe p = MediaProbe.parse("""
                {"streams": [{"index": 0, "codec_type": "audio", "codec_name": "vorbis"}], "format": {}}
                """);
        assertEquals(-1, p.audioEndUs());
        assertEquals(-1, p.durationUs());
        assertEquals("", p.formatName());
        assertNull(MediaProbe.parse("{\"streams\": [], \"format\": {}}").audio());
        assertThrows(IngestFailure.class, () -> MediaProbe.parse("[]"));
        assertThrows(IngestFailure.class, () -> MediaProbe.parse("{oops"));
        assertEquals(1_234_568, MediaProbe.micros("1.2345675"));
        assertEquals(-1, MediaProbe.micros("-0.5"));
        assertEquals(-1, MediaProbe.micros("abc"));
    }

    @Test
    void audioEndFromTheLastPackets() {
        assertEquals(5_338_000, MediaProbe.packetEndUs("5.315000,0.023000"));
        assertEquals(5_315_000, MediaProbe.packetEndUs("5.315000,N/A"));
        assertEquals(-1, MediaProbe.packetEndUs("N/A,0.023000"));
        assertEquals(-1, MediaProbe.packetEndUs(""));
        assertEquals(List.of("-v", "error", "-select_streams", "1", "-show_entries", "packet=pts_time,duration_time",
                "-of", "csv=p=0", "-read_intervals", "12.500000%", "in.mkv"),
                MediaProbe.tailArgs(Path.of("in.mkv"), 1, 12_500_000));
        assertFalse(MediaProbe.tailArgs(Path.of("in.mkv"), 1, -1).contains("-read_intervals"));
    }

    // ------------------------------------------------------------------ loudness

    @Test
    void loudnormJsonIsCollectedAndParsed() throws Exception {
        Loudness.JsonCollector c = new Loudness.JsonCollector();
        for (String line : List.of("size=N/A time=00:00:01.00", "{ not this }", "[Parsed_loudnorm_0 @ 0x1234] ",
                "{", "\t\"input_i\" : \"-21.75\",", "\t\"input_tp\" : \"-18.06\",", "\t\"input_lra\" : \"1.20\",",
                "\t\"input_thresh\" : \"-31.75\",", "\t\"output_i\" : \"-13.95\",",
                "\t\"normalization_type\" : \"dynamic\",", "\t\"target_offset\" : \"-0.05\"", "}",
                "[out#0/null @ 0x1] video:0kB audio:1125kB")) {
            c.accept(line);
        }
        Loudness.Measurement m = Loudness.parse(c.json());
        assertEquals(-21.75, m.inputI());
        assertEquals(-18.06, m.inputTp());
        assertEquals(1.2, m.inputLra());
        assertEquals(-31.75, m.inputThresh());
        assertEquals(-0.05, m.offset());
        assertTrue(m.usable());
        assertEquals("loudnorm=I=-14:TP=-1:LRA=11:measured_I=-21.75:measured_TP=-18.06:measured_LRA=1.20"
                + ":measured_thresh=-31.75:offset=-0.05:linear=true:print_format=summary,aresample=48000:first_pts=0",
                m.filter());
    }

    @Test
    void silenceMeasuresInfinityAndSkipsNormalisation() throws Exception {
        Loudness.Measurement m = Loudness.parse("""
                {"input_i" : "-inf", "input_tp" : "-inf", "input_lra" : "0.00", "input_thresh" : "-70.00",
                 "target_offset" : "inf"}""");
        assertFalse(m.usable());
        assertEquals("aresample=48000:first_pts=0", m.filter());
        assertThrows(IngestFailure.class, () -> Loudness.parse("{\"input_i\": \"-20\"}"));
        assertNull(new Loudness.JsonCollector().json());
    }

    @Test
    void measureArguments() {
        assertEquals(List.of("-i", "in.flac", "-map", "0:1", "-vn", "-sn", "-dn",
                "-af", "loudnorm=I=-14:TP=-1:LRA=11:print_format=json,aresample=48000", "-f", "null", "-"),
                Loudness.measureArgs("in.flac", "0:1"));
    }

    // ------------------------------------------------------------------ verify

    private static final byte[] AVCC = {1, 100, 0, 40, -1, -31, 0, 1, 0x67};

    /** A synthetic index: {@code frames} video frames at 30 fps (timescale 15360), IDR every {@code gop}. */
    private static SampleIndex index(int frames, int gop, long audioEndUs, long videoStartTicks) {
        Track.Builder v = new Track.Builder(Kind.VIDEO, 15360, 0);
        for (int i = 0; i < frames; i++) {
            v.add(i * 1000L, 100, videoStartTicks + i * 512L, 512, i % gop == 0 ? SampleIndex.FLAG_KEYFRAME : 0);
        }
        Track.Builder a = new Track.Builder(Kind.AUDIO, 48000, 1);
        long samples = audioEndUs * 48 / 1000;
        long pts = 0;
        while (pts < samples) {
            long d = Math.min(1024, samples - pts);
            a.add(pts * 10, 50, pts, d, SampleIndex.FLAG_KEYFRAME);
            pts += d;
        }
        return new SampleIndex(OutputProfile.P720_30, List.of("mezz.mp4", "audio.m4a"), List.of(v.build(), a.build()),
                AVCC);
    }

    @Test
    void verifyAcceptsAGoodIndex() throws Exception {
        SampleIndex good = index(150, 60, 5_000_000, 0);
        assertEquals(5_000_000, IngestVerifier.checkAudioDuration(good, 5_000_000, -1));
        assertEquals(5_049_000, IngestVerifier.checkAudioDuration(good, 5_049_000, -1));
        assertEquals(4_951_000, IngestVerifier.checkAudioDuration(good, 4_951_000, 4_000_000));
        // the container's length wins when both match
        assertEquals(5_010_000, IngestVerifier.checkAudioDuration(good, 5_010_000, 4_990_000));
        IngestVerifier.checkKeyframes(good, "mezz.mp4");
        assertEquals(5_000_000, IngestVerifier.audioDurationUs(good));
    }

    @Test
    void verifyRejectsAudioDrift() {
        SampleIndex idx = index(150, 60, 5_000_000, 0);
        IngestFailure e = assertThrows(IngestFailure.class, () -> IngestVerifier.checkAudioDuration(idx, 5_051_000, -1));
        assertTrue(e.getMessage().contains("5.000 s") && e.getMessage().contains("5.051 s"), e.getMessage());
        assertThrows(IngestFailure.class, () -> IngestVerifier.checkAudioDuration(idx, 4_940_000, -1));
        assertThrows(IngestFailure.class, () -> IngestVerifier.checkAudioDuration(idx, -1, -1));
        IngestFailure both = assertThrows(IngestFailure.class,
                () -> IngestVerifier.checkAudioDuration(idx, 6_000_000, 4_900_000));
        assertTrue(both.getMessage().contains("6.000 s (decoded: 4.900 s)"), both.getMessage());
    }

    /** A decoder that outputs more than the container declares (WMA's padded last block): the decoded length counts. */
    @Test
    void verifyAcceptsTheDecodedLength() throws Exception {
        SampleIndex wma = index(150, 60, 3_072_000, 0);
        assertEquals(3_064_000, IngestVerifier.checkAudioDuration(wma, 3_000_000, 3_064_000));
        // an unknown container length (no stream duration, no packets) falls back to the decoded one
        assertEquals(3_064_000, IngestVerifier.checkAudioDuration(wma, -1, 3_064_000));
        // a truncated encode matches neither
        SampleIndex cut = index(150, 60, 2_000_000, 0);
        assertThrows(IngestFailure.class, () -> IngestVerifier.checkAudioDuration(cut, 3_000_000, 3_064_000));
    }

    @Test
    void verifyRejectsWrongKeyframes() {
        IngestFailure wrongGop = assertThrows(IngestFailure.class,
                () -> IngestVerifier.checkKeyframes(index(150, 50, 5_000_000, 0), "mezz.mp4"));
        assertTrue(wrongGop.getMessage().contains("frame 50 is a keyframe outside the 2 s grid"), wrongGop.getMessage());
        IngestFailure longGop = assertThrows(IngestFailure.class,
                () -> IngestVerifier.checkKeyframes(index(150, 90, 5_000_000, 0), "mezz.mp4"));
        assertTrue(longGop.getMessage().contains("frame 60 should be an IDR"), longGop.getMessage());
        IngestFailure late = assertThrows(IngestFailure.class,
                () -> IngestVerifier.checkKeyframes(index(150, 60, 5_000_000, 512), "card.mp4"));
        assertTrue(late.getMessage().contains("card.mp4 IDR 0 is at 512/15360"), late.getMessage());
    }

    // ------------------------------------------------------------------ commands

    @Test
    void encodeCommandsUseTheSharedProfileArguments() {
        MediaProbe probe = new MediaProbe("matroska", 0, 1_000_000, new MediaProbe.Stream(1, "opus", 0, -1),
                new MediaProbe.Stream(0, "vp9", 0, -1), null);
        Loudness.Measurement m = new Loudness.Measurement(-20, -3, 5, -30, 0.5);
        List<String> video = IngestPipeline.videoEncodeArgs(Path.of("src.mkv"), probe, m, OutputProfile.P720_30,
                Path.of("mezz.mp4"));
        assertEquals(List.of("-y", "-i", "src.mkv", "-map", "0:0", "-map", "0:1", "-map_metadata", "-1",
                "-map_chapters", "-1", "-vf", EncodeProfile.scaleFilter(OutputProfile.P720_30)), video.subList(0, 13));
        assertEquals(EncodeProfile.VIDEO_ARGS_720P30, video.subList(13, 13 + EncodeProfile.VIDEO_ARGS_720P30.size()));
        int af = video.indexOf("-af");
        assertEquals(m.filter(), video.get(af + 1));
        assertEquals(EncodeProfile.AUDIO_ARGS, video.subList(af + 2, af + 2 + EncodeProfile.AUDIO_ARGS.size()));
        assertEquals(List.of("-movflags", "+faststart", "-f", "mp4", "mezz.mp4"), video.subList(video.size() - 5, video.size()));

        List<String> audio = IngestPipeline.audioEncodeArgs(Path.of("src.flac"), probe, m, Path.of("audio.m4a"));
        assertFalse(audio.contains("-vf"));
        assertTrue(audio.contains("-vn"));
        assertEquals("0:1", audio.get(audio.indexOf("-map") + 1));
        int aaf = audio.indexOf("-af");
        assertEquals(EncodeProfile.AUDIO_ARGS, audio.subList(aaf + 2, aaf + 2 + EncodeProfile.AUDIO_ARGS.size()));

        assertEquals(List.of("-y", "-i", "mezz.mp4", "-map", "0:a", "-c", "copy", "-movflags", "+faststart",
                "-f", "mp4", "audio.m4a"), IngestPipeline.extractAudioArgs(Path.of("mezz.mp4"), Path.of("audio.m4a")));
    }

    @Test
    void downloadUsesTheCanonicalUrl() {
        List<String> args = IngestPipeline.downloadArgs("dQw4w9WgXcQ", Path.of("/data/tmp/jobs/j"), "/opt/bin/ffmpeg");
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", args.get(args.size() - 1));
        assertEquals("--", args.get(args.size() - 2));
        assertEquals("bv*[height<=1080]+ba/b", args.get(args.indexOf("-f") + 1));
        assertEquals("mkv", args.get(args.indexOf("--merge-output-format") + 1));
        assertEquals("/data/tmp/jobs/j/src.%(ext)s", args.get(args.lastIndexOf("-o") + 1));
        // the best thumbnail, next to the download, becomes the cover
        assertTrue(args.contains("--write-thumbnail"));
        assertEquals("thumbnail:/data/tmp/jobs/j/cover.%(ext)s", args.get(args.indexOf("-o") + 1));
        assertTrue(args.contains("--no-playlist"));
        assertEquals("/opt/bin/ffmpeg", args.get(args.indexOf("--ffmpeg-location") + 1));
        assertFalse(args.contains("--cookies"));
        assertFalse(IngestPipeline.downloadArgs("dQw4w9WgXcQ", Path.of("x"), "ffmpeg").contains("--ffmpeg-location"));

        List<String> withCookies = IngestPipeline.downloadArgs("dQw4w9WgXcQ", Path.of("x"), "ffmpeg",
                Path.of("/etc/tt/cookies.txt"));
        assertEquals("/etc/tt/cookies.txt", withCookies.get(withCookies.indexOf("--cookies") + 1));
        assertTrue(withCookies.indexOf("--cookies") < withCookies.indexOf("--"), "options before --");
        assertThrows(IllegalArgumentException.class, () -> IngestPipeline.downloadArgs("not-an-id", Path.of("x"), "ffmpeg"));
    }

    @Test
    void metadataUsesTheCanonicalUrl() {
        assertEquals(List.of("-J", "--no-playlist", "--no-warnings", "--", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                IngestPipeline.metadataArgs("dQw4w9WgXcQ", null));
        assertEquals(List.of("-J", "--no-playlist", "--no-warnings", "--cookies", "/c.txt", "--",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ"), IngestPipeline.metadataArgs("dQw4w9WgXcQ", Path.of("/c.txt")));
        assertThrows(IllegalArgumentException.class, () -> IngestPipeline.metadataArgs("https://youtu.be/dQw4w9WgXcQ", null));
    }

    @Test
    void cookiesFileIsUsedOnlyWhenReadable() throws Exception {
        List<String> warnings = new java.util.ArrayList<>();
        assertNull(IngestPipeline.cookiesFile(null, warnings::add));
        assertNull(IngestPipeline.cookiesFile("  ", warnings::add));
        assertTrue(warnings.isEmpty(), "no setting, no warning");
        assertNull(IngestPipeline.cookiesFile(dir.resolve("missing.txt").toString(), warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("missing.txt"), warnings.toString());
        assertNull(IngestPipeline.cookiesFile(dir.toString(), warnings::add), "a directory isn't a cookies file");
        Path cookies = Files.writeString(dir.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        assertEquals(cookies, IngestPipeline.cookiesFile(" " + cookies + " ", warnings::add));
    }

    // ------------------------------------------------------------------ files

    @Test
    void stagedFilesAreFoundByBaseName() throws Exception {
        assertNull(IngestPipeline.staged(dir.resolve("missing"), "source"));
        Files.writeString(dir.resolve("sourcex"), "no");
        Files.writeString(dir.resolve("cover.webp"), "c");
        assertNull(IngestPipeline.staged(dir, "source"));
        Files.writeString(dir.resolve("source.flac"), "s");
        assertEquals(dir.resolve("source.flac"), IngestPipeline.staged(dir, "source"));
        assertEquals(dir.resolve("cover.webp"), IngestPipeline.staged(dir, "cover"));
    }

    @Test
    void publishSwapsTheDirectory() throws Exception {
        DataDir data = DataDir.create(dir.resolve("data"));
        String item = UuidV7.newId();
        String job = UuidV7.newId();
        Path target = IngestPipeline.itemDir(data, item);
        Path work = data.media().resolve(".ingest-" + job);
        Files.createDirectories(work);
        Files.writeString(work.resolve("audio.m4a"), "first");
        IngestPipeline.publish(work, target, item, job);
        assertEquals("first", Files.readString(target.resolve("audio.m4a")));
        assertFalse(Files.exists(work));

        String job2 = UuidV7.newId();
        Path work2 = data.media().resolve(".ingest-" + job2);
        Files.createDirectories(work2);
        Files.writeString(work2.resolve("mezz.mp4"), "second");
        IngestPipeline.publish(work2, target, item, job2);
        assertEquals("second", Files.readString(target.resolve("mezz.mp4")));
        assertFalse(Files.exists(target.resolve("audio.m4a")));
        try (var s = Files.list(data.media())) {
            assertEquals(List.of(item), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void crashLeftoversAreCleanedUpOrRestored() throws Exception {
        DataDir data = DataDir.create(dir.resolve("data"));
        String kept = UuidV7.newId();
        String lost = UuidV7.newId();
        // a half-built work dir, a finished swap's old dir, an interrupted swap's old dir, a job scratch dir
        Files.createDirectories(data.media().resolve(".ingest-" + UuidV7.newId()).resolve("x"));
        Files.createDirectories(data.media().resolve(kept));
        Files.writeString(data.media().resolve(kept).resolve("audio.m4a"), "new");
        Path oldKept = data.media().resolve(".old-" + kept + "-" + UuidV7.newId());
        Files.createDirectories(oldKept);
        Files.writeString(oldKept.resolve("audio.m4a"), "old");
        Path oldLost = data.media().resolve(".old-" + lost + "-" + UuidV7.newId());
        Files.createDirectories(oldLost);
        Files.writeString(oldLost.resolve("audio.m4a"), "restored");
        Files.createDirectories(IngestPipeline.scratchDir(data, UuidV7.newId()).resolve("y"));
        Path staging = IngestPipeline.stagingDir(data, UuidV7.newId());
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("source.mp3"), "upload");
        // what a purge moved aside and didn't get to delete
        Path purgedMedia = IngestPipeline.moveAside(Files.createDirectories(data.media().resolve(UuidV7.newId())));
        Files.writeString(purgedMedia.resolve("audio.m4a"), "purged");
        Path purgedStaging = IngestPipeline.moveAside(Files.createDirectories(
                IngestPipeline.stagingDir(data, UuidV7.newId())));
        assertTrue(purgedStaging.getFileName().toString().startsWith(IngestPipeline.PURGE_PREFIX));
        assertNull(IngestPipeline.moveAside(data.media().resolve("missing")));

        assertEquals(6, IngestPipeline.recoverDirectories(data));
        assertFalse(Files.exists(purgedMedia));
        assertFalse(Files.exists(purgedStaging));
        try (var s = Files.list(data.media())) {
            assertEquals(List.of(kept, lost), s.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals("new", Files.readString(data.media().resolve(kept).resolve("audio.m4a")));
        assertEquals("restored", Files.readString(data.media().resolve(lost).resolve("audio.m4a")));
        try (var s = Files.list(data.tmp().resolve("jobs"))) {
            assertEquals(0, s.count());
        }
        assertTrue(Files.exists(staging.resolve("source.mp3")), "staged uploads are kept for their job");
        assertEquals(0, IngestPipeline.recoverDirectories(data));
        assertNull(IngestPipeline.oldItemId(".old-short"));
    }

    @Test
    void idsCantEscapeTheirDirectories() throws Exception {
        DataDir data = DataDir.of(dir);
        assertThrows(IllegalArgumentException.class, () -> IngestPipeline.itemDir(data, "../x"));
        assertThrows(IllegalArgumentException.class, () -> IngestPipeline.stagingDir(data, ".."));
        assertThrows(IllegalArgumentException.class, () -> IngestPipeline.itemDir(data, ""));
    }
}
