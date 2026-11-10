package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.library.AddMediaPolicy;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guests in rooms on a real database ({@link RoomGuests}, {@link PresenceOps}): effective guest
 * capabilities (server AND room), the room half of guest uploads and YouTube links, guest bans
 * (who may ban, what a ban does, the IP-mode warning without the address) and the presence
 * counts (identified vs anonymous) with their events and the vote-skip re-check.
 */
abstract class RoomGuestsContract {
    private static final long T0 = 1_800_000_000_000L;
    private static final String GUEST_IP = "203.0.113.77";

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private final LinkedBlockingQueue<RoomChange> changes = new LinkedBlockingQueue<>();
    private Database db;
    private Settings settings;
    private Permissions permissions;
    private Rooms rooms;
    private RoomRegistry registry;
    private RoomGuests guests;
    private Actor.User admin; // holds every permission: ip.view, rooms.moderateAny
    private Actor.User alice; // owns the room
    private Actor.User bob; // moderator
    private Actor.User carol; // member
    private String roomId;
    private String roomSlug;
    private String guestId; // cookie mode
    private String ipGuestId; // IP mode
    private Actor.Guest guest;
    private Actor.Guest ipGuest;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        settings = new Settings(db, config);
        permissions = new Permissions(db, settings);
        rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        String carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        admin = new Actor.User(adminId, "s-admin", Actor.Via.BEARER);
        alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bob = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        carol = new Actor.User(carolId, "s-carol", Actor.Via.BEARER);
        Rooms.RoomView room = rooms.create(alice, "Party", null, Rooms.Visibility.PUBLIC, null, "203.0.113.9");
        roomId = room.id();
        roomSlug = room.slug();
        rooms.setRole(alice, roomId, bobId, Rooms.Role.MODERATOR, null);
        rooms.setRole(alice, roomId, carolId, Rooms.Role.MEMBER, null);
        guestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, ip_last, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', ?, '198.51.100.3', 'Quiet Walrus', 1, 1)", guestId, "hash-" + guestId);
        ipGuestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, ip_last, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'ip', ?, ?, 'Loud Heron', 1, 1)", ipGuestId, "hash-" + ipGuestId, GUEST_IP);
        guest = new Actor.Guest(guestId, GuestIdentity.COOKIE, false);
        ipGuest = new Actor.Guest(ipGuestId, GuestIdentity.IP, false);
        registry = new RoomRegistry(db, clock, changes::add);
        guests = new RoomGuests(db, settings, permissions, rooms, registry);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private void sql(String statement, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<Object> row(String query, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Object> out = new ArrayList<>();
                    while (rs.next()) {
                        for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                            out.add(rs.getObject(col));
                        }
                    }
                    return out;
                }
            }
        });
    }

    private long count(String query, Object... args) throws SQLException {
        return ((Number) row(query, args).get(0)).longValue();
    }

    private <T> T call(RoomMutation<T> m) throws Exception {
        return registry.call(roomId, m);
    }

    private long version() throws Exception {
        return call(tx -> tx.state().version());
    }

    private void roomSettings(String json) throws Exception {
        rooms.update(alice, roomId, new Rooms.Edit(null, null, null, null,
                com.google.gson.JsonParser.parseString(json).getAsJsonObject()), null);
    }

    private RoomSettings roomSettings() throws SQLException {
        return rooms.record(roomId).settings();
    }

    private static HttpError refused(Executable e) {
        return assertThrows(HttpError.class, e);
    }

    @Test
    void effectiveCapabilitiesAreTheServerAndTheRoomSetting() throws Exception {
        // Defaults: vote and library requests on (server and room), uploads and links off (server and room).
        assertEquals(new RoomGuests.Capabilities(true, true, false, false), guests.capabilities(guest, roomSettings()));
        String[] names = {"canVote", "canRequestLibrary", "canUpload", "canYoutube"};
        for (int i = 0; i < names.length; i++) {
            for (boolean server : new boolean[] {false, true}) {
                for (boolean room : new boolean[] {false, true}) {
                    settings.setJson("guests." + names[i], Boolean.toString(server));
                    roomSettings("{\"guests\":{\"" + names[i] + "\":" + room + "}}");
                    RoomGuests.Capabilities c = guests.capabilities(guest, roomSettings());
                    boolean[] got = {c.canVote(), c.canRequestLibrary(), c.canUpload(), c.canYoutube()};
                    assertEquals(server && room, got[i], names[i] + " server=" + server + " room=" + room);
                    assertEquals(c, guests.capabilities(Actor.ANONYMOUS, roomSettings()),
                            "an anonymous visitor would get the same once it is a guest");
                }
            }
        }
        // Everything on, then switched off at the server level in other ways.
        for (String n : names) {
            settings.setJson("guests." + n, "true");
        }
        roomSettings("{\"guests\":{\"canVote\":true,\"canRequestLibrary\":true,\"canUpload\":true,\"canYoutube\":true}}");
        RoomGuests.Capabilities all = new RoomGuests.Capabilities(true, true, true, true);
        assertEquals(all, guests.capabilities(guest, roomSettings()));
        assertTrue(all.canRequest());
        settings.setJson("ytdlp.enabled", "false");
        assertEquals(new RoomGuests.Capabilities(true, true, true, false), guests.capabilities(guest, roomSettings()),
                "YouTube links also need yt-dlp");
        settings.setJson("ytdlp.enabled", "true");
        assertEquals(RoomGuests.Capabilities.NONE, guests.capabilities(new Actor.Guest(guestId, GuestIdentity.COOKIE,
                true), roomSettings()), "banned server-wide");
        assertFalse(RoomGuests.Capabilities.NONE.canRequest());
        settings.setJson("guests.enabled", "false");
        assertEquals(RoomGuests.Capabilities.NONE, guests.capabilities(guest, roomSettings()), "guests disabled");
        assertThrows(IllegalArgumentException.class, () -> guests.capabilities(alice, roomSettings()));
    }

    @Test
    void roomActorsCarryRolesAndEffectiveCapabilities() throws Exception {
        RoomActor owner = guests.roomActor(alice, roomSlug);
        assertEquals(RoomActor.user(alice.userId(), Rooms.Role.OWNER), owner);
        assertTrue(owner.canUpload() && owner.canYoutube(), "the user role holds library.upload and library.youtube");
        assertEquals(Rooms.Role.MODERATOR, guests.roomActor(admin, roomId).role(), "rooms.moderateAny");
        settings.setJson("ytdlp.enabled", "false");
        assertFalse(guests.roomActor(carol, roomId).canYoutube());
        assertTrue(guests.roomActor(carol, roomId).canRequestLibrary());

        settings.setJson("guests.canUpload", "true");
        roomSettings("{\"guests\":{\"canUpload\":true,\"canVote\":false}}");
        RoomActor g = guests.roomActor(guest, roomId);
        assertEquals(new RoomActor(null, guestId, null, true, true, false, false), g);
        assertTrue(g.canRequest());
        ControlPolicy.Capabilities you = call(tx -> ControlPolicy.capabilities(tx.state(), g));
        assertEquals(new ControlPolicy.Capabilities(null, false, true, true, false, false), you);
        assertEquals(RoomActor.ANONYMOUS, guests.roomActor(Actor.ANONYMOUS, roomId));

        // Private rooms have no guests; a banned guest can't open the room.
        sql("UPDATE rooms SET visibility = 'private' WHERE id = ?", roomId);
        assertEquals("room_not_found", refused(() -> guests.roomActor(guest, roomId)).code());
        sql("UPDATE rooms SET visibility = 'unlisted' WHERE id = ?", roomId);
        guests.ban(alice, roomId, guestId, null);
        HttpError banned = refused(() -> guests.roomActor(guest, roomId));
        assertEquals(403, banned.status());
        assertEquals("banned_from_room", banned.code());
        assertEquals(RoomActor.guest(ipGuestId, new RoomGuests.Capabilities(false, true, true, false)),
                guests.roomActor(ipGuest, roomId), "another guest is not affected");
    }

    @Test
    void theRoomHalfOfGuestUploadsAndLinks() throws Exception {
        AddMediaPolicy policy = new AddMediaPolicy(permissions, settings, guests);
        settings.setJson("guests.canUpload", "true");
        settings.setJson("guests.canYoutube", "true");
        // The room setting is off by default.
        assertEquals("guest_uploads_disabled", refused(() -> policy.requireUpload(guest, roomSlug)).code());
        assertEquals("guest_youtube_disabled", refused(() -> policy.requireYoutube(guest, roomId)).code());
        roomSettings("{\"guests\":{\"canUpload\":true,\"canYoutube\":true}}");
        policy.requireUpload(guest, roomSlug);
        policy.requireYoutube(guest, roomId);
        policy.requireUpload(Actor.ANONYMOUS, roomId); // about to become a guest
        // The server half comes first.
        settings.setJson("guests.canUpload", "false");
        assertEquals("guest_uploads_disabled", refused(() -> policy.requireUpload(guest, roomId)).code());
        settings.setJson("guests.canUpload", "true");
        assertEquals("room_not_found", refused(() -> policy.requireUpload(guest, "no-such-room")).code());
        assertEquals("room_not_found", refused(() -> guests.require(UuidV7.newId(), guest,
                RoomGuestRules.Capability.UPLOAD)).code());
        guests.ban(bob, roomId, guestId, null);
        assertEquals("banned_from_room", refused(() -> policy.requireUpload(guest, roomId)).code());
        sql("UPDATE rooms SET visibility = 'private' WHERE id = ?", roomId);
        assertEquals("room_not_found", refused(() -> policy.requireYoutube(ipGuest, roomId)).code());
    }

    /**
     * Uploads need library requests: no mix of server and room switches lets a guest upload where
     * library requests are off, and the room's {@code uploadRequests} off refuses guest uploads.
     */
    @Test
    void guestUploadsNeedLibraryRequests() throws Exception {
        AddMediaPolicy policy = new AddMediaPolicy(permissions, settings, guests);
        settings.setJson("guests.canUpload", "true");
        roomSettings("{\"guests\":{\"canUpload\":true}}");
        policy.requireUpload(guest, roomId);
        assertTrue(guests.capabilities(guest, roomSettings()).canUpload());
        // a stored server state breaking the rule (the admin API never writes one) reads as upload off
        settings.setJson("guests.canRequestLibrary", "false");
        assertFalse(settings.guestsCanUpload());
        assertEquals(new RoomGuests.Capabilities(true, false, false, false), guests.capabilities(guest, roomSettings()));
        assertEquals("guest_uploads_disabled", refused(() -> policy.requireUpload(guest, roomId)).code());
        settings.setJson("guests.canRequestLibrary", "true");
        // the room: turning guest library requests off turns guest uploads off too
        roomSettings("{\"guests\":{\"canRequestLibrary\":false}}");
        assertFalse(roomSettings().guestsCanUpload());
        assertEquals("guest_uploads_disabled", refused(() -> policy.requireUpload(guest, roomId)).code());
        roomSettings("{\"guests\":{\"canUpload\":true}}");
        assertTrue(roomSettings().guestsCanRequestLibrary(), "turned back on with it");
        policy.requireUpload(guest, roomId);
        // the room's uploadRequests off (or libraryRequests off, which turns it off) refuses guest uploads
        roomSettings("{\"uploadRequests\":false}");
        assertEquals("upload_requests_off", refused(() -> policy.requireUpload(guest, roomId)).code());
        roomSettings("{\"uploadRequests\":true}");
        policy.requireUpload(guest, roomId);
        roomSettings("{\"libraryRequests\":false}");
        assertEquals("upload_requests_off", refused(() -> policy.requireUpload(guest, roomId)).code());
    }

    @Test
    void banLifecycle() throws Exception {
        call(tx -> {
            PresenceOps.join(tx, "w1", RoomActor.guest(guestId, true, true), RoomPresence.Mode.LISTEN);
            PresenceOps.join(tx, "w2", RoomActor.guest(guestId, true, true), RoomPresence.Mode.REMOTE);
            PresenceOps.join(tx, "w3", RoomActor.user(carol.userId(), Rooms.Role.MEMBER), RoomPresence.Mode.LISTEN);
            return null;
        });
        assertEquals(1, rooms.list(guest, null, 50).items().size(), "a public room is listed for the guest");
        long v = version();
        changes.clear();

        RoomGuests.BanSet set = guests.ban(alice, roomSlug, guestId, "192.0.2.1");
        assertTrue(set.created());
        RoomGuests.GuestBan ban = set.ban();
        assertEquals(guestId, ban.guest().id());
        assertEquals("Quiet Walrus", ban.guest().name());
        assertTrue(ban.guest().isGuest());
        assertEquals("cookie", ban.identifiedBy());
        assertTrue(ban.banned());
        assertEquals(T0, ban.bannedAt());
        assertNull(ban.warning(), "a cookie guest is one browser");
        assertEquals(v + 1, version());
        RoomChange change = changes.take();
        assertTrue(change.events().contains(new RoomEvent.GuestBanned(guestId, List.of("w1", "w2"))), change.toString());
        assertTrue(change.events().contains(new RoomEvent.PresenceChanged(new RoomPresence.Counts(1, 0, 0))));
        assertEquals(new RoomPresence.Counts(1, 0, 0), call(tx -> tx.state().presence().counts()));
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?",
                RoomGuests.AUDIT_BANNED, roomId));

        // Again: nothing changes.
        clock.advance(1000);
        RoomGuests.BanSet again = guests.ban(bob, roomId, guestId, null);
        assertFalse(again.created());
        assertEquals(T0, again.ban().bannedAt());
        assertEquals(v + 1, version());

        // The banned guest: no access, not listed; others are fine.
        assertEquals("banned_from_room", refused(() -> rooms.access(guest, roomId)).code());
        assertEquals("banned_from_room", refused(() -> rooms.get(guest, roomSlug)).code());
        assertTrue(rooms.list(guest, null, 50).items().isEmpty());
        assertEquals(1, rooms.list(ipGuest, null, 50).items().size());
        assertEquals(1, rooms.list(Actor.ANONYMOUS, null, 50).items().size());

        // Who may: owner, moderators, rooms.moderateAny; not members, guests or outsiders.
        assertEquals("forbidden", refused(() -> guests.ban(carol, roomId, ipGuestId, null)).code());
        assertEquals("forbidden", refused(() -> guests.list(carol, roomId, null, 50)).code());
        assertEquals("forbidden", refused(() -> guests.get(carol, roomId, guestId)).code());
        assertEquals("forbidden", refused(() -> guests.unban(carol, roomId, guestId, null)).code());
        assertEquals("forbidden", refused(() -> guests.ban(ipGuest, roomId, guestId, null)).code());
        assertTrue(guests.get(admin, roomId, guestId).banned());
        assertEquals("guest_not_found", refused(() -> guests.ban(alice, roomId, UuidV7.newId(), null)).code());
        assertEquals("guest_not_found", refused(() -> guests.get(alice, roomId, "nope")).code());
        assertEquals("room_not_found", refused(() -> guests.ban(alice, "no-such-room", guestId, null)).code());

        // The list, newest first, paged.
        clock.advance(1000);
        RoomGuests.BanSet second = guests.ban(admin, roomId, ipGuestId, null);
        assertTrue(second.created());
        RoomGuests.GuestBanPage page = guests.list(bob, roomSlug, null, 1);
        assertEquals(List.of(ipGuestId), page.items().stream().map(b -> b.guest().id()).toList());
        assertEquals(List.of(guestId), guests.list(bob, roomSlug, page.nextCursor(), 1).items().stream()
                .map(b -> b.guest().id()).toList());
        assertNull(guests.list(bob, roomSlug, page.nextCursor(), 1).nextCursor());
        assertEquals("invalid_cursor", refused(() -> guests.list(bob, roomId, "x", 10)).code());

        // Lifting the ban.
        changes.clear();
        assertTrue(guests.unban(bob, roomId, guestId, null));
        assertTrue(changes.take().events().contains(new RoomEvent.GuestUnbanned(guestId)));
        assertFalse(guests.unban(bob, roomId, guestId, null));
        assertFalse(guests.unban(bob, roomId, "nope", null));
        assertFalse(guests.get(alice, roomId, guestId).banned());
        assertNull(guests.get(alice, roomId, guestId).bannedAt());
        rooms.access(guest, roomId);
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_log WHERE action = ?", RoomGuests.AUDIT_UNBANNED));
        assertEquals(1L, count("SELECT COUNT(*) FROM room_guest_bans WHERE room_id = ?", roomId));

        // Deleting the room removes its bans.
        rooms.delete(alice, roomId, null);
        assertEquals(0L, count("SELECT COUNT(*) FROM room_guest_bans"));
    }

    @Test
    void ipModeBansWarnButNeverShowTheAddress() throws Exception {
        RoomGuests.GuestBan before = guests.get(alice, roomId, ipGuestId);
        assertFalse(before.banned());
        assertEquals("ip", before.identifiedBy());
        assertEquals(RoomGuests.IP_MODE_WARNING, before.warning(), "warned before banning");
        RoomGuests.GuestBan ban = guests.ban(alice, roomId, ipGuestId, null).ban();
        assertEquals(RoomGuests.IP_MODE_WARNING, ban.warning());
        String json = Json.GSON.toJson(List.of(before, ban, guests.list(alice, roomId, null, 50)));
        assertFalse(json.contains(GUEST_IP), json);
        assertFalse(RoomGuests.IP_MODE_WARNING.matches(".*\\d+\\.\\d+.*"));
        String audit = (String) row("SELECT details_json FROM audit_log WHERE action = ?", RoomGuests.AUDIT_BANNED)
                .get(0);
        assertFalse(audit.contains(GUEST_IP), audit);
        // The address only for ip.view.
        assertTrue(guests.ipsOf(alice, List.of(ipGuestId)).isEmpty());
        assertTrue(guests.ipsOf(bob, List.of(ipGuestId)).isEmpty());
        assertEquals(Map.of(ipGuestId, GUEST_IP), guests.ipsOf(admin, List.of(ipGuestId)));
    }

    @Test
    void presenceCountsIdentifiedAndAnonymousSeparately() throws Exception {
        RoomActor carolActor = RoomActor.user(carol.userId(), Rooms.Role.MEMBER);
        RoomActor guestActor = RoomActor.guest(guestId, true, true);
        long v = version();
        assertEquals(RoomPresence.Counts.NONE, call(tx -> tx.state().presence().counts()));

        assertEquals(new RoomPresence.Counts(0, 0, 1),
                call(tx -> PresenceOps.join(tx, "a1", RoomActor.ANONYMOUS, RoomPresence.Mode.LISTEN)));
        assertEquals(new RoomPresence.Counts(0, 0, 2),
                call(tx -> PresenceOps.join(tx, "a2", RoomActor.ANONYMOUS, RoomPresence.Mode.REMOTE)));
        assertEquals(new RoomPresence.Counts(0, 1, 2),
                call(tx -> PresenceOps.join(tx, "c1", carolActor, RoomPresence.Mode.REMOTE)));
        assertEquals(new RoomPresence.Counts(1, 0, 2),
                call(tx -> PresenceOps.join(tx, "c2", carolActor, RoomPresence.Mode.LISTEN)),
                "one user with a listening tab counts once, as listening");
        assertEquals(new RoomPresence.Counts(1, 1, 2),
                call(tx -> PresenceOps.join(tx, "g1", guestActor, RoomPresence.Mode.REMOTE)));
        assertEquals(v + 5, version(), "every change of the counts is a new version");
        assertEquals(2, (int) call(tx -> tx.state().presentIdentified()));

        changes.clear();
        // A third tab of the same user changes no count: no event, no version.
        assertEquals(new RoomPresence.Counts(1, 1, 2),
                call(tx -> PresenceOps.join(tx, "c3", carolActor, RoomPresence.Mode.LISTEN)));
        assertEquals(v + 5, version());
        assertTrue(changes.isEmpty());

        // An anonymous socket becomes a guest; mode switches; leaving.
        assertTrue(bool(tx -> PresenceOps.identify(tx, "a2", RoomActor.guest(ipGuestId, true, true))));
        assertEquals(new RoomPresence.Counts(1, 2, 1), call(tx -> tx.state().presence().counts()));
        assertEquals(new RoomEvent.PresenceChanged(new RoomPresence.Counts(1, 2, 1)), changes.take().events().get(0));
        assertTrue(bool(tx -> PresenceOps.mode(tx, "g1", RoomPresence.Mode.LISTEN)));
        assertEquals(new RoomPresence.Counts(2, 1, 1), call(tx -> tx.state().presence().counts()));
        assertTrue(bool(tx -> PresenceOps.leave(tx, "a1")));
        assertFalse(bool(tx -> PresenceOps.leave(tx, "a1")));
        assertFalse(bool(tx -> PresenceOps.mode(tx, "zz", RoomPresence.Mode.REMOTE)));
        assertFalse(bool(tx -> PresenceOps.identify(tx, "zz", guestActor)));
        assertEquals("identity_required", refused(() -> call(tx -> PresenceOps.identify(tx, "c1",
                RoomActor.ANONYMOUS))).code());
        assertEquals(new RoomPresence.Counts(2, 1, 0), call(tx -> tx.state().presence().counts()));

        // A failed mutation reloads the state from the database; the connections stay.
        assertThrows(IllegalStateException.class, () -> call(tx -> {
            tx.event(new RoomEvent.RoomUpdated());
            throw new IllegalStateException("bug");
        }));
        assertEquals(new RoomPresence.Counts(2, 1, 0), call(tx -> tx.state().presence().counts()));
        assertEquals(3, (int) call(tx -> tx.state().presence().identified()));
    }

    @Test
    void fewerMembersPresentCanReachTheVoteSkipThreshold() throws Exception {
        String itemId = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30', 1,"
                + " 60000)", itemId, alice.userId());
        RoomActor owner = RoomActor.user(alice.userId(), Rooms.Role.OWNER);
        call(tx -> QueueOps.add(tx, owner, itemId));
        call(tx -> QueueOps.add(tx, owner, itemId)); // owner is exempt from noRepeatWindow
        String first = call(tx -> tx.state().current().id());
        RoomActor guestActor = RoomActor.guest(guestId, true, true);
        call(tx -> {
            PresenceOps.join(tx, "o", owner, RoomPresence.Mode.LISTEN);
            PresenceOps.join(tx, "c", RoomActor.user(carol.userId(), Rooms.Role.MEMBER), RoomPresence.Mode.LISTEN);
            PresenceOps.join(tx, "g", guestActor, RoomPresence.Mode.REMOTE);
            PresenceOps.join(tx, "anon", RoomActor.ANONYMOUS, RoomPresence.Mode.LISTEN);
            return null;
        });
        // 3 identified present (the anonymous listener doesn't count): ceil(0.5 * 3) = 2 votes needed.
        assertEquals(new QueueControl.VoteSkip(1, 2, false), call(tx -> QueueControl.voteSkip(tx, guestActor)));
        assertTrue(bool(tx -> PresenceOps.leave(tx, "anon")));
        assertEquals(first, call(tx -> tx.state().current().id()), "an anonymous listener leaving changes nothing");
        call(tx -> PresenceOps.leave(tx, "c")); // 2 present: ceil(0.5 * 2) = 1 vote needed
        assertFalse(first.equals(call(tx -> tx.state().current().id())), "skipped when presence dropped");
        assertEquals("skipped", row("SELECT state FROM queue_entries WHERE id = ?", first).get(0));
    }

    private boolean bool(RoomMutation<Boolean> m) throws Exception {
        return call(m);
    }
}
