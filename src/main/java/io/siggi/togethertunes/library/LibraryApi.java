package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.Objects;

/**
 * The caller's own library ({@code docs/api.md#items-libraries-playlists-sharing},
 * {@link Library}). Users only: guests have no library, so a guest or anonymous request gets
 * {@code 401 not_authenticated}. Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/library?q=&kind=&cursor=&limit=} → {@code {"items": [entry], "nextCursor"?}},
 *       most recently added first. {@code q}: every word must appear in the title, artist or album
 *       (case-insensitive, substring); {@code kind}: {@code audio} or {@code video}; {@code limit}
 *       1..{@value Library#LIST_MAX}, default {@value Library#LIST_DEFAULT}. {@code 400
 *       invalid_kind}, {@code invalid_query} (over {@value Library#QUERY_MAX_CHARS} chars),
 *       {@code invalid_cursor}, {@code invalid_limit}.</li>
 *   <li>{@code GET /api/library/shows?name=} → {@code {"shows": [{"name", "seasons": [{"season"?,
 *       "episodes": [entry]}]}]}}: the {@code show} items of the library grouped by show name
 *       (case-insensitive), season (none first, then 0, 1, ...) and episode ({@link Episodes});
 *       {@code name} limits it to one show. {@code 400 invalid_query} (name too long).</li>
 *   <li>{@code GET /api/library/uncategorized?q=&cursor=&limit=} → {@code {"items": [entry],
 *       "nextCursor"?, "total"}}: the Uncategorized Videos view, the caller's library entries of
 *       media type {@code uncategorized} (as {@code GET /api/library?type=uncategorized}), plus
 *       {@code total}, how many the library has in all (not narrowed by {@code q}; the sidebar
 *       shows the view while it is above 0, so {@code limit=1} is enough to ask).</li>
 *   <li>{@code PUT /api/library/{itemId}} (body optional: {@code {"mediaType", "show"?}}) →
 *       {@code 201 entry} when added ({@code via: "manual"}),
 *       {@code 200 entry} when it already was there (unchanged). An {@code uncategorized} video
 *       needs {@code mediaType} (any but {@code uncategorized}; {@code show} details for
 *       {@code show}), else {@code 409 needs_classification} with {@code itemId} and
 *       {@code suggested} ({@link Classify}); {@code 400 invalid_media_type}, {@code invalid_show},
 *       {@code show_required}, {@code not_a_show}, {@code unknown_field}. {@code 404 item_not_found};
 *       a purged/failed upload → {@code 409 media_expired} / {@code item_failed}; a purged or
 *       failed YouTube item is fetched again, which needs {@code library.youtube} (and
 *       {@code ytdlp.enabled}: {@code 403 feature_disabled}).</li>
 *   <li>{@code DELETE /api/library/{itemId}} → {@code 204}; {@code 404 not_in_library}.</li>
 * </ul>
 * An entry is {@code {"item": {"id", "kind", "title", "artist"?, "album"?, "durationMs"?,
 * "source", "youtubeId"?, "status", "presentAsAudio"}, "addedAt", "via"}}.
 */
public final class LibraryApi {
    private final Library library;
    private final AddMediaPolicy policy;

    public LibraryApi(Library library, AddMediaPolicy policy) {
        this.library = Objects.requireNonNull(library);
        this.policy = Objects.requireNonNull(policy);
    }

    public void register(Router router) {
        router.get("/api/library", this::list);
        router.get("/api/library/shows", this::shows);
        router.get("/api/library/uncategorized", this::uncategorized);
        router.put("/api/library/{itemId}", this::add);
        router.delete("/api/library/{itemId}", this::remove);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void list(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = limit(request);
        Library.Page page = library.list(user.userId(), request.query("q"), emptyToNull(request.query("kind")),
                emptyToNull(request.query("type")), emptyToNull(request.query("cursor")), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    /** The Uncategorized Videos view: the caller's {@code uncategorized} entries, and how many there are. */
    private void uncategorized(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = limit(request);
        Library.Page page = library.list(user.userId(), request.query("q"), null, MediaType.UNCATEGORIZED.wire(),
                emptyToNull(request.query("cursor")), limit);
        long total = library.countUncategorized(user.userId());
        response.header("Cache-Control", "no-store");
        response.json(new Uncategorized(page.items(), page.nextCursor(), total));
    }

    private record Uncategorized(java.util.List<Library.Entry> items, String nextCursor, long total) {
    }

    private static int limit(Request request) {
        int limit = Library.LIST_DEFAULT;
        String limitParam = request.query("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > Library.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + Library.LIST_MAX);
            }
        }
        return limit;
    }

    private void shows(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String name = emptyToNull(request.query("name"));
        if (name != null && name.length() > Library.QUERY_MAX_CHARS) {
            throw new HttpError(400, "invalid_query", "The show name is too long");
        }
        response.header("Cache-Control", "no-store");
        response.json(new Shows(library.shows(user.userId(), name)));
    }

    private record Shows(java.util.List<Episodes.Show<Library.Entry>> shows) {
    }

    private void add(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Classify.Classification classification = null;
        if (!noBody(request)) {
            com.google.gson.JsonObject body = request.readJson(com.google.gson.JsonObject.class);
            for (String key : body.keySet()) {
                if (!key.equals("mediaType") && !key.equals("show")) {
                    throw new HttpError(400, "unknown_field", "Unknown field: " + key);
                }
            }
            classification = Classify.of(body);
        }
        Library.Added added = library.add(user, request.pathParam("itemId"), classification,
                () -> policy.requireYoutube(user, null));
        response.header("Cache-Control", "no-store");
        response.json(added.created() ? 201 : 200, added.entry());
    }

    private void remove(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        if (!library.remove(user.userId(), request.pathParam("itemId"))) {
            throw new HttpError(404, "not_in_library", "That item isn't in your library");
        }
        response.empty(204);
    }

    private static boolean noBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
