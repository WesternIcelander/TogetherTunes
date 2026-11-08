package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.ActorResolver.Credentials;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link Guests} storage and its use by {@link ActorResolver}, in both dialects. */
abstract class GuestsContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    private Settings settings;
    private Guests guests;
    private ActorResolver resolver;
    private TestClock clock;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        clock = new TestClock(NOW);
        settings = new Settings(db);
        guests = new Guests(db, settings, clock, true, () -> "Quiet Walrus");
        resolver = new ActorResolver(db, settings, clock);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private String column(String guestId, String column) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM guests WHERE id = ?")) {
                ps.setString(1, guestId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Test
    void cookieGuestIsStoredByTokenHashAndResolves() throws SQLException {
        Guests.Created created = guests.create(GuestIdentity.COOKIE, "203.0.113.7", null);
        assertTrue(created.created());
        String token = created.cookieToken();
        assertTrue(Tokens.isWellFormed(token));
        Guests.GuestView guest = created.guest();
        assertEquals("Quiet Walrus", guest.name());
        assertFalse(guest.nameChosen());
        assertEquals(NOW, guest.createdAt());
        assertEquals(Tokens.hash(token), column(guest.id(), "key_hash"));
        assertEquals("cookie", column(guest.id(), "kind"));
        assertEquals("203.0.113.7", column(guest.id(), "ip_last"));
        assertEquals(String.valueOf(NOW), column(guest.id(), "last_seen_at"));

        Actor actor = resolver.resolve(new Credentials(null, null, token, "198.51.100.1"));
        assertEquals(new Actor.Guest(guest.id(), GuestIdentity.COOKIE, false), actor);
        assertEquals(guest, guests.get(guest.id()));
        assertNull(guests.get("nope"));
    }

    @Test
    void ipGuestIsStoredByKeyHashAndResolves() throws SQLException {
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        Guests.Created created = guests.create(GuestIdentity.IP, "203.0.113.7", null);
        assertNull(created.cookieToken());
        String id = created.guest().id();
        assertEquals(GuestKeys.ipKeyHash("203.0.113.7"), column(id, "key_hash"));
        assertEquals("ip", column(id, "kind"));
        assertEquals("203.0.113.7", column(id, "ip_last"));
        assertEquals(new Actor.Guest(id, GuestIdentity.IP, false),
                resolver.resolve(new Credentials(null, null, null, "203.0.113.7")));
        assertInstanceOf(Actor.Anonymous.class, resolver.resolve(new Credentials(null, null, null, "203.0.113.8")));

        HttpError e = assertThrows(HttpError.class, () -> guests.create(GuestIdentity.IP, "not-an-ip", null));
        assertEquals("no_client_ip", e.code());
    }

    @Test
    void concurrentIpCreationsMakeOneGuest() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Callable<String> create = () -> guests.create(GuestIdentity.IP, "192.0.2.1", null).guest().id();
                results.add(pool.submit(create));
            }
            Set<String> ids = results.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }).collect(Collectors.toSet());
            assertEquals(1, ids.size(), ids.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void renameSetsNameChosen() throws SQLException {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        Guests.Created created = guests.create(GuestIdentity.COOKIE, null, "Night Owl");
        assertEquals("Night Owl", created.guest().name());
        assertTrue(created.guest().nameChosen());
        assertNull(column(created.guest().id(), "ip_last"));

        Guests.GuestView renamed = guests.rename(created.guest().id(), " Early   Bird ", "203.0.113.7");
        assertEquals("Early Bird", renamed.name());
        assertEquals(renamed, guests.get(created.guest().id()));
        assertNotNull(column(created.guest().id(), "display_name"));
        assertEquals("not_found", assertThrows(HttpError.class, () -> guests.rename("nope", "Some Name", null)).code());

        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, false);
        assertEquals("custom_names_disabled",
                assertThrows(HttpError.class, () -> guests.rename(created.guest().id(), "Other", null)).code());
    }

    private void insertUser(String id, String username, String displayName, boolean disabled) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, username);
                ps.setString(3, displayName);
                ps.setLong(4, NOW);
                ps.setInt(5, disabled ? 1 : 0);
                return ps.executeUpdate();
            }
        });
    }

    private void assertRejected(int status, String code, Callable<?> call) {
        HttpError e = assertThrows(HttpError.class, call::call);
        assertEquals(status, e.status());
        assertEquals(code, e.code());
    }

    @Test
    void customNameRulesAreEnforced() throws Exception {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        insertUser("00000000-0000-7000-8000-000000000001", "siggi", "DJ Sig", false);
        insertUser("00000000-0000-7000-8000-000000000002", "oldtimer", "Old Timer", true);
        settings.setJson("guests.nameBlocklist", "[\"rude\"]");
        String id = guests.create(GuestIdentity.COOKIE, null, null).guest().id();

        // Form: 400 invalid_name. (create(mode, ip, name) takes a checked name; the request-level
        // create runs the same GuestNameRules.check, tested over HTTP in MeApiTest.)
        for (String bad : List.of("x", "y".repeat(33), "a<b", "...", "a\u200bb")) {
            assertRejected(400, "invalid_name", () -> guests.rename(id, bad, null));
        }
        // Account names (username or display name, disabled accounts too): 409 name_taken.
        for (String taken : List.of("SIGGI", "\uff53iggi", " dj   SIG ", "oldtimer", "old timer")) {
            assertRejected(409, "name_taken", () -> guests.rename(id, taken, null));
        }
        // Blocklist: 400 name_blocked.
        for (String blocked : List.of("Rude Boy", "r.u.d.e", "\uff32ude")) {
            assertRejected(400, "name_blocked", () -> guests.rename(id, blocked, null));
        }
        // Nothing rejected was stored.
        assertEquals("1", db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM guests");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Long.toString(rs.getLong(1));
            }
        }));
        assertEquals("Quiet Walrus", guests.get(id).name());

        // Allowed: stored NFKC-normalised, trimmed and collapsed.
        assertEquals("Siggi Fan", guests.rename(id, "  \uff33iggi   Fan ", null).name());
        assertEquals("Siggi Fan", column(id, "display_name"));
    }

    // ------------------------------------------------------------------ generated names

    /** A {@link Guests} whose name draws come from {@code draws} (then "Last Resort"), counted. */
    private Guests guestsDrawing(AtomicInteger count, String... draws) {
        Deque<String> queue = new ArrayDeque<>(List.of(draws));
        return new Guests(db, settings, clock, true, () -> {
            count.incrementAndGet();
            synchronized (queue) {
                return queue.isEmpty() ? "Last Resort" : queue.poll();
            }
        });
    }

    private void setLastSeen(String guestId, long at) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE guests SET last_seen_at = ? WHERE id = ?")) {
                ps.setLong(1, at);
                ps.setString(2, guestId);
                return ps.executeUpdate();
            }
        });
    }

    @Test
    void generatedNameIsRedrawnWhileTaken() throws SQLException {
        guests.create(GuestIdentity.COOKIE, null, null); // Quiet Walrus
        AtomicInteger draws = new AtomicInteger();
        Guests drawing = guestsDrawing(draws, "Quiet Walrus", "QUIET WALRUS", "Night Owl");
        Guests.GuestView guest = drawing.create(GuestIdentity.COOKIE, null, null).guest();
        assertEquals("Night Owl", guest.name());
        assertFalse(guest.nameChosen());
        assertEquals(3, draws.get());
        assertEquals("Night Owl", column(guest.id(), "display_name"));
    }

    @Test
    void generatedNameGetsANumberAfterFiveTakenDraws() throws SQLException {
        AtomicInteger draws = new AtomicInteger();
        Guests drawing = guestsDrawing(draws);
        assertEquals("Last Resort", drawing.create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals(1, draws.get());
        assertEquals("Last Resort 2", drawing.create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals(1 + Guests.NAME_DRAWS, draws.get());
        assertEquals("Last Resort 3", drawing.create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals("Quiet Walrus", guests.create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals("Quiet Walrus 2", guests.create(GuestIdentity.COOKIE, null, null).guest().name());
    }

    @Test
    void numberIsTheSmallestFreeOneIgnoringOtherSuffixes() throws SQLException {
        settings.set(Settings.GUESTS_ALLOW_CUSTOM_NAMES, true);
        guests.create(GuestIdentity.COOKIE, null, "quiet walrus");
        guests.create(GuestIdentity.COOKIE, null, "Quiet Walrus 3");
        guests.create(GuestIdentity.COOKIE, null, "Quiet Walrus Fan");
        guests.create(GuestIdentity.COOKIE, null, "Quiet Walrus 02");
        guests.create(GuestIdentity.COOKIE, null, "Quiet Walrusx 2");
        guests.create(GuestIdentity.COOKIE, null, "Quiet_Walrus 2");
        assertEquals("Quiet Walrus 2", guests.create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals("Quiet Walrus 4", guests.create(GuestIdentity.COOKIE, null, null).guest().name());
    }

    @Test
    void onlyActiveGuestsHoldTheirNames() throws SQLException {
        String first = guests.create(GuestIdentity.COOKIE, null, null).guest().id();
        String second = guests.create(GuestIdentity.COOKIE, null, null).guest().id();
        assertEquals("Quiet Walrus 2", guests.get(second).name());

        setLastSeen(first, NOW - Guests.ACTIVE_MS); // exactly at the limit: still active
        assertEquals("Quiet Walrus 3", guests.create(GuestIdentity.COOKIE, null, null).guest().name());

        setLastSeen(first, NOW - Guests.ACTIVE_MS - 1);
        AtomicInteger draws = new AtomicInteger();
        assertEquals("Quiet Walrus",
                guestsDrawing(draws, "Quiet Walrus").create(GuestIdentity.COOKIE, null, null).guest().name());
        assertEquals(1, draws.get());

        clock.advance(Guests.ACTIVE_MS + 1); // everyone above is inactive now
        assertEquals("Quiet Walrus", guests.create(GuestIdentity.COOKIE, null, null).guest().name());
    }

    @Test
    void concurrentCreationsGetDistinctNames() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Callable<String> create = () -> guests.create(GuestIdentity.COOKIE, null, null).guest().name();
                results.add(pool.submit(create));
            }
            Set<String> names = results.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }).collect(Collectors.toSet());
            assertEquals(16, names.size(), names.toString());
            assertTrue(names.contains("Quiet Walrus") && names.contains("Quiet Walrus 16"), names.toString());
        } finally {
            pool.shutdownNow();
        }
    }
}
