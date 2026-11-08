package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.auth.ActorResolver.Credentials;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link ActorResolver} against a migrated database, in both dialects. */
abstract class ActorResolverContract {
    static final long NOW = 1_800_000_000_000L;
    static final long DAY = 86_400_000L;

    protected Database db;
    protected Settings settings;
    protected ActorResolver resolver;
    private AuthFixtures fixtures;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        settings = new Settings(db);
        fixtures = new AuthFixtures(db, NOW);
        resolver = new ActorResolver(db, settings, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    String user(String username, boolean disabled) throws SQLException {
        return fixtures.user(username, disabled);
    }

    String session(String userId, String kind, long expiresAt, String[] idOut) throws SQLException {
        return fixtures.session(userId, kind, expiresAt, idOut);
    }

    String session(String userId, String kind) throws SQLException {
        return fixtures.session(userId, kind, NOW + 30 * DAY, null);
    }

    String guest(String kind, String keyHash, boolean banned) throws SQLException {
        return fixtures.guest(kind, keyHash, banned);
    }

    Actor resolve(String bearer, String sid, String gid, String ip) throws SQLException {
        return resolver.resolve(new Credentials(bearer, sid, gid, ip));
    }

    static void assertInvalidToken(HttpError e) {
        assertEquals(401, e.status());
        assertEquals("invalid_token", e.code());
    }

    // ------------------------------------------------------------------ users

    @Test
    void nothingIsAnonymous() throws SQLException {
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, null));
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, "203.0.113.7"));
        assertFalse(Actor.ANONYMOUS.cookieAuthenticated());
    }

    @Test
    void sidCookieResolvesUser() throws SQLException {
        String alice = user("alice", false);
        String[] sessionId = new String[1];
        String sid = session(alice, "cookie", NOW + DAY, sessionId);
        Actor actor = resolve(null, sid, null, "203.0.113.7");
        assertEquals(new Actor.User(alice, sessionId[0], Actor.Via.COOKIE), actor);
        assertTrue(actor.cookieAuthenticated());
    }

    @Test
    void bearerResolvesUser() throws SQLException {
        String alice = user("alice", false);
        String[] sessionId = new String[1];
        String token = session(alice, "bearer", NOW + DAY, sessionId);
        Actor actor = resolve(token, null, null, null);
        assertEquals(new Actor.User(alice, sessionId[0], Actor.Via.BEARER), actor);
        assertFalse(actor.cookieAuthenticated());
    }

    @Test
    void bearerWinsOverCookies() throws SQLException {
        String alice = user("alice", false);
        String bob = user("bob", false);
        String bearer = session(alice, "bearer");
        String sid = session(bob, "cookie");
        String gid = Tokens.newToken();
        guest("cookie", Tokens.hash(gid), false);
        Actor actor = resolve(bearer, sid, gid, null);
        assertEquals(alice, ((Actor.User) actor).userId());
        assertFalse(actor.cookieAuthenticated());
    }

    @Test
    void invalidBearerIsRejectedNotAnonymous() throws SQLException {
        String alice = user("alice", false);
        String sid = session(alice, "cookie");
        // unknown token, even with a valid cookie next to it
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve(Tokens.newToken(), sid, null, null)));
        // malformed / empty
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve("", null, null, null)));
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve("not a token", null, null, null)));
        // a cookie session's token is not a bearer token
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve(sid, null, null, null)));
        // expired
        String expired = session(alice, "bearer", NOW, null);
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve(expired, null, null, null)));
        // disabled user
        String carol = user("carol", true);
        String disabled = session(carol, "bearer");
        assertInvalidToken(assertThrows(HttpError.class, () -> resolve(disabled, null, null, null)));
    }

    @Test
    void staleSidCookieFallsThrough() throws SQLException {
        String alice = user("alice", false);
        String expired = session(alice, "cookie", NOW, null); // expires_at == now: expired
        String bearerKind = session(alice, "bearer");
        String carol = user("carol", true);
        String disabled = session(carol, "cookie");
        for (String sid : new String[] {expired, bearerKind, disabled, Tokens.newToken(), "garbage"}) {
            assertSame(Actor.ANONYMOUS, resolve(null, sid, null, null), sid);
        }
        // and on to the guest cookie
        String gid = Tokens.newToken();
        String guestId = guest("cookie", Tokens.hash(gid), false);
        assertEquals(guestId, ((Actor.Guest) resolve(null, expired, gid, null)).guestId());
    }

    @Test
    void userWithGuestCookieIsTheUser() throws SQLException {
        String alice = user("alice", false);
        String sid = session(alice, "cookie");
        String gid = Tokens.newToken();
        guest("cookie", Tokens.hash(gid), false);
        assertEquals(alice, ((Actor.User) resolve(null, sid, gid, null)).userId());
    }

    // ------------------------------------------------------------------ guests

    @Test
    void gidCookieResolvesGuest() throws SQLException {
        String gid = Tokens.newToken();
        String guestId = guest("cookie", Tokens.hash(gid), false);
        Actor actor = resolve(null, null, gid, "203.0.113.7");
        assertEquals(new Actor.Guest(guestId, GuestIdentity.COOKIE, false), actor);
        assertTrue(actor.cookieAuthenticated());
        assertSame(Actor.ANONYMOUS, resolve(null, null, Tokens.newToken(), null));
        assertSame(Actor.ANONYMOUS, resolve(null, null, "x", null));
    }

    @Test
    void bannedGuestStillResolves() throws SQLException {
        String gid = Tokens.newToken();
        String guestId = guest("cookie", Tokens.hash(gid), true);
        assertEquals(new Actor.Guest(guestId, GuestIdentity.COOKIE, true), resolve(null, null, gid, null));
    }

    @Test
    void ipModeResolvesGuestByAddress() throws SQLException {
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        String v4 = guest("ip", GuestKeys.ipKeyHash("203.0.113.7"), false);
        String v6 = guest("ip", GuestKeys.ipKeyHash("2001:db8:1:2::1"), false);

        Actor actor = resolve(null, null, null, "203.0.113.7");
        assertEquals(new Actor.Guest(v4, GuestIdentity.IP, false), actor);
        assertFalse(actor.cookieAuthenticated());
        assertEquals(v4, ((Actor.Guest) resolve(null, null, null, "::ffff:203.0.113.7")).guestId());
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, "203.0.113.8"));

        // same /64, rotated interface ID
        assertEquals(v6, ((Actor.Guest) resolve(null, null, null, "2001:db8:1:2:abcd:ef01:2345:6789")).guestId());
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, "2001:db8:1:3::1"));
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, "not-an-ip"));
    }

    @Test
    void cookieModeIgnoresIpGuests() throws SQLException {
        guest("ip", GuestKeys.ipKeyHash("203.0.113.7"), false);
        assertSame(Actor.ANONYMOUS, resolve(null, null, null, "203.0.113.7"));
    }

    @Test
    void cookieGuestsKeepWorkingInIpMode() throws SQLException {
        String gid = Tokens.newToken();
        String cookieGuest = guest("cookie", Tokens.hash(gid), false);
        String ipGuest = guest("ip", GuestKeys.ipKeyHash("203.0.113.7"), false);
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        assertEquals(cookieGuest, ((Actor.Guest) resolve(null, null, gid, "203.0.113.7")).guestId());
        // an unknown gid cookie falls back to the IP
        assertEquals(ipGuest, ((Actor.Guest) resolve(null, null, Tokens.newToken(), "203.0.113.7")).guestId());
    }

    @Test
    void guestKeyOfOtherKindDoesNotMatch() throws SQLException {
        // an ip-kind row never matches a gid cookie, even with the same hash
        String gid = Tokens.newToken();
        guest("ip", Tokens.hash(gid), false);
        assertSame(Actor.ANONYMOUS, resolve(null, null, gid, null));
    }

    @Test
    void guestsDisabledMeansAnonymous() throws SQLException {
        String gid = Tokens.newToken();
        guest("cookie", Tokens.hash(gid), false);
        guest("ip", GuestKeys.ipKeyHash("203.0.113.7"), false);
        settings.set(Settings.GUESTS_ENABLED, false);
        assertSame(Actor.ANONYMOUS, resolve(null, null, gid, "203.0.113.7"));
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        assertSame(Actor.ANONYMOUS, resolve(null, null, gid, "203.0.113.7"));
        // users are unaffected
        String alice = user("alice", false);
        assertEquals(alice, ((Actor.User) resolve(null, session(alice, "cookie"), gid, null)).userId());
    }
}
