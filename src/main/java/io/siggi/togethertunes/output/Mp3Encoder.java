package io.siggi.togethertunes.output;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * One room's MP3 encode ({@code docs/program-output.md#mp3-output}): the packager never encodes,
 * so a supervised {@code ffmpeg -f mpegts -i pipe:0 -map 0:a:0 -c:a libmp3lame … -f mp3 pipe:1}
 * is fed the room's program output (a {@code join=latest} consumer holding a lease, like the RTMP
 * pusher) and its MP3 output, cut into whole frames ({@link Mp3Frames}), goes to every
 * {@link Listener} of the room: one encoder however many listeners.
 *
 * <ul>
 *   <li><b>Listeners</b> each have a bounded queue of {@link #QUEUE_MS} of audio; a listener whose
 *       queue overflows is dropped (its connection ends), as {@code .ts} clients are. A new listener
 *       first gets the last {@link #BURST_MS} of audio (so a player's buffer fills at once), then
 *       everything from the current position on.</li>
 *   <li><b>Stream title:</b> each unit names its item ({@link TsUnit#itemId()}); when it changes,
 *       the title ({@code Artist - Title}, empty for fillers) is attached to the MP3 audio from the
 *       position that unit's audio starts at (ffmpeg keeps the input's timeline), so it changes as
 *       the item reaches the stream, not when the timeline moved.</li>
 *   <li><b>Supervision:</b> when ffmpeg exits, the program output restarts or ffmpeg falls behind,
 *       the attempt ends and the next one starts after 1, 2, 5, 10, then every 30 s
 *       ({@link RtmpPusher#BACKOFF_MS}); listeners stay connected and get a gap. An attempt that
 *       produced audio for {@link RtmpPusher#STABLE_MS} starts the backoff over.</li>
 * </ul>
 *
 * <p>Sample rate 44.1 kHz stereo, CBR at the bitrate read when each attempt starts
 * ({@code outputs.mp3.bitrateKbps}). No ID3 tag and no Xing header, so the stream starts with an
 * audio frame.
 */
public final class Mp3Encoder implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Mp3Encoder.class.getName());

    public static final int SAMPLE_RATE = 44_100;
    /** Audio a listener may have queued before it is dropped as too slow. */
    public static final long QUEUE_MS = 10_000;
    /** Audio a new listener gets at once. */
    public static final long BURST_MS = 2_000;
    /** How long ffmpeg gets to finish after its input ends before it is killed. */
    static final long EXIT_WAIT_MS = 5_000;
    /** How often an encoder without listeners checks for new ones before starting another attempt. */
    static final long IDLE_POLL_MS = 250;

    /** Looks up an item's stream title ({@code Artist - Title}); null or empty for none. */
    @FunctionalInterface
    public interface Titles {
        String title(String itemId) throws Exception;
    }

    /** MP3 bytes (whole frames) and the stream title in effect for them. */
    public record Chunk(byte[] data, String title) {
    }

    /** Why a listener's queue closed. */
    public enum CloseReason {
        /** The listener left. */
        LEFT,
        /** Its queue overflowed: too slow. */
        OVERFLOW,
        /** The encoder stopped. */
        STOPPED
    }

    /** A listener's bounded queue of chunks. */
    public final class Listener implements AutoCloseable {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        private final long capacityBytes;
        private long bytes;
        private CloseReason closed;
        private Runnable onClose;

        private Listener(long capacityBytes) {
            this.capacityBytes = capacityBytes;
        }

        /** The encoder this listener is attached to. */
        public Mp3Encoder encoder() {
            return Mp3Encoder.this;
        }

        /** Runs when the encoder drops this listener (overflow) or stops. Must not block. */
        public void onClose(Runnable action) {
            boolean now;
            lock.lock();
            try {
                onClose = action;
                now = closed != null && closed != CloseReason.LEFT;
            } finally {
                lock.unlock();
            }
            if (now && action != null) {
                action.run();
            }
        }

        /** False once closed (then the listener is removed). */
        boolean offer(Chunk c) {
            Runnable run = null;
            lock.lock();
            try {
                if (closed != null) {
                    return false;
                }
                if (bytes + c.data().length > capacityBytes) {
                    closed = CloseReason.OVERFLOW;
                    chunks.clear();
                    notEmpty.signalAll();
                    run = onClose;
                    onClose = null;
                } else {
                    chunks.addLast(c);
                    bytes += c.data().length;
                    notEmpty.signalAll();
                }
            } finally {
                lock.unlock();
            }
            if (run != null) {
                run.run();
                return false;
            }
            return true;
        }

        /** The next chunk, waiting as long as needed; null once closed. */
        public Chunk take() throws InterruptedException {
            lock.lock();
            try {
                while (chunks.isEmpty() && closed == null) {
                    notEmpty.await();
                }
                if (closed != null) {
                    return null;
                }
                Chunk c = chunks.pollFirst();
                bytes -= c.data().length;
                return c;
            } finally {
                lock.unlock();
            }
        }

        /** Like {@link #take()}, but null after {@code timeoutMs} without a chunk too. */
        public Chunk poll(long timeoutMs) throws InterruptedException {
            long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            lock.lock();
            try {
                while (chunks.isEmpty() && closed == null) {
                    if (nanos <= 0) {
                        return null;
                    }
                    nanos = notEmpty.awaitNanos(nanos);
                }
                if (closed != null) {
                    return null;
                }
                Chunk c = chunks.pollFirst();
                bytes -= c.data().length;
                return c;
            } finally {
                lock.unlock();
            }
        }

        /** Null while open, else why it closed. */
        public CloseReason closeReason() {
            lock.lock();
            try {
                return closed;
            } finally {
                lock.unlock();
            }
        }

        void stop() {
            Runnable run;
            lock.lock();
            try {
                if (closed == null) {
                    closed = CloseReason.STOPPED;
                    chunks.clear();
                    notEmpty.signalAll();
                }
                run = closed == CloseReason.LEFT ? null : onClose;
                onClose = null;
            } finally {
                lock.unlock();
            }
            if (run != null) {
                run.run();
            }
        }

        /** The listener leaves. */
        @Override
        public void close() {
            lock.lock();
            try {
                if (closed == null) {
                    closed = CloseReason.LEFT;
                    chunks.clear();
                    notEmpty.signalAll();
                }
                onClose = null;
            } finally {
                lock.unlock();
            }
            removeListener(this);
        }
    }

    /** A title that starts at a position of the MP3 output (ms since the attempt's first audio). */
    private record Marker(long atMs, String title) {
    }

    private final String name;
    private final String ffmpeg;
    private final IntSupplier bitrateKbps;
    private final RtmpPusher.Feed feed;
    private final Titles titles;
    private final RtmpPusher.Launcher launcher;
    private final RtmpPusher.Sleeper sleeper;
    private final LongSupplier nowMs;
    private final Runnable onIdle;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** The last {@link #BURST_MS} of audio, for new listeners; guarded by {@link #lock}. */
    private final ArrayDeque<Chunk> recent = new ArrayDeque<>();
    private long recentBytes;
    private volatile int runningKbps;
    private Thread thread;
    private boolean stopped;
    private Process process;
    private volatile long attempts;
    private volatile long chunksSent;

    /**
     * @param name        for logs and thread names (the room id), never a token
     * @param bitrateKbps read at the start of every attempt
     * @param feed        a {@code join=latest} consumer of the room's program output with a lease
     * @param onIdle      run (on the listener's thread) when the last listener left; may be null
     */
    public Mp3Encoder(String name, String ffmpeg, IntSupplier bitrateKbps, RtmpPusher.Feed feed, Titles titles,
                      RtmpPusher.Launcher launcher, RtmpPusher.Sleeper sleeper, LongSupplier nowMs, Runnable onIdle) {
        this.name = Objects.requireNonNull(name, "name");
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.bitrateKbps = Objects.requireNonNull(bitrateKbps, "bitrateKbps");
        this.feed = Objects.requireNonNull(feed, "feed");
        this.titles = Objects.requireNonNull(titles, "titles");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.onIdle = onIdle;
        this.runningKbps = clampKbps(bitrateKbps.getAsInt());
    }

    /** The ffmpeg command line for an MP3 encode at {@code kbps}. */
    static List<String> command(String ffmpeg, int kbps) {
        return List.of(ffmpeg, "-hide_banner", "-nostats", "-loglevel", "warning",
                "-analyzeduration", "1000000", "-f", "mpegts", "-i", "pipe:0",
                "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", kbps + "k",
                "-ar", Integer.toString(SAMPLE_RATE), "-ac", "2",
                "-id3v2_version", "0", "-write_xing", "0", "-flush_packets", "1", "-f", "mp3", "pipe:1");
    }

    static int clampKbps(int kbps) {
        return Math.max(32, Math.min(320, kbps));
    }

    /** The bitrate of the current (or last) attempt, for {@code icy-br}. */
    public int bitrateKbps() {
        return runningKbps;
    }

    /** Starts supervising on a virtual thread {@code mp3-<name>} (once). */
    public void start() {
        synchronized (lock) {
            if (thread != null || stopped) {
                return;
            }
            thread = Thread.ofVirtual().name("mp3-" + name).unstarted(this::run);
            thread.start();
        }
    }

    /** Attempts started so far (tests). */
    long attempts() {
        return attempts;
    }

    /** Chunks handed to listeners so far (tests). */
    long chunksSent() {
        return chunksSent;
    }

    public boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    /**
     * A new listener: gets the recent burst at once, then every chunk from now on. If the encoder
     * has stopped, the listener comes back closed.
     */
    public Listener join() {
        long perSecond = runningKbps * 1000L / 8;
        Listener l = new Listener(Math.max(64 * 1024, perSecond * QUEUE_MS / 1000));
        synchronized (lock) {
            if (!stopped) {
                for (Chunk c : recent) {
                    l.offer(c);
                }
                listeners.add(l);
                return l;
            }
        }
        l.stop();
        return l;
    }

    /** Listeners attached now. */
    public int listenerCount() {
        return listeners.size();
    }

    private void removeListener(Listener l) {
        boolean idle;
        synchronized (lock) {
            idle = listeners.remove(l) && listeners.isEmpty() && !stopped;
        }
        if (idle && onIdle != null) {
            onIdle.run();
        }
    }

    private void broadcast(Chunk c) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            recent.addLast(c);
            recentBytes += c.data().length;
            long keep = runningKbps * 1000L / 8 * BURST_MS / 1000;
            while (recentBytes - recent.peekFirst().data().length >= keep) {
                recentBytes -= recent.pollFirst().data().length;
            }
            // under the lock, so a listener joining now gets c either in its burst or here, not twice
            chunksSent++;
            for (Listener l : listeners) {
                if (!l.offer(c)) {
                    removeListener(l);
                }
            }
        }
    }

    private boolean stopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    private void run() {
        LOG.log(System.Logger.Level.INFO, "MP3 encoder of {0} started", name);
        int failures = 0;
        while (!stopped()) {
            if (attempts > 0 && listeners.isEmpty()) {
                // nobody listens (the encoder is about to be stopped): don't restart the program output
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            long producedMs = attempt();
            if (stopped()) {
                break;
            }
            if (producedMs >= RtmpPusher.STABLE_MS) {
                failures = 0;
            }
            failures++;
            List<Long> backoff = RtmpPusher.BACKOFF_MS;
            long wait = backoff.get(Math.min(failures, backoff.size()) - 1);
            LOG.log(System.Logger.Level.INFO, "MP3 encoder of {0} ended; retrying in {1} s", name, wait / 1000.0);
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
        }
        LOG.log(System.Logger.Level.INFO, "MP3 encoder of {0} stopped", name);
    }

    /** One ffmpeg run. @return how long it produced audio, in ms */
    private long attempt() {
        attempts++;
        RtmpPusher.Subscription sub;
        try {
            sub = feed.open();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "MP3 encoder of " + name + ": the program output could not start", e);
            return 0;
        }
        try {
            return run(sub.queue());
        } finally {
            sub.close();
        }
    }

    private long run(ConsumerQueue queue) {
        int kbps = clampKbps(bitrateKbps.getAsInt());
        runningKbps = kbps;
        Process p;
        try {
            p = launcher.start(command(ffmpeg, kbps));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "MP3 encoder of " + name + ": ffmpeg could not be started", e);
            return 0;
        }
        synchronized (lock) {
            if (stopped) {
                p.destroyForcibly();
                return 0;
            }
            process = p;
        }
        ArrayDeque<Marker> markers = new ArrayDeque<>();
        long[] firstAudioAt = {-1};
        Thread err = Thread.ofVirtual().name("mp3-" + name + "-stderr").start(() -> readLines(p.getErrorStream(),
                line -> {
                    String l = line.strip();
                    if (!l.isEmpty()) {
                        LOG.log(System.Logger.Level.INFO, "ffmpeg (MP3 encoder of {0}): {1}", name,
                                l.length() > 300 ? l.substring(0, 300) + "…" : l);
                    }
                }));
        Thread out = Thread.ofVirtual().name("mp3-" + name + "-out").start(() -> {
            Mp3Frames frames = new Mp3Frames();
            String title = null;
            byte[] buf = new byte[8192];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    long atMs = frames.samples() * 1000 / SAMPLE_RATE;
                    byte[] data = frames.feed(buf, 0, n);
                    if (data.length == 0) {
                        continue;
                    }
                    synchronized (firstAudioAt) {
                        if (firstAudioAt[0] < 0) {
                            firstAudioAt[0] = nowMs.getAsLong();
                        }
                    }
                    synchronized (markers) {
                        while (!markers.isEmpty() && markers.peekFirst().atMs() <= atMs) {
                            title = markers.pollFirst().title();
                        }
                    }
                    broadcast(new Chunk(data, title));
                }
                byte[] last = frames.flush();
                if (last.length > 0) {
                    broadcast(new Chunk(last, title));
                }
            } catch (IOException ignored) {
                // ffmpeg is gone
            }
        });
        queue.onClose(() -> {
            if (queue.closeReason() == ConsumerQueue.CloseReason.OVERFLOW) {
                p.destroyForcibly();
            }
        });
        p.onExit().thenRun(queue::leave);
        OutputStream stdin = p.getOutputStream();
        String lastItem = null;
        long firstPts = Long.MIN_VALUE;
        try {
            TsUnit unit;
            while ((unit = queue.take()) != null) {
                if (firstPts == Long.MIN_VALUE) {
                    firstPts = unit.startPts();
                }
                if (firstPts == unit.startPts() || !Objects.equals(lastItem, unit.itemId())) {
                    lastItem = unit.itemId();
                    String t = title(lastItem);
                    synchronized (markers) {
                        markers.addLast(new Marker((unit.startPts() - firstPts) / 90, t));
                    }
                }
                stdin.write(unit.ts());
                stdin.flush();
            }
        } catch (IOException e) {
            // ffmpeg exited or was killed
        } catch (InterruptedException e) {
            // close()
        }
        try {
            stdin.close();
        } catch (IOException ignored) {
            // gone
        }
        waitForExit(p);
        joinQuietly(out, 2000);
        joinQuietly(err, 1000);
        synchronized (lock) {
            process = null;
        }
        long started;
        synchronized (firstAudioAt) {
            started = firstAudioAt[0];
        }
        return started < 0 ? 0 : nowMs.getAsLong() - started;
    }

    private String title(String itemId) {
        if (itemId == null) {
            return "";
        }
        try {
            String t = titles.title(itemId);
            return t == null ? "" : t;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "MP3 encoder of " + name + ": no title for item " + itemId, e);
            return "";
        }
    }

    private static void waitForExit(Process p) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!interrupted && p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return;
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
    }

    private static void readLines(InputStream in, java.util.function.Consumer<String> each) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                each.accept(line);
            }
        } catch (IOException ignored) {
            // gone
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the encoder: ffmpeg ends, every listener's queue closes. Idempotent. */
    @Override
    public void close() {
        Thread t;
        Process p;
        List<Listener> all;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            t = thread;
            p = process;
            all = new ArrayList<>(listeners);
            listeners.clear();
            recent.clear();
            recentBytes = 0;
        }
        for (Listener l : all) {
            l.stop();
        }
        if (p != null) {
            p.destroyForcibly();
        }
        if (t != null) {
            t.interrupt();
            joinQuietly(t, EXIT_WAIT_MS + 1_000);
        }
    }
}
