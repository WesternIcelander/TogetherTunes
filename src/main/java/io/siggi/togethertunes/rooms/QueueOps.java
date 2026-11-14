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

    /**
     * What {@link #addPlaylist} did: the new entries in queue order, where they landed
     * ({@code queued} or {@code suggested}), and the items left out: {@code skipped} (not
     * playable any more: failed, purged, gone), {@code recentlyPlayed} ({@code noRepeatWindow})
     * and {@code overLimit} (beyond the caller's {@code requestsPerActor}).
     */
    public record PlaylistAdded(List<String> entryIds, QueueEntry.State state, int skipped, int recentlyPlayed,
                                int overLimit) {
    }

    /** The most entries one {@link #move} takes. */
    public static final int MAX_MOVE = 500;

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
     * "Add All" of a playlist ({@code queue.addPlaylist}): its items (in its order, shuffled by the
     * caller if asked) are added to the end of the queue by the request rules, as if each was
     * requested by id ({@link ControlPolicy#addPlaylist}): the owner and moderators add them all
     * (no limits); anyone else with request rights adds them as requests (suggestions in
     * {@code owner} mode), leaving out items played in the last {@code noRepeatWindow} entries and
     * the ones beyond their {@code requestsPerActor} (at least one free place is needed:
     * {@code 409 request_limit}), each raising {@link RoomEvent.RequestAdded} ({@code via}
     * {@code playlist}). Items that can't be played any more (failed, purged, gone) are left out.
     * An idle room starts the first ready queued one. The caller checks the request path
     * ({@link ControlPolicy#requireSource} {@code library}) and {@code libraryRequests}.
     *
     * @throws HttpError as {@link #checkAdd}
     */
    public static PlaylistAdded addPlaylist(RoomTx tx, RoomActor a, List<String> itemIds) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.AddAs as = checkAdd(s, a);
        RoomQueue.requireLibraryRequests(s.settings(), a);
        boolean limited = !a.moderates();
        int window = limited ? s.controlSettings().noRepeatWindow() : 0;
        String roomId = s.id();
        List<RoomStore.ItemRow> items = tx.store().db().read(c -> {
            List<RoomStore.ItemRow> out = new ArrayList<>();
            for (String id : itemIds) {
                RoomStore.ItemRow row = id == null ? null : RoomStore.item(c, id);
                out.add(row);
            }
            return out;
        });
        List<String> recent = window <= 0 ? List.of()
                : tx.store().db().read(c -> RoomStore.recentlyPlayedItems(c, roomId, window));
        int room = limited ? s.controlSettings().requestsPerActor() - waitingBy(s, a) : Integer.MAX_VALUE;
        boolean request = isRequest(s, a);
        double position = QueueControl.endPosition(s);
        List<String> added = new ArrayList<>();
        int skipped = 0;
        int recentlyPlayed = 0;
        int overLimit = 0;
        for (RoomStore.ItemRow item : items) {
            if (item == null || !playable(item.status())) {
                skipped++;
            } else if (recent.contains(item.id())) {
                recentlyPlayed++;
            } else if (added.size() >= room) {
                overLimit++;
            } else {
                QueueEntry e = insert(tx, a, item, as.entryState(), position++, VIA_PLAYLIST);
                added.add(e.id());
                if (request) {
                    tx.event(new RoomEvent.RequestAdded(e.id(), VIA_PLAYLIST));
                }
            }
        }
        if (!added.isEmpty() && as == ControlPolicy.AddAs.QUEUE) {
            Playback.startIfIdle(tx);
        }
        return new PlaylistAdded(List.copyOf(added), as.entryState(), skipped, recentlyPlayed, overLimit);
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
     * Moves a queued entry before {@code beforeEntryId}, or to the end of its section
     * ({@code null}); {@link #move(RoomTx, RoomActor, List, String, QueueEntry.Section)} with one
     * entry.
     */
    public static boolean move(RoomTx tx, RoomActor a, String entryId, String beforeEntryId) {
        return move(tx, a, entryId == null ? null : List.of(entryId), beforeEntryId, null);
    }

    /**
     * Moves queued entries ({@code queue.move}, dragging in the queue; {@code docs/rooms.md#queue-editing}):
     * they go, in the given order, before {@code beforeEntryId}, or to the end of
     * {@code section} ({@code beforeEntryId} {@code null}; without a section, the end of the first
     * entry's section). The target section is {@code beforeEntryId}'s. Entries may come from both
     * sections: an autoplay entry moved into the manually added section becomes a manual entry
     * (keeping its adder, the selecting person), a manual one moved into the autoplay section
     * becomes part of it (its votes go; autoplay never removes it, see {@link Autoplay}). When
     * {@code beforeEntryId} is one of the moved entries, they go before the next entry after it
     * that stays. Only in a manual queue ({@code owner} and {@code open} modes; see
     * {@link ControlPolicy#requireMove}); suggestions and the current entry don't move. The new
     * positions are spread between the neighbours; when there is no room left between them, the
     * section's positions are rewritten to {@code 1, 2, 3, …}.
     *
     * @return whether the order or a section changed
     * @throws HttpError {@code 400 invalid_entry_ids} (none, more than {@link #MAX_MOVE}, or one
     *                   twice), {@code 404 entry_not_found}, {@code 409 before_entry_not_found}
     *                   (not a queued entry, or not in {@code section}), as
     *                   {@link ControlPolicy#requireMove}
     */
    public static boolean move(RoomTx tx, RoomActor a, List<String> entryIds, String beforeEntryId,
                               QueueEntry.Section section) {
        RoomState s = tx.state();
        ControlPolicy.requireMove(s, a);
        if (entryIds == null || entryIds.isEmpty() || entryIds.size() > MAX_MOVE) {
            throw new HttpError(400, "invalid_entry_ids", "Give 1 to " + MAX_MOVE + " entries to move");
        }
        java.util.LinkedHashMap<String, QueueEntry> moving = new java.util.LinkedHashMap<>();
        for (String id : entryIds) {
            QueueEntry e = queued(s, id);
            if (e == null) {
                throw new HttpError(404, "entry_not_found", "No such entry in the queue");
            }
            if (moving.put(e.id(), e) != null) {
                throw new HttpError(400, "invalid_entry_ids", "An entry is listed twice");
            }
        }
        QueueEntry before = null;
        if (beforeEntryId != null) {
            before = queued(s, beforeEntryId);
            if (before == null || section != null && before.section() != section) {
                throw new HttpError(409, "before_entry_not_found",
                        "The entry to move them before isn't in that part of the queue");
            }
        }
        QueueEntry.Section target = before != null ? before.section()
                : section != null ? section : moving.values().iterator().next().section();
        List<QueueEntry> old = Playback.sectionOrder(s, target);
        List<QueueEntry> others = new ArrayList<>();
        for (QueueEntry e : old) {
            if (!moving.containsKey(e.id())) {
                others.add(e);
            }
        }
        int to = others.size();
        if (before != null) {
            int from = old.indexOf(before);
            for (int i = from; i < old.size(); i++) {
                if (!moving.containsKey(old.get(i).id())) {
                    to = others.indexOf(old.get(i));
                    break;
                }
            }
        }
        List<QueueEntry> placed = new ArrayList<>(others);
        placed.addAll(to, moving.values());
        boolean sectionChange = false;
        for (QueueEntry e : moving.values()) {
            sectionChange |= e.section() != target;
        }
        if (!sectionChange && ids(placed).equals(ids(old))) {
            return false;
        }
        int k = moving.size();
        double prev = to == 0 ? Double.NaN : others.get(to - 1).position();
        double next = to == others.size() ? Double.NaN : others.get(to).position();
        double[] positions = new double[k];
        for (int i = 0; i < k; i++) {
            if (Double.isNaN(prev) && Double.isNaN(next)) {
                positions[i] = i + 1;
            } else if (Double.isNaN(prev)) {
                positions[i] = Math.floor(next) - k + i;
            } else if (Double.isNaN(next)) {
                positions[i] = Math.floor(prev) + 1 + i;
            } else {
                positions[i] = prev + (next - prev) * (i + 1) / (k + 1);
            }
        }
        boolean between = true;
        double last = prev;
        for (double p : positions) {
            between &= Double.isNaN(last) || last < p;
            last = p;
        }
        between &= Double.isNaN(next) || last < next;
        String roomId = s.id();
        int i = 0;
        for (QueueEntry e : placed) {
            boolean moved = moving.containsKey(e.id());
            double position;
            if (between) {
                if (!moved) {
                    continue;
                }
                position = positions[i++];
            } else {
                // No room left between the neighbours: number the whole section again.
                position = placed.indexOf(e) + 1;
            }
            place(tx, roomId, e, position, target);
        }
        for (QueueEntry e : moving.values()) {
            tx.event(new RoomEvent.EntryMoved(e.id(), before == null ? null : before.id()));
        }
        return true;
    }

    /** Puts a queued entry at {@code position} in {@code section} (unpinned), in the state and the database. */
    private static void place(RoomTx tx, String roomId, QueueEntry e, double position, QueueEntry.Section section) {
        RoomState s = tx.state();
        if (e.section() == section) {
            QueueEntry moved = e.withPlace(position, e.pinned());
            tx.persist(c -> {
                QueueControl.setPlace(c, roomId, moved);
                return null;
            });
            s.addToQueue(moved);
            return;
        }
        QueueEntry moved = e.withPlace(position, false).withSection(section, e.addedByUserId(), e.addedByGuestId());
        boolean toAutoplay = section == QueueEntry.Section.AUTOPLAY;
        tx.persist(c -> {
            if (toAutoplay) {
                RoomStore.clearVotes(c, moved.id());
            }
            RoomStore.setSectionAndPlace(c, roomId, moved);
            return null;
        });
        if (toAutoplay) {
            s.forget(e.id());
        }
        s.addToQueue(moved);
        tx.event(new RoomEvent.EntrySectionChanged(moved.id(), section));
    }

    private static List<String> ids(List<QueueEntry> entries) {
        List<String> out = new ArrayList<>(entries.size());
        for (QueueEntry e : entries) {
            out.add(e.id());
        }
        return out;
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
            // back to the front of the manually added section, so it plays next (an autoplay
            // entry becomes manual, keeping who it was added for)
            QueueEntry requeued = current.withState(QueueEntry.State.QUEUED, null)
                    .withPlace(frontPosition(s), s.controlMode() == Rooms.ControlMode.VOTE)
                    .withSection(QueueEntry.Section.MANUAL, current.addedByUserId(), current.addedByGuestId());
            tx.persist(c -> {
                RoomStore.clearVotes(c, requeued.id());
                RoomStore.setEntryState(c, roomId, requeued.id(), QueueEntry.State.QUEUED, null, now);
                RoomStore.setSection(c, roomId, requeued);
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
        PlayHistory.started(tx, playing);
        s.current(playing);
        // an abrupt start (docs/rooms.md#room-state): put off until the program output can play it from 0:00
        tx.timeline(paused ? Timeline.paused(playing.id(), now, 0) : Timeline.startingAt(playing.id(), tx.abruptStartAt()));
        return true;
    }

    /**
     * {@code queue.request} (↑ on a "From Playlist" entry, {@code docs/rooms.md#autoplay}): turns an
     * autoplay entry into the actor's request. It is checked like a request of its item by id
     * ({@link #checkAdd}: identity, request rights, {@code requestsPerActor}; the room setting
     * {@code libraryRequests} and the library request right), except {@code noRepeatWindow} (it
     * was queued already). Where a request lands, so does the entry: at the end of the manually
     * added section ({@code queued}), or as a suggestion in {@code owner} mode for everyone but the
     * owner and moderators. It is now added by the actor (who may remove it, and whose request it
     * counts as). In {@code vote} mode it also gets the actor's vote when they may vote. Raises
     * {@link RoomEvent.RequestAdded} with {@code via} {@code autoplay} when it is a request
     * ({@link #isRequest}). Autoplay then fills its section again.
     *
     * @return where it landed ({@code queued} or {@code suggested})
     * @throws HttpError {@code 404 entry_not_found} (not a waiting entry), {@code 409 not_autoplay}
     *                   (a request or suggestion already), as {@link ControlPolicy#requireSource}
     *                   and {@link #checkAdd}, {@code 403 library_requests_off}
     */
    public static QueueEntry.State request(RoomTx tx, RoomActor a, String entryId) {
        RoomState s = tx.state();
        ControlPolicy.requireSource(a, "library");
        QueueEntry e = entryId == null ? null : s.waitingEntry(entryId);
        if (e == null) {
            throw new HttpError(404, "entry_not_found", "No such entry in the queue");
        }
        if (!e.autoplay()) {
            throw new HttpError(409, "not_autoplay", "This song is already a request");
        }
        ControlPolicy.AddAs as = checkAdd(s, a);
        RoomQueue.requireLibraryRequests(s.settings(), a);
        QueueEntry.State state = as.entryState();
        QueueEntry requested = e.withState(state, null).withPlace(QueueControl.endPosition(s), false)
                .withSection(QueueEntry.Section.MANUAL, a.userId(), a.guestId());
        String roomId = s.id();
        tx.persist(c -> {
            RoomStore.clearVotes(c, requested.id());
            RoomStore.setSection(c, roomId, requested);
            return null;
        });
        s.forget(e.id());
        s.addToQueue(requested);
        tx.event(new RoomEvent.EntrySectionChanged(requested.id(), QueueEntry.Section.MANUAL));
        if (state == QueueEntry.State.SUGGESTED) {
            tx.event(new RoomEvent.EntryStateChanged(requested.id(), state));
        }
        boolean voteMode = s.controlMode() == Rooms.ControlMode.VOTE;
        if (voteMode && state == QueueEntry.State.QUEUED && a.canVote()) {
            QueueControl.vote(tx, a, requested.id(), 1);
        }
        if (isRequest(s, a)) {
            tx.event(new RoomEvent.RequestAdded(requested.id(), Autoplay.VIA_AUTOPLAY));
        }
        return state;
    }

    /** How many waiting entries (queued or suggested) {@code a} added; autoplay entries don't count. */
    static int waitingBy(RoomState s, RoomActor a) {
        int n = 0;
        for (QueueEntry e : s.queue()) {
            if (a.added(e) && !e.autoplay()) {
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
            if (vote && !e.pinned() || e.autoplay()) {
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
                false, null, item.status(), item.durationMs(), tx.store().filesUsable(item), item.audioOnlyMs(),
                s.settings().audioOnly());
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
