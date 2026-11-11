package io.siggi.togethertunes.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * Login providers over HTTP ({@code docs/api.md}): the admin pages' {@code /api/admin/providers}
 * and the login page's {@code /api/auth/providers}. The rules are {@link Providers}'.
 *
 * <ul>
 *   <li>{@code GET /api/auth/providers} (anyone) → {@code {"items": [{"slug", "displayName",
 *       "allowSignup", "startUrl"}]}}: the enabled providers, one login button each, oldest
 *       first. {@code startUrl} is {@code /auth/oidc/{slug}/start} (add {@code ?intent=link} to
 *       link the provider to the logged-in account).</li>
 *   <li>{@code GET /api/admin/providers} → {@code {"items": [provider]}} (every provider, oldest
 *       first; not paginated, there are few).</li>
 *   <li>{@code POST /api/admin/providers} {@code {"slug", "displayName", "kind"?, "issuer"?,
 *       "clientId", "clientSecret"?, "scopes"?, "enabled"?, "allowSignup"?, "claimMapping"?}} →
 *       {@code 201 provider}. {@code kind} defaults to {@code oidc}.</li>
 *   <li>{@code GET /api/admin/providers/{id}} → provider.</li>
 *   <li>{@code PATCH /api/admin/providers/{id}} with any of those fields but {@code kind} →
 *       provider. {@code clientSecret}: a string replaces it, {@code null} removes it, absent
 *       keeps it. {@code claimMapping} replaces the whole mapping ({@code null} removes it).</li>
 *   <li>{@code DELETE /api/admin/providers/{id}} → {@code 204}.</li>
 * </ul>
 *
 * <p>{@code provider} is {@code {"id", "slug", "displayName", "kind", "issuer", "clientId",
 * "hasClientSecret", "scopes", "enabled", "allowSignup", "claimMapping"?, "redirectUri",
 * "identities"}}: {@code redirectUri} is what to register at the provider, {@code identities}
 * how many accounts are linked. The client secret is write-only: no response, audit entry or log
 * line contains it.
 *
 * <p>The admin routes need {@code admin.providers}. Errors: {@code 400 invalid_provider},
 * {@code 400 unknown_field}, {@code 400 invalid_json}, {@code 404 provider_not_found},
 * {@code 409 provider_slug_taken}, {@code 409 provider_has_identities}. Changes are audited
 * ({@value #AUDIT_CREATED}, {@value #AUDIT_UPDATED}, {@value #AUDIT_DELETED}; a secret change only
 * as {@code "clientSecret": "set" | "removed"}). Responses are {@code Cache-Control: no-store}.
 */
public final class ProvidersApi {
    public static final String AUDIT_CREATED = "admin.provider_created";
    public static final String AUDIT_UPDATED = "admin.provider_updated";
    public static final String AUDIT_DELETED = "admin.provider_deleted";

    private static final Set<String> FIELDS = Set.of("slug", "displayName", "kind", "issuer", "clientId",
            "clientSecret", "scopes", "enabled", "allowSignup", "claimMapping");

    private final Providers providers;
    private final Permissions permissions;
    private final Accounts accounts;
    private final String publicBaseUrl;
    private OidcLogin login;

    public ProvidersApi(Providers providers, Permissions permissions, Accounts accounts, String publicBaseUrl) {
        this.providers = Objects.requireNonNull(providers);
        this.permissions = Objects.requireNonNull(permissions);
        this.accounts = Objects.requireNonNull(accounts);
        String base = Objects.requireNonNull(publicBaseUrl);
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * The login flow ({@code /auth/oidc/{slug}/start} and {@code /callback}), registered with the
     * other routes; without it only the provider admin and list exist.
     */
    public void loginFlow(OidcLogin login) {
        this.login = Objects.requireNonNull(login);
    }

    public void register(Router router) {
        if (login != null) {
            login.register(router);
        }
        var perm = permissions.hook(Permission.ADMIN_PROVIDERS);
        router.get("/api/auth/providers", this::publicList);
        router.get("/api/admin/providers", this::list, perm);
        router.post("/api/admin/providers", this::create, perm);
        router.get("/api/admin/providers/{id}", this::get, perm);
        router.patch("/api/admin/providers/{id}", this::update, perm);
        router.delete("/api/admin/providers/{id}", this::delete, perm);
    }

    /** The redirect URI of a provider with this slug. */
    public String redirectUri(String slug) {
        return OidcLogin.redirectUri(publicBaseUrl, slug);
    }

    // ------------------------------------------------------------- handlers

    private void publicList(Request request, Response response) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Providers.Provider p : providers.listEnabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug", p.slug());
            m.put("displayName", p.displayName());
            m.put("allowSignup", p.allowSignup());
            m.put("startUrl", "/auth/oidc/" + p.slug() + "/start");
            items.add(m);
        }
        send(response, 200, Map.of("items", items));
    }

    private void list(Request request, Response response) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Providers.Provider p : providers.list()) {
            items.add(json(p));
        }
        send(response, 200, Map.of("items", items));
    }

    private void get(Request request, Response response) throws Exception {
        send(response, 200, json(providers.get(request.pathParam("id"))));
    }

    private void create(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        JsonObject body = request.readJson(JsonObject.class);
        checkFields(body);
        Providers.Kind kind = body.has("kind") && !body.get("kind").isJsonNull()
                ? Providers.Kind.of(string(body, "kind")) : Providers.Kind.OIDC;
        Providers.Spec spec = new Providers.Spec(string(body, "slug"), string(body, "displayName"), kind,
                string(body, "issuer"), string(body, "clientId"), string(body, "scopes"),
                bool(body, "enabled", true), bool(body, "allowSignup", false),
                ClaimMapping.parse(body.get("claimMapping"), kind));
        String secret = string(body, "clientSecret");
        Providers.Provider p = providers.create(spec, secret);
        Map<String, Object> details = auditFields(p);
        details.put("clientSecret", secret == null ? "none" : "set");
        accounts.audit(actor.userId(), request.clientIp(), AUDIT_CREATED, p.id(), details);
        send(response, 201, json(p));
    }

    private void update(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        JsonObject body = request.readJson(JsonObject.class);
        checkFields(body);
        Providers.Provider before = providers.get(id);
        if (body.has("kind") && !before.kind().wire().equals(string(body, "kind"))) {
            throw Providers.invalid("A provider's kind can't be changed; create a new provider instead");
        }
        Providers.Spec old = before.spec();
        Providers.Spec spec = new Providers.Spec(
                body.has("slug") ? string(body, "slug") : old.slug(),
                body.has("displayName") ? string(body, "displayName") : old.displayName(),
                old.kind(),
                body.has("issuer") ? string(body, "issuer") : old.issuer(),
                body.has("clientId") ? string(body, "clientId") : old.clientId(),
                body.has("scopes") ? string(body, "scopes") : old.scopes(),
                bool(body, "enabled", old.enabled()),
                bool(body, "allowSignup", old.allowSignup()),
                body.has("claimMapping") ? ClaimMapping.parse(body.get("claimMapping"), old.kind()) : old.claimMapping());
        Providers.SecretChange secret = body.has("clientSecret")
                ? new Providers.SecretChange.Replace(string(body, "clientSecret")) : Providers.SecretChange.KEEP;
        Providers.Provider after = providers.update(id, spec, secret);
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> was = auditFields(before);
        Map<String, Object> now = auditFields(after);
        for (Map.Entry<String, Object> e : now.entrySet()) {
            if (!Objects.equals(e.getValue(), was.get(e.getKey()))) {
                details.put(e.getKey(), e.getValue());
            }
        }
        if (secret instanceof Providers.SecretChange.Replace r) {
            details.put("clientSecret", r.value() == null ? "removed" : "set");
        }
        if (!details.isEmpty()) {
            accounts.audit(actor.userId(), request.clientIp(), AUDIT_UPDATED, id, details);
        }
        send(response, 200, json(after));
    }

    private void delete(Request request, Response response) throws Exception {
        Actor.User actor = actor(request);
        String id = request.pathParam("id");
        Providers.Provider p = providers.delete(id);
        accounts.audit(actor.userId(), request.clientIp(), AUDIT_DELETED, id,
                Map.of("slug", p.slug(), "displayName", p.displayName()));
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Object> json(Providers.Provider p) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("slug", p.slug());
        m.put("displayName", p.displayName());
        m.put("kind", p.kind().wire());
        m.put("issuer", p.issuer());
        m.put("clientId", p.clientId());
        m.put("hasClientSecret", p.hasClientSecret());
        m.put("scopes", p.scopes());
        m.put("enabled", p.enabled());
        m.put("allowSignup", p.allowSignup());
        m.put("claimMapping", p.claimMapping() == null ? null : p.claimMapping().toMap());
        m.put("redirectUri", redirectUri(p.slug()));
        m.put("identities", providers.identityCount(p.id()));
        return m;
    }

    /** The non-secret settings, for audit details. */
    private static Map<String, Object> auditFields(Providers.Provider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", p.slug());
        m.put("displayName", p.displayName());
        m.put("kind", p.kind().wire());
        m.put("issuer", p.issuer());
        m.put("clientId", p.clientId());
        m.put("scopes", p.scopes());
        m.put("enabled", p.enabled());
        m.put("allowSignup", p.allowSignup());
        m.put("claimMapping", p.claimMapping() == null ? null : p.claimMapping().toMap());
        return m;
    }

    private static void checkFields(JsonObject body) {
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new HttpError(400, "unknown_field", "Unknown field: " + key);
            }
        }
    }

    private static Actor.User actor(Request request) throws Exception {
        if (ActorResolver.actor(request) instanceof Actor.User user) {
            return user;
        }
        throw Permissions.notAuthenticated(); // unreachable behind the permission hook
    }

    private static void send(Response response, int status, Object body) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(status, body);
    }

    private static String string(JsonObject body, String name) {
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new HttpError(400, "invalid_json", name + " must be a string");
        }
        return e.getAsString();
    }

    private static boolean bool(JsonObject body, String name, boolean def) {
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new HttpError(400, "invalid_json", name + " must be true or false");
        }
        return e.getAsBoolean();
    }
}
