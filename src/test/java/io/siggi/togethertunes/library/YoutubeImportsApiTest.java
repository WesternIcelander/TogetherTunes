package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code POST /api/imports/youtube} with {@code Accept: application/x-ndjson}: the lookup's stages
 * arrive as JSON lines while a slow fake yt-dlp runs, then the result; a known item and errors
 * before the lookup answer as plain JSON; a lookup that fails ends the stream with an error line.
 */
class YoutubeImportsApiTest {
    private static final String ID = "jNQXAC9IVRw";

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private String token;
    private String aliceId;
    private Playlists playlists;
    private Path failFile;

    @BeforeEach
    void setUp() throws Exception {
        failFile = dir.resolve("fail");
        Path ytdlp = dir.resolve("fake-yt-dlp");
        Files.writeString(ytdlp, "#!/bin/sh\n"
                + "echo '[youtube] Extracting URL: https://www.youtube.com/watch?v=" + ID + "' >&2\n"
                + "sleep 0.3\n"
                + "echo '[youtube] " + ID + ": Downloading webpage' >&2\n"
                + "sleep 0.3\n"
                + "if [ -f '" + failFile + "' ]; then echo 'ERROR: [youtube] " + ID + ": Private video' >&2; exit 1; fi\n"
                + "echo '[info] " + ID + ": Downloading 1 format(s): 18' >&2\n"
                + "echo '{\"id\":\"" + ID + "\",\"title\":\"Me at the zoo\",\"uploader\":\"jawed\",\"duration\":19,"
                + "\"live_status\":\"not_live\",\"is_live\":false}'\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(ytdlp, PosixFilePermissions.fromString("rwx------"));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example",
                "TOGETHERTUNES_TOOLS_YTDLP", ytdlp.toString()), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        io.siggi.togethertunes.settings.Settings settings = new io.siggi.togethertunes.settings.Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        YoutubeImports youtube = TestUploads.youtube(db, config, io.siggi.togethertunes.ingest.Tools.from(config), clock);
        playlists = new Playlists(db, youtube, clock);
        new YoutubeImportsApi(youtube, TestUploads.policy(new Permissions(db, settings), settings), playlists)
                .register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        token = new Sessions(db, clock).create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpRequest post(String accept) {
        return post(accept, ",\"mediaType\":\"music_video\"");
    }

    private HttpRequest post(String accept, String extra) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/imports/youtube"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://youtu.be/" + ID + "\"" + extra + "}"))
                .build();
    }

    /** The lines of a streamed answer, each with the ms since the request was sent. */
    private record Line(JsonObject json, long atMs) {
    }

    private List<Line> stream(HttpResponse<java.io.InputStream> r, long sentNanos) throws Exception {
        List<Line> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null) {
                lines.add(new Line(JsonParser.parseString(l).getAsJsonObject(), (System.nanoTime() - sentNanos) / 1_000_000));
            }
        }
        return lines;
    }

    @Test
    void stagesStreamWhileTheLookupRunsThenTheResult() throws Exception {
        long sent = System.nanoTime();
        HttpResponse<java.io.InputStream> r = http.send(post("application/x-ndjson, application/json"),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/x-ndjson"));
        assertEquals("no", r.headers().firstValue("X-Accel-Buffering").orElse(""));
        List<Line> lines = stream(r, sent);
        List<String> types = new ArrayList<>();
        for (Line l : lines) {
            types.add(l.json().get("type").getAsString() + (l.json().has("stage") ? ":" + l.json().get("stage").getAsString() : ""));
        }
        assertEquals(List.of("progress:contacting", "progress:page", "progress:formats", "result"), types);
        assertEquals("Reading the video page…", lines.get(1).json().get("text").getAsString());
        // the first stage arrives long before the answer (the fake takes 0.6 s)
        assertTrue(lines.get(3).atMs() - lines.get(0).atMs() >= 400, lines.toString());
        JsonObject result = lines.get(3).json();
        assertFalse(result.get("cached").getAsBoolean());
        assertEquals("pending", result.get("status").getAsString());

        // known now: no lookup, plain JSON at once
        HttpResponse<String> again = http.send(post("application/x-ndjson"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, again.statusCode());
        assertTrue(again.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals(result.get("itemId"), JsonParser.parseString(again.body()).getAsJsonObject().get("itemId"));
    }

    @Test
    void withoutTheAcceptHeaderTheAnswerIsPlainJson() throws Exception {
        HttpResponse<String> r = http.send(post("application/json"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("pending", JsonParser.parseString(r.body()).getAsJsonObject().get("status").getAsString());
    }

    /**
     * Classify on library add: without a media type the import still happens, then the answer is
     * 409 needs_classification with the item (in the stream: the error line carries it too).
     */
    @Test
    void anUncategorizedVideoForTheLibraryNeedsAType() throws Exception {
        HttpResponse<java.io.InputStream> r = http.send(post("application/x-ndjson", ""),
                HttpResponse.BodyHandlers.ofInputStream());
        List<Line> lines = stream(r, System.nanoTime());
        JsonObject last = lines.get(lines.size() - 1).json();
        assertEquals("error", last.get("type").getAsString(), lines.toString());
        assertEquals(409, last.get("status").getAsInt());
        JsonObject error = last.getAsJsonObject("error");
        assertEquals("needs_classification", error.get("code").getAsString());
        String itemId = error.get("itemId").getAsString();
        assertFalse(error.has("suggested"), "nothing in the metadata suggests a type");

        HttpResponse<String> plain = http.send(post("application/json", ""), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, plain.statusCode(), plain.body());
        assertEquals(itemId, JsonParser.parseString(plain.body()).getAsJsonObject().getAsJsonObject("error")
                .get("itemId").getAsString());
        // not for the library: no question
        HttpResponse<String> room = http.send(post("application/json", ",\"addToLibrary\":false"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, room.statusCode(), room.body());
        // a bad type is refused up front
        HttpResponse<String> bad = http.send(post("application/json", ",\"mediaType\":\"uncategorized\""),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode(), bad.body());
        HttpResponse<String> ok = http.send(post("application/json", ",\"mediaType\":\"movie\""),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("movie", db.read(c -> {
            try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT media_type FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }));
    }

    @Test
    void aFailedLookupEndsTheStreamWithAnErrorLine() throws Exception {
        Files.writeString(failFile, "x");
        HttpResponse<java.io.InputStream> r = http.send(post("application/x-ndjson"), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        List<Line> lines = stream(r, System.nanoTime());
        JsonObject last = lines.get(lines.size() - 1).json();
        assertEquals("error", last.get("type").getAsString(), lines.toString());
        assertEquals(422, last.get("status").getAsInt());
        assertEquals("youtube_unavailable", last.getAsJsonObject("error").get("code").getAsString());
        assertEquals("YouTube didn't return this video: Private video",
                last.getAsJsonObject("error").get("message").getAsString());

        // an error before any lookup is the usual JSON error
        HttpRequest bad = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/imports/youtube"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/\"}")).build();
        HttpResponse<String> refused = http.send(bad, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, refused.statusCode());
        assertEquals("invalid_youtube_url",
                JsonParser.parseString(refused.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString());
    }

    /** "Add from link": one call imports into the caller's library and into one of their playlists. */
    @Test
    void addFromLinkIntoAPlaylist() throws Exception {
        String mix = playlists.create(aliceId, "Mix", null).id();
        // checked before anything is imported (the fake yt-dlp isn't run)
        HttpResponse<String> missing = http.send(post("application/x-ndjson",
                ",\"mediaType\":\"music_video\",\"playlistId\":\"no-such\""), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missing.statusCode(), missing.body());
        assertEquals("playlist_not_found", code(missing));
        assertEquals(0, count("SELECT COUNT(*) FROM items"));
        HttpResponse<String> notLibrary = http.send(post("application/json",
                ",\"addToLibrary\":false,\"playlistId\":\"" + mix + "\""), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, notLibrary.statusCode(), notLibrary.body());
        assertEquals("invalid_add_to_library", code(notLibrary));
        HttpResponse<String> loose = http.send(post("application/json", ",\"beforeEntryId\":\"x\""),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("invalid_before_entry_id", code(loose));
        assertEquals(0, count("SELECT COUNT(*) FROM items"));

        // with a type, through the lookup's stream: the result line carries the playlist entry
        HttpResponse<java.io.InputStream> r = http.send(post("application/x-ndjson",
                ",\"mediaType\":\"music_video\",\"playlistId\":\"" + mix + "\""), HttpResponse.BodyHandlers.ofInputStream());
        List<Line> lines = stream(r, System.nanoTime());
        JsonObject result = lines.get(lines.size() - 1).json();
        assertEquals("result", result.get("type").getAsString(), lines.toString());
        String itemId = result.get("itemId").getAsString();
        JsonObject entry = result.getAsJsonObject("entry");
        assertEquals(itemId, entry.getAsJsonObject("item").get("id").getAsString());
        assertEquals("music_video", entry.getAsJsonObject("item").get("mediaType").getAsString());
        assertEquals(List.of(entry.get("id").getAsString()),
                playlists.get(aliceId, mix).entries().stream().map(Playlists.Entry::id).toList());
        assertEquals("youtube", string("SELECT via FROM library_entries WHERE item_id = '" + itemId + "'"));

        // the known item again, before the first entry (plain JSON, no lookup)
        HttpResponse<String> again = http.send(post("application/x-ndjson", ",\"playlistId\":\"" + mix
                + "\",\"beforeEntryId\":\"" + entry.get("id").getAsString() + "\""), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, again.statusCode(), again.body());
        JsonObject second = JsonParser.parseString(again.body()).getAsJsonObject();
        String secondEntry = second.getAsJsonObject("entry").get("id").getAsString();
        assertEquals(List.of(secondEntry, entry.get("id").getAsString()),
                playlists.get(aliceId, mix).entries().stream().map(Playlists.Entry::id).toList());
        assertEquals(1, count("SELECT COUNT(*) FROM library_entries"));
        // an unknown beforeEntryId: the item stays in the library, the playlist is unchanged
        HttpResponse<String> badBefore = http.send(post("application/json", ",\"playlistId\":\"" + mix
                + "\",\"beforeEntryId\":\"nope\""), HttpResponse.BodyHandlers.ofString());
        assertEquals("before_entry_not_found", code(badBefore));
        assertEquals(2, playlists.get(aliceId, mix).entries().size());

        // into Liked Songs: once
        String likes = playlists.likes(aliceId).id();
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> liked = http.send(post("application/json", ",\"playlistId\":\"" + likes + "\""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, liked.statusCode(), liked.body());
        }
        assertEquals(1, playlists.likes(aliceId).entries().size());
        // someone else's playlist is not found
        String bobId = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock)
                .createLocal("bob", null, "bob-password", false, null, "account.register").id();
        assertEquals("playlist_not_found", code(http.send(post("application/json", ",\"playlistId\":\""
                + playlists.create(bobId, "x", null).id() + "\""), HttpResponse.BodyHandlers.ofString())));

        // an uncategorized video without a type: in neither the library nor the playlist
        db.write(c -> {
            c.createStatement().executeUpdate("UPDATE items SET media_type = 'uncategorized'");
            c.createStatement().executeUpdate("DELETE FROM playlist_entries");
            c.createStatement().executeUpdate("DELETE FROM library_entries");
            return null;
        });
        HttpResponse<String> untyped = http.send(post("application/json", ",\"playlistId\":\"" + mix + "\""),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, untyped.statusCode(), untyped.body());
        assertEquals("needs_classification", code(untyped));
        assertEquals(0, playlists.get(aliceId, mix).entries().size());
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries"));
    }

    private static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    private long count(String sql) throws Exception {
        return db.read(c -> {
            try (java.sql.ResultSet rs = c.createStatement().executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private String string(String sql) throws Exception {
        return db.read(c -> {
            try (java.sql.ResultSet rs = c.createStatement().executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    @Test
    void acceptHeaderParsing() {
        assertTrue(YoutubeImportsApi.wantsStages("application/x-ndjson"));
        assertTrue(YoutubeImportsApi.wantsStages("application/json;q=0.5, application/x-ndjson"));
        assertTrue(YoutubeImportsApi.wantsStages("Application/X-NDJSON; charset=utf-8"));
        assertFalse(YoutubeImportsApi.wantsStages(null));
        assertFalse(YoutubeImportsApi.wantsStages("application/json"));
        assertFalse(YoutubeImportsApi.wantsStages("*/*"));
    }
}
