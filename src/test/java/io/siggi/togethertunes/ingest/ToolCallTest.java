package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@link ToolCall} against small shell scripts standing in for the real tools. */
@Timeout(60)
class ToolCallTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void unixOnly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(File.separatorChar == '/', "needs /bin/sh");
    }

    private Path script(String body) throws IOException {
        Path p = Files.createTempFile(dir, "tool", ".sh");
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        assertTrue(p.toFile().setExecutable(true));
        return p;
    }

    private ToolCall call(String body, String... args) throws IOException {
        return new ToolCall("faketool", script(body).toString(), List.of(args));
    }

    @Test
    void capturesStdoutAndLogsCommandStderrAndExit() throws Exception {
        JobLog log = new JobLog();
        ToolResult r = call("echo '{\"a\":1}'; echo 'warning: x' >&2; echo \"args: $*\" >&2", "-i", "a b.mkv")
                .stdoutToString(1024).log(log).run();
        assertEquals(0, r.exitCode());
        assertEquals("{\"a\":1}\n", r.stdoutString());
        assertEquals("warning: x\nargs: -i a b.mkv\n", r.stderrTail());
        List<String> lines = log.tail().lines().toList();
        assertTrue(lines.get(0).startsWith("$ /"), lines.get(0));
        assertTrue(lines.get(0).endsWith(" -i 'a b.mkv'"), "arguments are quoted for the log: " + lines.get(0));
        assertEquals("warning: x", lines.get(1));
        assertEquals("args: -i a b.mkv", lines.get(2));
        assertTrue(lines.get(3).startsWith("[faketool] exit 0 after "), lines.get(3));
        assertEquals(4, lines.size());
    }

    @Test
    void nonZeroExitFailsWithStderrTail() throws Exception {
        JobLog log = new JobLog();
        ToolException e = assertThrows(ToolException.class,
                () -> call("i=0; while [ $i -lt 30 ]; do echo \"line $i\" >&2; i=$((i+1)); done; exit 3").log(log).run());
        assertEquals(ToolException.Kind.EXIT_CODE, e.kind());
        assertEquals(3, e.exitCode());
        assertEquals("faketool", e.tool());
        assertEquals("exit code 3", e.reason());
        List<String> tail = e.stderrTail().lines().toList();
        assertEquals(ToolCall.STDERR_TAIL_LINES, tail.size());
        assertEquals("line 10", tail.get(0));
        assertEquals("line 29", tail.get(tail.size() - 1));
        assertTrue(e.getMessage().startsWith("faketool: exit code 3\nline 10\n"), e.getMessage());
        assertTrue(log.tail().contains("line 0\n"), "the job log keeps all of stderr");
        assertTrue(log.tail().contains("[faketool] exit 3 after "));
    }

    @Test
    void allowFailureReturnsTheExitCode() throws Exception {
        ToolResult r = call("echo out; exit 1").stdoutToString(100).allowFailure().run();
        assertEquals(1, r.exitCode());
        assertEquals("out\n", r.stdoutString());
    }

    @Test
    void overallTimeoutKillsTheTool() throws Exception {
        JobLog log = new JobLog();
        long t0 = System.nanoTime();
        ToolException e = assertThrows(ToolException.class,
                () -> call("echo started >&2; while true; do echo tick; sleep 0.05; done")
                        .timeout(Duration.ofMillis(500)).idleTimeout(Duration.ofSeconds(30)).log(log).run());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ToolException.Kind.TIMEOUT, e.kind());
        assertEquals("timed out after 0.5 s", e.reason());
        assertTrue(e.stderrTail().contains("started"));
        assertTrue(ms >= 500 && ms < 5_000, "took " + ms + " ms");
        assertTrue(log.tail().contains("[faketool] stopped: timed out after 0.5 s"), log.tail());
    }

    @Test
    void idleTimeoutFiresOnlyWhenTheToolGoesQuiet() throws Exception {
        // Busy but chatty for 1 s: no idle timeout at 400 ms.
        ToolResult ok = call("i=0; while [ $i -lt 10 ]; do echo $i >&2; sleep 0.1; i=$((i+1)); done")
                .idleTimeout(Duration.ofMillis(400)).run();
        assertEquals(0, ok.exitCode());
        // Then silent: idle timeout.
        long t0 = System.nanoTime();
        ToolException e = assertThrows(ToolException.class,
                () -> call("echo hello >&2; sleep 30").idleTimeout(Duration.ofMillis(400)).run());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ToolException.Kind.IDLE_TIMEOUT, e.kind());
        assertEquals("no output for 0.4 s", e.reason());
        assertTrue(ms < 5_000, "took " + ms + " ms");
    }

    @Test
    void killsTheWholeProcessTree() throws Exception {
        AtomicReference<String> childPid = new AtomicReference<>();
        ToolException e = assertThrows(ToolException.class,
                () -> call("sleep 60 & echo $!; wait")
                        .stdoutLines(childPid::set).timeout(Duration.ofMillis(500)).run());
        assertEquals(ToolException.Kind.TIMEOUT, e.kind());
        long pid = Long.parseLong(childPid.get().trim());
        assertProcessGone(pid);
    }

    @Test
    void interruptStopsTheToolAndPropagates() throws Exception {
        AtomicReference<String> pidLine = new AtomicReference<>();
        Path s = script("echo $$; sleep 60");
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                new ToolCall("faketool", s.toString(), List.of()).stdoutLines(pidLine::set).run();
                outcome.complete(null);
            } catch (Throwable ex) {
                outcome.complete(ex);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pidLine.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        t.interrupt();
        Throwable ex = outcome.get(10, TimeUnit.SECONDS);
        assertInstanceOf(InterruptedException.class, ex);
        assertProcessGone(Long.parseLong(pidLine.get().trim()));
    }

    private static void assertProcessGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) {
                return;
            }
            Thread.sleep(20);
        }
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        throw new AssertionError("process " + pid + " is still running");
    }

    @Test
    void unavailableAndUnstartableTools() throws Exception {
        ToolException missing = assertThrows(ToolException.class, () -> new ToolCall("yt-dlp", null, List.of()).run());
        assertEquals(ToolException.Kind.NOT_AVAILABLE, missing.kind());
        assertTrue(missing.getMessage().startsWith("yt-dlp: not available"));

        JobLog log = new JobLog();
        ToolException bad = assertThrows(ToolException.class,
                () -> new ToolCall("ffmpeg", dir.resolve("no-such-ffmpeg").toString(), List.of()).log(log).run());
        assertEquals(ToolException.Kind.START_FAILED, bad.kind());
        assertTrue(log.tail().contains("[ffmpeg] could not start: "), log.tail());
    }

    @Test
    void stdoutLimit() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> call("while true; do echo 0123456789abcdef; done").stdoutToString(10_000).run());
        assertEquals(ToolException.Kind.OUTPUT_TOO_LARGE, e.kind());
        assertEquals("stdout larger than 10000 bytes", e.reason());
    }

    @Test
    void stdoutLinesSplitOnNewlinesAndCarriageReturns() throws Exception {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        call("printf 'a\\nb\\r\\nc\\rd\\n\\n\\xc3\\xa9e'").stdoutLines(lines::add).run();
        assertEquals(List.of("a", "b", "c", "d", "ée"), lines);
    }

    @Test
    void aThrowingConsumerStopsTheTool() throws Exception {
        ToolException e = assertThrows(ToolException.class,
                () -> call("while true; do echo x; sleep 0.01; done").stdoutLines(l -> {
                    throw new IllegalStateException("bad line");
                }).run());
        assertEquals(ToolException.Kind.OUTPUT_FAILED, e.kind());
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void stdoutToFile() throws Exception {
        Path out = dir.resolve("out.csv");
        call("echo 'packet,1'; echo 'packet,2'").stdoutToFile(out).run();
        assertEquals("packet,1\npacket,2\n", Files.readString(out));
    }

    @Test
    void stdoutModesAreExclusive() throws Exception {
        ToolCall c = call("true").stdoutToString(10);
        assertThrows(IllegalStateException.class, () -> c.stdoutLines(l -> { }));
    }

    @Test
    void runsOnlyOnce() throws Exception {
        ToolCall c = call("true");
        c.run();
        assertThrows(IllegalStateException.class, c::run);
    }

    @Test
    void workingDir() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("work"));
        ToolResult r = call("pwd").workingDir(sub).stdoutToString(4096).run();
        assertEquals(sub.toRealPath().toString(), r.stdoutString().trim());
    }

    @Test
    void secretsAreRedactedEverywhere() throws Exception {
        String key = "live_abcdef123456";
        String url = "rtmp://ingest.example/app/" + key;
        JobLog log = new JobLog();
        ToolException e = assertThrows(ToolException.class,
                () -> call("echo \"$1: Connection refused\" >&2; exit 1", url).secret(key).log(log).run());
        assertFalse(log.tail().contains(key), log.tail());
        assertFalse(e.getMessage().contains(key), e.getMessage());
        assertTrue(log.tail().contains("rtmp://ingest.example/app/<redacted>"), log.tail());
        assertTrue(e.stderrTail().contains("rtmp://ingest.example/app/<redacted>: Connection refused"));
    }

    @Test
    void ffmpegProgressAddsArgumentsAndParsesBlocks() throws Exception {
        List<FfmpegProgress> seen = Collections.synchronizedList(new ArrayList<>());
        JobLog log = new JobLog();
        ToolCall c = call("""
                echo "$*" >&2
                printf 'frame=30\\nfps=0.0\\nout_time_us=1000000\\nspeed=N/A\\nprogress=continue\\n'
                printf 'frame=60\\nfps=59.9\\nout_time_us=2000000\\ntotal_size=1234\\nspeed=1.99x\\nprogress=end\\n'
                """, "-i", "in.mkv").ffmpegProgress(seen::add).log(log);
        assertEquals(List.of("-progress", "pipe:1", "-nostats", "-i", "in.mkv"), c.command().subList(1, 6));
        c.run();
        assertTrue(log.tail().contains("\n-progress pipe:1 -nostats -i in.mkv\n"), log.tail());
        assertEquals(List.of(
                new FfmpegProgress(30, 0.0, 1_000_000, -1, -1, false),
                new FfmpegProgress(60, 59.9, 2_000_000, 1234, 1.99, true)), seen);
    }

    @Test
    void lineReaderCutsLongLinesOrFails() throws Exception {
        byte[] data = ("x".repeat(100) + "\nshort\n").getBytes(StandardCharsets.UTF_8);
        List<String> cut = new ArrayList<>();
        ToolCall.readLines(new ByteArrayInputStream(data), 10, false, null, cut::add);
        assertEquals(List.of("x".repeat(10), "short"), cut);
        assertThrows(IOException.class,
                () -> ToolCall.readLines(new ByteArrayInputStream(data), 10, true, null, l -> { }));
    }

    @Test
    void quoting() {
        assertEquals("ffmpeg -i in.mkv -vf scale=1280:720,format=yuv420p -metadata 'title=a b' 'it'\\''s.mp4' ''",
                ToolCall.quote(List.of("ffmpeg", "-i", "in.mkv", "-vf", "scale=1280:720,format=yuv420p",
                        "-metadata", "title=a b", "it's.mp4", "")));
    }

    @Test
    void stderrLinesAreHandedOverRedactedAndAThrowingConsumerIsIgnored() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolResult r = call("echo 'one secret-key-123' >&2; echo two >&2; echo out", "x")
                .secret("secret-key-123").stdoutToString(1024).stderrLines(line -> {
                    seen.add(line);
                    throw new IllegalStateException("ignored");
                }).run();
        assertEquals(0, r.exitCode());
        assertEquals("out\n", r.stdoutString());
        assertEquals(List.of("one <redacted>", "two"), seen);
    }

    @Test
    void stdoutStreamHandsOverBinaryOutput() throws Exception {
        AtomicReference<byte[]> got = new AtomicReference<>();
        ToolResult r = call("printf 'a\\000\\001\\377b'").stdoutStream(in -> got.set(in.readAllBytes())).run();
        assertEquals(0, r.exitCode());
        assertEquals(List.of(0x61, 0, 1, 0xFF, 0x62), toList(got.get()));
    }

    @Test
    void stdoutStreamHandlerThatReturnsEarlyLetsTheToolFinish() throws Exception {
        // much more output than a pipe buffer: the rest is drained, the tool isn't blocked
        ToolResult r = call("i=0; while [ $i -lt 2000 ]; do echo 0123456789012345678901234567890123456789; i=$((i+1)); done")
                .stdoutStream(in -> in.read()).run();
        assertEquals(0, r.exitCode());
    }

    @Test
    void stdoutStreamHandlerFailureStopsTheTool() throws Exception {
        IOException boom = new IOException("bad stream");
        long t0 = System.nanoTime();
        ToolException e = assertThrows(ToolException.class,
                () -> call("echo x; sleep 30").stdoutStream(in -> {
                    in.read();
                    throw boom;
                }).run());
        assertEquals(ToolException.Kind.OUTPUT_FAILED, e.kind());
        assertTrue(e.getCause() == boom, "the handler's exception is the cause");
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(20), "the tool was stopped");
    }

    private static List<Integer> toList(byte[] b) {
        List<Integer> l = new ArrayList<>();
        for (byte x : b) {
            l.add(x & 0xFF);
        }
        return l;
    }
}
