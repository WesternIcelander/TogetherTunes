package io.siggi.togethertunes.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.siggi.togethertunes.TestClock;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private final TestClock clock = new TestClock(1_000_000);

    @Test
    void burstThenOnePerRefillInterval() {
        RateLimiter limiter = new RateLimiter("t", 3, 1_000, clock);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(1_000, limiter.tryAcquire("a"));
        clock.advance(400);
        assertEquals(600, limiter.tryAcquire("a"), "a refused request takes no token");
        clock.advance(600);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(1_000, limiter.tryAcquire("a"));
        clock.advance(10_000);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.tryAcquire("a"), "refills to the burst size, not beyond");
        }
        assertEquals(1_000, limiter.tryAcquire("a"));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter("t", 1, 60_000, clock);
        assertEquals(0, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("b"));
        assertEquals(60_000, limiter.tryAcquire("a"));
    }

    @Test
    void ipv6SharesALimitPerSlash64AndMappedIpv4CountsAsIpv4() {
        RateLimiter limiter = new RateLimiter("t", 1, 60_000, clock);
        assertEquals(0, limiter.acquireForIp("2001:db8:1:2::1"));
        assertEquals(60_000, limiter.acquireForIp("2001:db8:1:2:ffff::9"));
        assertEquals(0, limiter.acquireForIp("2001:db8:1:3::1"));
        assertEquals(0, limiter.acquireForIp("203.0.113.7"));
        assertEquals(60_000, limiter.acquireForIp("::ffff:203.0.113.7"));
        assertEquals(0, limiter.acquireForIp(null));
        assertEquals(60_000, limiter.acquireForIp(null));
    }

    @Test
    void checkIpThrows429WithRetryAfterInSeconds() {
        RateLimiter limiter = new RateLimiter("t", 1, 2_500, clock);
        limiter.checkIp("192.0.2.1");
        HttpError e = assertThrows(HttpError.class, () -> limiter.checkIp("192.0.2.1"));
        assertEquals(429, e.status());
        assertEquals("rate_limited", e.code());
        assertEquals(3, e.retryAfterSeconds());
    }

    @Test
    void memoryIsBoundedLeastRecentlyUsedFirst() {
        RateLimiter limiter = new RateLimiter("t", 1, 60_000, clock, 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("a"); // a is now the most recently used
        limiter.tryAcquire("c"); // drops b
        assertEquals(2, limiter.size());
        assertEquals(60_000, limiter.tryAcquire("a"));
        assertEquals(0, limiter.tryAcquire("b"), "a dropped bucket starts full");
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("", 1, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("t", 0, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("t", 1, 0, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("t", 1, 1, clock, 0));
    }
}
