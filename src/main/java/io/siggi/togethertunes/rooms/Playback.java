package io.siggi.togethertunes.rooms;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

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
     * Play order of the queued entries: pinned entries first, then by queue position, then by
     * time added. The control-mode step refines this per mode (votes in {@code vote} mode).
     */
    static final Comparator<QueueEntry> PLAY_ORDER = Comparator
            .comparing((QueueEntry e) -> !e.pinned())
            .thenComparingDouble(QueueEntry::position)
            .thenComparingLong(QueueEntry::addedAt)
            .thenComparing(QueueEntry::id);

    /** The queued entries in the order they will play (a new list). */
    public static List<QueueEntry> playOrder(RoomState state) {
        List<QueueEntry> out = new ArrayList<>(state.queue());
        out.sort(PLAY_ORDER);
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
     * so entries whose ingest finished in the meantime are eligible.
     *
     * @return the entry now playing, or {@code null} if the room is idle
     */
    public static QueueEntry advance(RoomTx tx, QueueEntry.State endedAs) throws SQLException {
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
                RoomStore.setEntryState(c, roomId, id, endedAs, null);
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
            RoomStore.setEntryState(c, roomId, playing.id(), QueueEntry.State.PLAYING, now);
            return null;
        });
        tx.event(new RoomEvent.EntryStateChanged(playing.id(), QueueEntry.State.PLAYING));
        s.removeFromQueue(playing.id());
        s.current(playing);
        tx.timeline(Timeline.playing(playing.id(), now, 0));
        return playing;
    }

    /**
     * Starts the next eligible entry if the room is idle (for example after something was added
     * to an empty queue, or an item became ready).
     *
     * @return whether it started something
     */
    public static boolean startIfIdle(RoomTx tx) throws SQLException {
        return switch (tx.state().timeline().state()) {
            case IDLE -> {
                RoomState s = tx.state();
                s.refreshItems(tx.store().itemInfo(s));
                yield nextEligible(s) != null && advance(tx, QueueEntry.State.PLAYED) != null;
            }
            case PLAYING, PAUSED -> false;
        };
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
        if (current == null || current.durationMs() == null) {
            return OptionalLong.empty();
        }
        OptionalLong end = state.timeline().endsAtServerMs(current.durationMs());
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
                } else if (current.durationMs() != null && t.positionAt(tx.now()) >= current.durationMs()) {
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
        return current == null || current.durationMs() == null ? pos : Math.min(pos, current.durationMs());
    }
}
