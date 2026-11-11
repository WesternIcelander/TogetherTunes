package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.Objects;

/**
 * Other users' libraries and playlists, read-only, for {@code library.viewAll}
 * ({@code docs/api.md#items-libraries-playlists-sharing}, {@link UserLibraries}). Every route
 * needs the permission: guests and anonymous callers get {@code 401 not_authenticated}, users
 * without it {@code 403 forbidden}. Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/users?q=&cursor=&limit=} → {@code {"items": [{"id", "username",
 *       "displayName", "disabled", "libraryCount", "playlistCount"}], "nextCursor"?}}, by
 *       username; {@code q} matches part of the username or display name. {@code 400
 *       invalid_query} (over {@value UserLibraries#QUERY_MAX_CHARS} characters),
 *       {@code invalid_cursor}, {@code invalid_limit}.</li>
 *   <li>{@code GET /api/users/{id}/library?q=&kind=&cursor=&limit=} → as {@code GET
 *       /api/library}, for that user.</li>
 *   <li>{@code GET /api/users/{id}/playlists?cursor=&limit=} → as {@code GET /api/playlists}.</li>
 *   <li>{@code GET /api/users/{id}/playlists/{playlistId}} → as {@code GET
 *       /api/playlists/{id}}; {@code 404 playlist_not_found} also for another user's playlist.</li>
 * </ul>
 * An unknown user (or a guest's id) is {@code 404 user_not_found}.
 */
public final class UserLibrariesApi {
    private final UserLibraries libraries;
    private final Permissions permissions;

    public UserLibrariesApi(UserLibraries libraries, Permissions permissions) {
        this.libraries = Objects.requireNonNull(libraries);
        this.permissions = Objects.requireNonNull(permissions);
    }

    public void register(Router router) {
        var viewAll = permissions.hook(Permission.LIBRARY_VIEW_ALL);
        router.get("/api/users", this::users, viewAll);
        router.get("/api/users/{id}/library", this::library, viewAll);
        router.get("/api/users/{id}/playlists", this::playlists, viewAll);
        router.get("/api/users/{id}/playlists/{playlistId}", this::playlist, viewAll);
    }

    private void users(Request request, Response response) throws Exception {
        int limit = limit(request, UserLibraries.LIST_DEFAULT, UserLibraries.LIST_MAX);
        UserLibraries.UserPage page = libraries.users(emptyToNull(request.query("q")),
                emptyToNull(request.query("cursor")), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void library(Request request, Response response) throws Exception {
        int limit = limit(request, Library.LIST_DEFAULT, Library.LIST_MAX);
        Library.Page page = libraries.library(request.pathParam("id"), request.query("q"),
                emptyToNull(request.query("kind")), emptyToNull(request.query("type")),
                emptyToNull(request.query("cursor")), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void playlists(Request request, Response response) throws Exception {
        int limit = limit(request, Playlists.LIST_DEFAULT, Playlists.LIST_MAX);
        Playlists.Page page = libraries.playlists(request.pathParam("id"), emptyToNull(request.query("cursor")),
                limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void playlist(Request request, Response response) throws Exception {
        Playlists.PlaylistPage page = libraries.playlist(request.pathParam("id"), request.pathParam("playlistId"));
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private static int limit(Request request, int defaultLimit, int max) {
        String param = request.query("limit");
        if (param == null || param.isEmpty()) {
            return defaultLimit;
        }
        int limit;
        try {
            limit = Integer.parseInt(param);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > max) {
            throw new HttpError(400, "invalid_limit", "limit must be 1.." + max);
        }
        return limit;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
