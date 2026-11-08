package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdentityTicketsTest {
    private final TestClock clock = new TestClock(1_700_000_000_000L);

    @Test
    void ticketIsATokenRedeemableOnce() {
        IdentityTickets tickets = new IdentityTickets(clock);
        String ticket = tickets.issue("guest-1");
        assertTrue(Tokens.isWellFormed(ticket), ticket);
        IdentityTickets.Ticket redeemed = tickets.redeem(ticket);
        assertEquals("guest-1", redeemed.guestId());
        assertEquals(clock.millis() + IdentityTickets.TTL_MS, redeemed.expiresAt());
        assertNull(tickets.redeem(ticket));
        assertEquals(0, tickets.size());
    }

    @Test
    void ticketsAreDistinctAndIndependent() {
        IdentityTickets tickets = new IdentityTickets(clock);
        String a = tickets.issue("guest-1");
        String b = tickets.issue("guest-1");
        String c = tickets.issue("guest-2");
        assertNotEquals(a, b);
        assertEquals("guest-2", tickets.redeem(c).guestId());
        assertEquals("guest-1", tickets.redeem(b).guestId());
        assertEquals("guest-1", tickets.redeem(a).guestId());
    }

    @Test
    void validForSixtySeconds() {
        IdentityTickets tickets = new IdentityTickets(clock);
        String early = tickets.issue("guest-1");
        String late = tickets.issue("guest-2");
        clock.advance(IdentityTickets.TTL_MS - 1);
        assertEquals("guest-1", tickets.redeem(early).guestId());
        clock.advance(1);
        assertNull(tickets.redeem(late));
    }

    @Test
    void expiredTicketsAreSwept() {
        IdentityTickets tickets = new IdentityTickets(clock);
        for (int i = 0; i < 10; i++) {
            tickets.issue("guest-" + i);
        }
        clock.advance(IdentityTickets.TTL_MS);
        String fresh = tickets.issue("guest-x");
        assertEquals(1, tickets.size());
        assertEquals("guest-x", tickets.redeem(fresh).guestId());
    }

    @Test
    void malformedOrUnknownTicketsAreRefused() {
        IdentityTickets tickets = new IdentityTickets(clock);
        tickets.issue("guest-1");
        assertNull(tickets.redeem(null));
        assertNull(tickets.redeem(""));
        assertNull(tickets.redeem("not a ticket"));
        assertNull(tickets.redeem(Tokens.newToken()));
        assertEquals(1, tickets.size());
    }

    @Test
    void storeIsBoundedDroppingTheOldest() {
        IdentityTickets tickets = new IdentityTickets(clock, 3);
        String first = tickets.issue("g1");
        String second = tickets.issue("g2");
        tickets.issue("g3");
        tickets.issue("g4");
        assertEquals(3, tickets.size());
        assertNull(tickets.redeem(first));
        assertEquals("g2", tickets.redeem(second).guestId());
    }

    @Test
    void concurrentRedeemsSucceedOnce() throws Exception {
        IdentityTickets tickets = new IdentityTickets(clock);
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            issued.add(tickets.issue("guest"));
        }
        AtomicInteger successes = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (String ticket : issued) {
                    if (tickets.redeem(ticket) != null) {
                        successes.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(50, successes.get());
    }
}
