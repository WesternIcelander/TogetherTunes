package io.siggi.togethertunes.http;

/**
 * The standard per-route hooks. Rate limiting is still a placeholder that only calls the next
 * step; M1 "Rate limiting" fills it in without changing how routes use it. The router-wide hooks
 * live with what they need: actor resolution is {@code auth.ActorResolver#hook()} (the database),
 * the CSRF check {@link Csrf#hook()} ({@code http.publicBaseUrl}).
 */
public final class Hooks {
    private Hooks() {
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
}
