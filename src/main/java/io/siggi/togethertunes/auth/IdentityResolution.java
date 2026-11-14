package io.siggi.togethertunes.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
import java.math.BigDecimal;
import java.text.ParseException;
import java.util.Objects;

/**
 * What a provider login ends with ({@link OidcLogin.Completion}; {@code docs/auth.md#oidc} step 4):
 * turns the callback into a {@link Identities.ProviderIdentity} ({@link #identityOf}, the one place
 * that reads a provider's answer) and resolves it.
 *
 * <ul>
 *   <li>{@code intent=login}: logs the account in ({@link Identities#login}, which may sign one
 *       up): the request's old {@code sid} session is deleted, a new {@code cookie} session and its
 *       {@code sid} cookie are set, and the browser goes to {@code next} (a popup login: {@link OidcLogin#POPUP_DONE_PAGE}).</li>
 *   <li>{@code intent=link}: links the identity to the logged-in user ({@link Identities#link})
 *       and goes to {@code /account/?linked=<slug>}.</li>
 * </ul>
 * Refusals go back to the login or account page with {@code ?error=<code>}, like every other
 * login failure ({@link OidcLogin#failRedirect}).
 */
public final class IdentityResolution implements OidcLogin.Completion {
    private final Identities identities;
    private final Sessions sessions;
    private final boolean secureCookies;

    public IdentityResolution(Identities identities, Sessions sessions, boolean secureCookies) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.secureCookies = secureCookies;
    }

    @Override
    public void complete(Request request, Response response, OidcLogin.Callback callback) throws Exception {
        Identities.ProviderIdentity identity = identityOf(callback);
        OidcLogin.Pending pending = callback.pending();
        if (pending.intent() == OidcLogin.Intent.LINK) {
            identities.link(callback.provider(), identity, pending.userId(), request.clientIp());
            // Slugs are [a-z0-9-]: nothing to encode.
            redirect(response, OidcLogin.ACCOUNT_PAGE + "?linked=" + callback.provider().slug());
            return;
        }
        Identities.Resolved resolved = identities.login(callback.provider(), identity, request.clientIp());
        sessions.deleteByToken(request.cookie(ActorResolver.SESSION_COOKIE));
        AuthApi.startCookieSession(sessions, secureCookies, request, response, resolved.account());
        OidcLogin.succeedRedirect(response, pending);
    }

    /**
     * Who the provider says logged in.
     *
     * <ul>
     *   <li><b>OpenID Connect:</b> the validated ID token's {@code sub}, and the claims the
     *       provider's mapping names (defaults {@code preferred_username}, {@code name},
     *       {@code email}).</li>
     *   <li><b>Plain OAuth2:</b> the userinfo response, at the mapping's paths: {@code subject}
     *       (a string, or an integer, e.g. a numeric user id, taken in its plain decimal form),
     *       and the optional {@code username}, {@code displayName} and {@code email} (strings
     *       only; anything else counts as absent).</li>
     * </ul>
     * This is where a provider-specific step after the token exchange would go
     * ({@code docs/future-ideas.md}).
     *
     * @throws OidcLogin.LoginException {@code userinfo_failed} when an OAuth2 answer has no usable
     *                                  subject
     */
    public static Identities.ProviderIdentity identityOf(OidcLogin.Callback callback) throws OidcLogin.LoginException {
        Providers.Provider provider = callback.provider();
        ClaimMapping m = provider.claimMapping();
        if (provider.kind() == Providers.Kind.OIDC && callback.idToken() != null) {
            var claims = callback.idToken().claims();
            String username = stringClaim(claims, m != null && m.username() != null ? m.username()
                    : ClaimMapping.OIDC_USERNAME);
            String displayName = stringClaim(claims, m != null && m.displayName() != null ? m.displayName()
                    : ClaimMapping.OIDC_DISPLAY_NAME);
            String email = stringClaim(claims, m != null && m.email() != null ? m.email() : ClaimMapping.OIDC_EMAIL);
            return new Identities.ProviderIdentity(callback.idToken().subject(), username, displayName, email);
        }
        if (provider.kind() == Providers.Kind.OAUTH2 && callback.userinfo() != null && m != null) {
            JsonElement info = callback.userinfo();
            String subject = subject(ClaimMapping.resolve(info, m.subject()));
            if (subject == null) {
                throw new OidcLogin.LoginException("userinfo_failed",
                        "the userinfo response has no string or integer at the subject path");
            }
            return new Identities.ProviderIdentity(subject, string(ClaimMapping.resolve(info, m.username())),
                    string(ClaimMapping.resolve(info, m.displayName())), string(ClaimMapping.resolve(info, m.email())));
        }
        throw new OidcLogin.LoginException("login_unavailable", "the login gave no identity to resolve");
    }

    /** A subject: a non-blank string, or an integral number in plain decimal. */
    private static String subject(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isString()) {
            return p.getAsString().isBlank() ? null : p.getAsString();
        }
        if (p.isNumber()) {
            try {
                BigDecimal n = new BigDecimal(p.getAsString());
                return n.signum() == 0 ? "0" : n.stripTrailingZeros().scale() <= 0
                        ? n.toBigIntegerExact().toString() : null;
            } catch (NumberFormatException | ArithmeticException ex) {
                return null;
            }
        }
        return null;
    }

    private static String string(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = e.getAsString();
        return s.isBlank() ? null : s;
    }

    private static String stringClaim(com.nimbusds.jwt.JWTClaimsSet claims, String name) {
        try {
            String value = claims.getStringClaim(name);
            return value == null || value.isBlank() ? null : value;
        } catch (ParseException e) {
            return null; // not a string
        }
    }

    private static void redirect(Response response, String location) throws java.io.IOException {
        response.header("Location", location);
        response.header("Cache-Control", "no-store");
        response.header("Referrer-Policy", "no-referrer");
        response.empty(302);
    }
}
