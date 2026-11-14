package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.RegistrationMode;
import io.siggi.togethertunes.settings.Settings;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Local accounts over HTTP ({@code docs/api.md}, {@code docs/auth.md#accounts}).
 *
 * <ul>
 *   <li>{@code POST /api/auth/register} {@code {"username", "password", "displayName"?}} →
 *       {@code 201 {"user": {...}}} and a new {@code sid} cookie (the new user is logged in).
 *       Only when {@code registration.mode} is {@code open}, or {@code invite} with a usable
 *       {@code "invite"} code in the body ({@link Invites}; used up by this registration, in the
 *       transaction that creates the account): without one {@code 403 invite_required}, with an
 *       unknown, used, revoked or expired one {@code 403 invalid_invite} (in {@code open} mode a
 *       code is ignored). {@code closed} gives {@code 403 registration_closed}. While no account exists it is {@code 409 setup_required}
 *       (the first account comes from {@link SetupApi}). Validation and the clash check as in
 *       {@link Accounts#createLocal}; audited as {@value #AUDIT_REGISTER}.</li>
 *   <li>{@code POST /api/auth/login} {@code {"username", "password"}} → {@code 200 {"user": {...}}}
 *       and a new {@code sid} cookie; the request's previous {@code sid} session, if any, is
 *       deleted. Errors from {@link Accounts#login}, including its per-IP and per-username backoff
 *       ({@code 429 rate_limited} with {@code Retry-After}).</li>
 *   <li>{@code POST /api/auth/logout} → {@code 204}. Deletes the session the request is
 *       authenticated with (cookie or bearer) and clears the {@code sid} cookie. Idempotent.</li>
 *   <li>{@code POST /api/auth/token} → {@code 201 {"token", "tokenType": "Bearer", "sessionId",
 *       "expiresAt", "user"}}: a new {@code bearer} session for {@code Authorization: Bearer}.
 *       With a body {@code {"username", "password"}} it logs in like {@code /api/auth/login}
 *       (same errors and audit) but sets no cookie; that is how apps get a token. Without a body
 *       (or with {@code {}}) it is issued to the user the request is already authenticated as
 *       (e.g. from the web UI, or for an account that has no password); an anonymous or guest
 *       request then gets {@code 401 not_authenticated}. Audited as {@value #AUDIT_TOKEN}. The
 *       token is shown only in this response; it expires {@link Sessions#LIFETIME_MS} after its
 *       last use and is revoked through {@link SessionsApi} or by logging out with it.</li>
 *
 * <p>Register, login and token are also limited per client IP ({@link AuthRateLimits}):
 * {@code 429 rate_limited} with {@code Retry-After} before the body is read.
 *
 *   <li>{@code POST /api/me/password} {@code {"currentPassword", "newPassword"}} → {@code 204}:
 *       the logged-in user changes their own password ({@link Accounts#changePassword}; other
 *       sessions are logged out). This is how a temporary password set by an admin is replaced.</li>
 * </ul>
 *
 * <p>{@code user} is {@code {"id", "username", "displayName", "roles": [...], "mustChangePassword"}}
 * ({@code mustChangePassword}: the password is a temporary one set by an admin, and the UI asks
 * for a new one).
 */
public final class AuthApi {
    /** {@code audit_log} action of a registration. */
    public static final String AUDIT_REGISTER = "account.register";
    /** {@code audit_log} action of a logout. */
    public static final String AUDIT_LOGOUT = "auth.logout";
    /** {@code audit_log} action of a bearer token issued. */
    public static final String AUDIT_TOKEN = "auth.token";

    private final Accounts accounts;
    private final Sessions sessions;
    private final Settings settings;
    private final boolean secureCookies;
    private final AuthRateLimits limits;
    private volatile Invites invites;
    private InvitesApi invitesApi;

    record RegisterRequest(String username, String password, String displayName, String invite) {
    }

    record LoginRequest(String username, String password) {
    }

    record TokenRequest(String username, String password) {
    }

    record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    /**
     * @param secureCookies whether cookies get the {@code Secure} flag: true when
     *                      {@code http.publicBaseUrl} is {@code https}
     */
    public AuthApi(Accounts accounts, Sessions sessions, Settings settings, boolean secureCookies) {
        this(accounts, sessions, settings, secureCookies, AuthRateLimits.defaults(Clock.systemUTC()));
    }

    /** @param limits the per-IP limits of register, login and token */
    public AuthApi(Accounts accounts, Sessions sessions, Settings settings, boolean secureCookies,
                   AuthRateLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        this.accounts = Objects.requireNonNull(accounts);
        this.sessions = Objects.requireNonNull(sessions);
        this.settings = Objects.requireNonNull(settings);
        this.secureCookies = secureCookies;
    }

    /**
     * Turns on invites ({@code docs/auth.md#invites}): registration in {@code invite} mode takes an
     * invite code, and {@code /api/invites} is served ({@link InvitesApi}). Call before
     * {@link #register}. Without it, {@code invite} mode refuses every registration
     * ({@code 403 registration_invite_only}).
     */
    public void invites(Invites invites, InvitesApi api) {
        this.invites = Objects.requireNonNull(invites);
        this.invitesApi = Objects.requireNonNull(api);
    }

    public void register(Router router) {
        if (invitesApi != null) {
            invitesApi.register(router);
        }
        router.post("/api/auth/register", this::registerAccount, Hooks.rateLimit(limits.register()));
        router.post("/api/auth/login", this::login, Hooks.rateLimit(limits.login()));
        router.post("/api/auth/logout", this::logout);
        router.post("/api/auth/token", this::token, Hooks.rateLimit(limits.login()));
        router.post("/api/me/password", this::changePassword, Hooks.rateLimit(limits.login()));
    }

    private void changePassword(Request request, Response response) throws Exception {
        if (!(ActorResolver.actor(request) instanceof Actor.User user)) {
            throw new HttpError(401, "not_authenticated", "Log in first");
        }
        PasswordChangeRequest body = request.readJson(PasswordChangeRequest.class);
        accounts.changePassword(user.userId(), user.sessionId(), body.currentPassword(), body.newPassword(),
                request.clientIp());
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void registerAccount(Request request, Response response) throws Exception {
        RegisterRequest body = request.readJson(RegisterRequest.class);
        RegistrationMode mode = settings.get(Settings.REGISTRATION_MODE);
        Invites inviteStore = invites;
        switch (mode) {
            case OPEN -> {
            }
            case INVITE -> {
                if (inviteStore == null) {
                    throw new HttpError(403, "registration_invite_only", "Registration is by invitation only");
                }
            }
            case CLOSED -> throw new HttpError(403, "registration_closed", "Registration is closed");
        }
        if (!accounts.anyExists()) {
            throw new HttpError(409, "setup_required", "The server has not been set up yet");
        }
        Accounts.InCreation useInvite = null;
        if (mode == RegistrationMode.INVITE) {
            inviteStore.check(body.invite()); // cheap, before the password hash; consume decides
            String code = body.invite();
            useInvite = (c, newUserId, now) -> Map.of("invite", Invites.consume(c, code, newUserId, now));
        }
        Accounts.Account account = accounts.createLocal(body.username(), body.displayName(), body.password(), false,
                request.clientIp(), AUDIT_REGISTER, null, false, useInvite);
        startSession(request, response, account);
        response.json(201, Map.of("user", userJson(account)));
    }

    private void login(Request request, Response response) throws Exception {
        LoginRequest body = request.readJson(LoginRequest.class);
        Accounts.Account account = accounts.login(body.username(), body.password(), request.clientIp());
        sessions.deleteByToken(request.cookie(ActorResolver.SESSION_COOKIE));
        startSession(request, response, account);
        response.json(Map.of("user", userJson(account)));
    }

    private void logout(Request request, Response response) throws Exception {
        Actor actor = ActorResolver.actor(request);
        if (actor instanceof Actor.User user) {
            if (sessions.delete(user.sessionId())) {
                accounts.audit(user.userId(), request.clientIp(), AUDIT_LOGOUT, user.sessionId(),
                        Map.of("via", user.via().kind()));
            }
        }
        if (request.cookie(ActorResolver.SESSION_COOKIE) != null) {
            if (!(actor instanceof Actor.User user && user.via() == Actor.Via.BEARER)) {
                // A stale sid (e.g. of a disabled user) is removed too.
                sessions.deleteByToken(request.cookie(ActorResolver.SESSION_COOKIE));
            }
            response.setCookie(sessionCookie("", 0));
        }
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void token(Request request, Response response) throws Exception {
        boolean noBody = request.contentLength() == 0
                || (request.contentLength() < 0 && request.header("Transfer-Encoding") == null);
        TokenRequest body = noBody ? null : request.readJson(TokenRequest.class);
        Accounts.Account account;
        String via;
        if (body != null && (body.username() != null || body.password() != null)) {
            account = accounts.login(body.username(), body.password(), request.clientIp());
            via = "password";
        } else {
            Actor actor = ActorResolver.actor(request);
            account = actor instanceof Actor.User user ? accounts.findEnabled(user.userId()) : null;
            if (account == null) {
                throw new HttpError(401, "not_authenticated", "Log in first, or send a username and password");
            }
            via = "session";
        }
        Sessions.Created session = sessions.create(account.id(), Actor.Via.BEARER, request.header("User-Agent"),
                request.clientIp());
        accounts.audit(account.id(), request.clientIp(), AUDIT_TOKEN, session.sessionId(), Map.of("via", via));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", session.token());
        out.put("tokenType", "Bearer");
        out.put("sessionId", session.sessionId());
        out.put("expiresAt", session.expiresAt());
        out.put("user", userJson(account));
        response.header("Cache-Control", "no-store");
        response.json(201, out);
    }

    private void startSession(Request request, Response response, Accounts.Account account) throws Exception {
        startCookieSession(sessions, secureCookies, request, response, account);
    }

    /** Logs {@code account} in on this browser: a new {@code cookie} session and its {@code sid} cookie. */
    static void startCookieSession(Sessions sessions, boolean secureCookies, Request request, Response response,
                                   Accounts.Account account) throws Exception {
        Sessions.Created session = sessions.create(account.id(), Actor.Via.COOKIE, request.header("User-Agent"),
                request.clientIp());
        response.setCookie(Sessions.cookie(session.token(), secureCookies));
        response.header("Cache-Control", "no-store");
    }

    /** The {@code sid} cookie header value ({@code docs/auth.md#sessions}). */
    String sessionCookie(String value, long maxAgeSeconds) {
        return Sessions.cookie(value, maxAgeSeconds, secureCookies);
    }

    static Map<String, Object> userJson(Accounts.Account account) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", account.id());
        user.put("username", account.username());
        user.put("displayName", account.displayName());
        user.put("roles", account.roles());
        user.put("mustChangePassword", account.mustChangePassword());
        return user;
    }
}
