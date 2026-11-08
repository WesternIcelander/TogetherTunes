package io.siggi.togethertunes.auth;

import java.util.Objects;

/**
 * An actor as every response and message shows it ({@code docs/api.md},
 * {@code docs/rooms.md}): {@code {"kind": "user"|"guest", "id", "name", "isGuest"}}.
 *
 * <p>{@code isGuest} is the guest badge ({@code docs/auth.md#guests}): the UI marks every guest
 * with it, and ordinary users tell guests apart by nothing else but name and badge. So this type
 * holds no IP address and nothing derived from one, and no guest detail such as its
 * identification mode or ban flag. Serialise it with {@code Json.GSON} (the record component
 * names are the JSON keys).
 *
 * @param kind    {@value #USER} or {@value #GUEST}
 * @param id      the user's or guest's id
 * @param name    a user's display name, or a guest's name
 * @param isGuest {@code true} exactly when {@code kind} is {@value #GUEST}
 */
public record ActorRef(String kind, String id, String name, boolean isGuest) {
    public static final String USER = "user";
    public static final String GUEST = "guest";

    public ActorRef {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        if (!kind.equals(USER) && !kind.equals(GUEST)) {
            throw new IllegalArgumentException("kind must be user or guest: " + kind);
        }
        if (isGuest != kind.equals(GUEST)) {
            throw new IllegalArgumentException("isGuest must match kind");
        }
    }

    /** A user, shown by display name. */
    public static ActorRef user(String id, String displayName) {
        return new ActorRef(USER, id, displayName, false);
    }

    /** A guest, shown by its name and the guest badge. */
    public static ActorRef guest(String id, String name) {
        return new ActorRef(GUEST, id, name, true);
    }

    /** The user of an account. */
    public static ActorRef of(Accounts.Account account) {
        return user(account.id(), account.displayName());
    }

    /** The user as the admin pages list it. */
    public static ActorRef of(Users.UserView user) {
        return user(user.id(), user.displayName());
    }

    /** A guest. */
    public static ActorRef of(Guests.GuestView guest) {
        return guest(guest.id(), guest.name());
    }

    /**
     * The actor of a row with a user/guest actor pair, as most tables have it
     * ({@code added_by_user_id}/{@code added_by_guest_id}, ...): the user if {@code userId} is
     * set, else the guest if {@code guestId} is set, else {@code null}. The names are the joined
     * {@code users.display_name} and {@code guests.display_name}.
     */
    public static ActorRef fromColumns(String userId, String userName, String guestId, String guestName) {
        if (userId != null) {
            return user(userId, Objects.requireNonNull(userName, "user name"));
        }
        if (guestId != null) {
            return guest(guestId, Objects.requireNonNull(guestName, "guest name"));
        }
        return null;
    }
}
