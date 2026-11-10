package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SkipFrames;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Skip-frame cards ({@code docs/library-and-ingest.md#cards}) with real ffmpeg: the written P_Skip
 * frames decode to exactly the IDR for every profile and preset (and for stream variants the
 * writer handles: other QPs, CAVLC, POC type 0, no weighted prediction), cost a few bytes each,
 * and a card of a detailed cover stays far below the audio-only rate; the fallback to a full x264
 * encode works. Needs ffmpeg and ffprobe.
 */
class SkipFrameCardTest {
    /** Most a written skip frame may take in the MP4 (with its 4-byte length prefix). */
    static final int MAX_SKIP_FRAME_BYTES = 100;

    @TempDir
    Path dir;

    /** A detailed, photo-like still at {@code w}×{@code h}: a fractal with grain. */
    static Path detailedPng(Tools tools, Path dir, int w, int h) throws Exception {
        Path png = dir.resolve("detailed-" + w + "x" + h + ".png");
        if (!Files.exists(png)) {
            tools.ffmpeg("-v", "error", "-y", "-f", "lavfi", "-i", "mandelbrot=s=" + w + "x" + h, "-vf",
                    "noise=alls=12:allf=u,format=rgb24", "-frames:v", "1", "-update", "1", png.toString()).run();
        }
        return png;
    }

    /** Encodes one frame of {@code png} with the profile arguments, then {@code extra} replacing or adding options. */
    private Path idrStream(Tools tools, Path png, OutputEncoding encoding, X264Preset preset, List<String> extra)
            throws Exception {
        Path out = Files.createTempFile(dir, "idr-", ".h264");
        List<String> args = new ArrayList<>(List.of("-v", "error", "-y", "-loop", "1", "-framerate", "30",
                "-i", png.toString(), "-frames:v", "1", "-vf", EncodeProfile.scaleFilter(encoding)));
        args.addAll(EncodeProfile.videoArgs(encoding, preset));
        args.addAll(extra);
        args.addAll(List.of("-an", "-f", "h264", out.toString()));
        tools.ffmpeg(args).run();
        return out;
    }

    /** Builds, remuxes and checks a skip-frame card; returns its packets. */
    private SampleIndex.Track card(Tools tools, Path idr, OutputEncoding encoding, boolean profileStream)
            throws Exception {
        Path gop = Files.createTempFile(dir, "gop-", ".h264");
        Files.write(gop, CardEncoder.skipFrameGop(Files.readAllBytes(idr)));
        Path mp4 = Files.createTempFile(dir, "card-", ".mp4");
        tools.ffmpeg(CardEncoder.remuxCommand(gop, mp4, encoding)).run();
        CardEncoder.check(tools, mp4, encoding, null); // 60 identical frames, one IDR, n/30 s
        if (profileStream) {
            CardEncoderTest.checkCard(tools, mp4, encoding);
        }
        SampleIndex.Track track = IndexBuilder.probeTrack(tools, mp4, SampleIndex.Kind.VIDEO, 0, null);
        for (int i = 1; i < track.sampleCount(); i++) {
            assertTrue(track.size(i) < MAX_SKIP_FRAME_BYTES, "skip frame " + i + " has " + track.size(i) + " bytes");
        }
        return track;
    }

    static List<Arguments> profilesAndPresets() {
        List<Arguments> list = new ArrayList<>();
        for (OutputProfile p : OutputProfile.values()) {
            for (X264Preset preset : X264Preset.values()) {
                list.add(Arguments.of(p, preset));
            }
        }
        return list;
    }

    @ParameterizedTest
    @MethodSource("profilesAndPresets")
    @Timeout(120)
    void skipFramesDecodeToTheIdrForEveryProfileAndPreset(OutputProfile p, X264Preset preset) throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(p);
        Path png = detailedPng(tools, dir, 1280, 720);
        card(tools, idrStream(tools, png, encoding, preset, List.of()), encoding, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qp=1", "qp=12", "qp=30", "qp=51", "cavlc", "bframes", "noweightp", "nodeblock"})
    @Timeout(120)
    void skipFramesHandleStreamVariants(String variant) throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path png = detailedPng(tools, dir, 640, 360);
        String params = "open-gop=0:ref=4";
        List<String> extra = new ArrayList<>();
        switch (variant) {
            case "cavlc" -> params += ":cabac=0";
            case "bframes" -> extra.addAll(List.of("-bf", "2")); // POC type 0
            case "noweightp" -> params += ":weightp=0";
            case "nodeblock" -> params += ":no-deblock=1";
            default -> {
                // constant QP: the CABAC context starts at another state (and in the LPS at low QPs)
                extra.addAll(List.of("-b:v", "0", "-maxrate", "0", "-bufsize", "0", "-qp", variant.substring(3)));
            }
        }
        extra.addAll(List.of("-x264-params", params));
        card(tools, idrStream(tools, png, encoding, X264Preset.REFERENCE, extra), encoding, false);
    }

    @Test
    @Timeout(120)
    void interlacedStreamsAreRefused() throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path png = detailedPng(tools, dir, 640, 360);
        Path idr = idrStream(tools, png, encoding, X264Preset.REFERENCE,
                List.of("-x264-params", "open-gop=0:ref=4:interlaced=1"));
        assertThrows(SkipFrames.UnsupportedStreamException.class,
                () -> CardEncoder.skipFrameGop(Files.readAllBytes(idr)));
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    @Timeout(120)
    void aDetailedCoverCardIsFarBelowTheAudioOnlyRate(OutputProfile p) throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(p);
        ReferenceCard ref = ReferenceCard.ensure(tools, dir.resolve("cards"), encoding, null);
        BufferedImage cover = CardRenderer.readImage(detailedPng(tools, dir, 1000, 1000));
        BufferedImage image = new CardRenderer(p).renderItem(
                new CardRenderer.ItemCard("A Detailed Cover", "Some Artist", "Some Album", cover));
        Path out = dir.resolve("item").resolve("card.mp4");
        JobLog log = new JobLog();
        CardEncoder.encode(tools, image, out, ref, log);
        assertArrayEquals(ref.avcC(), ReferenceCard.readAvcC(out));
        assertTrue(!log.tail().contains("skip-frame card failed"), log.tail());
        CardEncoder.check(tools, out, encoding, null);
        SampleIndex.Track track = IndexBuilder.probeTrack(tools, out, SampleIndex.Kind.VIDEO, 0, null);
        long bytes = 0;
        for (int i = 0; i < track.sampleCount(); i++) {
            bytes += track.size(i);
            if (i > 0) {
                assertTrue(track.size(i) < MAX_SKIP_FRAME_BYTES, "frame " + i + ": " + track.size(i) + " bytes");
            }
        }
        long kbps = bytes * 8 / (EncodeProfile.GOP_MS / 1000) / 1000;
        // the default audio-only rate is 800 kb/s, with 192 kb/s of audio; a full x264 card of a
        // detailed cover took 800+ kb/s of video at 720p
        long limit = p == OutputProfile.P720_30 ? 200 : 400;
        System.out.println("[SkipFrameCardTest] " + p + " detailed cover card: " + kbps + " kb/s, IDR " + track.size(0)
                + " bytes, skip frames " + track.size(1) + " bytes");
        assertTrue(kbps < limit, "card video at " + kbps + " kb/s (IDR " + track.size(0) + " bytes)");
        assertTrue(track.size(0) > 5_000, "the IDR carries the picture: " + track.size(0) + " bytes");
    }

    @Test
    @Timeout(120)
    void fallsBackToTheFullEncodeWhenSkipFramesFail() throws Exception {
        Tools tools = CardEncoderTest.tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        Path png = detailedPng(tools, dir, 640, 360);
        Path out = dir.resolve("fallback.mp4");
        JobLog log = new JobLog();
        CardEncoder.encodeCard(tools, png, out, encoding, log, (t, in, o, e, l) -> {
            Files.write(o, new byte[] {1, 2, 3}); // a half-written card is replaced
            throw new java.io.IOException("simulated");
        });
        assertTrue(log.tail().contains("skip-frame card failed (simulated)"), log.tail());
        CardEncoderTest.checkCard(tools, out, encoding);
        SampleIndex.Track track = IndexBuilder.probeTrack(tools, out, SampleIndex.Kind.VIDEO, 0, null);
        assertEquals(EncodeProfile.GOP_FRAMES, track.sampleCount());
        long pBytes = 0;
        for (int i = 1; i < track.sampleCount(); i++) {
            pBytes += track.size(i);
        }
        assertTrue(pBytes > (EncodeProfile.GOP_FRAMES - 1) * MAX_SKIP_FRAME_BYTES,
                "x264 encoded the P-frames: " + pBytes + " bytes");
    }
}
