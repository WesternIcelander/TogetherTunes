package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.http.RateLimiter;
import java.time.Clock;
import java.util.Objects;

/**
 * The per-IP request limits of the account routes ({@link io.siggi.togethertunes.http.Hooks#rateLimit}),
 * on top of the per-IP and per-username login backoff in {@link LoginThrottle}. They bound how much
 * Argon2 work one client can cause.
 *
 * @param setup    {@code POST /api/setup}
 * @param register {@code POST /api/auth/register}
 * @param login    {@code POST /api/auth/login} and {@code POST /api/auth/token} (one shared bucket)
 */
public record AuthRateLimits(RateLimiter setup, RateLimiter register, RateLimiter login) {
    public AuthRateLimits {
        Objects.requireNonNull(setup);
        Objects.requireNonNull(register);
        Objects.requireNonNull(login);
    }

    /**
     * The defaults: setup 5 at once then one per 12 s; register 5 at once then one per minute;
     * login and token 10 at once then one per 6 s.
     */
    public static AuthRateLimits defaults(Clock clock) {
        return new AuthRateLimits(
                new RateLimiter("setup", 5, 12_000, clock),
                new RateLimiter("register", 5, 60_000, clock),
                new RateLimiter("login", 10, 6_000, clock));
    }
}
