package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.SilenceFrame;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The muxer over real profile encodes, checked with ffmpeg/ffprobe as in
 * {@code docs/program-output.md#testing-the-packager} (needs ffmpeg and ffprobe).
 */
class RealTsMuxerTest {
    @TempDir
    Path dir;

    private void encode(Tools tools, OutputEncoding profile, Path out, double seconds, boolean audio)
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
    @Timeout(180)
    void splicedSessionIsCleanForFfmpeg() throws Exception {
        Tools tools = RealSourcesTest.tools();
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        JobLog log = new JobLog();

        Path cardFile = dir.resolve("card.mp4");
        encode(tools, profile, cardFile, 2, false);
        CardGop card = CardGop.load(tools, profile, cardFile, log);
        Path mezz = dir.resolve("mezz.mp4");
        encode(tools, profile, mezz, 5.3, true);
        SampleIndex index = IndexBuilder.build(tools, profile, mezz, mezz, dir.resolve("index.bin"), log);
        SilenceFrame silence = SilenceFrame.generate(tools, log);

        Path out = dir.resolve("out.ts");
        long videoFrames = 0;
        long audioFrames = 0;
        long fillerIn = 0;
        long fillerBytesIn = 0;
        try (FileSampleData data = new FileSampleData(dir, index);
                UnitBuilder b = new UnitBuilder();
                OutputStream os = Files.newOutputStream(out)) {
            TsMuxer mux = new TsMuxer(index.avcC(), "TogetherTunes", "Test Room");
            List<Unit> units = new ArrayList<>();
            // video item to its natural end (mid-unit)
            b.play(new VideoItemSource("item", index, data, 0));
            while (!b.sourceEnded()) {
                units.add(b.next());
            }
            // paused for two units
            b.play(new FillerSource(FillerSource.Filler.PAUSED, card, silence));
            units.add(b.next());
            units.add(b.next());
            // seek into the item, one unit, then the same item as audio (card GOPs) to its end
            b.play(new VideoItemSource("item", index, data, 2500));
            units.add(b.next());
            b.play(ItemSources.forItem("item", true, true, index, data, card, 3000));
            while (!b.sourceEnded()) {
                units.add(b.next());
            }
            // idle
            b.play(new FillerSource(FillerSource.Filler.IDLE, card, silence));
            units.add(b.next());
            // padded to the encode cap while sending, as the pipeline does (nothing on disk is padded)
            CbrPadder padder = new CbrPadder(4, profile.videoBitrateBps());
            for (Unit unpadded : units) {
                Unit u = padder.pad(unpadded);
                assertNotNull(u);
                videoFrames += u.video().size();
                audioFrames += u.audio().size();
                for (Unit.Video f : u.video()) {
                    // AVCC, 4-byte lengths: count the filler data NAL units (type 12), the padding
                    int pos = 0;
                    while (pos < f.data().length) {
                        int len = java.nio.ByteBuffer.wrap(f.data(), pos, 4).getInt();
                        if ((f.data()[pos + 4] & 0x1F) == 12) {
                            fillerIn++;
                            fillerBytesIn += len;
                        }
                        pos += 4 + len;
                    }
                }
                os.write(mux.mux(u));
            }
        }

        ToolResult decode = tools.ffmpeg("-v", "error", "-i", out.toString(), "-f", "null", "-").run();
        assertEquals("", decode.stderrTail().trim(), "ffmpeg -v error prints nothing");

        String streams = tools.ffprobe("-v", "error", "-show_entries", "stream=id,codec_name",
                "-of", "csv=p=0", out.toString()).stdoutToString(1 << 20).run().stdoutString();
        assertTrue(streams.contains("h264,0x101"), streams);
        assertTrue(streams.contains("aac,0x102"), streams);
        String programs = tools.ffprobe("-v", "error", "-show_entries",
                "program=program_id,pcr_pid:program_tags=service_name,service_provider",
                "-of", "default=nw=1", out.toString()).stdoutToString(1 << 20).run().stdoutString();
        assertTrue(programs.contains("pcr_pid=257"), programs);
        assertTrue(programs.contains("service_name=Test Room"), programs);
        assertTrue(programs.contains("service_provider=TogetherTunes"), programs);

        String packets = tools.ffprobe("-v", "error", "-show_entries", "packet=codec_type,pts,dts",
                "-of", "csv=p=0", out.toString()).stdoutToString(64 << 20).run().stdoutString();
        long lastVideo = Long.MIN_VALUE;
        long lastAudio = Long.MIN_VALUE;
        long nv = 0;
        long na = 0;
        for (String line : packets.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.trim().split(",");
            long pts = Long.parseLong(f[1]);
            assertEquals(f[1], f[2], "DTS = PTS");
            if (f[0].equals("video")) {
                if (nv == 0) {
                    assertEquals(OutputClock.START, pts);
                }
                assertTrue(pts > lastVideo, "video PTS strictly increasing at " + pts);
                lastVideo = pts;
                nv++;
            } else {
                if (na == 0) {
                    assertEquals(OutputClock.START, pts);
                } else {
                    assertEquals(1920, pts - lastAudio, "audio delta at " + pts);
                }
                lastAudio = pts;
                na++;
            }
        }
        assertEquals(videoFrames, nv, "every access unit");
        assertEquals(audioFrames, na, "every audio frame");

        // the padding passes through: every filler NAL of the samples is in the .ts, byte for byte
        assertTrue(fillerIn > videoFrames / 2, "the units are padded: " + fillerIn + " filler NALs");
        Path h264 = dir.resolve("out.h264");
        tools.ffmpeg("-v", "error", "-i", out.toString(), "-map", "0:v", "-c", "copy", "-f", "h264", h264.toString()).run();
        long[] fillerOut = fillerNals(Files.readAllBytes(h264));
        assertEquals(fillerIn, fillerOut[0], "filler NAL count");
        assertEquals(fillerBytesIn, fillerOut[1], "filler NAL bytes");
    }

    /** Count and total size of the filler data NAL units (type 12) in an Annex B stream. */
    private static long[] fillerNals(byte[] annexB) {
        long count = 0;
        long bytes = 0;
        int start = -1;
        for (int i = 0; i + 2 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                if (start >= 0 && (annexB[start] & 0x1F) == 12) {
                    count++;
                    bytes += nalEnd(annexB, i) - start;
                }
                start = i + 3;
                i += 2;
            }
        }
        if (start >= 0 && start < annexB.length && (annexB[start] & 0x1F) == 12) {
            count++;
            bytes += annexB.length - start;
        }
        return new long[] {count, bytes};
    }

    /** End of the NAL unit before the start code at {@code startCode} (drops a 4-byte code's leading zero). */
    private static int nalEnd(byte[] annexB, int startCode) {
        return startCode > 0 && annexB[startCode - 1] == 0 ? startCode - 1 : startCode;
    }
}
