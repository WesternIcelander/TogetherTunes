package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.settings.GuestIdentity;
import java.util.Objects;

/**
 * Who made a request ({@code docs/auth.md}): a {@link User}, a {@link Guest}, or
 * {@link Anonymous}. Resolved by {@link ActorResolver} and read with {@link ActorResolver#actor}.
 *
 * <p>Holds IDs only (never an IP address), so it is safe to log and to pass around; DTOs for
 * responses are built from it separately.
 */
public sealed interface Actor permits Actor.User, Actor.Guest, Actor.Anonymous {
    /** The anonymous actor. */
    Anonymous ANONYMOUS = new Anonymous();

    /**
     * Whether the request was authenticated by a cookie ({@code sid} or {@code gid}). Such
     * requests need the CSRF {@code Origin} check when they change state; bearer-token and
     * IP-mode requests don't carry ambient credentials a third-party page could ride on.
     */
    boolean cookieAuthenticated();

    /** How a user's session token reached us. */
    enum Via {
        /** The {@code sid} cookie (session kind {@code cookie}). */
        COOKIE,
        /** {@code Authorization: Bearer …} (session kind {@code bearer}). */
        BEARER;

        /** The {@code sessions.kind} value. */
        public String kind() {
            return this == COOKIE ? "cookie" : "bearer";
        }
    }

    /** A logged-in user, identified through one of their sessions. */
    record User(String userId, String sessionId, Via via) implements Actor {
        public User {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(via);
        }

        @Override
        public boolean cookieAuthenticated() {
            return via == Via.COOKIE;
        }
    }

    /**
     * A guest, identified by the {@code gid} cookie or (IP mode) by the client IP.
     *
     * @param banned the server-wide ban flag ({@code guests.banned}); enforced by the actions that
     *               need an identity, not by resolution
     */
    record Guest(String guestId, GuestIdentity identifiedBy, boolean banned) implements Actor {
        public Guest {
            Objects.requireNonNull(guestId);
            Objects.requireNonNull(identifiedBy);
        }

        @Override
        public boolean cookieAuthenticated() {
            return identifiedBy == GuestIdentity.COOKIE;
        }
    }

    /** Nobody we know: may listen and read public pages; becomes a guest when it needs an identity. */
    record Anonymous() implements Actor {
        @Override
        public boolean cookieAuthenticated() {
            return false;
        }
    }
}
