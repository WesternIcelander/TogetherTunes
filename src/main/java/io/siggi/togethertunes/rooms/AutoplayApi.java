package io.siggi.togethertunes.rooms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Autoplay selections over HTTP ({@link AutoplaySelections}, {@code docs/api.md#autoplay-selections}).
 * Responses are {@code Cache-Control: no-store}. Reading needs only access to the room
 * ({@code 404 room_not_found} / {@code 403 banned_from_room}); changing needs a logged-in user
 * ({@code 401 not_authenticated}).
 *
 * <ul>
 *   <li>{@code GET /api/rooms/{room}/autoplay} → the listing {@code {"roomId", "canSelect",
 *       "selections": [{"user", "playlists": [{"id", "name", "entryCount", "builtin"?,
 *       "selectedAt"}]}]}}: the caller first, then people in the order they first selected.</li>
 *   <li>{@code GET /api/rooms/{room}/autoplay/playlists/{playlistId}} → {@code {"owner",
 *       "playlist"}} (the playlist with its entries, as {@code GET /api/playlists/{id}}), only
 *       while it is selected in the room ({@code 404 playlist_not_found} otherwise).</li>
 *   <li>{@code PUT /api/rooms/{room}/autoplay/playlists/{playlistId}} → the listing: selects one of
 *       the caller's playlists (toggle on). {@code 403 forbidden} (the room's
 *       {@code autoplay.selectors} doesn't include the caller), {@code 404 playlist_not_found}
 *       (not theirs).</li>
 *   <li>{@code DELETE /api/rooms/{room}/autoplay/playlists/{playlistId}} → the listing: deselects it
 *       (toggle off; never needs the right).</li>
 *   <li>{@code PUT /api/rooms/{room}/autoplay/mine} {@code {"playlistIds": [id…]}} → the listing:
 *       exactly these are the caller's selection (one: "only this one"; empty: none). Other people's
 *       selections stay. {@code 400 invalid_playlist_ids}, and the errors of selecting.</li>
 * </ul>
 */
final class AutoplayApi {
    private static final Set<String> MINE_FIELDS = Set.of("playlistIds");

    private final AutoplaySelections selections;

    AutoplayApi(AutoplaySelections selections) {
        this.selections = Objects.requireNonNull(selections);
    }

    void register(Router router) {
        router.get("/api/rooms/{room}/autoplay", this::list);
        router.put("/api/rooms/{room}/autoplay/mine", this::setMine);
        router.get("/api/rooms/{room}/autoplay/playlists/{playlistId}", this::playlist);
        router.put("/api/rooms/{room}/autoplay/playlists/{playlistId}", this::select);
        router.delete("/api/rooms/{room}/autoplay/playlists/{playlistId}", this::deselect);
    }

    private void list(Request request, Response response) throws Exception {
        send(response, selections.list(ActorResolver.actor(request), request.pathParam("room")));
    }

    private void playlist(Request request, Response response) throws Exception {
        send(response, selections.playlist(ActorResolver.actor(request), request.pathParam("room"),
                request.pathParam("playlistId")));
    }

    private void select(Request request, Response response) throws Exception {
        send(response, selections.select(ActorResolver.actor(request), request.pathParam("room"),
                request.pathParam("playlistId")));
    }

    private void deselect(Request request, Response response) throws Exception {
        send(response, selections.deselect(ActorResolver.actor(request), request.pathParam("room"),
                request.pathParam("playlistId")));
    }

    private void setMine(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof io.siggi.togethertunes.auth.Actor.User)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        JsonObject body = request.readJson(JsonObject.class);
        RoomsApi.fields(body, MINE_FIELDS);
        JsonElement ids = body.get("playlistIds");
        if (ids == null || !ids.isJsonArray()) {
            throw new HttpError(400, "invalid_playlist_ids", "playlistIds (a list of playlist ids) is required");
        }
        List<String> list = new ArrayList<>();
        for (JsonElement e : (JsonArray) ids) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().isEmpty()) {
                throw new HttpError(400, "invalid_playlist_ids", "playlistIds must be playlist ids");
            }
            list.add(e.getAsString());
        }
        send(response, selections.setMine(ActorResolver.actor(request), request.pathParam("room"), list));
    }

    private static void send(Response response, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(body);
    }
}
