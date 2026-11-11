package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.ingest.Tools;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packager golden-file test ({@code docs/milestones.md#testing-strategy}): a two-minute scripted
 * session (two {@link HarnessFixture#cycle} cycles) through the {@link OfflineHarness}, written to a
 * {@code .ts} file and checked with ffmpeg and ffprobe: decodes without a message, PTS strictly
 * increasing, DTS = PTS, audio deltas exactly 1920, every frame present, and an IDR at the start of
 * every unit and nowhere else. The session's structure (ffprobe's program and stream description,
 * the frame and unit counts, and the harness log: every unit's PTS range and source, every timeline
 * change) must match {@code src/test/resources/golden/packager-session.txt}.
 *
 * <p>The golden file holds no bitstream bytes or sizes, which depend on the x264 build; it holds
 * what the packager decides. After an intended change to the packager's timing rules, regenerate it
 * with {@code mvn test -Dtest=PackagerGoldenFileTest -Dtt.golden.update=true} and review the diff.
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI ({@code CI=true} or
 * {@code -Dtt.requireMediaTools=true}), where it fails.
 */
class PackagerGoldenFileTest {
    static final String GOLDEN = "golden/packager-session.txt";
    static final int MINUTES = 2;
    private static final Pattern UNIT = Pattern.compile("unit \\d+ pts (\\d+)\\.\\.(\\d+) .*");

    @TempDir
    Path dir;

    @Test
    @Timeout(600)
    void packagerSessionMatchesTheGoldenFile() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        Path out = dir.resolve("golden.ts");
        OfflineHarness.Result r = HarnessFixture.run(tools, m, MINUTES, out);

        HarnessFixture.assertDecodesClean(tools, out);
        HarnessFixture.assertPackets(tools, out, r);
        assertEquals(unitStarts(r), keyframes(tools, out), "an IDR at the start of every unit and nowhere else");

        String actual = describe(tools, out, r);
        if (Boolean.getBoolean("tt.golden.update")) {
            Path golden = Path.of("src", "test", "resources", GOLDEN);
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected;
        try (InputStream in = PackagerGoldenFileTest.class.getClassLoader().getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN + " missing; create it with -Dtt.golden.update=true");
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        compare(GOLDEN, expected, actual);
    }

    /** The file's structure, as stored in the golden file. */
    static String describe(Tools tools, Path out, OfflineHarness.Result r) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Packager golden file: PackagerGoldenFileTest, ").append(MINUTES)
                .append(" scripted minutes (HarnessFixture.cycle) through OfflineHarness.\n");
        sb.append("# Regenerate after an intended change: mvn test -Dtest=PackagerGoldenFileTest -Dtt.golden.update=true\n");
        sb.append("[ffprobe]\n");
        String probe = tools.ffprobe("-v", "error",
                "-show_entries", "program=program_id,pmt_pid,pcr_pid:stream=index,id,codec_type,codec_name,profile,"
                        + "width,height,pix_fmt,r_frame_rate,has_b_frames,sample_rate,channels",
                "-of", "compact", out.toString()).stdoutToString(1 << 20).run().stdoutString();
        for (String line : probe.split("\n")) {
            if (!line.isBlank()) {
                sb.append(line.trim()).append('\n');
            }
        }
        sb.append("[harness]\n");
        sb.append("units=").append(r.units()).append(" videoFrames=").append(r.videoFrames())
                .append(" audioFrames=").append(r.audioFrames()).append(" endPts=").append(r.endPts())
                .append(" durationMs=").append(r.durationMs()).append('\n');
        sb.append("[log]\n");
        for (String line : r.log()) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** Compares two golden texts line by line, ignoring comments and blank lines. */
    static void compare(String golden, String expected, String actual) {
        List<String> e = lines(expected);
        List<String> a = lines(actual);
        for (int i = 0; i < Math.min(e.size(), a.size()); i++) {
            if (!e.get(i).equals(a.get(i))) {
                fail("differs from " + golden + " at line " + (i + 1) + " (ignoring comments)\nexpected: " + e.get(i)
                        + "\nactual:   " + a.get(i) + "\nIf the change is intended, regenerate with "
                        + "-Dtt.golden.update=true and review the diff.");
            }
        }
        assertEquals(e.size(), a.size(), "line count differs from " + golden);
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        for (String line : s.split("\n")) {
            line = line.stripTrailing();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    static List<Long> unitStarts(OfflineHarness.Result r) {
        List<Long> starts = new ArrayList<>();
        for (String line : r.log()) {
            Matcher mt = UNIT.matcher(line);
            if (mt.matches()) {
                starts.add(Long.parseLong(mt.group(1)));
            }
        }
        assertEquals(r.units(), starts.size(), "one log line per unit");
        return starts;
    }

    static List<Long> keyframes(Tools tools, Path out) throws Exception {
        String packets = tools.ffprobe("-v", "error", "-select_streams", "v", "-show_entries", "packet=pts,flags",
                "-of", "csv=p=0", out.toString()).stdoutToString(1 << 28).run().stdoutString();
        List<Long> k = new ArrayList<>();
        for (String line : packets.split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length >= 2 && f[1].startsWith("K")) {
                k.add(Long.parseLong(f[0]));
            }
        }
        return k;
    }
}
