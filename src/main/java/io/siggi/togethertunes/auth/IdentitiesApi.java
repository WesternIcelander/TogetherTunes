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
 * The logged-in user's linked logins ({@code docs/api.md}, {@link Identities}).
 *
 * <ul>
 *   <li>{@code GET /api/me/identities} → {@code {"items": [{"id", "provider": {"slug",
 *       "displayName", "enabled"}, "email"?, "createdAt", "lastLoginAt"?}], "hasPassword"}}, oldest
 *       first. No subject and no IP. Linking happens through
 *       {@code /auth/oidc/{slug}/start?intent=link}.</li>
 *   <li>{@code DELETE /api/me/identities/{id}} → {@code 204}; {@code 404 identity_not_found},
 *       {@code 409 last_login_method}.</li>
 * </ul>
 * Users only: guests and anonymous callers get {@code 401 not_authenticated}.
 */
public final class IdentitiesApi {
    private final Identities identities;

    public IdentitiesApi(Identities identities) {
        this.identities = Objects.requireNonNull(identities);
    }

    public void register(Router router) {
        router.get("/api/me/identities", this::list);
        router.delete("/api/me/identities/{id}", this::unlink);
    }

    private static Actor.User user(Request request) throws java.sql.SQLException {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        return user;
    }

    private void list(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Identities.IdentityView v : identities.list(user.userId())) {
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("slug", v.providerSlug());
            provider.put("displayName", v.providerName());
            provider.put("enabled", v.providerEnabled());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id());
            m.put("provider", provider);
            if (v.email() != null) {
                m.put("email", v.email());
            }
            m.put("createdAt", v.createdAt());
            if (v.lastLoginAt() != null) {
                m.put("lastLoginAt", v.lastLoginAt());
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("hasPassword", identities.hasPassword(user.userId()));
        response.header("Cache-Control", "no-store");
        response.json(out);
    }

    private void unlink(Request request, Response response) throws Exception {
        Actor.User user = user(request);
        identities.unlink(user.userId(), request.pathParam("id"), request.clientIp());
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }
}
