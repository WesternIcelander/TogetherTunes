package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.Hooks;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import io.siggi.togethertunes.http.Router;
import java.util.Map;
import java.util.Objects;

/**
 * The first-run setup flow, {@code /api/setup} ({@code docs/api.md}): until the first account
 * exists the web UI shows the setup page, which creates that account; it gets the
 * {@value BuiltinRoles#ADMIN} role.
 *
 * <ul>
 *   <li>{@code GET /api/setup} → {@code {"needed": true|false}}. Open to everyone.</li>
 *   <li>{@code POST /api/setup} with {@code {"username", "password", "displayName"?}} →
 *       {@code 201 {"user": {"id", "username", "displayName", "roles": [...]}}}. Only while no
 *       account exists; afterwards {@code 409 setup_complete}. Validation errors as in
 *       {@link Accounts#createLocal}. Audited as {@code account.setup}.</li>
 * </ul>
 *
 * <p>The new admin is not logged in by this call yet; the Sessions step makes it set the
 * {@code sid} cookie.
 */
public final class SetupApi {
    /** {@code audit_log} action of a successful setup. */
    public static final String AUDIT_ACTION = "account.setup";

    private final Accounts accounts;

    /** The POST body. */
    record SetupRequest(String username, String password, String displayName) {
    }

    public SetupApi(Accounts accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    public void register(Router router) {
        router.get("/api/setup", (request, response) -> status(response));
        router.post("/api/setup", this::setup, Hooks.rateLimit("setup"));
    }

    private void status(Response response) throws Exception {
        response.header("Cache-Control", "no-store");
        response.json(Map.of("needed", !accounts.anyExists()));
    }

    private void setup(Request request, Response response) throws Exception {
        SetupRequest body = request.readJson(SetupRequest.class);
        Accounts.Account account = accounts.createLocal(body.username(), body.displayName(), body.password(), true,
                request.clientIp(), AUDIT_ACTION);
        response.header("Cache-Control", "no-store");
        response.json(201, Map.of("user", AuthApi.userJson(account)));
    }
}
