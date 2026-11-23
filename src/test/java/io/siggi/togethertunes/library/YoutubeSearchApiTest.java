package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.LinkPreference;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * The YouTube search over HTTP ({@code GET /api/youtube/search}, {@link YoutubeSearch}) against a
 * fake MVFinder and a fake yt-dlp: MVFinder's {@code /search} first (live left out, YouTube's
 * order, thumbnails), the yt-dlp fallback (off, failing, empty), permissions and the feature switch,
 * the query and limit checks, and {@code youtubeSearch.maxPerHour} (separate from
 * {@code mvfinder.maxPerHour}).
 */
class YoutubeSearchApiTest {
    private static final String SONG = "SSSSSSSSSSS";
    private static final String MV = "AAAAAAAAAAA";
    private static final String LIVE = "LLLLLLLLLLL";
    private static final String LYRIC = "BBBBBBBBBBB";
    private static final String FOUND1 = "FFFFFFFFFFF";
    private static final String FOUND2 = "GGGGGGGGGGG";

    /** YouTube's order: a song upload, a music video, a live stream, a lyric video on a foreign thumbnail host. */
    private static final String SEARCH_ANSWER = "{\"query\":\"firework\",\"matches\":["
            + "{\"type\":\"song\",\"title\":\"Firework\",\"durationMs\":228000,\"videoId\":\"" + SONG + "\","
            + "\"artists\":[\"Katy Perry\"]},"
            + "{\"type\":\"music_video\",\"title\":\"Firework (Official Music Video)\",\"durationMs\":234000,"
            + "\"videoId\":\"" + MV + "\",\"channel\":\"KatyPerryVEVO\","
            + "\"thumbnails\":[{\"url\":\"https://i.ytimg.com/vi/" + MV + "/hq720.jpg\"}]},"
            + "{\"type\":\"video\",\"title\":\"Firework 24/7\",\"videoId\":\"" + LIVE + "\",\"channel\":\"Radio\","
            + "\"live\":true},"
            + "{\"type\":\"lyric_video\",\"title\":\"Firework (Lyrics)\",\"videoId\":\"" + LYRIC + "\","
            + "\"channel\":\"Lyrics\",\"thumbnails\":[{\"url\":\"https://evil.example/x.jpg\"}]},"
            + "{\"type\":\"video\",\"title\":\"Again\",\"videoId\":\"" + MV + "\"}]}";

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private FakeMvfinder mvfinder;
    private FakeYtdlpSearch ytdlp;
    private Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private volatile String mvfinderUrl;
    private String alice;
    private String bob;
    private String bobId;

    @BeforeEach
    void setUp() throws Exception {
        mvfinder = new FakeMvfinder();
        mvfinder.searchBody = SEARCH_ANSWER;
        mvfinderUrl = mvfinder.url();
        ytdlp = new FakeYtdlpSearch(dir.resolve("yt"));
        ytdlp.searchJson(FakeYtdlpSearch.entries(
                new String[] {FOUND1, "Found one", "Chan", "200"},
                new String[] {LIVE, "Live now", "Chan", "0", "is_live"},
                new String[] {FOUND2, "Found two", "Other", "61.5", "", "[{\"url\":\"https://i.ytimg.com/vi/"
                        + FOUND2 + "/mq.jpg\",\"width\":320}]"}));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example",
                "TOGETHERTUNES_TOOLS_YTDLP", ytdlp.script.toString()), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        Items items = new Items(db, io.siggi.togethertunes.DataDir.create(dir.resolve("data")), clock);
        ItemsApi itemsApi = new ItemsApi(items, permissions);
        MusicLinkResolver resolver = new MusicLinkResolver(() -> new MusicLinkResolver.Options(mvfinderUrl,
                "X-Api-Key", "sekret", LinkPreference.SONG, false, 1), clock);
        itemsApi.youtubeSearch(new YoutubeSearchApi(new YoutubeSearch(Tools.from(config), () -> null, resolver,
                () -> {
                    try {
                        return settings.get(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, clock), permissions));
        itemsApi.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register");
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
        bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        bob = sessions.create(bobId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        mvfinder.close();
        db.close();
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                + "/api/youtube/search" + query)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> search(String q, String token) throws Exception {
        return get("?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), token);
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return json(r).getAsJsonObject("error").get("code").getAsString();
    }

    private static List<String> ids(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        List<String> out = new ArrayList<>();
        for (JsonElement e : json(r).getAsJsonArray("items")) {
            out.add(e.getAsJsonObject().get("videoId").getAsString());
        }
        return out;
    }

    private static JsonObject item(HttpResponse<String> r, String videoId) {
        for (JsonElement e : json(r).getAsJsonArray("items")) {
            if (e.getAsJsonObject().get("videoId").getAsString().equals(videoId)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(videoId + " not in " + r.body());
    }

    @Test
    void mvfinderSearchesFirstInYoutubesOrderWithoutLiveStreams() throws Exception {
        HttpResponse<String> r = search("  katy   perry firework ", bob);
        assertEquals(List.of(SONG, MV, LYRIC), ids(r), "live and the duplicate left out, songs kept");
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(1, mvfinder.seen.size());
        FakeMvfinder.Seen seen = mvfinder.seen.get(0);
        assertEquals("/search", seen.path());
        assertEquals("query=katy%20perry%20firework&limit=10", seen.query(), "trimmed, default limit");
        assertEquals("sekret", seen.header("X-Api-Key"));
        assertTrue(ytdlp.searches().isEmpty(), "no yt-dlp search");

        JsonObject mv = item(r, MV);
        assertEquals("Firework (Official Music Video)", mv.get("title").getAsString());
        assertEquals("KatyPerryVEVO", mv.get("channel").getAsString());
        assertEquals(234000, mv.get("durationMs").getAsLong());
        assertEquals("music_video", mv.get("type").getAsString());
        assertEquals("https://i.ytimg.com/vi/" + MV + "/hq720.jpg", mv.get("thumbnailUrl").getAsString());
        assertEquals("https://i.ytimg.com/vi/" + LYRIC + "/hqdefault.jpg", item(r, LYRIC).get("thumbnailUrl").getAsString(),
                "a foreign host falls back");
        assertEquals("Katy Perry", item(r, SONG).get("channel").getAsString(), "the artists without a channel");
        assertEquals("https://i.ytimg.com/vi/" + SONG + "/hqdefault.jpg", item(r, SONG).get("thumbnailUrl").getAsString());

        r = get("?q=firework&limit=2", bob);
        assertEquals(List.of(SONG, MV), ids(r), "at most limit");
        assertEquals("query=firework&limit=2", mvfinder.seen.get(1).query());
    }

    @Test
    void ytdlpWhenMvfinderIsOffFailsOrFindsNothing() throws Exception {
        // unset
        mvfinderUrl = null;
        HttpResponse<String> r = get("?q=found&limit=5", bob);
        assertEquals(List.of(FOUND1, FOUND2), ids(r), "the live stream left out");
        assertEquals(0, mvfinder.seen.size());
        assertEquals(1, ytdlp.searches().size());
        assertTrue(ytdlp.searches().get(0).endsWith("-- ytsearch5:found"), ytdlp.searches().get(0));
        JsonObject two = item(r, FOUND2);
        assertEquals("Other", two.get("channel").getAsString());
        assertEquals(61500, two.get("durationMs").getAsLong());
        assertFalse(two.has("type"));
        assertEquals("https://i.ytimg.com/vi/" + FOUND2 + "/mq.jpg", two.get("thumbnailUrl").getAsString());
        assertEquals("https://i.ytimg.com/vi/" + FOUND1 + "/hqdefault.jpg", item(r, FOUND1).get("thumbnailUrl").getAsString());

        // failing
        mvfinderUrl = mvfinder.url();
        mvfinder.searchStatus = 502;
        assertEquals(List.of(FOUND1, FOUND2), ids(search("found", bob)));
        assertTrue(ytdlp.searches().get(1).endsWith("-- ytsearch10:found"), ytdlp.searches().get(1));
        mvfinder.searchStatus = 200;
        mvfinder.searchBody = "<html>";
        assertEquals(List.of(FOUND1, FOUND2), ids(search("found", bob)));
        // nothing usable (only a live stream)
        mvfinder.searchBody = "{\"query\":\"q\",\"matches\":[{\"type\":\"video\",\"videoId\":\"" + LIVE
                + "\",\"live\":true}]}";
        assertEquals(List.of(FOUND1, FOUND2), ids(search("found", bob)));
        assertEquals(4, ytdlp.searches().size());
        assertEquals(3, mvfinder.seen.size());

        // yt-dlp failing too
        ytdlp.searchExit(1);
        r = search("found", bob);
        assertEquals(502, r.statusCode(), r.body());
        assertEquals("search_failed", code(r));
    }

    @Test
    void signedInUsersWithYoutubeImportsOnly() throws Exception {
        HttpResponse<String> r = search("firework", null);
        assertEquals(401, r.statusCode());
        assertEquals("not_authenticated", code(r));
        // without library.youtube (no roles at all)
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                ps.setString(1, bobId);
                ps.executeUpdate();
            }
            return null;
        });
        r = search("firework", bob);
        assertEquals(403, r.statusCode(), r.body());
        assertEquals("forbidden", code(r));
        assertEquals(200, search("firework", alice).statusCode());
        settings.set(Settings.YTDLP_ENABLED, false);
        r = search("firework", alice);
        assertEquals(403, r.statusCode());
        assertEquals("feature_disabled", code(r));
        assertEquals(1, mvfinder.seen.size(), "refused searches ask nobody");
        assertTrue(ytdlp.searches().isEmpty());
    }

    @Test
    void queryAndLimitAreChecked() throws Exception {
        for (String q : List.of("", "?q=", "?q=%20%20", "?q=" + "x".repeat(201))) {
            HttpResponse<String> r = get(q, bob);
            assertEquals(400, r.statusCode(), q);
            assertEquals("invalid_query", code(r), q);
        }
        assertEquals(200, get("?q=" + "x".repeat(200), bob).statusCode());
        assertEquals(200, get("?q=%20" + "x".repeat(200) + "%20", bob).statusCode(), "trimmed first");
        for (String limit : List.of("0", "21", "-1", "abc")) {
            HttpResponse<String> r = get("?q=a&limit=" + limit, bob);
            assertEquals(400, r.statusCode(), limit);
            assertEquals("invalid_limit", code(r), limit);
        }
        assertEquals(200, get("?q=a&limit=20", bob).statusCode());
        assertEquals("query=a&limit=20", mvfinder.seen.get(mvfinder.seen.size() - 1).query());
    }

    @Test
    void theSettingLimitsSearchesPerPerson() throws Exception {
        assertEquals(60, settings.get(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR), "default");
        settings.set(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR, 2);
        // separate from the music-video finder's limit
        settings.set(Settings.MVFINDER_MAX_PER_HOUR, 1);
        assertEquals(200, search("a", bob).statusCode());
        assertEquals(200, search("b", bob).statusCode());
        HttpResponse<String> r = search("c", bob);
        assertEquals(429, r.statusCode(), r.body());
        assertEquals("too_many_searches", code(r));
        long retry = Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retry > 0 && retry <= 3600, String.valueOf(retry));
        assertEquals(2, mvfinder.seen.size(), "a refused search asks nobody");
        // someone else isn't affected; an hour later bob may search again
        assertEquals(200, search("d", alice).statusCode());
        clock.advance(3_600_001L);
        assertEquals(200, search("e", bob).statusCode());
        // a higher limit applies at once
        settings.set(Settings.YOUTUBE_SEARCH_MAX_PER_HOUR, 3);
        assertEquals(200, search("f", bob).statusCode());
    }
}
