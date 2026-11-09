package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.GuestNames;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.rooms.RoomGuestRules;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.settings.UploadQuota;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Who may add media ({@link AddMediaPolicy}) and the guest upload quota ({@link GuestUploadQuota})
 * over HTTP. Rooms don't exist yet, so the server's room half is {@link RoomGuestRules#NO_ROOMS};
 * these tests plug in a room rule that lets a known room through (and records what it was asked),
 * to exercise the guest path behind it.
 */
abstract class GuestUploadsContract {
    static final long MAX_BYTES = 20_000_000;
    static final String IP_A = "203.0.113.10";
    static final String IP_B = "198.51.100.20";
    static final String IP_C = "192.0.2.30";

    @TempDir
    Path dir;

    protected Database db;
    private Settings settings;
    private Permissions permissions;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebServer> servers = new ArrayList<>();
    private Uploads uploads;
    private WebServer server;
    /** What the test room rule was asked: "roomId capability actor-class". */
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private String roomId;
    private String closedRoomId;
    private String alice;
    private String aliceId;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db);
        permissions = new Permissions(db, settings);
        DataDir data = DataDir.create(dir.resolve("data"));
        uploads = new Uploads(db, data, new Tools(null, null, null), new IngestJobs(db, clock), () -> {
        }, OutputProfile.P720_30, MAX_BYTES, 600, new GuestUploadQuota(db, settings, clock), clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        accounts.createLocal("root", null, "root-password", true, null, "account.setup"); // the admin
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = new Sessions(db, clock).create(aliceId, Actor.Via.BEARER, null, null).token();
        roomId = insertRoom("open-room");
        closedRoomId = insertRoom("closed-room");
        RoomGuestRules rules = (room, guest, capability) -> {
            asked.add(room + " " + capability + " " + guest.getClass().getSimpleName());
            if (room.equals(closedRoomId)) {
                throw new HttpError(403, "room_guest_uploads_disabled", "Not in this room");
            }
            if (!room.equals(roomId)) {
                throw new HttpError(404, "room_not_found", "No such room");
            }
        };
        server = start(rules);
    }

    @AfterEach
    void tearDown() {
        for (WebServer s : servers) {
            s.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private WebServer start(RoomGuestRules rules) throws Exception {
        Router router = new Router(new ActorResolver(db, settings, clock).hook());
        AddMediaPolicy policy = new AddMediaPolicy(permissions, settings, rules);
        new UploadsApi(uploads, policy, new Guests(db, settings, clock, false, GuestNames.bundled())).register(router);
        WebServer s = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        servers.add(s);
        return s;
    }

    private String insertRoom(String slug) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO rooms (id, slug, name, owner_id, created_at,"
                    + " visibility, control_mode, settings_json, playback_state) VALUES (?, ?, ?, ?, 1, 'public',"
                    + " 'owner', '{}', 'paused')")) {
                ps.setString(1, id);
                ps.setString(2, slug);
                ps.setString(3, slug);
                ps.setString(4, aliceId);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    // ------------------------------------------------------------------ HTTP helpers

    /** A caller: a bearer token, or a {@code gid} cookie (updated from responses), and an IP. */
    final class Caller {
        final String token;
        String gid;
        String ip;

        Caller(String token, String ip) {
            this.token = token;
            this.ip = ip;
        }
    }

    record Reply(int status, String body, HttpResponse<String> raw) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            try {
                return json().getAsJsonObject("error").get("code").getAsString();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private Reply call(WebServer to, Caller who, String method, String path, String body, String contentRange)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("X-Forwarded-For", who.ip); // the loopback peer is a trusted proxy
        if (who.token != null) {
            b.header("Authorization", "Bearer " + who.token);
        }
        if (who.gid != null) {
            b.header("Cookie", who.gid);
        }
        if (contentRange != null) {
            b.header("Content-Range", contentRange).header("Content-Type", "application/octet-stream");
        } else if (body != null) {
            b.header("Content-Type", "application/json");
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("gid=")) {
                who.gid = c.substring(0, c.indexOf(';'));
            }
        }
        assertTrue(!r.body().contains(who.ip), "no IP in responses: " + r.body());
        return new Reply(r.statusCode(), r.body(), r);
    }

    private Reply start(Caller who, String room, long size) throws Exception {
        return call(server, who, "POST", "/api/uploads", "{\"filename\":\"song.mp3\",\"size\":" + size
                + (room == null ? "" : ",\"roomId\":\"" + room + "\"") + "}", null);
    }

    private String started(Caller who) throws Exception {
        Reply r = start(who, roomId, 10);
        assertEquals(201, r.status(), r.body());
        return r.json().get("uploadId").getAsString();
    }

    private static void assertError(int status, String code, Reply r) {
        assertEquals(status, r.status(), r.body());
        assertEquals(code, r.error(), r.body());
    }

    private Caller anonymous(String ip) {
        return new Caller(null, ip);
    }

    private void guestUploads(boolean on) throws SQLException {
        settings.set(Settings.GUESTS_CAN_UPLOAD, on);
    }

    private long count(String sql, String... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private long guests() throws SQLException {
        return count("SELECT COUNT(*) FROM guests");
    }

    // ------------------------------------------------------------------ who may upload

    @Test
    void guestsUploadOnlyAsARequestInARoom() throws Exception {
        guestUploads(true);
        Caller anon = anonymous(IP_A);
        assertError(401, "not_authenticated", start(anon, null, 10));
        assertEquals(0, guests(), "a refused visitor doesn't become a guest");

        // Main's stub: no rooms exist yet, so even an allowed guest gets 404 for any room.
        WebServer stub = start(RoomGuestRules.NO_ROOMS);
        assertError(404, "room_not_found", call(stub, anon, "POST", "/api/uploads",
                "{\"filename\":\"a.mp3\",\"size\":10,\"roomId\":\"" + roomId + "\"}", null));
        assertEquals(0, guests());

        // With a room that allows it, the visitor becomes a guest and owns the upload.
        Reply r = start(anon, roomId, 10);
        assertEquals(201, r.status(), r.body());
        assertNotNull(anon.gid, "cookie mode sets gid");
        assertEquals(1, guests());
        String id = r.json().get("uploadId").getAsString();
        assertEquals(1, count("SELECT COUNT(*) FROM uploads WHERE id = ? AND guest_id IS NOT NULL AND user_id IS NULL"
                + " AND room_id = ?", id, roomId));
        assertEquals(List.of(roomId + " UPLOAD Anonymous"), asked);

        // The guest continues its own upload; nobody else sees it.
        assertEquals(200, call(server, anon, "PUT", "/api/uploads/" + id, "hello", "bytes 0-4/10").status());
        Reply status = call(server, anon, "GET", "/api/uploads/" + id, null, null);
        assertEquals(5, status.json().get("receivedBytes").getAsLong(), status.body());
        assertError(401, "not_authenticated", call(server, anonymous(IP_A), "GET", "/api/uploads/" + id, null, null));
        Caller other = anonymous(IP_B);
        assertEquals(201, start(other, roomId, 10).status());
        assertError(404, "upload_not_found", call(server, other, "GET", "/api/uploads/" + id, null, null));
        assertError(404, "upload_not_found", call(server, new Caller(alice, IP_C), "GET", "/api/uploads/" + id, null, null));

        // Without a room, a guest is still refused.
        assertError(401, "not_authenticated", start(anon, null, 10));
        // The room has its say after the server: unknown and closed rooms.
        assertError(404, "room_not_found", start(anon, UuidV7.newId(), 10));
        assertError(403, "room_guest_uploads_disabled", start(anon, closedRoomId, 10));
    }

    @Test
    void serverGuestRulesComeBeforeTheRoom() throws Exception {
        Caller anon = anonymous(IP_A);
        // guests.canUpload is off by default.
        assertError(403, "guest_uploads_disabled", start(anon, roomId, 10));
        guestUploads(true);
        settings.set(Settings.GUESTS_ENABLED, false);
        assertError(403, "guests_disabled", start(anon, roomId, 10));
        assertTrue(asked.isEmpty(), "the room isn't asked when the server says no: " + asked);
        assertEquals(0, guests());

        settings.set(Settings.GUESTS_ENABLED, true);
        String id = started(anon);
        assertEquals(List.of(roomId + " UPLOAD Anonymous"), asked);
        assertEquals(201, start(anon, roomId, 10).status());
        assertEquals(roomId + " UPLOAD Guest", asked.get(1));

        // Switching guest uploads off stops uploads in progress too.
        guestUploads(false);
        assertError(403, "guest_uploads_disabled", call(server, anon, "PUT", "/api/uploads/" + id, "hello", "bytes 0-4/10"));
        assertError(403, "guest_uploads_disabled", call(server, anon, "GET", "/api/uploads/" + id, null, null));
        assertError(403, "guest_uploads_disabled", call(server, anon, "POST", "/api/uploads/" + id + "/complete", null, null));
        guestUploads(true);
        assertEquals(200, call(server, anon, "GET", "/api/uploads/" + id, null, null).status());

        // A banned guest can do neither.
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE guests SET banned = 1")) {
                ps.executeUpdate();
            }
            return null;
        });
        assertError(403, "guest_banned", start(anon, roomId, 10));
        assertError(403, "guest_banned", call(server, anon, "GET", "/api/uploads/" + id, null, null));

        // IP mode: the guest is the IP, no cookie.
        settings.set(Settings.GUESTS_IDENTIFY_BY, GuestIdentity.IP);
        Caller byIp = anonymous(IP_C);
        String ipUpload = started(byIp);
        assertNull(byIp.gid);
        assertEquals(200, call(server, byIp, "GET", "/api/uploads/" + ipUpload, null, null).status());
    }

    @Test
    void usersNeedLibraryUploadAndHaveNoQuota() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(1, 100));
        Caller a = new Caller(alice, IP_A);
        for (int i = 0; i < 3; i++) {
            assertEquals(201, call(server, a, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":5000}", null)
                    .status());
        }
        // Nor do they share the IP's guest quota.
        assertEquals(201, start(anonymous(IP_A), roomId, 10).status());
        assertEquals(0, count("SELECT COUNT(*) FROM guest_upload_starts WHERE guest_id IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM guest_upload_starts"));

        // Without library.upload: 403 forbidden, also with a room (user room requests come in M3).
        Roles roles = new Roles(db);
        Roles.Role user = roles.list().stream().filter(r -> r.name().equals(BuiltinRoles.USER)).findFirst().orElseThrow();
        roles.setPermissions(user.id(), List.of());
        assertError(403, "forbidden", call(server, a, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":5}", null));
        assertError(403, "forbidden", call(server, a, "POST", "/api/uploads",
                "{\"filename\":\"a.mp3\",\"size\":5,\"roomId\":\"" + roomId + "\"}", null));
    }

    // ------------------------------------------------------------------ quota

    @Test
    void quotaCountsPerGuestAndPerIp() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(2, 1000));

        // Per guest: one guest moving between IPs is still one guest.
        Caller g = anonymous(IP_A);
        started(g);
        clock.advance(60_000);
        g.ip = IP_B;
        started(g);
        g.ip = IP_C;
        Reply over = start(g, roomId, 10);
        assertError(429, "upload_quota_exceeded", over);
        assertEquals(String.valueOf(3540), over.raw().headers().firstValue("Retry-After").orElse(null),
                "until the first of the two is an hour old");

        // Per IP: IP_A has one upload; one more from another guest there fills it.
        Caller h = anonymous(IP_A);
        started(h);
        long guestsBefore = guests();
        Caller k = anonymous(IP_A);
        assertError(429, "upload_quota_exceeded", start(k, roomId, 10));
        assertEquals(guestsBefore, guests(), "a visitor over the IP's quota doesn't become a guest");
        assertNull(k.gid);
        // ... and the guest who has room left is held back by the IP too.
        assertError(429, "upload_quota_exceeded", start(h, roomId, 10));
        h.ip = IP_C;
        started(h);

        // Refused attempts don't count: g 2, h 2.
        assertEquals(4, count("SELECT COUNT(*) FROM guest_upload_starts"));

        // The window rolls: an hour after g's first upload, g has room for one again.
        clock.advance(3_540_000 - 1);
        assertError(429, "upload_quota_exceeded", start(g, roomId, 10));
        clock.advance(1);
        started(g);
        assertError(429, "upload_quota_exceeded", start(g, roomId, 10));
    }

    @Test
    void ipv6CountsByTheSlash64() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(1, 1000));
        started(anonymous("2001:db8:1:2::1"));
        assertError(429, "upload_quota_exceeded", start(anonymous("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), roomId, 10));
        started(anonymous("2001:db8:1:3::1"));
        // IPv4-mapped is the IPv4 address.
        started(anonymous(IP_A));
        assertError(429, "upload_quota_exceeded", start(anonymous("::ffff:" + IP_A), roomId, 10));
        // No IP address is stored, only hashes of the keys.
        assertEquals(0, count("SELECT COUNT(*) FROM guest_upload_starts WHERE ip_key_hash LIKE '%:%'"
                + " OR ip_key_hash LIKE '%.%'"));
        assertEquals(3, count("SELECT COUNT(DISTINCT ip_key_hash) FROM guest_upload_starts"));
    }

    @Test
    void sizeLimitAndZeroQuota() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(3, 1000));
        Caller g = anonymous(IP_A);
        assertError(413, "payload_too_large", start(g, roomId, 1001));
        assertEquals(0, guests(), "refused before the visitor becomes a guest");
        assertEquals(201, start(g, roomId, 1000).status());
        assertError(413, "payload_too_large", start(g, roomId, 1001));
        // The server's own limit still applies, and bad requests don't use up the quota.
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(3, MAX_BYTES * 2));
        assertError(413, "payload_too_large", start(g, roomId, MAX_BYTES + 1));
        assertError(400, "invalid_filename", call(server, anonymous(IP_B), "POST", "/api/uploads",
                "{\"filename\":\"  \",\"size\":10,\"roomId\":\"" + roomId + "\"}", null));
        assertEquals(1, guests());
        assertEquals(1, count("SELECT COUNT(*) FROM guest_upload_starts"));

        // perHour 0: guests can't upload at all.
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(0, 1000));
        assertError(403, "guest_uploads_disabled", start(g, roomId, 10));
    }

    @Test
    void abandonedAndRefusedUploadsStillCount() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(2, 1000));
        Caller g = anonymous(IP_A);
        String first = started(g);
        // An upload whose row is gone (refused, or swept) still counts within the hour.
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM uploads WHERE id = ?")) {
                ps.setString(1, first);
                ps.executeUpdate();
            }
            return null;
        });
        started(g);
        assertError(429, "upload_quota_exceeded", start(g, roomId, 10));

        // The sweep drops starts once they no longer count.
        clock.advance(GuestUploadQuota.WINDOW.toMillis() - 1);
        assertEquals(0, uploads.guestQuota().sweep());
        clock.advance(1);
        assertEquals(2, uploads.guestQuota().sweep());
        assertEquals(0, count("SELECT COUNT(*) FROM guest_upload_starts"));
        started(g);
    }

    @Test
    void concurrentStartsCantOvershoot() throws Exception {
        guestUploads(true);
        settings.set(Settings.GUESTS_UPLOAD_QUOTA, new UploadQuota(3, 1000));
        Caller g = anonymous(IP_A);
        started(g); // the guest exists, so every request below is the same guest
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Caller same = new Caller(null, i % 2 == 0 ? IP_B : IP_C);
            same.gid = g.gid;
            tasks.add(() -> start(same, roomId, 10).status());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int created = 0;
            int refused = 0;
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                int status = f.get();
                if (status == 201) {
                    created++;
                } else {
                    assertEquals(429, status);
                    refused++;
                }
            }
            assertEquals(2, created);
            assertEquals(6, refused);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, count("SELECT COUNT(*) FROM uploads"));
    }

    // ------------------------------------------------------------------ YouTube

    @Test
    void youtubePolicy() throws Exception {
        AddMediaPolicy policy = new AddMediaPolicy(permissions, settings, (room, guest, capability) -> {
            asked.add(room + " " + capability);
            if (!room.equals(roomId)) {
                throw new HttpError(404, "room_not_found", "No such room");
            }
        });
        Actor user = new Actor.User(aliceId, "session", Actor.Via.BEARER);
        Actor.Guest guest = new Actor.Guest(UuidV7.newId(), GuestIdentity.COOKIE, false);

        policy.requireYoutube(user, null);
        assertCode(401, "not_authenticated", () -> policy.requireYoutube(guest, null));
        assertCode(401, "not_authenticated", () -> policy.requireYoutube(Actor.ANONYMOUS, null));
        assertCode(403, "guest_youtube_disabled", () -> policy.requireYoutube(guest, roomId));
        settings.set(Settings.GUESTS_CAN_YOUTUBE, true);
        policy.requireYoutube(guest, roomId);
        policy.requireYoutube(Actor.ANONYMOUS, roomId);
        assertCode(404, "room_not_found", () -> policy.requireYoutube(guest, UuidV7.newId()));
        assertEquals(List.of(roomId + " YOUTUBE", roomId + " YOUTUBE"), asked.subList(0, 2));
        // Guest uploads and guest YouTube links are separate switches.
        assertCode(403, "guest_uploads_disabled", () -> policy.requireUpload(guest, roomId));
        assertCode(403, "guest_banned",
                () -> policy.requireYoutube(new Actor.Guest(guest.guestId(), GuestIdentity.COOKIE, true), roomId));
        settings.set(Settings.GUESTS_ENABLED, false);
        assertCode(403, "guests_disabled", () -> policy.requireYoutube(guest, roomId));
        settings.set(Settings.GUESTS_ENABLED, true);

        // ytdlp.enabled off: nobody, admins and guests included, and before anything else.
        settings.set(Settings.YTDLP_ENABLED, false);
        assertCode(403, "feature_disabled", () -> policy.requireYoutube(user, null));
        assertCode(403, "feature_disabled", () -> policy.requireYoutube(guest, roomId));
        assertCode(403, "feature_disabled", () -> policy.requireYoutube(Actor.ANONYMOUS, null));
        settings.set(Settings.YTDLP_ENABLED, true);

        // A user without library.youtube.
        Roles roles = new Roles(db);
        Roles.Role role = roles.list().stream().filter(r -> r.name().equals(BuiltinRoles.USER)).findFirst().orElseThrow();
        roles.setPermissions(role.id(), List.of());
        assertCode(403, "forbidden", () -> policy.requireYoutube(user, null));
        assertCode(403, "forbidden", () -> policy.requireUpload(user, null));
    }

    interface Check {
        void run() throws Exception;
    }

    private static void assertCode(int status, String code, Check check) {
        HttpError e = assertThrows(HttpError.class, check::run);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
    }
}
