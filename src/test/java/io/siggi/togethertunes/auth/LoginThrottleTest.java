package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {
    private final TestClock clock = new TestClock(1_000_000);
    /** Per IP: 4 free, per username: 2 free; 1 s doubling to 8 s; forgotten after 60 s. */
    private final LoginThrottle throttle = new LoginThrottle(clock,
            new LoginThrottle.Policy(4, 1_000, 8_000, 60_000, 100),
            new LoginThrottle.Policy(2, 1_000, 8_000, 60_000, 100));

    private void fail(String ip, String username) throws LoginThrottle.Locked {
        throttle.begin(ip, username); // never succeeded: counts as a failure
    }

    private LoginThrottle.Refusal refused(String ip, String username) {
        return assertThrows(LoginThrottle.Locked.class, () -> throttle.begin(ip, username)).refusal();
    }

    @Test
    void usernameBackoffDoublesUpToTheCap() throws Exception {
        fail("192.0.2.1", "siggi");
        fail("192.0.2.2", "Siggi ");
        assertEquals(0, throttle.usernameLockedForMs("siggi"), "two free failures");
        long[] expected = {1_000, 2_000, 4_000, 8_000, 8_000};
        for (int i = 0; i < expected.length; i++) {
            fail("192.0.2." + (10 + i), "SIGGI"); // different IPs: only the username limit applies
            assertEquals(expected[i], throttle.usernameLockedForMs("siggi"));
            LoginThrottle.Refusal r = refused("192.0.2.99", "siggi");
            assertEquals(LoginThrottle.Scope.USERNAME, r.scope());
            assertEquals(expected[i], r.retryAfterMs());
            clock.advance(expected[i]);
        }
        assertEquals(0, throttle.usernameLockedForMs("other"));
    }

    @Test
    void ipBackoffAppliesAcrossUsernamesAndIpv6BySlash64() throws Exception {
        for (int i = 0; i < 5; i++) {
            fail("2001:db8:1:2::" + (i + 1), "user" + i);
        }
        assertEquals(1_000, throttle.ipLockedForMs("2001:db8:1:2::abcd"));
        LoginThrottle.Refusal r = refused("2001:db8:1:2::77", "fresh");
        assertEquals(LoginThrottle.Scope.IP, r.scope());
        assertEquals(1_000, r.retryAfterMs());
        throttle.begin("2001:db8:1:3::1", "fresh"); // another /64
    }

    @Test
    void onlyTheFirstRefusalOfEachLockIsFirst() throws Exception {
        for (int i = 0; i < 3; i++) {
            fail("192.0.2." + i, "siggi");
        }
        assertTrue(refused("192.0.2.50", "siggi").firstRefusal());
        clock.advance(500);
        assertFalse(refused("192.0.2.51", "siggi").firstRefusal());
        clock.advance(500);
        fail("192.0.2.52", "siggi"); // the next lock
        assertTrue(refused("192.0.2.53", "siggi").firstRefusal());
        assertFalse(refused("192.0.2.53", "siggi").firstRefusal());
    }

    @Test
    void refusedAttemptsAreNotCounted() throws Exception {
        for (int i = 0; i < 3; i++) {
            fail("192.0.2." + i, "siggi");
        }
        for (int i = 0; i < 50; i++) {
            refused("192.0.2.9", "siggi");
        }
        assertEquals(1_000, throttle.usernameLockedForMs("siggi"));
        assertEquals(0, throttle.ipLockedForMs("192.0.2.9"));
    }

    @Test
    void successClearsTheUsernameAndTakesBackOnlyItsOwnIpAttempt() throws Exception {
        for (int i = 0; i < 4; i++) {
            fail("192.0.2.1", "guess" + i);
        }
        fail("192.0.2.2", "siggi");
        fail("192.0.2.2", "siggi");
        clock.advance(10);
        throttle.begin("192.0.2.1", "siggi").succeeded();
        assertEquals(0, throttle.usernameLockedForMs("siggi"));
        assertEquals(0, throttle.ipLockedForMs("192.0.2.1"), "the success itself doesn't lock the IP");
        // The IP's four earlier failures still count: one more and it is locked.
        fail("192.0.2.1", "guess4");
        assertEquals(1_000, throttle.ipLockedForMs("192.0.2.1"));
        // And the username starts from zero again.
        fail("192.0.2.3", "siggi");
        fail("192.0.2.3", "siggi");
        assertEquals(0, throttle.usernameLockedForMs("siggi"));
    }

    @Test
    void succeededIsIdempotent() throws Exception {
        for (int i = 0; i < 4; i++) {
            fail("192.0.2.1", "guess" + i);
        }
        LoginThrottle.Attempt attempt = throttle.begin("192.0.2.1", "siggi");
        attempt.succeeded();
        attempt.succeeded();
        fail("192.0.2.1", "guess4");
        assertEquals(1_000, throttle.ipLockedForMs("192.0.2.1"));
    }

    @Test
    void attemptsCountBeforeTheyFinish() throws Exception {
        // Parallel attempts: each counts while its password check runs.
        for (int i = 0; i < 3; i++) {
            throttle.begin("192.0.2." + i, "siggi");
        }
        refused("192.0.2.9", "siggi");
    }

    @Test
    void countsAreForgottenAfterAQuietPeriod() throws Exception {
        for (int i = 0; i < 6; i++) {
            fail("192.0.2.1", "siggi");
            clock.advance(10_000);
        }
        clock.advance(60_000);
        assertEquals(0, throttle.usernameLockedForMs("siggi"));
        fail("192.0.2.1", "siggi");
        fail("192.0.2.1", "siggi");
        assertEquals(0, throttle.usernameLockedForMs("siggi"));
        assertEquals(0, throttle.ipLockedForMs("192.0.2.1"));
    }

    @Test
    void blankUsernameHasOnlyTheIpLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            fail("192.0.2.1", i % 2 == 0 ? null : "  ");
        }
        assertEquals(1_000, throttle.ipLockedForMs("192.0.2.1"));
        assertEquals(0, throttle.usernameLockedForMs(""));
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        assertEquals(5, LoginThrottle.USERNAME_POLICY.freeFailures());
        assertEquals(20, LoginThrottle.IP_POLICY.freeFailures());
        assertEquals(1_000, LoginThrottle.USERNAME_POLICY.delayMs(6));
        assertEquals(2_000, LoginThrottle.USERNAME_POLICY.delayMs(7));
        assertEquals(15 * 60_000, LoginThrottle.USERNAME_POLICY.delayMs(100));
        assertEquals(15 * 60_000, LoginThrottle.USERNAME_POLICY.delayMs(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle.Policy(1, 1_000, 500, 60_000, 1));
    }
}
