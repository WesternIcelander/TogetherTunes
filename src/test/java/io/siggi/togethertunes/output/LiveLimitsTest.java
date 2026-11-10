package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.http.HttpError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link LiveLimits}: per-token connection cap (default 64) and the per-IP rate limit. */
class LiveLimitsTest {
    @Test
    void connectionsPerTokenAreCappedAt64ByDefault() {
        LiveLimits limits = LiveLimits.defaults(new TestClock(0));
        List<LiveLimits.Slot> slots = new ArrayList<>();
        for (int i = 0; i < LiveLimits.MAX_CONNECTIONS_PER_TOKEN; i++) {
            slots.add(limits.open("tokenA"));
        }
        assertEquals(64, limits.connections("tokenA"));
        HttpError e = assertThrows(HttpError.class, () -> limits.open("tokenA"));
        assertEquals(429, e.status());
        assertEquals("too_many_connections", e.code());
        assertDoesNotThrow(() -> limits.open("tokenB").close(), "other tokens are counted on their own");
        slots.get(0).close();
        slots.get(0).close(); // idempotent
        assertEquals(63, limits.connections("tokenA"));
        slots.set(0, limits.open("tokenA"));
        slots.forEach(LiveLimits.Slot::close);
        assertEquals(0, limits.connections("tokenA"));
        assertEquals(0, limits.connections("tokenB"));
    }

    @Test
    void ipRateLimitAllowsABurstThenTenPerSecond() {
        TestClock clock = new TestClock(1_000_000);
        LiveLimits limits = LiveLimits.defaults(clock);
        for (int i = 0; i < LiveLimits.IP_BURST; i++) {
            limits.checkIp("203.0.113.5");
        }
        HttpError e = assertThrows(HttpError.class, () -> limits.checkIp("203.0.113.5"));
        assertEquals(429, e.status());
        assertEquals("rate_limited", e.code());
        assertDoesNotThrow(() -> limits.checkIp("203.0.113.6"), "another address has its own bucket");
        // IPv6 by /64
        for (int i = 0; i < LiveLimits.IP_BURST; i++) {
            limits.checkIp("2001:db8:1:2::" + Integer.toHexString(i + 1));
        }
        assertThrows(HttpError.class, () -> limits.checkIp("2001:db8:1:2::ffff"));
        clock.advance(LiveLimits.IP_REFILL_MS);
        assertDoesNotThrow(() -> limits.checkIp("203.0.113.5"));
        assertThrows(HttpError.class, () -> limits.checkIp("203.0.113.5"));
    }

    @Test
    void noIpLimitOnlyCapsTokens() {
        LiveLimits limits = LiveLimits.noIpLimit();
        for (int i = 0; i < 1000; i++) {
            limits.checkIp("203.0.113.5");
        }
    }
}
