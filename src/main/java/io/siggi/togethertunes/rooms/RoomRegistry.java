package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.ItemStatusListener;
import io.siggi.togethertunes.ingest.PlayableFiles;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live rooms: one {@link RoomExecutor} per room, loaded from the database on first use and
 * kept until the room is deleted or the server stops. Thread-safe.
 *
 * <p>It is also the rooms' {@link ItemStatusListener}: when an item's status changes (ingest
 * finished or failed, a re-ingest started, a purge), every room with an entry for it hears about
 * it ({@link #itemStatusChanged}).
 */
public final class RoomRegistry implements AutoCloseable, ItemStatusListener {
    private static final System.Logger LOG = System.getLogger(RoomRegistry.class.getName());
    private static final int CLOSED_RETRIES = 3;

    private final RoomStore store;
    private final Clock clock;
    private final PackagerHook packager;
    private final LivePlayingItems playing = new LivePlayingItems();
    private final RoomViewers viewers = new RoomViewers(playing);
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
     * Run once at server start, after the rooms became the ingest's {@link ItemStatusListener}:
     * idle rooms with a queued entry whose item is ready (its ingest finished while no listener was
     * set, e.g. during start-up or a crash) start it, as {@link #itemStatusChanged} would have.
     *
     * @return how many rooms were told
     */
    public int startIdleWithReadyEntries() throws SQLException {
        List<String> ids = store.db().read(c -> {
            List<String> out = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT DISTINCT r.id FROM rooms r"
                    + " JOIN queue_entries q ON q.room_id = r.id JOIN items i ON i.id = q.item_id"
                    + " WHERE r.playback_state = ? AND q.state = ? AND i.status = 'ready' ORDER BY r.id")) {
                ps.setString(1, PlaybackState.IDLE.wire());
                ps.setString(2, QueueEntry.State.QUEUED.wire());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
        int told = 0;
        for (String id : ids) {
            try {
                executor(id).submit(Playback::itemsChanged);
                told++;
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not load idle room " + id, e);
            }
        }
        return told;
    }

    /**
     * The current and next items of every loaded room, kept up to date by the room executors:
     * what the guests and anonymous visitors in each room may fetch from {@code /media}.
     */
    public PlayingItems playingItems() {
        return playing;
    }

    /**
     * Who is in which room, and the media access rule built on it ({@link GuestMediaAccess}): a
     * guest or anonymous visitor gets an item's media only while a room they are in plays it or
     * plays it next. The room socket keeps it up to date.
     */
    public RoomViewers mediaAccess() {
        return viewers;
    }

    /**
     * What the loaded rooms are waiting on, ranked by how soon it plays: the ingest claim order
     * ({@code docs/library-and-ingest.md#priorities}). {@code listener} is told (on the thread
     * that changed a room) whenever the ranks change.
     */
    public io.siggi.togethertunes.ingest.WantedItems wantedItems(Runnable listener) {
        playing.changeListener(listener);
        return playing;
    }

    /** The database the rooms live in. */
    Database db() {
        return store.db();
    }

    /** The rooms' clock. */
    Clock clock() {
        return clock;
    }

    /** The room's executor if it is loaded, else {@code null}. */
    public RoomExecutor loaded(String roomId) {
        RoomExecutor e = executors.get(roomId);
        return e == null || e.isClosed() ? null : e;
    }

    /**
     * The room's listener count: everyone in it right now, identified people (users, guests) once
     * each, in listen or remote mode, plus anonymous connections ({@link RoomPresence#counts}).
     * {@code 0} for a room that isn't loaded (nobody can be in it). Never loads the room.
     */
    public int listeners(String roomId) {
        RoomExecutor e = loaded(roomId);
        if (e == null) {
            return 0;
        }
        RoomPresence.Counts c = e.presence();
        return c.identified() + c.anonymous();
    }

    /**
     * A ban or timeout changed in a room ({@link RoomBans}); for a timeout ({@code until}, epoch
     * ms), the room broadcasts {@link RoomEvent.RestrictionsChanged} again once it ends, so the
     * sockets' {@code you} follows. Only while the room stays loaded (a room loaded later sends
     * {@code you.restriction.until}; the server checks every action anyway). Never loads the room.
     */
    public void timeoutSet(String roomId, Long until) {
        if (until == null) {
            return;
        }
        RoomExecutor e = loaded(roomId);
        if (e == null) {
            return;
        }
        try {
            e.schedule(java.time.Duration.ofMillis(Math.max(0, until - clock.millis()) + 1), tx -> {
                tx.event(new RoomEvent.RestrictionsChanged());
                return null;
            });
        } catch (RoomClosedException ignored) {
            // closed meanwhile: nobody to tell
        }
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

    /**
     * An item's status changed: every room that has it queued, suggested or current (loaded or
     * not: an idle room may now start it) runs {@link Playback#itemsChanged} on its executor,
     * which refreshes the entries, broadcasts the change and starts an idle room. Returns at once;
     * the lookup runs on a virtual thread. Failures are logged.
     */
    @Override
    public void itemStatusChanged(String itemId) {
        if (closed || itemId == null) {
            return;
        }
        Thread.ofVirtual().name("item-status-" + itemId).start(() -> {
            try {
                notifyRooms(itemId);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Could not tell the rooms about item " + itemId, t);
            }
        });
    }

    /**
     * Sets how the rooms tell whether the program output can play a {@code ready} item's files
     * ({@link PlayableFiles}): such an item is skipped over like one still processing. Default
     * {@link PlayableFiles.Check#ANY} (the item status alone). Set before rooms are loaded.
     */
    public void playableFiles(PlayableFiles.Check check) {
        store.playable(check);
    }

    /** Autoplay's randomness ({@code docs/rooms.md#autoplay}); a seeded one in tests. Set before rooms load. */
    public void autoplayRandom(java.util.Random random) {
        store.random(random);
    }

    /**
     * The autoplay selections of a room changed, or someone there lost the right to select
     * ({@code docs/rooms.md#autoplay}): the room (loaded if needed) drops autoplay entries whose
     * song is no longer selected and fills its autoplay section again. Waits for it; failures are
     * logged, never thrown (the change itself is already committed).
     */
    public void autoplayChanged(String roomId) {
        if (closed || roomId == null) {
            return;
        }
        try {
            call(roomId, tx -> {
                tx.refreshAutoplay();
                return null;
            });
        } catch (Exception e) {
            if (!(e instanceof RoomClosedException) && !(e instanceof HttpError)) {
                LOG.log(System.Logger.Level.WARNING, "Room " + roomId + " could not refresh its autoplay", e);
            }
        }
    }

    /**
     * A user's playlists changed (an entry added or removed, a playlist deleted, a like, an item
     * removed from their library): every room where they selected playlists for autoplay, or have
     * autoplay entries waiting, refreshes its autoplay ({@link #autoplayChanged}). Waits for them.
     */
    public void playlistsChanged(String userId) {
        if (closed || userId == null) {
            return;
        }
        List<String> roomIds;
        try {
            roomIds = store.db().read(c -> {
                java.util.Set<String> out = new java.util.TreeSet<>();
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT DISTINCT room_id"
                        + " FROM room_autoplay_selections WHERE user_id = ?")) {
                    ps.setString(1, userId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(rs.getString(1));
                        }
                    }
                }
                try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT DISTINCT room_id FROM queue_entries"
                        + " WHERE added_by_user_id = ? AND section = ? AND state = ?")) {
                    ps.setString(1, userId);
                    ps.setString(2, QueueEntry.Section.AUTOPLAY.wire());
                    ps.setString(3, QueueEntry.State.QUEUED.wire());
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(rs.getString(1));
                        }
                    }
                }
                return new ArrayList<>(out);
            });
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not find the rooms autoplaying the playlists of " + userId, e);
            return;
        }
        for (String roomId : roomIds) {
            autoplayChanged(roomId);
        }
    }

    /**
     * Every item's playability may have changed (the output encoding was switched, so items of the
     * old one wait for their re-encodes): every loaded room refreshes its entries
     * ({@link Playback#itemsChanged}). Rooms that aren't loaded read them when they load. Returns
     * at once.
     *
     * @return the number of rooms told
     */
    public int allItemsChanged() {
        int n = 0;
        for (RoomExecutor e : executors.values()) {
            if (closed || e.isClosed()) {
                continue;
            }
            try {
                e.submit(Playback::itemsChanged).whenComplete((changed, failure) -> {
                    if (failure != null && !(failure instanceof RoomClosedException)) {
                        LOG.log(System.Logger.Level.WARNING, "Room " + e.roomId() + " could not refresh its entries",
                                failure);
                    }
                });
                n++;
            } catch (RuntimeException ex) {
                // closing meanwhile
            }
        }
        return n;
    }

    /** {@link #itemStatusChanged}, on the calling thread; the futures complete when each room is done (tests). */
    List<CompletableFuture<Boolean>> notifyRooms(String itemId) throws SQLException {
        List<String> roomIds = store.db().read(c -> {
            List<String> out = new ArrayList<>();
            try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT DISTINCT room_id FROM queue_entries"
                    + " WHERE item_id = ? AND state IN (?, ?, ?) ORDER BY room_id")) {
                ps.setString(1, itemId);
                ps.setString(2, QueueEntry.State.QUEUED.wire());
                ps.setString(3, QueueEntry.State.SUGGESTED.wire());
                ps.setString(4, QueueEntry.State.PLAYING.wire());
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
        List<CompletableFuture<Boolean>> out = new ArrayList<>();
        for (String roomId : roomIds) {
            if (closed) {
                break;
            }
            try {
                CompletableFuture<Boolean> f = executor(roomId).submit(Playback::itemsChanged);
                f.whenComplete((changed, failure) -> {
                    if (failure != null && !(failure instanceof RoomClosedException)) {
                        LOG.log(System.Logger.Level.WARNING, "Room " + roomId + " could not take the status of item "
                                + itemId, failure);
                    }
                });
                out.add(f);
            } catch (HttpError e) {
                // Deleted meanwhile, or shutting down.
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not load room " + roomId + " for item " + itemId, e);
            }
        }
        return out;
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
