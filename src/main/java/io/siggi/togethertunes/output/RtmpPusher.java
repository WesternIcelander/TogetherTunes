package io.siggi.togethertunes.output;

import io.siggi.togethertunes.rooms.RoomOutputs;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;

/**
 * One room's RTMP push, v1 ({@code docs/program-output.md#rtmp-push-vrcdn-etc}): a supervised
 * {@code ffmpeg -f mpegts -i pipe:0 -c copy -f flv <url>/<key>} fed with the packager's units as one
 * more consumer. When ffmpeg exits (connection refused, network cut, the service hung up), the
 * consumer is too slow (its queue overflows), or the packager restarts, the attempt ends and the
 * next one starts after a backoff of 1, 2, 5, 10, then every 30 s; an attempt that stayed connected
 * for {@link #STABLE_MS} starts the backoff over. {@link #status()} gives the state with the last
 * line ffmpeg wrote to stderr.
 *
 * <p>"Connected" means ffmpeg reported progress ({@code -progress pipe:1}), which it only does once
 * the RTMP output is open. The stream key is a capability (invariant 6): it never appears in a log
 * or in the status; stderr lines have it replaced by {@link #maskKey}, and URL credentials masked
 * ({@link #maskUrlCredentials}). (It is on ffmpeg's command
 * line, so local users who can list processes can see it; ffmpeg has no other way to take it.)
 */
public final class RtmpPusher implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RtmpPusher.class.getName());

    /** Waits before the 1st, 2nd, … retry; the last one repeats. */
    public static final List<Long> BACKOFF_MS = List.of(1_000L, 2_000L, 5_000L, 10_000L, 30_000L);
    /** An attempt connected at least this long resets the backoff. */
    public static final long STABLE_MS = 60_000;
    /** How long ffmpeg gets to finish after its input ends before it is killed. */
    static final long EXIT_WAIT_MS = 5_000;
    /** Why an attempt ended whose queue overflowed. */
    static final String FELL_BEHIND = "The RTMP connection fell behind the program output";
    /** Why an attempt ended because the packager stopped (ffmpeg finished cleanly). */
    static final String RESTARTED = "The program output restarted";
    /** Longest stderr line kept for the status. */
    static final int MAX_DETAIL = 300;

    /** Where the push stands. */
    public enum State {
        /** Started; ffmpeg hasn't reported output yet. */
        CONNECTING,
        /** ffmpeg is sending. */
        CONNECTED,
        /** The last attempt ended; the next one starts at {@link Status#retryAtMs()}. */
        RETRYING,
        /**
         * Like {@link #RETRYING}, but the push keeps failing (the backoff reached its longest wait)
         * or ffmpeg or the program output can't be started at all. Still retried.
         */
        ERROR,
        /** {@link #close()} was called. */
        STOPPED
    }

    /**
     * The push's status.
     *
     * @param detail    why the last attempt ended (ffmpeg's last stderr line, key masked), or null
     * @param failures  attempts that ended since the push was last stable
     * @param sinceMs   when this state was entered
     * @param retryAtMs when the next attempt starts ({@link State#RETRYING}/{@link State#ERROR}), else 0
     */
    public record Status(State state, String detail, int failures, long sinceMs, long retryAtMs) {
    }

    /** A consumer slot on the room's program output, held for one attempt. */
    public interface Subscription extends AutoCloseable {
        ConsumerQueue queue();

        /** Leaves the queue and releases the output. */
        @Override
        void close();
    }

    /** Opens a {@link Subscription} (starting the program output if needed). */
    @FunctionalInterface
    public interface Feed {
        Subscription open() throws Exception;
    }

    /** Starts ffmpeg (a {@link ProcessBuilder} in production). */
    @FunctionalInterface
    public interface Launcher {
        Process start(List<String> command) throws IOException;
    }

    /** Waits between attempts ({@link Thread#sleep} in production); interrupted by {@link #close()}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final String name;
    private final String ffmpeg;
    private final String target;
    private final String key;
    private final Feed feed;
    private final Launcher launcher;
    private final Sleeper sleeper;
    private final LongSupplier nowMs;
    private final Consumer<Status> listener;
    private final Object lock = new Object();
    private Status status;
    private Thread thread;
    private boolean stopped;
    private Process process;

    /**
     * @param name     for logs and the thread name (the room id), never a key
     * @param ffmpeg   the ffmpeg executable
     * @param rtmpUrl  the service's ingest URL, e.g. {@code rtmp://ingest.example/live}
     * @param key      the stream key, appended as {@code <rtmpUrl>/<key>}; may be empty
     * @param listener told about every status change (any thread; must not block); may be null
     */
    public RtmpPusher(String name, String ffmpeg, String rtmpUrl, String key, Feed feed, Launcher launcher,
            Sleeper sleeper, LongSupplier nowMs, Consumer<Status> listener) {
        this.name = Objects.requireNonNull(name, "name");
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.key = key == null ? "" : key;
        this.target = target(Objects.requireNonNull(rtmpUrl, "rtmpUrl"), this.key);
        this.feed = Objects.requireNonNull(feed, "feed");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.listener = listener;
        this.status = new Status(State.CONNECTING, null, 0, nowMs.getAsLong(), 0);
    }

    /** {@code <rtmpUrl>/<key>} (one slash between them), or the URL alone without a key. */
    static String target(String rtmpUrl, String key) {
        if (key.isEmpty()) {
            return rtmpUrl;
        }
        String base = rtmpUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + key;
    }

    /** The ffmpeg command line for a push to {@code target}. */
    static List<String> command(String ffmpeg, String target) {
        return List.of(ffmpeg, "-hide_banner", "-nostats", "-loglevel", "warning", "-progress", "pipe:1",
                "-analyzeduration", "1000000", "-f", "mpegts", "-i", "pipe:0", "-c", "copy", "-f", "flv", "-flvflags", "no_duration_filesize",
                target);
    }

    /** How a stream key is shown: {@code …} and its last 4 characters (just {@code …} if it is shorter than 8). */
    public static String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "…";
        }
        return "…" + key.substring(key.length() - 4);
    }

    /**
     * {@code line} with every occurrence of the key masked, and the credentials of every URL in it
     * (user info, query, fragment) masked by {@link #maskUrlCredentials}: some services take
     * credentials in the ingest URL instead of a key.
     */
    String redact(String line) {
        String l = key.isEmpty() ? line : line.replace(key, maskKey(key));
        return maskUrlCredentials(l);
    }

    /**
     * Every URL in {@code text} with its user info replaced by {@code ***@} and its query and
     * fragment by {@code ?…} / {@code #…}.
     */
    static String maskUrlCredentials(String text) {
        Matcher m = RoomOutputs.URL_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = m.group();
            int start = url.indexOf("://") + 3;
            int pathAt = start;
            while (pathAt < url.length() && "/?#".indexOf(url.charAt(pathAt)) < 0) {
                pathAt++;
            }
            String authority = url.substring(start, pathAt);
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                authority = "***@" + authority.substring(at + 1);
            }
            String rest = url.substring(pathAt);
            int q = rest.indexOf('?');
            int h = rest.indexOf('#');
            int cut = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
            if (cut >= 0) {
                rest = rest.substring(0, cut) + rest.charAt(cut) + "…";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(url.substring(0, start) + authority + rest));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The target URL as it may be logged. */
    String maskedTarget() {
        return redact(target);
    }

    /** Starts supervising on a virtual thread {@code rtmp-<name>}. */
    public void start() {
        synchronized (lock) {
            if (thread != null || stopped) {
                return;
            }
            thread = Thread.ofVirtual().name("rtmp-" + name).unstarted(this::run);
            thread.start();
        }
    }

    public Status status() {
        synchronized (lock) {
            return status;
        }
    }

    private void setStatus(State state, String detail, int failures, long retryAtMs) {
        Status s;
        synchronized (lock) {
            if (stopped && state != State.STOPPED) {
                return;
            }
            s = new Status(state, detail, failures, nowMs.getAsLong(), retryAtMs);
            status = s;
        }
        if (listener != null) {
            try {
                listener.accept(s);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "RTMP status listener failed", e);
            }
        }
    }

    private boolean stopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    private void run() {
        LOG.log(System.Logger.Level.INFO, "RTMP push of {0} to {1} started", name, maskedTarget());
        int failures = 0;
        while (!stopped()) {
            Attempt a = attempt(failures);
            if (stopped()) {
                break;
            }
            if (a.connectedMs >= STABLE_MS) {
                failures = 0;
            }
            failures++;
            long wait = BACKOFF_MS.get(Math.min(failures, BACKOFF_MS.size()) - 1);
            boolean error = a.fatal || failures >= BACKOFF_MS.size();
            LOG.log(System.Logger.Level.INFO, "RTMP push of {0} ended ({1}); retrying in {2} s", name, a.detail,
                    wait / 1000.0);
            setStatus(error ? State.ERROR : State.RETRYING, a.detail, failures, nowMs.getAsLong() + wait);
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                if (stopped()) {
                    break;
                }
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.log(System.Logger.Level.INFO, "RTMP push of {0} stopped", name);
    }

    /** How an attempt ended. */
    private static final class Attempt {
        String detail;
        /** ffmpeg or the program output couldn't be started at all. */
        boolean fatal;
        /** How long it was connected. */
        long connectedMs;
    }

    private Attempt attempt(int failures) {
        Attempt result = new Attempt();
        Subscription sub;
        try {
            sub = feed.open();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.detail = "Stopped";
            return result;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "RTMP push of " + name + ": the program output could not start", e);
            result.detail = "The program output could not start: " + e.getMessage();
            result.fatal = true;
            return result;
        }
        try {
            return run(sub, result, failures);
        } finally {
            sub.close();
        }
    }

    private Attempt run(Subscription sub, Attempt result, int failures) {
        ConsumerQueue queue = sub.queue();
        Process p;
        try {
            p = launcher.start(command(ffmpeg, target));
        } catch (IOException e) {
            result.detail = "ffmpeg could not be started: " + redact(String.valueOf(e.getMessage()));
            result.fatal = true;
            return result;
        }
        synchronized (lock) {
            if (stopped) {
                p.destroyForcibly();
                result.detail = "Stopped";
                return result;
            }
            process = p;
        }
        boolean[] writing = new boolean[1];
        long[] connectedAt = {-1};
        String[] lastLine = new String[1];
        int failuresNow = failures;
        Thread err = Thread.ofVirtual().name("rtmp-" + name + "-stderr").start(() -> {
            readLines(p.getErrorStream(), line -> {
                String l = redact(line.strip());
                if (!l.isEmpty()) {
                    if (l.length() > MAX_DETAIL) {
                        l = l.substring(0, MAX_DETAIL) + "…";
                    }
                    synchronized (lastLine) {
                        lastLine[0] = l;
                    }
                    LOG.log(System.Logger.Level.INFO, "ffmpeg (RTMP push of {0}): {1}", name, l);
                }
            });
        });
        Thread out = Thread.ofVirtual().name("rtmp-" + name + "-progress").start(() -> {
            readLines(p.getInputStream(), line -> {
                if (line.startsWith("progress=")) {
                    boolean first;
                    synchronized (connectedAt) {
                        first = connectedAt[0] < 0;
                        if (first) {
                            connectedAt[0] = nowMs.getAsLong();
                        }
                    }
                    if (first) {
                        LOG.log(System.Logger.Level.INFO, "RTMP push of {0} connected", name);
                        setStatus(State.CONNECTED, null, failuresNow, 0);
                    }
                }
            });
        });
        // A full queue means ffmpeg isn't taking the stream (e.g. a stalled connection): end it, which
        // also frees a write blocked on the pipe. When the packager stops, a pending write is ended too.
        queue.onClose(() -> {
            boolean busy;
            synchronized (writing) {
                busy = writing[0];
            }
            if (busy || queue.closeReason() == ConsumerQueue.CloseReason.OVERFLOW) {
                p.destroyForcibly();
            }
        });
        // ffmpeg exiting by itself (refused, cut off) ends the wait for the next unit.
        p.onExit().thenRun(queue::leave);
        String ended = null;
        OutputStream stdin = p.getOutputStream();
        try {
            TsUnit unit;
            while ((unit = queue.take()) != null) {
                synchronized (writing) {
                    writing[0] = true;
                }
                try {
                    stdin.write(unit.ts());
                    stdin.flush();
                } finally {
                    synchronized (writing) {
                        writing[0] = false;
                    }
                }
            }
            ConsumerQueue.CloseReason reason = queue.closeReason();
            if (reason == ConsumerQueue.CloseReason.OVERFLOW) {
                ended = FELL_BEHIND;
            } else if (reason == ConsumerQueue.CloseReason.STOPPED) {
                ended = RESTARTED;
            }
        } catch (IOException e) {
            // ffmpeg exited (its stdin is gone) or was killed; its exit and stderr tell why.
        } catch (InterruptedException e) {
            // close(): stop ffmpeg below.
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        int code = waitForExit(p);
        joinQuietly(err, 1000);
        joinQuietly(out, 1000);
        synchronized (lock) {
            process = null;
        }
        long connected;
        synchronized (connectedAt) {
            connected = connectedAt[0];
        }
        result.connectedMs = connected < 0 ? 0 : nowMs.getAsLong() - connected;
        String last;
        synchronized (lastLine) {
            last = lastLine[0];
        }
        if (queue.closeReason() == ConsumerQueue.CloseReason.OVERFLOW) {
            result.detail = FELL_BEHIND;
        } else if (ended != null && code == 0) {
            result.detail = ended;
        } else if (last != null) {
            result.detail = last;
        } else {
            result.detail = ended != null ? ended : "ffmpeg exited with code " + code;
        }
        return result;
    }

    /** Waits for ffmpeg to finish after its input ended; kills it if it takes too long or on interrupt. */
    private int waitForExit(Process p) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!interrupted && p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
        } catch (InterruptedException e) {
            interrupted = true;
        }
        p.destroyForcibly();
        try {
            p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return p.isAlive() ? -1 : p.exitValue();
    }

    private static void readLines(InputStream in, Consumer<String> each) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                each.accept(line);
            }
        } catch (IOException ignored) {
            // The process is gone.
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops the push: ends ffmpeg (its input is closed, then it is killed if it doesn't exit within
     * {@link #EXIT_WAIT_MS}) and waits for the supervisor to finish. Idempotent.
     */
    @Override
    public void close() {
        Thread t;
        Process p;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            t = thread;
            p = process;
        }
        setStatus(State.STOPPED, null, 0, 0);
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
        if (t != null) {
            t.interrupt();
            joinQuietly(t, EXIT_WAIT_MS + 1_000);
            if (t.isAlive()) {
                // ffmpeg doesn't take its input (a stalled connection): a write may be blocked on it.
                synchronized (lock) {
                    p = process;
                }
                if (p != null) {
                    p.destroyForcibly();
                }
                joinQuietly(t, EXIT_WAIT_MS + 1_000);
            }
        }
    }

    /** A {@link Launcher} that runs the command as a process (stdin, stdout and stderr piped). */
    public static Launcher processLauncher() {
        return command -> new ProcessBuilder(new ArrayList<>(command)).start();
    }
}
