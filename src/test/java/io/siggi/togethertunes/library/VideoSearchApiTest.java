package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Find a music video" over HTTP ({@code POST /api/items/{id}/video-search[/pick]}, {@link VideoSearch})
 * against a fake MVFinder and a fake yt-dlp: candidate order and dedupe, MVFinder off / failing / no
 * ISRC, the per-person limit, permissions, the feature switch, and picking (import, automatic link
 * for a non-editor, lyric video type, the cap, audit).
 */
class VideoSearchApiTest {
    private static final String MV = "AAAAAAAAAAA";
    private static final String LYRIC = "BBBBBBBBBBB";
    private static final String OFFICIAL = "DDDDDDDDDDD";
    private static final String VISUAL = "EEEEEEEEEEE";
    private static final String SONG = "SSSSSSSSSSS";
    private static final String USER = "UUUUUUUUUUU";
    private static final String FOUND1 = "FFFFFFFFFFF";
    private static final String FOUND2 = "GGGGGGGGGGG";
    private static final String LIVE = "LLLLLLLLLLL";

    /** An ISRC answer: a song, a music video, a lyric video, a user upload, an official video. */
    private static final String ISRC_ANSWER = "{\"source\":{\"service\":\"deezer\",\"title\":\"T\",\"artists\":[\"A\"]},"
            + "\"matches\":["
            + "{\"type\":\"song\",\"videoId\":\"" + SONG + "\",\"title\":\"Song\",\"artists\":[\"A\"]},"
            + "{\"type\":\"music_video\",\"videoId\":\"" + MV + "\",\"title\":\"The Video\",\"artists\":[\"A\",\"B\"],"
            + "\"durationMs\":214000},"
            + "{\"type\":\"lyric_video\",\"videoId\":\"" + LYRIC + "\",\"title\":\"Lyrics\",\"artists\":[\"A\"]},"
            + "{\"type\":\"user_video\",\"videoId\":\"" + USER + "\",\"title\":\"Cover\",\"artists\":[]},"
            + "{\"type\":\"official_video\",\"videoId\":\"" + OFFICIAL + "\",\"title\":\"Live\",\"artists\":[\"A\"]}]}";

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private FakeMvfinder mvfinder;
    private FakeYtdlpSearch ytdlp;
    private Settings settings;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private final AtomicInteger maxPerHour = new AtomicInteger(15);
    private volatile String mvfinderUrl;
    private String aliceId;
    private String alice;
    private String bob;
    private String bobId;

    @BeforeEach
    void setUp() throws Exception {
        mvfinder = new FakeMvfinder();
        mvfinder.body = ISRC_ANSWER;
        mvfinderUrl = mvfinder.url();
        ytdlp = new FakeYtdlpSearch(dir.resolve("yt"));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example",
                "TOGETHERTUNES_TOOLS_YTDLP", ytdlp.script.toString()), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        Permissions permissions = new Permissions(db, settings);
        YoutubeImports youtube = TestUploads.youtube(db, config, Tools.from(config), clock);
        Items items = new Items(db, io.siggi.togethertunes.DataDir.create(dir.resolve("data")), clock);
        ItemsApi itemsApi = new ItemsApi(items, permissions, youtube);
        MusicLinkResolver resolver = new MusicLinkResolver(() -> new MusicLinkResolver.Options(mvfinderUrl,
                "X-Api-Key", "sekret", LinkPreference.SONG, false, 1000), clock);
        itemsApi.videoSearch(new VideoSearchApi(new VideoSearch(db, Tools.from(config), () -> null, youtube, resolver,
                maxPerHour::get, clock), permissions));
        itemsApi.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        accounts.createLocal("root", null, "root-password", false, null, "account.register");
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
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

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
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

    /** Alice's song, with this ISRC and metadata_json (either may be null). */
    private String song(String isrc, String metadataJson) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, isrc, metadata_json)"
                    + " VALUES (?, 'audio', 'Firework', 'Katy  Perry', 'upload', ?, 3, 'ready', '720p30', 1, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, aliceId);
                ps.setString(3, isrc);
                ps.setString(4, metadataJson);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 3, 'upload')")) {
                ps.setString(1, aliceId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private static final String KEPT = "{\"link\":{\"service\":\"spotify\",\"id\":\"x\",\"videos\":["
            + "{\"videoId\":\"" + LYRIC + "\",\"title\":\"Lyrics again\",\"artists\":[\"A\"],\"type\":\"lyric_video\"},"
            + "{\"videoId\":\"" + VISUAL + "\",\"title\":\"Visualizer\",\"artists\":[],\"durationMs\":1000,"
            + "\"type\":\"visualizer\"}]}}";

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String code(HttpResponse<String> r) {
        return json(r).getAsJsonObject("error").get("code").getAsString();
    }

    private HttpResponse<String> search(String itemId, String token) throws Exception {
        return call("POST", "/api/items/" + itemId + "/video-search", token, null);
    }

    private static List<String> ids(HttpResponse<String> r) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : json(r).getAsJsonArray("candidates")) {
            out.add(e.getAsJsonObject().get("videoId").getAsString());
        }
        return out;
    }

    private JsonObject candidate(HttpResponse<String> r, String videoId) {
        for (JsonElement e : json(r).getAsJsonArray("candidates")) {
            if (e.getAsJsonObject().get("videoId").getAsString().equals(videoId)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(videoId + " not in " + r.body());
    }

    private long count(String sql, String arg) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, arg);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Test
    void mvfinderThenTheKeptMatchesInOrderWithoutDuplicatesAndNoYoutubeSearch() throws Exception {
        String song = song("USUM71000000", KEPT);
        HttpResponse<String> r = search(song, bob);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        // never the song, never a user upload; the kept lyric video only once
        assertEquals(List.of(MV, LYRIC, OFFICIAL, VISUAL), ids(r));
        JsonObject mv = candidate(r, MV);
        assertEquals("The Video", mv.get("title").getAsString());
        assertEquals("A, B", mv.get("channel").getAsString());
        assertEquals(214000, mv.get("durationMs").getAsLong());
        assertEquals("music_video", mv.get("type").getAsString());
        assertEquals("lyric_video", candidate(r, LYRIC).get("type").getAsString());
        assertEquals("Lyrics", candidate(r, LYRIC).get("title").getAsString(), "MVFinder's comes first");
        assertFalse(candidate(r, VISUAL).has("channel"));
        // MVFinder was asked by ISRC with the header; yt-dlp wasn't run
        assertEquals(1, mvfinder.seen.size());
        assertEquals("/isrc/USUM71000000", mvfinder.seen.get(0).path());
        assertEquals("type=all", mvfinder.seen.get(0).query());
        assertEquals("sekret", mvfinder.seen.get(0).header("X-Api-Key"));
        assertEquals(List.of(), ytdlp.searches());
    }

    @Test
    void withoutAnIsrcOrMvfinderTheKeptMatchesAndElseYoutube() throws Exception {
        ytdlp.searchJson(FakeYtdlpSearch.entries(new String[] {FOUND1, "Katy Perry - Firework (Official)", "KatyPerryVEVO", "231.5"},
                new String[] {LIVE, "Live now", "Somebody", "null", "is_live"},
                new String[] {FOUND2, "Firework lyrics", "Lyrics Channel", "228"},
                new String[] {FOUND1, "Same again", "x", "1"},
                new String[] {"bad", "Not an id", "x", "1"}));
        // no ISRC: MVFinder isn't asked, the kept matches are enough
        String kept = song(null, KEPT);
        assertEquals(List.of(LYRIC, VISUAL), ids(search(kept, bob)));
        assertEquals(0, mvfinder.seen.size());
        assertEquals(List.of(), ytdlp.searches());

        // nothing kept, no ISRC: the YouTube search with artist and title (no live streams, deduplicated)
        String plain = song(null, null);
        HttpResponse<String> r = search(plain, bob);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of(FOUND1, FOUND2), ids(r));
        JsonObject f = candidate(r, FOUND1);
        assertEquals("KatyPerryVEVO", f.get("channel").getAsString());
        assertEquals(231500, f.get("durationMs").getAsLong());
        assertFalse(f.has("type"));
        assertEquals(List.of("--flat-playlist -J --no-warnings -- ytsearch8:Katy Perry Firework"), ytdlp.searches());
        assertEquals(0, mvfinder.seen.size());

        // MVFinder failing (or not knowing the ISRC) falls through to the search too
        mvfinder.status = 503;
        String isrc = song("USUM71000001", null);
        assertEquals(List.of(FOUND1, FOUND2), ids(search(isrc, bob)));
        mvfinder.status = 404;
        assertEquals(List.of(FOUND1, FOUND2), ids(search(isrc, bob)));
        // MVFinder off: the search
        mvfinderUrl = null;
        assertEquals(List.of(FOUND1, FOUND2), ids(search(isrc, bob)));
        assertEquals(2, mvfinder.seen.size());

        // nothing anywhere: an empty list; a failing yt-dlp: 502
        ytdlp.searchJson("{\"entries\":[]}");
        assertEquals(List.of(), ids(search(plain, bob)));
        ytdlp.searchExit(1);
        HttpResponse<String> failed = search(plain, bob);
        assertEquals(502, failed.statusCode(), failed.body());
        assertEquals("search_failed", code(failed));
    }

    @Test
    void searchesAreLimitedPerPerson() throws Exception {
        maxPerHour.set(2);
        String song = song("USUM71000000", null);
        assertEquals(200, search(song, bob).statusCode());
        assertEquals(200, search(song, bob).statusCode());
        HttpResponse<String> r = search(song, bob);
        assertEquals(429, r.statusCode(), r.body());
        assertEquals("too_many_searches", code(r));
        assertTrue(r.headers().firstValue("Retry-After").isPresent());
        assertEquals(2, mvfinder.seen.size(), "a refused search asks nobody");
        // someone else isn't affected; an hour later bob may search again
        assertEquals(200, search(song, alice).statusCode());
        clock.advance(3_600_001L);
        assertEquals(200, search(song, bob).statusCode());
    }

    @Test
    void signedInUsersWithYoutubeImportsOnly() throws Exception {
        String song = song("USUM71000000", null);
        HttpResponse<String> r = search(song, null);
        assertEquals(401, r.statusCode());
        assertEquals("not_authenticated", code(r));
        assertEquals(401, call("POST", "/api/items/" + song + "/video-search/pick", null, "{\"videoId\":\"" + MV + "\"}")
                .statusCode());
        assertEquals(404, search(UuidV7.newId(), bob).statusCode());
        settings.set(Settings.YTDLP_ENABLED, false);
        r = search(song, bob);
        assertEquals(403, r.statusCode());
        assertEquals("feature_disabled", code(r));
        r = call("POST", "/api/items/" + song + "/video-search/pick", bob, "{\"videoId\":\"" + MV + "\"}");
        assertEquals("feature_disabled", code(r));
        assertEquals(0, mvfinder.seen.size());
    }

    @Test
    void pickingImportsAndLinksForAnyoneWhoMaySearch() throws Exception {
        String song = song("USUM71000000", KEPT);
        String path = "/api/items/" + song + "/video-search/pick";
        // only an offered video, and only by the one it was offered to
        HttpResponse<String> r = call("POST", path, bob, "{\"videoId\":\"" + MV + "\"}");
        assertEquals(400, r.statusCode());
        assertEquals("not_a_candidate", code(r));
        assertEquals(200, search(song, bob).statusCode());
        assertEquals("not_a_candidate", code(call("POST", path, alice, "{\"videoId\":\"" + MV + "\"}")));
        assertEquals("not_a_candidate", code(call("POST", path, bob, "{\"videoId\":\"" + USER + "\"}")));
        assertEquals("not_a_candidate", code(call("POST", path, bob, "{\"videoId\":\"../etc\"}")));
        assertEquals("unknown_field", code(call("POST", path, bob, "{\"videoId\":\"" + MV + "\",\"x\":1}")));

        // bob isn't the song's editor: the link is still saved
        r = call("POST", path, bob, "{\"videoId\":\"" + MV + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject picked = json(r);
        String video = picked.get("itemId").getAsString();
        assertEquals("pending", picked.get("status").getAsString());
        assertTrue(picked.get("linked").getAsBoolean());
        assertEquals("music_video", picked.get("linkType").getAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", MV));
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries WHERE item_id = ?", video), "not into a library");
        JsonArray links = json(call("GET", "/api/items/" + song, null, null)).getAsJsonArray("links");
        assertEquals(1, links.size());
        assertEquals("music_video", links.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(video, links.get(0).getAsJsonObject().getAsJsonObject("item").get("id").getAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.link_added' AND target = ?", song));
        String details = db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT details_json, actor_user_id FROM audit_log WHERE action = 'item.link_added'")) {
                rs.next();
                return rs.getString(1) + " " + rs.getString(2);
            }
        });
        assertTrue(details.contains("\"via\":\"search\"") && details.contains(bobId), details);

        // the same again: no second row; a lyric video is linked as one, after it
        r = call("POST", path, bob, "{\"videoId\":\"" + MV + "\"}");
        assertTrue(json(r).get("linked").getAsBoolean());
        r = call("POST", path, bob, "{\"videoId\":\"" + LYRIC + "\"}");
        assertEquals("lyric_video", json(r).get("linkType").getAsString());
        links = json(call("GET", "/api/items/" + song, null, null)).getAsJsonArray("links");
        assertEquals(List.of("music_video", "lyric_video"), List.of(links.get(0).getAsJsonObject().get("type").getAsString(),
                links.get(1).getAsJsonObject().get("type").getAsString()));
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.link_added' AND target = ?", song));

        // an official video or a visualizer is linked as a Music Video
        r = call("POST", path, bob, "{\"videoId\":\"" + VISUAL + "\"}");
        assertEquals("music_video", json(r).get("linkType").getAsString());

        // a pick lapses after an hour
        clock.advance(3_600_001L);
        assertEquals("not_a_candidate", code(call("POST", path, bob, "{\"videoId\":\"" + OFFICIAL + "\"}")));
    }

    /** An existing YouTube video item (ready) with this media type. */
    private String video(String youtubeId, String mediaType) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source, youtube_id,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, media_type)"
                    + " VALUES (?, 'video', 'Some video', 'youtube', ?, ?, 3, 'ready', '720p30', 1, ?)")) {
                ps.setString(1, id);
                ps.setString(2, youtubeId);
                ps.setString(3, aliceId);
                ps.setString(4, mediaType);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String mediaType(String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT media_type FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void thePickedVideoBecomesAMusicVideoUnlessItHasAType() throws Exception {
        String song = song("USUM71000000", KEPT);
        String path = "/api/items/" + song + "/video-search/pick";
        String movie = video(OFFICIAL, "movie");
        String uncategorized = video(VISUAL, "uncategorized");
        assertEquals(200, search(song, bob).statusCode());

        // a new video (bob isn't an editor of anything): music_video, audited by bob
        HttpResponse<String> r = call("POST", path, bob, "{\"videoId\":\"" + MV + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        String mv = json(r).get("itemId").getAsString();
        assertEquals("music_video", mediaType(mv));
        assertEquals("music_video", json(call("GET", "/api/items/" + mv, null, null)).get("mediaType").getAsString());
        String details = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT details_json, actor_user_id FROM audit_log WHERE action = 'item.updated' AND target = ?")) {
                ps.setString(1, mv);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    String d = rs.getString(1) + " " + rs.getString(2);
                    assertFalse(rs.next());
                    return d;
                }
            }
        });
        assertTrue(details.contains("\"mediaType\":\"music_video\"") && details.contains("\"via\":\"search\"")
                && details.contains(song) && details.contains(bobId), details);

        // a lyric video match: music_video too, linked as a Lyric Video
        r = call("POST", path, bob, "{\"videoId\":\"" + LYRIC + "\"}");
        assertEquals("lyric_video", json(r).get("linkType").getAsString());
        assertEquals("music_video", mediaType(json(r).get("itemId").getAsString()));

        // someone classified it before: it keeps its type, nothing audited
        r = call("POST", path, bob, "{\"videoId\":\"" + OFFICIAL + "\"}");
        assertEquals(movie, json(r).get("itemId").getAsString());
        assertTrue(json(r).get("linked").getAsBoolean());
        assertEquals("movie", mediaType(movie));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.updated' AND target = ?", movie));

        // an existing video still uncategorized: music_video
        r = call("POST", path, bob, "{\"videoId\":\"" + VISUAL + "\"}");
        assertEquals(uncategorized, json(r).get("itemId").getAsString());
        assertEquals("music_video", mediaType(uncategorized));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.updated' AND target = ?",
                uncategorized));
        // picking it again changes nothing more
        call("POST", path, bob, "{\"videoId\":\"" + VISUAL + "\"}");
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.updated' AND target = ?",
                uncategorized));
    }

    @Test
    void atTheCapThePickIsOnlyImported() throws Exception {
        String song = song("USUM71000000", null);
        db.write(c -> {
            for (int i = 0; i < ItemLinks.MAX_LINKS; i++) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO item_links (id, item_id, position, kind,"
                        + " url, added_by, added_at) VALUES (?, ?, ?, 'external', ?, ?, 1)")) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, song);
                    ps.setInt(3, i);
                    ps.setString(4, "https://example.com/" + i);
                    ps.setString(5, aliceId);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        assertEquals(200, search(song, alice).statusCode());
        HttpResponse<String> r = call("POST", "/api/items/" + song + "/video-search/pick", alice,
                "{\"videoId\":\"" + MV + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertFalse(json(r).get("linked").getAsBoolean());
        assertEquals(1, count("SELECT COUNT(*) FROM items WHERE youtube_id = ?", MV));
        assertEquals(ItemLinks.MAX_LINKS, count("SELECT COUNT(*) FROM item_links WHERE item_id = ?", song));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'item.link_added' AND target = ?", song));
    }
}
