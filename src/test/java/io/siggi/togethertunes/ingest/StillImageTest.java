package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.ingest.IngestJob.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Still-image detection ({@link StillImage}, {@code docs/media-types.md#still-image-detection}):
 * the decision on synthetic frames, and on real media made with ffmpeg (skipped without it): a
 * still image, a near-still with grain, a slow zoom, a lyric video whose line of text changes, a
 * normal video, and a video stream of one frame under a longer song.
 */
@Timeout(120)
@Execution(ExecutionMode.SAME_THREAD)
class StillImageTest {
    private static final int N = StillImage.WIDTH * StillImage.HEIGHT;

    @TempDir
    Path dir;

    private static byte[] flat(int grey) {
        byte[] f = new byte[N];
        java.util.Arrays.fill(f, (byte) grey);
        return f;
    }

    private static StillImage.Result run(long durationUs, List<byte[]> frames) {
        StillImage.Detector d = new StillImage.Detector(durationUs);
        for (byte[] f : frames) {
            if (!d.frame(f)) {
                break;
            }
        }
        return d.result();
    }

    private static List<byte[]> repeat(byte[] f, int n) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(f);
        }
        return out;
    }

    @Test
    void pixelsAndCounts() {
        byte[] ref = flat(100);
        byte[] f = ref.clone();
        for (int i = 0; i < StillImage.MIN_CHANGED_PIXELS - 1; i++) {
            f[i * 50] = (byte) 200;
        }
        assertFalse(StillImage.differs(ref, f), "fewer pixels than the minimum");
        f[7777] = (byte) 200;
        assertTrue(StillImage.differs(ref, f));
        byte[] noise = ref.clone();
        Random r = new Random(1);
        for (int i = 0; i < N; i++) {
            noise[i] = (byte) (100 + r.nextInt(2 * StillImage.PIXEL_THRESHOLD + 1) - StillImage.PIXEL_THRESHOLD);
        }
        assertFalse(StillImage.differs(ref, noise), "noise within the threshold everywhere");
    }

    @Test
    void decisions() {
        // 60 s at 2 fps: a still all along
        StillImage.Result still = run(60_000_000, repeat(flat(80), 120));
        assertTrue(still.still(), still.reason());
        assertEquals(0, still.startUs());
        assertEquals(60_000_000, still.endUs());
        assertEquals(30_000_000, still.coverAtUs());

        // a fade-in of 1.5 s, then still (the allowance is 2 s for 60 s)
        List<byte[]> fade = new ArrayList<>();
        fade.add(flat(0));
        fade.add(flat(40));
        fade.add(flat(80));
        fade.addAll(repeat(flat(120), 117));
        StillImage.Result faded = run(60_000_000, fade);
        assertTrue(faded.still(), faded.reason());
        assertEquals(1_500_000, faded.startUs());

        // the picture changes every 10 s: no, and decided early
        List<byte[]> lyric = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lyric.addAll(repeat(flat(40 * (i % 2) + 50), 20));
        }
        StillImage.Detector d = new StillImage.Detector(60_000_000);
        int fed = 0;
        for (byte[] f : lyric) {
            fed++;
            if (!d.frame(f)) {
                break;
            }
        }
        assertEquals(21, fed, "stops at the first change after the allowance");
        StillImage.Result changes = d.result();
        assertFalse(changes.still(), changes.reason());

        // a change at the very end, within the allowance: still
        List<byte[]> tail = new ArrayList<>(repeat(flat(80), 117));
        tail.addAll(repeat(flat(0), 3));
        assertTrue(run(60_000_000, tail).still());
        // a lead-in and a tail that together exceed the allowance: no
        List<byte[]> both = new ArrayList<>(repeat(flat(10), 3));
        both.addAll(repeat(flat(80), 114));
        both.addAll(repeat(flat(0), 3));
        assertFalse(run(60_000_000, both).still());

        // a video stream that ends early (one frame under a longer song) counts as still
        assertTrue(run(60_000_000, repeat(flat(80), 1)).still());
        // nothing decoded, or no duration: never still
        assertFalse(run(60_000_000, List.of()).still());
        assertEquals(10_000_000, StillImage.allowanceUs(3_600_000_000L));
        assertEquals(1_000_000, StillImage.allowanceUs(10_000_000));
        assertEquals(2_000_000, StillImage.allowanceUs(60_000_000));
        assertEquals(6_000_000, StillImage.allowanceUs(200_000_000));
        assertEquals(200_000, StillImage.allowanceUs(2_000_000), "a tenth of a short video");

        // a short moving clip is no still image (its allowance isn't the whole clip)
        List<byte[]> moving = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            moving.add(flat(20 + 50 * i));
        }
        assertFalse(run(2_000_000, moving).still());
    }

    @Test
    void whenTheCheckRuns() {
        EditSettings none = EditSettings.parse(null);
        assertTrue(IngestPipeline.checksStillImage(Kind.UPLOAD, none));
        assertTrue(IngestPipeline.checksStillImage(Kind.YOUTUBE, none));
        assertTrue(IngestPipeline.checksStillImage(Kind.REFETCH, none));
        assertFalse(IngestPipeline.checksStillImage(Kind.REENCODE, none));
        assertFalse(IngestPipeline.checksStillImage(Kind.QUALITY, none));
        assertFalse(IngestPipeline.checksStillImage(Kind.TRIM, none));
        assertFalse(IngestPipeline.checksStillImage(Kind.REFETCH, EditSettings.parse("{\"videoStartMs\":1000}")));
    }

    /** A video item (a still image brought back by Undo) never encodes from an audio-only original. */
    @Test
    void anAudioOnlyOriginalIsNoSourceForAVideo() throws Exception {
        Files.writeString(dir.resolve(Originals.AUDIO_OF_VIDEO), "audio");
        IngestItems.Item video = new IngestItems.Item("i", "video", "T", null, null, "upload", null, "ready",
                "720p30", Originals.AUDIO_OF_VIDEO, null, "veryfast", false, 3);
        assertEquals(IngestPipeline.SourceKind.PREVIOUS, IngestPipeline.sourceKind(Kind.REENCODE, video, dir));
        IngestItems.Item youtube = new IngestItems.Item("i", "video", "T", null, null, "youtube", "dQw4w9WgXcQ",
                "ready", "720p30", Originals.AUDIO_OF_VIDEO, null, "veryfast", false, 3);
        assertEquals(IngestPipeline.SourceKind.DOWNLOAD, IngestPipeline.sourceKind(Kind.QUALITY, youtube, dir));
        IngestItems.Item audio = new IngestItems.Item("i", "audio", "T", null, null, "upload", null, "ready",
                "720p30", Originals.AUDIO_OF_VIDEO, null, "veryfast", true, 4);
        assertEquals(IngestPipeline.SourceKind.ORIGINAL, IngestPipeline.sourceKind(Kind.REENCODE, audio, dir));
    }

    // ------------------------------------------------------------------ real media

    private Tools tools() {
        return CardEncoderTest.tools();
    }

    private void ffmpeg(Tools tools, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("-y", "-loglevel", "error"));
        all.addAll(List.of(args));
        tools.ffmpeg(all).run();
    }

    private Path picture(Tools tools) throws Exception {
        Path png = dir.resolve("pic.png");
        if (!Files.exists(png)) {
            ffmpeg(tools, "-f", "lavfi", "-i", "testsrc2=s=1280x720", "-frames:v", "1", png.toString());
        }
        return png;
    }

    private StillImage.Result detect(Tools tools, Path file) throws Exception {
        MediaProbe probe = MediaProbe.probe(tools, file, null);
        return StillImage.detect(tools, file, probe, new JobLog());
    }

    /** {@code -loop 1} over the picture with a tone, through {@code vf} (null: none), 12 s. */
    private Path fromPicture(Tools tools, String name, String vf) throws Exception {
        Path out = dir.resolve(name + ".mp4");
        List<String> args = new ArrayList<>(List.of("-loop", "1", "-framerate", "30", "-i", picture(tools).toString(),
                "-f", "lavfi", "-i", "sine=f=330:r=48000", "-t", "12"));
        if (vf != null) {
            args.addAll(List.of("-vf", vf));
        }
        args.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", out.toString()));
        ffmpeg(tools, args.toArray(String[]::new));
        return out;
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aStillImageVideoIsStill() throws Exception {
        Tools tools = tools();
        StillImage.Result r = detect(tools, fromPicture(tools, "still", null));
        assertTrue(r.still(), r.reason());
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aNearStillWithGrainIsStill() throws Exception {
        Tools tools = tools();
        // strong temporal grain (±8 levels before compression), more than compression noise ever is
        StillImage.Result r = detect(tools, fromPicture(tools, "grain", "noise=alls=8:allf=t"));
        assertTrue(r.still(), r.reason());
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aSlowZoomIsNotStill() throws Exception {
        Tools tools = tools();
        // 2.4 % a second: slow, but it adds up against the reference frame
        StillImage.Result r = detect(tools, fromPicture(tools, "zoom",
                "zoompan=z='1+0.0008*on':d=1:s=1280x720:fps=30"));
        assertFalse(r.still(), r.reason());
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aLyricVideoIsNotStill() throws Exception {
        Tools tools = tools();
        Path out = dir.resolve("lyric.mp4");
        Path font = dir.resolve("font.ttf");
        try (java.io.InputStream in = StillImageTest.class.getResourceAsStream("/io/siggi/togethertunes/fonts/DejaVuSans.ttf")) {
            Files.copy(in, font);
        }
        String text = "fontfile=" + font + ":fontsize=28:fontcolor=white:x=(w-tw)/2:y=h-80";
        // a plain background and one line of text whose words change every 3 s
        ffmpeg(tools, "-f", "lavfi", "-i", "color=c=0x203040:s=1280x720:r=30", "-f", "lavfi", "-i", "sine=r=48000",
                "-t", "12", "-vf", "drawtext=text='Never gonna give you up':" + text + ":enable='lt(mod(t,6),3)',"
                        + "drawtext=text='Never gonna let you down':" + text + ":enable='gte(mod(t,6),3)'",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                out.toString());
        StillImage.Result r = detect(tools, out);
        assertFalse(r.still(), r.reason());
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void aNormalVideoIsNotStill() throws Exception {
        Tools tools = tools();
        Path out = dir.resolve("normal.mp4");
        ffmpeg(tools, "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30", "-f", "lavfi", "-i", "sine=r=48000",
                "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest", out.toString());
        JobLog log = new JobLog();
        StillImage.Result r = StillImage.detect(tools, out, MediaProbe.probe(tools, out, null), log);
        assertFalse(r.still(), r.reason());
        assertTrue(r.reason().contains("stopped after"), "decided early: " + r.reason());
    }

    @Test
    @Execution(ExecutionMode.CONCURRENT)
    void oneFrameUnderALongerSongIsStill() throws Exception {
        Tools tools = tools();
        Path out = dir.resolve("oneframe.mkv");
        ffmpeg(tools, "-i", picture(tools).toString(), "-f", "lavfi", "-i", "sine=r=48000", "-t", "8",
                "-map", "0:v", "-map", "1:a", "-frames:v", "1", "-c:v", "libx264", "-preset", "ultrafast",
                "-c:a", "aac", out.toString());
        StillImage.Result r = detect(tools, out);
        assertTrue(r.still(), r.reason());
    }
}
