package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The custom card image routes ({@link CustomCardsApi}): rights, uploads with warnings, deletes. */
class CustomCardsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final AtomicReference<OutputProfile> profile = new AtomicReference<>(OutputProfile.P720_30);
    private CustomCards cards;
    private Rooms.RoomView room;
    private String root;
    private String alice;
    private String bob;
    private String carol;
    private String aliceId;
    private Roles roles;

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
        Rooms rooms = new Rooms(db, permissions, clock);
        cards = new CustomCards(dir.resolve("cards/custom"), dir.resolve("rooms"),
                CustomCards.ownerHolds(db, permissions));
        new CustomCardsApi(cards, rooms, permissions, db, profile::get, dir.resolve("tmp"), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String rootId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        String carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null,
                "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        root = sessions.create(rootId, Actor.Via.BEARER, "test", null).token();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        carol = sessions.create(carolId, Actor.Via.BEARER, "test", null).token();
        room = rooms.create(new Actor.User(aliceId, "s", Actor.Via.BEARER), "Chill Room", null,
                Rooms.Visibility.PUBLIC, null, null);
        // Bob moderates Alice's room.
        rooms.setRole(new Actor.User(aliceId, "s", Actor.Via.BEARER), room.id(), bobId, Rooms.Role.MODERATOR, null);
        roles = new Roles(db);
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private void grantAlice() throws Exception {
        String role = roles.create("cards", List.of(Permission.ROOMS_CUSTOM_CARDS), false).id();
        List<String> ids = new ArrayList<>();
        for (Roles.Role r : roles.userRoles(aliceId)) {
            ids.add(r.id());
        }
        ids.add(role);
        roles.setUserRoles(aliceId, ids);
    }

    private HttpResponse<byte[]> call(String method, String token, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static JsonObject json(int status, HttpResponse<byte[]> r) {
        assertEquals(status, r.statusCode(), new String(r.body()));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return JsonParser.parseString(new String(r.body())).getAsJsonObject();
    }

    private static void expectError(int status, String code, HttpResponse<byte[]> r) {
        assertEquals(status, r.statusCode(), new String(r.body()));
        assertEquals(code, JsonParser.parseString(new String(r.body())).getAsJsonObject().getAsJsonObject("error")
                .get("code").getAsString());
    }

    static byte[] image(int w, int h, Color color, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(CustomCardsTest.solid(w, h, color), format, out));
        return out.toByteArray();
    }

    private static List<String> warningCodes(JsonObject screen) {
        List<String> out = new ArrayList<>();
        for (JsonElement w : screen.getAsJsonArray("warnings")) {
            out.add(w.getAsJsonObject().get("code").getAsString());
        }
        return out;
    }

    private static JsonObject screen(JsonObject list, String id) {
        JsonArray screens = list.getAsJsonArray("screens");
        assertEquals(4, screens.size());
        for (JsonElement s : screens) {
            if (s.getAsJsonObject().get("screen").getAsString().equals(id)) {
                return s.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }

    private List<String> auditActions() throws Exception {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT action, target FROM audit_log ORDER BY at, id");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getString(1).contains("card")) {
                        out.add(rs.getString(1) + " " + rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    @Test
    void serverImagesWithWarningsReCheckedForTheOutput() throws Exception {
        JsonObject empty = json(200, call("GET", root, "/api/admin/cards", null));
        assertEquals(1280, empty.getAsJsonObject("output").get("width").getAsInt());
        assertFalse(screen(empty, "paused").has("image"));
        assertEquals(List.of("paused", "idle", "loading", "gap"), empty.getAsJsonArray("screens").asList().stream()
                .map(e -> e.getAsJsonObject().get("screen").getAsString()).toList());

        byte[] png = image(1280, 720, Color.RED, "png");
        JsonObject up = json(200, call("PUT", root, "/api/admin/cards/paused", png));
        assertEquals("paused", up.get("screen").getAsString());
        assertEquals(1280, up.getAsJsonObject("image").get("width").getAsInt());
        assertEquals("png", up.getAsJsonObject("image").get("format").getAsString());
        assertEquals(List.of(), warningCodes(up));
        // An off-spec JPEG: accepted, with both warnings.
        JsonObject off = json(200, call("PUT", root, "/api/admin/cards/idle", image(640, 480, Color.BLUE, "jpeg")));
        assertEquals(List.of("aspect", "resolution"), warningCodes(off));

        // The original comes back as uploaded.
        String url = up.getAsJsonObject("image").get("url").getAsString();
        HttpResponse<byte[]> file = call("GET", root, url, null);
        assertEquals(200, file.statusCode());
        assertEquals("image/png", file.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        assertArrayEquals(png, file.body());

        // Another output encoding: the warnings are checked again.
        profile.set(OutputProfile.P1080_30);
        JsonObject list = json(200, call("GET", root, "/api/admin/cards", null));
        assertEquals(List.of("resolution"), warningCodes(screen(list, "paused")));
        assertEquals(1920, list.getAsJsonObject("output").get("width").getAsInt());

        expectError(400, "invalid_image", call("PUT", root, "/api/admin/cards/loading", "hello".getBytes()));
        expectError(400, "invalid_image", call("PUT", root, "/api/admin/cards/loading", new byte[0]));
        expectError(400, "invalid_image", call("PUT", root, "/api/admin/cards/loading",
                image(1280, 720, Color.RED, "gif")));
        expectError(413, "payload_too_large", call("PUT", root, "/api/admin/cards/loading",
                new byte[(int) CustomCards.MAX_BYTES + 1]));
        expectError(404, "unknown_screen", call("PUT", root, "/api/admin/cards/title", png));

        assertEquals(204, call("DELETE", root, "/api/admin/cards/paused", null).statusCode());
        expectError(404, "no_image", call("DELETE", root, "/api/admin/cards/paused", null));
        expectError(404, "no_image", call("GET", root, url, null));
        assertEquals(List.of("admin.card_uploaded paused", "admin.card_uploaded idle", "admin.card_removed paused"),
                auditActions());
    }

    @Test
    void serverImagesAreForAdminSettingsOnly() throws Exception {
        byte[] png = image(1280, 720, Color.RED, "png");
        expectError(401, "not_authenticated", call("GET", null, "/api/admin/cards", null));
        expectError(403, "forbidden", call("GET", alice, "/api/admin/cards", null));
        expectError(403, "forbidden", call("PUT", alice, "/api/admin/cards/paused", png));
        expectError(403, "forbidden", call("DELETE", alice, "/api/admin/cards/paused", null));
        expectError(403, "forbidden", call("GET", alice, "/api/admin/cards/paused/image", null));
        grantAlice(); // rooms.customCards is about her rooms, not the server's images
        expectError(403, "forbidden", call("PUT", alice, "/api/admin/cards/paused", png));
        assertEquals(java.util.Map.of(), cards.effective(room.id()));
    }

    @Test
    void roomImagesNeedTheOwnerWithRoomsCustomCards() throws Exception {
        String base = "/api/rooms/" + room.slug() + "/cards";
        byte[] png = image(1920, 1080, Color.GREEN, "png");
        // Without the permission: Alice reads but can't upload.
        JsonObject list = json(200, call("GET", alice, base, null));
        assertFalse(list.get("canEdit").getAsBoolean());
        assertFalse(list.get("applies").getAsBoolean());
        expectError(403, "forbidden", call("PUT", alice, base + "/idle", png));

        grantAlice();
        assertTrue(json(200, call("GET", alice, base, null)).get("canEdit").getAsBoolean());
        JsonObject up = json(200, call("PUT", alice, base + "/idle", png));
        assertEquals(List.of(), warningCodes(up));
        assertTrue(up.getAsJsonObject("image").get("url").getAsString().startsWith("/api/rooms/" + room.id()
                + "/cards/idle/image?v="));
        // The server's image shows where the room has none; the room's wins where it has one.
        json(200, call("PUT", root, "/api/admin/cards/idle", image(1280, 720, Color.RED, "png")));
        json(200, call("PUT", root, "/api/admin/cards/paused", image(1280, 720, Color.RED, "png")));
        JsonObject both = json(200, call("GET", alice, base, null));
        assertTrue(both.get("applies").getAsBoolean());
        assertTrue(screen(both, "idle").has("image"));
        assertEquals(1280, screen(both, "paused").getAsJsonObject("server").get("width").getAsInt());
        assertFalse(screen(both, "paused").has("image"));
        var effective = cards.effective(room.id());
        assertEquals(1920, effective.get(Filler.IDLE).width(), "the room's image beats the server's");
        assertEquals(1280, effective.get(Filler.PAUSED).width());

        // A moderator reads (and sees the image) but can't change it; others get nothing.
        JsonObject mod = json(200, call("GET", bob, base, null));
        assertFalse(mod.get("canEdit").getAsBoolean());
        assertEquals(200, call("GET", bob, base + "/idle/image", null).statusCode());
        expectError(403, "forbidden", call("PUT", bob, base + "/idle", png));
        expectError(403, "forbidden", call("DELETE", bob, base + "/idle", null));
        expectError(403, "forbidden", call("GET", carol, base, null));
        expectError(403, "forbidden", call("PUT", carol, base + "/idle", png));
        expectError(401, "not_authenticated", call("GET", null, base, null));
        expectError(404, "room_not_found", call("GET", alice, "/api/rooms/no-such-room/cards", null));
        // An admin who isn't the owner can't change a room's images either (rooms.moderateAny reads).
        assertFalse(json(200, call("GET", root, base, null)).get("canEdit").getAsBoolean());
        expectError(403, "forbidden", call("PUT", root, base + "/idle", png));

        // Delete: back to the server's image.
        assertEquals(204, call("DELETE", alice, base + "/idle", null).statusCode());
        expectError(404, "no_image", call("DELETE", alice, base + "/idle", null));
        assertEquals(1280, cards.effective(room.id()).get(Filler.IDLE).width());
        assertTrue(auditActions().containsAll(List.of("room.card_uploaded " + room.id(),
                "room.card_removed " + room.id())));
    }
}
