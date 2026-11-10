package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Sources over real profile encodes (needs ffmpeg and ffprobe). */
class RealSourcesTest {
    @TempDir
    Path dir;

    static Tools tools() {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        return new Tools(ffmpeg, ffprobe, null);
    }

    private static String onPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String candidate : List.of(entry, "/opt/homebrew/bin", "/usr/local/bin")) {
                Path p = Path.of(candidate, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    private void encode(Tools tools, OutputProfile profile, Path out, double seconds, boolean audio)
            throws Exception {
        List<String> enc = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=30"));
        if (audio) {
            enc.addAll(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000"));
        }
        enc.addAll(List.of("-t", Double.toString(seconds), "-vf", EncodeProfile.scaleFilter(profile)));
        enc.addAll(EncodeProfile.videoArgs(profile));
        if (audio) {
            enc.addAll(EncodeProfile.AUDIO_ARGS);
        } else {
            enc.add("-an");
        }
        enc.add(out.toString());
        tools.ffmpeg(enc).run();
    }

    @Test
    @Timeout(120)
    void realCardAndVideoItem() throws Exception {
        Tools tools = tools();
        OutputProfile profile = OutputProfile.P720_30;

        Path cardFile = dir.resolve("card.mp4");
        encode(tools, profile, cardFile, 2, false);
        CardGop card = CardGop.load(tools, profile, cardFile, new JobLog());
        assertEquals(60, card.frameCount());

        Path mezz = dir.resolve("mezz.mp4");
        encode(tools, profile, mezz, 5, true);
        SampleIndex index = IndexBuilder.build(tools, profile, mezz, mezz, dir.resolve("index.bin"), new JobLog());
        assertTrue(Arrays.equals(card.avcC(), index.avcC()), "invariant 1: card and mezzanine avcC match");
        AnnexB annexB = new AnnexB(index.avcC());

        try (FileSampleData data = new FileSampleData(dir, index)) {
            VideoItemSource s = new VideoItemSource("item", index, data, 3000);
            assertTrue(Math.abs(s.mediaStartMs() - 2000) <= 11, "audio starts at the keyframe: " + s.mediaStartMs());
            int n = 0;
            for (VideoSample v; (v = s.nextVideo()) != null; n++) {
                assertEquals(n * 3000L, v.pts(), "pts " + n);
                assertEquals(n % 60 == 0, v.idr(), "idr " + n);
                assertEquals(v.idr(), annexB.isIdr(v.data(), 0, v.data().length), "IDR NAL " + n);
            }
            assertEquals(90, n);
            int frames = 0;
            for (byte[] f; (f = s.nextAudio()) != null; frames++) {
                assertTrue(f.length > 0);
            }
            // 3 s of 48 kHz audio from about 2 s: ~141 frames
            assertTrue(frames >= 138 && frames <= 144, "audio frames " + frames);

            ItemSource asAudio = ItemSources.forItem("item", true, true, index, data, card, 3000);
            VideoSample first = asAudio.nextVideo();
            assertTrue(first.idr() && annexB.isIdr(first.data(), 0, first.data().length));
            assertTrue(Math.abs(asAudio.mediaStartMs() - 3000) <= 22, "from the frame containing 3 s");
        }
    }
}
