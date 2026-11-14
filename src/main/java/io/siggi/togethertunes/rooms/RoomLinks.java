package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.io.IOException;

/**
 * The room view's URLs in the web UI.
 *
 * <ul>
 *   <li>{@code /rooms/{idOrSlug}}: the room view of the single-page app (served as the app's
 *       {@code index.html} by {@link io.siggi.togethertunes.http.StaticFiles}; the view reads the
 *       room from the path, and the mode from {@code ?mode=}).</li>
 *   <li>{@code GET /r/{slug}}: the short link, the QR code's target ({@code docs/overlay.md}) →
 *       {@code 302} to {@code /rooms/{slug}?mode=remote}, the room page in remote mode
 *       ({@code docs/rooms.md#web-and-app-playback}). The slug is lower-cased; anything that isn't
 *       a valid slug is {@code 404}. Whether the room exists (and whether the visitor may see it) is
 *       left to the room page, so the redirect never tells anyone which slugs are taken. {@code 302},
 *       not permanent, because a slug can be changed or reused.</li>
 * </ul>
 */
public final class RoomLinks {
    /** The short link's target mode ({@code ?mode=remote}). */
    public static final String REMOTE_MODE_QUERY = "mode=remote";

    private RoomLinks() {
    }

    /** Registers the short link's route. */
    public static void register(Router router) {
        router.get("/r/{slug}", RoomLinks::shortLink);
    }

    /** The room page's path for a slug (or id), e.g. {@code /rooms/lounge}. */
    public static String roomPath(String slug) {
        return "/rooms/" + slug;
    }

    /** The room page in remote mode, the short link's target. */
    public static String remoteModePath(String slug) {
        return roomPath(slug) + "?" + REMOTE_MODE_QUERY;
    }

    private static void shortLink(Request request, Response response) throws IOException {
        String slug;
        try {
            slug = Rooms.slug(request.pathParam("slug"));
        } catch (HttpError e) {
            throw new HttpError(404, "room_not_found", "No such room");
        }
        // A valid slug is only a-z, 0-9 and '-', so it needs no escaping in the Location.
        response.header("Location", remoteModePath(slug));
        response.header("Cache-Control", "no-store");
        response.empty(302);
    }
}
