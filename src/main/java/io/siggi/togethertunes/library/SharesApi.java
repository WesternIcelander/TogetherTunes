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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sharing items with other accounts ({@code docs/api.md#items-libraries-playlists-sharing},
 * {@link Shares}). Users only: guests and anonymous callers get {@code 401 not_authenticated}
 * (users can't share with guests either). Responses are {@code Cache-Control: no-store}.
 *
 * <ul>
 *   <li>{@code POST /api/shares} {@code {"itemId", "toUsername", "message"?}} → {@code 201 share}
 *       (the sender's view); the item is in the recipient's library from now on. {@code 400
 *       invalid_item_id} / {@code invalid_username} / {@code invalid_message} /
 *       {@code message_too_long} (over {@value Shares#MESSAGE_MAX} characters) /
 *       {@code cannot_share_with_self} / {@code unknown_field}, {@code 404 user_not_found} /
 *       {@code item_not_found}, {@code 409 media_expired} / {@code item_failed}, a purged or
 *       failed YouTube item is fetched again ({@code library.youtube}: {@code 403
 *       feature_disabled}, {@code 422 youtube_failed}), {@code 429 too_many_shares} (with
 *       {@code Retry-After}).</li>
 *   <li>{@code GET /api/shares?direction=in|out&cursor=&limit=} → {@code {"items": [share],
 *       "nextCursor"?, "unseenCount"}}, newest first; {@code direction} defaults to {@code in};
 *       {@code limit} 1..{@value Shares#LIST_MAX}, default {@value Shares#LIST_DEFAULT}.
 *       {@code 400 invalid_direction} / {@code invalid_cursor} / {@code invalid_limit}.</li>
 *   <li>{@code POST /api/shares/seen} {@code {"ids": [shareId, …]}} or {@code {"all": true}} →
 *       {@code {"unseenCount"}}: marks received shares as seen (the notification is read). Ids
 *       that aren't the caller's received shares are ignored. {@code 400 invalid_ids} (not an
 *       array of strings, or more than {@value Shares#SEEN_IDS_MAX}) / {@code invalid_all} /
 *       {@code unknown_field}.</li>
 * </ul>
 * A share is {@code {"id", "item": {…as in the library…}, "from": actor, "to": actor,
 * "message"?, "createdAt", "seen"?}}; {@code seen} only in the recipient's view.
 */
public final class SharesApi {
    private static final Set<String> SHARE_FIELDS = Set.of("itemId", "toUsername", "message");
    private static final Set<String> SEEN_FIELDS = Set.of("ids", "all");

    private final Shares shares;
    private final AddMediaPolicy policy;

    public SharesApi(Shares shares, AddMediaPolicy policy) {
        this.shares = Objects.requireNonNull(shares);
        this.policy = Objects.requireNonNull(policy);
    }

    public void register(Router router) {
        router.get("/api/shares", this::list);
        router.post("/api/shares", this::share);
        router.post("/api/shares/seen", this::seen);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void share(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, SHARE_FIELDS);
        String itemId = string(body.get("itemId"), "invalid_item_id");
        if (itemId == null) {
            throw new HttpError(400, "invalid_item_id", "itemId is required");
        }
        String toUsername = string(body.get("toUsername"), "invalid_username");
        if (toUsername == null) {
            throw new HttpError(400, "invalid_username", "toUsername is required");
        }
        String message = string(body.get("message"), "invalid_message");
        Shares.Share share = shares.share(user, itemId, toUsername, message,
                () -> policy.requireYoutube(user, null));
        response.header("Cache-Control", "no-store");
        response.json(201, share);
    }

    private void list(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String directionParam = request.query("direction");
        Shares.Direction direction = directionParam == null || directionParam.isEmpty()
                ? Shares.Direction.IN : Shares.Direction.parse(directionParam);
        int limit = Shares.LIST_DEFAULT;
        String limitParam = request.query("limit");
        if (limitParam != null && !limitParam.isEmpty()) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > Shares.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + Shares.LIST_MAX);
            }
        }
        String cursor = request.query("cursor");
        Shares.Page page = shares.list(user.userId(), direction, cursor == null || cursor.isEmpty() ? null : cursor,
                limit);
        response.header("Cache-Control", "no-store");
        response.json(page);
    }

    private void seen(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        JsonObject body = request.readJson(JsonObject.class);
        fields(body, SEEN_FIELDS);
        boolean all = false;
        if (body.has("all")) {
            JsonElement e = body.get("all");
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
                throw new HttpError(400, "invalid_all", "all must be true or false");
            }
            all = p.getAsBoolean();
        }
        List<String> ids = null;
        if (body.has("ids") && !body.get("ids").isJsonNull()) {
            if (!(body.get("ids") instanceof JsonArray array) || array.size() > Shares.SEEN_IDS_MAX) {
                throw new HttpError(400, "invalid_ids", "ids must be an array of at most "
                        + Shares.SEEN_IDS_MAX + " share ids");
            }
            ids = new ArrayList<>(array.size());
            for (JsonElement e : array) {
                if (!(e instanceof JsonPrimitive p) || !p.isString()) {
                    throw new HttpError(400, "invalid_ids", "ids must be share ids");
                }
                ids.add(p.getAsString());
            }
        }
        if (all == (ids != null)) {
            throw new HttpError(400, "invalid_ids", "Send either ids or all: true");
        }
        int unseen = shares.markSeen(user.userId(), all ? null : ids);
        response.header("Cache-Control", "no-store");
        response.json(Map.of("unseenCount", unseen));
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
