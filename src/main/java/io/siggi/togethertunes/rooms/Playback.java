package io.siggi.togethertunes.rooms;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The room timeline's state machine ({@code docs/rooms.md#room-state}): play, pause, seek,
 * advancing to the next entry, and resuming after a restart. Every change re-anchors the
 * {@link Timeline} through {@link RoomTx#timeline}, and the executor then reschedules the advance
 * task ({@link #advanceDelayMs}) for the new anchor.
 *
 * <p>These are the primitives only: they run inside a {@link RoomMutation} on the room's
 * executor and check no permissions (the queue operations do that). Entries whose item isn't
 * ready ({@link QueueEntry#ready()}) stay in the queue and are skipped over when choosing the
 * next entry; they become eligible as soon as their item is ready.
 *
 * <p>Every switch on {@link PlaybackState} is exhaustive with no {@code default}: a new state
 * must say what each operation means for it.
 */
public final class Playback {
    private Playback() {
    }

    /**
     * Manual play order ({@code owner} and {@code open} modes): by queue position, then time
     * added. The pinned flag doesn't count here (pins are a {@code vote} mode thing).
     */
    static final Comparator<QueueEntry> MANUAL_ORDER = Comparator
            .comparingDouble(QueueEntry::position)
            .thenComparingLong(QueueEntry::addedAt)
            .thenComparing(QueueEntry::id);

    /** Order of the suggestions: oldest first. */
    static final Comparator<QueueEntry> SUGGESTION_ORDER = Comparator
            .comparingLong(QueueEntry::addedAt)
            .thenComparing(QueueEntry::id);

    /**
     * Play order in {@code vote} mode: pinned entries first (in the order they were pinned, kept
     * in their positions), then by votes, descending, ties broken by time added.
     */
    static Comparator<QueueEntry> voteOrder(RoomState state) {
        return Comparator
                .comparing((QueueEntry e) -> !e.pinned())
                .thenComparingDouble(e -> e.pinned() ? e.position() : 0)
                .thenComparingInt(e -> e.pinned() ? 0 : -state.voteTotal(e.id()))
                .thenComparingLong(QueueEntry::addedAt)
                .thenComparingDouble(QueueEntry::position)
                .thenComparing(QueueEntry::id);
    }

    /** The order the room's control mode plays the queue in ({@code docs/rooms.md#control-modes}). */
    static Comparator<QueueEntry> order(RoomState state) {
        return switch (state.controlMode()) {
            case OWNER, OPEN -> MANUAL_ORDER;
            case VOTE -> voteOrder(state);
        };
    }

    /** The queued entries in the order they will play (a new list). */
    public static List<QueueEntry> playOrder(RoomState state) {
        List<QueueEntry> out = new ArrayList<>(state.queue());
        out.sort(order(state));
        return out;
    }

    /** The suggestions, oldest first (a new list). */
    public static List<QueueEntry> suggestionOrder(RoomState state) {
        List<QueueEntry> out = new ArrayList<>(state.suggestions());
        out.sort(SUGGESTION_ORDER);
        return out;
    }

    /** The first queued entry that is ready to play, or {@code null}. Not-ready entries are skipped over. */
    public static QueueEntry nextEligible(RoomState state) {
        for (QueueEntry e : playOrder(state)) {
            if (e.ready()) {
                return e;
            }
        }
        return null;
    }

    /**
     * Ends the current entry (if any) as {@code endedAs} ({@link QueueEntry.State#PLAYED} at its
     * natural end, {@link QueueEntry.State#SKIPPED} when skipped) and starts the next eligible
     * entry at position 0, or goes idle when there is none. Item statuses are read again first,
     * so entries whose ingest finished in the meantime are eligible. A skip is an abrupt start
     * ({@link #advance(RoomTx, QueueEntry.State, boolean)}); a natural end isn't.
     *
     * @return the entry now playing, or {@code null} if the room is idle
     */
    public static QueueEntry advance(RoomTx tx, QueueEntry.State endedAs) throws SQLException {
        return advance(tx, endedAs, endedAs == QueueEntry.State.SKIPPED);
    }

    /**
     * {@link #advance(RoomTx, QueueEntry.State)}; with {@code abrupt} (a skip, a start from idle:
     * anything but the natural end of the previous entry) the next entry starts at
     * {@link RoomTx#abruptStartAt()}, which is in the future while the room's program output
     * runs, so the stream plays it from 0:00 too ({@code docs/rooms.md#room-state}). At a natural
     * end the next entry starts after the room's transition gap
     * ({@link RoomSettings#transitionGapMs()}, {@code docs/rooms.md#transitions}), at once without one.
     */
    public static QueueEntry advance(RoomTx tx, QueueEntry.State endedAs, boolean abrupt) throws SQLException {
        if (endedAs != QueueEntry.State.PLAYED && endedAs != QueueEntry.State.SKIPPED) {
            throw new IllegalArgumentException("an entry ends as played or skipped, not " + endedAs);
        }
        RoomState s = tx.state();
        s.refreshItems(tx.store().itemInfo(s));
        QueueEntry next = nextEligible(s);
        String roomId = s.id();
        long now = tx.now();
        QueueEntry ended = s.current();
        if (ended != null) {
            String id = ended.id();
            tx.persist(c -> {
                RoomStore.setEntryState(c, roomId, id, endedAs, null, now);
                return null;
            });
            tx.event(new RoomEvent.EntryStateChanged(id, endedAs));
        }
        if (next == null) {
            s.current(null);
            tx.timeline(Timeline.IDLE);
            return null;
        }
        QueueEntry playing = next.withState(QueueEntry.State.PLAYING, now);
        tx.persist(c -> {
            RoomStore.setEntryState(c, roomId, playing.id(), QueueEntry.State.PLAYING, now, now);
            return null;
        });
        tx.event(new RoomEvent.EntryStateChanged(playing.id(), QueueEntry.State.PLAYING));
        s.forget(playing.id());
        s.current(playing);
        int gap = s.settings().transitionGapMs();
        if (abrupt) {
            tx.timeline(Timeline.startingAt(playing.id(), tx.abruptStartAt()));
        } else if (ended != null && gap > 0) {
            // the transition between items (docs/rooms.md#transitions): the next entry starts after
            // the gap, silent at 0 until then, like a delayed start
            tx.timeline(Timeline.startingAt(playing.id(), now + gap));
        } else {
            tx.timeline(Timeline.playing(playing.id(), now, 0));
        }
        return playing;
    }

    /**
     * Starts the next eligible entry if the room is idle (for example after something was added
     * to an empty queue, or an item became ready). An abrupt start: it may begin a moment later
     * while the program output runs ({@link #advance(RoomTx, QueueEntry.State, boolean)}).
     *
     * @return whether it started something
     */
    public static boolean startIfIdle(RoomTx tx) throws SQLException {
        return switch (tx.state().timeline().state()) {
            case IDLE -> {
                RoomState s = tx.state();
                s.refreshItems(tx.store().itemInfo(s));
                yield nextEligible(s) != null && advance(tx, QueueEntry.State.PLAYED, true) != null;
            }
            case PLAYING, PAUSED -> false;
        };
    }

    /**
     * Some item changed status ({@code docs/rooms.md#room-state}: an entry becomes eligible as
     * soon as its item is ready). Reads the status and duration of the items of the current,
     * queued and suggested entries again; the entries that changed are announced
     * ({@link RoomEvent.EntryItemsChanged}, so clients see e.g. {@code processing -> ready} or
     * {@code failed}). Then a current entry whose item can never play now ({@code failed},
     * {@code purged}) is skipped, and an idle room starts the next eligible entry. A room that is
     * playing or paused keeps going; the entry that turned ready is picked up when the timeline
     * advances. Failed entries stay in the queue (shown as failed) and are skipped over.
     *
     * @return whether anything changed
     */
    public static boolean itemsChanged(RoomTx tx) throws SQLException {
        RoomState s = tx.state();
        Map<String, QueueEntry> before = new HashMap<>();
        if (s.current() != null) {
            before.put(s.current().id(), s.current());
        }
        for (QueueEntry e : s.queue()) {
            before.put(e.id(), e);
        }
        for (QueueEntry e : s.suggestions()) {
            before.put(e.id(), e);
        }
        s.refreshItems(tx.store().itemInfo(s));
        List<String> changed = new ArrayList<>();
        Consumer<QueueEntry> check = e -> {
            QueueEntry old = before.get(e.id());
            if (old != null && !old.equals(e)) {
                changed.add(e.id());
            }
        };
        if (s.current() != null) {
            check.accept(s.current());
        }
        s.queue().forEach(check);
        s.suggestions().forEach(check);
        if (!changed.isEmpty()) {
            tx.event(new RoomEvent.EntryItemsChanged(changed));
        }
        QueueEntry current = s.current();
        if (current != null && ("failed".equals(current.itemStatus()) || "purged".equals(current.itemStatus()))) {
            advance(tx, QueueEntry.State.SKIPPED);
            return true;
        }
        if (current != null && changed.contains(current.id())) {
            tx.rescheduleAdvance(); // its duration may have changed
        }
        return startIfIdle(tx) || !changed.isEmpty();
    }

    /**
     * Resumes a paused timeline from its position; when idle, starts the next eligible entry.
     *
     * @return whether the timeline changed
     */
    public static boolean play(RoomTx tx) throws SQLException {
        Timeline t = tx.state().timeline();
        return switch (t.state()) {
            case PAUSED -> {
                tx.timeline(Timeline.playing(t.entryId(), tx.now(), clamp(tx.state(), t.anchorPositionMs())));
                yield true;
            }
            case IDLE -> startIfIdle(tx);
            case PLAYING -> false;
        };
    }

    /**
     * Pauses a playing timeline at its current position.
     *
     * @return whether the timeline changed
     */
    public static boolean pause(RoomTx tx) {
        Timeline t = tx.state().timeline();
        return switch (t.state()) {
            case PLAYING -> {
                tx.timeline(Timeline.paused(t.entryId(), tx.now(), clamp(tx.state(), t.positionAt(tx.now()))));
                yield true;
            }
            case PAUSED, IDLE -> false;
        };
    }

    /**
     * Moves the position in the current entry to {@code positionMs} (clamped to the entry), in the
     * same state. A seek to the end of a playing entry advances right away (the advance task is
     * due).
     *
     * @return whether the timeline changed ({@code false} when idle)
     */
    public static boolean seek(RoomTx tx, long positionMs) {
        Timeline t = tx.state().timeline();
        long pos = clamp(tx.state(), positionMs);
        return switch (t.state()) {
            case PLAYING -> {
                tx.timeline(Timeline.playing(t.entryId(), tx.now(), pos));
                yield true;
            }
            case PAUSED -> {
                tx.timeline(Timeline.paused(t.entryId(), tx.now(), pos));
                yield true;
            }
            case IDLE -> false;
        };
    }

    /**
     * Milliseconds from {@code nowMs} until the current entry ends and the advance task must run
     * ({@code 0} if overdue), or empty if the timeline doesn't move towards an end: idle, paused,
     * or an entry whose duration isn't known.
     */
    static OptionalLong advanceDelayMs(RoomState state, long nowMs) {
        QueueEntry current = state.current();
        if (current == null || current.lengthMs() == null) {
            return OptionalLong.empty();
        }
        OptionalLong end = state.timeline().endsAtServerMs(current.lengthMs());
        return end.isPresent() ? OptionalLong.of(Math.max(0, end.getAsLong() - nowMs)) : OptionalLong.empty();
    }

    /**
     * The advance task: if the current entry has reached its end, ends it as played and moves on
     * ({@link #advance}). If it ran early (or the timeline moved since), asks for a reschedule.
     */
    static void advanceIfDue(RoomTx tx) throws SQLException {
        OptionalLong delay = advanceDelayMs(tx.state(), tx.now());
        if (delay.isEmpty()) {
            return;
        }
        if (delay.getAsLong() > 0) {
            tx.rescheduleAdvance();
            return;
        }
        advance(tx, QueueEntry.State.PLAYED);
    }

    /**
     * Run once when a room's executor starts (at server start for every playing room): a playing
     * room resumes from its computed position, or from the start of the entry if the server was
     * down for longer than the entry's remaining time. A current entry whose item can't be played
     * any more (its item isn't {@code ready}, e.g. purged) is skipped. Paused and idle rooms stay
     * as they are. If the timeline is kept, nothing is written. The advance task is scheduled
     * afterwards.
     */
    static void resume(RoomTx tx) throws SQLException {
        RoomState s = tx.state();
        Timeline t = s.timeline();
        switch (t.state()) {
            case PLAYING -> {
                s.refreshItems(tx.store().itemInfo(s));
                QueueEntry current = s.current();
                if (!"ready".equals(current.itemStatus())) {
                    advance(tx, QueueEntry.State.SKIPPED);
                } else if (current.lengthMs() != null && t.positionAt(tx.now()) >= current.lengthMs()) {
                    tx.timeline(Timeline.playing(current.id(), tx.now(), 0));
                }
            }
            case PAUSED, IDLE -> {
            }
        }
        tx.rescheduleAdvance();
    }

    /** {@code positionMs} clamped to {@code [0, duration of the current entry]} (or just {@code >= 0}). */
    private static long clamp(RoomState state, long positionMs) {
        QueueEntry current = state.current();
        long pos = Math.max(0, positionMs);
        return current == null || current.lengthMs() == null ? pos : Math.min(pos, current.lengthMs());
    }
}
