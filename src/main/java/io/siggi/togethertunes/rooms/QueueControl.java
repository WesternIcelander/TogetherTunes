package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * The control-mode operations on a room's queue ({@code docs/rooms.md#control-modes}): votes
 * (and downvotes), pins, accepting and rejecting suggestions, vote-skip, and keeping the play
 * order when the control mode changes. Each runs inside a {@link RoomMutation} on the room's
 * executor, checks {@link ControlPolicy} first and changes the state afterwards.
 *
 * <p>The play order itself is {@link Playback#playOrder}: manual (queue position) in
 * {@code owner} and {@code open} mode; pinned entries, then votes (descending, ties by time
 * added) in {@code vote} mode.
 */
public final class QueueControl {
    private QueueControl() {
    }

    /** {@link #voteSkip}'s result: the skip votes and how many are needed, and whether it skipped. */
    public record VoteSkip(int votes, int needed, boolean skipped) {
    }

    /**
     * Sets the actor's vote on a queued entry: {@code 1}, {@code -1} (with {@code allowDownvotes})
     * or {@code 0} to take it back. Voting the same value again changes nothing.
     *
     * @return the entry's new vote total
     * @throws HttpError as {@link ControlPolicy#requireVote}, {@code 404 entry_not_found}
     */
    public static int vote(RoomTx tx, RoomActor a, String entryId, int value) {
        RoomState s = tx.state();
        ControlPolicy.requireVote(s, a, value);
        QueueEntry e = queued(s, entryId);
        requireManual(e, "Request this song to vote on it");
        String key = a.key();
        if (s.voteOf(e.id(), key) == value) {
            return s.voteTotal(e.id());
        }
        String userId = a.userId();
        String guestId = a.guestId();
        long now = tx.now();
        tx.persist(c -> {
            String column = userId != null ? "voter_user_id" : "voter_guest_id";
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM votes WHERE entry_id = ? AND " + column + " = ?")) {
                ps.setString(1, e.id());
                ps.setString(2, userId != null ? userId : guestId);
                ps.executeUpdate();
            }
            if (value != 0) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO votes (entry_id, voter_user_id,"
                        + " voter_guest_id, value, at) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, e.id());
                    ps.setString(2, userId);
                    ps.setString(3, guestId);
                    ps.setInt(4, value);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        s.vote(e.id(), key, value);
        int total = s.voteTotal(e.id());
        tx.event(new RoomEvent.VotesChanged(e.id(), total));
        return total;
    }

    /**
     * Pins a queued entry to the top ({@code vote} mode; owner and moderators), below the entries
     * pinned before it, or unpins it.
     *
     * @return whether anything changed
     * @throws HttpError as {@link ControlPolicy#requirePin}, {@code 404 entry_not_found}
     */
    public static boolean pin(RoomTx tx, RoomActor a, String entryId, boolean pinned) {
        RoomState s = tx.state();
        ControlPolicy.requirePin(s, a);
        QueueEntry e = queued(s, entryId);
        requireManual(e, "Request this song to pin it");
        if (e.pinned() == pinned) {
            return false;
        }
        // A new pin goes below the existing ones: pinned entries play in position order.
        double position = pinned ? endPosition(s) : e.position();
        QueueEntry changed = e.withPlace(position, pinned);
        String roomId = s.id();
        tx.persist(c -> {
            setPlace(c, roomId, changed);
            return null;
        });
        s.addToQueue(changed);
        tx.event(new RoomEvent.EntryPinned(e.id(), pinned));
        return true;
    }

    /**
     * Accepts a suggestion: it joins the end of the queue (owner and moderators, in any mode, so
     * suggestions left over from {@code owner} mode can still be handled). An idle room starts it.
     *
     * @throws HttpError as {@link ControlPolicy#requireDecideSuggestion}, {@code 404 suggestion_not_found}
     */
    public static void accept(RoomTx tx, RoomActor a, String entryId) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.requireDecideSuggestion(s, a);
        QueueEntry e = suggestion(s, entryId);
        QueueEntry queued = e.withState(QueueEntry.State.QUEUED, null).withPlace(endPosition(s), false);
        String roomId = s.id();
        long now = tx.now();
        tx.persist(c -> {
            RoomStore.setEntryState(c, roomId, queued.id(), QueueEntry.State.QUEUED, null, now);
            setPlace(c, roomId, queued);
            return null;
        });
        s.addToQueue(queued);
        tx.event(new RoomEvent.EntryStateChanged(queued.id(), QueueEntry.State.QUEUED));
        Playback.startIfIdle(tx);
    }

    /**
     * Rejects a suggestion: it becomes {@code removed} (owner and moderators, in any mode). Its
     * item's {@code last_referenced_at} is touched (an unreference event).
     *
     * @throws HttpError as {@link ControlPolicy#requireDecideSuggestion}, {@code 404 suggestion_not_found}
     */
    public static void reject(RoomTx tx, RoomActor a, String entryId) {
        RoomState s = tx.state();
        ControlPolicy.requireDecideSuggestion(s, a);
        QueueEntry e = suggestion(s, entryId);
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
     * Votes to skip the current entry ({@code vote} mode). When the skip votes reach the threshold
     * ({@link ControlSettings#skipVotesNeeded} of the identified members present), the entry is
     * skipped. Voting twice counts once.
     *
     * @throws HttpError as {@link ControlPolicy#requireVoteSkip}, {@code 409 nothing_playing}
     */
    public static VoteSkip voteSkip(RoomTx tx, RoomActor a) throws SQLException {
        RoomState s = tx.state();
        ControlPolicy.requireVoteSkip(s, a);
        QueueEntry current = s.current();
        if (current == null) {
            throw new HttpError(409, "nothing_playing", "Nothing is playing");
        }
        String key = a.key();
        int needed = s.controlSettings().skipVotesNeeded(s.presentIdentified());
        if (!s.skipVotes().contains(key)) {
            String userId = a.userId();
            String guestId = a.guestId();
            long now = tx.now();
            tx.persist(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO skip_votes (entry_id, voter_user_id,"
                        + " voter_guest_id, at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, current.id());
                    ps.setString(2, userId);
                    ps.setString(3, guestId);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return null;
            });
            s.addSkipVote(key);
            tx.event(new RoomEvent.SkipVotesChanged(current.id(), s.skipVotes().size(), needed));
        }
        int votes = s.skipVotes().size();
        return new VoteSkip(votes, needed, checkVoteSkip(tx));
    }

    /**
     * Skips the current entry if its skip votes have reached the threshold: after a skip vote, and
     * after presence dropped (fewer members present need fewer votes). Only in {@code vote} mode
     * with vote-skip on.
     *
     * @return whether it skipped
     */
    public static boolean checkVoteSkip(RoomTx tx) throws SQLException {
        RoomState s = tx.state();
        ControlSettings settings = s.controlSettings();
        boolean voteMode = switch (s.controlMode()) {
            case VOTE -> true;
            case OWNER, OPEN -> false;
        };
        if (!voteMode || settings.voteSkipThreshold() == null || s.current() == null || s.skipVotes().isEmpty()) {
            return false;
        }
        if (s.skipVotes().size() < settings.skipVotesNeeded(s.presentIdentified())) {
            return false;
        }
        Playback.advance(tx, QueueEntry.State.SKIPPED);
        return true;
    }

    /**
     * Keeps the play order across a control-mode change: call it <b>before</b> the state takes
     * the new mode over. The queue's positions are rewritten to the old mode's play order
     * ({@code 1, 2, 3, …}) and pins are cleared, so a queue ordered by votes stays in that order
     * when the owner takes manual control, and a manual order is the starting point in
     * {@code vote} mode. Suggestions stay; the owner and moderators can still accept or reject
     * them in any mode.
     */
    public static void controlModeChanging(RoomTx tx, Rooms.ControlMode newMode) {
        RoomState s = tx.state();
        if (s.controlMode() == newMode) {
            return;
        }
        List<QueueEntry> order = Playback.playOrder(s);
        String roomId = s.id();
        boolean changed = false;
        for (int i = 0; i < order.size(); i++) {
            QueueEntry e = order.get(i);
            double position = i + 1;
            if (e.position() == position && !e.pinned()) {
                continue;
            }
            QueueEntry placed = e.withPlace(position, false);
            tx.persist(c -> {
                setPlace(c, roomId, placed);
                return null;
            });
            s.addToQueue(placed);
            changed = true;
        }
        if (changed) {
            tx.event(new RoomEvent.QueueReordered());
        }
    }

    /** A position after every queued entry (the end of a manual queue). */
    static double endPosition(RoomState s) {
        double max = 0;
        for (QueueEntry e : s.queue()) {
            max = Math.max(max, e.position());
        }
        return Math.floor(max) + 1;
    }

    private static QueueEntry queued(RoomState s, String entryId) {
        QueueEntry e = entryId == null ? null : s.waitingEntry(entryId);
        if (e == null || e.state() != QueueEntry.State.QUEUED) {
            throw new HttpError(404, "entry_not_found", "No such entry in the queue");
        }
        return e;
    }

    /**
     * Votes and pins are for the manually added section: an autoplay entry is turned into a
     * request first ({@link QueueOps#request}).
     *
     * @throws HttpError {@code 409 autoplay_entry}
     */
    static void requireManual(QueueEntry e, String message) {
        if (e.autoplay()) {
            throw new HttpError(409, "autoplay_entry", message);
        }
    }

    private static QueueEntry suggestion(RoomState s, String entryId) {
        QueueEntry e = entryId == null ? null : s.waitingEntry(entryId);
        if (e == null || e.state() != QueueEntry.State.SUGGESTED) {
            throw new HttpError(404, "suggestion_not_found", "No such suggestion");
        }
        return e;
    }

    static void setPlace(Connection c, String roomId, QueueEntry e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET position = ?, pinned = ?"
                + " WHERE id = ? AND room_id = ?")) {
            ps.setDouble(1, e.position());
            ps.setInt(2, e.pinned() ? 1 : 0);
            ps.setString(3, e.id());
            ps.setString(4, roomId);
            ps.executeUpdate();
        }
    }
}
