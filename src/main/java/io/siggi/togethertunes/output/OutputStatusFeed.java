package io.siggi.togethertunes.output;

import io.siggi.togethertunes.rooms.RoomOutputs;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * What the room pages see of the rooms' outputs ({@code docs/api.md#websocket}): the active
 * outputs of a room for its {@code state} ({@link #active}), and an {@code output.status} for
 * every change, found by comparing each room's outputs with what was last sent.
 *
 * <p>An output is listed while it is active (row enabled AND server switch). RTMP shows the push's
 * state ({@code connecting}, {@code connected}, {@code retrying}, {@code error}) with the last
 * ffmpeg message as {@code detail} (the key is already masked by {@link RtmpPusher}); a push that
 * isn't running yet is {@code connecting}, and one that can't run (no ffmpeg) is {@code error}.
 * {@code .ts}, HLS and MP3 are {@code ready} (MP3 without ffmpeg: {@code error}). An output that stops being active is sent once as
 * {@code off}.
 *
 * <p>Checks run on one worker thread, in order: a room's after its push changed status
 * ({@link #roomChanged}), every room with outputs after each reconcile of
 * {@link StreamOutputControl} ({@link #allChanged}: API changes, admin settings, every 10 s).
 */
public final class OutputStatusFeed implements RoomOutputs, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(OutputStatusFeed.class.getName());

    /** Where changes go ({@code RoomSocket#outputStatus}). Must not block. */
    @FunctionalInterface
    public interface Sink {
        void send(String roomId, RoomOutputs.Status status);
    }

    static final String NO_FFMPEG = "This server can't push RTMP: ffmpeg isn't configured";
    static final String NO_FFMPEG_MP3 = "This server can't encode MP3: ffmpeg isn't configured";

    private final StreamOutputs rows;
    private final StreamOutputControl control;
    private final Sink sink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("output-status").factory());
    /** Per room: kind → what was last sent. Worker thread only. */
    private final Map<String, Map<String, RoomOutputs.Status>> last = new HashMap<>();

    public OutputStatusFeed(StreamOutputs rows, StreamOutputControl control, Sink sink) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.control = Objects.requireNonNull(control, "control");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public List<RoomOutputs.Status> active(String roomId) throws SQLException {
        List<RoomOutputs.Status> out = new ArrayList<>();
        for (StreamOutputs.Output o : rows.list(roomId)) {
            if (o.enabled() && control.serverEnabled(o.kind())) {
                out.add(status(o));
            }
        }
        return out;
    }

    private RoomOutputs.Status status(StreamOutputs.Output o) {
        String kind = o.kind().wire();
        if (o.kind() == StreamOutputs.Kind.MP3 && !control.mp3Available()) {
            return new RoomOutputs.Status(kind, "error", NO_FFMPEG_MP3);
        }
        if (o.kind().hasToken()) {
            return new RoomOutputs.Status(kind, "ready", null);
        }
        if (!control.rtmpAvailable()) {
            return new RoomOutputs.Status(kind, "error", NO_FFMPEG);
        }
        RtmpPusher.Status s = control.rtmpStatus(o.roomId());
        if (s == null || s.state() == RtmpPusher.State.STOPPED) {
            return new RoomOutputs.Status(kind, "connecting", null);
        }
        return new RoomOutputs.Status(kind, s.state().name().toLowerCase(Locale.ROOT), s.detail());
    }

    /** The room's outputs may have changed (e.g. its push changed status). Any thread; never blocks. */
    public void roomChanged(String roomId) {
        submit(() -> refresh(roomId));
    }

    /** Any room's outputs may have changed. Any thread; never blocks. */
    public void allChanged() {
        submit(this::refreshAll);
    }

    private void submit(Runnable r) {
        try {
            worker.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Could not check the rooms' outputs", e);
                }
            });
        } catch (RejectedExecutionException closed) {
            // shutting down
        }
    }

    /** Waits until everything submitted so far is done (tests). */
    void flush() throws Exception {
        worker.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
    }

    private void refreshAll() {
        Set<String> rooms = new HashSet<>(last.keySet());
        try {
            rooms.addAll(rows.roomIds());
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the rooms' outputs", e);
            return;
        }
        for (String roomId : rooms) {
            refresh(roomId);
        }
    }

    private void refresh(String roomId) {
        Map<String, RoomOutputs.Status> now = new LinkedHashMap<>();
        try {
            for (RoomOutputs.Status s : active(roomId)) {
                now.put(s.kind(), s);
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the outputs of room " + roomId, e);
            return;
        }
        Map<String, RoomOutputs.Status> before = last.getOrDefault(roomId, Map.of());
        for (Map.Entry<String, RoomOutputs.Status> e : before.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                sink.send(roomId, new RoomOutputs.Status(e.getKey(), "off", null));
            }
        }
        for (RoomOutputs.Status s : now.values()) {
            if (!s.equals(before.get(s.kind()))) {
                sink.send(roomId, s);
            }
        }
        if (now.isEmpty()) {
            last.remove(roomId);
        } else {
            last.put(roomId, now);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
