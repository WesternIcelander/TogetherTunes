package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.HttpError;
import java.sql.SQLException;

/**
 * The room half of a guest capability ({@code docs/rooms.md#guests-in-rooms}): guests add media
 * only as a request inside a room, and only when the room allows it as well as the server. Also
 * the room half of a signed-in user's upload as a request in a room ({@link #requireUserUpload}).
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

    /**
     * The room half of a signed-in user's upload as a request in the room {@code roomId} (id or
     * slug), seen from {@code clientIp}: the same checks as the room's request by upload
     * ({@code queue.add {uploadId}}), made before any bytes are sent. {@code 404 room_not_found}
     * for an unknown room or one the user can't see; {@code 403 banned_from_room} /
     * {@code timed_out}, {@code 403 forbidden} (no {@code library.upload}), {@code 403
     * cannot_request}, {@code 409 request_limit}, {@code 403 upload_requests_off} as there.
     * The server half ({@code library.upload}) was checked by the caller. The default (rooms not
     * wired, tests) is {@code 404 room_not_found}.
     *
     * @return the room's id (to record on the upload)
     */
    default String requireUserUpload(String roomId, Actor user, String clientIp) throws Exception {
        throw new HttpError(404, "room_not_found", "No such room");
    }

    /** No rooms (tests): every room is {@code 404 room_not_found}. */
    RoomGuestRules NO_ROOMS = (roomId, guest, capability) -> {
        throw new HttpError(404, "room_not_found", "No such room");
    };
}
