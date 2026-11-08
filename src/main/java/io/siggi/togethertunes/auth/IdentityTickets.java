package io.siggi.togethertunes.auth;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket identity tickets ({@code docs/api.md#websocket}).
 *
 * <p>A socket's identity is fixed at its upgrade, so an anonymous socket can't see a {@code gid}
 * cookie set afterwards. {@code POST /api/me/guest} ({@link MeApi}) therefore returns an
 * {@code identityTicket}: the client sends {@code {type:"identity", ticket}} on its open socket,
 * and the socket takes on that guest ({@link #redeem}, wired up with the WebSocket in M3).
 *
 * <p>A ticket is a fresh 256-bit token ({@link Tokens}), valid for {@link #TTL_MS} = 60 s and
 * redeemable <b>once</b>. Tickets live in memory only (a restart drops the open sockets too), keyed
 * by the token's SHA-256, so the map never holds a redeemable token. A ticket names the guest only,
 * never an IP address. The store is bounded ({@link #MAX_TICKETS}); when full, the oldest ticket
 * goes. Tickets are capabilities: never log them in full ({@link Tokens#redact}).
 */
public final class IdentityTickets {
    /** How long a ticket can be redeemed: 60 s. */
    public static final long TTL_MS = 60_000;
    /** Most outstanding tickets kept; issuing more drops the oldest. */
    public static final int MAX_TICKETS = 65_536;

    private final Clock clock;
    private final int maxTickets;
    /** By SHA-256 of the ticket; insertion order = issue order = expiry order. */
    private final LinkedHashMap<String, Entry> tickets = new LinkedHashMap<>();

    /** The guest a redeemed ticket names. */
    public record Ticket(String guestId, long expiresAt) {
        public Ticket {
            Objects.requireNonNull(guestId);
        }
    }

    private record Entry(String guestId, long expiresAt) {
    }

    public IdentityTickets() {
        this(Clock.systemUTC());
    }

    public IdentityTickets(Clock clock) {
        this(clock, MAX_TICKETS);
    }

    IdentityTickets(Clock clock, int maxTickets) {
        if (maxTickets < 1) {
            throw new IllegalArgumentException("maxTickets must be positive");
        }
        this.clock = Objects.requireNonNull(clock);
        this.maxTickets = maxTickets;
    }

    /**
     * A new ticket for {@code guestId}, valid for {@link #TTL_MS}. Each call issues a new one;
     * earlier tickets for the same guest stay valid until used or expired.
     */
    public String issue(String guestId) {
        Objects.requireNonNull(guestId);
        String token = Tokens.newToken();
        long now = clock.millis();
        synchronized (tickets) {
            sweep(now);
            while (tickets.size() >= maxTickets) {
                Iterator<String> oldest = tickets.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            tickets.put(Tokens.hash(token), new Entry(guestId, now + TTL_MS));
        }
        return token;
    }

    /**
     * Redeems a ticket: the guest it names, or {@code null} if it is malformed, unknown, expired or
     * already redeemed. A ticket is removed by its first redemption attempt, so it works at most
     * once. The caller still checks the guest itself (it may have been banned since).
     */
    public Ticket redeem(String ticket) {
        if (!Tokens.isWellFormed(ticket)) {
            return null;
        }
        long now = clock.millis();
        Entry entry;
        synchronized (tickets) {
            entry = tickets.remove(Tokens.hash(ticket));
            sweep(now);
        }
        if (entry == null || now >= entry.expiresAt()) {
            return null;
        }
        return new Ticket(entry.guestId(), entry.expiresAt());
    }

    /** Outstanding tickets (expired ones may still be counted until the next issue or redeem). */
    public int size() {
        synchronized (tickets) {
            return tickets.size();
        }
    }

    /** Drops expired tickets from the front; the map is in expiry order. Holds the lock. */
    private void sweep(long now) {
        Iterator<Map.Entry<String, Entry>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (now < it.next().getValue().expiresAt()) {
                return;
            }
            it.remove();
        }
    }
}
