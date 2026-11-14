package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Invites over HTTP ({@code docs/auth.md#invites}, {@code docs/api.md}); all {@code no-store}.
 *
 * <ul>
 *   <li>{@code POST /api/invites} {@code {"expiresInHours"?, "note"?}} (body optional) →
 *       {@code 201 {"invite": invite, "code", "url"}}: needs {@code users.invite}. The code (and
 *       the registration link {@code url} holding it) is shown only in this response.
 *       {@code expiresInHours} 1..{@value Invites#MAX_HOURS}, default {@value Invites#DEFAULT_HOURS};
 *       {@code note} (who it's for) at most {@value Invites#NOTE_MAX} characters. Without
 *       {@code admin.users} a user has at most {@value Invites#PENDING_MAX} pending invites
 *       ({@code 409 too_many_invites}).</li>
 *   <li>{@code GET /api/invites?cursor=&limit=} → {@code {"items": [invite], "nextCursor"?}}, newest
 *       first: holders of {@code admin.users} see every invite, others (with {@code users.invite})
 *       their own. {@code limit} 1..{@value Invites#LIST_MAX}, default {@value Invites#LIST_DEFAULT}.</li>
 *   <li>{@code DELETE /api/invites/{id}} → {@code 200 invite}: revokes a pending invite (own ones;
 *       any with {@code admin.users}); a revoked or expired one is returned unchanged;
 *       {@code 409 invite_used}; {@code 404 invite_not_found} (also for someone else's).</li>
 * </ul>
 *
 * <p>{@code invite} is {@code {"id", "status": "pending"|"used"|"revoked"|"expired", "createdAt",
 * "expiresAt", "note"?, "createdBy"?: actor, "usedBy"?: actor, "usedAt"?, "revokedAt"?}}, never the
 * code. Guests and anonymous visitors get {@code 401 not_authenticated}; users holding neither
 * permission {@code 403 forbidden}.
 */
public final class InvitesApi {
    private final Invites invites;
    private final Permissions permissions;
    private final String publicBaseUrl;

    record CreateRequest(Integer expiresInHours, String note) {
    }

    /** @param publicBaseUrl {@code http.publicBaseUrl}, for the registration link */
    public InvitesApi(Invites invites, Permissions permissions, String publicBaseUrl) {
        this.invites = Objects.requireNonNull(invites);
        this.permissions = Objects.requireNonNull(permissions);
        String base = Objects.requireNonNull(publicBaseUrl);
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The registration link for an invite code. */
    public String link(String code) {
        return publicBaseUrl + "/register?invite=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }

    public void register(Router router) {
        router.post("/api/invites", this::create);
        router.get("/api/invites", this::list);
        router.delete("/api/invites/{id}", this::revoke);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw Permissions.notAuthenticated();
    }

    /** Whether the user sees and revokes every invite ({@code admin.users}); 403 if neither permission. */
    private boolean seesAll(Actor.User user) throws Exception {
        Set<Permission> held = permissions.held(user.userId());
        if (held.contains(Permission.ADMIN_USERS)) {
            return true;
        }
        if (held.contains(Permission.USERS_INVITE)) {
            return false;
        }
        throw HttpError.forbidden("Missing permission " + Permission.USERS_INVITE.wireName());
    }

    private void create(Request request, Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        Actor.User user = user(request);
        permissions.require(user, Permission.USERS_INVITE);
        boolean noBody = request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
        CreateRequest body = noBody ? null : request.readJson(CreateRequest.class);
        boolean limited = !permissions.held(user.userId()).contains(Permission.ADMIN_USERS);
        Invites.Created created = invites.create(user.userId(), body == null ? null : body.expiresInHours(),
                body == null ? null : body.note(), limited, request.clientIp());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invite", created.invite());
        out.put("code", created.code());
        out.put("url", link(created.code()));
        response.json(201, out);
    }

    private void list(Request request, Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        Actor.User user = user(request);
        boolean all = seesAll(user);
        int limit = Invites.LIST_DEFAULT;
        String limitParam = request.query("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > Invites.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + Invites.LIST_MAX);
            }
        }
        String cursor = request.query("cursor");
        if (cursor != null && cursor.isEmpty()) {
            cursor = null;
        }
        Invites.Page page = invites.list(all ? null : user.userId(), cursor, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page.items());
        out.put("nextCursor", page.nextCursor());
        response.json(out);
    }

    private void revoke(Request request, Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        Actor.User user = user(request);
        boolean all = seesAll(user);
        Invites.InviteView invite = invites.revoke(request.pathParam("id"), all ? null : user.userId(),
                user.userId(), request.clientIp());
        response.json(invite);
    }
}
