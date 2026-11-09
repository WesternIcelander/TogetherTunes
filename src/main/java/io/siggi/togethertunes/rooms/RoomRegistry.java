package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live rooms: one {@link RoomExecutor} per room, loaded from the database on first use and
 * kept until the room is deleted or the server stops. Thread-safe.
 */
public final class RoomRegistry implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RoomRegistry.class.getName());
    private static final int CLOSED_RETRIES = 3;

    private final RoomStore store;
    private final Clock clock;
    private final PackagerHook packager;
    private final LivePlayingItems playing = new LivePlayingItems();
    private final Map<String, RoomExecutor> executors = new ConcurrentHashMap<>();
    /** Serialises loading (a ReentrantLock, not synchronized: loading does JDBC on virtual threads). */
    private final ReentrantLock loadLock = new ReentrantLock();
    private volatile boolean closed;

    public RoomRegistry(Database db, Clock clock, PackagerHook packager) {
        this.store = new RoomStore(db);
        this.clock = Objects.requireNonNull(clock);
        this.packager = Objects.requireNonNull(packager);
    }

    /**
     * The room's executor, loading the room if needed.
     *
     * @throws HttpError {@code 404 room_not_found} (no such room id), {@code 503 shutting_down}
     */
    public RoomExecutor executor(String roomId) throws SQLException {
        RoomExecutor e = executors.get(roomId);
        if (e != null && !e.isClosed()) {
            return e;
        }
        loadLock.lock();
        try {
            e = executors.get(roomId);
            if (e != null && !e.isClosed()) {
                return e;
            }
            if (closed) {
                throw new HttpError(503, "shutting_down", "The server is stopping");
            }
            RoomState state = roomId == null ? null : store.load(roomId);
            if (state == null) {
                throw new HttpError(404, "room_not_found", "No such room");
            }
            e = new RoomExecutor(state, store, clock, packager, playing, this::closed);
            executors.put(roomId, e);
            e.start(); // resume first: queued ahead of whatever the caller submits
            return e;
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * Run once at server start: loads every room whose timeline was playing, so each one resumes
     * ({@link Playback#resume}) and its advance task runs without waiting for someone to open the
     * room. Rooms that fail to load are logged and skipped.
     *
     * @return how many rooms were loaded
     */
    public int resumePlaying() throws SQLException {
        List<String> ids = store.db().read(c -> {
            List<String> out = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM rooms WHERE playback_state = ? ORDER BY id")) {
                ps.setString(1, PlaybackState.PLAYING.wire());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
        int loaded = 0;
        for (String id : ids) {
            try {
                executor(id);
                loaded++;
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not resume room " + id, e);
            }
        }
        return loaded;
    }

    /**
     * The current and next items of every loaded room, kept up to date by the room executors:
     * what guests may fetch from {@code /media} when {@code guests.download} is off.
     */
    public PlayingItems playingItems() {
        return playing;
    }

    /** The room's executor if it is loaded, else {@code null}. */
    public RoomExecutor loaded(String roomId) {
        RoomExecutor e = executors.get(roomId);
        return e == null || e.isClosed() ? null : e;
    }

    /** How many rooms are loaded. */
    public int loadedCount() {
        return executors.size();
    }

    /**
     * Runs a mutation on the room's executor (loading it if needed) and waits for it. If the
     * executor closes first, retries on a fresh one, so a deleted room ends in {@code 404}.
     */
    public <T> T call(String roomId, RoomMutation<T> mutation) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return executor(roomId).call(mutation);
            } catch (RoomClosedException e) {
                if (attempt >= CLOSED_RETRIES) {
                    throw e;
                }
            }
        }
    }

    private void closed(RoomExecutor e) {
        executors.remove(e.roomId(), e);
    }

    /** Closes every room executor after its queued work and waits up to 5 s for them. */
    @Override
    public void close() {
        closed = true;
        List<RoomExecutor> all;
        loadLock.lock();
        try {
            all = new ArrayList<>(executors.values());
        } finally {
            loadLock.unlock();
        }
        for (RoomExecutor e : all) {
            e.close();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (RoomExecutor e : all) {
            try {
                long left = Math.max(0, deadline - System.nanoTime());
                if (!e.awaitTermination(Duration.ofNanos(left))) {
                    LOG.log(System.Logger.Level.WARNING, "Room " + e.roomId() + " didn't stop in time");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
