package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rooms ({@link Rooms}): creation defaults, slugs (validation, derivation, collisions, lookup by
 * id or slug), visibility (listing and reading), the owner-only edit, deleting (with its queue
 * and retention), member roles, {@code rooms.moderateAny}, ownership transfer, leaving, and the
 * audit trail.
 */
abstract class RoomsContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Rooms rooms;
    private Accounts accounts;
    private Roles roles;
    private Actor.User alice;
    private Actor.User bob;
    private Actor.User carol;
    private Actor.User mod; // holds rooms.moderateAny
    private final Actor guest = new Actor.Guest(UuidV7.newId(), GuestIdentity.COOKIE, false);
    private static final String IP = "203.0.113.9";

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        Permissions permissions = new Permissions(db, new Settings(db, config));
        rooms = new Rooms(db, permissions, clock);
        roles = new Roles(db);
        accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        user("root", "Root"); // the first account is an admin; the users below are plain
        alice = user("alice", "Alice");
        bob = user("bob", "Bob");
        carol = user("carol", "Carol");
        mod = user("mod", "Mod");
        Roles.Role moderators = roles.create("moderators", List.of(Permission.ROOMS_MODERATE_ANY), false);
        List<String> modRoles = new ArrayList<>();
        roles.userRoles(mod.userId()).forEach(r -> modRoles.add(r.id()));
        modRoles.add(moderators.id());
        roles.setUserRoles(mod.userId(), modRoles);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ fixture

    private Actor.User user(String username, String displayName) throws SQLException {
        String id = accounts.createLocal(username, displayName, username + "-password", false, null,
                "account.register").id();
        return new Actor.User(id, "s-" + username, Actor.Via.BEARER);
    }

    private Rooms.RoomView room(Actor.User owner, String name, Rooms.Visibility visibility) throws SQLException {
        return rooms.create(owner, name, null, visibility, null, IP);
    }

    private static void expect(int status, String code, Executable e) {
        HttpError err = assertThrows(HttpError.class, e);
        assertEquals(status, err.status(), err.getMessage());
        assertEquals(code, err.code(), err.getMessage());
    }

    private List<String> listedIds(Actor actor) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (Rooms.RoomView r : rooms.list(actor, null, Rooms.LIST_MAX).items()) {
            ids.add(r.id());
        }
        return ids;
    }

    private List<String> auditActions(String target) throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT action, ip FROM audit_log WHERE target = ?"
                    + " ORDER BY at, id")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                        assertEquals(IP, rs.getString(2), "the client IP goes to audit_log.ip");
                    }
                }
            }
            return out;
        });
    }

    private String ownerColumn(String roomId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT owner_id FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private String storedRole(String roomId, String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT role FROM room_members WHERE room_id = ? AND user_id = ?")) {
                ps.setString(1, roomId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private void disable(String userId) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ creating

    @Test
    void createUsesDefaultsAndMakesTheCreatorOwner() throws Exception {
        Rooms.RoomView r = rooms.create(alice, "  Friday   Night  ", null, null, null, IP);
        assertTrue(UuidV7.isValid(r.id()), r.id());
        assertEquals("Friday Night", r.name());
        assertEquals("friday-night", r.slug());
        assertEquals("unlisted", r.visibility());
        assertEquals("vote", r.controlMode());
        assertEquals(clock.millis(), r.createdAt());
        assertEquals(alice.userId(), r.owner().id());
        assertEquals("Alice", r.owner().name());
        assertFalse(r.owner().isGuest());
        assertEquals(new Rooms.You("owner", true, true, true), r.you());
        assertEquals(alice.userId(), ownerColumn(r.id()));
        assertEquals("owner", storedRole(r.id(), alice.userId()));
        assertEquals(List.of(Rooms.AUDIT_CREATED), auditActions(r.id()));
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT settings_json, playback_state, current_entry_id,"
                    + " version FROM rooms WHERE id = ?")) {
                ps.setString(1, r.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("{}", rs.getString(1));
                    assertEquals("idle", rs.getString(2));
                    assertNull(rs.getString(3));
                    assertEquals(0, rs.getLong(4));
                }
            }
            return null;
        });

        Rooms.RoomView explicit = rooms.create(bob, "Bob's", " My-Room-1 ", Rooms.Visibility.PRIVATE,
                Rooms.ControlMode.OPEN, IP);
        assertEquals("my-room-1", explicit.slug());
        assertEquals("private", explicit.visibility());
        assertEquals("open", explicit.controlMode());
    }

    @Test
    void slugsAreValidatedAndUnique() throws Exception {
        for (String bad : List.of("ab", "-abc", "abc-", "a_b_c", "ab cd", "x".repeat(Rooms.SLUG_MAX + 1),
                "0196f2a4-1b2c-7d3e-8f40-123456789abc", "")) {
            expect(400, "invalid_slug", () -> Rooms.slug(bad));
        }
        assertEquals("abc", Rooms.slug("ABC"));
        assertEquals("x".repeat(Rooms.SLUG_MAX), Rooms.slug("x".repeat(Rooms.SLUG_MAX)));
        assertEquals("a--b", Rooms.slug("a--b"));

        rooms.create(alice, "One", "taken", null, null, IP);
        expect(409, "slug_taken", () -> rooms.create(bob, "Two", "TAKEN", null, null, IP));

        // derived slugs: accents folded, punctuation collapsed, numbered when taken
        assertEquals("cafe-del-mar", room(alice, "Café  del Mar!", null).slug());
        assertEquals("cafe-del-mar-2", room(bob, "Cafe del mar", null).slug());
        assertEquals("cafe-del-mar-3", room(bob, "CAFÉ DEL MAR", null).slug());
        assertEquals("room", room(alice, "!!", null).slug());
        assertEquals("room-2", room(alice, "日本", null).slug());
        String longSlug = room(alice, "a".repeat(80), null).slug();
        assertTrue(longSlug.length() <= Rooms.SLUG_MAX && longSlug.startsWith("aaaa"), longSlug);
        assertEquals(Rooms.slug(longSlug), longSlug);

        expect(400, "invalid_name", () -> rooms.create(alice, "   ", null, null, null, IP));
        expect(400, "name_too_long", () -> rooms.create(alice, "n".repeat(Rooms.NAME_MAX + 1), null, null, null, IP));
    }

    @Test
    void slugsDerivedConcurrentlyAreDistinct() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Rooms.RoomView>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Actor.User owner = i % 2 == 0 ? alice : bob;
                futures.add(pool.submit(() -> room(owner, "Party", null)));
            }
            Set<String> slugs = new HashSet<>();
            for (Future<Rooms.RoomView> f : futures) {
                assertTrue(slugs.add(f.get().slug()));
            }
            assertTrue(slugs.contains("party") && slugs.contains("party-16"), slugs.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ownedRoomsAreLimited() throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO rooms (id, slug, name, owner_id, created_at,"
                    + " visibility, control_mode, settings_json, playback_state) VALUES (?, ?, 'R', ?, 1, 'public',"
                    + " 'vote', '{}', 'idle')")) {
                for (int i = 0; i < Rooms.MAX_OWNED; i++) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, "bulk-" + i);
                    ps.setString(3, alice.userId());
                    ps.executeUpdate();
                }
            }
            return null;
        });
        expect(409, "too_many_rooms", () -> room(alice, "One more", null));
        assertNotNull(room(bob, "Bob's first", null));
    }

    // ------------------------------------------------------------------ seeing rooms

    @Test
    void visibilityDecidesWhoListsAndReadsARoom() throws Exception {
        Rooms.RoomView pub = room(alice, "Public", Rooms.Visibility.PUBLIC);
        clock.advance(1);
        Rooms.RoomView unl = room(alice, "Unlisted", Rooms.Visibility.UNLISTED);
        clock.advance(1);
        Rooms.RoomView priv = room(alice, "Private", Rooms.Visibility.PRIVATE);
        clock.advance(1);
        Rooms.RoomView bobs = room(bob, "Bob unlisted", Rooms.Visibility.UNLISTED);

        assertEquals(List.of(priv.id(), unl.id(), pub.id()), listedIds(alice), "own rooms, newest first");
        assertEquals(List.of(bobs.id(), pub.id()), listedIds(bob));
        assertEquals(List.of(pub.id()), listedIds(carol));
        assertEquals(List.of(pub.id()), listedIds(guest));
        assertEquals(List.of(pub.id()), listedIds(Actor.ANONYMOUS));
        assertEquals(List.of(pub.id()), listedIds(mod), "moderateAny doesn't list other people's private rooms");

        // reading: public and unlisted by anyone, by id or by slug (any case); private: 404
        for (Actor a : List.of(carol, guest, Actor.ANONYMOUS)) {
            assertEquals(pub.id(), rooms.get(a, pub.id()).id());
            assertEquals(unl.id(), rooms.get(a, unl.slug()).id());
            assertEquals(unl.id(), rooms.get(a, unl.slug().toUpperCase()).id());
            assertNull(rooms.get(a, pub.slug()).you().role());
            expect(404, "room_not_found", () -> rooms.get(a, priv.id()));
            expect(404, "room_not_found", () -> rooms.get(a, priv.slug()));
        }
        expect(404, "room_not_found", () -> rooms.get(carol, "no-such-room"));
        expect(404, "room_not_found", () -> rooms.get(carol, UuidV7.newId()));
        expect(404, "room_not_found", () -> rooms.get(carol, ""));
        Rooms.RoomView seenByMod = rooms.get(mod, priv.id());
        assertEquals(new Rooms.You("moderator", false, true, true), seenByMod.you());

        // invited: a member sees and lists the private room
        rooms.setRole(alice, priv.id(), carol.userId(), Rooms.Role.MEMBER, IP);
        assertEquals(new Rooms.You("member", false, false, false), rooms.get(carol, priv.slug()).you());
        assertEquals(List.of(priv.id(), pub.id()), listedIds(carol));

        // banned: 403 on public/unlisted, 404 on private, never listed
        rooms.setRole(alice, pub.id(), bob.userId(), Rooms.Role.BANNED, IP);
        rooms.setRole(alice, priv.id(), bob.userId(), Rooms.Role.BANNED, IP);
        expect(403, "banned_from_room", () -> rooms.get(bob, pub.id()));
        expect(404, "room_not_found", () -> rooms.get(bob, priv.id()));
        assertEquals(List.of(bobs.id()), listedIds(bob));
        // moderateAny overrides a ban
        rooms.setRole(alice, pub.id(), mod.userId(), Rooms.Role.BANNED, IP);
        assertEquals("moderator", rooms.get(mod, pub.id()).you().role());
    }

    @Test
    void listPagesWithACursor() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(0, room(alice, "Room " + i, Rooms.Visibility.PUBLIC).id());
            if (i % 2 == 0) {
                clock.advance(1); // some share a created_at, so the id breaks ties
            }
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Rooms.Page page = rooms.list(carol, cursor, 2);
            assertTrue(page.items().size() <= 2);
            page.items().forEach(r -> seen.add(r.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(5, seen.size());
        assertEquals(new HashSet<>(ids), new HashSet<>(seen));
        assertEquals(rooms.list(carol, null, 10).items().stream().map(Rooms.RoomView::id).toList(), seen);
        for (String bad : List.of("x", "_abc", "12_", "abc_def")) {
            expect(400, "invalid_cursor", () -> rooms.list(carol, bad, 2));
        }
    }

    // ------------------------------------------------------------------ changing and deleting

    @Test
    void onlyTheOwnerChangesARoom() throws Exception {
        Rooms.RoomView r = room(alice, "Lounge", Rooms.Visibility.PUBLIC);
        rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        expect(403, "forbidden", () -> rooms.update(bob, r.id(), new Rooms.Edit("Mine", null, null, null), IP));
        expect(403, "forbidden", () -> rooms.update(mod, r.id(), new Rooms.Edit("Mine", null, null, null), IP));
        expect(403, "forbidden", () -> rooms.update(carol, r.id(), new Rooms.Edit("Mine", null, null, null), IP));

        Rooms.RoomView changed = rooms.update(alice, r.slug(), new Rooms.Edit(" Big  Lounge ", "Big-Lounge",
                Rooms.Visibility.PRIVATE, Rooms.ControlMode.OWNER), IP);
        assertEquals("Big Lounge", changed.name());
        assertEquals("big-lounge", changed.slug());
        assertEquals("private", changed.visibility());
        assertEquals("owner", changed.controlMode());
        assertEquals(changed, rooms.get(alice, "big-lounge"));
        expect(404, "room_not_found", () -> rooms.get(alice, "lounge"));
        // the old slug is free again
        assertEquals("lounge", room(bob, "Lounge", null).slug());
        // a private room the caller can't see is 404, not 403
        expect(404, "room_not_found", () -> rooms.update(carol, r.id(), new Rooms.Edit("x", null, null, null), IP));

        expect(409, "slug_taken", () -> rooms.update(alice, r.id(), new Rooms.Edit(null, "lounge", null, null), IP));
        expect(400, "invalid_slug", () -> rooms.update(alice, r.id(), new Rooms.Edit(null, "no", null, null), IP));
        expect(400, "invalid_name", () -> rooms.update(alice, r.id(), new Rooms.Edit(" ", null, null, null), IP));
        // the room's own slug (any case) and an empty edit are no-ops, and aren't audited
        assertEquals(changed, rooms.update(alice, r.id(), new Rooms.Edit(null, "BIG-LOUNGE", null, null), IP));
        assertEquals(changed, rooms.update(alice, r.id(), new Rooms.Edit(null, null, null, null), IP));
        assertEquals(List.of(Rooms.AUDIT_CREATED, Rooms.AUDIT_ROLE_SET, Rooms.AUDIT_UPDATED), auditActions(r.id()));
    }

    @Test
    void deleteRemovesTheRoomAndUnreferencesItsQueue() throws Exception {
        Rooms.RoomView r = room(alice, "Doomed", Rooms.Visibility.PUBLIC);
        rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        rooms.setRole(alice, r.id(), carol.userId(), Rooms.Role.MEMBER, IP);
        String queued = item();
        String played = item();
        String playing = queueEntry(r.id(), queued, "queued");
        queueEntry(r.id(), played, "played");
        String current = queueEntry(r.id(), item(), "playing");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET current_entry_id = ? WHERE id = ?")) {
                ps.setString(1, current);
                ps.setString(2, r.id());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO votes (entry_id, voter_user_id, value, at)"
                    + " VALUES (?, ?, 1, 1)")) {
                ps.setString(1, playing);
                ps.setString(2, carol.userId());
                ps.executeUpdate();
            }
            return null;
        });

        expect(403, "forbidden", () -> rooms.delete(bob, r.id(), IP));
        expect(403, "forbidden", () -> rooms.delete(carol, r.id(), IP));
        clock.advance(5000);
        rooms.delete(alice, r.slug(), IP);
        expect(404, "room_not_found", () -> rooms.get(alice, r.id()));
        expect(404, "room_not_found", () -> rooms.delete(alice, r.id(), IP));
        assertEquals(0, count("SELECT COUNT(*) FROM room_members WHERE room_id = '" + r.id() + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM queue_entries WHERE room_id = '" + r.id() + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM votes"));
        assertEquals(clock.millis(), lastReferenced(queued), "a queued item is unreferenced now");
        assertEquals(1, lastReferenced(played), "a played entry wasn't a reference");
        assertEquals(List.of(Rooms.AUDIT_CREATED, Rooms.AUDIT_ROLE_SET, Rooms.AUDIT_ROLE_SET, Rooms.AUDIT_DELETED),
                auditActions(r.id()));

        // rooms.moderateAny may delete any room
        Rooms.RoomView other = room(bob, "Abusive", Rooms.Visibility.PRIVATE);
        rooms.delete(mod, other.id(), IP);
        expect(404, "room_not_found", () -> rooms.get(bob, other.id()));
    }

    private String item() throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source, added_by_user_id,"
                    + " added_at, status, output_profile, last_referenced_at) VALUES (?, 'audio', 'Song', 'upload', ?,"
                    + " 1, 'ready', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, alice.userId());
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String queueEntry(String roomId, String itemId, String state) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_at, state, position, pinned) VALUES (?, ?, ?, ?, 1, ?, 1, 0)")) {
                ps.setString(1, id);
                ps.setString(2, roomId);
                ps.setString(3, itemId);
                ps.setString(4, alice.userId());
                ps.setString(5, state);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private long count(String sql) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private long lastReferenced(String itemId) throws SQLException {
        return count("SELECT last_referenced_at FROM items WHERE id = '" + itemId + "'");
    }

    // ------------------------------------------------------------------ members

    @Test
    void ownerAssignsRoles() throws Exception {
        Rooms.RoomView r = room(alice, "Club", Rooms.Visibility.PUBLIC);
        Rooms.RoleSet set = rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        assertTrue(set.created());
        assertEquals(new Rooms.Member(io.siggi.togethertunes.auth.ActorRef.user(bob.userId(), "Bob"), "moderator",
                clock.millis()), set.member());
        clock.advance(10);
        Rooms.RoleSet again = rooms.setRole(alice, r.slug(), bob.userId(), Rooms.Role.MEMBER, IP);
        assertFalse(again.created());
        assertEquals("member", again.member().role());
        assertEquals(set.member().joinedAt(), again.member().joinedAt(), "joinedAt is kept");
        rooms.setRole(alice, r.id(), carol.userId(), Rooms.Role.BANNED, IP);

        expect(404, "user_not_found", () -> rooms.setRole(alice, r.id(), UuidV7.newId(), Rooms.Role.MEMBER, IP));
        expect(409, "cannot_change_own_role", () -> rooms.setRole(alice, r.id(), alice.userId(), Rooms.Role.MEMBER, IP));
        expect(403, "banned_from_room", () -> rooms.setRole(carol, r.id(), bob.userId(), Rooms.Role.MEMBER, IP));
        expect(403, "forbidden", () -> rooms.setRole(outsider(), r.id(), bob.userId(), Rooms.Role.MEMBER, IP));
        expect(403, "banned_from_room", () -> rooms.setRole(carol, r.id(), carol.userId(), Rooms.Role.MEMBER, IP));

        Rooms.MemberPage members = rooms.members(alice, r.id(), null, Rooms.LIST_MAX);
        assertEquals(List.of("owner", "member", "banned"), members.items().stream().map(Rooms.Member::role).toList());
        assertEquals(List.of(alice.userId(), bob.userId(), carol.userId()),
                members.items().stream().map(m -> m.user().id()).toList());
        assertNull(members.nextCursor());
    }

    @Test
    void moderatorsManageMembersAndBansOnly() throws Exception {
        Rooms.RoomView r = room(alice, "Hall", Rooms.Visibility.PUBLIC);
        Actor.User dave = user("dave", "Dave");
        rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        rooms.setRole(alice, r.id(), dave.userId(), Rooms.Role.MODERATOR, IP);

        // a moderator: member and banned for plain users
        assertEquals("banned", rooms.setRole(bob, r.id(), carol.userId(), Rooms.Role.BANNED, IP).member().role());
        assertEquals("member", rooms.setRole(bob, r.id(), carol.userId(), Rooms.Role.MEMBER, IP).member().role());
        expect(403, "forbidden", () -> rooms.setRole(bob, r.id(), carol.userId(), Rooms.Role.MODERATOR, IP));
        expect(403, "forbidden", () -> rooms.setRole(bob, r.id(), carol.userId(), Rooms.Role.OWNER, IP));
        expect(403, "forbidden", () -> rooms.setRole(bob, r.id(), dave.userId(), Rooms.Role.BANNED, IP));
        expect(403, "forbidden", () -> rooms.setRole(bob, r.id(), alice.userId(), Rooms.Role.BANNED, IP));
        expect(409, "cannot_change_own_role", () -> rooms.setRole(bob, r.id(), bob.userId(), Rooms.Role.MEMBER, IP));
        assertTrue(rooms.removeMember(bob, r.id(), carol.userId(), IP));
        expect(403, "forbidden", () -> rooms.removeMember(bob, r.id(), dave.userId(), IP));
        expect(403, "forbidden", () -> rooms.removeMember(bob, r.id(), alice.userId(), IP));
        assertFalse(rooms.removeMember(bob, r.id(), carol.userId(), IP), "no row any more");
        assertEquals(3, rooms.members(bob, r.id(), null, 10).items().size());

        // rooms.moderateAny acts as a moderator without a row
        assertEquals("banned", rooms.setRole(mod, r.id(), carol.userId(), Rooms.Role.BANNED, IP).member().role());
        expect(403, "forbidden", () -> rooms.setRole(mod, r.id(), carol.userId(), Rooms.Role.MODERATOR, IP));
        assertEquals(4, rooms.members(mod, r.id(), null, 10).items().size());

        // members and outsiders can't see the list or change roles
        Actor.User erin = user("erin", "Erin");
        rooms.setRole(alice, r.id(), erin.userId(), Rooms.Role.MEMBER, IP);
        expect(403, "forbidden", () -> rooms.members(erin, r.id(), null, 10));
        expect(403, "forbidden", () -> rooms.members(user("frank", "Frank"), r.id(), null, 10));
        expect(403, "forbidden", () -> rooms.setRole(erin, r.id(), carol.userId(), Rooms.Role.MEMBER, IP));
        expect(403, "forbidden", () -> rooms.removeMember(erin, r.id(), carol.userId(), IP));
        expect(403, "forbidden", () -> rooms.members(guest, r.id(), null, 10));
    }

    @Test
    void membersPageWithACursor() throws Exception {
        Rooms.RoomView r = room(alice, "Big", Rooms.Visibility.PRIVATE);
        List<Actor.User> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Actor.User u = user("user" + i, "User " + i);
            users.add(u);
            rooms.setRole(alice, r.id(), u.userId(),
                    i % 3 == 0 ? Rooms.Role.BANNED : i % 3 == 1 ? Rooms.Role.MEMBER : Rooms.Role.MODERATOR, IP);
            if (i % 2 == 0) {
                clock.advance(1);
            }
        }
        List<String> all = rooms.members(alice, r.id(), null, 50).items().stream().map(m -> m.user().id()).toList();
        assertEquals(7, all.size());
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            Rooms.MemberPage page = rooms.members(alice, r.id(), cursor, 2);
            page.items().forEach(m -> paged.add(m.user().id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(all, paged);
        List<String> roleOrder = rooms.members(alice, r.id(), null, 50).items().stream().map(Rooms.Member::role)
                .distinct().toList();
        assertEquals(List.of("owner", "moderator", "member", "banned"), roleOrder);
        for (String bad : List.of("x", "1_2", "a_1_x", "1_b_x", "1_2_")) {
            expect(400, "invalid_cursor", () -> rooms.members(alice, r.id(), bad, 2));
        }
    }

    @Test
    void ownershipTransfers() throws Exception {
        Rooms.RoomView r = room(alice, "Handover", Rooms.Visibility.PRIVATE);
        rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        expect(403, "forbidden", () -> rooms.setRole(bob, r.id(), carol.userId(), Rooms.Role.OWNER, IP));
        expect(403, "forbidden", () -> rooms.setRole(mod, r.id(), carol.userId(), Rooms.Role.OWNER, IP));
        disable(carol.userId());
        expect(409, "user_disabled", () -> rooms.setRole(alice, r.id(), carol.userId(), Rooms.Role.OWNER, IP));
        assertNull(storedRole(r.id(), carol.userId()), "the refused transfer changed nothing");

        Rooms.RoleSet set = rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.OWNER, IP);
        assertFalse(set.created());
        assertEquals("owner", set.member().role());
        assertEquals(bob.userId(), ownerColumn(r.id()));
        assertEquals("owner", storedRole(r.id(), bob.userId()));
        assertEquals("moderator", storedRole(r.id(), alice.userId()));
        Rooms.RoomView seen = rooms.get(bob, r.id());
        assertEquals("Bob", seen.owner().name());
        assertEquals("owner", seen.you().role());
        assertEquals("moderator", rooms.get(alice, r.id()).you().role());
        expect(403, "forbidden", () -> rooms.update(alice, r.id(), new Rooms.Edit("Back", null, null, null), IP));
        expect(403, "forbidden", () -> rooms.removeMember(alice, r.id(), bob.userId(), IP));

        // to a user who had no row: created; and the room moves between their listings
        Actor.User dave = user("dave", "Dave");
        assertTrue(rooms.setRole(bob, r.id(), dave.userId(), Rooms.Role.OWNER, IP).created());
        assertEquals(dave.userId(), ownerColumn(r.id()));
        assertEquals(1, rooms.members(dave, r.id(), null, 10).items().stream()
                .filter(m -> m.role().equals("owner")).count(), "exactly one owner");
        assertTrue(listedIds(dave).contains(r.id()));
        assertTrue(auditActions(r.id()).contains(Rooms.AUDIT_OWNER_TRANSFERRED));
    }

    @Test
    void usersLeaveRoomsButOwnersAndBannedUsersCant() throws Exception {
        Rooms.RoomView r = room(alice, "Leaving", Rooms.Visibility.PRIVATE);
        rooms.setRole(alice, r.id(), bob.userId(), Rooms.Role.MODERATOR, IP);
        rooms.setRole(alice, r.id(), carol.userId(), Rooms.Role.MEMBER, IP);
        assertTrue(rooms.removeMember(carol, r.id(), carol.userId(), IP));
        expect(404, "room_not_found", () -> rooms.get(carol, r.id()), "after leaving, the private room is gone");
        assertTrue(rooms.removeMember(bob, r.id(), bob.userId(), IP), "a moderator can step down");
        expect(409, "owner_cannot_leave", () -> rooms.removeMember(alice, r.id(), alice.userId(), IP));

        Rooms.RoomView pub = room(alice, "Banning", Rooms.Visibility.PUBLIC);
        rooms.setRole(alice, pub.id(), carol.userId(), Rooms.Role.BANNED, IP);
        expect(403, "banned_from_room", () -> rooms.removeMember(carol, pub.id(), carol.userId(), IP));
        assertTrue(rooms.removeMember(alice, pub.id(), carol.userId(), IP), "the owner lifts the ban");
        assertEquals(pub.id(), rooms.get(carol, pub.id()).id());
        assertTrue(auditActions(pub.id()).contains(Rooms.AUDIT_MEMBER_REMOVED));
    }

    /** A plain user with no role anywhere. */
    private Actor.User outsider() throws SQLException {
        return user("outsider" + UuidV7.newId().substring(30), "Outsider");
    }

    private static void expect(int status, String code, Executable e, String message) {
        HttpError err = assertThrows(HttpError.class, e, message);
        assertEquals(status, err.status(), message);
        assertEquals(code, err.code(), message);
    }
}
