package io.siggi.togethertunes.ingest;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The admin's "Update yt-dlp" button ({@code docs/library-and-ingest.md}: "Keep yt-dlp up to date"):
 * runs {@code yt-dlp -U} in the background, one run at a time, and keeps the last run's outcome and
 * output in memory (a restart forgets it). Also reports the installed version
 * ({@code yt-dlp --version}, cached until the next update).
 *
 * <p>{@code -U} only works for yt-dlp's own release binaries and zipapp; a pip or package-manager
 * install refuses and says how to update instead, which the admin sees in the output.
 */
public final class YtdlpUpdater {
    /** Overall limit for {@code yt-dlp -U} (it downloads one release file). */
    public static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(5);
    /** Overall limit for {@code yt-dlp --version}. */
    public static final Duration VERSION_TIMEOUT = Duration.ofSeconds(30);
    /** Characters of output kept from a run. */
    static final int LOG_MAX_CHARS = 32 * 1024;

    /** Where a run is. */
    public enum State {
        RUNNING, SUCCEEDED, FAILED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * A snapshot of the current or last run.
     *
     * @param finishedAt    epoch ms, or null while running
     * @param exitCode      yt-dlp's exit code, or null (running, or it didn't run to the end)
     * @param versionBefore the version before the run, or null if unknown
     * @param versionAfter  the version after the run, or null (running, or unknown)
     * @param log           the command line, yt-dlp's output and the outcome so far
     */
    public record Status(State state, long startedAt, Long finishedAt, Integer exitCode, String versionBefore,
                         String versionAfter, String log) {
    }

    private final Tools tools;
    private final Clock clock;
    private final Executor executor;
    private String version; // cached; null = not known yet
    private Run current;

    private static final class Run {
        final long startedAt;
        final String versionBefore;
        final JobLog log = new JobLog(LOG_MAX_CHARS, null);
        State state = State.RUNNING;
        Long finishedAt;
        Integer exitCode;
        String versionAfter;

        Run(long startedAt, String versionBefore) {
            this.startedAt = startedAt;
            this.versionBefore = versionBefore;
        }
    }

    /** Runs updates on a new virtual thread each. */
    public YtdlpUpdater(Tools tools, Clock clock) {
        this(tools, clock, task -> Thread.ofVirtual().name("yt-dlp-update").start(task));
    }

    YtdlpUpdater(Tools tools, Clock clock, Executor executor) {
        this.tools = Objects.requireNonNull(tools);
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
    }

    /** Whether yt-dlp is installed (configured or found on PATH). */
    public boolean available() {
        return tools.hasYtdlp();
    }

    /**
     * The installed version ({@code yt-dlp --version}), or empty if yt-dlp isn't available or didn't
     * answer. Cached once known; an update clears it.
     */
    public Optional<String> version() throws InterruptedException {
        synchronized (this) {
            if (version != null) {
                return Optional.of(version);
            }
        }
        if (!available()) {
            return Optional.empty();
        }
        String v;
        try {
            v = tools.ytdlp("--version").timeout(VERSION_TIMEOUT).stdoutToString(1024).run().stdoutString().strip();
        } catch (ToolException e) {
            return Optional.empty();
        }
        if (v.isEmpty() || v.contains("\n")) {
            return Optional.empty();
        }
        synchronized (this) {
            version = v;
        }
        return Optional.of(v);
    }

    /** The current or last run, if there was one since the server started. */
    public synchronized Optional<Status> status() {
        return Optional.ofNullable(current).map(YtdlpUpdater::snapshot);
    }

    private static Status snapshot(Run r) {
        return new Status(r.state, r.startedAt, r.finishedAt, r.exitCode, r.versionBefore, r.versionAfter,
                r.log.tail());
    }

    /**
     * Starts {@code yt-dlp -U} in the background, unless a run is going on.
     *
     * @return the new run, or empty if one is already running
     * @throws IllegalStateException if yt-dlp isn't available
     */
    public Optional<Status> start() throws InterruptedException {
        if (!available()) {
            throw new IllegalStateException("yt-dlp isn't available");
        }
        String before = version().orElse(null);
        Run run;
        synchronized (this) {
            if (current != null && current.state == State.RUNNING) {
                return Optional.empty();
            }
            run = new Run(clock.millis(), before);
            current = run;
        }
        Status started = snapshot(run);
        executor.execute(() -> update(run));
        return Optional.of(started);
    }

    private void update(Run run) {
        State state = State.FAILED;
        Integer exitCode = null;
        try {
            ToolResult result = tools.ytdlp("-U").timeout(UPDATE_TIMEOUT).allowFailure().log(run.log)
                    .stdoutLines(run.log::append).run();
            exitCode = result.exitCode();
            state = exitCode == 0 ? State.SUCCEEDED : State.FAILED;
        } catch (ToolException e) {
            run.log.append("Failed: " + e.reason());
        } catch (InterruptedException e) {
            run.log.append("Interrupted");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            run.log.append("Failed: " + e);
        }
        synchronized (this) {
            version = null;
        }
        String after = null;
        if (!Thread.currentThread().isInterrupted()) {
            try {
                after = version().orElse(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            run.exitCode = exitCode;
            run.versionAfter = after;
            run.finishedAt = clock.millis();
            run.state = state;
        }
    }
}
