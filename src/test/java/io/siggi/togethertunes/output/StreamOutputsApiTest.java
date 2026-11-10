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
 * tokens (128-bit base64url, rotation), RTMP target and key (the owner's only), audit entries
 * without secrets, and what the control does with pushes, {@code .ts} connections and the program
 * output.
 */
class StreamOutputsApiTest {
    static final Pattern TS_URL = Pattern.compile("https://tunes\\.example/live/([A-Za-z0-9_-]{22})\\.ts");
    static final Pattern HLS_URL = Pattern.compile("https://tunes\\.example/live/([A-Za-z0-9_-]{22})/index\\.m3u8");
    static final String KEY = "live_123456789_abcdWXYZ";
    /** A push detail as RtmpPusher makes it (key masked to its last 4, URL credentials masked). */
    static final String DETAIL = "Error opening output rtmps://***@ingest.example/live/…WXYZ: Connection refused";

    @TempDir
    Path dir;

    Database db;
    Settings settings;
    WebServer server;
    StreamOutputs rows;
    StreamOutputControl control;
    OutputAutoOff autoOff;
    Rooms rooms;
    Actor.User owner;
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
        rooms = new Rooms(db, permissions, clock);
        rows = new StreamOutputs(db, new SecureRandom(), clock);
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

            @Override
            public boolean mp3() throws SQLException {
                return settings.outputsMp3Enabled();
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
                        ? new RtmpPusher.Status(RtmpPusher.State.RETRYING, DETAIL, 2, 5, 9) : null;
            }
        }, revalidations::incrementAndGet);
        autoOff = new OutputAutoOff(rows, id -> {
            var rs = rooms.record(id).settings();
            return OutputAutoOff.policy(rs.autoOff(), rs.autoOffMinutes(), settings.outputsForceAutoOff(),
                    settings.outputsMaxAutoOffMinutes());
        }, clock::millis);
        control.autoOff(autoOff);
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
        owner = new Actor.User(aliceId, "s", Actor.Via.BEARER);
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

    /** Changes the room's visibility as {@code PATCH /api/rooms/{room}} does (with the outputs' follow-up). */
    void visibility(Rooms.Visibility v) throws Exception {
        boolean wasPrivate = rooms.record(roomId).visibility() == Rooms.Visibility.PRIVATE;
        rooms.update(owner, roomId, new Rooms.Edit(null, null, v, null), null);
        control.roomEdited(roomId, v == Rooms.Visibility.PRIVATE && !wasPrivate);
        events.clear();
    }

    @Test
    void tsAndHlsOutputsHaveTokenUrlsThatRotate() throws Exception {
        visibility(Rooms.Visibility.PRIVATE);
        JsonObject ts = create("{\"kind\":\"ts\"}");
        String tsToken = token(TS_URL, ts);
        assertEquals(16, java.util.Base64.getUrlDecoder().decode(tsToken).length, "128 bits");
        assertEquals("ts", ts.get("kind").getAsString());
        assertTrue(ts.get("enabled").getAsBoolean());
        assertTrue(ts.get("serverEnabled").getAsBoolean());
        assertTrue(ts.get("active").getAsBoolean());
        assertEquals(clock.millis(), ts.get("createdAt").getAsLong());
        assertEquals("token", ts.get("urlKind").getAsString());
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
        expectError(404, "room_not_found", call(carol, "GET", "/api/rooms/club/outputs", null)); // private
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
    void rtmpTargetAndKeyAreTheOwnersOnly() throws Exception {
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
        assertFalse(rtmp.has("rtmpKeyLast4"));
        assertFalse(rtmp.has("url"));
        // the owner gets the full key; moderators nothing about the key or the ingest URL
        assertEquals(KEY, rtmp.get("rtmpKey").getAsString());
        String rtmpId = rtmp.get("id").getAsString();
        assertEquals(KEY, json(call(alice, "GET", "/api/rooms/club/outputs/" + rtmpId, null)).get("rtmpKey").getAsString());
        JsonObject modView = json(call(bob, "GET", "/api/rooms/club/outputs/" + rtmpId, null));
        for (String field : List.of("rtmpKey", "hasRtmpKey", "rtmpKeyLast4", "rtmpUrl")) {
            assertFalse(modView.has(field), field + ": " + modView);
        }
        assertEquals("retrying", modView.getAsJsonObject("status").get("state").getAsString());
        // the push detail without the URL or key hint
        assertEquals("Error opening output (ingest URL): Connection refused",
                modView.getAsJsonObject("status").get("detail").getAsString());
        String modList = call(bob, "GET", "/api/rooms/club/outputs", null).body();
        for (String leak : List.of("WXYZ", "ingest.example", "rtmpKey", "rtmpUrl")) {
            assertFalse(modList.contains(leak), leak + ": " + modList);
        }
        assertTrue(call(alice, "GET", "/api/rooms/club/outputs", null).body().contains(KEY));
        expectError(403, "forbidden", call(carol, "GET", "/api/rooms/club/outputs/" + rtmpId, null));
        // the push runs with the plaintext key; the status shows while it runs
        assertEquals("rtmps://ingest.example/live|" + KEY, pushes.get(roomId));
        assertEquals("{\"state\":\"retrying\",\"detail\":\"" + DETAIL + "\",\"failures\":2,\"since\":5,"
                + "\"retryAt\":9}", rtmp.get("status").toString());
        assertTrue(rtmp.get("rtmpAvailable").getAsBoolean());
        // at rest: plain text in rtmp_key; the old encrypted column stays unused
        assertEquals(KEY, column("SELECT rtmp_key FROM stream_outputs WHERE kind = 'rtmp'"));
        assertNull(column("SELECT rtmp_key_enc FROM stream_outputs WHERE kind = 'rtmp'"));
        assertNull(column("SELECT token FROM stream_outputs WHERE kind = 'rtmp'"));

        String path = "/api/rooms/club/outputs/" + rtmp.get("id").getAsString();
        expectError(409, "no_token", call(alice, "POST", path + "/rotate", null));
        // a new key restarts the push; a short one shows nothing
        JsonObject changed = json(call(alice, "PATCH", path, "{\"rtmpKey\":\"short\"}"));
        assertTrue(changed.get("hasRtmpKey").getAsBoolean());
        assertFalse(changed.has("rtmpKeyLast4"));
        assertEquals("short", changed.get("rtmpKey").getAsString());
        assertEquals("rtmps://ingest.example/live|short", pushes.get(roomId));
        // the same key again changes nothing
        events.clear();
        call(alice, "PATCH", path, "{\"rtmpKey\":\"short\"}");
        assertFalse(events.contains("push"), events.toString());
        changed = json(call(alice, "PATCH", path, "{\"rtmpKey\":null}"));
        assertFalse(changed.get("hasRtmpKey").getAsBoolean());
        assertFalse(changed.has("rtmpKey"));
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
        visibility(Rooms.Visibility.PRIVATE);
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
    void mp3OutputHasATokenUrlAndItsOwnServerSwitch() throws Exception {
        visibility(Rooms.Visibility.PRIVATE);
        JsonObject ts = create("{\"kind\":\"ts\"}");
        JsonObject mp3 = create("{\"kind\":\"mp3\"}");
        assertEquals("mp3", mp3.get("kind").getAsString());
        Matcher m = Pattern.compile("https://tunes\\.example/live/([A-Za-z0-9_-]{22})\\.mp3").matcher(
                mp3.get("url").getAsString());
        assertTrue(m.matches(), mp3.toString());
        assertEquals(m.group(1), column("SELECT token FROM stream_outputs WHERE kind = 'mp3'"));
        assertNotEquals(token(TS_URL, ts), m.group(1));
        assertTrue(mp3.get("active").getAsBoolean());
        expectError(409, "output_exists", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"mp3\"}"));
        expectError(400, "invalid_field", call(alice, "POST", "/api/rooms/club/outputs",
                "{\"kind\":\"mp3\",\"rtmpUrl\":\"rtmp://x/y\"}"));
        // listed after rtmp, ts, hls
        JsonArray items = json(call(bob, "GET", "/api/rooms/club/outputs", null)).getAsJsonArray("items");
        assertEquals(List.of("ts", "mp3"), List.of(items.get(0).getAsJsonObject().get("kind").getAsString(),
                items.get(1).getAsJsonObject().get("kind").getAsString()));
        // rotate gives a new token
        String path = "/api/rooms/club/outputs/" + mp3.get("id").getAsString();
        JsonObject rotated = json(call(alice, "POST", path + "/rotate", null));
        assertNotEquals(mp3.get("url").getAsString(), rotated.get("url").getAsString());
        // the server switch outputs.mp3.enabled gates it like the others
        settings.set(Settings.OUTPUTS_MP3_ENABLED, false);
        JsonObject off = json(call(alice, "GET", path, null));
        assertFalse(off.get("serverEnabled").getAsBoolean());
        assertFalse(off.get("active").getAsBoolean());
        assertEquals(200, call(alice, "PATCH", path, "{\"enabled\":false}").statusCode());
        expectError(403, "feature_disabled", call(alice, "PATCH", path, "{\"enabled\":true}"));
        assertEquals(204, call(alice, "DELETE", path, null).statusCode());
        expectError(403, "feature_disabled", call(alice, "POST", "/api/rooms/club/outputs", "{\"kind\":\"mp3\"}"));
        assertTrue(control.hasActiveOutput(roomId), "the .ts output is still on");
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

    // ------------------------------------------------------------------ auto turn-off (OutputAutoOff)

    static final long MINUTE = 60_000;

    /** Runs the auto turn-off check; the rooms of the outputs it turned off. */
    List<String> stoppedRooms() {
        return control.checkIdle().stream().map(OutputAutoOff.Stop::roomId).distinct().toList();
    }

    /** Runs the auto turn-off check; "kind:reason" of the outputs it turned off. */
    List<String> stops() {
        return control.checkIdle().stream().map(st -> st.kind().wire() + ":" + st.reason()).sorted().toList();
    }

    JsonObject output(String token, String kind) throws Exception {
        HttpResponse<String> r = call(token, "GET", "/api/rooms/club/outputs", null);
        assertEquals(200, r.statusCode(), r.body());
        for (var e : json(r).getAsJsonArray("items")) {
            if (e.getAsJsonObject().get("kind").getAsString().equals(kind)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(r.body());
    }

    void patchSettings(String json) throws Exception {
        rooms.update(owner, roomId, new Rooms.Edit(null, null, null, null,
                JsonParser.parseString(json).getAsJsonObject()), null);
    }

    JsonObject rtmpOutput(String token) throws Exception {
        HttpResponse<String> r = call(token, "GET", "/api/rooms/club/outputs", null);
        assertEquals(200, r.statusCode(), r.body());
        for (var e : json(r).getAsJsonArray("items")) {
            if (e.getAsJsonObject().get("kind").getAsString().equals("rtmp")) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(r.body());
    }

    @Test
    void idleForTheLimitTurnsTheRtmpOutputOffUntilTheOwnerTurnsItOn() throws Exception {
        JsonObject rtmp = create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\",\"rtmpKey\":\""
                + KEY + "\"}");
        create("{\"kind\":\"ts\",\"enabled\":false}");
        assertTrue(pushes.containsKey(roomId));
        autoOff.idle(roomId, true);
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stoppedRooms());
        assertTrue(pushes.containsKey(roomId));
        events.clear();
        clock.advance(1);
        assertEquals(List.of(roomId), stoppedRooms());
        // off like the owner's Turn off: push stopped, program output stopped (no active output left)
        assertFalse(pushes.containsKey(roomId));
        assertTrue(events.contains("unpush"), events.toString());
        assertTrue(events.contains("stop"), events.toString());
        JsonObject off = rtmpOutput(alice);
        assertFalse(off.get("enabled").getAsBoolean());
        assertEquals("rtmp://ingest.example/live", off.get("rtmpUrl").getAsString()); // still configured
        assertEquals(KEY, off.get("rtmpKey").getAsString());
        JsonObject stopped = off.getAsJsonObject("stopped");
        assertEquals("idle", stopped.get("reason").getAsString());
        assertEquals(10, stopped.get("idleMinutes").getAsInt());
        assertEquals(clock.millis(), stopped.get("at").getAsLong());
        assertFalse(rtmpOutput(bob).has("stopped")); // moderators just see it off
        // audited like a turn-off, by the system (no actor, no IP), without the URL or key
        String audit = db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT actor_user_id, ip, details_json"
                    + " FROM audit_log WHERE action = 'room.output_updated' ORDER BY at DESC, id DESC")) {
                assertTrue(rs.next());
                assertNull(rs.getString(1));
                assertNull(rs.getString(2));
                return rs.getString(3);
            }
        });
        assertTrue(audit.contains("\"reason\":\"idle\"") && audit.contains("\"idleMinutes\":10")
                && audit.contains("\"enabled\":false"), audit);
        assertFalse(audit.contains(KEY) || audit.contains("ingest.example"), audit);

        // activity (a request, a play) doesn't turn it back on
        autoOff.idle(roomId, false);
        control.reconcile();
        assertEquals(List.of(), stoppedRooms());
        assertFalse(pushes.containsKey(roomId));
        autoOff.idle(roomId, true);

        // the owner turns it on: push again, reason gone, a fresh timer although the room is still idle
        HttpResponse<String> r = call(alice, "PATCH", "/api/rooms/club/outputs/" + rtmp.get("id").getAsString(),
                "{\"enabled\":true}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(pushes.containsKey(roomId));
        assertFalse(rtmpOutput(alice).has("stopped"));
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stoppedRooms());
        clock.advance(1);
        assertEquals(List.of(roomId), stoppedRooms());
        assertFalse(pushes.containsKey(roomId));
    }

    @Test
    void activityResetsTheIdleTimer() throws Exception {
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        autoOff.idle(roomId, true);
        clock.advance(9 * MINUTE);
        autoOff.idle(roomId, true); // still idle: the timer goes on
        clock.advance(30_000);
        autoOff.idle(roomId, false); // an item queued / playing
        clock.advance(5 * MINUTE);
        assertEquals(List.of(), stoppedRooms());
        autoOff.idle(roomId, true); // the queue ran empty again
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stoppedRooms());
        assertTrue(pushes.containsKey(roomId));
        clock.advance(1);
        assertEquals(List.of(roomId), stoppedRooms());
        assertEquals(List.of(), stoppedRooms()); // once
    }

    @Test
    void theAdminMaximumCapsTheRoomSetting() throws Exception {
        patchSettings("{\"outputs\":{\"autoOffMinutes\":30}}");
        settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 5);
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        autoOff.idle(roomId, true);
        clock.advance(5 * MINUTE - 1);
        assertEquals(List.of(), stoppedRooms());
        clock.advance(1);
        assertEquals(List.of(roomId), stoppedRooms());
        assertEquals(5, rtmpOutput(alice).getAsJsonObject("stopped").get("idleMinutes").getAsInt());
        // below the maximum, the room's setting applies
        settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 60);
        assertEquals(30, OutputAutoOff.effectiveMinutes(rooms.record(roomId).settings().autoOffMinutes(),
                settings.outputsMaxAutoOffMinutes()));
        assertEquals(1, OutputAutoOff.effectiveMinutes(1, 60));
        assertEquals(new OutputAutoOff.Policy(true, 5), OutputAutoOff.policy(false, 30, true, 5));
        assertEquals(new OutputAutoOff.Policy(false, 10), OutputAutoOff.policy(false, 10, false, 60));
    }

    @Test
    void noListenersTurnsEachPullOutputOffSeparately() throws Exception {
        create("{\"kind\":\"ts\"}");
        create("{\"kind\":\"hls\"}");
        create("{\"kind\":\"mp3\"}");
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        autoOff.idle(roomId, false); // something is playing: only the listener clock runs
        autoOff.opened(roomId, StreamOutputs.Kind.TS);
        clock.advance(5 * MINUTE);
        autoOff.touched(roomId, StreamOutputs.Kind.HLS);
        clock.advance(5 * MINUTE - 1);
        assertEquals(List.of(), stops());
        clock.advance(1);
        // MP3: nobody since it was turned on; .ts: a connection is open; HLS: fetched 5 min ago; RTMP has no listeners
        assertEquals(List.of("mp3:no_listeners"), stops());
        JsonObject mp3 = output(alice, "mp3");
        assertFalse(mp3.get("enabled").getAsBoolean());
        assertEquals("no_listeners", mp3.getAsJsonObject("stopped").get("reason").getAsString());
        assertEquals(10, mp3.getAsJsonObject("stopped").get("idleMinutes").getAsInt());
        assertFalse(output(bob, "mp3").has("stopped"), "the reason is the owner's");
        clock.advance(5 * MINUTE);
        assertEquals(List.of("hls:no_listeners"), stops());
        autoOff.closed(roomId, StreamOutputs.Kind.TS);
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stops());
        clock.advance(1);
        assertEquals(List.of("ts:no_listeners"), stops());
        assertTrue(pushes.containsKey(roomId), "RTMP stays on while the room isn't idle");
        clock.advance(24 * 60 * MINUTE);
        assertEquals(List.of(), stops());

        // turning one on again starts a fresh timer
        String mp3Path = "/api/rooms/club/outputs/" + mp3.get("id").getAsString();
        assertEquals(200, call(alice, "PATCH", mp3Path, "{\"enabled\":true}").statusCode());
        assertFalse(output(alice, "mp3").has("stopped"));
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stops());
        clock.advance(1);
        assertEquals(List.of("mp3:no_listeners"), stops());
    }

    @Test
    void anEmptyQueueTurnsEveryOutputOffEvenWithListeners() throws Exception {
        create("{\"kind\":\"ts\"}");
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        autoOff.opened(roomId, StreamOutputs.Kind.TS);
        autoOff.idle(roomId, true);
        clock.advance(10 * MINUTE - 1);
        assertEquals(List.of(), stops());
        events.clear();
        int before = revalidations.get();
        clock.advance(1);
        assertEquals(List.of("rtmp:idle", "ts:idle"), stops());
        assertFalse(pushes.containsKey(roomId));
        assertTrue(revalidations.get() > before, "the open .ts connections are re-checked (and end)");
        assertTrue(events.contains("stop"), "no active output left: the program output stops");
        assertEquals("idle", output(alice, "ts").getAsJsonObject("stopped").get("reason").getAsString());
        assertFalse(control.hasActiveOutput(roomId));
    }

    @Test
    void alwaysOnNeverTurnsOffUnlessTheAdminForcesAutoOff() throws Exception {
        patchSettings("{\"outputs\":{\"autoOff\":false,\"autoOffMinutes\":30}}");
        create("{\"kind\":\"ts\"}");
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        autoOff.idle(roomId, true);
        clock.advance(24 * 60 * MINUTE);
        assertEquals(List.of(), stops());
        assertTrue(control.hasActiveOutput(roomId));
        // the admin forces it: the room's minutes apply (capped by the server's maximum)
        settings.set(Settings.OUTPUTS_FORCE_AUTO_OFF, true);
        settings.set(Settings.OUTPUTS_MAX_AUTO_OFF_MINUTES, 20);
        assertEquals(List.of("rtmp:idle", "ts:idle"), stops());
        assertEquals(20, output(alice, "ts").getAsJsonObject("stopped").get("idleMinutes").getAsInt());
    }

    // ------------------------------------------------------------------ stream URLs by slug or token

    @Test
    void publicAndUnlistedRoomsUseTheSlugPrivateRoomsATokenThatRotates() throws Exception {
        JsonObject ts = create("{\"kind\":\"ts\"}");
        JsonObject hls = create("{\"kind\":\"hls\"}");
        JsonObject mp3 = create("{\"kind\":\"mp3\"}");
        assertEquals("https://tunes.example/live/club.ts", ts.get("url").getAsString());
        assertEquals("https://tunes.example/live/club/index.m3u8", hls.get("url").getAsString());
        assertEquals("https://tunes.example/live/club.mp3", mp3.get("url").getAsString());
        assertEquals("slug", ts.get("urlKind").getAsString());
        assertFalse(ts.has("insecureUrl"), "no plain-HTTP URL without http.insecureBaseUrl");
        String tsPath = "/api/rooms/club/outputs/" + ts.get("id").getAsString();
        expectError(409, "no_token", call(alice, "POST", tsPath + "/rotate", null));
        expectError(404, "output_not_found", call(alice, "POST", "/api/rooms/club/outputs/nope/rotate", null));
        LiveTsApi.Targets tsTargets = LiveTsApi.targetsFrom(db, settings);
        LiveHlsApi.Targets hlsTargets = LiveHlsApi.targetsFrom(db, settings);
        LiveMp3Api.Targets mp3Targets = LiveMp3Api.targetsFrom(db, settings);
        String oldToken = column("SELECT token FROM stream_outputs WHERE kind = 'ts'");
        assertEquals(roomId, tsTargets.lookup("club").roomId());
        assertEquals(roomId, hlsTargets.lookup("club"));
        assertEquals(roomId, mp3Targets.lookup("club").roomId());
        assertNull(tsTargets.lookup(oldToken), "no token URLs for a public room");

        visibility(Rooms.Visibility.UNLISTED);
        assertEquals("https://tunes.example/live/club.ts", output(alice, "ts").get("url").getAsString());
        assertEquals(roomId, tsTargets.lookup("club").roomId());

        // private: slug URLs stop, open connections are re-checked, new tokens are issued
        int before = revalidations.get();
        visibility(Rooms.Visibility.PRIVATE);
        assertTrue(revalidations.get() > before);
        assertNull(tsTargets.lookup("club"));
        assertNull(hlsTargets.lookup("club"));
        assertNull(mp3Targets.lookup("club"));
        String token = token(TS_URL, output(alice, "ts"));
        assertNotEquals(oldToken, token, "a room that becomes private gets fresh tokens");
        assertEquals("token", output(alice, "ts").get("urlKind").getAsString());
        assertEquals(roomId, tsTargets.lookup(token).roomId());
        assertEquals(roomId, hlsTargets.lookup(token(HLS_URL, output(alice, "hls"))));
        assertEquals(200, call(alice, "POST", tsPath + "/rotate", null).statusCode());

        // back to public: token URLs stop, the slug works again
        String rotated = token(TS_URL, output(alice, "ts"));
        visibility(Rooms.Visibility.PUBLIC);
        assertNull(tsTargets.lookup(rotated));
        assertEquals(roomId, tsTargets.lookup("club").roomId());
    }

    /** Serves the outputs API again, with plain-HTTP stream URLs on {@code insecureBase}. */
    void restartWithInsecureBase(String insecureBase) throws Exception {
        server.close();
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        new StreamOutputsApi(rooms, rows, control, "https://tunes.example", insecureBase).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
    }

    @Test
    void insecureUrlOnlyForPublicAndUnlistedRoomsWithThePlainHttpAddressSet() throws Exception {
        restartWithInsecureBase("http://tunes.example/");
        create("{\"kind\":\"ts\"}");
        create("{\"kind\":\"hls\"}");
        create("{\"kind\":\"mp3\"}");
        create("{\"kind\":\"rtmp\",\"rtmpUrl\":\"rtmp://ingest.example/live\"}");
        for (Rooms.Visibility v : List.of(Rooms.Visibility.PUBLIC, Rooms.Visibility.UNLISTED)) {
            visibility(v);
            assertEquals("https://tunes.example/live/club.ts", output(alice, "ts").get("url").getAsString());
            assertEquals("http://tunes.example/live/club.ts", output(alice, "ts").get("insecureUrl").getAsString());
            assertEquals("http://tunes.example/live/club/index.m3u8",
                    output(alice, "hls").get("insecureUrl").getAsString());
            assertEquals("http://tunes.example/live/club.mp3", output(bob, "mp3").get("insecureUrl").getAsString(),
                    "moderators see it too");
            assertFalse(output(alice, "rtmp").has("insecureUrl"), "RTMP is pushed, not pulled");
        }
        visibility(Rooms.Visibility.PRIVATE);
        for (String kind : List.of("ts", "hls", "mp3")) {
            JsonObject o = output(alice, kind);
            assertEquals("token", o.get("urlKind").getAsString());
            assertFalse(o.has("insecureUrl"), "a token never goes over plain HTTP: " + o);
            assertFalse(o.toString().contains("http://"), o.toString());
        }
    }

    @Test
    void plainHttpStreamsAreTheSlugsOfPublicAndUnlistedRoomsOnly() throws Exception {
        create("{\"kind\":\"ts\"}");
        PlainHttpStreams.PublicSlugs slugs = PlainHttpStreams.from(db);
        assertTrue(slugs.isPublicSlug("club"));
        assertFalse(slugs.isPublicSlug("nope"));
        visibility(Rooms.Visibility.UNLISTED);
        assertTrue(slugs.isPublicSlug("club"));
        visibility(Rooms.Visibility.PRIVATE);
        assertFalse(slugs.isPublicSlug("club"));
        String token = column("SELECT token FROM stream_outputs WHERE kind = 'ts'");
        assertFalse(slugs.isPublicSlug(token));
    }

    @Test
    void aNewSlugEndsTheOldSlugsUrls() throws Exception {
        create("{\"kind\":\"ts\"}");
        LiveTsApi.Targets tsTargets = LiveTsApi.targetsFrom(db, settings);
        rooms.update(owner, roomId, new Rooms.Edit(null, "disco", null, null), null);
        int before = revalidations.get();
        control.roomEdited(roomId, false);
        assertTrue(revalidations.get() > before, "connections on the old slug are re-checked (and end)");
        assertNull(tsTargets.lookup("club"));
        assertEquals(roomId, tsTargets.lookup("disco").roomId());
        HttpResponse<String> r = call(alice, "GET", "/api/rooms/disco/outputs", null);
        assertEquals("https://tunes.example/live/disco.ts",
                json(r).getAsJsonArray("items").get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test
    void tokensAreUnique128BitBase64Url() {
        StreamOutputs r = rows;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = r.newToken();
            assertTrue(t.matches("[A-Za-z0-9_-]{22}"), t);
            assertFalse(t.matches("[a-z0-9-]+"), "a token never looks like a slug: " + t);
            assertTrue(seen.add(t));
        }
    }
}
