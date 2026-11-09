package io.siggi.togethertunes.rooms;

import java.sql.SQLException;
import java.util.List;

/**
 * Presence changes, run on the room's executor ({@code docs/rooms.md#guests-in-rooms}): the
 * WebSocket layer calls these when a socket joins the room, leaves it, switches mode or gets an
 * identity. When the counts change ({@link RoomPresence#counts}), a
 * {@link RoomEvent.PresenceChanged} is broadcast (with a new version, like every change); when
 * fewer identified members are present, the vote-skip threshold is checked again
 * ({@link QueueControl#checkVoteSkip}), since fewer members need fewer skip votes.
 *
 * <p>Nothing here checks who may join: the caller got the {@link RoomActor} from
 * {@link RoomGuests#roomActor}, which refuses banned users and guests.
 */
public final class PresenceOps {
    private PresenceOps() {
    }

    /** The actor key a connection of {@code a} is counted under ({@code null}: anonymous). */
    private static String key(RoomActor a) {
        return a.identified() ? a.key() : null;
    }

    /**
     * A connection joins the room (or joins again: its old entry is replaced).
     *
     * @return the counts afterwards
     */
    public static RoomPresence.Counts join(RoomTx tx, String connectionId, RoomActor a, RoomPresence.Mode mode)
            throws SQLException {
        RoomPresence p = tx.state().presence();
        RoomPresence.Counts before = p.counts();
        p.join(connectionId, key(a), mode);
        return changed(tx, before);
    }

    /** A connection leaves (the socket closed, or sent {@code leave}); whether it was present. */
    public static boolean leave(RoomTx tx, String connectionId) throws SQLException {
        RoomPresence p = tx.state().presence();
        RoomPresence.Counts before = p.counts();
        if (!p.leave(connectionId)) {
            return false;
        }
        changed(tx, before);
        return true;
    }

    /** A connection switches between listen and remote mode; whether it was present. */
    public static boolean mode(RoomTx tx, String connectionId, RoomPresence.Mode mode) throws SQLException {
        RoomPresence p = tx.state().presence();
        RoomPresence.Counts before = p.counts();
        if (!p.mode(connectionId, mode)) {
            return false;
        }
        changed(tx, before);
        return true;
    }

    /**
     * An anonymous connection got an identity ({@code identity} ticket redeemed): from now on it
     * counts as that user or guest. Whether it was present.
     */
    public static boolean identify(RoomTx tx, String connectionId, RoomActor a) throws SQLException {
        a.requireIdentity();
        RoomPresence p = tx.state().presence();
        RoomPresence.Counts before = p.counts();
        if (!p.identify(connectionId, a.key())) {
            return false;
        }
        changed(tx, before);
        return true;
    }

    /** Removes every connection of an actor (banned from the room); the removed connection ids. */
    static List<String> removeActor(RoomTx tx, String actorKey) throws SQLException {
        RoomPresence p = tx.state().presence();
        RoomPresence.Counts before = p.counts();
        List<String> removed = p.removeActor(actorKey);
        if (!removed.isEmpty()) {
            changed(tx, before);
        }
        return removed;
    }

    private static RoomPresence.Counts changed(RoomTx tx, RoomPresence.Counts before) throws SQLException {
        RoomPresence.Counts after = tx.state().presence().counts();
        if (!after.equals(before)) {
            tx.event(new RoomEvent.PresenceChanged(after));
        }
        if (after.identified() < before.identified()) {
            QueueControl.checkVoteSkip(tx);
        }
        return after;
    }
}
