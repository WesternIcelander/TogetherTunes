package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
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
import io.siggi.togethertunes.auth.Tokens;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.rooms.PackagerHook;
import io.siggi.togethertunes.rooms.RequestSearch;
import io.siggi.togethertunes.rooms.RoomGuests;
import io.siggi.togethertunes.rooms.RoomHistory;
import io.siggi.togethertunes.rooms.RoomRegistry;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.rooms.RoomsApi;
import io.siggi.togethertunes.settings.LinkPreference;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room form of the YouTube search ({@code GET /api/rooms/{room}/youtube-search},
 * {@link YoutubeSearchApi}, {@link RoomGuests#requireYoutubeRequests}) against a fake MVFinder and
 * a fake yt-dlp: who may search by the room's rules for YouTube requests (users, guests by the
 * server and room settings, anonymous visitors, bans and timeouts, the feature switch), the shared
 * per-person limit (guests counted by their identity) and MVFinder for guests only with
 * {@code mvfinder.allowGuests}.
 */
class RoomYoutubeSearchApiTest {
    private static final String MV = "AAAAAAAAAAA";
    private static final String FOUND1 = "FFFFFFFFFFF";
    private static final String SEARCH_ANSWER = "{\"query\":\"firework\",\"matches\":["
            + "{\"type\":\"music_video\",\"title\":\"Firework\",\"durationMs\":234000,\"videoId\":\"" + MV + "\","
            + "\"channel\":\"KatyPerryVEVO\"}]}";
    private static final String GUEST_KEY = Tokens.newToken();
    private static final String GUEST2_KEY = Tokens.newToken();

    @TempDir
    Path dir;

    private Database db;
    private RoomRegistry registry;
    private WebServer server;
    private FakeMvfinder mvfinder;
    private FakeYtdlpSearch ytdlp;
    private Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private volatile boolean allowGuests;
    private String alice;
    private String bob;
    private String bobId;
    private String guestId;

    @BeforeEach
    void setUp() throws Exception {
        mvfinder = new FakeMvfinder();
        mvfinder.searchBody = SEARCH_ANSWER;
        String mvfinderUrl = mvfinder.url();
        ytdlp = new FakeYtdlpSearch(dir.resolve("yt"));
        ytdlp.searchJson(FakeYtdlpSearch.entries(new String[] {FOUND1, "Found one", "Chan", "200"}));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example",
                "TOGETHERTUNES_TOOLS_YTDLP", ytdlp.script.toString()), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        Rooms rooms = new Rooms(db, permissions, clock);
        RoomGuests guests = new RoomGuests(db, settings, permissions, rooms, registry);
        new RoomsApi(rooms, registry, permissions, guests, new RequestSearch(db, rooms, guests),
                new RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock)).register(router);
        ItemsApi itemsApi = new ItemsApi(new Items(db, io.siggi.togethertunes.DataDir.create(dir.resolve("data")), clock),
                permissions);
        MusicLinkResolver resolver = new MusicLinkResolver(() -> new MusicLinkResolver.Options(mvfinderUrl,
                "X-Api-Key", "sekret", LinkPreference.SONG, allowGuests, 1), clock);
        itemsApi.youtubeSearch(new YoutubeSearchApi(new YoutubeSearch(Tools.from(config), () -> null, resolver,
                () -> {
                    try {
                        return settings.get(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, clock), permissions).roomRules(guests::requireYoutubeRequests));
        itemsApi.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register");
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
        guestId = insertGuest(GUEST_KEY, "Quiet Walrus");
        insertGuest(GUEST2_KEY, "Loud Heron");
        HttpResponse<String> created = call(alice, null, "POST", "/api/rooms",
                "{\"name\":\"Bar\",\"visibility\":\"public\",\"controlMode\":\"open\"}");
        assertEquals(201, created.statusCode(), created.body());
    }

    @AfterEach
    void tearDown() {
        server.close();
        registry.close();
        mvfinder.close();
        db.close();
    }

    private String insertGuest(String key, String name) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO guests (id, kind, key_hash, ip_last,"
                    + " display_name, created_at, last_seen_at) VALUES (?, 'cookie', ?, NULL, ?, 1, 1)")) {
                ps.setString(1, id);
                ps.setString(2, Tokens.hash(key));
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private HttpResponse<String> call(String token, String guestKey, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (guestKey != null) {
            b.header("Cookie", "gid=" + guestKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> search(String token, String guestKey, String room, String query) throws Exception {
        return call(token, guestKey, "GET", "/api/rooms/" + room + "/youtube-search" + query, null);
    }

    private HttpResponse<String> asGuest(String query) throws Exception {
        return search(null, GUEST_KEY, "bar", query);
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private static void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, code(r), r.body());
    }

    private static List<String> ids(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        List<String> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("items")) {
            out.add(e.getAsJsonObject().get("videoId").getAsString());
        }
        return out;
    }

    private void guestLinks(boolean server, boolean room) throws Exception {
        settings.set(Settings.GUESTS_CAN_YOUTUBE, server);
        HttpResponse<String> r = call(alice, null, "PATCH", "/api/rooms/bar",
                "{\"settings\":{\"guests\":{\"canYoutube\":" + room + "}}}");
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void usersWithYoutubeLinksSearch() throws Exception {
        assertEquals(List.of(MV), ids(search(bob, null, "bar", "?q=firework")), "a user who isn't a member");
        assertEquals(List.of(MV), ids(search(alice, null, "bar", "?q=firework&limit=3")));
        assertEquals("query=firework&limit=3", mvfinder.seen.get(1).query());
        expectError(404, "room_not_found", search(bob, null, "nope", "?q=firework"));
        expectError(400, "invalid_query", search(bob, null, "bar", "?q=%20"));
        expectError(400, "invalid_limit", search(bob, null, "bar", "?q=a&limit=21"));
        // without library.youtube
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                ps.setString(1, bobId);
                ps.executeUpdate();
            }
            return null;
        });
        expectError(403, "forbidden", search(bob, null, "bar", "?q=firework"));
        settings.set(Settings.YTDLP_ENABLED, false);
        expectError(403, "feature_disabled", search(alice, null, "bar", "?q=firework"));
        expectError(404, "room_not_found", search(alice, null, "nope", "?q=firework"));
        assertEquals(2, mvfinder.seen.size(), "refused searches ask nobody");
        assertTrue(ytdlp.searches().isEmpty());
    }

    @Test
    void guestsByTheServerAndRoomSettings() throws Exception {
        // off by default (server and room)
        expectError(403, "guest_youtube_disabled", asGuest("?q=firework"));
        expectError(403, "guest_youtube_disabled", search(null, null, "bar", "?q=firework"));
        guestLinks(true, false);
        expectError(403, "guest_youtube_disabled", asGuest("?q=firework"));
        guestLinks(false, true);
        expectError(403, "guest_youtube_disabled", asGuest("?q=firework"));
        guestLinks(true, true);
        // allowGuests off: yt-dlp, not MVFinder
        assertEquals(List.of(FOUND1), ids(asGuest("?q=firework")));
        assertEquals(0, mvfinder.seen.size());
        assertEquals(1, ytdlp.searches().size());
        // allowGuests on: MVFinder
        allowGuests = true;
        assertEquals(List.of(MV), ids(asGuest("?q=firework")));
        assertEquals(1, mvfinder.seen.size());
        assertEquals(1, ytdlp.searches().size());
        // an anonymous visitor becomes a guest first
        expectError(401, "identity_required", search(null, null, "bar", "?q=firework"));
        // guests are off on the server
        settings.set(Settings.GUESTS_ENABLED, false);
        expectError(403, "guest_youtube_disabled", search(null, GUEST2_KEY, "bar", "?q=firework"));
        settings.set(Settings.GUESTS_ENABLED, true);
        // a private room is hidden from guests
        assertEquals(200, call(alice, null, "PATCH", "/api/rooms/bar", "{\"visibility\":\"private\"}").statusCode());
        expectError(404, "room_not_found", asGuest("?q=firework"));
        assertEquals(1, mvfinder.seen.size(), "refused searches ask nobody");
        assertEquals(1, ytdlp.searches().size());
    }

    @Test
    void bannedAndTimedOutPeopleCantSearch() throws Exception {
        guestLinks(true, true);
        HttpResponse<String> r = call(alice, null, "PUT", "/api/rooms/bar/bans/users/" + bobId, "{\"durationMs\":60000}");
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.body());
        expectError(403, "timed_out", search(bob, null, "bar", "?q=firework"));
        r = call(alice, null, "PUT", "/api/rooms/bar/bans/users/" + bobId, null);
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.body());
        expectError(403, "banned_from_room", search(bob, null, "bar", "?q=firework"));
        assertEquals(200, asGuest("?q=firework").statusCode());
        r = call(alice, null, "PUT", "/api/rooms/bar/bans/guests/" + guestId, null);
        assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.body());
        expectError(403, "banned_from_room", asGuest("?q=firework"));
        // the owner still may
        assertEquals(200, search(alice, null, "bar", "?q=firework").statusCode());
    }

    @Test
    void theLimitCountsEachPersonOnce() throws Exception {
        guestLinks(true, true);
        settings.set(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR, 2);
        assertEquals(200, asGuest("?q=a").statusCode());
        assertEquals(200, asGuest("?q=b").statusCode());
        HttpResponse<String> r = asGuest("?q=c");
        expectError(429, "too_many_searches", r);
        assertTrue(r.headers().firstValue("Retry-After").isPresent());
        // another guest and a user have their own counts
        assertEquals(200, search(null, GUEST2_KEY, "bar", "?q=d").statusCode());
        assertEquals(200, search(bob, null, "bar", "?q=e").statusCode());
        // a user's room searches and header searches share one count
        assertEquals(200, call(bob, null, "GET", "/api/youtube/search?q=f", null).statusCode());
        expectError(429, "too_many_searches", search(bob, null, "bar", "?q=g"));
        clock.advance(3_600_001L);
        assertEquals(200, asGuest("?q=h").statusCode());
    }
}
