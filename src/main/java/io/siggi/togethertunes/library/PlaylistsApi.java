package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The caller's playlists ({@code docs/api.md#items-libraries-playlists-sharing},
 * {@link Playlists}). Users only: guests and anonymous callers get {@code 401 not_authenticated}.
 * Someone else's playlist is {@code 404 playlist_not_found}. Responses are
 * {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/playlists?cursor=&limit=} → {@code {"items": [playlist], "nextCursor"?}}:
 *       Liked Songs first (once it exists), then the caller's order; {@code limit}
 *       1..{@value Playlists#LIST_MAX}, default {@value Playlists#LIST_DEFAULT}. {@code 400
 *       invalid_cursor} / {@code invalid_limit}.</li>
 *   <li>{@code POST /api/playlists} {@code {"name", "description"?}} → {@code 201 playlist}, at
 *       the top. {@code 409 too_many_playlists}.</li>
 *   <li>{@code PUT /api/playlists/order} {@code {"playlistIds": [id…], "beforePlaylistId": id|null}}
 *       → {@code 204}: those playlists, in that order, before that one (or at the end).
 *       {@code 400 invalid_playlist_ids} (missing, empty, duplicates), {@code 404
 *       playlist_not_found}, {@code 409 before_playlist_not_found}, {@code 409 builtin_playlist}
 *       (Liked Songs stays first).</li>
 *   <li>{@code GET /api/playlists/{id}} → the playlist with {@code "entries": [entry]} in
 *       order.</li>
 *   <li>{@code PATCH /api/playlists/{id}} {@code {"name"?, "description"?}} → the playlist;
 *       {@code description} {@code null} or blank removes it. {@code 409 builtin_playlist}.</li>
 *   <li>{@code DELETE /api/playlists/{id}} → {@code 204}; {@code 409 builtin_playlist}.</li>
 *   <li>{@code POST /api/playlists/{id}/entries} {@code {"itemId", "beforeEntryId"?, "mediaType"?,
 *       "show"?}} → {@code 201 entry}, at the end or before that entry, and the item is added to
 *       the caller's library if it isn't there ({@code 409 needs_classification} for an
 *       uncategorized video without {@code mediaType}, as {@code PUT /api/library/{itemId}}).
 *       Duplicates are allowed, except in Liked Songs ({@code 200} with the existing entry).
 *       {@code 404 item_not_found}, {@code 409 media_expired} / {@code item_failed} (a purged or
 *       failed upload), a purged or failed YouTube item is fetched again (needs
 *       {@code library.youtube}: {@code 403 feature_disabled} with {@code ytdlp.enabled} off,
 *       {@code 422 youtube_failed}), {@code 409 before_entry_not_found},
 *       {@code 409 playlist_full}.</li>
 *   <li>{@code PATCH /api/playlists/{id}/entries/{entryId}} {@code {"beforeEntryId": id|null}} →
 *       the entry, moved before that entry, or to the end for {@code null}. {@code 404
 *       entry_not_found}, {@code 409 before_entry_not_found}.</li>
 *   <li>{@code POST /api/playlists/{id}/entries/move} {@code {"entryIds": [id…], "beforeEntryId":
 *       id|null}} → the playlist with its entries: those entries, in that order, before that entry
 *       (or at the end). {@code 400 invalid_entry_ids}, {@code 404 entry_not_found}, {@code 409
 *       before_entry_not_found}.</li>
 *   <li>{@code DELETE /api/playlists/{id}/entries/{entryId}} → {@code 204}; {@code 404
 *       entry_not_found}. The item stays in the library.</li>
 *   <li>{@code GET /api/library/{itemId}/playlists} → {@code {"playlists": [playlist]}}: the
 *       caller's playlists holding the item (removing it from the library removes it from
 *       these).</li>
 *   <li>{@code GET /api/me/likes} → Liked Songs with its entries (made on demand).
 *       {@code GET /api/me/likes/{itemId}} → {@code {"liked": bool}}. {@code PUT
 *       /api/me/likes/{itemId}} (body optional: {@code {"mediaType", "show"?}}) → {@code 201
 *       entry} (at the top) or {@code 200 entry} (already liked); the errors of adding an entry.
 *       {@code DELETE /api/me/likes/{itemId}} → {@code 204} (liked or not; the item stays in the
 *       library).</li>
 * </ul>
 * A playlist is {@code {"id", "name", "description"?, "entryCount", "createdAt", "updatedAt",
 * "builtin"?}} ({@code builtin}: {@code "likes"} for Liked Songs);
 * an entry is {@code {"id", "item": {…as in the library…}, "addedAt"}}. Body errors: {@code 400
 * invalid_name} / {@code name_too_long} (over {@value Playlists#NAME_MAX} characters),
 * {@code invalid_description} / {@code description_too_long} (over
 * {@value Playlists#DESCRIPTION_MAX}), {@code invalid_item_id}, {@code invalid_before_entry_id},
 * {@code unknown_field}.
 */
public final class PlaylistsApi {
    private static final Set<String> PLAYLIST_FIELDS = Set.of("name", "description");
    private static final Set<String> ADD_FIELDS = Set.of("itemId", "beforeEntryId", "mediaType", "show");
    private static final Set<String> MOVE_FIELDS = Set.of("beforeEntryId");
    private static final Set<String> MOVE_MANY_FIELDS = Set.of("entryIds", "beforeEntryId");
    private static final Set<String> ORDER_FIELDS = Set.of("playlistIds", "beforePlaylistId");
    private static final Set<String> LIKE_FIELDS = Set.of("mediaType", "show");

    private final Playlists playlists;
    private final AddMediaPolicy policy;

    public PlaylistsApi(Playlists playlists, AddMediaPolicy policy) {
        this.playlists = Objects.requireNonNull(playlists);
        this.policy = Objects.requireNonNull(policy);
    }

    public void register(Router router) {
        router.get("/api/playlists", this::list);
        router.post("/api/playlists", this::create);
        router.put("/api/playlists/order", this::order);
        router.get("/api/playlists/{id}", this::get);
        router.patch("/api/playlists/{id}", this::update);
        router.delete("/api/playlists/{id}", this::delete);
        router.post("/api/playlists/{id}/entries", this::addEntry);
        router.post("/api/playlists/{id}/entries/move", this::moveEntries);
        router.patch("/api/playlists/{id}/entries/{entryId}", this::moveEntry);
        router.delete("/api/playlists/{id}/entries/{entryId}", this::removeEntry);
        router.get("/api/library/{itemId}/playlists", this::containing);
        router.get("/api/me/likes", this::likes);
        router.get("/api/me/likes/{itemId}", this::liked);
        router.put("/api/me/likes/{itemId}", this::like);
        router.delete("/api/me/likes/{itemId}", this::unlike);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void list(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = Playlists.LIST_DEFAULT;
        String limitParam = request.query("limit");
        if (limitParam != null && !limitParam.isEmpty()) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > Playlists.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + Playlists.LIST_MAX);
            }
        }
        String cursor = request.query("cursor");
        Playlists.Page page = playlists.list(user.userId(), cursor == null || cursor.isEmpty() ? null : cursor, limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void create(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, PLAYLIST_FIELDS);
        String name = string(body.get("name"), "invalid_name");
        if (name == null) {
            throw new HttpError(400, "invalid_name", "A name is required");
        }
        Playlists.Playlist created = playlists.create(user.userId(), name,
                string(body.get("description"), "invalid_description"));
        response.header("Cache-Control", "no-store");
        response.json(201, created);
    }

    private void get(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Playlists.PlaylistPage page = playlists.get(user.userId(), request.pathParam("id"));
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void update(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, PLAYLIST_FIELDS);
        String name = null;
        if (body.has("name")) {
            name = string(body.get("name"), "invalid_name");
            if (name == null) {
                throw new HttpError(400, "invalid_name", "The name can't be empty");
            }
        }
        String description = body.has("description") ? string(body.get("description"), "invalid_description") : null;
        boolean clear = body.has("description") && description == null;
        Playlists.Playlist updated = playlists.update(user.userId(), request.pathParam("id"),
                new Playlists.Edit(name, description, clear));
        response.header("Cache-Control", "no-store");
        response.json(updated);
    }

    private void delete(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        if (!playlists.delete(user.userId(), request.pathParam("id"))) {
            throw new HttpError(404, "playlist_not_found", "No such playlist");
        }
        response.empty(204);
    }

    private void addEntry(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ADD_FIELDS);
        String itemId = string(body.get("itemId"), "invalid_item_id");
        if (itemId == null) {
            throw new HttpError(400, "invalid_item_id", "itemId is required");
        }
        String before = string(body.get("beforeEntryId"), "invalid_before_entry_id");
        Playlists.Added added = playlists.add(user, request.pathParam("id"), itemId, before, Classify.of(body),
                () -> policy.requireYoutube(user, null));
        response.header("Cache-Control", "no-store");
        response.json(added.created() ? 201 : 200, added.entry());
    }

    private void order(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ORDER_FIELDS);
        List<String> ids = ids(body.get("playlistIds"), "invalid_playlist_ids", Playlists.MAX_PLAYLISTS);
        if (!body.has("beforePlaylistId")) {
            throw new HttpError(400, "invalid_before_playlist_id",
                    "beforePlaylistId is required (a playlist id, or null for the end)");
        }
        playlists.movePlaylists(user.userId(), ids,
                string(body.get("beforePlaylistId"), "invalid_before_playlist_id"));
        response.empty(204);
    }

    private void moveEntries(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, MOVE_MANY_FIELDS);
        List<String> ids = ids(body.get("entryIds"), "invalid_entry_ids", Playlists.MAX_ENTRIES);
        if (!body.has("beforeEntryId")) {
            throw new HttpError(400, "invalid_before_entry_id",
                    "beforeEntryId is required (an entry id, or null for the end)");
        }
        Playlists.PlaylistPage page = playlists.moveEntries(user.userId(), request.pathParam("id"), ids,
                string(body.get("beforeEntryId"), "invalid_before_entry_id"));
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void containing(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        response.json(new Containing(playlists.containing(user.userId(), request.pathParam("itemId"))));
    }

    /** {@code GET /api/library/{itemId}/playlists}. */
    record Containing(List<Playlists.Playlist> playlists) {
    }

    private void likes(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        response.json(playlists.likes(user.userId()));
    }

    private void liked(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        response.header("Cache-Control", "no-store");
        response.json(new Liked(playlists.liked(user.userId(), request.pathParam("itemId"))));
    }

    /** {@code GET /api/me/likes/{itemId}}. */
    record Liked(boolean liked) {
    }

    private void like(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Classify.Classification classification = null;
        if (!noBody(request)) {
            JsonObject body = request.readJson(JsonObject.class);
            fields(body, LIKE_FIELDS);
            classification = Classify.of(body);
        }
        Playlists.Added added = playlists.like(user, request.pathParam("itemId"), classification,
                () -> policy.requireYoutube(user, null));
        response.header("Cache-Control", "no-store");
        response.json(added.created() ? 201 : 200, added.entry());
    }

    private void unlike(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        playlists.unlike(user.userId(), request.pathParam("itemId"));
        response.empty(204);
    }

    private static boolean noBody(Request request) {
        return request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
    }

    /** A non-empty JSON array of distinct non-blank strings, at most {@code max}; else {@code 400 code}. */
    private static List<String> ids(JsonElement e, String code, int max) {
        if (!(e instanceof JsonArray array) || array.isEmpty() || array.size() > max) {
            throw new HttpError(400, code, "Must be a list of 1.." + max + " ids");
        }
        List<String> out = new ArrayList<>(array.size());
        Set<String> seen = new HashSet<>();
        for (JsonElement x : array) {
            String id = string(x, code);
            if (id == null || !seen.add(id)) {
                throw new HttpError(400, code, "The ids must be distinct and not empty");
            }
            out.add(id);
        }
        return out;
    }

    private void moveEntry(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, MOVE_FIELDS);
        if (!body.has("beforeEntryId")) {
            throw new HttpError(400, "invalid_before_entry_id",
                    "beforeEntryId is required (an entry id, or null for the end)");
        }
        String before = string(body.get("beforeEntryId"), "invalid_before_entry_id");
        Playlists.Entry entry = playlists.move(user.userId(), request.pathParam("id"),
                request.pathParam("entryId"), before);
        if (entry == null) {
            throw new HttpError(404, "entry_not_found", "No such entry in this playlist");
        }
        response.header("Cache-Control", "no-store");
        response.json(entry);
    }

    private void removeEntry(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        if (!playlists.remove(user.userId(), request.pathParam("id"), request.pathParam("entryId"))) {
            throw new HttpError(404, "entry_not_found", "No such entry in this playlist");
        }
        response.empty(204);
    }

    private static void fields(JsonObject body, Set<String> allowed) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
    }

    /** The string, or null for a missing field, JSON null or a blank string; else {@code 400 code}. */
    private static String string(JsonElement e, String code) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString()) {
            throw new HttpError(400, code, "Must be a string");
        }
        String s = p.getAsString();
        return s.isBlank() ? null : s;
    }
}
