package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.Request;
import io.siggi.togethertunes.http.Response;
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
 *       {@code sid} cookie are set, and the browser goes to {@code next}.</li>
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
        redirect(response, pending.next());
    }

    /**
     * Who the provider says logged in. OpenID Connect: the validated ID token's {@code sub}, and
     * the claims the provider's mapping names (defaults {@code preferred_username}, {@code name},
     * {@code email}). This is where a provider-specific step after the token exchange would go
     * ({@code docs/future-ideas.md}).
     *
     * @throws OidcLogin.LoginException {@code login_unavailable} for providers whose answer can't
     *                                  be read yet (plain OAuth2, until its userinfo step exists)
     */
    public static Identities.ProviderIdentity identityOf(OidcLogin.Callback callback) throws OidcLogin.LoginException {
        Providers.Provider provider = callback.provider();
        if (provider.kind() == Providers.Kind.OIDC && callback.idToken() != null) {
            ClaimMapping m = provider.claimMapping();
            var claims = callback.idToken().claims();
            String username = stringClaim(claims, m != null && m.username() != null ? m.username()
                    : ClaimMapping.OIDC_USERNAME);
            String displayName = stringClaim(claims, m != null && m.displayName() != null ? m.displayName()
                    : ClaimMapping.OIDC_DISPLAY_NAME);
            String email = stringClaim(claims, m != null && m.email() != null ? m.email() : ClaimMapping.OIDC_EMAIL);
            return new Identities.ProviderIdentity(callback.idToken().subject(), username, displayName, email);
        }
        throw new OidcLogin.LoginException("login_unavailable", "logging in with this kind of provider isn't built yet");
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
