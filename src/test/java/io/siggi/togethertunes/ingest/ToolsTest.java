package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Tools} against the real ffmpeg and ffprobe, when they are installed (skipped otherwise;
 * the dev machine has them, see PLAN.md).
 */
@Timeout(120)
class ToolsTest {
    @TempDir
    Path dir;

    private static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String d : path.split(File.pathSeparator)) {
            Path p = Path.of(d, name);
            if (!d.isEmpty() && Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    @Test
    void addsDefaultArgumentsAndLimits() {
        Tools tools = new Tools("/x/ffmpeg", "/x/ffprobe", "/x/yt-dlp");
        assertEquals(List.of("/x/ffmpeg", "-hide_banner", "-nostdin", "-i", "a"), tools.ffmpeg("-i", "a").command());
        assertEquals(List.of("/x/ffmpeg", "-progress", "pipe:1", "-nostats", "-hide_banner", "-nostdin", "-i", "a"),
                tools.ffmpeg("-i", "a").ffmpegProgress(p -> { }).command());
        assertEquals(List.of("/x/ffprobe", "-hide_banner", "a"), tools.ffprobe("a").command());
        assertEquals(List.of("/x/yt-dlp", "-J", "u"), tools.ytdlp("-J", "u").command());
        assertTrue(tools.hasFfmpeg() && tools.hasFfprobe() && tools.hasYtdlp());
        Tools none = new Tools(null, null, null);
        assertFalse(none.hasFfmpeg() || none.hasFfprobe() || none.hasYtdlp());
        ToolException e = assertThrows(ToolException.class, () -> none.ytdlp("-U").run());
        assertEquals(ToolException.Kind.NOT_AVAILABLE, e.kind());
    }

    @Test
    void realFfmpegProgressAndFfprobeJson() throws Exception {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        Tools tools = new Tools(ffmpeg, ffprobe, null);
        JobLog log = new JobLog();
        Path out = dir.resolve("clip.mp4");
        List<FfmpegProgress> progress = Collections.synchronizedList(new ArrayList<>());
        ToolResult r = tools.ffmpeg("-f", "lavfi", "-i", "testsrc=size=320x180:rate=30:duration=3",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                        "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-shortest", out.toString())
                .ffmpegProgress(progress::add).log(log).run();
        assertEquals(0, r.exitCode());
        assertTrue(Files.size(out) > 0);
        assertFalse(progress.isEmpty());
        FfmpegProgress last = progress.get(progress.size() - 1);
        assertTrue(last.end());
        assertEquals(90, last.frame());
        assertTrue(Math.abs(last.outTimeUs() - 3_000_000) < 100_000, "out_time " + last.outTimeUs());
        for (int i = 0; i < progress.size() - 1; i++) {
            assertFalse(progress.get(i).end());
        }
        assertTrue(log.tail().startsWith("$ " + ffmpeg + " -progress pipe:1 -nostats -hide_banner -nostdin "), log.tail());
        assertTrue(log.tail().contains("[ffmpeg] exit 0 after "));

        ToolResult probe = tools.ffprobe("-v", "error", "-print_format", "json", "-show_format", "-show_streams",
                out.toString()).stdoutToString(1 << 20).run();
        JsonObject json = JsonParser.parseString(probe.stdoutString()).getAsJsonObject();
        assertEquals(2, json.getAsJsonArray("streams").size());
        assertEquals(3.0, json.getAsJsonObject("format").get("duration").getAsDouble(), 0.1);

        ToolException e = assertThrows(ToolException.class,
                () -> tools.ffprobe("-v", "error", dir.resolve("missing.mp4").toString()).run());
        assertEquals(ToolException.Kind.EXIT_CODE, e.kind());
        assertTrue(e.stderrTail().contains("No such file or directory"), e.stderrTail());
    }
}
