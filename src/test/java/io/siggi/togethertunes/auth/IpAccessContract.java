package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link IpAccess}, the admin listings it decorates ({@link Guests#list}, {@link AuditEntries}) and
 * the guest touch that compares IPs without reading them, in both dialects.
 */
abstract class IpAccessContract {
    static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    protected Database db;
    private AuthFixtures fixtures;
    private Roles roles;
    private Permissions permissions;
    private IpAccess ipAccess;
    private Guests guests;
    private AuditEntries audit;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        roles = new Roles(db);
        Settings settings = new Settings(db);
        permissions = new Permissions(db, settings);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        ipAccess = new IpAccess(db, permissions, clock);
        int[] n = {0};
        Supplier<String> names = () -> "Guest Name " + (++n[0]);
        guests = new Guests(db, settings, clock, false, names);
        audit = new AuditEntries(db);
        fixtures = new AuthFixtures(db, NOW);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** A user holding exactly these permissions (through a new role). */
    private Actor.User userWith(String username, Permission... perms) throws SQLException {
        String id = fixtures.user(username, false);
        if (perms.length > 0) {
            Roles.Role role = roles.create("r-" + username, Set.of(perms), false);
            roles.setUserRoles(id, List.of(role.id()));
        }
        return new Actor.User(id, "s-" + username, Actor.Via.COOKIE);
    }

    private int admins;

    /** A new user holding the {@code admin} role. */
    private Actor.User admin() throws SQLException {
        String id = fixtures.user("root" + (++admins), false);
        Roles.Role adminRole = roles.list().stream().filter(Roles.Role::isAdmin).findFirst().orElseThrow();
        roles.setUserRoles(id, List.of(adminRole.id()));
        return new Actor.User(id, "s-root" + admins, Actor.Via.COOKIE);
    }

    private void writeAudit(long at, String actorUserId, String ip, String action) throws SQLException {
        db.write(c -> {
            AuditLog.write(c, at, actorUserId, null, ip, action, "t", Map.of("n", 1));
            return null;
        });
    }

    @Test
    void ipsOnlyForIpView() throws SQLException {
        Guests.GuestView g1 = guests.create(GuestIdentity.COOKIE, "203.0.113.7", null).guest();
        Guests.GuestView g2 = guests.create(GuestIdentity.COOKIE, null, null).guest();
        writeAudit(NOW, null, "198.51.100.9", "x.one");
        writeAudit(NOW, null, null, "x.two");
        List<String> auditIds = new ArrayList<>();
        for (AuditEntries.AuditEntryView e : audit.list(null, null, null, 50).items()) {
            auditIds.add(e.id());
        }

        Actor.User viewer = userWith("viewer", Permission.IP_VIEW);
        Actor.User plain = userWith("plain", Permission.ADMIN_USERS);
        Actor.User root = admin();

        for (Actor allowed : List.of(viewer, root)) {
            assertTrue(ipAccess.mayView(allowed));
            assertEquals(Map.of(g1.id(), "203.0.113.7"), ipAccess.ipsOfGuests(allowed, List.of(g1.id(), g2.id())));
            Map<String, String> ips = ipAccess.ipsOfAuditEntries(allowed, auditIds);
            // 4 rows: g1's and g2's guest.created (g2 without an IP), x.one, x.two (without).
            assertEquals(4, auditIds.size());
            assertEquals(2, ips.size(), "rows without an IP are left out");
            assertEquals(Set.of("203.0.113.7", "198.51.100.9"), Set.copyOf(ips.values()));
        }
        Actor.Guest guestActor = g1.actor();
        for (Actor denied : List.of(plain, guestActor, Actor.ANONYMOUS)) {
            assertFalse(ipAccess.mayView(denied));
            assertEquals(Map.of(), ipAccess.ipsOfGuests(denied, List.of(g1.id(), g2.id())));
            assertEquals(Map.of(), ipAccess.ipsOfAuditEntries(denied, auditIds));
        }
        assertEquals(Map.of(), ipAccess.ipsOfGuests(root, List.of()));
    }

    @Test
    void manyIdsAreChunked() throws SQLException {
        Guests.GuestView g = guests.create(GuestIdentity.COOKIE, "2001:db8::1", null).guest();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            ids.add("missing-" + i);
        }
        ids.add(g.id());
        ids.add(g.id());
        assertEquals(Map.of(g.id(), "2001:db8::1"), ipAccess.ipsOfGuests(admin(), ids));
    }

    @Test
    void sessionIps() throws SQLException {
        Sessions sessions = new Sessions(db, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        Actor.User viewer = userWith("viewer", Permission.IP_VIEW);
        String alice = fixtures.user("alice", false);
        Sessions.Created first = sessions.create(alice, Actor.Via.COOKIE, "Firefox", "203.0.113.7");
        Sessions.Created second = sessions.create(alice, Actor.Via.BEARER, null, null);
        fixtures.session(alice, "cookie", NOW - 1, null); // expired: not listed

        List<IpAccess.SessionIp> list = ipAccess.sessions(viewer, alice);
        assertEquals(2, list.size());
        assertEquals(second.sessionId(), list.get(0).id(), "newest first");
        assertEquals("bearer", list.get(0).kind());
        assertNull(list.get(0).ip());
        assertEquals(first.sessionId(), list.get(1).id());
        assertEquals("203.0.113.7", list.get(1).ip());
        assertEquals("Firefox", list.get(1).userAgent());
        assertEquals(List.of(), ipAccess.sessions(viewer, "no-such-user"));

        HttpError e = assertThrows(HttpError.class,
                () -> ipAccess.sessions(userWith("plain", Permission.ADMIN_USERS), alice));
        assertEquals(403, e.status());
        e = assertThrows(HttpError.class, () -> ipAccess.sessions(Actor.ANONYMOUS, alice));
        assertEquals(401, e.status());
    }

    @Test
    void guestListPagesBySeenTimeAndSearchesNames() throws SQLException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = guests.create(GuestIdentity.COOKIE, "203.0.113." + i, null).guest().id();
            ids.add(id);
            long seen = NOW - (5 - i) * MINUTE; // later guests seen more recently
            db.write(c -> {
                try (var ps = c.prepareStatement("UPDATE guests SET last_seen_at = ? WHERE id = ?")) {
                    ps.setLong(1, seen);
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
                return null;
            });
        }
        Guests.Page page = guests.list(null, null, 2);
        assertEquals(List.of(ids.get(4), ids.get(3)), page.items().stream().map(i -> i.guest().id()).toList());
        assertEquals(NOW - MINUTE, page.items().get(0).lastSeenAt());
        page = guests.list(null, page.nextCursor(), 2);
        assertEquals(List.of(ids.get(2), ids.get(1)), page.items().stream().map(i -> i.guest().id()).toList());
        page = guests.list(null, page.nextCursor(), 2);
        assertEquals(List.of(ids.get(0)), page.items().stream().map(i -> i.guest().id()).toList());
        assertNull(page.nextCursor());

        page = guests.list("NAME 3", null, 50);
        assertEquals(List.of(ids.get(2)), page.items().stream().map(i -> i.guest().id()).toList());
        assertEquals("Guest Name 3", page.items().get(0).guest().name());
        assertEquals(0, guests.list("%", null, 50).items().size(), "LIKE wildcards are literal");

        for (String bad : List.of("x", "_abc", "12_", "abc_def")) {
            HttpError e = assertThrows(HttpError.class, () -> guests.list(null, bad, 2), bad);
            assertEquals("invalid_cursor", e.code());
        }
    }

    @Test
    void auditListPagesFiltersAndNamesActors() throws SQLException {
        Actor.User alice = userWith("alice");
        String guestId = guests.create(GuestIdentity.COOKIE, "203.0.113.5", null).guest().id(); // writes guest.created
        for (int i = 0; i < 4; i++) {
            writeAudit(NOW + i, alice.userId(), "203.0.113.1", "test.action");
        }
        writeAudit(NOW + 2, null, null, "test.other"); // same time as one of alice's rows

        AuditEntries.Page all = audit.list(null, null, null, 50);
        assertEquals(6, all.items().size());
        assertEquals("test.action", all.items().get(0).action());
        assertEquals(NOW + 3, all.items().get(0).at());
        assertEquals(ActorRef.user(alice.userId(), "alice"), all.items().get(0).actor());
        assertEquals("{\"n\":1}", all.items().get(0).detailsJson());
        AuditEntries.AuditEntryView created = all.items().get(5);
        assertEquals(Guests.AUDIT_CREATED, created.action());
        assertTrue(created.actor().isGuest());
        assertEquals(guestId, created.actor().id());

        // Paging one at a time visits every row once, in order, across equal timestamps.
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            AuditEntries.Page page = audit.list(null, null, cursor, 1);
            page.items().forEach(e -> paged.add(e.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(all.items().stream().map(AuditEntries.AuditEntryView::id).toList(), paged);

        assertEquals(4, audit.list("test.action", null, null, 50).items().size());
        assertEquals(1, audit.list("test.other", null, null, 50).items().size());
        assertNull(audit.list("test.other", null, null, 50).items().get(0).actor());
        assertEquals(4, audit.list(null, alice.userId(), null, 50).items().size());
        assertEquals(1, audit.list(null, guestId, null, 50).items().size());
        assertThrows(HttpError.class, () -> audit.list(null, null, "nope", 5));
    }

    @Test
    void guestTouchComparesIpsWithoutReadingThem() throws SQLException {
        Guests.GuestView g = guests.create(GuestIdentity.COOKIE, "203.0.113.7", null).guest();
        long created = g.createdAt();
        // Same IP, too soon: nothing written.
        assertFalse(Guests.touch(db, g.id(), created, true, "203.0.113.7", created + 1000));
        // Another IP: written at once.
        assertTrue(Guests.touch(db, g.id(), created, false, "198.51.100.2", created + 1000));
        assertEquals(Map.of(g.id(), "198.51.100.2"), ipAccess.ipsOfGuests(admin(), List.of(g.id())));
        // A stale read (last_seen_at moved on) writes nothing.
        assertFalse(Guests.touch(db, g.id(), created, false, "198.51.100.3", created + 2000));
        // No client IP: the stored one is kept.
        assertTrue(Guests.touch(db, g.id(), created + 1000, false, null, created + 1000 + Guests.TOUCH_INTERVAL_MS));
        assertEquals(Map.of(g.id(), "198.51.100.2"), ipAccess.ipsOfGuests(admin(), List.of(g.id())));
    }

}
