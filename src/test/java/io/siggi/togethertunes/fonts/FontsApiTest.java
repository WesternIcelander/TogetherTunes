package io.siggi.togethertunes.fonts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
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
import io.siggi.togethertunes.settings.Settings;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The fonts admin API over HTTP ({@link FontsApi}): permissions, the JSON shape, downloads, uploads, audit. */
class FontsApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private HttpServer fontServer;
    private FontLibrary library;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Settings settings;
    private String admin;
    private String alice;

    @BeforeEach
    void setUp() throws Exception {
        byte[] thai = TestFonts.thai();
        fontServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fontServer.createContext("/thai.ttf", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, thai.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(thai);
                }
            }
        });
        fontServer.start();
        String base = "http://127.0.0.1:" + fontServer.getAddress().getPort();
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        library = new FontLibrary(dir.resolve("fonts"), FontManifest.of(List.of(TestFonts.entry("thai",
                "NotoSansThai-Regular.ttf", thai, base + "/thai.ttf", Character.UnicodeScript.THAI))),
                new FontDownloader(), SystemFonts.of(List.of(TestFonts.font(TestFonts.lao()))), new FontLibrary.Prefs() {
                    @Override
                    public String cardFont() throws Exception {
                        return settings.cardsFont();
                    }

                    @Override
                    public boolean autoDownload() {
                        return false;
                    }

                    @Override
                    public io.siggi.togethertunes.settings.CjkVariant cjkVariant() throws Exception {
                        return settings.fontsCjkVariant();
                    }
                }, attempt -> 60_000, Clock.systemUTC());
        library.start();
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        FontLibrary.Texts texts = c -> c.accept("สวัสดี");
        new FontsApi(library, settings, new Permissions(db, settings), db, texts, dir.resolve("tmp"), clock)
                .register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        String adminId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        library.close();
        fontServer.stop(0);
        db.close();
        CardFonts.install(CardFonts.bundledOnly());
    }

    private HttpResponse<String> call(String method, String path, String token, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject ok(int status, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return r.body().isEmpty() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private int audits(String action) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?")) {
                ps.setString(1, action);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Test
    void onlyAdminsWithSettingsPermissionGetIn() throws Exception {
        for (String[] route : new String[][] {{"GET", "/api/admin/fonts"},
                {"POST", "/api/admin/fonts/scripts/thai/download"}, {"DELETE", "/api/admin/fonts/scripts/thai"},
                {"POST", "/api/admin/fonts/download-all"}, {"POST", "/api/admin/fonts/uploads"},
                {"DELETE", "/api/admin/fonts/uploads/0123456789abcdef"}}) {
            HttpResponse<String> anonymous = call(route[0], route[1], null, null);
            assertEquals(401, anonymous.statusCode(), route[1]);
            HttpResponse<String> user = call(route[0], route[1], alice, null);
            assertEquals(403, user.statusCode(), route[1]);
            assertEquals("forbidden", code(user));
        }
    }

    @Test
    void theFontsPanelData() throws Exception {
        JsonObject info = ok(200, call("GET", "/api/admin/fonts", admin, null));
        assertEquals("bundled", info.get("cardFont").getAsString());
        assertTrue(info.get("autoDownload").getAsBoolean());
        assertEquals("jp", info.get("cjkVariant").getAsString());
        JsonObject thai = info.getAsJsonArray("fonts").get(0).getAsJsonObject();
        assertEquals("thai", thai.get("id").getAsString());
        assertEquals("THAI", thai.getAsJsonArray("scripts").get(0).getAsString());
        assertEquals("not_downloaded", thai.get("status").getAsString());
        assertEquals(TestFonts.thai().length, thai.get("size").getAsLong());
        assertEquals(TestFonts.thai().length, info.get("missingBytes").getAsLong());
        JsonObject choices = info.getAsJsonObject("choices");
        assertEquals("bundled", choices.getAsJsonObject("bundled").get("value").getAsString());
        assertEquals("Noto Sans Lao", choices.getAsJsonArray("system").get(0).getAsString());
        JsonObject uncovered = info.getAsJsonArray("uncovered").get(0).getAsJsonObject();
        assertEquals("THAI", uncovered.get("script").getAsString());
        assertEquals("thai", uncovered.get("font").getAsString());
        assertTrue(uncovered.get("codePoint").getAsString().startsWith("U+0E"));
    }

    @Test
    void downloadAndRemove() throws Exception {
        assertEquals(404, call("POST", "/api/admin/fonts/scripts/nope/download", admin, null).statusCode());
        JsonObject queued = ok(202, call("POST", "/api/admin/fonts/scripts/thai/download", admin, null));
        assertEquals("thai", queued.get("id").getAsString());
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (library.status("thai").state() != FontLibrary.State.INSTALLED && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        JsonObject info = ok(200, call("GET", "/api/admin/fonts", admin, null));
        assertEquals("installed", info.getAsJsonArray("fonts").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(0, info.getAsJsonArray("uncovered").size(), "Thai has a font now");
        assertEquals(0, info.get("missingBytes").getAsLong());
        assertEquals(0, ok(202, call("POST", "/api/admin/fonts/download-all", admin, null)).get("queuedBytes")
                .getAsLong(), "nothing left to download");
        ok(204, call("DELETE", "/api/admin/fonts/scripts/thai", admin, null));
        assertEquals(404, call("DELETE", "/api/admin/fonts/scripts/nope", admin, null).statusCode());
        assertEquals(TestFonts.thai().length, ok(202, call("POST", "/api/admin/fonts/download-all", admin, null))
                .get("queuedBytes").getAsLong());
        assertEquals(3, audits(FontsApi.AUDIT_DOWNLOAD));
        assertEquals(1, audits(FontsApi.AUDIT_REMOVED));
    }

    @Test
    void uploads() throws Exception {
        HttpResponse<String> bad = call("POST", "/api/admin/fonts/uploads", admin, "not a font".getBytes());
        assertEquals(400, bad.statusCode());
        assertEquals("invalid_font", code(bad));
        JsonObject up = ok(201, call("POST", "/api/admin/fonts/uploads", admin, TestFonts.lao()));
        assertEquals("Noto Sans Lao", up.get("family").getAsString());
        String id = up.get("id").getAsString();
        assertEquals("upload:" + id, up.get("value").getAsString());
        JsonElement listed = ok(200, call("GET", "/api/admin/fonts", admin, null)).getAsJsonObject("choices")
                .getAsJsonArray("uploads").get(0);
        assertEquals(id, listed.getAsJsonObject().get("id").getAsString());
        assertEquals(1, audits(FontsApi.AUDIT_UPLOADED));
        settings.set(Settings.CARDS_FONT, "upload:" + id);
        JsonObject info = ok(200, call("GET", "/api/admin/fonts", admin, null));
        assertEquals("upload:" + id, info.get("cardFont").getAsString());
        HttpResponse<String> inUse = call("DELETE", "/api/admin/fonts/uploads/" + id, admin, null);
        assertEquals(409, inUse.statusCode());
        assertEquals("font_in_use", code(inUse));
        settings.reset(Settings.CARDS_FONT);
        ok(204, call("DELETE", "/api/admin/fonts/uploads/" + id, admin, null));
        assertEquals(404, call("DELETE", "/api/admin/fonts/uploads/" + id, admin, null).statusCode());
        assertFalse(ok(200, call("GET", "/api/admin/fonts", admin, null)).getAsJsonObject("choices")
                .getAsJsonArray("uploads").size() > 0);
    }
}
