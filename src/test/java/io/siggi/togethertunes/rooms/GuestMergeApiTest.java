package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.auth.Tokens;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /api/me/merge-guest} over HTTP ({@link GuestMerge}): only a logged-in cookie session,
 * only the cookie guest this browser holds (never an IP-mode guest, never by bearer token), not a
 * banned one; the merge moves the guest's entries and clears the {@code gid} cookie, after which
 * the old cookie no longer names anyone.
 */
class GuestMergeApiTest {
    @TempDir
    Path dir;

    private Database db;
    private RoomRegistry registry;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Guests guests;
    private String bobId;
    private String bobSid;
    private String bobBearer;
    private String roomId;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        Rooms rooms = new Rooms(db, permissions, clock);
        RoomGuests roomGuests = new RoomGuests(db, settings, permissions, rooms, registry);
        new RoomsApi(rooms, registry, permissions, roomGuests, new RequestSearch(db, rooms, roomGuests),
                new RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock)).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        guests = new Guests(db, settings, false);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        bobSid = sessions.create(bobId, Actor.Via.COOKIE, "test", null).token();
        bobBearer = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        roomId = rooms.create(new Actor.User(aliceId, "s", Actor.Via.BEARER), "Party", null, null, null, null).id();
    }

    @AfterEach
    void tearDown() {
        server.close();
        registry.close();
        db.close();
    }

    private HttpResponse<String> call(String method, String bearer, String sid, String gid) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + "/api/me/merge-guest")).method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        List<String> cookies = new ArrayList<>();
        if (sid != null) {
            cookies.add("sid=" + sid);
        }
        if (gid != null) {
            cookies.add("gid=" + gid);
        }
        if (!cookies.isEmpty()) {
            b.header("Cookie", String.join("; ", cookies));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, json(r).getAsJsonObject("error").get("code").getAsString(), r.body());
    }

    private void sql(String statement, Object... args) throws Exception {
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

    private String one(String query, Object... args) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /** A queued entry of the guest in the room. */
    private String guestEntry(String guestId) throws Exception {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, added_by_guest_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30', 1,"
                + " 60000)", itemId, guestId);
        sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_guest_id, added_at, state, position, pinned)"
                + " VALUES (?, ?, ?, ?, 1, 'queued', 1, 0)", entryId, roomId, itemId, guestId);
        return entryId;
    }

    @Test
    void mergesTheCookieGuestOfThisBrowser() throws Exception {
        Guests.Created guest = guests.create(GuestIdentity.COOKIE, "203.0.113.5", null);
        String gid = guest.cookieToken();
        String guestId = guest.guest().id();
        String entry = guestEntry(guestId);
        sql("INSERT INTO votes (entry_id, voter_guest_id, value, at) VALUES (?, ?, 1, 1)", entry, guestId);
        JsonObject ref = JsonParser.parseString("{\"kind\":\"guest\",\"id\":\"" + guestId + "\",\"name\":\""
                + guest.guest().name() + "\",\"isGuest\":true}").getAsJsonObject();

        // The offer: a logged-in browser that holds the guest's cookie.
        HttpResponse<String> offer = call("GET", null, bobSid, gid);
        assertEquals(200, offer.statusCode(), offer.body());
        assertEquals("no-store", offer.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(ref, json(offer).get("guest"));
        assertFalse(json(call("GET", null, bobSid, null)).has("guest"), "no gid cookie");
        assertFalse(json(call("GET", bobBearer, null, gid)).has("guest"), "bearer: cookies don't count");
        expectError(401, "not_authenticated", call("GET", null, null, gid));

        // Refusals.
        expectError(401, "not_authenticated", call("POST", null, null, gid));
        expectError(403, "cookie_session_required", call("POST", bobBearer, null, gid));
        expectError(404, "guest_not_found", call("POST", null, bobSid, null));
        expectError(404, "guest_not_found", call("POST", null, bobSid, Tokens.newToken()));
        assertEquals(guestId, one("SELECT added_by_guest_id FROM queue_entries WHERE id = ?", entry), "nothing moved");

        // The merge.
        HttpResponse<String> merged = call("POST", null, bobSid, gid);
        assertEquals(200, merged.statusCode(), merged.body());
        assertEquals(ref, json(merged).get("guest"));
        assertEquals(JsonParser.parseString("{\"entries\":1,\"votes\":1,\"items\":1,\"requestedItems\":0}"), json(merged).get("moved"));
        assertEquals("gid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax",
                merged.headers().firstValue("Set-Cookie").orElse(""), "the gid cookie is cleared");
        assertEquals(bobId, one("SELECT added_by_user_id FROM queue_entries WHERE id = ?", entry));
        assertEquals(bobId, one("SELECT voter_user_id FROM votes WHERE entry_id = ?", entry));
        assertEquals("1", one("SELECT COUNT(*) FROM library_entries e JOIN queue_entries q ON q.item_id = e.item_id"
                + " WHERE q.id = ? AND e.user_id = ?", entry, bobId), "the guest's item is in Bob's library");
        assertEquals(bobId, registry.call(roomId, tx -> tx.state().queue().get(0).addedByUserId()),
                "the live room has it too");
        assertEquals("1", one("SELECT COUNT(*) FROM audit_log WHERE action = ? AND actor_user_id = ? AND target = ?",
                GuestMerge.AUDIT_MERGED, bobId, guestId));

        // The old cookie no longer names the guest: no offer, no second merge, not a guest any more.
        assertFalse(json(call("GET", null, bobSid, gid)).has("guest"));
        expectError(404, "guest_not_found", call("POST", null, bobSid, gid));
        assertEquals(guestId, one("SELECT id FROM guests WHERE id = ?", guestId), "the guest row stays");
    }

    @Test
    void neverAnIpGuestOrABannedOne() throws Exception {
        // An IP-mode guest can't be named by a cookie, even one whose hash matched its key.
        String token = Tokens.newToken();
        String ipGuestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'ip', ?, 'Shared Otter', 1, 1)", ipGuestId, Tokens.hash(token));
        String ipEntry = guestEntry(ipGuestId);
        assertFalse(json(call("GET", null, bobSid, token)).has("guest"));
        expectError(404, "guest_not_found", call("POST", null, bobSid, token));
        assertEquals(ipGuestId, one("SELECT added_by_guest_id FROM queue_entries WHERE id = ?", ipEntry));

        // A guest banned server-wide isn't offered and can't be merged.
        Guests.Created banned = guests.create(GuestIdentity.COOKIE, null, null);
        String bannedEntry = guestEntry(banned.guest().id());
        sql("UPDATE guests SET banned = 1 WHERE id = ?", banned.guest().id());
        assertFalse(json(call("GET", null, bobSid, banned.cookieToken())).has("guest"));
        expectError(403, "guest_banned", call("POST", null, bobSid, banned.cookieToken()));
        assertEquals(banned.guest().id(), one("SELECT added_by_guest_id FROM queue_entries WHERE id = ?", bannedEntry));
    }
}
