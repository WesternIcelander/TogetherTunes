package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
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
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /api/users...} over HTTP ({@link UserLibrariesApi}): JSON shapes, the permission, statuses, paging. */
class UserLibrariesApiTest {
    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Library library;
    private Playlists playlists;
    private String rootToken; // the first account: admin, so library.viewAll
    private Actor.User bob;
    private String bobToken;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        TestUploads.userLibrariesApi(db, config, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        YoutubeImports youtube = TestUploads.youtube(db, config, Tools.from(config), clock);
        library = new Library(db, youtube, clock);
        playlists = new Playlists(db, youtube, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        String rootId = accounts.createLocal("root", null, "root-password", false, null, "account.register").id();
        rootToken = sessions.create(rootId, Actor.Via.BEARER, "test", null).token();
        String bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        bob = new Actor.User(bobId, "s", Actor.Via.BEARER);
        bobToken = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject ok(String path) throws Exception {
        HttpResponse<String> r = call(path, rootToken);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private String item(String title) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', ?, 'upload', ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, title);
                ps.setString(3, bob.userId());
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Test
    void browsesAnotherUsersLibraryAndPlaylists() throws Exception {
        String song = item("Song");
        library.add(bob, song, () -> {
        });
        Playlists.Playlist mix = playlists.create(bob.userId(), "Mix", null);
        playlists.add(bob, mix.id(), song, null, () -> {
        });

        JsonObject users = ok("/api/users?q=BO");
        JsonArray items = users.getAsJsonArray("items");
        assertEquals(1, items.size());
        JsonObject u = items.get(0).getAsJsonObject();
        assertEquals(new TreeSet<>(Set.of("id", "username", "displayName", "disabled", "libraryCount",
                "playlistCount")), new TreeSet<>(u.keySet()));
        assertEquals(bob.userId(), u.get("id").getAsString());
        assertEquals("bob", u.get("username").getAsString());
        assertEquals("Bob", u.get("displayName").getAsString());
        assertFalse(u.get("disabled").getAsBoolean());
        assertEquals(1, u.get("libraryCount").getAsInt());
        assertEquals(1, u.get("playlistCount").getAsInt());
        assertFalse(users.has("nextCursor"));

        // the same shapes as the owner's own endpoints
        JsonObject lib = ok("/api/users/" + bob.userId() + "/library?q=song&kind=audio");
        JsonObject entry = lib.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(new TreeSet<>(Set.of("item", "addedAt", "via")), new TreeSet<>(entry.keySet()));
        assertEquals(song, entry.getAsJsonObject("item").get("id").getAsString());
        assertEquals("manual", entry.get("via").getAsString());
        assertEquals(0, ok("/api/users/" + bob.userId() + "/library?kind=video").getAsJsonArray("items").size());

        JsonObject pls = ok("/api/users/" + bob.userId() + "/playlists");
        JsonObject pl = pls.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(mix.id(), pl.get("id").getAsString());
        assertEquals(1, pl.get("entryCount").getAsInt());
        JsonObject full = ok("/api/users/" + bob.userId() + "/playlists/" + mix.id());
        assertEquals("Mix", full.get("name").getAsString());
        assertEquals(song, full.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("item")
                .get("id").getAsString());
    }

    @Test
    void usersArePaged() throws Exception {
        List<String> seen = new ArrayList<>();
        String path = "/api/users?limit=1";
        while (true) {
            JsonObject page = ok(path);
            page.getAsJsonArray("items").forEach(e -> seen.add(e.getAsJsonObject().get("username").getAsString()));
            if (!page.has("nextCursor")) {
                break;
            }
            path = "/api/users?limit=1&cursor=" + page.get("nextCursor").getAsString();
        }
        assertEquals(List.of("bob", "root"), seen);
        assertEquals(2, ok("/api/users?q=&cursor=").getAsJsonArray("items").size());
    }

    @Test
    void errorsAndCallers() throws Exception {
        String bobId = bob.userId();
        Playlists.Playlist rootList = playlists.create(
                ok("/api/users?q=root").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString(),
                "Root's", null);
        List<String> paths = List.of("/api/users", "/api/users/" + bobId + "/library",
                "/api/users/" + bobId + "/playlists", "/api/users/" + bobId + "/playlists/" + rootList.id());
        for (String p : paths) {
            HttpResponse<String> anon = call(p, null);
            assertEquals(401, anon.statusCode(), p);
            assertEquals("not_authenticated", code(anon));
            HttpResponse<String> plain = call(p, bobToken);
            assertEquals(403, plain.statusCode(), p);
            assertEquals("forbidden", code(plain));
        }

        String unknown = UuidV7.newId();
        for (String p : List.of("/api/users/" + unknown + "/library", "/api/users/" + unknown + "/playlists",
                "/api/users/" + unknown + "/playlists/" + rootList.id())) {
            HttpResponse<String> r = call(p, rootToken);
            assertEquals(404, r.statusCode(), p);
            assertEquals("user_not_found", code(r));
        }
        HttpResponse<String> foreign = call("/api/users/" + bobId + "/playlists/" + rootList.id(), rootToken);
        assertEquals(404, foreign.statusCode());
        assertEquals("playlist_not_found", code(foreign));

        for (String p : List.of("/api/users", "/api/users/" + bobId + "/library", "/api/users/" + bobId + "/playlists")) {
            for (String bad : List.of("0", "201", "x")) {
                HttpResponse<String> r = call(p + "?limit=" + bad, rootToken);
                assertEquals(400, r.statusCode(), p + " " + bad);
                assertEquals("invalid_limit", code(r));
            }
            assertEquals("invalid_cursor", code(call(p + "?cursor=nope", rootToken)));
        }
        assertEquals("invalid_query", code(call("/api/users?q=" + "x".repeat(101), rootToken)));
        assertEquals("invalid_query", code(call("/api/users/" + bobId + "/library?q=" + "x".repeat(201), rootToken)));
        assertEquals("invalid_kind", code(call("/api/users/" + bobId + "/library?kind=podcast", rootToken)));
    }
}
