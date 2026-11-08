package io.siggi.togethertunes.http;

/**
 * The standard hooks. In M0 these are placeholders that only call the next step; M1 fills them in
 * (actor resolution, the CSRF {@code Origin} check and rate limiting from {@code docs/auth.md}),
 * without changing how routes use them.
 */
public final class Hooks {
    private Hooks() {
    }

    /**
     * Router-wide: resolves the actor (user from {@code sid} cookie or bearer token, guest, or
     * anonymous) and stores it on the request. Placeholder until M1 "Actor resolution".
     */
    public static Hook authenticate() {
        return Placeholder.AUTHENTICATE;
    }

    /**
     * Router-wide: for state-changing, cookie-authenticated requests, requires {@code Origin} to
     * match {@code http.publicBaseUrl}. Placeholder until M1 "CSRF".
     */
    public static Hook csrf() {
        return Placeholder.CSRF;
    }

    /**
     * Per-route: rate limits the route under the named bucket (e.g. {@code "login"}), rejecting
     * with {@link HttpError#tooManyRequests}. Placeholder until M1 "Rate limiting".
     */
    public static Hook rateLimit(String bucket) {
        if (bucket == null || bucket.isEmpty()) {
            throw new IllegalArgumentException("bucket");
        }
        return (request, response, next) -> next.handle(request, response);
    }

    private enum Placeholder implements Hook {
        AUTHENTICATE, CSRF;

        @Override
        public void handle(Request request, Response response, Handler next) throws Exception {
            next.handle(request, response);
        }
    }
}
