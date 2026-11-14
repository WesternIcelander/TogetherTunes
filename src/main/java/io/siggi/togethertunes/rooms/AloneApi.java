package io.siggi.togethertunes.rooms;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.Objects;
import java.util.Set;

/**
 * The alone state and the user's own play history over HTTP ({@link AloneSessions},
 * {@link PlayHistory}; {@code docs/api.md#alone-state}). Users only: guests and anonymous callers
 * get {@code 401 not_authenticated}. Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/me/play-session} → the session {@code {"aloneRoomId", "roomId"?, "deletedRoom"?:
 *       {"name"}}}: the user's alone room (made on first use; play it through the room socket like
 *       any room), the room they are in (absent: alone), and a room deleted while they were in it
 *       (they still choose fork or restore).</li>
 *   <li>{@code POST /api/me/play-session/join} {@code {"room": idOrSlug}} → the session. Saves the alone
 *       state (pauses it) when coming from it. {@code 404 room_not_found} / {@code 403
 *       banned_from_room}, {@code 409 alone_room}.</li>
 *   <li>{@code POST /api/me/play-session/leave} {@code {"choice": "fork"|"restore"}} → the session (now
 *       alone). {@code 400 invalid_choice}, {@code 409 not_in_room}.</li>
 *   <li>{@code GET /api/me/history?cursor=&limit=} → {@code {"items": [{"id", "playedAt", "item",
 *       "room"?: {"id"?, "name"}}], "nextCursor"?}}, newest first; {@code room} absent: it played
 *       alone; {@code room.id} absent: that room was deleted since. {@code limit}
 *       1..{@value PlayHistory#LIST_MAX}, default {@value PlayHistory#LIST_DEFAULT}; {@code 400
 *       invalid_cursor} / {@code invalid_limit}.</li>
 * </ul>
 */
final class AloneApi {
    private static final Set<String> JOIN_FIELDS = Set.of("room");
    private static final Set<String> LEAVE_FIELDS = Set.of("choice");

    private final AloneSessions sessions;
    private final PlayHistory history;

    AloneApi(AloneSessions sessions, PlayHistory history) {
        this.sessions = Objects.requireNonNull(sessions);
        this.history = Objects.requireNonNull(history);
    }

    void register(Router router) {
        router.get("/api/me/play-session", this::get);
        router.post("/api/me/play-session/join", this::join);
        router.post("/api/me/play-session/leave", this::leave);
        router.get("/api/me/history", this::history);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void get(Request request, Response response) throws Exception {
        AloneSessions.Session s = sessions.get(user(request));
        response.header("Cache-Control", "no-store");
        response.json(s);
    }

    private void join(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        RoomsApi.fields(body, JOIN_FIELDS);
        String room = RoomsApi.string(body.get("room"), "invalid_room");
        if (room == null) {
            throw new HttpError(400, "invalid_room", "room (an id or link name) is required");
        }
        AloneSessions.Session s = sessions.join(user, room);
        response.header("Cache-Control", "no-store");
        response.json(s);
    }

    private void leave(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        RoomsApi.fields(body, LEAVE_FIELDS);
        AloneSessions.Choice choice = AloneSessions.Choice.parse(RoomsApi.string(body.get("choice"), "invalid_choice"));
        if (choice == null) {
            throw new HttpError(400, "invalid_choice", "choice must be fork or restore");
        }
        AloneSessions.Session s = sessions.leave(user, choice);
        response.header("Cache-Control", "no-store");
        response.json(s);
    }

    private void history(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String param = request.query("limit");
        int limit = PlayHistory.LIST_DEFAULT;
        if (param != null && !param.isEmpty()) {
            try {
                limit = Integer.parseInt(param);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > PlayHistory.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + PlayHistory.LIST_MAX);
            }
        }
        String cursor = request.query("cursor");
        PlayHistory.Page page = history.list(user.userId(), cursor == null || cursor.isEmpty() ? null : cursor, limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }
}
