package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.settings.RegistrationMode;
import io.siggi.togethertunes.settings.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Local accounts over HTTP ({@code docs/api.md}, {@code docs/auth.md#accounts}).
 *
 * <ul>
 *   <li>{@code POST /api/auth/register} {@code {"username", "password", "displayName"?}} →
 *       {@code 201 {"user": {...}}} and a new {@code sid} cookie (the new user is logged in).
 *       Only when {@code registration.mode} is {@code open}: {@code invite} gives
 *       {@code 403 registration_invite_only} (no invite mechanism exists yet), {@code closed}
 *       {@code 403 registration_closed}. While no account exists it is {@code 409 setup_required}
 *       (the first account comes from {@link SetupApi}). Validation and the clash check as in
 *       {@link Accounts#createLocal}; audited as {@value #AUDIT_REGISTER}.</li>
 *   <li>{@code POST /api/auth/login} {@code {"username", "password"}} → {@code 200 {"user": {...}}}
 *       and a new {@code sid} cookie; the request's previous {@code sid} session, if any, is
 *       deleted. Errors from {@link Accounts#login}.</li>
 *   <li>{@code POST /api/auth/logout} → {@code 204}. Deletes the session the request is
 *       authenticated with (cookie or bearer) and clears the {@code sid} cookie. Idempotent.</li>
 * </ul>
 *
 * <p>{@code user} is {@code {"id", "username", "displayName", "roles": [...]}}, as from setup.
 */
public final class AuthApi {
    /** {@code audit_log} action of a registration. */
    public static final String AUDIT_REGISTER = "account.register";
    /** {@code audit_log} action of a logout. */
    public static final String AUDIT_LOGOUT = "auth.logout";

    private final Accounts accounts;
    private final Sessions sessions;
    private final Settings settings;
    private final boolean secureCookies;

    record RegisterRequest(String username, String password, String displayName) {
    }

    record LoginRequest(String username, String password) {
    }

    /**
     * @param secureCookies whether cookies get the {@code Secure} flag: true when
     *                      {@code http.publicBaseUrl} is {@code https}
     */
    public AuthApi(Accounts accounts, Sessions sessions, Settings settings, boolean secureCookies) {
        this.accounts = Objects.requireNonNull(accounts);
        this.sessions = Objects.requireNonNull(sessions);
        this.settings = Objects.requireNonNull(settings);
        this.secureCookies = secureCookies;
    }

    public void register(Router router) {
        router.post("/api/auth/register", this::registerAccount, Hooks.rateLimit("register"));
        router.post("/api/auth/login", this::login, Hooks.rateLimit("login"));
        router.post("/api/auth/logout", this::logout);
    }

    private void registerAccount(Request request, Response response) throws Exception {
        RegisterRequest body = request.readJson(RegisterRequest.class);
        RegistrationMode mode = settings.get(Settings.REGISTRATION_MODE);
        switch (mode) {
            case OPEN -> {
            }
            case INVITE -> throw new HttpError(403, "registration_invite_only", "Registration is by invitation only");
            case CLOSED -> throw new HttpError(403, "registration_closed", "Registration is closed");
        }
        if (!accounts.anyExists()) {
            throw new HttpError(409, "setup_required", "The server has not been set up yet");
        }
        Accounts.Account account = accounts.createLocal(body.username(), body.displayName(), body.password(), false,
                request.clientIp(), AUDIT_REGISTER);
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
            response.addHeader("Set-Cookie", sessionCookie("", 0));
        }
        response.header("Cache-Control", "no-store");
        response.empty(204);
    }

    private void startSession(Request request, Response response, Accounts.Account account) throws Exception {
        Sessions.Created session = sessions.create(account.id(), Actor.Via.COOKIE, request.header("User-Agent"),
                request.clientIp());
        response.addHeader("Set-Cookie", sessionCookie(session.token(), Sessions.LIFETIME_MS / 1000));
        response.header("Cache-Control", "no-store");
    }

    /** The {@code sid} cookie header value ({@code docs/auth.md#sessions}). */
    String sessionCookie(String value, long maxAgeSeconds) {
        return ActorResolver.SESSION_COOKIE + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : "");
    }

    static Map<String, Object> userJson(Accounts.Account account) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", account.id());
        user.put("username", account.username());
        user.put("displayName", account.displayName());
        user.put("roles", account.roles());
        return user;
    }
}
