package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
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
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code GET /api/rooms/{room}/overlay/preview.png} ({@link OverlayPreviewApi}). */
class OverlayPreviewApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final AtomicReference<OutputProfile> profile = new AtomicReference<>(OutputProfile.P720_30);
    private Rooms.RoomView room;
    private String alice;
    private String bob;
    private String root;

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
        new OverlayPreviewApi(rooms, new Overlays(dir.resolve("rooms"), "https://tunes.example", profile::get))
                .register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String rootId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        root = sessions.create(rootId, Actor.Via.BEARER, "test", null).token();
        room = rooms.create(new Actor.User(aliceId, "s", Actor.Via.BEARER), "Chill Room", null,
                Rooms.Visibility.PUBLIC, null, null);
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<byte[]> get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String path(String query) {
        return "/api/rooms/" + room.slug() + "/overlay/preview.png" + (query.isEmpty() ? "" : "?" + query);
    }

    private BufferedImage image(HttpResponse<byte[]> r) throws Exception {
        assertEquals(200, r.statusCode(), new String(r.body()));
        assertEquals("image/png", r.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return ImageIO.read(new ByteArrayInputStream(r.body()));
    }

    private void expectError(int status, String code, HttpResponse<byte[]> r) {
        assertEquals(status, r.statusCode(), new String(r.body()));
        assertEquals(code, JsonParser.parseString(new String(r.body())).getAsJsonObject().getAsJsonObject("error")
                .get("code").getAsString());
    }

    /** Whether every finder pattern of the decoded QR code lies in that quarter of the frame. */
    private static boolean inCorner(Result qr, BufferedImage img, String corner) {
        for (ResultPoint p : qr.getResultPoints()) {
            boolean bottom = p.getY() > img.getHeight() / 2.0;
            boolean right = p.getX() > img.getWidth() / 2.0;
            if (bottom != (corner.charAt(0) == 'b') || right != (corner.charAt(1) == 'r')) {
                return false;
            }
        }
        return true;
    }

    @Test
    void previewsSavedAndUnsavedSettingsWithoutStoringAnything() throws Exception {
        // The saved settings (defaults: bottom right), overlay off: it still previews.
        BufferedImage saved = image(get(alice, path("")));
        assertEquals(1280, saved.getWidth());
        assertEquals(720, saved.getHeight());
        Result qr = OverlayRendererTest.decode(saved);
        assertEquals("https://tunes.example/r/" + room.slug(), qr.getText());
        assertTrue(inCorner(qr, saved, "br"));

        // Unsaved values from the form.
        BufferedImage tl = image(get(alice, path("corner=tl&heightPct=30&margin=0")));
        Result moved = OverlayRendererTest.decode(tl);
        assertTrue(inCorner(moved, tl, "tl"));
        assertTrue(moved.getResultPoints()[0].getX() < 100, "margin 0 puts it at the edge");
        // Partial: only the corner.
        assertTrue(inCorner(OverlayRendererTest.decode(image(get(alice, path("corner=bl")))),
                tl, "bl"));
        // Follows the output profile.
        profile.set(OutputProfile.P1080_30);
        assertEquals(1920, image(get(alice, path(""))).getWidth());

        // Nothing stored: no overlay files for the room.
        assertFalse(Files.exists(dir.resolve("rooms").resolve(room.id())));
    }

    @Test
    void validatesLikeTheRoomSettings() throws Exception {
        expectError(400, "invalid_setting", get(alice, path("corner=middle")));
        expectError(400, "invalid_setting", get(alice, path("heightPct=4")));
        expectError(400, "invalid_setting", get(alice, path("heightPct=51")));
        expectError(400, "invalid_setting", get(alice, path("heightPct=abc")));
        expectError(400, "invalid_setting", get(alice, path("margin=21")));
        expectError(400, "invalid_setting", get(alice, path("margin=-1")));
    }

    @Test
    void ownerAndModeratorsOnly() throws Exception {
        image(get(root, path(""))); // rooms.moderateAny
        expectError(403, "forbidden", get(bob, path("")));
        expectError(401, "not_authenticated", get(null, path("")));
        expectError(404, "room_not_found", get(alice, "/api/rooms/no-such-room/overlay/preview.png"));
    }
}
