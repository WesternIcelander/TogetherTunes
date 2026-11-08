package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A user's own sessions, {@code /api/me/sessions} ({@code docs/api.md}). Users only (a guest or
 * anonymous request gets {@code 401 not_authenticated}), and only their own sessions.
 *
 * <ul>
 *   <li>{@code GET /api/me/sessions?cursor=&limit=} → {@code {"items": [{"id", "kind",
 *       "createdAt", "lastSeenAt", "expiresAt", "userAgent"?, "current"}], "nextCursor"?}}: the live
 *       sessions (web logins and bearer tokens), newest first; {@code current} marks the one this
 *       request uses. {@code limit} 1..{@value Sessions#LIST_MAX}, default
 *       {@value Sessions#LIST_DEFAULT}. Absent keys are null (as everywhere, nulls are left out):
 *       no {@code nextCursor} on the last page. <b>No IP field</b> ({@code docs/auth.md#ip-address-policy}):
 *       IPs of sessions are only for {@code ip.view}, through {@code /api/admin/ip/sessions}.</li>
 *   <li>{@code DELETE /api/me/sessions/{id}} → {@code 204}; {@code 404 not_found} if the user has
 *       no such session. Revoking the current session logs this request's client out (and clears
 *       the {@code sid} cookie when it was a cookie session). Audited as
 *       {@value #AUDIT_REVOKE}.</li>
 *   <li>{@code DELETE /api/me/sessions} → {@code 200 {"revoked": n}}: revokes every session
 *       except the current one ("log out everywhere else"). Audited as
 *       {@value #AUDIT_REVOKE_OTHERS}.</li>
 * </ul>
 */
public final class SessionsApi {
    /** {@code audit_log} action of a session revoked by its owner. */
    public static final String AUDIT_REVOKE = "auth.session_revoked";
    /** {@code audit_log} action of "log out everywhere else". */
    public static final String AUDIT_REVOKE_OTHERS = "auth.sessions_revoked";

    private final Sessions sessions;
    private final Accounts accounts;
    private final boolean secureCookies;

    /**
     * @param secureCookies whether the cleared {@code sid} cookie carries {@code Secure} (as in
     *                      {@link AuthApi})
     */
    public SessionsApi(Sessions sessions, Accounts accounts, boolean secureCookies) {
        this.sessions = Objects.requireNonNull(sessions);
        this.accounts = Objects.requireNonNull(accounts);
        this.secureCookies = secureCookies;
    }

    public void register(Router router) {
        router.get("/api/me/sessions", this::list);
        router.delete("/api/me/sessions", this::revokeOthers);
        router.delete("/api/me/sessions/{id}", this::revoke);
    }

    private static Actor.User user(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw new HttpError(401, "not_authenticated", "Log in first");
    }

    private void list(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int limit = Sessions.LIST_DEFAULT;
        String limitParam = request.query("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > Sessions.LIST_MAX) {
                throw new HttpError(400, "invalid_limit", "limit must be 1.." + Sessions.LIST_MAX);
            }
        }
        String cursor = request.query("cursor");
        if (cursor != null && cursor.isEmpty()) {
            cursor = null;
        }
        Sessions.Page page = sessions.list(user.userId(), cursor, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Sessions.SessionView s : page.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.id());
            item.put("kind", s.kind());
            item.put("createdAt", s.createdAt());
            item.put("lastSeenAt", s.lastSeenAt());
            item.put("expiresAt", s.expiresAt());
            item.put("userAgent", s.userAgent());
            item.put("current", s.id().equals(user.sessionId()));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", page.nextCursor());
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void revoke(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        String id = request.pathParam("id");
        if (!sessions.revoke(user.userId(), id)) {
            throw new HttpError(404, "not_found", "No such session");
        }
        accounts.audit(user.userId(), request.clientIp(), AUDIT_REVOKE, id,
                Map.of("current", id.equals(user.sessionId())));
        if (id.equals(user.sessionId()) && user.via() == Actor.Via.COOKIE) {
            response.setCookie(Sessions.cookie("", 0, secureCookies));
        }
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void revokeOthers(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        int revoked = sessions.revokeOthers(user.userId(), user.sessionId());
        accounts.audit(user.userId(), request.clientIp(), AUDIT_REVOKE_OTHERS, user.userId(),
                Map.of("revoked", revoked));
        response.header("Cache-Control", "no-store");
        response.json(Map.of("revoked", revoked));
    }
}
