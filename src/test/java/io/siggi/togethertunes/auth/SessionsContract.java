package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.ActorResolver.Credentials;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.Settings;
import java.lang.reflect.RecordComponent;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Sessions} (sliding expiry, list, revoke) and its use by {@link ActorResolver}, in both dialects. */
abstract class SessionsContract {
    static final long NOW = 1_800_000_000_000L;
    static final long MINUTE = 60_000L;
    static final long DAY = 86_400_000L;

    protected Database db;
    private TestClock clock;
    private Sessions sessions;
    private ActorResolver resolver;
    private AuthFixtures fixtures;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        clock = new TestClock(NOW);
        sessions = new Sessions(db, clock);
        resolver = new ActorResolver(db, new Settings(db), clock);
        fixtures = new AuthFixtures(db, NOW);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private long[] times(String sessionId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_seen_at, expires_at FROM sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : null;
                }
            }
        });
    }

    private Actor resolveCookie(String token) throws SQLException {
        return resolver.resolve(new Credentials(null, token, null, null));
    }

    @Test
    void tokensAre256BitAndStoredOnlyAsSha256() throws SQLException {
        String user = fixtures.user("alice", false);
        Sessions.Created created = sessions.create(user, Actor.Via.COOKIE, "UA", "192.0.2.7");
        assertTrue(Tokens.isWellFormed(created.token()));
        assertEquals(NOW + Sessions.LIFETIME_MS, created.expiresAt());
        assertFalse(created.toString().contains(created.token()), "toString redacts the token");
        List<String> stored = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT token_hash, kind FROM sessions WHERE id = ?")) {
                ps.setString(1, created.sessionId());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return List.of(rs.getString(1), rs.getString(2));
                }
            }
        });
        assertEquals(List.of(Tokens.hash(created.token()), "cookie"), stored);
        assertEquals(64, stored.get(0).length());
    }

    @Test
    void expirySlidesWithUse() throws SQLException {
        String user = fixtures.user("alice", false);
        Sessions.Created created = sessions.create(user, Actor.Via.COOKIE, null, null);
        String token = created.token();

        // Within the touch interval nothing is written.
        clock.advance(MINUTE - 1);
        assertInstanceOf(Actor.User.class, resolveCookie(token));
        assertEquals(NOW, times(created.sessionId())[0]);
        assertEquals(NOW + Sessions.LIFETIME_MS, times(created.sessionId())[1]);

        // After it, last_seen_at and expires_at move.
        clock.advance(1);
        assertInstanceOf(Actor.User.class, resolveCookie(token));
        long seen = NOW + MINUTE;
        assertEquals(seen, times(created.sessionId())[0]);
        assertEquals(seen + Sessions.LIFETIME_MS, times(created.sessionId())[1]);

        // Used every 20 days, it lives far past its original 30 days.
        for (int i = 0; i < 5; i++) {
            clock.advance(20 * DAY);
            assertInstanceOf(Actor.User.class, resolveCookie(token), "use " + i);
        }
        // Left alone for 30 days, it expires.
        clock.advance(Sessions.LIFETIME_MS);
        assertSameAnonymous(resolveCookie(token));
    }

    private static void assertSameAnonymous(Actor actor) {
        assertEquals(Actor.ANONYMOUS, actor);
    }

    @Test
    void bearerTokensSlideToo() throws SQLException {
        String user = fixtures.user("alice", false);
        Sessions.Created created = sessions.create(user, Actor.Via.BEARER, "app/1.0", null);
        clock.advance(25 * DAY);
        assertEquals(new Actor.User(user, created.sessionId(), Actor.Via.BEARER),
                resolver.resolve(new Credentials(created.token(), null, null, null)));
        clock.advance(25 * DAY);
        assertInstanceOf(Actor.User.class, resolver.resolve(new Credentials(created.token(), null, null, null)));
        clock.advance(Sessions.LIFETIME_MS);
        HttpError e = assertThrows(HttpError.class,
                () -> resolver.resolve(new Credentials(created.token(), null, null, null)));
        assertEquals("invalid_token", e.code());
        // A bearer token is not a cookie and vice versa.
        clock.set(NOW);
        assertSameAnonymous(resolveCookie(created.token()));
    }

    @Test
    void touchIsConditional() throws SQLException {
        String user = fixtures.user("alice", false);
        Sessions.Created created = sessions.create(user, Actor.Via.COOKIE, null, null);
        assertEquals(0, sessions.touch(created.sessionId(), NOW), "too soon");
        clock.advance(2 * MINUTE);
        assertEquals(NOW + 2 * MINUTE + Sessions.LIFETIME_MS, sessions.touch(created.sessionId(), NOW));
        // A second request that read the old last_seen_at doesn't write again.
        assertEquals(0, sessions.touch(created.sessionId(), NOW));
        assertEquals(0, sessions.touch("no-such-session", NOW));
    }

    @Test
    void listShowsLiveSessionsNewestFirstWithoutIp() throws SQLException {
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(sessions.create(alice, i % 2 == 0 ? Actor.Via.COOKIE : Actor.Via.BEARER, "UA " + i, "192.0.2." + i)
                    .sessionId());
            clock.advance(1);
        }
        sessions.create(bob, Actor.Via.COOKIE, null, null);
        String[] expiredId = new String[1];
        fixtures.session(alice, "cookie", NOW - 1, expiredId);

        Sessions.Page all = sessions.list(alice, null, 50);
        assertNull(all.nextCursor());
        assertEquals(List.of(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0)),
                all.items().stream().map(Sessions.SessionView::id).toList());
        Sessions.SessionView first = all.items().get(4);
        assertEquals("cookie", first.kind());
        assertEquals("UA 0", first.userAgent());
        assertEquals(NOW, first.createdAt());
        assertEquals("bearer", all.items().get(3).kind());

        // Pages of 2.
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Sessions.Page page = sessions.list(alice, cursor, 2);
            assertTrue(page.items().size() <= 2);
            page.items().forEach(s -> assertTrue(seen.add(s.id())));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(new HashSet<>(ids), seen);
        assertThrows(IllegalArgumentException.class, () -> sessions.list(alice, null, 0));

        // The view type has no IP (or token) component at all.
        for (RecordComponent rc : Sessions.SessionView.class.getRecordComponents()) {
            String name = rc.getName().toLowerCase();
            assertFalse(name.contains("ip") || name.contains("token") || name.contains("hash"), name);
        }
    }

    @Test
    void revokeOnlyOwnSessions() throws SQLException {
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        Sessions.Created a = sessions.create(alice, Actor.Via.COOKIE, null, null);
        Sessions.Created b = sessions.create(bob, Actor.Via.COOKIE, null, null);
        assertFalse(sessions.revoke(alice, b.sessionId()), "not alice's");
        assertInstanceOf(Actor.User.class, resolveCookie(b.token()));
        assertTrue(sessions.revoke(alice, a.sessionId()));
        assertFalse(sessions.revoke(alice, a.sessionId()));
        assertSameAnonymous(resolveCookie(a.token()));
    }

    @Test
    void revokeOthersKeepsTheCurrentOne() throws SQLException {
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        Sessions.Created keep = sessions.create(alice, Actor.Via.COOKIE, null, null);
        Sessions.Created other1 = sessions.create(alice, Actor.Via.BEARER, null, null);
        Sessions.Created other2 = sessions.create(alice, Actor.Via.COOKIE, null, null);
        fixtures.session(alice, "cookie", NOW - 1, null); // expired: deleted, not counted
        Sessions.Created bobs = sessions.create(bob, Actor.Via.COOKIE, null, null);
        assertEquals(2, sessions.revokeOthers(alice, keep.sessionId()));
        assertEquals(List.of(keep.sessionId()),
                sessions.list(alice, null, 50).items().stream().map(Sessions.SessionView::id).toList());
        assertSameAnonymous(resolveCookie(other2.token()));
        assertThrows(HttpError.class, () -> resolver.resolve(new Credentials(other1.token(), null, null, null)));
        assertInstanceOf(Actor.User.class, resolveCookie(bobs.token()));
    }

    private long count() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sessions");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void expiredRowsAreCleanedUp() throws SQLException {
        String alice = fixtures.user("alice", false);
        String bob = fixtures.user("bob", false);
        fixtures.session(alice, "cookie", NOW - 1, null);
        fixtures.session(bob, "cookie", NOW, null);
        fixtures.session(bob, "cookie", NOW + 1, null);
        // A new session of alice's deletes her expired ones only.
        sessions.create(alice, Actor.Via.COOKIE, null, null);
        assertEquals(3, count());
        assertEquals(1, sessions.deleteExpired());
        assertEquals(2, count());
    }
}
