package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RtmpPusher}: supervised ffmpeg with backoff 1/2/5/10/30 s, status with the last stderr line
 * (key masked), stdin fed with the units, slow/stalled ffmpeg ended, stop. Most cases run a small
 * shell script in place of ffmpeg; one pushes real units with real ffmpeg to an ffmpeg RTMP listener.
 */
class RtmpPusherTest {
    static final String KEY = "sk_live_abcdefgh1234WXYZ";

    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(1_000_000);
    final List<Long> sleeps = new CopyOnWriteArrayList<>();
    final List<RtmpPusher.Status> statuses = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<ConsumerQueue> queues = new LinkedBlockingQueue<>();
    final AtomicInteger subscriptionsOpen = new AtomicInteger();
    final AtomicInteger launches = new AtomicInteger();
    final List<String> logged = Collections.synchronizedList(new ArrayList<>());
    RtmpPusher pusher;
    Handler logHandler;
    /** Held so the logger (and its handler) isn't collected while the test runs. */
    final Logger julLogger = Logger.getLogger(RtmpPusher.class.getName());

    @AfterEach
    void tearDown() {
        if (pusher != null) {
            pusher.close();
        }
        if (logHandler != null) {
            julLogger.removeHandler(logHandler);
        }
    }

    void captureLogs() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                String msg = r.getMessage();
                if (r.getParameters() != null) {
                    msg = java.text.MessageFormat.format(msg, r.getParameters());
                }
                logged.add(msg);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        julLogger.addHandler(logHandler);
    }

    RtmpPusher.Feed feed(int capacity) {
        return () -> {
            ConsumerQueue q = new ConsumerQueue(capacity);
            subscriptionsOpen.incrementAndGet();
            queues.add(q);
            return new RtmpPusher.Subscription() {
                @Override
                public ConsumerQueue queue() {
                    return q;
                }

                @Override
                public void close() {
                    q.leave();
                    subscriptionsOpen.decrementAndGet();
                }
            };
        };
    }

    /** Runs {@code script(attempt)} with sh in place of ffmpeg (attempts count from 1). */
    RtmpPusher.Launcher scripts(IntFunction<String> script) {
        return command -> {
            assertEquals("-c", command.get(command.indexOf("copy") - 1));
            int n = launches.incrementAndGet();
            return new ProcessBuilder("sh", "-c", script.apply(n)).start();
        };
    }

    /** A sleeper that records the wait and returns at once (optionally running something first). */
    RtmpPusher.Sleeper recordingSleeper(Runnable each) {
        return ms -> {
            sleeps.add(ms);
            if (each != null) {
                each.run();
            }
            Thread.sleep(1);
        };
    }

    RtmpPusher pusher(RtmpPusher.Feed feed, RtmpPusher.Launcher launcher, RtmpPusher.Sleeper sleeper) {
        pusher = new RtmpPusher("room1", "ffmpeg", "rtmp://ingest.example/live/", KEY, feed, launcher, sleeper,
                now::get, statuses::add);
        return pusher;
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    static TsUnit unit(long seq, byte[] ts) {
        long start = OutputClock.START + seq * 180_000;
        return new TsUnit(seq, start, start + 180_000, start + 180_000, ts);
    }

    @Test
    void targetAndCommand() {
        assertEquals("rtmp://h/live/k", RtmpPusher.target("rtmp://h/live", "k"));
        assertEquals("rtmp://h/live/k", RtmpPusher.target("rtmp://h/live//", "k"));
        assertEquals("rtmp://h/live", RtmpPusher.target("rtmp://h/live", ""));
        List<String> cmd = RtmpPusher.command("/usr/bin/ffmpeg", "rtmp://h/live/k");
        assertEquals("/usr/bin/ffmpeg", cmd.get(0));
        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("-f mpegts -i pipe:0 -c copy -f flv"), joined);
        assertTrue(joined.contains("-progress pipe:1"), joined);
        assertTrue(joined.contains("-loglevel warning"), joined);
        assertEquals("rtmp://h/live/k", cmd.get(cmd.size() - 1));
    }

    @Test
    void keyIsMasked() {
        assertEquals("…WXYZ", RtmpPusher.maskKey(KEY));
        assertEquals("…", RtmpPusher.maskKey("short"));
        assertEquals("…", RtmpPusher.maskKey(null));
        RtmpPusher p = pusher(feed(4), scripts(n -> "true"), recordingSleeper(null));
        assertEquals("rtmp://ingest.example/live/…WXYZ", p.maskedTarget());
        assertEquals("Error opening output rtmp://ingest.example/live/…WXYZ: Connection refused",
                p.redact("Error opening output rtmp://ingest.example/live/" + KEY + ": Connection refused"));
        assertFalse(p.maskedTarget().contains(KEY));
    }

    @Test
    @Timeout(60)
    void failingFfmpegIsRetriedWithBackoffAndTheLastStderrLine() throws Exception {
        captureLogs();
        String err = "Error opening output rtmp://ingest.example/live/" + KEY + ": Connection refused";
        RtmpPusher p = pusher(feed(4), scripts(n -> "echo 'first line' >&2; echo '" + err + "' >&2; exit 1"),
                recordingSleeper(null));
        p.start();
        await(() -> sleeps.size() >= 7, "7 retries");
        p.close();
        assertEquals(List.of(1000L, 2000L, 5000L, 10000L, 30000L, 30000L, 30000L), sleeps.subList(0, 7));
        // RETRYING for the first four, then ERROR once the backoff reached 30 s.
        List<RtmpPusher.Status> ended = statuses.stream()
                .filter(s -> s.state() == RtmpPusher.State.RETRYING || s.state() == RtmpPusher.State.ERROR).toList();
        assertEquals(List.of(RtmpPusher.State.RETRYING, RtmpPusher.State.RETRYING, RtmpPusher.State.RETRYING,
                        RtmpPusher.State.RETRYING, RtmpPusher.State.ERROR, RtmpPusher.State.ERROR),
                ended.subList(0, 6).stream().map(RtmpPusher.Status::state).toList());
        RtmpPusher.Status first = ended.get(0);
        assertEquals("Error opening output rtmp://ingest.example/live/…WXYZ: Connection refused", first.detail());
        assertEquals(1, first.failures());
        assertEquals(first.sinceMs() + 1000, first.retryAtMs());
        assertEquals(5, ended.get(4).failures());
        assertEquals(RtmpPusher.State.STOPPED, p.status().state());
        assertEquals(0, subscriptionsOpen.get(), "every attempt's subscription was closed");
        // Never connected: no CONNECTED status.
        assertTrue(statuses.stream().noneMatch(s -> s.state() == RtmpPusher.State.CONNECTED));
        synchronized (logged) {
            assertFalse(logged.isEmpty());
            for (String line : logged) {
                assertFalse(line.contains(KEY), line);
            }
            assertTrue(logged.stream().anyMatch(l -> l.contains("…WXYZ")), "masked target and stderr logged");
        }
    }

    @Test
    @Timeout(60)
    void unitsAreWrittenToStdinAndProgressMeansConnected() throws Exception {
        Path out = dir.resolve("out.bin");
        RtmpPusher p = pusher(feed(4), scripts(n -> n == 1 ? "echo progress=continue; cat > '" + out + "'"
                : "sleep 30"), recordingSleeper(null));
        p.start();
        ConsumerQueue q = queues.poll(10, TimeUnit.SECONDS);
        assertNotNull(q);
        await(() -> p.status().state() == RtmpPusher.State.CONNECTED, "connected");
        assertNull(p.status().detail());
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < 6; i++) {
            byte[] ts = new byte[188 * 300];
            Arrays.fill(ts, (byte) i);
            ts[0] = 0x47;
            expected.write(ts);
            assertTrue(q.deliver(unit(i, ts)));
            await(() -> q.size() == 0, "unit taken");
        }
        // The packager stops: ffmpeg's input ends, it exits 0, and the push starts over.
        q.close();
        await(() -> !sleeps.isEmpty(), "retry");
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(out));
        RtmpPusher.Status s = statuses.stream().filter(x -> x.state() == RtmpPusher.State.RETRYING).findFirst()
                .orElseThrow();
        assertEquals("The program output restarted", s.detail());
        assertEquals(1000L, sleeps.get(0));
        await(() -> launches.get() == 2, "second attempt");
        p.close();
        assertEquals(RtmpPusher.State.STOPPED, p.status().state());
        assertEquals(0, subscriptionsOpen.get());
    }

    @Test
    @Timeout(60)
    void aConnectionStableForAMinuteResetsTheBackoff() throws Exception {
        // Attempts 1 and 2 fail at once; 3 connects, stays a minute (fake clock), then drops;
        // 4 fails at once again.
        Path go = dir.resolve("go");
        RtmpPusher p = pusher(feed(4), scripts(n -> switch (n) {
            case 3 -> "echo progress=continue; while [ ! -e '" + go + "' ]; do sleep 0.02; done;"
                    + " echo 'Connection reset by peer' >&2; exit 1";
            default -> "echo 'refused' >&2; exit 1";
        }), recordingSleeper(null));
        p.start();
        await(() -> launches.get() == 3 && p.status().state() == RtmpPusher.State.CONNECTED, "third attempt connects");
        assertEquals(2, p.status().failures());
        now.addAndGet(RtmpPusher.STABLE_MS);
        Files.createFile(go);
        await(() -> sleeps.size() >= 4, "4 retries");
        p.close();
        assertEquals(List.of(1000L, 2000L, 1000L, 2000L), sleeps.subList(0, 4));
        RtmpPusher.Status dropped = statuses.stream()
                .filter(s -> s.state() == RtmpPusher.State.RETRYING && s.failures() == 1).skip(1).findFirst()
                .orElseThrow();
        assertEquals("Connection reset by peer", dropped.detail());
    }

    @Test
    @Timeout(60)
    void aShortConnectionDoesNotResetTheBackoff() throws Exception {
        RtmpPusher p = pusher(feed(4), scripts(n -> n == 2 ? "echo progress=continue; sleep 0.1; exit 1"
                : "exit 1"), recordingSleeper(null));
        p.start();
        await(() -> sleeps.size() >= 3, "3 retries");
        p.close();
        assertEquals(List.of(1000L, 2000L, 5000L), sleeps.subList(0, 3));
        // No stderr: the exit code is the detail.
        assertEquals("ffmpeg exited with code 1", statuses.stream()
                .filter(s -> s.state() == RtmpPusher.State.RETRYING).findFirst().orElseThrow().detail());
    }

    @Test
    @Timeout(60)
    void ffmpegThatStopsReadingIsKilledWhenTheQueueOverflows() throws Exception {
        // Never reads stdin: the writer blocks once the pipe is full; the 2-unit queue overflows.
        RtmpPusher p = pusher(feed(2), scripts(n -> n == 1 ? "echo progress=continue; exec sleep 60" : "exec sleep 60"),
                recordingSleeper(null));
        p.start();
        ConsumerQueue q = queues.poll(10, TimeUnit.SECONDS);
        assertNotNull(q);
        await(() -> p.status().state() == RtmpPusher.State.CONNECTED, "connected");
        byte[] big = new byte[1 << 20];
        long t0 = System.nanoTime();
        for (int i = 0; i < 20 && !q.isClosed(); i++) {
            if (!q.deliver(unit(i, big))) {
                q.close(); // as the packager does with a sink that refused a unit
            }
            Thread.sleep(20);
        }
        assertEquals(ConsumerQueue.CloseReason.OVERFLOW, q.closeReason());
        await(() -> !sleeps.isEmpty(), "retry");
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(10), "ended promptly, not after ffmpeg's 60 s");
        RtmpPusher.Status s = statuses.stream().filter(x -> x.state() == RtmpPusher.State.RETRYING).findFirst()
                .orElseThrow();
        assertEquals("The RTMP connection fell behind the program output", s.detail());
    }

    @Test
    @Timeout(60)
    void closeStopsFfmpegAndTheSupervisor() throws Exception {
        Path pidFile = dir.resolve("pid");
        RtmpPusher p = pusher(feed(4), scripts(n -> "echo $$ > '" + pidFile + "'; echo progress=continue; exec sleep 60"),
                recordingSleeper(null));
        p.start();
        await(() -> p.status().state() == RtmpPusher.State.CONNECTED, "connected");
        long t0 = System.nanoTime();
        p.close();
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(15));
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        await(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true), "ffmpeg gone");
        assertEquals(RtmpPusher.State.STOPPED, p.status().state());
        assertEquals(0, subscriptionsOpen.get());
        assertEquals(1, launches.get());
        assertTrue(sleeps.isEmpty());
        int n = statuses.size();
        p.close(); // idempotent, no new status
        assertEquals(n, statuses.size());
    }

    @Test
    @Timeout(60)
    void closeDuringTheBackoffWait() throws Exception {
        RtmpPusher p = pusher(feed(4), scripts(n -> "exit 1"), ms -> {
            sleeps.add(ms);
            Thread.sleep(ms * 1000); // effectively forever; close() interrupts it
        });
        p.start();
        await(() -> !sleeps.isEmpty(), "waiting");
        p.close();
        assertEquals(1, launches.get());
        assertEquals(RtmpPusher.State.STOPPED, p.status().state());
    }

    @Test
    @Timeout(60)
    void ffmpegMissingOrOutputFailingIsAnError() throws Exception {
        RtmpPusher p = pusher(feed(4), command -> {
            launches.incrementAndGet();
            throw new IOException("Cannot run program \"/nope/ffmpeg\": error=2, No such file or directory");
        }, recordingSleeper(null));
        p.start();
        await(() -> !sleeps.isEmpty(), "retry");
        p.close();
        RtmpPusher.Status s = statuses.stream().filter(x -> x.state() == RtmpPusher.State.ERROR).findFirst()
                .orElseThrow();
        assertTrue(s.detail().startsWith("ffmpeg could not be started: "), s.detail());
        assertEquals(1000L, sleeps.get(0));
        assertEquals(0, subscriptionsOpen.get());

        statuses.clear();
        sleeps.clear();
        RtmpPusher q = pusher(() -> {
            throw new IOException("no cards");
        }, scripts(n -> "exit 0"), recordingSleeper(null));
        q.start();
        await(() -> !sleeps.isEmpty(), "retry");
        q.close();
        RtmpPusher.Status e = statuses.stream().filter(x -> x.state() == RtmpPusher.State.ERROR).findFirst()
                .orElseThrow();
        assertEquals("The program output could not start: no cards", e.detail());
    }

    // ------------------------------------------------------------------ real ffmpeg

    @Test
    @Timeout(120)
    void pushesToARealRtmpServer() throws Exception {
        Tools tools = RealSourcesTest.tools();
        Path in = dir.resolve("in.ts");
        // A TS like the packager's: H.264 without B-frames, IDR every 2 s, AAC 48 kHz.
        tools.ffmpeg("-v", "error", "-f", "lavfi", "-i", "testsrc2=s=320x180:r=30", "-f", "lavfi", "-i",
                "sine=f=440:r=48000", "-t", "6", "-c:v", "libx264", "-preset", "veryfast", "-bf", "0", "-g", "60",
                "-keyint_min", "60", "-sc_threshold", "0", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-f", "mpegts",
                in.toString()).run();
        byte[] ts = Files.readAllBytes(in);
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String key = "rtmpTestKey9876";
        String url = "rtmp://127.0.0.1:" + port + "/live";

        // Nothing listening yet: ffmpeg's "Connection refused", with the key masked.
        pusher = new RtmpPusher("room1", tools.ffmpegExecutable(), url, key, feed(4), RtmpPusher.processLauncher(),
                recordingSleeper(null), now::get, statuses::add);
        pusher.start();
        // ffmpeg reads (probes) its input before it opens the output: give it the stream.
        ConsumerQueue first = queues.poll(10, TimeUnit.SECONDS);
        assertNotNull(first);
        first.deliver(unit(0, Arrays.copyOfRange(ts, 0, ts.length / 188 * 188)));
        await(() -> !sleeps.isEmpty(), "first failure");
        pusher.close();
        RtmpPusher.Status refused = statuses.stream().filter(x -> x.state() == RtmpPusher.State.RETRYING)
                .findFirst().orElseThrow();
        assertTrue(refused.detail().contains("Connection refused") || refused.detail().contains("Cannot open"),
                refused.detail());
        assertFalse(refused.detail().contains(key), refused.detail());

        // A listener: ffmpeg as a one-shot RTMP server writing what it receives to a file.
        Path flv = dir.resolve("out.flv");
        Process server = new ProcessBuilder(tools.ffmpegExecutable(), "-hide_banner", "-loglevel", "error",
                "-listen", "1", "-timeout", "30", "-i", url + "/" + key, "-c", "copy", "-f", "flv", "-y",
                flv.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("server.log").toFile()).start();
        try {
            statuses.clear();
            sleeps.clear();
            queues.clear();
            pusher = new RtmpPusher("room1", tools.ffmpegExecutable(), url, key, feed(4),
                    RtmpPusher.processLauncher(), ms -> {
                        sleeps.add(ms);
                        Thread.sleep(200);
                    }, now::get, statuses::add);
            pusher.start();
            // The listener may not be up at first: each attempt gets the whole stream until one connects.
            ConsumerQueue q = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (pusher.status().state() != RtmpPusher.State.CONNECTED) {
                ConsumerQueue next = queues.poll(50, TimeUnit.MILLISECONDS);
                if (next != null) {
                    q = next;
                    q.deliver(unit(0, ts));
                }
                assertTrue(System.nanoTime() < end, "connected within 30 s: " + statuses);
            }
            // Let ffmpeg read the rest, then the packager stops: ffmpeg finishes and closes the connection.
            ConsumerQueue fq = q;
            await(() -> fq.size() == 0, "unit taken");
            Thread.sleep(1000);
            q.close();
            assertTrue(server.waitFor(30, TimeUnit.SECONDS), "listener finished");
            await(() -> !sleeps.isEmpty(), "attempt ended");
            assertEquals("The program output restarted", pusher.status().detail(), pusher.status().toString());
            pusher.close();
        } finally {
            server.destroyForcibly();
        }
        ToolResult probe = tools.ffprobe("-v", "error", "-show_entries", "stream=codec_name:format=duration",
                "-of", "default=nw=1", flv.toString()).stdoutToString(1 << 16).run();
        String info = probe.stdoutString() + "\nserver log: " + Files.readString(dir.resolve("server.log"))
                + "\nstatuses: " + statuses;
        assertTrue(info.contains("codec_name=h264"), info);
        assertTrue(info.contains("codec_name=aac"), info);
        double duration = Double.parseDouble(info.lines().filter(l -> l.startsWith("duration="))
                .findFirst().orElseThrow().substring("duration=".length()));
        assertTrue(duration > 5.5 && duration < 6.5, "about 6 s pushed: " + duration);
    }
}
