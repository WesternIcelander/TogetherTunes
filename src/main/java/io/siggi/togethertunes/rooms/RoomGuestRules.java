package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.HttpError;
import java.sql.SQLException;

/**
 * The room half of a guest capability ({@code docs/rooms.md#guests-in-rooms}): guests add media
 * only as a request inside a room, and only when the room allows it as well as the server.
 * {@code library.AddMediaPolicy} checks the server half (the {@code guests.*} settings) and then
 * asks this. Implemented by {@link RoomGuests}; {@link #NO_ROOMS} (every room
 * {@code 404 room_not_found}) is for tests without rooms.
 */
@FunctionalInterface
public interface RoomGuestRules {
    /** A guest capability that a room switches as well as the server. */
    enum Capability {
        /** Upload files as requests (room setting {@code guests.canUpload}). */
        UPLOAD,
        /** YouTube links as requests (room setting {@code guests.canYoutube}). */
        YOUTUBE
    }

    /**
     * Throws unless {@code guest} (a guest, or an anonymous visitor about to become one) may use
     * {@code capability} in the room {@code roomId}: {@code 404 room_not_found} for an unknown
     * room or one the guest can't see (private rooms have no guests), {@code 403} if the room
     * switches the capability off or has banned the guest.
     */
    void require(String roomId, Actor guest, Capability capability) throws SQLException;

    /** No rooms (tests): every room is {@code 404 room_not_found}. */
    RoomGuestRules NO_ROOMS = (roomId, guest, capability) -> {
        throw new HttpError(404, "room_not_found", "No such room");
    };
}
