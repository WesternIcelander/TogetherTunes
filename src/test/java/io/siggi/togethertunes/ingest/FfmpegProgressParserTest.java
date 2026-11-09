package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegProgressParserTest {

    private static List<FfmpegProgress> parse(String text) {
        List<FfmpegProgress> out = new ArrayList<>();
        FfmpegProgressParser p = new FfmpegProgressParser(out::add);
        text.lines().forEach(p::line);
        return out;
    }

    @Test
    void realFfmpeg6Blocks() {
        List<FfmpegProgress> out = parse("""
                frame=0
                fps=0.00
                stream_0_0_q=0.0
                bitrate=N/A
                total_size=0
                out_time_us=N/A
                out_time_ms=N/A
                out_time=N/A
                dup_frames=0
                drop_frames=0
                speed=N/A
                progress=continue
                frame=131
                fps=0.00
                stream_0_0_q=28.0
                bitrate= 245.3kbits/s
                total_size=131120
                out_time_us=4276000
                out_time_ms=4276000
                out_time=00:00:04.276000
                dup_frames=0
                drop_frames=0
                speed=8.52x
                progress=continue
                frame=300
                fps=287.11
                stream_0_0_q=-1.0
                bitrate= 300.0kbits/s
                total_size=375000
                out_time_us=10000000
                out_time_ms=10000000
                out_time=00:00:10.000000
                dup_frames=0
                drop_frames=0
                speed=9.57x
                progress=end
                """);
        assertEquals(List.of(
                new FfmpegProgress(0, 0.0, -1, 0, -1, false),
                new FfmpegProgress(131, 0.0, 4_276_000, 131_120, 8.52, false),
                new FfmpegProgress(300, 287.11, 10_000_000, 375_000, 9.57, true)), out);
        assertEquals(0.0, out.get(0).fraction(10_000_000));
        assertEquals(0.4276, out.get(1).fraction(10_000_000), 1e-9);
        assertEquals(1.0, out.get(2).fraction(10_000_000));
    }

    @Test
    void audioOnlyBlocksAndNegativeStart() {
        List<FfmpegProgress> out = parse("""
                bitrate=N/A
                total_size=N/A
                out_time_us=-9223372036854775807
                out_time_ms=-9223372036854775807
                out_time=-577014:32:22.775808
                speed=N/A
                progress=continue
                total_size=48000
                out_time_us=3000000
                speed=12x
                progress=continue
                """);
        assertEquals(new FfmpegProgress(-1, -1, -1, -1, -1, false), out.get(0));
        assertEquals(new FfmpegProgress(-1, -1, 3_000_000, 48_000, 12, false), out.get(1));
    }

    @Test
    void fallsBackToOutTimeMsThenOutTime() {
        assertEquals(2_500_000, parse("out_time_ms=2500000\nprogress=continue").get(0).outTimeUs());
        assertEquals(3_723_500_000L, parse("out_time=01:02:03.5\nprogress=continue").get(0).outTimeUs());
        assertEquals(-1, parse("out_time=garbage\nprogress=continue").get(0).outTimeUs());
    }

    @Test
    void valuesDoNotLeakIntoTheNextBlock() {
        List<FfmpegProgress> out = parse("frame=5\nout_time_us=100\nprogress=continue\nprogress=continue");
        assertEquals(new FfmpegProgress(-1, -1, -1, -1, -1, false), out.get(1));
    }

    @Test
    void ignoresJunk() {
        List<FfmpegProgress> out = parse("hello\n=x\nunknown_key=1\nframe=abc\nprogress=continue");
        assertEquals(List.of(new FfmpegProgress(-1, -1, -1, -1, -1, false)), out);
    }

    @Test
    void fractionIsClampedAndHandlesUnknownDuration() {
        FfmpegProgress p = new FfmpegProgress(1, 1, 12_000_000, 1, 1, false);
        assertEquals(1.0, p.fraction(10_000_000));
        assertEquals(0.0, p.fraction(0));
        assertEquals(0.0, p.fraction(-1));
    }

    @Test
    void clockParsing() {
        assertEquals(0, FfmpegProgressParser.parseClock("00:00:00"));
        assertEquals(59_999_999, FfmpegProgressParser.parseClock("00:00:59.9999999"));
        assertEquals(-1, FfmpegProgressParser.parseClock("00:60:00"));
        assertEquals(-1, FfmpegProgressParser.parseClock("N/A"));
        assertEquals(-1, FfmpegProgressParser.parseClock("00:00:01."));
    }
}
