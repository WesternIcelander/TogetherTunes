package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Feature;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.Hook;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.rooms.RoomGuestRules;
import io.siggi.togethertunes.settings.Settings;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Who may add media ({@code docs/library-and-ingest.md#who-can-add-media}):
 *
 * <table>
 *   <caption>Paths</caption>
 *   <tr><th>Path</th><th>Users</th><th>Guests</th></tr>
 *   <tr><td>Upload</td><td>{@code library.upload}</td><td>{@code guests.canUpload} and the room's
 *       setting, only as a request in a room, within {@code guests.uploadQuota}
 *       ({@link GuestUploadQuota})</td></tr>
 *   <tr><td>YouTube</td><td>{@code library.youtube} and {@code ytdlp.enabled}</td><td>
 *       {@code ytdlp.enabled}, {@code guests.canYoutube} and the room's setting, only as a request
 *       in a room</td></tr>
 * </table>
 *
 * <p>Order of the checks: for YouTube the feature switch first ({@code 403 feature_disabled},
 * for everyone). Users then need the permission ({@code 403 forbidden}). Guests and anonymous
 * visitors without a room get {@code 401 not_authenticated} (as for any permission); with a room:
 * {@code 403 guests_disabled} ({@code guests.enabled} off), {@code 403 guest_banned} (banned
 * server-wide), {@code 403 guest_uploads_disabled} / {@code guest_youtube_disabled} (the server
 * setting is off), then the room ({@link RoomGuestRules}, {@code rooms.RoomGuests}: {@code 404
 * room_not_found}, {@code 403 banned_from_room}, {@code 403 guest_uploads_disabled} /
 * {@code guest_youtube_disabled} when the room switches it off). Nothing here creates a guest: callers do that after the checks,
 * so a refused anonymous visitor doesn't become one.
 */
public final class AddMediaPolicy {
    private final Permissions permissions;
    private final Settings settings;
    private final RoomGuestRules rooms;

    public AddMediaPolicy(Permissions permissions, Settings settings, RoomGuestRules rooms) {
        this.permissions = Objects.requireNonNull(permissions);
        this.settings = Objects.requireNonNull(settings);
        this.rooms = Objects.requireNonNull(rooms);
    }

    /**
     * Throws unless {@code actor} may start an upload. A user needs {@code library.upload}; a
     * guest or anonymous visitor needs a {@code roomId} and the guest rules (see the class
     * comment). A user's {@code roomId} isn't checked here (a user's request is checked when it is
     * added to the queue).
     */
    public void requireUpload(Actor actor, String roomId) throws SQLException {
        if (actor instanceof Actor.User) {
            permissions.require(actor, Permission.LIBRARY_UPLOAD);
            return;
        }
        requireGuest(actor, roomId, RoomGuestRules.Capability.UPLOAD);
    }

    /** Throws {@code 403 feature_disabled} unless YouTube imports are on ({@code ytdlp.enabled}). */
    public void requireYoutubeFeature() throws SQLException {
        permissions.requireFeature(Feature.YOUTUBE);
    }

    /**
     * Throws unless {@code actor} may import a YouTube link: {@code ytdlp.enabled} first, then as
     * {@link #requireUpload} with {@code library.youtube} and {@code guests.canYoutube}.
     */
    public void requireYoutube(Actor actor, String roomId) throws SQLException {
        permissions.requireFeature(Feature.YOUTUBE);
        if (actor instanceof Actor.User) {
            permissions.require(actor, Permission.LIBRARY_YOUTUBE);
            return;
        }
        requireGuest(actor, roomId, RoomGuestRules.Capability.YOUTUBE);
    }

    /**
     * Throws unless {@code actor} may go on with an upload it started (send chunks, read the
     * status, complete it): a user still needs {@code library.upload}; a guest still needs the
     * server's guest rules (so switching guest uploads off stops uploads in progress); an
     * anonymous visitor owns no uploads ({@code 401}).
     */
    public void requireUploadOwner(Actor actor) throws SQLException {
        if (actor instanceof Actor.User) {
            permissions.require(actor, Permission.LIBRARY_UPLOAD);
            return;
        }
        if (!(actor instanceof Actor.Guest guest)) {
            throw notAuthenticated(RoomGuestRules.Capability.UPLOAD);
        }
        requireServerGuestRules(guest, RoomGuestRules.Capability.UPLOAD);
    }

    private void requireGuest(Actor actor, String roomId, RoomGuestRules.Capability capability) throws SQLException {
        if (roomId == null) {
            throw notAuthenticated(capability);
        }
        requireServerGuestRules(actor, capability);
        rooms.require(roomId, actor, capability);
    }

    private void requireServerGuestRules(Actor actor, RoomGuestRules.Capability capability) throws SQLException {
        if (!settings.guestsEnabled()) {
            throw new HttpError(403, "guests_disabled", "Guests are disabled on this server; log in first");
        }
        if (actor instanceof Actor.Guest guest && guest.banned()) {
            throw new HttpError(403, "guest_banned", "This guest is banned");
        }
        switch (capability) {
            case UPLOAD -> {
                if (!settings.guestsCanUpload()) {
                    throw new HttpError(403, "guest_uploads_disabled", "Guests can't upload files on this server");
                }
            }
            case YOUTUBE -> {
                if (!settings.guestsCanYoutube()) {
                    throw new HttpError(403, "guest_youtube_disabled", "Guests can't add YouTube links on this server");
                }
            }
        }
    }

    private static HttpError notAuthenticated(RoomGuestRules.Capability capability) {
        return new HttpError(401, "not_authenticated", capability == RoomGuestRules.Capability.UPLOAD
                ? "Log in first; guests can upload only as a request in a room"
                : "Log in first; guests can add YouTube links only as a request in a room");
    }

    /** A per-route hook running {@link #requireUploadOwner} for the request's actor. */
    public Hook uploadOwnerHook() {
        return (request, response, next) -> {
            requireUploadOwner(ActorResolver.actor(request));
            next.handle(request, response);
        };
    }

    /**
     * A per-route hook running {@link #requireYoutube} without a room (the REST import; guests'
     * links come through a room's {@code queue.add}).
     */
    public Hook youtubeHook() {
        return (request, response, next) -> {
            requireYoutube(ActorResolver.actor(request), null);
            next.handle(request, response);
        };
    }
}
