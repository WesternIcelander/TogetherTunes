package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * The rooms' MP3 encoders ({@code docs/program-output.md#mp3-output}), at most one per room: started
 * by the room's first MP3 listener ({@link #join}), stopped {@link #LINGER_MS} after its last
 * listener left (so a player that reconnects at once finds it running). Each encoder holds a lease
 * on the room's program output while it runs, so the output stops the usual 60 s after the encoder.
 */
public final class Mp3Encoders implements AutoCloseable {
    /** How long an encoder without listeners keeps running. */
    public static final long LINGER_MS = 15_000;

    /** Builds a room's encoder ({@link Mp3Encoder} in production). */
    @FunctionalInterface
    interface Factory {
        Mp3Encoder create(String roomId, Runnable onIdle);
    }

    private final Factory factory;
    private final long lingerMs;
    private final Map<String, Mp3Encoder> encoders = new HashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("mp3-encoders").factory());
    private boolean closed;

    /**
     * @param ffmpeg      the ffmpeg executable ({@code tools.ffmpeg})
     * @param bitrateKbps {@code outputs.mp3.bitrateKbps}, read as each encode starts
     */
    public Mp3Encoders(ProgramOutputs outputs, String ffmpeg, IntSupplier bitrateKbps, Mp3Encoder.Titles titles) {
        this((roomId, onIdle) -> new Mp3Encoder(roomId, ffmpeg, bitrateKbps, () -> subscribe(outputs, roomId), titles,
                RtmpPusher.processLauncher(), Thread::sleep, System::currentTimeMillis, onIdle), LINGER_MS);
    }

    Mp3Encoders(Factory factory, long lingerMs) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.lingerMs = lingerMs;
    }

    /** Stream titles from {@code items}: {@code Artist - Title}, or the title alone. */
    public static Mp3Encoder.Titles titlesFrom(Database db) {
        return itemId -> db.read(c -> {
            try (var ps = c.prepareStatement("SELECT title, artist FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return streamTitle(rs.getString(2), rs.getString(1));
                }
            }
        });
    }

    /** {@code Artist - Title}, or whichever of them is there. */
    static String streamTitle(String artist, String title) {
        String a = artist == null ? "" : artist.strip();
        String t = title == null ? "" : title.strip();
        if (a.isEmpty()) {
            return t;
        }
        return t.isEmpty() ? a : a + " - " + t;
    }

    private static RtmpPusher.Subscription subscribe(ProgramOutputs outputs, String roomId) throws Exception {
        ProgramOutputs.Lease lease = outputs.acquire(roomId);
        ConsumerQueue queue = lease.packager().subscribe(true);
        return new RtmpPusher.Subscription() {
            @Override
            public ConsumerQueue queue() {
                return queue;
            }

            @Override
            public void close() {
                queue.leave();
                lease.close();
            }
        };
    }

    /** A new listener of the room's MP3 stream, starting the room's encoder if needed. */
    public synchronized Mp3Encoder.Listener join(String roomId) {
        if (closed) {
            throw new IllegalStateException("closed");
        }
        Mp3Encoder e = encoders.get(roomId);
        if (e == null || e.isStopped()) {
            Mp3Encoder created = factory.create(roomId, () -> idle(roomId));
            encoders.put(roomId, created);
            created.start();
            e = created;
        }
        return e.join();
    }

    /** Whether the room's encoder is running. */
    public synchronized boolean running(String roomId) {
        Mp3Encoder e = encoders.get(roomId);
        return e != null && !e.isStopped();
    }

    /** Listeners of the room's encoder. */
    public synchronized int listeners(String roomId) {
        Mp3Encoder e = encoders.get(roomId);
        return e == null ? 0 : e.listenerCount();
    }

    /** The bitrate the room's encoder runs at, or {@code fallback} if none runs. */
    public synchronized int bitrateKbps(String roomId, int fallback) {
        Mp3Encoder e = encoders.get(roomId);
        return e == null || e.isStopped() ? fallback : e.bitrateKbps();
    }

    private void idle(String roomId) {
        try {
            timer.schedule(() -> stopIfIdle(roomId), lingerMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // shutting down
        }
    }

    /** Stops the room's encoder if it still has no listeners. */
    void stopIfIdle(String roomId) {
        Mp3Encoder e;
        synchronized (this) {
            e = encoders.get(roomId);
            if (e == null || e.listenerCount() > 0) {
                return;
            }
            encoders.remove(roomId);
        }
        e.close();
    }

    /** Stops the room's encoder now (its listeners' connections end). */
    public void stop(String roomId) {
        Mp3Encoder e;
        synchronized (this) {
            e = encoders.remove(roomId);
        }
        if (e != null) {
            e.close();
        }
    }

    @Override
    public void close() {
        java.util.List<Mp3Encoder> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(encoders.values());
            encoders.clear();
        }
        timer.shutdownNow();
        for (Mp3Encoder e : all) {
            e.close();
        }
    }
}
