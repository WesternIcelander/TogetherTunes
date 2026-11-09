package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.http.HttpError;

/**
 * Who is acting on a room's queue, and what the room lets them do: the input of
 * {@link ControlPolicy} and {@link QueueControl}. Built per action by the caller (the WebSocket
 * layer) from the {@link io.siggi.togethertunes.auth.Actor}, the actor's room role and the guest
 * capabilities (server setting AND room setting, {@code docs/rooms.md#guests-in-rooms}).
 *
 * <p>Holds IDs only, never an IP address.
 *
 * @param userId     the user, or {@code null}
 * @param guestId    the guest, or {@code null}; at most one of the two is set, neither for an
 *                   anonymous visitor
 * @param role       the effective room role ({@code rooms.moderateAny} counts as moderator), or
 *                   {@code null} for none (guests never have one)
 * @param canRequest has request rights: may add to the queue (or suggest, in {@code owner} mode)
 * @param canVote    may vote on entries and vote to skip
 */
public record RoomActor(String userId, String guestId, Rooms.Role role, boolean canRequest, boolean canVote) {
    /** An anonymous visitor: may listen, and must become a guest before anything else. */
    public static final RoomActor ANONYMOUS = new RoomActor(null, null, null, false, false);

    public RoomActor {
        if (userId != null && guestId != null) {
            throw new IllegalArgumentException("a room actor is a user or a guest, not both");
        }
        if (guestId != null && role != null) {
            throw new IllegalArgumentException("guests have no room role");
        }
    }

    /**
     * A user with this effective room role ({@code null}: none). A user who can see the room has
     * request and vote rights unless banned.
     */
    public static RoomActor user(String userId, Rooms.Role role) {
        boolean rights = role != Rooms.Role.BANNED;
        return new RoomActor(java.util.Objects.requireNonNull(userId), null, role, rights, rights);
    }

    /** A guest with the effective (server AND room) capabilities; a banned guest has none. */
    public static RoomActor guest(String guestId, boolean canRequest, boolean canVote) {
        return new RoomActor(null, java.util.Objects.requireNonNull(guestId), null, canRequest, canVote);
    }

    /** Has an identity (a user or a guest). */
    public boolean identified() {
        return userId != null || guestId != null;
    }

    /** Owner or moderator (incl. {@code rooms.moderateAny}). */
    public boolean moderates() {
        return role != null && role.moderates();
    }

    /** {@code u:<userId>} or {@code g:<guestId>}: the key of this actor's votes in {@link RoomState}. */
    public String key() {
        requireIdentity();
        return userId != null ? "u:" + userId : "g:" + guestId;
    }

    /** Whether this actor added {@code entry}. */
    public boolean added(QueueEntry entry) {
        return userId != null ? userId.equals(entry.addedByUserId())
                : guestId != null && guestId.equals(entry.addedByGuestId());
    }

    /** @throws HttpError {@code 401 identity_required} for an anonymous visitor */
    public void requireIdentity() {
        if (!identified()) {
            throw new HttpError(401, "identity_required", "This needs an identity: become a guest or log in");
        }
    }
}
