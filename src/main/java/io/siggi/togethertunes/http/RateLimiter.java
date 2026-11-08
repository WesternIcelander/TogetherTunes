package io.siggi.togethertunes.http;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A keyed token-bucket rate limiter, in memory. Each key (normally a client IP, see
 * {@link #acquireForIp}) has a bucket of {@code burst} tokens that refills one token every
 * {@code refillMs}; a request takes one token, or is refused with the time until the next token.
 * So a client can make {@code burst} requests at once and then one per {@code refillMs}.
 *
 * <p>Used per route through {@link Hooks#rateLimit(RateLimiter)}; reusable for any per-IP limit
 * (e.g. {@code /live/} later). Memory is bounded: at most {@code maxKeys} buckets, the least
 * recently used dropped first (a dropped bucket starts full again, which only ever lets a request
 * through that would otherwise have waited). Thread-safe.
 */
public final class RateLimiter {
    /** Default {@code maxKeys}. */
    public static final int DEFAULT_MAX_KEYS = 65_536;

    private final String name;
    private final int burst;
    private final long refillMs;
    private final Clock clock;
    private final Map<String, Bucket> buckets;

    private static final class Bucket {
        /** Tokens available at {@link #at}, scaled by {@code refillMs} (so whole-ms precision). */
        long scaledTokens;
        long at;
    }

    /**
     * @param name     for messages and logs, e.g. {@code "login"}
     * @param burst    bucket size: requests allowed at once (at least 1)
     * @param refillMs one token comes back every this many ms (at least 1)
     */
    public RateLimiter(String name, int burst, long refillMs, Clock clock) {
        this(name, burst, refillMs, clock, DEFAULT_MAX_KEYS);
    }

    public RateLimiter(String name, int burst, long refillMs, Clock clock, int maxKeys) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
        if (burst < 1 || refillMs < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("burst, refillMs and maxKeys must be positive");
        }
        this.name = name;
        this.burst = burst;
        this.refillMs = refillMs;
        this.clock = Objects.requireNonNull(clock);
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    public String name() {
        return name;
    }

    /**
     * Takes a token from {@code key}'s bucket.
     *
     * @return 0 if the request may go ahead, otherwise how many ms until it may (no token taken)
     */
    public long tryAcquire(String key) {
        Objects.requireNonNull(key);
        long now = clock.millis();
        long full = burst * refillMs;
        synchronized (buckets) {
            Bucket b = buckets.get(key);
            if (b == null) {
                b = new Bucket();
                b.scaledTokens = full;
                b.at = now;
                buckets.put(key, b);
            } else if (now > b.at) {
                b.scaledTokens = Math.min(full, b.scaledTokens + (now - b.at));
                b.at = now;
            }
            if (b.scaledTokens >= refillMs) {
                b.scaledTokens -= refillMs;
                return 0;
            }
            return refillMs - b.scaledTokens;
        }
    }

    /**
     * {@link #tryAcquire} keyed by the client's {@linkplain IpKeys#ipKey IP key} (IPv6 by /64).
     * An address that isn't an IP literal is used as given.
     */
    public long acquireForIp(String clientIp) {
        String ip = clientIp == null ? "" : clientIp;
        String key = IpKeys.ipKey(ip);
        return tryAcquire(key == null ? ip : key);
    }

    /**
     * Like {@link #acquireForIp}, but throws {@code 429 rate_limited} with {@code Retry-After}
     * when refused.
     */
    public void checkIp(String clientIp) {
        long wait = acquireForIp(clientIp);
        if (wait > 0) {
            throw HttpError.tooManyRequests("Too many requests; try again later", wait);
        }
    }

    /** Number of buckets held (for tests). */
    int size() {
        synchronized (buckets) {
            return buckets.size();
        }
    }
}
