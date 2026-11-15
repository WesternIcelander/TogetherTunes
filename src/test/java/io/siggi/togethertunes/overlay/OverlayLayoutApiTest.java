package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.common.BitMatrix;
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
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.output.StreamOutputs;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /api/rooms/{room}/overlay/layout} ({@link OverlayLayoutApi}): the web player's copy of
 * the stream's overlay matches what {@link OverlayRenderer} draws, and who may read it.
 */
class OverlayLayoutApiTest {
    private static final String BASE = "https://tunes.example";

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private Rooms rooms;
    private StreamOutputs outputs;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final AtomicReference<OutputProfile> profile = new AtomicReference<>(OutputProfile.P720_30);
    private final AtomicBoolean serverSwitch = new AtomicBoolean(true);
    private Rooms.RoomView room;
    private Rooms.RoomView hidden;
    private String aliceId;
    private String bobId;
    private String carolId;
    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                .load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        rooms = new Rooms(db, permissions, clock);
        outputs = new StreamOutputs(db, new SecureRandom(), clock);
        new OverlayLayoutApi(rooms, new Overlays(dir.resolve("rooms"), BASE, profile::get), serverSwitch::get)
                .register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register");
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        carol = sessions.create(carolId, Actor.Via.BEARER, "test", null).token();
        Actor.User owner = new Actor.User(aliceId, "s", Actor.Via.BEARER);
        room = rooms.create(owner, "Chill Room", null, Rooms.Visibility.PUBLIC, null, null);
        hidden = rooms.create(owner, "Secret Room", null, Rooms.Visibility.PRIVATE, null, null);
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> get(String token, String ref) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/api/rooms/" + ref + "/overlay/layout"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject layout(String token, String ref) throws Exception {
        HttpResponse<String> r = get(token, ref);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error")
                .get("code").getAsString());
    }

    private void settings(String ref, String json) throws Exception {
        rooms.update(new Actor.User(aliceId, "s", Actor.Via.BEARER), ref,
                new Rooms.Edit(null, null, null, null, JsonParser.parseString(json).getAsJsonObject()), null);
    }

    private static int i(JsonObject o, String name) {
        return (int) Math.round(o.get(name).getAsDouble());
    }

    @Test
    void matrixIsZxingsForTheShortLink() throws Exception {
        JsonObject l = layout(null, room.slug());
        String url = BASE + "/r/" + room.slug();
        assertEquals(url, l.get("url").getAsString());
        assertEquals(OverlayRenderer.RENDER_VERSION, l.get("version").getAsInt());
        assertEquals("Requests at tunes.example/r/" + room.slug(), l.getAsJsonObject("text").get("value").getAsString());
        BitMatrix m = OverlayRenderer.qr(url);
        JsonObject qr = l.getAsJsonObject("qr");
        JsonArray rows = qr.getAsJsonArray("rows");
        assertEquals(m.getHeight(), rows.size());
        assertEquals(m.getWidth(), qr.get("modules").getAsInt());
        assertEquals(4, qr.get("quietZone").getAsInt());
        for (int y = 0; y < m.getHeight(); y++) {
            String row = rows.get(y).getAsString();
            assertEquals(m.getWidth(), row.length());
            for (int x = 0; x < m.getWidth(); x++) {
                assertEquals(m.get(x, y), row.charAt(x) == '1', "module " + x + "," + y);
            }
        }
        // The quiet zone is light.
        assertFalse(rows.get(0).getAsString().contains("1"));
        assertEquals("rgba(0, 0, 0, 0.6902)", l.getAsJsonObject("colors").get("backdrop").getAsString());
        assertEquals("rgba(255, 255, 255, 1)", l.getAsJsonObject("colors").get("modules").getAsString());
    }

    /** The layout's rectangles are where the stream's overlay image has the QR square, modules and backdrop. */
    @Test
    void rectsMatchTheRenderedOverlay() throws Exception {
        for (String corner : new String[] {"br", "tl", "tr", "bl"}) {
            settings(room.slug(), "{\"overlay\":{\"corner\":\"" + corner + "\",\"heightPct\":30,\"margin\":2}}");
            JsonObject l = layout(alice, room.slug());
            Rooms.Room r = rooms.record(room.id());
            OverlayRenderer.Rendered rendered = new OverlayRenderer().render(
                    OverlayRenderer.spec(BASE, r.slug(), r.settings(), profile.get()));
            compare(l, rendered);
        }
        // Another profile: frame coordinates follow it.
        profile.set(OutputProfile.P1080_30);
        Rooms.Room r = rooms.record(room.id());
        JsonObject l = layout(alice, room.slug());
        assertEquals(1920, l.getAsJsonObject("frame").get("width").getAsInt());
        compare(l, new OverlayRenderer().render(OverlayRenderer.spec(BASE, r.slug(), r.settings(), profile.get())));
    }

    /** Checks the layout against the rendered image's pixels (frame coordinates → image coordinates). */
    static void compare(JsonObject l, OverlayRenderer.Rendered rendered) {
        BufferedImage img = rendered.image();
        JsonObject bounds = l.getAsJsonObject("bounds");
        assertEquals(rendered.x(), i(bounds, "x"));
        assertEquals(rendered.y(), i(bounds, "y"));
        assertEquals(rendered.width(), i(bounds, "width"));
        assertEquals(rendered.height(), i(bounds, "height"));
        assertEquals(rendered.spec().corner(), l.get("corner").getAsString());
        int ox = rendered.x(), oy = rendered.y();

        JsonObject qr = l.getAsJsonObject("qr");
        int qx = i(qr, "x") - ox, qy = i(qr, "y") - oy, side = i(qr, "width"), px = qr.get("modulePx").getAsInt();
        JsonArray rows = qr.getAsJsonArray("rows");
        assertEquals(side, px * rows.size());
        for (int my = 0; my < rows.size(); my++) {
            String row = rows.get(my).getAsString();
            for (int mx = 0; mx < row.length(); mx++) {
                int argb = img.getRGB(qx + mx * px + px / 2, qy + my * px + px / 2);
                boolean white = argb == 0xFFFFFFFF;
                assertEquals(row.charAt(mx) == '1', white, "module " + mx + "," + my);
            }
        }
        // The QR square's edges: inside at the middle of each edge, outside just beyond.
        assertEquals(176, img.getRGB(qx, qy + side / 2) >>> 24);
        assertEquals(176, img.getRGB(qx + side - 1, qy + side / 2) >>> 24);
        if (qx > 0) {
            assertEquals(0, img.getRGB(qx - 1, qy + side / 2) >>> 24);
        }
        if (qx + side < img.getWidth()) {
            assertEquals(0, img.getRGB(qx + side, qy + side / 2) >>> 24);
        }

        JsonObject text = l.getAsJsonObject("text");
        JsonObject b = text.getAsJsonObject("backdrop");
        int bx = i(b, "x") - ox, by = i(b, "y") - oy, bw = i(b, "width"), bh = i(b, "height");
        // Left and right ends of the backdrop at mid-height: backdrop, and transparent just outside.
        assertEquals(176, img.getRGB(bx, by + bh / 2) >>> 24);
        assertEquals(176, img.getRGB(bx + bw - 1, by + bh / 2) >>> 24);
        if (bx > 0) {
            assertEquals(0, img.getRGB(bx - 1, by + bh / 2) >>> 24);
        }
        if (bx + bw < img.getWidth()) {
            assertEquals(0, img.getRGB(bx + bw, by + bh / 2) >>> 24);
        }
        // Between the QR square and the backdrop: the transparent gap.
        boolean below = rendered.spec().corner().charAt(0) == 'b';
        int gapY = below ? qy + side : by + bh;
        assertEquals(0, img.getRGB(qx + side / 2, gapY) >>> 24, "gap");
        // The text is inside its backdrop: its baseline, and white ink on the baseline's row band.
        double baseline = text.get("baseline").getAsDouble() - oy;
        double tx = text.get("x").getAsDouble() - ox;
        assertTrue(baseline > by && baseline < by + bh, "baseline inside the backdrop");
        assertTrue(tx >= bx - 1 && tx + text.get("advance").getAsDouble() <= bx + bw + text.get("size").getAsDouble(),
                "text inside the backdrop");
        int ink = 0;
        for (int x = bx; x < bx + bw; x++) {
            for (int y = by; y < by + bh; y++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF && img.getRGB(x, y) >>> 24 == 255) {
                    ink++;
                }
            }
        }
        assertTrue(ink > 50, "text drawn in the backdrop");
        assertTrue(text.get("size").getAsDouble() > 0);
        assertEquals(OverlayLayoutApi.FONT, text.get("font").getAsString());
    }

    @Test
    void modeAndAvailability() throws Exception {
        JsonObject l = layout(null, room.slug());
        assertEquals("off", l.get("mode").getAsString());
        assertFalse(l.get("available").getAsBoolean());
        settings(room.slug(), "{\"overlay\":{\"mode\":\"always\"}}");
        l = layout(null, room.slug());
        assertEquals("always", l.get("mode").getAsString());
        assertTrue(l.get("enabled").getAsBoolean());
        assertTrue(l.get("available").getAsBoolean());
        settings(room.slug(), "{\"overlay\":{\"mode\":\"idleOnly\"}}");
        assertTrue(layout(null, room.slug()).get("available").getAsBoolean());
        // The server's switch off: not offered.
        serverSwitch.set(false);
        l = layout(null, room.slug());
        assertFalse(l.get("enabled").getAsBoolean());
        assertFalse(l.get("available").getAsBoolean());
        assertEquals(RoomSettings.OverlayMode.IDLE_ONLY.wire(), l.get("mode").getAsString());
    }

    @Test
    void anyoneWhoMaySeeTheRoom() throws Exception {
        // Public: everyone, by slug or id.
        layout(null, room.slug());
        layout(bob, room.id());
        // Private: the owner and members; to others it doesn't exist.
        layout(alice, hidden.slug());
        expectError(404, "room_not_found", get(null, hidden.slug()));
        expectError(404, "room_not_found", get(bob, hidden.slug()));
        rooms.setRole(new Actor.User(aliceId, "s", Actor.Via.BEARER), hidden.slug(), bobId, Rooms.Role.MEMBER, null);
        layout(bob, hidden.slug());
        // Banned from the public room: still sees it read-only (may listen), as GET /api/rooms/{room}.
        rooms.setRole(new Actor.User(aliceId, "s", Actor.Via.BEARER), room.slug(), carolId, Rooms.Role.BANNED, null);
        layout(carol, room.slug());
        expectError(404, "room_not_found", get(alice, "no-such-room"));
    }

    @Test
    void privateRoomShowsTheShortLinkNeverAStreamToken() throws Exception {
        String token = null;
        for (StreamOutputs.Kind kind : new StreamOutputs.Kind[] {StreamOutputs.Kind.TS, StreamOutputs.Kind.HLS,
                StreamOutputs.Kind.MP3}) {
            StreamOutputs.Output o = outputs.create(hidden.id(), kind, null, null, true, aliceId, null);
            assertTrue(o.token() != null && !o.token().isEmpty());
            token = o.token();
            String body = get(alice, hidden.slug()).body();
            assertFalse(body.contains(o.token()), "no stream token in the layout");
        }
        JsonObject l = layout(alice, hidden.slug());
        assertEquals(BASE + "/r/" + hidden.slug(), l.get("url").getAsString());
        assertFalse(l.toString().contains(token));
    }
}
