package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.IndexBuilder;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.JobLog;
import io.siggi.togethertunes.ingest.SilenceFrame;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The packager pads the program output to a constant video rate ({@link CbrPadder},
 * {@code docs/program-output.md#constant-rate-padding}), checked on real encodes through the
 * offline harness: every unit (IDR to IDR) of a mixed session carries {@code T × its length / 8}
 * video bytes within a small tolerance, at T = the encode cap and, for cards only (as an
 * audio-only room plays), at a lower T; and an RTMP-style {@code -c copy} remux to FLV keeps the
 * filler. Needs ffmpeg and ffprobe.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CbrPaddingTest {
    /** The access unit delimiter the muxer writes before every frame, start code included. */
    private static final int AUD_BYTES = 6;

    @TempDir
    Path dir;

    /** SPS and PPS with their start codes, as the muxer writes them before every IDR. */
    private static int parameterSetBytes(byte[] avcC) throws Exception {
        AnnexB a = new AnnexB(avcC);
        int n = 0;
        for (byte[] nal : a.sps()) {
            n += 4 + nal.length;
        }
        for (byte[] nal : a.pps()) {
            n += 4 + nal.length;
        }
        return n;
    }

    /** One video packet as ffprobe reports it. */
    private record Packet(long pts, long size, boolean key) {
    }

    private static List<Packet> videoPackets(Tools tools, Path file) throws Exception {
        String out = tools.ffprobe("-v", "error", "-select_streams", "v", "-show_entries", "packet=pts,size,flags",
                "-of", "csv=p=0", file.toString()).stdoutToString(1 << 28)
                .timeout(Duration.ofMinutes(5)).run().stdoutString();
        List<Packet> packets = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.trim().split(",");
            packets.add(new Packet(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2].startsWith("K")));
        }
        return packets;
    }

    /**
     * Video bytes per unit (each unit starts with the only keyframe in it) against
     * {@code T × (next unit's start − start) / 8}, ticks of {@code timeBase} Hz; the last unit is left
     * out (its end isn't known). Every full unit (about 2 s) must be within {@code unitTolerance} of
     * its target; the running total may be ahead by at most {@code maxAheadBytes} (an encode's IDR
     * burst, bounded by its VBV buffer) and behind by at most {@code maxBehindBytes} (the padding for
     * the up to one audio frame between a unit's last frame and a switch to a new source lands in the
     * next unit); the whole run within 0.5 %. Packet sizes are the Annex B access units, so the
     * AUD before every frame and the SPS and PPS before every IDR ({@code keyOverhead}), which the
     * padder doesn't count (it counts AVCC samples), are taken off first.
     */
    private static void checkConstantRate(List<Packet> packets, long bitrateBps, long timeBase, double unitTolerance,
            double maxAheadBytes, double maxBehindBytes, int keyOverhead) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i).key()) {
                starts.add(i);
            }
        }
        assertTrue(starts.size() > 10, "units: " + starts.size());
        List<String> report = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        double running = 0;
        double totalTarget = 0;
        for (int u = 0; u + 1 < starts.size(); u++) {
            long bytes = 0;
            for (int i = starts.get(u); i < starts.get(u + 1); i++) {
                bytes += packets.get(i).size() - AUD_BYTES - (packets.get(i).key() ? keyOverhead : 0);
            }
            long ticks = packets.get(starts.get(u + 1)).pts() - packets.get(starts.get(u)).pts();
            double target = (double) bitrateBps * ticks / timeBase / 8;
            double dev = (bytes - target) / target;
            running += bytes - target;
            totalTarget += target;
            String r = String.format("%d:%dms:%+.2f%%:%+.0fB", u, ticks * 1000 / timeBase, dev * 100, running);
            report.add(r);
            boolean full = ticks * 1000 / timeBase >= 1900;
            if ((full && Math.abs(dev) > unitTolerance) || running > maxAheadBytes || running < -maxBehindBytes) {
                bad.add(r);
            }
        }
        System.out.println("constant rate at " + bitrateBps + " b/s: " + report);
        assertEquals(List.of(), bad, "units off the constant rate; all: " + report);
        assertEquals(0, running / totalTarget, 0.005, "overall rate");
    }

    /** NAL units of type 12 in a raw Annex B H.264 stream. */
    private static int fillerNals(byte[] annexB) {
        int count = 0;
        for (int i = 0; i + 3 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                if ((annexB[i + 3] & 0x1F) == 12) {
                    count++;
                }
                i += 3;
            }
        }
        return count;
    }

    /**
     * A mixed session (the harness's 60 s cycle: video items, an audio item's card, a pause, a seek,
     * idle) padded to the encode cap, and the same through {@code -c copy} to FLV as the RTMP pusher
     * sends it.
     */
    @Test
    @Timeout(600)
    void mixedSessionAtTheEncodeCap() throws Exception {
        Tools tools = HarnessFixture.tools();
        // real cards are still pictures (moving ones would loop a GOP that may run ahead of the cap)
        HarnessFixture.Media m = HarnessFixture.media(tools, dir, "smptebars");
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
        HarnessFixture.cycle(script, 0, 0);
        long rate = OutputEncoding.defaultFor(OutputProfile.P720_30).videoBitrateBps();
        Path ts = dir.resolve("padded.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 60_000, rate, ts);
        List<String> kinds = HarnessFixture.kinds(r);
        assertTrue(kinds.containsAll(List.of("video", "audio", "paused", "idle")), kinds.toString());
        HarnessFixture.assertDecodesClean(tools, ts);
        HarnessFixture.assertPackets(tools, ts, r);
        // ahead: the VBV buffer, half a second at the cap; behind: one audio frame plus overhead
        double buffer = rate / 8.0 * EncodeProfile.BUFSIZE_MS / 1000;
        double frame = rate / 8.0 * ProgramSource.AUDIO_FRAME_TICKS / 90_000 + 1_000;
        checkConstantRate(videoPackets(tools, ts), rate, 90_000, 0.03, buffer, frame, parameterSetBytes(m.avcC()));

        // RTMP: the pusher remuxes the TS with -c copy to FLV; the filler must survive that
        Path flv = dir.resolve("padded.flv");
        tools.ffmpeg("-v", "error", "-i", ts.toString(), "-c", "copy", "-f", "flv", flv.toString()).run();
        Path raw = dir.resolve("padded.h264");
        tools.ffmpeg("-v", "error", "-i", flv.toString(), "-c:v", "copy", "-bsf:v", "h264_mp4toannexb", "-an",
                "-f", "h264", raw.toString()).run();
        assertTrue(fillerNals(Files.readAllBytes(raw)) > r.videoFrames() / 2, "filler NAL units kept in FLV");
        checkConstantRate(videoPackets(tools, flv), rate, 1000, 0.03, buffer, frame, parameterSetBytes(m.avcC()));
    }

    /**
     * An audio item and filler cards only (what an audio-only room's output plays) at a stream rate
     * well below the encode cap: still cards are small, so it is constant as well.
     */
    @Test
    @Timeout(300)
    void cardsOnlyBelowTheCap() throws Exception {
        Tools tools = HarnessFixture.tools();
        OutputEncoding profile = OutputEncoding.defaultFor(OutputProfile.P720_30);
        JobLog log = new JobLog();
        Path media = Files.createDirectories(dir.resolve("media"));
        Path audio = Files.createDirectories(media.resolve("audio1"));
        HarnessFixture.encode(tools, audio.resolve(IngestPipeline.CARD), "rgbtestsrc", 2, false);
        HarnessFixture.encodeAudio(tools, audio.resolve(IngestPipeline.AUDIO), HarnessFixture.AUDIO_MS / 1000.0);
        SampleIndex index = IndexBuilder.build(tools, profile, audio.resolve(IngestPipeline.CARD),
                audio.resolve(IngestPipeline.AUDIO), audio.resolve(SampleIndex.FILE_NAME), log);
        Map<Filler, CardGop> fillers = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            Path card = dir.resolve(f.name().toLowerCase() + ".mp4");
            HarnessFixture.encode(tools, card, "smptebars", 2, false);
            fillers.put(f, CardGop.load(tools, profile, card, log));
        }
        HarnessFixture.Media m = new HarnessFixture.Media(media, index.avcC(), fillers,
                SilenceFrame.generate(tools, log).frame());

        Entry a = new Entry("a", "audio1", false, false, HarnessFixture.AUDIO_MS);
        Entry b = new Entry("b", "audio1", false, false, HarnessFixture.AUDIO_MS);
        ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS)
                .at(0).add(a, b).at(3_000).pause().at(6_000).play().at(9_000).seek(1_000);
        long rate = 800_000;
        assertTrue(rate < profile.videoBitrateBps());
        Path ts = dir.resolve("cards.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, script, 40_000, rate, ts);
        List<String> kinds = HarnessFixture.kinds(r);
        assertTrue(kinds.containsAll(List.of("audio", "paused", "idle")), kinds.toString());
        // (no assertDecodesClean here: ffmpeg's null muxer reports "non monotonically increasing dts"
        // for this script with or without padding, 2026-11-10; the TS timestamps themselves are checked)
        HarnessFixture.assertPackets(tools, ts, r);
        // still cards never run ahead: every unit on the rate
        double frame = rate / 8.0 * ProgramSource.AUDIO_FRAME_TICKS / 90_000 + 1_000;
        checkConstantRate(videoPackets(tools, ts), rate, 90_000, 0.02, 2_000, frame, parameterSetBytes(m.avcC()));
    }
}
