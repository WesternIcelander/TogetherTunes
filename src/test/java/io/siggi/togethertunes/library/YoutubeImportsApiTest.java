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
        TestUploads.youtubeApi(db, config, new Permissions(db, settings), clock).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        String aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        token = new Sessions(db, clock).create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
    }

    private HttpRequest post(String accept) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/imports/youtube"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://youtu.be/" + ID + "\"}")).build();
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
