package io.siggi.togethertunes.http;

import java.util.Objects;

/**
 * The standard per-route hooks. The router-wide hooks live with what they need: actor resolution
 * is {@code auth.ActorResolver#hook()} (the database), the CSRF check {@link Csrf#hook()}
 * ({@code http.publicBaseUrl}).
 */
public final class Hooks {
    private Hooks() {
    }

    /**
     * Per-route: limits each client IP ({@link RateLimiter#acquireForIp}, IPv6 by /64) with
     * {@code limiter}, rejecting with {@code 429 rate_limited} and {@code Retry-After} before the
     * handler runs (and before the body is read). Several routes may share one limiter.
     */
    public static Hook rateLimit(RateLimiter limiter) {
        Objects.requireNonNull(limiter, "limiter");
        return (request, response, next) -> {
            limiter.checkIp(request.clientIp());
            next.handle(request, response);
        };
    }
}
