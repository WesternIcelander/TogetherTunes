package io.siggi.togethertunes.output;

import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.RateLimiter;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits on the session-less {@code /live/...} endpoints ({@code docs/auth.md#stream-urls}):
 *
 * <ul>
 *   <li><b>Per IP:</b> a token bucket ({@link RateLimiter}, IPv6 by /64) over every {@code /live}
 *       request, checked before the token is looked up, so guessing tokens is slow and cheap for
 *       the server. Defaults: a burst of {@value #IP_BURST}, then one request per
 *       {@value #IP_REFILL_MS} ms (10/s): an HLS player makes about one request per second, so
 *       several viewers behind one address fit. Refused: {@code 429 rate_limited} with
 *       {@code Retry-After}.</li>
 *   <li><b>Per token:</b> at most {@value #MAX_CONNECTIONS_PER_TOKEN} concurrent connections
 *       ({@code .ts} streams for as long as they last, HLS requests while they are served).
 *       Refused: {@code 429 too_many_connections} with {@code Retry-After}.</li>
 * </ul>
 *
 * <p>Tokens are held in memory as map keys only while connections use them; they are never logged.
 */
public final class LiveLimits {
    public static final int MAX_CONNECTIONS_PER_TOKEN = 64;
    public static final int IP_BURST = 60;
    public static final long IP_REFILL_MS = 100;
    /** {@code Retry-After} for a token at its connection cap. */
    static final long CAP_RETRY_AFTER_SECONDS = 10;

    private final RateLimiter perIp; // null: no per-IP limit
    private final int maxPerToken;
    private final ConcurrentHashMap<String, AtomicInteger> open = new ConcurrentHashMap<>();

    /** One connection counted against a token; {@link #close} releases it (idempotent). */
    public final class Slot implements AutoCloseable {
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Slot(String token) {
            this.token = token;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                open.computeIfPresent(token, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
            }
        }
    }

    public LiveLimits(RateLimiter perIp, int maxPerToken) {
        if (maxPerToken < 1) {
            throw new IllegalArgumentException("maxPerToken");
        }
        this.perIp = perIp;
        this.maxPerToken = maxPerToken;
    }

    /** The documented defaults. */
    public static LiveLimits defaults(Clock clock) {
        return new LiveLimits(new RateLimiter("live", IP_BURST, IP_REFILL_MS, clock), MAX_CONNECTIONS_PER_TOKEN);
    }

    /** No per-IP limit and the default per-token cap (tests of other things). */
    public static LiveLimits noIpLimit() {
        return new LiveLimits(null, MAX_CONNECTIONS_PER_TOKEN);
    }

    /** Counts a request from this client IP; throws {@code 429 rate_limited} once it is over the limit. */
    public void checkIp(String clientIp) {
        if (perIp != null) {
            perIp.checkIp(clientIp);
        }
    }

    /**
     * Counts a connection against {@code token}.
     *
     * @throws HttpError {@code 429 too_many_connections} if the token already has the maximum
     */
    public Slot open(String token) {
        Objects.requireNonNull(token, "token");
        boolean[] ok = {false};
        open.compute(token, (k, n) -> {
            if (n == null) {
                n = new AtomicInteger();
            }
            if (n.get() < maxPerToken) {
                n.incrementAndGet();
                ok[0] = true;
            }
            return n.get() == 0 ? null : n;
        });
        if (!ok[0]) {
            throw new HttpError(429, "too_many_connections", "Too many connections to this stream",
                    CAP_RETRY_AFTER_SECONDS);
        }
        return new Slot(token);
    }

    /** Connections counted against {@code token} now. */
    public int connections(String token) {
        AtomicInteger n = open.get(token);
        return n == null ? 0 : n.get();
    }
}
