package io.siggi.togethertunes.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One run of an external tool (ffmpeg, ffprobe, yt-dlp) as a subprocess. Made by {@link Tools},
 * configured with the fluent setters, then {@link #run()}. Not reusable.
 *
 * <ul>
 *   <li><b>Timeouts:</b> an overall limit ({@link #timeout}) and an idle limit
 *       ({@link #idleTimeout}: nothing written to stdout or stderr for that long). On either, or
 *       when the calling thread is interrupted, the process and all its descendants are
 *       terminated (SIGTERM, then SIGKILL after {@link #KILL_GRACE}).</li>
 *   <li><b>stderr</b> is read line by line ({@code \n} or {@code \r} ends a line) into the
 *       {@link JobLog} given with {@link #log}, between a {@code $ command} line and an exit
 *       line; the last {@link #STDERR_TAIL_LINES} lines also go into the result and into
 *       {@link ToolException} messages.</li>
 *   <li><b>stdout</b> is discarded by default, or captured ({@link #stdoutToString}), written to a
 *       file ({@link #stdoutToFile}), handed over line by line ({@link #stdoutLines}), or parsed
 *       as ffmpeg {@code -progress pipe:1} output ({@link #ffmpegProgress}, which also adds the
 *       {@code -progress pipe:1 -nostats} arguments).</li>
 *   <li><b>stdin</b> is closed at once (ffmpeg would otherwise read keyboard commands from it).</li>
 *   <li><b>Secrets</b> ({@link #secret}), e.g. an RTMP stream key in an output URL, are replaced
 *       by {@code <redacted>} in everything logged and in exception messages (invariant 6).</li>
 * </ul>
 */
public final class ToolCall {
    /** Lines of stderr kept for the result and exception messages. */
    public static final int STDERR_TAIL_LINES = 20;
    /** Time between SIGTERM and SIGKILL when a tool is stopped. */
    public static final Duration KILL_GRACE = Duration.ofSeconds(3);
    /** Stdout lines ({@link #stdoutLines}, {@link #ffmpegProgress}) longer than this fail the run. */
    public static final int MAX_STDOUT_LINE_BYTES = 1024 * 1024;
    /** Longer stderr lines are cut (the rest of the line is skipped). */
    static final int MAX_STDERR_LINE_BYTES = 8 * 1024;
    static final String REDACTED = "<redacted>";
    /** How often the waiting thread checks the time limits. */
    private static final long POLL_MS = 100;

    private enum StdoutMode { DISCARD, STRING, FILE, LINES, PROGRESS }

    private final String tool;
    private final String executable;
    private final List<String> args;
    private Duration timeout;
    private Duration idleTimeout;
    private Path workingDir;
    private JobLog log;
    private final List<String> secrets = new ArrayList<>();
    private boolean allowFailure;
    private StdoutMode stdoutMode = StdoutMode.DISCARD;
    private int stdoutMaxBytes;
    private Path stdoutFile;
    private Consumer<String> stdoutConsumer;
    private Consumer<FfmpegProgress> progressConsumer;
    private boolean used;

    /**
     * @param tool       name for logs and messages, e.g. {@code ffmpeg}
     * @param executable absolute path of the tool, or {@code null} if it isn't available (then
     *                   {@link #run()} fails with {@link ToolException.Kind#NOT_AVAILABLE})
     * @param args       arguments after the executable
     */
    public ToolCall(String tool, String executable, List<String> args) {
        this.tool = Objects.requireNonNull(tool);
        this.executable = executable;
        this.args = List.copyOf(args);
    }

    /** Overall time limit; {@code null} for none. */
    public ToolCall timeout(Duration timeout) {
        this.timeout = positiveOrNull(timeout, "timeout");
        return this;
    }

    /** Idle limit (no stdout or stderr output); {@code null} for none. */
    public ToolCall idleTimeout(Duration idleTimeout) {
        this.idleTimeout = positiveOrNull(idleTimeout, "idleTimeout");
        return this;
    }

    private static Duration positiveOrNull(Duration d, String what) {
        if (d != null && (d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException(what + " must be positive");
        }
        return d;
    }

    /** Working directory of the process (default: the server's). */
    public ToolCall workingDir(Path dir) {
        this.workingDir = dir;
        return this;
    }

    /** Where the command line, stderr and the exit status are logged. */
    public ToolCall log(JobLog log) {
        this.log = log;
        return this;
    }

    /** A string to redact from everything logged (ignored if {@code null} or shorter than 4 chars). */
    public ToolCall secret(String secret) {
        if (secret != null && secret.length() >= 4) {
            secrets.add(secret);
            secrets.sort((a, b) -> b.length() - a.length());
        }
        return this;
    }

    /** Don't fail on a non-zero exit code; the caller checks {@link ToolResult#exitCode()}. */
    public ToolCall allowFailure() {
        this.allowFailure = true;
        return this;
    }

    /** Captures stdout into {@link ToolResult#stdout()}; more than {@code maxBytes} fails the run. */
    public ToolCall stdoutToString(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes");
        }
        setStdoutMode(StdoutMode.STRING);
        this.stdoutMaxBytes = maxBytes;
        return this;
    }

    /** Writes stdout to {@code file} (replacing it). Doesn't count as activity for the idle limit. */
    public ToolCall stdoutToFile(Path file) {
        setStdoutMode(StdoutMode.FILE);
        this.stdoutFile = Objects.requireNonNull(file);
        return this;
    }

    /**
     * Hands every stdout line (UTF-8, without the line break; {@code \n}, {@code \r\n} or a lone
     * {@code \r} ends a line) to {@code consumer}, on a reader thread. If it throws, the tool is
     * stopped and the run fails with {@link ToolException.Kind#OUTPUT_FAILED}.
     */
    public ToolCall stdoutLines(Consumer<String> consumer) {
        setStdoutMode(StdoutMode.LINES);
        this.stdoutConsumer = Objects.requireNonNull(consumer);
        return this;
    }

    /**
     * Adds {@code -progress pipe:1 -nostats} in front of the arguments and parses stdout as
     * ffmpeg progress blocks, reporting each to {@code consumer} on a reader thread (ffmpeg
     * writes one about every 0.5 s, which also keeps the idle limit from firing).
     */
    public ToolCall ffmpegProgress(Consumer<FfmpegProgress> consumer) {
        setStdoutMode(StdoutMode.PROGRESS);
        this.progressConsumer = Objects.requireNonNull(consumer);
        return this;
    }

    private void setStdoutMode(StdoutMode mode) {
        if (stdoutMode != StdoutMode.DISCARD) {
            throw new IllegalStateException("stdout is already used for " + stdoutMode);
        }
        stdoutMode = mode;
    }

    /** The full command line that {@link #run()} starts (with the executable, or the tool name if unavailable). */
    public List<String> command() {
        List<String> cmd = new ArrayList<>(args.size() + 4);
        cmd.add(executable != null ? executable : tool);
        if (stdoutMode == StdoutMode.PROGRESS) {
            cmd.add("-progress");
            cmd.add("pipe:1");
            cmd.add("-nostats");
        }
        cmd.addAll(args);
        return cmd;
    }

    /**
     * Runs the tool and waits for it.
     *
     * @throws ToolException        if the tool isn't available, can't start, exits non-zero (unless
     *                              {@link #allowFailure()}), hits a time limit or its output fails
     * @throws InterruptedException if the calling thread is interrupted; the tool is stopped first
     */
    public ToolResult run() throws ToolException, InterruptedException {
        synchronized (this) {
            if (used) {
                throw new IllegalStateException("a ToolCall runs only once");
            }
            used = true;
        }
        if (executable == null) {
            throw new ToolException(tool, ToolException.Kind.NOT_AVAILABLE,
                    "not available (not configured and not found on PATH)", -1, "", null);
        }
        List<String> cmd = command();
        logLine("$ " + redact(quote(cmd)));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        switch (stdoutMode) {
            case DISCARD -> pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            case FILE -> pb.redirectOutput(stdoutFile.toFile());
            default -> pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        }
        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        long startNanos = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            String reason = "could not start: " + redact(String.valueOf(e.getMessage()));
            logLine("[" + tool + "] " + reason);
            throw new ToolException(tool, ToolException.Kind.START_FAILED, reason, -1, "", e);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to close
        }

        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicReference<ToolException> outputFailure = new AtomicReference<>();
        Deque<String> stderrTail = new ArrayDeque<>();

        Thread errReader = Thread.ofVirtual().name(tool + "-stderr").start(() -> {
            try (InputStream in = process.getErrorStream()) {
                readLines(in, MAX_STDERR_LINE_BYTES, false, lastActivity, line -> {
                    String l = redact(line);
                    synchronized (stderrTail) {
                        stderrTail.addLast(l);
                        if (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                    logLine(l);
                });
            } catch (IOException | RuntimeException e) {
                // the process was killed, or its stderr broke; the exit status says what happened
            }
        });
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread outReader = null;
        if (stdoutMode == StdoutMode.STRING || stdoutMode == StdoutMode.LINES || stdoutMode == StdoutMode.PROGRESS) {
            outReader = Thread.ofVirtual().name(tool + "-stdout").start(() -> readStdout(process, captured, lastActivity, outputFailure));
        }

        boolean finished = false;
        try {
            ToolException limit = null;
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                long now = System.nanoTime();
                if (outputFailure.get() != null) {
                    break;
                }
                if (timeout != null && now - startNanos > timeout.toNanos()) {
                    limit = new ToolException(tool, ToolException.Kind.TIMEOUT,
                            "timed out after " + seconds(timeout.toMillis()), -1, null, null);
                    break;
                }
                if (idleTimeout != null && now - lastActivity.get() > idleTimeout.toNanos()) {
                    limit = new ToolException(tool, ToolException.Kind.IDLE_TIMEOUT,
                            "no output for " + seconds(idleTimeout.toMillis()), -1, null, null);
                    break;
                }
            }
            if (process.isAlive()) {
                destroyTree(process);
            }
            joinReaders(process, errReader, outReader);
            finished = true;
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            String tail = tail(stderrTail);

            ToolException failure = limit != null ? limit : outputFailure.get();
            if (failure != null) {
                logLine("[" + tool + "] stopped: " + failure.reason() + " (after " + seconds(elapsedMs) + ")");
                throw new ToolException(tool, failure.kind(), failure.reason(), -1, tail, failure.getCause());
            }
            int exit = process.exitValue();
            logLine("[" + tool + "] exit " + exit + " after " + seconds(elapsedMs));
            if (exit != 0 && !allowFailure) {
                throw new ToolException(tool, ToolException.Kind.EXIT_CODE, "exit code " + exit, exit, tail, null);
            }
            return new ToolResult(exit, captured.toByteArray(), tail, elapsedMs);
        } catch (InterruptedException e) {
            logLine("[" + tool + "] interrupted, stopping");
            throw e;
        } finally {
            if (!finished) {
                destroyTree(process);
                errReader.interrupt();
                if (outReader != null) {
                    outReader.interrupt();
                }
            }
        }
    }

    private void readStdout(Process process, ByteArrayOutputStream captured, AtomicLong lastActivity,
                            AtomicReference<ToolException> failure) {
        try (InputStream in = process.getInputStream()) {
            switch (stdoutMode) {
                case STRING -> {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        lastActivity.set(System.nanoTime());
                        if (captured.size() + n > stdoutMaxBytes) {
                            failure.compareAndSet(null, new ToolException(tool, ToolException.Kind.OUTPUT_TOO_LARGE,
                                    "stdout larger than " + stdoutMaxBytes + " bytes", -1, null, null));
                            return;
                        }
                        captured.write(buf, 0, n);
                    }
                }
                case LINES -> readLines(in, MAX_STDOUT_LINE_BYTES, true, lastActivity, stdoutConsumer);
                case PROGRESS -> {
                    FfmpegProgressParser parser = new FfmpegProgressParser(progressConsumer);
                    readLines(in, MAX_STDOUT_LINE_BYTES, true, lastActivity, parser::line);
                }
                default -> throw new IllegalStateException();
            }
        } catch (LineTooLongException e) {
            failure.compareAndSet(null, new ToolException(tool, ToolException.Kind.OUTPUT_TOO_LARGE,
                    "stdout line longer than " + MAX_STDOUT_LINE_BYTES + " bytes", -1, null, null));
        } catch (IOException e) {
            failure.compareAndSet(null, new ToolException(tool, ToolException.Kind.OUTPUT_FAILED,
                    "reading stdout failed: " + redact(String.valueOf(e.getMessage())), -1, null, e));
        } catch (RuntimeException e) {
            failure.compareAndSet(null, new ToolException(tool, ToolException.Kind.OUTPUT_FAILED,
                    "handling stdout failed: " + redact(String.valueOf(e)), -1, null, e));
        }
    }

    /** Waits for the reader threads; if a stray descendant keeps a pipe open, closes it. */
    private static void joinReaders(Process process, Thread errReader, Thread outReader) throws InterruptedException {
        long waitMs = 2_000;
        if (!errReader.join(Duration.ofMillis(waitMs))) {
            closeQuietly(process.getErrorStream());
            errReader.join(Duration.ofMillis(waitMs));
        }
        if (outReader != null && !outReader.join(Duration.ofMillis(waitMs))) {
            closeQuietly(process.getInputStream());
            outReader.join(Duration.ofMillis(waitMs));
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** SIGTERM to the process and its descendants, SIGKILL to whatever is left after {@link #KILL_GRACE}. */
    static void destroyTree(Process process) {
        List<ProcessHandle> all = new ArrayList<>();
        process.descendants().forEach(all::add);
        all.add(process.toHandle());
        for (ProcessHandle h : all) {
            h.destroy();
        }
        long deadline = System.nanoTime() + KILL_GRACE.toNanos();
        for (ProcessHandle h : all) {
            long left = deadline - System.nanoTime();
            if (left > 0) {
                try {
                    h.onExit().get(left, TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    // still alive (or interrupted): killed below
                }
            }
            if (h.isAlive()) {
                h.destroyForcibly();
            }
        }
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class LineTooLongException extends IOException {
        LineTooLongException() {
            super("line too long");
        }
    }

    /**
     * Splits {@code in} into UTF-8 lines at {@code \n}, {@code \r\n} or {@code \r}, skipping empty
     * lines. A line longer than {@code maxLineBytes} either fails ({@code failOnLong}) or is cut.
     */
    static void readLines(InputStream in, int maxLineBytes, boolean failOnLong, AtomicLong lastActivity,
                          Consumer<String> sink) throws IOException {
        byte[] buf = new byte[8 * 1024];
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean skipping = false;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (lastActivity != null) {
                lastActivity.set(System.nanoTime());
            }
            int start = 0;
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (b == '\n' || b == '\r') {
                    if (!skipping) {
                        line.write(buf, start, i - start);
                    }
                    emit(line, sink);
                    skipping = false;
                    start = i + 1;
                } else if (!skipping && line.size() + (i - start) >= maxLineBytes) {
                    if (failOnLong) {
                        throw new LineTooLongException();
                    }
                    line.write(buf, start, i - start);
                    skipping = true;
                }
            }
            if (!skipping) {
                line.write(buf, start, n - start);
            }
        }
        emit(line, sink);
    }

    private static void emit(ByteArrayOutputStream line, Consumer<String> sink) {
        if (line.size() > 0) {
            String s = line.toString(StandardCharsets.UTF_8);
            line.reset();
            sink.accept(s);
        }
    }

    private void logLine(String line) {
        if (log != null) {
            log.append(line);
        }
    }

    String redact(String s) {
        for (String secret : secrets) {
            s = s.replace(secret, REDACTED);
        }
        return s;
    }

    private static String tail(Deque<String> lines) {
        synchronized (lines) {
            return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        }
    }

    static String seconds(long ms) {
        return String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    /** The command line for the log, with arguments quoted where a shell would need it. */
    static String quote(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String a : cmd) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            if (!a.isEmpty() && a.chars().allMatch(c -> Character.isLetterOrDigit(c) || "-_./:=,+@%".indexOf(c) >= 0)) {
                sb.append(a);
            } else {
                sb.append('\'').append(a.replace("'", "'\\''")).append('\'');
            }
        }
        return sb.toString();
    }
}
