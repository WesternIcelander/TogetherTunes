package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The alone state ({@code docs/ui.md#alone-and-in-rooms}, {@code docs/rooms.md#alone}): a user who
 * isn't in a room plays their own music with their own play status and queue, kept on the server.
 *
 * <p><b>Design: a private pseudo-room.</b> Each user's alone state is an ordinary room run by the
 * room engine ({@link RoomExecutor}, {@link Playback}, {@link QueueOps}, the room socket), marked
 * by {@code rooms.alone_user_id} ({@link Rooms#aloneRoom}): {@code private}, {@code owner} control
 * mode, owned by the user, never listed, not counted against {@link Rooms#MAX_OWNED}, and only that
 * user can open it (not even {@code rooms.moderateAny}). It can't be edited, deleted, given members
 * or stream outputs ({@code 409 alone_room}). So the sync engine and clients work the same alone and
 * in a room: a client plays the alone room through the same room socket and messages, and the
 * room's {@code state} says {@code room.alone: true}. Its queue is the room queue (the manually added
 * section first, as in rooms). Two devices of the same user are simply two sockets on that room.
 *
 * <p><b>Where the user is</b> ({@code alone_sessions}): {@code room_id} is the room they joined
 * ({@code null}: alone). Moving between rooms goes through this class:
 * <ul>
 *   <li>{@link #join}: joining a room <i>saves</i> the alone state: the alone room is paused
 *       (remembering whether it was playing, {@code resume_playing}) and stays as it is.</li>
 *   <li>{@link #moveIntoRoom} (creating a room with {@code moveAloneSession}): the current song
 *       (position, playing or paused) and the queue move into the new room; the alone room is left
 *       empty and the user is in the new room.</li>
 *   <li>{@link #leave}: back to being alone, with a choice: {@code fork} copies the room's current
 *       song, position, play state and queue into the alone state (replacing the saved one);
 *       {@code restore} brings the saved alone state back (playing again if it was playing).</li>
 *   <li>A room deleted while users are in it ({@link #roomDeleting}, in the delete's transaction):
 *       they are no longer in a room, and the session keeps the room's name and what a fork would
 *       copy ({@code fork_json}) until they choose with {@link #leave} (or join another room).</li>
 * </ul>
 * The alone room's state carries {@code room.awayInRoomId} (the room the user is in while it is
 * saved), so every device hears about a join or leave; the session itself is
 * {@code GET /api/me/play-session}. Guests and anonymous visitors have no alone state ({@code 401}).
 *
 * <p>Changes of one user's session are serialised (striped locks; one server).
 */
public final class AloneSessions {
    /** What leaving a room to be alone does with the alone state. */
    public enum Choice {
        FORK, RESTORE;

        public static Choice parse(String s) {
            for (Choice c : values()) {
                if (c.name().toLowerCase(Locale.ROOT).equals(s)) {
                    return c;
                }
            }
            return null;
        }
    }

    /**
     * What is copied between rooms: the current item (or {@code null}), its position, whether it
     * was playing, and the queued items in play order. Also stored as {@code fork_json}.
     */
    public record Snapshot(String currentItemId, long positionMs, boolean playing, List<String> queue) {
        public static final Snapshot EMPTY = new Snapshot(null, 0, false, List.of());

        public Snapshot {
            queue = queue == null ? List.of() : List.copyOf(queue);
            positionMs = Math.max(0, positionMs);
        }
    }

    /** A room deleted while the user was in it, waiting for their fork-or-restore choice. */
    public record DeletedRoom(String name) {
    }

    /**
     * The user's session: their alone room, the room they are in ({@code null}: alone), and a room
     * deleted while they were in it ({@code null} unless they still have to choose).
     */
    public record Session(String aloneRoomId, String roomId, DeletedRoom deletedRoom) {
    }

    private record Row(String roomId, boolean resumePlaying, String deletedRoomName, String forkJson) {
        static final Row NONE = new Row(null, false, null, null);
    }

    private final Database db;
    private final Rooms rooms;
    private final RoomRegistry registry;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public AloneSessions(Database db, Rooms rooms, RoomRegistry registry, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.rooms = Objects.requireNonNull(rooms);
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lock(String userId) {
        return locks[Math.floorMod(userId.hashCode(), locks.length)];
    }

    @FunctionalInterface
    private interface Locked<T> {
        T run() throws Exception;
    }

    private <T> T locked(String userId, Locked<T> work) throws Exception {
        ReentrantLock l = lock(userId);
        l.lock();
        try {
            return work.run();
        } finally {
            l.unlock();
        }
    }

    // ------------------------------------------------------------------ the session row

    private Row row(String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT room_id, resume_playing, deleted_room_name,"
                    + " fork_json FROM alone_sessions WHERE user_id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Row(rs.getString(1), rs.getInt(2) != 0, rs.getString(3), rs.getString(4))
                            : Row.NONE;
                }
            }
        });
    }

    private void save(String userId, Row row) throws SQLException {
        long now = EpochMs.now(clock);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO alone_sessions (user_id, room_id,"
                    + " resume_playing, deleted_room_name, fork_json, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (user_id) DO UPDATE SET room_id = excluded.room_id,"
                    + " resume_playing = excluded.resume_playing, deleted_room_name = excluded.deleted_room_name,"
                    + " fork_json = excluded.fork_json, updated_at = excluded.updated_at")) {
                ps.setString(1, userId);
                ps.setString(2, row.roomId());
                ps.setInt(3, row.resumePlaying() ? 1 : 0);
                ps.setString(4, row.deletedRoomName());
                ps.setString(5, row.forkJson());
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Session session(String aloneRoomId, Row row) {
        return new Session(aloneRoomId, row.roomId(),
                row.deletedRoomName() == null ? null : new DeletedRoom(row.deletedRoomName()));
    }

    // ------------------------------------------------------------------ API

    /** The user's session; makes their alone room on first use. */
    public Session get(Actor.User user) throws Exception {
        String uid = user.userId();
        return locked(uid, () -> session(rooms.aloneRoom(uid), row(uid)));
    }

    /**
     * The user joins a room (any room they may open, {@link Rooms#access}). Coming from the alone
     * state saves it: the alone room is paused, remembering whether it played. From another room,
     * the saved alone state stays as it is. Joining the room they are in changes nothing.
     *
     * @throws HttpError as {@link Rooms#access}; {@code 409 alone_room} for an alone room
     */
    public Session join(Actor.User user, String idOrSlug) throws Exception {
        String uid = user.userId();
        return locked(uid, () -> {
            String aloneId = rooms.aloneRoom(uid);
            Rooms.Access a = rooms.access(user, idOrSlug);
            if (a.room().alone()) {
                throw new HttpError(409, "alone_room", "That is your own play session; leave the room to be alone");
            }
            String target = a.room().id();
            Row row = row(uid);
            if (target.equals(row.roomId())) {
                return session(aloneId, row);
            }
            boolean resume = row.resumePlaying();
            if (row.roomId() == null && row.deletedRoomName() == null) {
                // alone until now: save the alone state (paused, remembering whether it played)
                resume = registry.call(aloneId, tx -> {
                    boolean playing = tx.state().timeline().state() == PlaybackState.PLAYING;
                    Playback.pause(tx);
                    away(tx, target);
                    return playing;
                });
            } else {
                registry.call(aloneId, tx -> {
                    away(tx, target);
                    return null;
                });
            }
            Row joined = new Row(target, resume, null, null);
            save(uid, joined);
            return session(aloneId, joined);
        });
    }

    /**
     * The user leaves the room they are in (or the room deleted while they were in it) to be alone.
     * {@code fork}: the room's current song, position, play state and queue replace the alone
     * state (for a deleted room: as it was when it was deleted). {@code restore}: the saved alone
     * state comes back, playing again if it was playing when they joined.
     *
     * @throws HttpError {@code 409 not_in_room} (alone already); for a fork from a room they may no
     *                   longer open, as {@link Rooms#access}
     */
    public Session leave(Actor.User user, Choice choice) throws Exception {
        Objects.requireNonNull(choice);
        String uid = user.userId();
        return locked(uid, () -> {
            String aloneId = rooms.aloneRoom(uid);
            Row row = row(uid);
            if (row.roomId() == null && row.deletedRoomName() == null) {
                throw new HttpError(409, "not_in_room", "You are not in a room");
            }
            switch (choice) {
                case FORK -> {
                    Snapshot snap;
                    if (row.roomId() != null) {
                        String roomId = rooms.access(user, row.roomId()).room().id();
                        snap = registry.call(roomId, tx -> snapshot(tx.state(), tx.now()));
                    } else {
                        snap = row.forkJson() == null ? Snapshot.EMPTY
                                : Json.GSON.fromJson(row.forkJson(), Snapshot.class);
                    }
                    Snapshot copy = snap;
                    registry.call(aloneId, tx -> {
                        load(tx, copy, uid);
                        away(tx, null);
                        return null;
                    });
                }
                case RESTORE -> registry.call(aloneId, tx -> {
                    if (row.resumePlaying()) {
                        Playback.play(tx);
                    }
                    away(tx, null);
                    return null;
                });
            }
            save(uid, Row.NONE);
            return session(aloneId, Row.NONE);
        });
    }

    /**
     * The user created {@code newRoomId} and takes their alone state into it: its current song
     * (with its position; playing if the user was alone and it played, else paused) and its queue
     * move into the new room, the alone room is left empty, and the user is in the new room.
     * The caller has checked that the user owns the new room.
     */
    public Session moveIntoRoom(Actor.User user, String newRoomId) throws Exception {
        String uid = user.userId();
        return locked(uid, () -> {
            String aloneId = rooms.aloneRoom(uid);
            Row row = row(uid);
            boolean alone = row.roomId() == null && row.deletedRoomName() == null;
            Snapshot snap = registry.call(aloneId, tx -> {
                Snapshot s = snapshot(tx.state(), tx.now());
                load(tx, Snapshot.EMPTY, uid);
                away(tx, newRoomId);
                return s;
            });
            Snapshot moved = alone ? snap : new Snapshot(snap.currentItemId(), snap.positionMs(), false, snap.queue());
            registry.call(newRoomId, tx -> {
                load(tx, moved, uid);
                return null;
            });
            Row joined = new Row(newRoomId, false, null, null);
            save(uid, joined);
            return session(aloneId, joined);
        });
    }

    /**
     * Called in a room's delete transaction ({@link Rooms#delete(io.siggi.togethertunes.auth.Actor,
     * String, String, Rooms.BeforeDelete)}), on its executor thread with its live state: the users
     * in it are no longer in a room, and their sessions keep the room's name and what a fork
     * copies.
     *
     * @return the users who were in it (pass them to {@link #roomDeleted} after the delete)
     */
    List<String> roomDeleting(Connection c, RoomState state, long now) throws SQLException {
        List<String> users = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM alone_sessions WHERE room_id = ?")) {
            ps.setString(1, state.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(rs.getString(1));
                }
            }
        }
        if (users.isEmpty()) {
            return users;
        }
        String fork = Json.GSON.toJson(snapshot(state, now));
        try (PreparedStatement ps = c.prepareStatement("UPDATE alone_sessions SET room_id = NULL,"
                + " deleted_room_name = ?, fork_json = ?, updated_at = ? WHERE room_id = ?")) {
            ps.setString(1, state.name());
            ps.setString(2, fork);
            ps.setLong(3, now);
            ps.setString(4, state.id());
            ps.executeUpdate();
        }
        return users;
    }

    /**
     * After a room was deleted: tells the alone rooms of the users who were in it (loaded ones) that
     * they aren't away any more, so their devices hear about it. Failures are logged.
     */
    public void roomDeleted(List<String> userIds) {
        for (String uid : userIds) {
            try {
                String aloneId = db.read(c -> Rooms.aloneRoomOf(c, uid));
                if (aloneId != null && registry.loaded(aloneId) != null) {
                    registry.call(aloneId, tx -> {
                        if (tx.state().awayInRoomId() != null) {
                            away(tx, null);
                        }
                        return null;
                    });
                }
            } catch (Exception e) {
                System.getLogger(AloneSessions.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not update the alone room of user " + uid + " after a room was deleted", e);
            }
        }
    }

    // ------------------------------------------------------------------ on the room executors

    /** Sets the room an alone room's user is in, announced to its sockets. */
    private static void away(RoomTx tx, String roomId) {
        RoomState s = tx.state();
        if (!Objects.equals(s.awayInRoomId(), roomId)) {
            s.alone(s.aloneUserId(), roomId);
            tx.event(new RoomEvent.RoomUpdated());
        }
    }

    /** What {@code state} plays now (on its executor thread). */
    static Snapshot snapshot(RoomState state, long now) {
        QueueEntry current = state.current();
        Timeline t = state.timeline();
        long position = 0;
        if (current != null) {
            Long length = current.lengthMs();
            position = length == null ? Math.max(0, t.positionAt(now)) : t.positionAt(now, length);
        }
        List<String> queue = new ArrayList<>();
        for (QueueEntry e : Playback.playOrder(state)) {
            queue.add(e.itemId());
        }
        return new Snapshot(current == null ? null : current.itemId(), position,
                current != null && t.state() == PlaybackState.PLAYING, queue);
    }

    /**
     * Replaces what the room plays with {@code snap}: every queued and suggested entry and the
     * current one are removed ({@code removed}: not in the history), then the snapshot's current
     * item plays (or is paused) at its position and its queue follows, all added by
     * {@code userId}. Items that can't be played any more (failed, purged, gone) are left out; a
     * current item that isn't ready goes to the front of the queue instead. Nothing is recorded
     * in the play history (the song didn't start).
     */
    static void load(RoomTx tx, Snapshot snap, String userId) throws SQLException {
        RoomState s = tx.state();
        String roomId = s.id();
        long now = tx.now();
        List<QueueEntry> waiting = new ArrayList<>(s.queue());
        waiting.addAll(s.suggestions());
        QueueEntry old = s.current();
        if (old != null) {
            waiting.add(old);
        }
        for (QueueEntry e : waiting) {
            tx.persist(c -> {
                RoomStore.setEntryState(c, roomId, e.id(), QueueEntry.State.REMOVED, null, now);
                return null;
            });
            s.forget(e.id());
            tx.event(new RoomEvent.EntryStateChanged(e.id(), QueueEntry.State.REMOVED));
        }
        Set<String> ids = new LinkedHashSet<>(snap.queue());
        if (snap.currentItemId() != null) {
            ids.add(snap.currentItemId());
        }
        Map<String, RoomStore.ItemRow> items = new HashMap<>();
        if (!ids.isEmpty()) {
            tx.store().db().read(c -> {
                for (String id : ids) {
                    RoomStore.ItemRow row = UuidV7.isValid(id) ? RoomStore.item(c, id) : null;
                    if (row != null) {
                        items.put(id, row);
                    }
                }
                return null;
            });
        }
        boolean audioOnly = s.settings().audioOnly();
        List<String> queueIds = new ArrayList<>(snap.queue());
        QueueEntry current = null;
        if (snap.currentItemId() != null) {
            RoomStore.ItemRow item = items.get(snap.currentItemId());
            if (item != null) {
                QueueEntry e = new QueueEntry(UuidV7.newId(), item.id(), userId, null, now, QueueEntry.State.PLAYING,
                        0, false, now, item.status(), item.durationMs(), tx.store().filesUsable(item),
                        item.audioOnlyMs(), audioOnly);
                if (e.ready()) {
                    current = e;
                } else {
                    queueIds.add(0, item.id());
                }
            }
        }
        if (current != null) {
            QueueEntry playing = current;
            tx.persist(c -> {
                RoomStore.insertEntry(c, roomId, playing);
                RoomStore.setEntryState(c, roomId, playing.id(), QueueEntry.State.PLAYING, now, now);
                References.touch(c, playing.itemId(), now);
                return null;
            });
            tx.event(new RoomEvent.EntryStateChanged(playing.id(), QueueEntry.State.PLAYING));
        }
        double position = 1;
        for (String itemId : queueIds) {
            RoomStore.ItemRow item = items.get(itemId);
            if (item == null || !playable(item.status())) {
                continue;
            }
            QueueEntry e = new QueueEntry(UuidV7.newId(), item.id(), userId, null, now, QueueEntry.State.QUEUED,
                    position++, false, null, item.status(), item.durationMs(), tx.store().filesUsable(item),
                    item.audioOnlyMs(), audioOnly);
            tx.persist(c -> {
                RoomStore.insertEntry(c, roomId, e);
                References.touch(c, e.itemId(), now);
                return null;
            });
            s.addToQueue(e);
            tx.event(new RoomEvent.EntryAdded(e.id(), QueueEntry.State.QUEUED));
        }
        if (current != null) {
            s.current(current);
            long pos = Math.min(snap.positionMs(), current.lengthMs());
            tx.timeline(snap.playing() ? Timeline.playing(current.id(), now, pos)
                    : Timeline.paused(current.id(), now, pos));
        } else {
            if (s.timeline().state() != PlaybackState.IDLE || s.current() != null) {
                tx.timeline(Timeline.IDLE);
            }
            if (snap.playing()) {
                Playback.startIfIdle(tx);
            }
        }
    }

    private static boolean playable(String status) {
        return switch (status) {
            case "ready", "pending", "processing" -> true;
            default -> false;
        };
    }
}
