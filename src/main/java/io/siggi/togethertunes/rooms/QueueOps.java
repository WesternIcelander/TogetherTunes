package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.PlayableFiles;
import io.siggi.togethertunes.retention.References;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The queue and playback operations of {@code docs/rooms.md#queue-operations} that run on the
 * room's executor: {@code add}, {@code addPlaylist}, {@code remove}, {@code move}, {@code play},
 * {@code pause}, {@code seek}, {@code next} and {@code previous} (pins, votes, suggestions and
 * vote-skip are in {@link QueueControl}). Each runs inside a {@link RoomMutation}, checks
 * {@link ControlPolicy} and the room settings first and changes the state afterwards.
 *
 * <p>Nothing here blocks on anything but the database: an upload or a YouTube link is turned into
 * an item before the mutation runs ({@link RoomQueue}), and {@link #add} takes the item id.
 *
 * <p>Limits on requests ({@code docs/rooms.md#room-settings-settings_json}), which the owner and
 * moderators are exempt from: {@code requestsPerActor} (waiting entries, queued or suggested, per
 * user or guest: {@code 409 request_limit}) and {@code noRepeatWindow} (an item that started
 * playing in the last N entries, the current one included: {@code 409 recently_played}).
 */
public final class QueueOps {
    /** {@code previous} within this much of the start goes to the previous entry; later it restarts the current one. */
    public static final long PREVIOUS_RESTART_MS = 3000;

    /** {@code queue_entries.via} of an entry added from a playlist. */
    public static final String VIA_PLAYLIST = "playlist";
    /** {@code queue_entries.via} of an entry added again from the room's history. */
    public static final String VIA_HISTORY = "history";

    private QueueOps() {
    }

    /**
     * Whether an add by {@code a} is a request: not by the owner, nor by a moderator in
     * {@code owner} mode (who adds directly there).
     */
    static boolean isRequest(RoomState s, RoomActor a) {
        if (!a.identified() || (a.userId() != null && a.userId().equals(s.ownerId()))) {
            return false;
        }
        return !(a.moderates() && s.controlMode() == Rooms.ControlMode.OWNER);
    }

    /** What {@link #add} did: the new entry and where it landed ({@code queued} or {@code suggested}). */
    public record Added(String entryId, String itemId, QueueEntry.State state) {
    }

    /** What {@link #addPlaylist} did: the new entries in queue order, and the items left out (not playable). */
    public record PlaylistAdded(List<String> entryIds, int skipped) {
    }

    /**
     * The checks of {@link #add} that don't depend on the item: identity, request rights and
     * {@code requestsPerActor}. Read-only; {@link RoomQueue} runs it before importing a YouTube
     * link or resolving an upload, so a refused request costs nothing.
     *
     * @return where an add would land
     */
    public static ControlPolicy.AddAs checkAdd(RoomState s, RoomActor a) {
        ControlPolicy.AddAs as = ControlPolicy.add(s, a);
        if (!a.moderates()) {
            int limit = s.controlSettings().requestsPerActor();
            if (waitingBy(s, a) >= limit) {
                throw new HttpError(409, "request_limit", "You already have " + limit
                        + (limit == 1 ? " request" : " requests") + " waiting in this room");
            }
        }
        return as;
    }

    /**
     * Adds an item: to the end of the queue, or as a suggestion when {@link ControlPolicy#add}
     * says so ({@code owner} mode, not the owner or a moderator). An idle room starts playing it
     * if it is ready. The item's {@code last_referenced_at} is set to now.
     *
     * @throws HttpError as {@link #checkAdd}; {@code 404 item_not_found}, {@code 409 item_failed}
     *                   (a failed item), {@code 409 media_expired} (a purged one; {@link RoomQueue}
     *                   refetches purged YouTube items first), {@code 409 recently_played}
     */
    public static Added add(RoomTx tx, RoomActor a, String itemId) throws SQLException {
        return add(tx, a, itemId, null);
    }

    /**
     * {@link #add(RoomTx, RoomActor, String)}, recording how the item was added on the entry
     * ({@code queue_entries.via}, shown in the history). A request ({@link #isRequest}: not by the
     * owner, nor by a moderator in {@code owner} mode) with a {@code via} also raises
     * {@link RoomEvent.RequestAdded} ({@code request.new} to the owner and moderators).
     *
     * @param via {@code library}, {@code upload}, {@code youtube} or {@code history}; {@code null}
     *            records nothing and raises no event
     */
    public static Added add(RoomTx tx, RoomActor a, String itemId, String via) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.AddAs as = checkAdd(s, a);
        RoomStore.ItemRow item = itemId == null || !UuidV7.isValid(itemId) ? null
                : tx.store().db().read(c -> RoomStore.item(c, itemId));
        if (item == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        requirePlayable(item);
        if (!a.moderates()) {
            int window = s.controlSettings().noRepeatWindow();
            List<String> recent = window <= 0 ? List.of()
                    : tx.store().db().read(c -> RoomStore.recentlyPlayedItems(c, s.id(), window));
            if (recent.contains(item.id())) {
                throw new HttpError(409, "recently_played", "That was played in the last " + window
                        + (window == 1 ? " entry" : " entries") + " here; try again later");
            }
        }
        QueueEntry e = insert(tx, a, item, as.entryState(), QueueControl.endPosition(s), via);
        if (via != null && isRequest(s, a)) {
            tx.event(new RoomEvent.RequestAdded(e.id(), via));
        }
        if (e.state() == QueueEntry.State.QUEUED) {
            Playback.startIfIdle(tx);
        }
        return new Added(e.id(), item.id(), e.state());
    }

    /**
     * Adds items (a playlist's, in its order, shuffled by the caller if asked) to the end of the
     * queue: owner and moderators only, so no request limits apply. Items that can't be played
     * any more (failed, purged, gone) are left out. An idle room starts the first ready one.
     *
     * @throws HttpError as {@link ControlPolicy#requireAddPlaylist}
     */
    public static PlaylistAdded addPlaylist(RoomTx tx, RoomActor a, List<String> itemIds) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.requireAddPlaylist(s, a);
        List<RoomStore.ItemRow> items = tx.store().db().read(c -> {
            List<RoomStore.ItemRow> out = new ArrayList<>();
            for (String id : itemIds) {
                RoomStore.ItemRow row = id == null ? null : RoomStore.item(c, id);
                out.add(row);
            }
            return out;
        });
        double position = QueueControl.endPosition(s);
        List<String> added = new ArrayList<>();
        int skipped = 0;
        for (RoomStore.ItemRow item : items) {
            if (item == null || !playable(item.status())) {
                skipped++;
                continue;
            }
            added.add(insert(tx, a, item, QueueEntry.State.QUEUED, position++, VIA_PLAYLIST).id());
        }
        if (!added.isEmpty()) {
            Playback.startIfIdle(tx);
        }
        return new PlaylistAdded(List.copyOf(added), skipped);
    }

    /**
     * Removes a queued entry or a suggestion ({@code removed}); see {@link ControlPolicy#requireRemove}.
     * The current entry isn't removed (skip it with {@link #next}). Its item's
     * {@code last_referenced_at} is set to now.
     *
     * @throws HttpError {@code 404 entry_not_found}, as {@link ControlPolicy#requireRemove}
     */
    public static void remove(RoomTx tx, RoomActor a, String entryId) {
        RoomState s = tx.state();
        a.requireIdentity();
        QueueEntry e = entryId == null ? null : s.waitingEntry(entryId);
        if (e == null) {
            throw new HttpError(404, "entry_not_found", "No such entry in the queue");
        }
        ControlPolicy.requireRemove(s, a, e);
        String roomId = s.id();
        long now = tx.now();
        tx.persist(c -> {
            RoomStore.setEntryState(c, roomId, e.id(), QueueEntry.State.REMOVED, null, now);
            return null;
        });
        s.forget(e.id());
        tx.event(new RoomEvent.EntryStateChanged(e.id(), QueueEntry.State.REMOVED));
    }

    /**
     * Moves a queued entry before {@code beforeEntryId}, or to the end ({@code null}), in a manual
     * queue ({@code owner} and {@code open} modes; see {@link ControlPolicy#requireMove}). The new
     * position lies between its neighbours; when there is no room left between them, the queue's
     * positions are rewritten to {@code 1, 2, 3, …}.
     *
     * @return whether the order changed
     * @throws HttpError {@code 404 entry_not_found}, {@code 409 before_entry_not_found}, as
     *                   {@link ControlPolicy#requireMove}
     */
    public static boolean move(RoomTx tx, RoomActor a, String entryId, String beforeEntryId) {
        RoomState s = tx.state();
        ControlPolicy.requireMove(s, a);
        QueueEntry e = queued(s, entryId);
        if (e == null) {
            throw new HttpError(404, "entry_not_found", "No such entry in the queue");
        }
        QueueEntry before = null;
        if (beforeEntryId != null) {
            before = queued(s, beforeEntryId);
            if (before == null) {
                throw new HttpError(409, "before_entry_not_found", "The entry to move it before isn't in the queue");
            }
            if (before.id().equals(e.id())) {
                return false;
            }
        }
        List<QueueEntry> order = Playback.playOrder(s);
        int from = order.indexOf(e);
        List<QueueEntry> others = new ArrayList<>(order);
        others.remove(from);
        int to = before == null ? others.size() : others.indexOf(before);
        if (to == from) {
            return false;
        }
        double prev = to == 0 ? Double.NaN : others.get(to - 1).position();
        double next = to == others.size() ? Double.NaN : others.get(to).position();
        double position;
        if (Double.isNaN(prev) && Double.isNaN(next)) {
            return false;
        } else if (Double.isNaN(prev)) {
            position = next - 1;
        } else if (Double.isNaN(next)) {
            position = Math.floor(prev) + 1;
        } else {
            position = (prev + next) / 2;
        }
        String roomId = s.id();
        boolean between = (Double.isNaN(prev) || prev < position) && (Double.isNaN(next) || position < next);
        if (between) {
            QueueEntry moved = e.withPlace(position, e.pinned());
            tx.persist(c -> {
                QueueControl.setPlace(c, roomId, moved);
                return null;
            });
            s.addToQueue(moved);
        } else {
            // No room left between the neighbours: number the whole queue again.
            others.add(to, e);
            for (int i = 0; i < others.size(); i++) {
                QueueEntry placed = others.get(i).withPlace(i + 1, others.get(i).pinned());
                tx.persist(c -> {
                    QueueControl.setPlace(c, roomId, placed);
                    return null;
                });
                s.addToQueue(placed);
            }
        }
        tx.event(new RoomEvent.EntryMoved(e.id(), before == null ? null : before.id()));
        return true;
    }

    /**
     * Resumes a paused room, or starts the next ready entry of an idle one.
     *
     * @return whether the timeline changed
     * @throws HttpError as {@link ControlPolicy#requireControlPlayback}
     */
    public static boolean play(RoomTx tx, RoomActor a) throws SQLException {
        ControlPolicy.requireControlPlayback(tx.state(), a);
        return Playback.play(tx);
    }

    /**
     * Pauses a playing room.
     *
     * @return whether the timeline changed
     * @throws HttpError as {@link ControlPolicy#requireControlPlayback}
     */
    public static boolean pause(RoomTx tx, RoomActor a) {
        ControlPolicy.requireControlPlayback(tx.state(), a);
        return Playback.pause(tx);
    }

    /**
     * Seeks in the current entry (clamped to it; a seek to the end of a playing entry moves on).
     *
     * @throws HttpError {@code 400 invalid_position} (negative), {@code 409 nothing_playing}, as
     *                   {@link ControlPolicy#requireControlPlayback}
     */
    public static void seek(RoomTx tx, RoomActor a, long positionMs) {
        ControlPolicy.requireControlPlayback(tx.state(), a);
        if (positionMs < 0) {
            throw new HttpError(400, "invalid_position", "The position must be 0 or more");
        }
        if (!Playback.seek(tx, positionMs)) {
            throw nothingPlaying();
        }
    }

    /**
     * Skips the current entry ({@code skipped}) and plays the next ready one, or goes idle.
     *
     * @return the entry now playing, or {@code null} when the room went idle
     * @throws HttpError {@code 409 nothing_playing}, as {@link ControlPolicy#requireControlPlayback}
     */
    public static QueueEntry next(RoomTx tx, RoomActor a) throws SQLException {
        ControlPolicy.requireControlPlayback(tx.state(), a);
        if (tx.state().current() == null) {
            throw nothingPlaying();
        }
        return Playback.advance(tx, QueueEntry.State.SKIPPED);
    }

    /**
     * Goes back: more than {@link #PREVIOUS_RESTART_MS} into the current entry restarts it.
     * Otherwise the entry that played (or was skipped) last plays again from the start, and the
     * current entry goes back to the front of the queue (in {@code vote} mode as the top pin, so
     * it plays next); a paused room stays paused. With nothing to go back to, the current entry
     * restarts. An idle room plays the last entry again.
     *
     * @return whether the timeline changed
     * @throws HttpError as {@link ControlPolicy#requireControlPlayback}
     */
    public static boolean previous(RoomTx tx, RoomActor a) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.requireControlPlayback(s, a);
        Timeline t = s.timeline();
        QueueEntry current = s.current();
        boolean paused = switch (t.state()) {
            case PLAYING, IDLE -> false;
            case PAUSED -> true;
        };
        if (current != null && t.positionAt(tx.now()) > PREVIOUS_RESTART_MS) {
            return Playback.seek(tx, 0);
        }
        String roomId = s.id();
        PlayableFiles.Check playable = tx.store().playable();
        QueueEntry prev = tx.store().db().read(c -> RoomStore.lastPlayed(c, roomId,
                current == null ? null : current.id(), playable));
        if (prev == null || !prev.ready()) {
            return current != null && Playback.seek(tx, 0);
        }
        long now = tx.now();
        if (current != null) {
            QueueEntry requeued = current.withState(QueueEntry.State.QUEUED, null)
                    .withPlace(frontPosition(s), s.controlMode() == Rooms.ControlMode.VOTE);
            tx.persist(c -> {
                RoomStore.clearVotes(c, requeued.id());
                RoomStore.setEntryState(c, roomId, requeued.id(), QueueEntry.State.QUEUED, null, now);
                QueueControl.setPlace(c, roomId, requeued);
                return null;
            });
            s.addToQueue(requeued);
            tx.event(new RoomEvent.EntryStateChanged(requeued.id(), QueueEntry.State.QUEUED));
        }
        QueueEntry playing = prev.withState(QueueEntry.State.PLAYING, now).withPlace(prev.position(), false);
        tx.persist(c -> {
            RoomStore.clearVotes(c, playing.id());
            RoomStore.setEntryState(c, roomId, playing.id(), QueueEntry.State.PLAYING, now, now);
            QueueControl.setPlace(c, roomId, playing);
            return null;
        });
        tx.event(new RoomEvent.EntryStateChanged(playing.id(), QueueEntry.State.PLAYING));
        s.current(playing);
        tx.timeline(paused ? Timeline.paused(playing.id(), now, 0) : Timeline.playing(playing.id(), now, 0));
        return true;
    }

    /** How many waiting entries (queued or suggested) {@code a} added. */
    static int waitingBy(RoomState s, RoomActor a) {
        int n = 0;
        for (QueueEntry e : s.queue()) {
            if (a.added(e)) {
                n++;
            }
        }
        for (QueueEntry e : s.suggestions()) {
            if (a.added(e)) {
                n++;
            }
        }
        return n;
    }

    /**
     * A position before every queued entry: in a manual queue before the first one, in
     * {@code vote} mode (where the entry is pinned) before the first pin.
     */
    private static double frontPosition(RoomState s) {
        boolean vote = s.controlMode() == Rooms.ControlMode.VOTE;
        double min = Double.NaN;
        for (QueueEntry e : s.queue()) {
            if (vote && !e.pinned()) {
                continue;
            }
            min = Double.isNaN(min) ? e.position() : Math.min(min, e.position());
        }
        return Double.isNaN(min) ? 0 : Math.floor(min) - 1;
    }

    private static QueueEntry insert(RoomTx tx, RoomActor a, RoomStore.ItemRow item, QueueEntry.State state,
                                     double position, String via) {
        RoomState s = tx.state();
        long now = tx.now();
        QueueEntry e = new QueueEntry(UuidV7.newId(), item.id(), a.userId(), a.guestId(), now, state, position,
                false, null, item.status(), item.durationMs(), tx.store().filesUsable(item));
        String roomId = s.id();
        tx.persist(c -> {
            RoomStore.insertEntry(c, roomId, e, via);
            References.touch(c, e.itemId(), now);
            return null;
        });
        s.addToQueue(e);
        tx.event(new RoomEvent.EntryAdded(e.id(), state));
        return e;
    }

    /** Whether an item with this status can still be played (now or once ingested). */
    private static boolean playable(String status) {
        return switch (status) {
            case "ready", "pending", "processing" -> true;
            default -> false;
        };
    }

    private static void requirePlayable(RoomStore.ItemRow item) {
        switch (item.status()) {
            case "ready", "pending", "processing" -> {
            }
            case "failed" -> throw new HttpError(409, "item_failed", "This item failed to ingest");
            case "purged" -> throw new HttpError(409, "media_expired", "This item's media has been deleted");
            default -> throw new HttpError(409, "item_unavailable", "This item can't be played");
        }
    }

    private static QueueEntry queued(RoomState s, String entryId) {
        QueueEntry e = entryId == null ? null : s.waitingEntry(entryId);
        return e == null || e.state() != QueueEntry.State.QUEUED ? null : e;
    }

    private static HttpError nothingPlaying() {
        return new HttpError(409, "nothing_playing", "Nothing is playing");
    }
}
