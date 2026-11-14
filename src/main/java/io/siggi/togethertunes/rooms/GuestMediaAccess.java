package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;

/**
 * Whether an actor may fetch an item's media ({@code docs/libraries.md#media-access-rules}):
 * logged-in users always; a guest or anonymous visitor only while the item is the current or the
 * next entry of a room they are in. Asked on every media request, so implementations answer
 * from memory and must be thread-safe. The server uses {@link RoomViewers}
 * ({@link RoomRegistry#mediaAccess()}).
 */
@FunctionalInterface
public interface GuestMediaAccess {
    /**
     * Whether {@code actor} may fetch the media of {@code itemId} now.
     *
     * @param clientIp the request's client address (an anonymous visitor is recognised by its
     *                 {@link io.siggi.togethertunes.http.IpKeys IP key}); may be {@code null}
     */
    boolean allowed(Actor actor, String clientIp, String itemId);

    /** Users only: no rooms (tests that don't involve rooms). */
    GuestMediaAccess USERS_ONLY = (actor, clientIp, itemId) -> actor instanceof Actor.User;
}
