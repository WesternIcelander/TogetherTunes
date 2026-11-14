package io.siggi.togethertunes.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.RateLimiter;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The login flow with a login provider ({@code docs/auth.md#oidc}, step 2 and the code exchange of
 * step 3): the <b>authorization code flow with PKCE (S256)</b>, for OIDC and plain OAuth2 providers
 * alike.
 *
 * <ul>
 *   <li>{@code GET /auth/oidc/{slug}/start?intent=login|link&next=/path}: makes a random
 *       {@code state}, {@code nonce} (OIDC only) and PKCE {@code code_verifier}, keeps them
 *       <b>server-side, keyed by the state's SHA-256</b> ({@link PendingLogins}), and redirects
 *       ({@code 302}) to the provider's authorization endpoint with {@code response_type=code},
 *       {@code client_id}, {@code redirect_uri}, {@code scope}, {@code state}, {@code nonce} and
 *       {@code code_challenge} / {@code code_challenge_method=S256}. {@code intent=link} needs a
 *       logged-in user and remembers who. {@code next} must be a local path ({@code /…}, not
 *       {@code //…}); anything else becomes {@code /}. {@code popup=1} (logins only, ignored for a
 *       link) marks a login started in a popup window (docs/ui.md#single-page-app): its end, success
 *       or failure, goes to {@link #POPUP_DONE_PAGE} instead of {@code next} or the login page.</li>
 *   <li>{@code GET /auth/oidc/{slug}/callback?state&code} (the redirect URI
 *       {@code {publicBaseUrl}/auth/oidc/{slug}/callback}): takes the pending login out of the
 *       store (<b>a state is good for one callback</b>, also a failed one, and for
 *       {@link #TTL_MS}), checks it was started for this provider, in this browser (the binding
 *       cookie, below) and, for a link, by the user still logged in, then exchanges the code at the
 *       token endpoint with the {@code code_verifier}, validates the ID token (OIDC,
 *       {@link IdTokenValidator}) or fetches the userinfo URL (plain OAuth2, below) and hands the
 *       result to the {@link Completion} (in the server: {@link IdentityResolution}).</li>
 * </ul>
 *
 * <p><b>Popup logins:</b> {@code GET} {@value #POPUP_DONE_PAGE} ({@code ?error=<code>} on failure) is a
 * tiny page whose script ({@code /js/popupdone.js}) tells the page that opened the popup how the
 * login ended (same-origin {@code postMessage}, and a {@code BroadcastChannel} for when the provider's
 * pages cut the opener link) and closes the window.
 *
 * <p><b>Browser binding:</b> the start sets the cookie {@value #BINDING_COOKIE} (a random token,
 * {@code HttpOnly; SameSite=Lax; Path=/auth/oidc/}, {@link #TTL_MS}) and the pending login stores its
 * hash; a callback from a browser without that cookie is refused. This stops login CSRF (an attacker
 * sending a victim a callback URL with the attacker's own code). The cookie is reused while it is
 * set, so logins in two tabs both work. Each provider has its own redirect URI and its states only
 * work at that URI, so a code can't be taken to another provider's callback (mix-up).
 *
 * <p><b>Token request:</b> {@code POST} form with {@code grant_type=authorization_code},
 * {@code code}, {@code redirect_uri} and {@code code_verifier}, {@code Accept: application/json}. A
 * public client (no secret) sends {@code client_id} in the form; with a secret, OIDC uses
 * {@code client_secret_basic} when the provider lists it or lists no methods (the default), else
 * {@code client_secret_post}; plain OAuth2 uses {@code client_secret_post}. The response needs an
 * {@code access_token} (and, for OIDC, an {@code id_token}); an {@code error} field fails it even
 * with status 200. Redirects aren't followed, {@value #MAX_BODY_BYTES} bytes at most.
 *
 * <p><b>Userinfo (plain OAuth2):</b> {@code GET} the mapping's {@code userinfoUrl} with
 * {@code Authorization: Bearer <access_token>}, {@code Accept: application/json}, a
 * {@code User-Agent} (some APIs refuse requests without one) and {@code Client-Id} (the public
 * client ID; some APIs want it next to the token). It must answer {@code 200} with JSON, same
 * limits as the token request; the {@link ClaimMapping} paths are resolved against it by
 * {@link IdentityResolution#identityOf}.
 *
 * <p><b>Failures</b> redirect ({@code 302}) to the login page {@code /login?error=<code>}
 * (with {@code next}), or for a link to the account page {@code /account/?error=<code>}. Codes:
 * {@code provider_unavailable} (unknown or disabled provider, or changed while logging in),
 * {@code provider_unreachable} (discovery failed), {@code provider_unsupported} (no PKCE S256),
 * {@code not_authenticated} (link without a login), {@code login_expired} (unknown, used or expired
 * state, or a state of another provider), {@code login_browser_mismatch} (binding cookie missing or
 * different), {@code link_session_changed}, {@code provider_denied} (the user said no),
 * {@code provider_error}, {@code token_exchange_failed}, {@code id_token_invalid} (signature,
 * algorithm, {@code iss}, {@code aud}, {@code exp}, {@code iat}, {@code nonce}),
 * {@code userinfo_failed} (the userinfo request failed or its answer has no usable subject), and
 * {@code login_unavailable} (the default {@link Completion}); identity resolution
 * ({@link IdentityResolution}) adds
 * {@code account_disabled}, {@code signup_disabled}, {@code setup_required},
 * {@code identity_in_use} and {@code provider_already_linked}. Start and callback share a per-IP limit
 * ({@code 429 rate_limited}). Never logged: state, nonce, verifier, code, tokens, secrets.
 */
public final class OidcLogin {
    private static final System.Logger LOG = System.getLogger(OidcLogin.class.getName());

    /** How long a started login can be completed: 10 min. */
    public static final long TTL_MS = 10 * 60_000L;
    /** Most pending logins kept; starting more drops the oldest. */
    public static final int MAX_PENDING = 10_000;
    public static final String BINDING_COOKIE = "oidc_login";
    public static final String COOKIE_PATH = "/auth/oidc/";
    public static final int MAX_BODY_BYTES = 512 * 1024;
    static final int NEXT_MAX = 2048;
    static final String LOGIN_PAGE = "/login";
    static final String ACCOUNT_PAGE = "/account/";
    /** Where a login started in a popup ends ({@code ?error=<code>} on failure). */
    public static final String POPUP_DONE_PAGE = "/auth/popup-done";
    /** The popup-done page: no inline script, the result is read from the URL by popupdone.js. */
    static final String POPUP_DONE_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Logging in · TogetherTunes</title>
            <link rel="stylesheet" href="/css/main.css">
            </head>
            <body>
            <main class="popup-done">
            <p id="popup-done-status" role="status">Finishing the login…</p>
            </main>
            <script type="module" src="/js/popupdone.js"></script>
            </body>
            </html>
            """;
    static final String USER_AGENT = "TogetherTunes";

    /** What the user started the login for. */
    public enum Intent {
        /** Log in (or sign up). */
        LOGIN,
        /** Link the provider to the logged-in account. */
        LINK;

        /** {@code null} or {@code login} → LOGIN, {@code link} → LINK, anything else {@code null}. */
        static Intent of(String wire) {
            if (wire == null || wire.equals("login")) {
                return LOGIN;
            }
            return wire.equals("link") ? LINK : null;
        }
    }

    /**
     * A started login, as kept server-side.
     *
     * @param nonce   the ID token must carry it (OIDC); {@code null} for OAuth2
     * @param issuer  the provider's issuer when it started (a change fails the callback)
     * @param userId  for {@link Intent#LINK}: the user linking; otherwise {@code null}
     * @param next    local path to return to
     * @param popup   started in a popup window (logins only): ends at {@link #POPUP_DONE_PAGE}
     */
    public record Pending(String providerId, String slug, Providers.Kind kind, String issuer, String nonce,
                          String codeVerifier, Intent intent, String userId, String next, long expiresAt,
                          boolean popup) {
        public Pending {
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(slug);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(codeVerifier);
            Objects.requireNonNull(intent);
            Objects.requireNonNull(next);
        }

        @Override
        public String toString() {
            return "Pending[" + slug + ", " + intent + (popup ? ", popup" : "") + "]";
        }
    }

    /** The token endpoint's answer. {@code idToken} is set for OIDC (unvalidated here). */
    public record TokenResponse(String accessToken, String tokenType, String idToken, String scope, Long expiresIn) {
        @Override
        public String toString() {
            return "TokenResponse[" + tokenType + ", idToken=" + (idToken != null) + "]";
        }
    }

    /**
     * A callback that got through the state checks and the code exchange.
     *
     * @param idToken  for OIDC: the validated ID token ({@link IdTokenValidator}); {@code null} for
     *                 OAuth2
     * @param userinfo for OAuth2: the userinfo URL's JSON answer; {@code null} for OIDC
     */
    public record Callback(Providers.Provider provider, Pending pending, TokenResponse tokens,
                           IdTokenValidator.IdToken idToken, JsonElement userinfo) {
        @Override
        public String toString() {
            return "Callback[" + provider.slug() + ", " + pending + "]";
        }
    }

    /** What happens after the code exchange and ID-token validation: identity resolution. */
    @FunctionalInterface
    public interface Completion {
        void complete(Request request, Response response, Callback callback) throws Exception;
    }

    /** A failed login; {@link #code} goes to the page, the message only to the log. */
    public static final class LoginException extends Exception {
        private final String code;

        public LoginException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code);
        }

        public String code() {
            return code;
        }
    }

    /** The provider's endpoints for one login. */
    record Endpoints(String authorization, String token, List<String> authMethods, boolean supportsS256) {
    }

    private final Providers providers;
    private final OidcDiscovery discovery;
    private final String publicBaseUrl;
    private final boolean secureCookies;
    private final HttpClient client;
    private final Duration timeout;
    private final Clock clock;
    private final RateLimiter limiter;
    private final PendingLogins pending;
    private final IdTokenValidator idTokens;
    private volatile Completion completion = (request, response, callback) -> fail(response, callback.pending(),
            "login_unavailable");

    public OidcLogin(Providers providers, OidcDiscovery discovery, String publicBaseUrl, boolean secureCookies) {
        this(providers, discovery, publicBaseUrl, secureCookies,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(15), Clock.systemUTC(),
                new RateLimiter("provider-login", 20, 3_000, Clock.systemUTC()));
    }

    /**
     * @param client  must not follow redirects
     * @param timeout of a whole token request
     * @param limiter per-IP limit shared by start and callback
     */
    public OidcLogin(Providers providers, OidcDiscovery discovery, String publicBaseUrl, boolean secureCookies,
                     HttpClient client, Duration timeout, Clock clock, RateLimiter limiter) {
        this.providers = Objects.requireNonNull(providers);
        this.discovery = Objects.requireNonNull(discovery);
        String base = Objects.requireNonNull(publicBaseUrl);
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.secureCookies = secureCookies;
        this.client = Objects.requireNonNull(client);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("the login client must not follow redirects");
        }
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
        this.limiter = Objects.requireNonNull(limiter);
        this.pending = new PendingLogins(clock, MAX_PENDING);
        this.idTokens = new IdTokenValidator(discovery, clock::millis);
    }

    /** Sets what runs after a successful code exchange. */
    public void completion(Completion completion) {
        this.completion = Objects.requireNonNull(completion);
    }

    public void register(Router router) {
        var limit = Hooks.rateLimit(limiter);
        router.get("/auth/oidc/{slug}/start", this::start, limit);
        router.get("/auth/oidc/{slug}/callback", this::callback, limit);
        router.get(POPUP_DONE_PAGE, OidcLogin::popupDone);
    }

    /** {@code {publicBaseUrl}/auth/oidc/{slug}/callback}. */
    public static String redirectUri(String publicBaseUrl, String slug) {
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/auth/oidc/" + slug + "/callback";
    }

    /** The pending logins (for tests). */
    PendingLogins pending() {
        return pending;
    }

    // ------------------------------------------------------------- start

    private void start(Request request, Response response) throws Exception {
        String slug = request.pathParam("slug");
        Intent intent = Intent.of(request.query("intent"));
        String next = safeNext(request.query("next"));
        boolean popup = intent == Intent.LOGIN && "1".equals(request.query("popup"));
        if (intent == null) {
            failRedirect(response, Intent.LOGIN, next, "provider_error");
            return;
        }
        String userId = null;
        if (intent == Intent.LINK) {
            if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
                failRedirect(response, intent, next, "not_authenticated");
                return;
            }
            userId = user.userId();
        }
        Providers.Provider provider = providers.bySlug(slug);
        if (provider == null || !provider.enabled()) {
            failRedirect(response, intent, next, popup, "provider_unavailable");
            return;
        }
        Endpoints endpoints;
        try {
            endpoints = endpoints(provider);
        } catch (LoginException e) {
            LOG.log(System.Logger.Level.WARNING, "Login with provider " + slug + " can't start: " + e.getMessage());
            failRedirect(response, intent, next, popup, e.code());
            return;
        }
        String state = Tokens.newToken();
        String verifier = Tokens.newToken();
        String nonce = provider.kind() == Providers.Kind.OIDC ? Tokens.newToken() : null;
        String binding = request.cookie(BINDING_COOKIE);
        if (!Tokens.isWellFormed(binding)) {
            binding = Tokens.newToken();
        }
        // Set (or refresh) it every time, so it lives as long as the newest pending login.
        response.setCookie(bindingCookie(binding, TTL_MS / 1000));
        pending.put(state, Tokens.hash(binding), new Pending(provider.id(), provider.slug(), provider.kind(),
                provider.issuer(), nonce, verifier, intent, userId, next, clock.millis() + TTL_MS, popup));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", redirectUri(publicBaseUrl, provider.slug()));
        if (!provider.scopes().isEmpty()) {
            params.put("scope", provider.scopes());
        }
        params.put("state", state);
        if (nonce != null) {
            params.put("nonce", nonce);
        }
        params.put("code_challenge", codeChallenge(verifier));
        params.put("code_challenge_method", "S256");
        String url = endpoints.authorization() + (endpoints.authorization().contains("?") ? "&" : "?") + form(params);
        redirect(response, url);
    }

    // ------------------------------------------------------------- callback

    private void callback(Request request, Response response) throws Exception {
        String slug = request.pathParam("slug");
        PendingLogins.Taken taken = pending.take(request.query("state"));
        if (taken == null || !taken.login().slug().equals(slug)) {
            failRedirect(response, taken == null ? Intent.LOGIN : taken.login().intent(),
                    taken == null ? "/" : taken.login().next(), "login_expired");
            return;
        }
        Pending login = taken.login();
        try {
            String binding = request.cookie(BINDING_COOKIE);
            if (!Tokens.isWellFormed(binding) || !MessageDigest.isEqual(
                    Tokens.hash(binding).getBytes(StandardCharsets.US_ASCII),
                    taken.bindingHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new LoginException("login_browser_mismatch", "the callback came from another browser");
            }
            String error = request.query("error");
            if (error != null) {
                throw new LoginException("access_denied".equals(error) ? "provider_denied" : "provider_error",
                        "the provider answered with error " + safeLogText(error));
            }
            String code = request.query("code");
            if (code == null || code.isEmpty()) {
                throw new LoginException("provider_error", "the provider sent no code");
            }
            Providers.Provider provider;
            try {
                provider = providers.get(login.providerId());
            } catch (io.siggi.togethertunes.http.HttpError e) {
                throw new LoginException("provider_unavailable", "the provider was deleted");
            }
            if (!provider.enabled() || !provider.slug().equals(login.slug())
                    || !Objects.equals(provider.issuer(), login.issuer())) {
                throw new LoginException("provider_unavailable", "the provider was disabled or changed");
            }
            if (login.intent() == Intent.LINK && !(ActorResolver.actor(request) instanceof Actor.User user
                    && user.userId().equals(login.userId()))) {
                throw new LoginException("link_session_changed", "the user who started linking isn't logged in");
            }
            TokenResponse tokens = exchange(provider, endpoints(provider), code, login.codeVerifier());
            IdTokenValidator.IdToken idToken = null;
            JsonElement userinfo = null;
            if (provider.kind() == Providers.Kind.OIDC) {
                idToken = idTokens.validate(tokens.idToken(), provider.issuer(), provider.clientId(), login.nonce());
            } else {
                userinfo = userinfo(provider, tokens);
            }
            completion.complete(request, response, new Callback(provider, login, tokens, idToken, userinfo));
        } catch (LoginException e) {
            LOG.log(System.Logger.Level.INFO, "Login with provider " + slug + " failed (" + e.code() + "): "
                    + e.getMessage());
            fail(response, login, e.code());
        }
    }

    // ------------------------------------------------------------- provider side

    Endpoints endpoints(Providers.Provider provider) throws LoginException, InterruptedException {
        if (provider.kind() == Providers.Kind.OAUTH2) {
            ClaimMapping m = provider.claimMapping();
            if (m == null || m.authorizationEndpoint() == null || m.tokenEndpoint() == null) {
                throw new LoginException("provider_unavailable", "the provider has no endpoints configured");
            }
            return new Endpoints(m.authorizationEndpoint(), m.tokenEndpoint(), List.of(), true);
        }
        OidcDiscovery.Metadata md;
        try {
            md = discovery.metadata(provider.issuer());
        } catch (OidcDiscovery.DiscoveryException e) {
            throw new LoginException("provider_unreachable", e.getMessage());
        }
        if (!md.supportsS256()) {
            throw new LoginException("provider_unsupported", "the provider doesn't support PKCE S256");
        }
        return new Endpoints(md.authorizationEndpoint(), md.tokenEndpoint(), md.tokenEndpointAuthMethods(), true);
    }

    /** Exchanges {@code code} at the token endpoint. */
    TokenResponse exchange(Providers.Provider provider, Endpoints endpoints, String code, String verifier)
            throws LoginException, InterruptedException, SQLException {
        String secret = provider.hasClientSecret() ? providers.clientSecret(provider.id()) : null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", redirectUri(publicBaseUrl, provider.slug()));
        params.put("code_verifier", verifier);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoints.token()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (secret == null) {
            params.put("client_id", provider.clientId());
        } else if (provider.kind() == Providers.Kind.OIDC && (endpoints.authMethods().isEmpty()
                || endpoints.authMethods().contains("client_secret_basic"))) {
            // RFC 6749 §2.3.1: both parts form-encoded before Base64.
            String pair = URLEncoder.encode(provider.clientId(), StandardCharsets.UTF_8) + ":"
                    + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        } else if (provider.kind() == Providers.Kind.OAUTH2
                || endpoints.authMethods().contains("client_secret_post")) {
            params.put("client_id", provider.clientId());
            params.put("client_secret", secret);
        } else {
            throw new LoginException("provider_unsupported",
                    "the provider supports neither client_secret_basic nor client_secret_post");
        }
        builder.POST(HttpRequest.BodyPublishers.ofString(form(params)));
        URI uri = URI.create(endpoints.token());
        int status;
        String body;
        try {
            HttpResponse<InputStream> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            status = res.statusCode();
            try (InputStream in = res.body()) {
                body = readCapped(in);
            }
        } catch (IOException e) {
            throw new LoginException("token_exchange_failed", "token request to " + uri.getHost() + " failed: "
                    + e.getClass().getSimpleName());
        }
        JsonObject json = null;
        try {
            JsonElement el = body == null ? null : JsonParser.parseString(body);
            if (el != null && el.isJsonObject()) {
                json = el.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            // handled below
        }
        if (json != null && json.has("error")) {
            throw new LoginException("token_exchange_failed", "the token endpoint answered " + status
                    + " with error " + safeLogText(string(json, "error")));
        }
        if (status != 200) {
            throw new LoginException("token_exchange_failed", "the token endpoint answered " + status);
        }
        if (json == null) {
            throw new LoginException("token_exchange_failed", body == null
                    ? "the token response is too large" : "the token response is not a JSON object");
        }
        String accessToken = string(json, "access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new LoginException("token_exchange_failed", "the token response has no access_token");
        }
        String tokenType = string(json, "token_type");
        if (tokenType != null && !tokenType.equalsIgnoreCase("Bearer")) {
            throw new LoginException("token_exchange_failed", "unsupported token_type " + safeLogText(tokenType));
        }
        String idToken = string(json, "id_token");
        if (provider.kind() == Providers.Kind.OIDC && (idToken == null || idToken.isEmpty())) {
            throw new LoginException("token_exchange_failed", "the token response has no id_token");
        }
        Long expiresIn = null;
        JsonElement exp = json.get("expires_in");
        if (exp != null && exp.isJsonPrimitive() && exp.getAsJsonPrimitive().isNumber()) {
            expiresIn = exp.getAsLong();
        }
        return new TokenResponse(accessToken, tokenType == null ? "Bearer" : tokenType, idToken,
                string(json, "scope"), expiresIn);
    }

    /** Fetches a plain OAuth2 provider's userinfo URL with the access token. */
    JsonElement userinfo(Providers.Provider provider, TokenResponse tokens) throws LoginException, InterruptedException {
        ClaimMapping m = provider.claimMapping();
        if (m == null || m.userinfoUrl() == null || m.subject() == null) {
            throw new LoginException("provider_unavailable", "the provider has no userinfo URL configured");
        }
        URI uri = URI.create(m.userinfoUrl());
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + tokens.accessToken())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Client-Id", provider.clientId())
                .GET()
                .build();
        int status;
        String body;
        try {
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            status = res.statusCode();
            try (InputStream in = res.body()) {
                body = readCapped(in);
            }
        } catch (IOException e) {
            throw new LoginException("userinfo_failed", "userinfo request to " + uri.getHost() + " failed: "
                    + e.getClass().getSimpleName());
        }
        if (status != 200) {
            throw new LoginException("userinfo_failed", "the userinfo URL answered " + status);
        }
        if (body == null) {
            throw new LoginException("userinfo_failed", "the userinfo response is too large");
        }
        try {
            JsonElement json = JsonParser.parseString(body);
            if (json == null || !(json.isJsonObject() || json.isJsonArray())) {
                throw new LoginException("userinfo_failed", "the userinfo response is not a JSON object or array");
            }
            return json;
        } catch (JsonParseException e) {
            throw new LoginException("userinfo_failed", "the userinfo response is not JSON");
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_BODY_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String string(JsonObject json, String name) {
        JsonElement el = json.get(name);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    // ------------------------------------------------------------- helpers

    /** The PKCE S256 challenge of {@code verifier}: BASE64URL(SHA-256(ASCII(verifier))), RFC 7636 §4.2. */
    public static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is always available", e);
        }
    }

    /** {@code next} if it is a local path, else {@code /}. */
    static String safeNext(String next) {
        if (next == null || next.isEmpty() || next.length() > NEXT_MAX || !next.startsWith("/")
                || next.startsWith("//") || next.startsWith("/\\")) {
            return "/";
        }
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return "/";
            }
        }
        return next;
    }

    /** Provider-sent text for the log: at most 64 characters of {@code [A-Za-z0-9._-]}. */
    static String safeLogText(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < 64; i++) {
            char c = text.charAt(i);
            out.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-' ? c : '?');
        }
        return out.toString();
    }

    static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private String bindingCookie(String value, long maxAgeSeconds) {
        return BINDING_COOKIE + "=" + value + "; Path=" + COOKIE_PATH + "; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : "");
    }

    private static void redirect(Response response, String location) throws IOException {
        response.header("Location", location);
        response.header("Cache-Control", "no-store");
        response.header("Referrer-Policy", "no-referrer");
        response.empty(302);
    }

    /**
     * Ends a successful login: to {@code next}, or for a popup login to {@link #POPUP_DONE_PAGE}.
     */
    public static void succeedRedirect(Response response, Pending login) throws IOException {
        redirect(response, login.popup() ? POPUP_DONE_PAGE : login.next());
    }

    /** Ends a failed login of a pending login ({@link #failRedirect}, popup logins at {@link #POPUP_DONE_PAGE}). */
    public static void fail(Response response, Pending login, String code) throws IOException {
        failRedirect(response, login.intent(), login.next(), login.popup(), code);
    }

    /**
     * Ends a failed login: for a popup login at {@link #POPUP_DONE_PAGE}{@code ?error=<code>},
     * otherwise as {@link #failRedirect(Response, Intent, String, String)}.
     */
    public static void failRedirect(Response response, Intent intent, String next, boolean popup, String code)
            throws IOException {
        if (popup && intent == Intent.LOGIN) {
            redirect(response, POPUP_DONE_PAGE + "?" + form(Map.of("error", code)));
            return;
        }
        failRedirect(response, intent, next, code);
    }

    /** {@code GET /auth/popup-done}: the page a popup login ends at (it reports to its opener and closes). */
    private static void popupDone(Request request, Response response) throws IOException {
        response.header("Cache-Control", "no-store");
        response.header("Referrer-Policy", "no-referrer");
        response.send(200, "text/html; charset=utf-8", POPUP_DONE_HTML);
    }

    /**
     * Ends a failed login: redirects to the login page ({@code /login?error=<code>&next=…}), or
     * for a link to the account page ({@code /account/?error=<code>}).
     */
    public static void failRedirect(Response response, Intent intent, String next, String code) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", code);
        String page;
        if (intent == Intent.LINK) {
            page = ACCOUNT_PAGE;
        } else {
            page = LOGIN_PAGE;
            if (next != null && !next.equals("/")) {
                params.put("next", next);
            }
        }
        redirect(response, page + "?" + form(params));
    }

    // ------------------------------------------------------------- store

    /**
     * The started logins, in memory (a restart drops them: the user starts again). Keyed by the
     * SHA-256 of the state, so the map never holds a usable state; each is taken at most once;
     * expired ones are swept; bounded, dropping the oldest.
     */
    static final class PendingLogins {
        record Taken(Pending login, String bindingHash) {
        }

        private record Entry(Pending login, String bindingHash) {
        }

        private final Clock clock;
        private final int max;
        /** Insertion order = expiry order (fixed TTL). */
        private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

        PendingLogins(Clock clock, int max) {
            this.clock = clock;
            this.max = max;
        }

        synchronized void put(String state, String bindingHash, Pending login) {
            sweep(clock.millis());
            while (entries.size() >= max) {
                Iterator<String> it = entries.keySet().iterator();
                it.next();
                it.remove();
            }
            entries.put(Tokens.hash(state), new Entry(login, bindingHash));
        }

        /** The pending login for {@code state}, removed; {@code null} if unknown, used or expired. */
        synchronized Taken take(String state) {
            if (!Tokens.isWellFormed(state)) {
                return null;
            }
            long now = clock.millis();
            sweep(now);
            Entry e = entries.remove(Tokens.hash(state));
            if (e == null || e.login().expiresAt() <= now) {
                return null;
            }
            return new Taken(e.login(), e.bindingHash());
        }

        synchronized int size() {
            return entries.size();
        }

        private void sweep(long now) {
            Iterator<Entry> it = entries.values().iterator();
            while (it.hasNext()) {
                if (it.next().login().expiresAt() <= now) {
                    it.remove();
                } else {
                    break;
                }
            }
        }
    }
}
