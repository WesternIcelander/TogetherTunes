package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.secrets.SecretCipher;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /api/rooms/{room}/outputs…} over HTTP ({@link StreamOutputsApi}, {@link StreamOutputs},
 * {@link StreamOutputControl}): who may see and change outputs, one per kind, the server switches,
 * tokens (128-bit base64url, rotation), RTMP keys (encrypted at rest, last 4 only), audit entries
 * without secrets, and what the control does with pushes, {@code .ts} connections and the program
 * output.
 */
class StreamOutputsApiTest {
    static final Pattern TS_URL = Pattern.compile("https://tunes\\.example/live/([A-Za-z0-9_-]{22})\\.ts");
    static final Pattern HLS_URL = Pattern.compile("https://tunes\\.example/live/([A-Za-z0-9_-]{22})/index\\.m3u8");
    static final String KEY = "live_123456789_abcdWXYZ";

    @TempDir
    Path dir;

    Database db;
    Settings settings;
    WebServer server;
    StreamOutputs rows;
    StreamOutputControl control;
    final HttpClient http = HttpClient.newHttpClient();
    final TestClock clock = new TestClock(1_800_000_000_000L);
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> pushes = new ConcurrentHashMap<>(); // roomId → url|key
    final AtomicInteger revalidations = new AtomicInteger();
    String alice; // owner
    String bob; // moderator
    String carol; // nobody in the room
    String roomId;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        Rooms rooms = new Rooms(db, permissions, clock);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        rows = new StreamOutputs(db, new SecretCipher(key), new SecureRandom(), clock);
        control = new StreamOutputControl(rows, new StreamOutputControl.Switches() {
            @Override
            public boolean rtmp() throws SQLException {
                return settings.outputsRtmpEnabled();
            }

            @Override
            public boolean ts() throws SQLException {
                return settings.outputsTsEnabled();
            }

            @Override
            public boolean hls() throws SQLException {
                return settings.outputsHlsEnabled();
            }
        }, new StreamOutputControl.Outputs() {
            @Override
            public void start(String id) {
                events.add("start");
            }

            @Override
            public void stop(String id) {
                events.add("stop");
            }
        }, new StreamOutputControl.Pushers() {
            @Override
            public void start(String id, String rtmpUrl, String k) {
                pushes.put(id, rtmpUrl + "|" + k);
                events.add("push");
            }

            @Override
            public void stop(String id) {
                pushes.remove(id);
                events.add("unpush");
            }

            @Override
            public RtmpPusher.Status status(String id) {
                return pushes.containsKey(id)
                        ? new RtmpPusher.Status(RtmpPusher.State.RETRYING, "Connection refused", 2, 5, 9) : null;
            }
        }, revalidations::incrementAndGet);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        new StreamOutputsApi(rooms, rows, control, config.publicBaseUrl()).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register"); // admin
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        String carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        carol = sessions.create(carolId, Actor.Via.BEARER, "test", null).token();
        Actor.User owner = new Actor.User(aliceId, "s", Actor.Via.BEARER);
        roomId = rooms.create(owner, "Club", "club", Rooms.Visibility.PUBLIC, null, "203.0.113.9").id();
        rooms.setRole(owner, roomId, bobId, Rooms.Role.MODERATOR, null);
    }

    @AfterEach
    void tearDown() {
        control.close();
        server.close();
        db.close();
    }

    HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    static void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, json(r).getAsJsonObject("error").get("code").getAsString(), r.body());
    }

    JsonObject create(String body) throws Exception {
        HttpResponse<String> r = call(alice, "POST", "/api/rooms/club/outputs", body);
        assertEquals(201, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return json(r);
    }

    static String token(Pattern p, JsonObject output) {
        Matcher m = p.matcher(output.get("url").getAsString());
        assertTrue(m.matches(), output.toString());
        return m.group(1);
    }

    String column(String sql) throws Exception {
        return db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    String auditText() throws Exception {
        return db.read(c -> {
            StringBuilder sb = new StringBuilder();
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT action, target, details_json FROM audit_log ORDER BY at, id")) {
                while (rs.next()) {
                    sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(' ')
                            .append(rs.getString(3)).append('\n');
                }
            }
            return sb.toString();
        });
    }

    @Test
    void tsAndHlsOutputsHaveTokenUrlsThatRotate() throws Exception {
        JsonObject ts = create("{\"kind\":\"ts\"}");
        String tsToken = token(TS_URL, ts);
        assertEquals(16, java.util.Base64.getUrlDecoder().decode(tsToken).length, "128 bits");
        assertEquals("ts", ts.get("kind").getAsString());
        assertTrue(ts.get("enabled").getAsBoolean());
        assertTrue(ts.get("serverEnabled").getAsBoolean());
        assertTrue(ts.get("active").getAsBoolean());
        assertEquals(clock.millis(), ts.get("createdAt").getAsLong());
        assertFalse(ts.has("rtmpUrl"));
        assertEquals(List.of("start"), events, "enabling starts the program output");
        assertEquals(tsToken, column("SELECT token FROM stream_outputs WHERE kind = 'ts'"));

        expectError(409, "output_exists", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"ts\"}"));
        JsonObject hls = create("{\"kind\":\"hls\",\"enabled\":false}");
        String hlsToken = token(HLS_URL, hls);
        assertNotEquals(tsToken, hlsToken);
        assertFalse(hls.get("active").getAsBoolean());

        // moderators read them (with the URLs), nobody else does
        JsonArray items = json(call(bob, "GET", "/api/rooms/club/outputs", null)).getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("ts", items.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(ts, json(call(bob, "GET", "/api/rooms/" + roomId + "/outputs/" + ts.get("id").getAsString(), null)));
        expectError(403, "forbidden", call(carol, "GET", "/api/rooms/club/outputs", null));
        expectError(401, "not_authenticated", call(null, "GET", "/api/rooms/club/outputs", null));
        expectError(404, "room_not_found", call(alice, "GET", "/api/rooms/nope/outputs", null));
        expectError(404, "output_not_found", call(alice, "GET", "/api/rooms/club/outputs/nope", null));
        // only the owner changes them
        String tsPath = "/api/rooms/club/outputs/" + ts.get("id").getAsString();
        expectError(403, "forbidden", call(bob, "POST", tsPath + "/rotate", null));
        expectError(403, "forbidden", call(bob, "PATCH", tsPath, "{\"enabled\":false}"));

        int before = revalidations.get();
        HttpResponse<String> rotated = call(alice, "POST", tsPath + "/rotate", null);
        assertEquals(200, rotated.statusCode(), rotated.body());
        String newToken = token(TS_URL, json(rotated));
        assertNotEquals(tsToken, newToken);
        assertEquals(newToken, column("SELECT token FROM stream_outputs WHERE kind = 'ts'"));
        assertTrue(revalidations.get() > before, "open .ts connections are re-checked");

        expectError(400, "invalid_field", call(alice, "PATCH", tsPath, "{\"rtmpKey\":\"x\"}"));
        expectError(400, "unknown_field", call(alice, "PATCH", tsPath, "{\"token\":\"x\"}"));
        expectError(400, "invalid_enabled", call(alice, "PATCH", tsPath, "{\"enabled\":\"yes\"}"));

        // turning the last active output off stops the program output at once
        events.clear();
        assertFalse(json(call(alice, "PATCH", tsPath, "{\"enabled\":false}")).get("enabled").getAsBoolean());
        assertEquals(List.of("stop"), events);
        assertEquals(204, call(alice, "DELETE", tsPath, null).statusCode());
        expectError(404, "output_not_found", call(alice, "DELETE", tsPath, null));
        assertEquals(1, json(call(alice, "GET", "/api/rooms/club/outputs", null)).getAsJsonArray("items").size());

        String audit = auditText();
        for (String action : List.of(StreamOutputs.AUDIT_CREATED, StreamOutputs.AUDIT_ROTATED,
                StreamOutputs.AUDIT_UPDATED, StreamOutputs.AUDIT_DELETED)) {
            assertTrue(audit.contains(action + " " + roomId), audit);
        }
        for (String secret : List.of(tsToken, newToken, hlsToken)) {
            assertFalse(audit.contains(secret), "a token in the audit log: " + audit);
        }
    }

    @Test
    void rtmpKeyIsEncryptedAndShownAsLast4Only() throws Exception {
        expectError(400, "invalid_rtmp_url", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"rtmp\"}"));
        for (String bad : List.of("http://ingest.example/live", "rtmp:///live", "rtmp://ingest example/live",
                "ingest.example/live")) {
            expectError(400, "invalid_rtmp_url", call(alice, "POST", "/api/rooms/club/outputs",
                    "{\"kind\":\"rtmp\",\"rtmpUrl\":\"" + bad + "\"}"));
        }
        expectError(400, "invalid_rtmp_key", call(alice, "POST", "/api/rooms/club/outputs",
                "{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\",\"rtmpKey\":\"has space\"}"));
        expectError(400, "invalid_kind", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"srt\"}"));
        expectError(400, "invalid_field", call(alice, "POST", "/api/rooms/club/outputs",
                "{\"kind\":\"ts\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}"));

        JsonObject rtmp = create("{\"kind\":\"rtmp\",\"rtmpUrl\":\" rtmps://ingest.example/live \",\"rtmpKey\":\""
                + KEY + "\"}");
        assertEquals("rtmps://ingest.example/live", rtmp.get("rtmpUrl").getAsString());
        assertTrue(rtmp.get("hasRtmpKey").getAsBoolean());
        assertEquals("WXYZ", rtmp.get("rtmpKeyLast4").getAsString());
        assertFalse(rtmp.has("url"));
        assertFalse(rtmp.toString().contains(KEY.substring(0, KEY.length() - 4)), rtmp.toString());
        // the push runs with the plaintext key; the status shows while it runs
        assertEquals("rtmps://ingest.example/live|" + KEY, pushes.get(roomId));
        assertEquals("{\"state\":\"retrying\",\"detail\":\"Connection refused\",\"failures\":2,\"since\":5,"
                + "\"retryAt\":9}", rtmp.get("status").toString());
        assertTrue(rtmp.get("rtmpAvailable").getAsBoolean());
        // at rest: encrypted, bound to the row
        String enc = column("SELECT rtmp_key_enc FROM stream_outputs WHERE kind = 'rtmp'");
        assertFalse(enc.contains(KEY));
        assertNull(column("SELECT token FROM stream_outputs WHERE kind = 'rtmp'"));

        String path = "/api/rooms/club/outputs/" + rtmp.get("id").getAsString();
        expectError(409, "no_token", call(alice, "POST", path + "/rotate", null));
        // a new key restarts the push; a short one shows nothing
        JsonObject changed = json(call(alice, "PATCH", path, "{\"rtmpKey\":\"short\"}"));
        assertTrue(changed.get("hasRtmpKey").getAsBoolean());
        assertFalse(changed.has("rtmpKeyLast4"));
        assertEquals("rtmps://ingest.example/live|short", pushes.get(roomId));
        // the same key again changes nothing
        events.clear();
        call(alice, "PATCH", path, "{\"rtmpKey\":\"short\"}");
        assertFalse(events.contains("push"), events.toString());
        changed = json(call(alice, "PATCH", path, "{\"rtmpKey\":null}"));
        assertFalse(changed.get("hasRtmpKey").getAsBoolean());
        assertEquals("rtmps://ingest.example/live|null", pushes.get(roomId));
        expectError(400, "invalid_rtmp_url", call(alice, "PATCH", path, "{\"rtmpUrl\":null}"));
        // off: the push stops, and with nothing else on, the program output too
        events.clear();
        call(alice, "PATCH", path, "{\"enabled\":false}");
        assertNull(pushes.get(roomId));
        assertEquals(List.of("unpush", "stop"), events);

        String audit = auditText();
        assertTrue(audit.contains("\"changed\":[\"rtmpKey\"]"), audit);
        assertFalse(audit.contains(KEY) || audit.contains("short") || audit.contains("ingest.example"), audit);
    }

    @Test
    void serverSwitchesGateOutputs() throws Exception {
        JsonObject rtmp = create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\",\"rtmpKey\":\""
                + KEY + "\"}");
        JsonObject ts = create("{\"kind\":\"ts\",\"enabled\":false}");
        assertTrue(pushes.containsKey(roomId));

        // the admin turns RTMP off: the push stops at the next reconcile, the row stays enabled
        settings.set(Settings.OUTPUTS_RTMP_ENABLED, false);
        control.reconcile();
        assertFalse(pushes.containsKey(roomId));
        JsonObject seen = json(call(alice, "GET", "/api/rooms/club/outputs/" + rtmp.get("id").getAsString(), null));
        assertTrue(seen.get("enabled").getAsBoolean());
        assertFalse(seen.get("serverEnabled").getAsBoolean());
        assertFalse(seen.get("active").getAsBoolean());
        assertFalse(seen.has("status"));
        // and back on: it starts again
        settings.set(Settings.OUTPUTS_RTMP_ENABLED, true);
        control.reconcile();
        assertEquals("rtmp://ingest.example/live|" + KEY, pushes.get(roomId));

        // with the .ts switch off, new .ts/... outputs and turning one on are refused; turning off works
        settings.set(Settings.OUTPUTS_TS_ENABLED, false);
        settings.set(Settings.OUTPUTS_HLS_ENABLED, false);
        expectError(403, "feature_disabled", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"hls\"}"));
        String tsPath = "/api/rooms/club/outputs/" + ts.get("id").getAsString();
        expectError(403, "feature_disabled", call(alice, "PATCH", tsPath, "{\"enabled\":true}"));
        assertEquals(200, call(alice, "PATCH", tsPath, "{\"enabled\":false}").statusCode());
        assertEquals(200, call(alice, "POST", tsPath + "/rotate", null).statusCode());
        int before = revalidations.get();
        control.reconcile();
        assertTrue(revalidations.get() > before, "reconcile re-checks the .ts connections");
    }

    @Test
    void reconcileStartsEnabledPushesAndDropsDeletedRooms() throws Exception {
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        control.close(); // a server restart: nothing runs
        assertTrue(pushes.isEmpty());
        control.reconcile();
        assertEquals("rtmp://ingest.example/live|null", pushes.get(roomId));
        // the room is deleted (rows cascade): its push stops
        db.write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("DELETE FROM rooms WHERE id = '" + roomId + "'");
            }
            return null;
        });
        control.reconcile();
        assertTrue(pushes.isEmpty());
    }

    @Test
    void tokensAreUnique128BitBase64Url() {
        StreamOutputs r = rows;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = r.newToken();
            assertTrue(t.matches("[A-Za-z0-9_-]{22}"), t);
            assertTrue(seen.add(t));
        }
    }
}
