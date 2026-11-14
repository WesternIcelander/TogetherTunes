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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Room CRUD and room roles over HTTP ({@code docs/api.md#rooms}, {@link Rooms}). Every
 * {@code {room}} path segment takes the room's id or its slug. Responses are
 * {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code GET /api/rooms?q=&cursor=&limit=} (anyone) → {@code {"items": [room], "nextCursor"?}}:
 *       public rooms plus the caller's own (owned, moderated, member of), newest first, each with
 *       {@code listeners} ({@link RoomRegistry#listeners}); {@code q} filters by name and slug, and an
 *       exact slug, id or room URL of a room the caller may open (e.g. unlisted) puts that room first
 *       ({@link Rooms#list(io.siggi.togethertunes.auth.Actor, String, String, int)});
 *       {@code limit} 1..{@value Rooms#LIST_MAX}, default {@value Rooms#LIST_DEFAULT}.
 *       {@code 400 invalid_cursor} / {@code invalid_limit} / {@code invalid_query}.</li>
 *   <li>{@code POST /api/rooms} {@code {"name", "slug"?, "visibility"?, "controlMode"?}} →
 *       {@code 201 room}. Needs {@code rooms.create} ({@code 401} guests/anonymous,
 *       {@code 403 forbidden}). Defaults: a slug derived from the name, {@code unlisted},
 *       {@code vote}. {@code 409 slug_taken} / {@code too_many_rooms}. {@code "moveAloneSession": true}
 *       moves the caller's alone state (current song and queue) into the new room and puts them in
 *       it ({@link AloneSessions#moveIntoRoom}).</li>
 *   <li>{@code GET /api/rooms/{room}} → the room (anyone who may see it). An alone room
 *       ({@link AloneSessions}) is only its user's, has {@code "alone": true}, and can't be changed,
 *       deleted or given members ({@code 409 alone_room}).</li>
 *   <li>{@code PATCH /api/rooms/{room}} {@code {"name"?, "slug"?, "visibility"?, "controlMode"?,
 *       "settings"?}} → the room; the owner only. {@code settings} is a merge patch of the room
 *       settings ({@link RoomSettings}: nested objects change only what they name, {@code null}
 *       resets to the default); {@code 400 invalid_settings} (not an object), {@code
 *       unknown_setting}, {@code invalid_setting}. {@code 409 slug_taken}. Uploads need library
 *       requests ({@link RoomSettings#patchAdjusting}): a patch that would break that makes the
 *       companion change and the response lists it, e.g. {@code "adjusted": ["libraryRequests"]}.</li>
 *   <li>{@code DELETE /api/rooms/{room}} → {@code 204}; the owner, or {@code rooms.moderateAny}.</li>
 *   <li>{@code GET /api/rooms/{room}/members?cursor=&limit=} → {@code {"items": [member],
 *       "nextCursor"?}}; owner and moderators.</li>
 *   <li>{@code PUT /api/rooms/{room}/members/{userId}} {@code {"role": "owner"|"moderator"|
 *       "member"|"banned"}} → the member ({@code 201} if the user had no row, else {@code 200}).
 *       {@code "owner"} transfers the room (owner only; the old owner becomes a moderator).
 *       {@code 404 user_not_found}, {@code 409 cannot_change_own_role} / {@code user_disabled}.</li>
 *   <li>{@code POST /api/rooms/{room}/members} {@code {"username", "role"}} → as {@code PUT
 *       …/members/{userId}} for the user with that username (case-insensitive; {@code 404
 *       user_not_found}, {@code 400 invalid_username}), so the management view can add members by
 *       name.</li>
 *   <li>{@code DELETE /api/rooms/{room}/members/{userId}} → {@code 204}; also lifts a ban, and is
 *       how a user leaves a room. {@code 404 member_not_found}, {@code 409 owner_cannot_leave}.</li>
 *   <li>{@code GET /api/rooms/{room}/guest-bans?cursor=&limit=} → {@code {"items": [ban],
 *       "nextCursor"?}}, newest first; {@code GET /api/rooms/{room}/guest-bans/{guestId}} → the
 *       guest's ban (also when not banned, so the UI can warn first); {@code PUT …/{guestId}} (no
 *       body) → the ban ({@code 201} if new, else {@code 200}); {@code DELETE …/{guestId}} →
 *       {@code 204}, {@code 404 ban_not_found}. Owner and moderators ({@link RoomGuests});
 *       {@code 404 guest_not_found}. A ban is {@code {"guest": actor, "identifiedBy": "cookie"|"ip",
 *       "banned", "bannedAt"?, "warning"?, "ip"?}}: {@code warning} for an IP-mode guest (the ban
 *       covers everyone sharing its address), {@code ip} (the guest's last IP) only for
 *       {@code ip.view} holders.</li>
 *   <li>{@code GET /api/rooms/{room}/search?q=&kind=&cursor=&limit=&all=} → {@code {"items": [item],
 *       "nextCursor"?}}: the request search ({@link RequestSearch}): the owner's library and
 *       playlists and the requester's library, or with {@code all=1} every item on the server that
 *       isn't purged; an item is only its metadata, never whose libraries hold it. Newest item
 *       first; {@code limit} 1..{@value RequestSearch#LIST_MAX}, default
 *       {@value RequestSearch#LIST_DEFAULT}. Anyone who may open the room and request from the
 *       library; {@code 403 guest_library_requests_disabled} / {@code library_requests_off};
 *       {@code 400 invalid_query} / {@code invalid_kind} / {@code invalid_cursor} /
 *       {@code invalid_limit}.</li>
 *   <li>{@code GET /api/rooms/{room}/history?cursor=&limit=} → {@code {"items": [row], "nextCursor"?,
 *       "noRepeatWindow"}}: the room's history ({@link RoomHistory}): every entry that started
 *       playing and ended, newest first. Anyone who may open the room. A row is {@code {"entryId",
 *       "state": "played"|"skipped", "playedAt", "item", "addedBy"?: actor, "via"?,
 *       "inYourLibrary"? (users only), "recentlyPlayed"}} ({@code addedBy}'s name is the one it had
 *       then). {@code limit} 1..{@value RoomHistory#LIST_MAX}, default {@value RoomHistory#LIST_DEFAULT};
 *       {@code 400 invalid_cursor} / {@code invalid_limit}.</li>
 *   <li>{@code GET /api/rooms/{room}/server-switches} → {@code {"guests": {"enabled", "canVote",
 *       "canRequestLibrary", "canUpload", "canYoutube"}, "overlay": {"enabled", "videoOverlay"}, "outputs":
 *       {"forceAutoOff", "maxAutoOffMinutes", "rtmp": {"enabled"}, "ts": {"enabled"}, "hls":
 *       {"enabled"}, "mp3": {"enabled"}}}}: the server switches behind the room settings, so the
 *       management view can grey out what the server turned off or forces, and show the server's
 *       cap on {@code outputs.autoOffMinutes} (the guest
 *       capabilities are each the server's setting alone; {@code guests.enabled} off turns them
 *       all off; {@code canYoutube} also needs {@code ytdlp.enabled}; {@code overlay.videoOverlay}: the
 *       overlay is on and the room's owner holds {@code output.overlayVideo}, so music videos get
 *       it too). Owner and moderators.</li>
 *   <li>{@code GET} / {@code POST /api/me/merge-guest}: guest → account merge, see {@link GuestMerge}
 *       (registered here because it moves room data).</li>
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
    private static final Set<String> ROOM_FIELDS = Set.of("name", "slug", "visibility", "controlMode",
            "moveAloneSession");
    private static final Set<String> UPDATE_FIELDS = Set.of("name", "slug", "visibility", "controlMode", "settings");
    private static final Set<String> ROLE_FIELDS = Set.of("role");
    private static final Set<String> ADD_MEMBER_FIELDS = Set.of("username", "role");

    private final Rooms rooms;
    private final RoomRegistry registry;
    private final Permissions permissions;
    private final RoomGuests guests;
    private final RequestSearch search;
    private final RoomHistory history;
    private volatile EditListener editListener = (before, after) -> {
    };

    /**
     * Told after a room was changed through {@code PATCH /api/rooms/{room}} (before the response is
     * sent), e.g. so the stream outputs follow a new slug or visibility. Must not block long.
     */
    @FunctionalInterface
    public interface EditListener {
        void edited(Rooms.Room before, Rooms.Room after) throws Exception;
    }

    /** Sets the {@link EditListener}. */
    public void editListener(EditListener listener) {
        this.editListener = Objects.requireNonNull(listener);
    }

    private volatile java.util.function.Consumer<String> deleteListener = roomId -> {
    };

    /**
     * Told (with the room's id) after a room was deleted through {@code DELETE /api/rooms/{room}},
     * after its program outputs were closed, e.g. to delete its overlay files. Runs on the request's
     * thread before the response; hand slow work off.
     */
    public void deleteListener(java.util.function.Consumer<String> listener) {
        this.deleteListener = Objects.requireNonNull(listener);
    }

    private volatile AloneSessions alone;
    private volatile PlayHistory playHistory;

    /**
     * Turns on the alone state ({@link AloneSessions}, {@link AloneApi}): {@code /api/me/play-session*}
     * and {@code /api/me/history}, {@code moveAloneSession} on create, and the sessions of the
     * users in a deleted room. Set before {@link #register}.
     */
    public void aloneSessions(AloneSessions alone, PlayHistory playHistory) {
        this.alone = Objects.requireNonNull(alone);
        this.playHistory = Objects.requireNonNull(playHistory);
    }

    private volatile AutoplaySelections autoplay;

    /**
     * Turns on the autoplay selections ({@link AutoplaySelections}, {@link AutoplayApi}):
     * {@code /api/rooms/{room}/autoplay*}. Set before {@link #register}.
     */
    public void autoplay(AutoplaySelections autoplay) {
        this.autoplay = Objects.requireNonNull(autoplay);
    }

    public RoomsApi(Rooms rooms, RoomRegistry registry, Permissions permissions, RoomGuests guests,
                    RequestSearch search, RoomHistory history) {
        this.rooms = Objects.requireNonNull(rooms);
        this.registry = Objects.requireNonNull(registry);
        this.permissions = Objects.requireNonNull(permissions);
        this.guests = Objects.requireNonNull(guests);
        this.search = Objects.requireNonNull(search);
        this.history = Objects.requireNonNull(history);
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
        router.post("/api/rooms/{room}/members", this::addMemberByUsername);
        router.put("/api/rooms/{room}/members/{userId}", this::setRole);
        router.delete("/api/rooms/{room}/members/{userId}", this::removeMember);
        router.get("/api/rooms/{room}/guest-bans", this::guestBans);
        router.get("/api/rooms/{room}/guest-bans/{guestId}", this::guestBan);
        router.put("/api/rooms/{room}/guest-bans/{guestId}", this::banGuest);
        router.delete("/api/rooms/{room}/guest-bans/{guestId}", this::unbanGuest);
        router.get("/api/rooms/{room}/search", this::search);
        router.get("/api/rooms/{room}/history", this::history);
        router.get("/api/rooms/{room}/server-switches", this::serverSwitches);
        // Guest -> account merge moves room data, so it lives with the rooms (GuestMerge).
        new GuestMerge(registry).register(router);
        if (alone != null) {
            new AloneApi(alone, playHistory).register(router);
        }
        if (autoplay != null) {
            new AutoplayApi(autoplay).register(router);
        }
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
        Rooms.Page page = rooms.list(ActorResolver.actor(request), request.query("q"), cursor(request), limit);
        List<Rooms.RoomView> items = new ArrayList<>(page.items().size());
        for (Rooms.RoomView room : page.items()) {
            items.add(room.withListeners(registry.listeners(room.id())));
        }
        response.header("Cache-Control", "no-store");
        response.json(new Rooms.Page(List.copyOf(items), page.nextCursor()));
    }

    private void search(Request request, Response response) throws Exception {
        String param = request.query("limit");
        int limit = RequestSearch.LIST_DEFAULT;
        if (param != null && !param.isEmpty()) {
            try {
                limit = Integer.parseInt(param);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > RequestSearch.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + RequestSearch.LIST_MAX);
            }
        }
        String kind = request.query("kind");
        String type = request.query("type");
        RequestSearch.Page page = search.search(ActorResolver.actor(request), request.pathParam("room"),
                request.query("q"), kind == null || kind.isEmpty() ? null : kind,
                type == null || type.isEmpty() ? null : type, cursor(request), limit,
                isTrue(request.query("all")));
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    /** A query flag: {@code 1} or {@code true}. */
    private static boolean isTrue(String param) {
        return "1".equals(param) || "true".equals(param);
    }

    private void history(Request request, Response response) throws Exception {
        String param = request.query("limit");
        int limit = RoomHistory.LIST_DEFAULT;
        if (param != null && !param.isEmpty()) {
            try {
                limit = Integer.parseInt(param);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > RoomHistory.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + RoomHistory.LIST_MAX);
            }
        }
        RoomHistory.Page page = history.list(ActorResolver.actor(request), request.pathParam("room"),
                cursor(request), limit);
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
        JsonElement move = body.get("moveAloneSession");
        if (move != null && !move.isJsonNull() && !(move instanceof JsonPrimitive p && p.isBoolean())) {
            throw new HttpError(400, "invalid_move_alone_session", "moveAloneSession must be true or false");
        }
        boolean moveAlone = move != null && !move.isJsonNull() && move.getAsBoolean();
        AloneSessions sessions = alone;
        if (moveAlone && sessions == null) {
            throw new HttpError(400, "unknown_field", "Unknown field: moveAloneSession");
        }
        Rooms.RoomView room = rooms.create(user, name, string(body.get("slug"), "invalid_slug"),
                visibility(body.get("visibility")), controlMode(body.get("controlMode")), request.clientIp());
        if (moveAlone) {
            // creating a room moves the current song and queue into it (docs/ui.md#alone-and-in-rooms)
            sessions.moveIntoRoom(user, room.id());
        }
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
        Rooms.Room[] records = new Rooms.Room[2];
        Rooms.Updated updated = registry.call(id, tx -> {
            records[0] = rooms.record(id);
            Rooms.Updated v = rooms.updateAdjusting(user, id, edit, request.clientIp());
            syncRecord(tx);
            tx.refreshAutoplay(); // autoplay.selectors may have pruned selections
            records[1] = rooms.record(id);
            return v;
        });
        if (records[0] != null && records[1] != null) {
            try {
                editListener.edited(records[0], records[1]);
            } catch (Exception e) {
                System.getLogger(RoomsApi.class.getName()).log(System.Logger.Level.WARNING,
                        "A room edit listener failed for room " + id, e);
            }
        }
        response.header("Cache-Control", "no-store");
        // adjusted: settings changed on top of the patch (uploads need library requests)
        JsonObject out = io.siggi.togethertunes.http.Json.GSON.toJsonTree(updated.view()).getAsJsonObject();
        if (!updated.adjusted().isEmpty()) {
            out.add("adjusted", io.siggi.togethertunes.http.Json.GSON.toJsonTree(updated.adjusted()));
        }
        response.json(out);
    }

    private void delete(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String id = roomId(request);
        AloneSessions sessions = alone;
        List<String> wereIn = new ArrayList<>();
        String deleted = registry.call(id, tx -> {
            String roomId = rooms.delete(user, id, request.clientIp(), sessions == null ? null : (c, rid, now) -> {
                wereIn.clear(); // the transaction may run again
                wereIn.addAll(sessions.roomDeleting(c, tx.state(), now));
            });
            tx.closeRoom();
            return roomId;
        });
        if (sessions != null && !wereIn.isEmpty()) {
            sessions.roomDeleted(wereIn);
        }
        try {
            deleteListener.accept(deleted);
        } catch (RuntimeException e) {
            System.getLogger(RoomsApi.class.getName()).log(System.Logger.Level.WARNING,
                    "After deleting room " + deleted + ": " + e, e);
        }
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
        user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ROLE_FIELDS);
        Rooms.Role role = Rooms.Role.parse(string(body.get("role"), "invalid_role"));
        if (role == null) {
            throw new HttpError(400, "invalid_role", "role must be owner, moderator, member or banned");
        }
        setRole(request, response, roomId(request), request.pathParam("userId"), role);
    }

    private void setRole(Request request, Response response, String id, String target, Rooms.Role role)
            throws Exception {
        Actor.User user = user(request);
        // Through the room's executor: an ownership transfer changes the live state's owner.
        Rooms.RoleSet set = registry.call(id, tx -> {
            Rooms.RoleSet r = rooms.setRole(user, id, target, role, request.clientIp());
            syncRecord(tx);
            tx.refreshAutoplay(); // the role change may have pruned selections
            // The WebSocket layer re-checks that user's sockets (role, capabilities, a ban).
            tx.event(new RoomEvent.MemberChanged(target));
            return r;
        });
        response.header("Cache-Control", "no-store");
        response.json(set.created() ? 201 : 200, set.member());
    }

    private void addMemberByUsername(Request request, Response response) throws Exception {
        user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, ADD_MEMBER_FIELDS);
        String username = string(body.get("username"), "invalid_username");
        if (username == null) {
            throw new HttpError(400, "invalid_username", "A username is required");
        }
        Rooms.Role role = Rooms.Role.parse(string(body.get("role"), "invalid_role"));
        if (role == null) {
            throw new HttpError(400, "invalid_role", "role must be owner, moderator, member or banned");
        }
        String id = roomId(request);
        String target = rooms.userIdByUsername(username);
        if (target == null) {
            // Same answer as PUT by id, after the room check, so a hidden room stays hidden.
            rooms.access(ActorResolver.actor(request), id);
            throw new HttpError(404, "user_not_found", "No such user");
        }
        setRole(request, response, id, target, role);
    }

    private void serverSwitches(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        Rooms.Access access = rooms.access(user, request.pathParam("room"));
        if (access.role() == null || !access.role().moderates()) {
            throw HttpError.forbidden("Only the owner and moderators manage a room");
        }
        RoomGuests.ServerSwitches sw = guests.serverSwitches();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("enabled", sw.guestsEnabled());
        g.put("canVote", sw.guests().canVote());
        g.put("canRequestLibrary", sw.guests().canRequestLibrary());
        g.put("canUpload", sw.guests().canUpload());
        g.put("canYoutube", sw.guests().canYoutube());
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("forceAutoOff", sw.forceAutoOff());
        outputs.put("maxAutoOffMinutes", sw.maxAutoOffMinutes());
        outputs.put("rtmp", Map.of("enabled", sw.rtmpEnabled()));
        outputs.put("ts", Map.of("enabled", sw.tsEnabled()));
        outputs.put("hls", Map.of("enabled", sw.hlsEnabled()));
        outputs.put("mp3", Map.of("enabled", sw.mp3Enabled()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guests", g);
        // Whether music and uncategorized videos get the overlay too (in the room's "always" mode; never
        // movies or shows): the owner holds output.overlayVideo (a disabled
        // owner holds nothing), as OverlayDecisions decides it per entry.
        boolean videoOverlay = sw.overlayEnabled()
                && permissions.held(access.room().ownerId()).contains(Permission.OUTPUT_OVERLAY_VIDEO);
        body.put("overlay", Map.of("enabled", sw.overlayEnabled(), "videoOverlay", videoOverlay));
        body.put("outputs", outputs);
        response.header("Cache-Control", "no-store");
        response.json(body);
    }

    private void removeMember(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String id = roomId(request);
        String target = request.pathParam("userId");
        // Through the room's executor, so the WebSocket layer re-checks that user's sockets.
        boolean removed = registry.call(id, tx -> {
            if (!rooms.removeMember(user, id, target, request.clientIp())) {
                return false;
            }
            tx.refreshAutoplay(); // their selections went
            tx.event(new RoomEvent.MemberChanged(target));
            return true;
        });
        if (!removed) {
            throw new HttpError(404, "member_not_found", "That user has no role in this room");
        }
        response.empty(204);
    }

    /** A ban as JSON, with the guest's {@code ip} added from {@code ips} (only ever filled for {@code ip.view}). */
    private static Map<String, Object> banJson(RoomGuests.GuestBan ban, Map<String, String> ips) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guest", ban.guest());
        m.put("identifiedBy", ban.identifiedBy());
        m.put("banned", ban.banned());
        m.put("bannedAt", ban.bannedAt());
        m.put("warning", ban.warning());
        String ip = ips.get(ban.guest().id());
        if (ip != null) {
            m.put("ip", ip);
        }
        return m;
    }

    private Map<String, Object> banJson(Actor.User viewer, RoomGuests.GuestBan ban) throws Exception {
        return banJson(ban, guests.ipsOf(viewer, List.of(ban.guest().id())));
    }

    private void guestBans(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = limit(request);
        RoomGuests.GuestBanPage page = guests.list(user, request.pathParam("room"), cursor(request), limit);
        List<String> ids = new ArrayList<>();
        for (RoomGuests.GuestBan b : page.items()) {
            ids.add(b.guest().id());
        }
        Map<String, String> ips = guests.ipsOf(user, ids);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RoomGuests.GuestBan b : page.items()) {
            items.add(banJson(b, ips));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", page.nextCursor());
        response.header("Cache-Control", "no-store");
        response.json(body);
    }

    private void guestBan(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        RoomGuests.GuestBan ban = guests.get(user, request.pathParam("room"), request.pathParam("guestId"));
        response.header("Cache-Control", "no-store");
        response.json(banJson(user, ban));
    }

    private void banGuest(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        RoomGuests.BanSet set = guests.ban(user, request.pathParam("room"), request.pathParam("guestId"),
                request.clientIp());
        response.header("Cache-Control", "no-store");
        response.json(set.created() ? 201 : 200, banJson(user, set.ban()));
    }

    private void unbanGuest(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        if (!guests.unban(user, request.pathParam("room"), request.pathParam("guestId"), request.clientIp())) {
            throw new HttpError(404, "ban_not_found", "That guest isn't banned from this room");
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

    static void fields(JsonObject body, Set<String> allowed) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
    }

    /** The string, or null for a missing field, JSON null or a blank string; else {@code 400 code}. */
    static String string(JsonElement e, String code) {
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
