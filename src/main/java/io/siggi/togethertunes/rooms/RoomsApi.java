package io.siggi.togethertunes.rooms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Room CRUD and room roles over HTTP ({@code docs/api.md#rooms}, {@link Rooms}). Every
 * {@code {room}} path segment takes the room's id or its slug. Responses are
 * {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/rooms?cursor=&limit=} (anyone) → {@code {"items": [room], "nextCursor"?}}:
 *       public rooms plus the caller's own (owned, moderated, member of), newest first;
 *       {@code limit} 1..{@value Rooms#LIST_MAX}, default {@value Rooms#LIST_DEFAULT}.
 *       {@code 400 invalid_cursor} / {@code invalid_limit}.</li>
 *   <li>{@code POST /api/rooms} {@code {"name", "slug"?, "visibility"?, "controlMode"?}} →
 *       {@code 201 room}. Needs {@code rooms.create} ({@code 401} guests/anonymous,
 *       {@code 403 forbidden}). Defaults: a slug derived from the name, {@code unlisted},
 *       {@code vote}. {@code 409 slug_taken} / {@code too_many_rooms}.</li>
 *   <li>{@code GET /api/rooms/{room}} → the room (anyone who may see it).</li>
 *   <li>{@code PATCH /api/rooms/{room}} {@code {"name"?, "slug"?, "visibility"?, "controlMode"?,
 *       "settings"?}} → the room; the owner only. {@code settings} is a merge patch of the room
 *       settings ({@link RoomSettings}: nested objects change only what they name, {@code null}
 *       resets to the default); {@code 400 invalid_settings} (not an object), {@code
 *       unknown_setting}, {@code invalid_setting}. {@code 409 slug_taken}.</li>
 *   <li>{@code DELETE /api/rooms/{room}} → {@code 204}; the owner, or {@code rooms.moderateAny}.</li>
 *   <li>{@code GET /api/rooms/{room}/members?cursor=&limit=} → {@code {"items": [member],
 *       "nextCursor"?}}; owner and moderators.</li>
 *   <li>{@code PUT /api/rooms/{room}/members/{userId}} {@code {"role": "owner"|"moderator"|
 *       "member"|"banned"}} → the member ({@code 201} if the user had no row, else {@code 200}).
 *       {@code "owner"} transfers the room (owner only; the old owner becomes a moderator).
 *       {@code 404 user_not_found}, {@code 409 cannot_change_own_role} / {@code user_disabled}.</li>
 *   <li>{@code DELETE /api/rooms/{room}/members/{userId}} → {@code 204}; also lifts a ban, and is
 *       how a user leaves a room. {@code 404 member_not_found}, {@code 409 owner_cannot_leave}.</li>
 * </ul>
 * A room is {@code {"id", "slug", "name", "owner": actor, "visibility", "controlMode", "settings",
 * "createdAt", "you": {"role"?, "canEdit", "canDelete", "canModerate"}}}; a member is
 * {@code {"user": actor, "role", "joinedAt"}}. A room the caller can't see is {@code 404
 * room_not_found}; a banned user gets {@code 403 banned_from_room}. Body errors: {@code 400
 * invalid_name} / {@code name_too_long}, {@code invalid_slug}, {@code invalid_visibility},
 * {@code invalid_control_mode}, {@code invalid_role}, {@code unknown_field}. Room roles need a
 * logged-in user: guests and anonymous callers get {@code 401 not_authenticated} on every
 * route that changes something or lists members.
 */
public final class RoomsApi {
    private static final Set<String> ROOM_FIELDS = Set.of("name", "slug", "visibility", "controlMode");
    private static final Set<String> UPDATE_FIELDS = Set.of("name", "slug", "visibility", "controlMode", "settings");
    private static final Set<String> ROLE_FIELDS = Set.of("role");

    private final Rooms rooms;
    private final RoomRegistry registry;
    private final Permissions permissions;

    public RoomsApi(Rooms rooms, RoomRegistry registry, Permissions permissions) {
        this.rooms = Objects.requireNonNull(rooms);
        this.registry = Objects.requireNonNull(registry);
        this.permissions = Objects.requireNonNull(permissions);
    }

    /** The id of the room in the path ({@code {room}}: id or slug), else {@code 404 room_not_found}. */
    private String roomId(Request request) throws Exception {
        String id = rooms.idOf(request.pathParam("room"));
        if (id == null) {
            throw new HttpError(404, "room_not_found", "No such room");
        }
        return id;
    }

    /**
     * Takes the room record over into the live state after {@link Rooms} changed it, as a
     * {@link RoomEvent.RoomUpdated} (only if something the state holds changed).
     */
    private void syncRecord(RoomTx tx) throws Exception {
        Rooms.Room r = rooms.record(tx.roomId());
        if (r == null) {
            return;
        }
        RoomState s = tx.state();
        if (!r.slug().equals(s.slug()) || !r.name().equals(s.name()) || !r.ownerId().equals(s.ownerId())
                || r.visibility() != s.visibility() || r.controlMode() != s.controlMode()) {
            QueueControl.controlModeChanging(tx, r.controlMode());
            s.record(r.slug(), r.name(), r.ownerId(), r.visibility(), r.controlMode());
            tx.event(new RoomEvent.RoomUpdated());
        }
        if (!r.settings().equals(s.settings())) {
            List<String> changed = r.settings().changedFrom(s.settings());
            s.settings(r.settings());
            tx.event(new RoomEvent.SettingsChanged(changed));
            // A lower threshold (or vote-skip turned back on) may already be reached.
            QueueControl.checkVoteSkip(tx);
        }
    }

    public void register(Router router) {
        router.get("/api/rooms", this::list);
        router.post("/api/rooms", this::create);
        router.get("/api/rooms/{room}", this::get);
        router.patch("/api/rooms/{room}", this::update);
        router.delete("/api/rooms/{room}", this::delete);
        router.get("/api/rooms/{room}/members", this::members);
        router.put("/api/rooms/{room}/members/{userId}", this::setRole);
        router.delete("/api/rooms/{room}/members/{userId}", this::removeMember);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private static int limit(Request request) {
        String param = request.query("limit");
        if (param == null || param.isEmpty()) {
            return Rooms.LIST_DEFAULT;
        }
        int limit;
        try {
            limit = Integer.parseInt(param);
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > Rooms.LIST_MAX) {
            throw new HttpError(400, "invalid_limit", "limit must be 1.." + Rooms.LIST_MAX);
        }
        return limit;
    }

    private static String cursor(Request request) {
        String cursor = request.query("cursor");
        return cursor == null || cursor.isEmpty() ? null : cursor;
    }

    private void list(Request request, Response response) throws Exception {
        int limit = limit(request);
        Rooms.Page page = rooms.list(ActorResolver.actor(request), cursor(request), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void create(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        permissions.require(user, Permission.ROOMS_CREATE);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ROOM_FIELDS);
        String name = string(body.get("name"), "invalid_name");
        if (name == null) {
            throw new HttpError(400, "invalid_name", "A name is required");
        }
        Rooms.RoomView room = rooms.create(user, name, string(body.get("slug"), "invalid_slug"),
                visibility(body.get("visibility")), controlMode(body.get("controlMode")), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(201, room);
    }

    private void get(Request request, Response response) throws Exception {
        Rooms.RoomView room = rooms.get(ActorResolver.actor(request), request.pathParam("room"));
        response.header("Cache-Control", "no-store");
        response.json(room);
    }

    private void update(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, UPDATE_FIELDS);
        String name = null;
        if (body.has("name")) {
            name = string(body.get("name"), "invalid_name");
            if (name == null) {
                throw new HttpError(400, "invalid_name", "The name can't be empty");
            }
        }
        String slug = null;
        if (body.has("slug")) {
            slug = string(body.get("slug"), "invalid_slug");
            if (slug == null) {
                throw new HttpError(400, "invalid_slug", "The slug can't be empty");
            }
        }
        JsonObject settings = null;
        JsonElement settingsField = body.get("settings");
        if (settingsField != null && !settingsField.isJsonNull()) {
            if (!settingsField.isJsonObject()) {
                throw new HttpError(400, "invalid_settings", "settings must be an object");
            }
            settings = settingsField.getAsJsonObject();
        }
        Rooms.Edit edit = new Rooms.Edit(name, slug, visibility(body.get("visibility")),
                controlMode(body.get("controlMode")), settings);
        String id = roomId(request);
        // Through the room's executor, so the live state changes in step with the row.
        Rooms.RoomView room = registry.call(id, tx -> {
            Rooms.RoomView v = rooms.update(user, id, edit, request.clientIp());
            syncRecord(tx);
            return v;
        });
        response.header("Cache-Control", "no-store");
        response.json(room);
    }

    private void delete(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String id = roomId(request);
        registry.call(id, tx -> {
            rooms.delete(user, id, request.clientIp());
            tx.closeRoom();
            return null;
        });
        response.empty(204);
    }

    private void members(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = limit(request);
        Rooms.MemberPage page = rooms.members(user, request.pathParam("room"), cursor(request), limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void setRole(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ROLE_FIELDS);
        Rooms.Role role = Rooms.Role.parse(string(body.get("role"), "invalid_role"));
        if (role == null) {
            throw new HttpError(400, "invalid_role", "role must be owner, moderator, member or banned");
        }
        String id = roomId(request);
        String target = request.pathParam("userId");
        // Through the room's executor: an ownership transfer changes the live state's owner.
        Rooms.RoleSet set = registry.call(id, tx -> {
            Rooms.RoleSet r = rooms.setRole(user, id, target, role, request.clientIp());
            syncRecord(tx);
            return r;
        });
        response.header("Cache-Control", "no-store");
        response.json(set.created() ? 201 : 200, set.member());
    }

    private void removeMember(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        if (!rooms.removeMember(user, request.pathParam("room"), request.pathParam("userId"), request.clientIp())) {
            throw new HttpError(404, "member_not_found", "That user has no role in this room");
        }
        response.empty(204);
    }

    private static Rooms.Visibility visibility(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        Rooms.Visibility v = Rooms.Visibility.parse(e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null);
        if (v == null) {
            throw new HttpError(400, "invalid_visibility", "visibility must be public, unlisted or private");
        }
        return v;
    }

    private static Rooms.ControlMode controlMode(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        Rooms.ControlMode m = Rooms.ControlMode.parse(e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null);
        if (m == null) {
            throw new HttpError(400, "invalid_control_mode", "controlMode must be owner, vote or open");
        }
        return m;
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
