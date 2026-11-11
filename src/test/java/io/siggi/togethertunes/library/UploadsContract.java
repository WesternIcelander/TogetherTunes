package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.GuestNames;
import io.siggi.togethertunes.auth.Guests;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.IngestItems;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.IngestQueue;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.rooms.RoomGuestRules;
import io.siggi.togethertunes.settings.Settings;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chunked uploads over HTTP ({@link UploadsApi}, {@link Uploads}): chunks, resume, the SHA-256,
 * limits, {@code complete} with dedupe and re-ingest, ffprobe rejects, metadata, and a full
 * ingest of an uploaded FLAC with an embedded cover. Tests that need media skip without ffmpeg.
 */
abstract class UploadsContract {
    static final long MAX_BYTES = 20_000_000;
    static final int MAX_SECONDS = 4;

    @TempDir
    Path dir;

    protected Database db;
    private DataDir data;
    private Settings settings;
    private Permissions permissions;
    private ActorResolver actors;
    private final TestClock clock = new TestClock(System.currentTimeMillis());
    private final List<WebServer> servers = new ArrayList<>();
    private final List<IngestQueue> queues = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private Tools tools; // null without ffmpeg
    private Uploads uploads;
    private WebServer server;
    private String root;
    private String alice;
    private String aliceId;
    private String bob;
    private String bobId;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        data = DataDir.create(dir.resolve("data"));
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example"), 4, dir).load(null).config();
        settings = new Settings(db, config);
        actors = new ActorResolver(db, settings, clock);
        permissions = new Permissions(db, settings);
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        tools = ffmpeg != null && ffprobe != null ? new Tools(ffmpeg, ffprobe, null) : null;
        uploads = uploads();
        server = start(uploads);

        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        String rootId = accounts.createLocal("root", null, "root-password", true, null, "account.setup").id();
        root = sessions.create(rootId, Actor.Via.BEARER, null, null).token();
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, null, null).token();
        bobId = accounts.createLocal("bob", null, "bob-password", false, null, "account.register").id();
        bob = sessions.create(bobId, Actor.Via.BEARER, null, null).token();
    }

    @AfterEach
    void tearDown() {
        for (IngestQueue q : queues) {
            q.close();
        }
        for (WebServer s : servers) {
            s.close();
        }
        if (db != null) {
            db.close();
        }
    }

    /** Items the uploads told their library listener about. */
    private final List<String> libraryAdds = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Uploads uploads() {
        Tools t = tools != null ? tools : new Tools(null, null, null);
        Uploads u = new Uploads(db, data, t, new IngestJobs(db, clock), () -> {
        }, OutputEncoding.defaultFor(OutputProfile.P720_30), MAX_BYTES, MAX_SECONDS, new GuestUploadQuota(db, settings, clock), clock);
        u.libraryAddListener(libraryAdds::add);
        u.precheck(true); // the test server's base URL is https
        return u;
    }

    private WebServer start(Uploads u) throws Exception {
        Router router = new Router(actors.hook());
        new UploadsApi(u, new AddMediaPolicy(permissions, settings, RoomGuestRules.NO_ROOMS),
                new Guests(db, settings, clock, false, GuestNames.bundled())).register(router);
        WebServer s = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        servers.add(s);
        return s;
    }

    static String onPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String candidate : List.of(entry, "/opt/homebrew/bin", "/usr/local/bin")) {
                Path p = Path.of(candidate, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    private void needFfmpeg() {
        assumeTrue(tools != null, "ffmpeg/ffprobe not on PATH");
    }

    // ------------------------------------------------------------------ HTTP helpers

    record Reply(int status, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }

        String error() {
            try {
                return json().getAsJsonObject("error").get("code").getAsString();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private Reply call(WebServer to, String token, String method, String path, byte[] body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private Reply json(String token, String method, String path, String json) throws Exception {
        return call(server, token, method, path, json == null ? null : json.getBytes(), json == null ? Map.of()
                : Map.of("Content-Type", "application/json"));
    }

    private Reply expect(int status, Reply r) {
        assertEquals(status, r.status(), r.body());
        return r;
    }

    private String create(String token, String filename, long size) throws Exception {
        return expect(201, json(token, "POST", "/api/uploads",
                "{\"filename\":\"" + filename + "\",\"size\":" + size + "}")).json().get("uploadId").getAsString();
    }

    private Reply chunk(WebServer to, String token, String id, byte[] file, int from, int toExclusive) throws Exception {
        return call(to, token, "PUT", "/api/uploads/" + id, Arrays.copyOfRange(file, from, toExclusive),
                Map.of("Content-Range", "bytes " + from + "-" + (toExclusive - 1) + "/" + file.length,
                        "Content-Type", "application/octet-stream"));
    }

    private long received(String token, String id) throws Exception {
        return expect(200, json(token, "GET", "/api/uploads/" + id, null)).json().get("receivedBytes").getAsLong();
    }

    /** Uploads {@code file} in chunks of {@code chunk} bytes; returns the upload id. */
    private String upload(String token, String filename, byte[] file, int chunkSize) throws Exception {
        String id = create(token, filename, file.length);
        for (int from = 0; from < file.length; from += chunkSize) {
            int to = Math.min(file.length, from + chunkSize);
            assertEquals(to, expect(200, chunk(server, token, id, file, from, to)).json().get("receivedBytes").getAsLong());
        }
        return id;
    }

    private Reply complete(String token, String id, String body) throws Exception {
        return json(token, "POST", "/api/uploads/" + id + "/complete", body);
    }

    // ------------------------------------------------------------------ DB helpers

    private String insertItem(String status, String sha256) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source, content_sha256,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Existing', 'upload', ?, ?, 1, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, sha256);
                ps.setString(3, bobId);
                ps.setString(4, status);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String insertItem(String status, String sha256, String contentId) throws SQLException {
        String id = insertItem(status, sha256);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET content_id = ? WHERE id = ?")) {
                ps.setString(1, contentId);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String contentIdOf(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT content_id FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private Map<String, Object> item(String id) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, title, artist, album, source, content_sha256,"
                    + " added_by_user_id, status, output_profile, last_referenced_at FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= 10; i++) {
                        m.put(rs.getMetaData().getColumnName(i).toLowerCase(), rs.getObject(i));
                    }
                    return m;
                }
            }
        });
    }

    private long count(String sql, String... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private List<String> queuedJobs(String itemId) throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT kind FROM ingest_jobs WHERE item_id = ? AND state = 'queued'")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    private String libraryVia(String userId, String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?")) {
                ps.setString(1, userId);
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private List<String> partFiles() throws Exception {
        Path d = uploads.dir();
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return Uploads.sha256Hex(new ByteArrayInputStream(bytes));
    }

    private static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ media

    private void ffmpeg(String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of("-v", "error", "-y"));
        all.addAll(List.of(args));
        tools.ffmpeg(all).run();
    }

    private byte[] media(String name, String... args) throws Exception {
        Path out = dir.resolve("media").resolve(name);
        Files.createDirectories(out.getParent());
        List<String> all = new ArrayList<>(List.of(args));
        all.add(out.toString());
        ffmpeg(all.toArray(String[]::new));
        return Files.readAllBytes(out);
    }

    private byte[] taggedMp3() throws Exception {
        return media("tagged.mp3", "-f", "lavfi", "-i", "sine=f=440:d=2", "-c:a", "libmp3lame", "-b:a", "128k",
                "-metadata", "title=Tagged Song", "-metadata", "artist=Tag Artist", "-metadata", "album=Tag Album");
    }

    // ------------------------------------------------------------------ tests

    /**
     * Chunks append in order; a repeated or overlapping chunk only adds what's new; a chunk past
     * the received bytes is refused; the upload continues on a restarted server (no in-memory
     * hash), and the final hash is that of the whole file: complete finds the item with it.
     */
    @Test
    void chunksAreResumableAndIdempotent() throws Exception {
        byte[] file = random(1_000_000, 1);
        String existing = insertItem("ready", sha256(file));
        String id = create(alice, "noise.bin", file.length);
        assertEquals(List.of(id + ".part"), partFiles());
        assertEquals(0, received(alice, id));

        assertEquals(300_000, expect(200, chunk(server, alice, id, file, 0, 300_000)).json().get("receivedBytes").getAsLong());
        // the same chunk again (e.g. its response was lost): nothing changes
        assertEquals(300_000, expect(200, chunk(server, alice, id, file, 0, 300_000)).json().get("receivedBytes").getAsLong());
        // overlapping: only the new part is appended
        assertEquals(600_000, expect(200, chunk(server, alice, id, file, 200_000, 600_000)).json().get("receivedBytes").getAsLong());
        // a gap is refused
        Reply gap = chunk(server, alice, id, file, 700_000, 800_000);
        assertEquals(409, gap.status(), gap.body());
        assertEquals("upload_offset_mismatch", gap.error());
        assertEquals(600_000, received(alice, id));
        assertEquals(600_000, Files.size(uploads.dir().resolve(id + ".part")));

        // a restart: a new service without the running hash continues the upload
        Uploads restarted = uploads();
        WebServer second = start(restarted);
        assertEquals(1_000_000, expect(200, chunk(second, alice, id, file, 550_000, 1_000_000)).json()
                .get("receivedBytes").getAsLong());
        assertArrayEquals(file, Files.readAllBytes(uploads.dir().resolve(id + ".part")));

        JsonObject done = expect(200, call(second, alice, "POST", "/api/uploads/" + id + "/complete", null, Map.of())).json();
        assertEquals(existing, done.get("itemId").getAsString(), "the hash over all chunks matches the item");
        assertTrue(done.get("deduplicated").getAsBoolean());
        assertEquals(List.of(), partFiles(), "a deduplicated upload is discarded");
        assertEquals("upload", libraryVia(aliceId, existing));

        // complete again: the same answer; status shows the item; no more chunks
        assertEquals(done, expect(200, complete(alice, id, null)).json());
        JsonObject status = expect(200, json(alice, "GET", "/api/uploads/" + id, null)).json();
        assertEquals(1_000_000, status.get("receivedBytes").getAsLong());
        assertEquals(existing, status.get("itemId").getAsString());
        assertEquals("upload_completed", chunk(server, alice, id, file, 0, 10).error());
    }

    @Test
    void lowDiskSpaceRefusesNewUploadsAndChunks() throws Exception {
        java.util.concurrent.atomic.AtomicLong free = new java.util.concurrent.atomic.AtomicLong(10_000_000);
        java.util.concurrent.atomic.AtomicLong min = new java.util.concurrent.atomic.AtomicLong(5_000_000);
        uploads.diskSpace(new io.siggi.togethertunes.storage.DiskSpace(
                () -> new io.siggi.togethertunes.storage.DiskSpace.Usage(free.get(), 100_000_000), min::get,
                Duration.ZERO, System::nanoTime));
        byte[] file = random(1_000_000, 7);

        // Above the minimum, also after the declared size: started.
        String id = create(alice, "noise.bin", file.length);
        // Size-aware: 10 MB free - 5 000 001 B would leave less than the 5 MB minimum.
        Reply tooBig = json(alice, "POST", "/api/uploads", "{\"filename\":\"big.bin\",\"size\":5000001}");
        assertEquals(507, tooBig.status(), tooBig.body());
        assertEquals("insufficient_storage", tooBig.error());
        assertTrue(tooBig.body().contains("The server is low on disk space; adding media is paused."), tooBig.body());
        expect(201, json(alice, "POST", "/api/uploads", "{\"filename\":\"fits.bin\",\"size\":5000000}"));

        expect(200, chunk(server, alice, id, file, 0, 400_000));
        // Below the minimum: chunks of the started upload are refused, the bytes so far stay.
        free.set(4_999_999);
        Reply refused = chunk(server, alice, id, file, 400_000, 800_000);
        assertEquals(507, refused.status(), refused.body());
        assertEquals("insufficient_storage", refused.error());
        assertEquals(400_000, received(alice, id));
        assertEquals(507, json(alice, "POST", "/api/uploads", "{\"filename\":\"tiny.bin\",\"size\":1}").status());

        // Space freed: the upload continues where it stopped.
        free.set(6_000_000);
        assertEquals(1_000_000, expect(200, chunk(server, alice, id, file, 400_000, 1_000_000)).json()
                .get("receivedBytes").getAsLong());

        // A minimum of 0 turns the check off.
        min.set(0);
        free.set(0);
        expect(201, json(alice, "POST", "/api/uploads", "{\"filename\":\"any.bin\",\"size\":5000001}"));
    }

    @Test
    void chunkAndRequestErrors() throws Exception {
        byte[] file = random(1000, 2);
        String id = create(alice, "x.bin", file.length);
        // Content-Range missing, malformed, wrong total, past the end
        assertEquals("invalid_content_range", call(server, alice, "PUT", "/api/uploads/" + id, file, Map.of()).error());
        for (String range : List.of("bytes 0-99", "items 0-99/1000", "bytes 10-5/1000", "bytes 0-999/2000",
                "bytes 0-1000/1001", "bytes 0-1000/1000", "bytes 99999999999999999999-1/1000")) {
            Reply r = call(server, alice, "PUT", "/api/uploads/" + id, new byte[1], Map.of("Content-Range", range));
            assertEquals(400, r.status(), range + ": " + r.body());
            assertEquals("invalid_content_range", r.error(), range);
        }
        // `*` as the total is fine
        expect(200, call(server, alice, "PUT", "/api/uploads/" + id, Arrays.copyOf(file, 10),
                Map.of("Content-Range", "bytes 0-9/*")));
        // a body shorter than its range: what came is kept
        Reply shortBody = call(server, alice, "PUT", "/api/uploads/" + id, Arrays.copyOfRange(file, 10, 20),
                Map.of("Content-Range", "bytes 10-99/1000"));
        assertEquals(400, shortBody.status(), shortBody.body());
        assertEquals("chunk_length_mismatch", shortBody.error());
        assertEquals(20, received(alice, id));
        // a body longer than its range
        Reply longBody = call(server, alice, "PUT", "/api/uploads/" + id, Arrays.copyOfRange(file, 20, 120),
                Map.of("Content-Range", "bytes 20-29/1000"));
        assertEquals(413, longBody.status(), longBody.body());
        // the upload still completes into the right content afterwards
        for (int from = (int) received(alice, id); from < file.length; from += 300) {
            expect(200, chunk(server, alice, id, file, from, Math.min(file.length, from + 300)));
        }
        assertArrayEquals(file, Files.readAllBytes(uploads.dir().resolve(id + ".part")));

        // create: sizes, names, rooms
        assertEquals("invalid_size", json(alice, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":0}").error());
        assertEquals("invalid_size", json(alice, "POST", "/api/uploads", "{\"filename\":\"a.mp3\"}").error());
        Reply tooBig = json(alice, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":" + (MAX_BYTES + 1) + "}");
        assertEquals(413, tooBig.status(), tooBig.body());
        assertEquals("invalid_filename", json(alice, "POST", "/api/uploads", "{\"filename\":\" \",\"size\":5}").error());
        assertEquals("room_not_found", json(alice, "POST", "/api/uploads",
                "{\"filename\":\"a.mp3\",\"size\":5,\"roomId\":\"" + UuidV7.newId() + "\"}").error());
        expect(201, json(alice, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":" + MAX_BYTES + "}"));

        // someone else's upload, or a made-up id, doesn't exist
        assertEquals("upload_not_found", json(bob, "GET", "/api/uploads/" + id, null).error());
        assertEquals("upload_not_found", chunk(server, bob, id, file, 0, 10).error());
        assertEquals("upload_not_found", complete(bob, id, null).error());
        assertEquals("upload_not_found", json(alice, "GET", "/api/uploads/" + UuidV7.newId(), null).error());
        assertEquals("upload_not_found", json(alice, "GET", "/api/uploads/not-an-id", null).error());

        // complete before the last byte
        String unfinished = create(alice, "y.bin", 100);
        Reply early = complete(alice, unfinished, null);
        assertEquals(409, early.status(), early.body());
        assertEquals("upload_incomplete", early.error());

        // who may upload
        assertEquals(401, json(null, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":5}").status());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                ps.setString(1, bobId);
                ps.executeUpdate();
            }
            return null;
        });
        Reply forbidden = json(bob, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":5}");
        assertEquals(403, forbidden.status(), forbidden.body());
        expect(201, json(root, "POST", "/api/uploads", "{\"filename\":\"a.mp3\",\"size\":5}"));
    }

    /**
     * A tagged MP3: the item takes kind, title, artist and album from the probe, records the hash
     * and uploader, and is queued with the file in its staging directory; uploading the same
     * bytes again (by anyone) reuses it.
     */
    @Test
    void completeCreatesAnItemAndDedupes() throws Exception {
        needFfmpeg();
        byte[] mp3 = taggedMp3();
        String id = upload(alice, "whatever-name.mp3", mp3, 7000);
        JsonObject done = expect(200, complete(alice, id, null)).json();
        assertFalse(done.get("deduplicated").getAsBoolean());
        String itemId = done.get("itemId").getAsString();

        Map<String, Object> row = item(itemId);
        assertEquals("audio", row.get("kind"));
        assertEquals("Tagged Song", row.get("title"));
        assertEquals("Tag Artist", row.get("artist"));
        assertEquals("Tag Album", row.get("album"));
        assertEquals("upload", row.get("source"));
        assertEquals(sha256(mp3), row.get("content_sha256"));
        assertEquals(ContentId.of(new ByteArrayInputStream(mp3)), contentIdOf(itemId), "the content ID is recorded");
        assertEquals(aliceId, row.get("added_by_user_id"));
        assertEquals("pending", row.get("status"));
        assertEquals("720p30-3000k-v3", row.get("output_profile"));
        assertEquals(clock.millis(), ((Number) row.get("last_referenced_at")).longValue());
        assertEquals(List.of("upload"), queuedJobs(itemId));
        Path staged = IngestPipeline.stagingDir(data, itemId).resolve("source.mp3");
        assertArrayEquals(mp3, Files.readAllBytes(staged));
        assertEquals("upload", libraryVia(aliceId, itemId));
        // into a library: the job asks for the quality preset, and the library listener hears it
        assertEquals(1L, count("SELECT COUNT(*) FROM ingest_jobs WHERE item_id = ? AND tier = 'quality'", itemId));
        assertEquals(List.of(itemId), libraryAdds);
        assertEquals(List.of(), partFiles());
        assertEquals(done, expect(200, complete(alice, id, null)).json(), "complete is repeatable");

        // the same bytes from bob, without adding to his library: the same item
        String again = upload(bob, "copy.mp3", mp3, 100_000);
        JsonObject dup = expect(200, complete(bob, again, "{\"addToLibrary\":false,\"title\":\"Ignored\"}")).json();
        assertEquals(itemId, dup.get("itemId").getAsString());
        assertTrue(dup.get("deduplicated").getAsBoolean());
        assertNull(libraryVia(bobId, itemId));
        assertEquals(List.of(itemId), libraryAdds, "no library entry, no call");
        assertEquals("Tagged Song", item(itemId).get("title"), "a duplicate doesn't change the item");
        assertEquals(1, count("SELECT COUNT(*) FROM items"));
        assertEquals(1, count("SELECT COUNT(*) FROM ingest_jobs"));
        assertEquals(List.of(), partFiles());
    }

    /** Untagged files take their title from the file name; the uploader's title/artist win over tags; video is video. */
    @Test
    void metadataFromTagsOrFileName() throws Exception {
        needFfmpeg();
        byte[] wav = media("plain.wav", "-f", "lavfi", "-i", "sine=f=330:d=1", "-c:a", "pcm_s16le",
                "-map_metadata", "-1", "-fflags", "+bitexact");
        String a = upload(alice, "C:\\\\Music\\\\My_Great Track.wav", wav, 50_000);
        String itemA = expect(200, complete(alice, a, null)).json().get("itemId").getAsString();
        assertEquals("My Great Track", item(itemA).get("title"));
        assertNull(item(itemA).get("artist"));
        assertNull(item(itemA).get("album"));
        assertTrue(Files.exists(IngestPipeline.stagingDir(data, itemA).resolve("source.wav")));

        byte[] mp3 = taggedMp3();
        String b = upload(alice, "tagged.mp3", mp3, 1_000_000);
        String itemB = expect(200, complete(alice, b, "{\"title\":\"  My\\nOwn   Title \",\"artist\":\"Me\"}"))
                .json().get("itemId").getAsString();
        assertEquals("My Own Title", item(itemB).get("title"));
        assertEquals("Me", item(itemB).get("artist"));
        assertEquals("Tag Album", item(itemB).get("album"));

        byte[] video = media("clip.mp4", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=1", "-f", "lavfi",
                "-i", "sine=f=500:d=1", "-c:v", "libx264", "-c:a", "aac", "-shortest");
        String c = upload(alice, "clip", video, 1_000_000);
        String itemC = expect(200, complete(alice, c, "{\"mediaType\":\"music_video\"}")).json()
                .get("itemId").getAsString();
        assertEquals("video", item(itemC).get("kind"));
        assertEquals("clip", item(itemC).get("title"));
        assertTrue(Files.exists(IngestPipeline.stagingDir(data, itemC).resolve("source")),
                "no extension on the upload: a bare source file");
    }

    /** Unparseable, silent-video-only and too-long files are refused and deleted; nothing is created. */
    @Test
    void ffprobeRejects() throws Exception {
        needFfmpeg();
        byte[] junk = "This is not a media file.\n".repeat(200).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String a = upload(alice, "junk.mp3", junk, 5000);
        Reply r = complete(alice, a, null);
        assertEquals(422, r.status(), r.body());
        assertEquals("unreadable_media", r.error());
        assertEquals("upload_not_found", json(alice, "GET", "/api/uploads/" + a, null).error(), "a refused upload is gone");

        byte[] silentVideo = media("silent.mp4", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=1", "-c:v", "libx264");
        String b = upload(alice, "silent.mp4", silentVideo, 1_000_000);
        r = complete(alice, b, null);
        assertEquals(422, r.status(), r.body());
        assertEquals("no_audio", r.error());

        byte[] longFile = media("long.mp3", "-f", "lavfi", "-i", "sine=f=440:d=" + (MAX_SECONDS + 2), "-c:a",
                "libmp3lame");
        String c = upload(alice, "long.mp3", longFile, 1_000_000);
        r = complete(alice, c, null);
        assertEquals(422, r.status(), r.body());
        assertEquals("too_long", r.error());
        assertTrue(r.json().getAsJsonObject("error").get("message").getAsString().contains("limit is " + MAX_SECONDS + " s"),
                r.body());

        assertEquals(0, count("SELECT COUNT(*) FROM items"));
        assertEquals(0, count("SELECT COUNT(*) FROM uploads"));
        assertEquals(List.of(), partFiles());
    }

    /** A purged (or failed) upload item gets the new copy: the same row, a new job, referenced now. */
    @Test
    void purgedAndFailedItemsAreIngestedAgainIntoTheSameRow() throws Exception {
        needFfmpeg();
        byte[] mp3 = taggedMp3();
        for (String status : List.of("purged", "failed")) {
            String existing = insertItem(status, sha256(mp3));
            Path staging = IngestPipeline.stagingDir(data, existing);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("source.old"), "leftover of a failed job");
            clock.advance(1000);
            String id = upload(alice, "again.mp3", mp3, 1_000_000);
            JsonObject done = expect(200, complete(alice, id, "{\"title\":\"New Title\"}")).json();
            assertEquals(existing, done.get("itemId").getAsString(), status);
            assertTrue(done.get("deduplicated").getAsBoolean());
            Map<String, Object> row = item(existing);
            assertEquals("pending", row.get("status"), status);
            assertEquals("Existing", row.get("title"), "the row keeps its metadata");
            assertEquals(bobId, row.get("added_by_user_id"));
            assertEquals(clock.millis(), ((Number) row.get("last_referenced_at")).longValue());
            assertEquals(List.of("upload"), queuedJobs(existing));
            try (Stream<Path> files = Files.list(staging)) {
                assertEquals(List.of("source.mp3"), files.map(p -> p.getFileName().toString()).toList());
            }
            assertEquals("upload", libraryVia(aliceId, existing));
            // done with this row: mark its job finished so the other status starts clean
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'ready', content_sha256 = NULL,"
                        + " content_id = NULL WHERE id = ?")) {
                    ps.setString(1, existing);
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    private Reply startWithContentId(String token, long size, String contentId, String extra) throws Exception {
        return json(token, "POST", "/api/uploads", "{\"filename\":\"known.mp3\",\"size\":" + size
                + ",\"contentId\":\"" + contentId + "\"" + extra + "}");
    }

    /**
     * The pre-check: a content ID of a ready, pending or processing upload item gives that item
     * without any bytes, with a library entry (unless addToLibrary is false) and a fresh
     * last_referenced_at; the upload counts as completed, so its id works like any other.
     */
    @Test
    void precheckHitAddsTheItemWithoutBytes() throws Exception {
        byte[] file = random(30_000, 7);
        String contentId = ContentId.of(new ByteArrayInputStream(file));
        String ready = insertItem("ready", sha256(file), contentId);
        clock.advance(5000);
        JsonObject hit = expect(200, startWithContentId(alice, file.length, contentId, "")).json();
        assertTrue(hit.get("deduplicated").getAsBoolean());
        assertEquals(ready, hit.get("itemId").getAsString());
        assertEquals("ready", hit.get("status").getAsString());
        String uploadId = hit.get("uploadId").getAsString();
        assertEquals("upload", libraryVia(aliceId, ready));
        assertEquals(List.of(ready), libraryAdds);
        assertEquals(clock.millis(), ((Number) item(ready).get("last_referenced_at")).longValue());
        assertEquals(List.of(), partFiles(), "no bytes, no part file");
        JsonObject status = expect(200, json(alice, "GET", "/api/uploads/" + uploadId, null)).json();
        assertEquals(ready, status.get("itemId").getAsString());
        assertEquals(file.length, status.get("receivedBytes").getAsLong());
        JsonObject done = expect(200, complete(alice, uploadId, null)).json();
        assertEquals(ready, done.get("itemId").getAsString());
        assertTrue(done.get("deduplicated").getAsBoolean());
        assertEquals(1, count("SELECT COUNT(*) FROM items"));
        assertEquals(0, count("SELECT COUNT(*) FROM ingest_jobs"));

        // without a library entry; and a pending item counts too
        insertItem("purged", sha256(file), contentId); // an older purged row with the same ID doesn't get in the way
        sql("UPDATE items SET status = 'pending' WHERE id = ?", ready);
        JsonObject bobs = expect(200, startWithContentId(bob, file.length, contentId, ",\"addToLibrary\":false")).json();
        assertEquals(ready, bobs.get("itemId").getAsString());
        assertEquals("pending", bobs.get("status").getAsString());
        assertNull(libraryVia(bobId, ready));
        // nothing in the reply says whose libraries hold the item
        assertEquals(java.util.Set.of("uploadId", "chunkSize", "size", "deduplicated", "itemId", "status"), bobs.keySet());
    }

    /**
     * No shortcut for a purged or failed match, an unknown, malformed or wrong-size ID, a non-upload
     * item, or with the pre-check off (a non-https server): a normal upload starts.
     */
    @Test
    void precheckMissesStartANormalUpload() throws Exception {
        byte[] file = random(20_000, 9);
        String contentId = ContentId.of(new ByteArrayInputStream(file));
        String purged = insertItem("purged", sha256(file), contentId);
        expect(201, startWithContentId(alice, file.length, contentId, ""));
        sql("UPDATE items SET status = 'failed' WHERE id = ?", purged);
        expect(201, startWithContentId(alice, file.length, contentId, ""));
        sql("UPDATE items SET status = 'ready' WHERE id = ?", purged);
        expect(201, startWithContentId(alice, file.length, ContentId.of(new ByteArrayInputStream(random(20_000, 10))), ""));
        expect(201, startWithContentId(alice, file.length, contentId.toUpperCase(), ""));
        expect(201, startWithContentId(alice, file.length, "nonsense", ""));
        expect(201, startWithContentId(alice, file.length + 1, contentId, ""));
        sql("UPDATE items SET source = 'youtube', youtube_id = 'dQw4w9WgXcQ' WHERE id = ?", purged);
        expect(201, startWithContentId(alice, file.length, contentId, ""));
        sql("UPDATE items SET source = 'upload', youtube_id = NULL WHERE id = ?", purged);
        expect(200, startWithContentId(alice, file.length, contentId, ""));
        uploads.precheck(false);
        expect(201, startWithContentId(alice, file.length, contentId, ""));
        assertNull(libraryVia(bobId, purged));
        // a wrong claim only means no shortcut: the bytes are hashed on completion as always
        uploads.precheck(true);
        expect(400, json(alice, "POST", "/api/uploads", "{\"filename\":\"x.mp3\",\"size\":0,\"contentId\":\""
                + contentId + "\"}"));
        // the usual checks still come first
        expect(401, startWithContentId(null, file.length, contentId, ""));
    }

    private String mediaType(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT media_type FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Classify on library add (docs/media-types.md#media-types): a new video upload going into the
     * library needs a media type. Without one the upload still completes (no library entry, a
     * quick-tier job) and the answer is 409 needs_classification with the item and a suggestion
     * from the file name; a repeated complete answers 200 without adding it. With one, the item
     * gets the type and the entry. An audio upload never asks, nor does one with addToLibrary false.
     */
    @Test
    void videoUploadsAreClassifiedForTheLibrary() throws Exception {
        needFfmpeg();
        byte[] video = media("ep.mp4", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=1", "-f", "lavfi",
                "-i", "sine=f=500:d=1", "-c:v", "libx264", "-c:a", "aac", "-shortest");
        String a = upload(alice, "My.Great_Show.S02E05.1080p.mp4", video, 1_000_000);
        Reply r = complete(alice, a, null);
        assertEquals(409, r.status(), r.body());
        assertEquals("needs_classification", r.error());
        JsonObject error = r.json().getAsJsonObject("error");
        String itemId = error.get("itemId").getAsString();
        JsonObject suggested = error.getAsJsonObject("suggested");
        assertEquals("show", suggested.get("mediaType").getAsString());
        JsonObject show = suggested.getAsJsonObject("show");
        assertEquals("My Great Show", show.get("name").getAsString());
        assertEquals(2, show.get("season").getAsInt());
        assertEquals(5, show.get("episode").getAsInt());
        assertEquals("uncategorized", mediaType(itemId));
        assertNull(libraryVia(aliceId, itemId), "no library entry without a type");
        assertTrue(libraryAdds.isEmpty());
        assertEquals("pending", item(itemId).get("status"), "the upload itself went through");
        assertEquals(List.of("upload"), queuedJobs(itemId));
        // a repeated complete answers with the item, still without adding it
        assertEquals(itemId, expect(200, complete(alice, a, null)).json().get("itemId").getAsString());
        assertNull(libraryVia(aliceId, itemId));

        // bob uploads the same file with a type: deduplicated, classified, in his library
        String b = upload(bob, "whatever.mp4", video, 1_000_000);
        r = expect(200, complete(bob, b, "{\"mediaType\":\"show\",\"show\":{\"name\":\"My Great Show\","
                + "\"season\":2,\"episode\":5}}"));
        assertEquals(itemId, r.json().get("itemId").getAsString());
        assertEquals("show", mediaType(itemId));
        assertEquals("upload", libraryVia(bobId, itemId));
        // now classified: alice's next upload of it doesn't ask, and a type sent along is ignored
        String a2 = upload(alice, "again.mp4", video, 1_000_000);
        expect(200, complete(alice, a2, "{\"mediaType\":\"movie\"}"));
        assertEquals("show", mediaType(itemId));
        assertEquals("upload", libraryVia(aliceId, itemId));

        // bad classifications are refused before anything happens
        byte[] other = media("other.mp4", "-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=1", "-f", "lavfi",
                "-i", "sine=f=700:d=1", "-c:v", "libx264", "-c:a", "aac", "-shortest");
        String c = upload(alice, "other.mp4", other, 1_000_000);
        assertEquals("invalid_media_type", expect(400, complete(alice, c, "{\"mediaType\":\"uncategorized\"}")).error());
        assertEquals("show_required", expect(400, complete(alice, c, "{\"mediaType\":\"show\"}")).error());
        assertEquals("not_a_show", expect(400, complete(alice, c, "{\"mediaType\":\"movie\","
                + "\"show\":{\"name\":\"X\",\"episode\":1}}")).error());
        // a video kept out of the library doesn't ask
        r = expect(200, complete(alice, c, "{\"addToLibrary\":false}"));
        assertEquals("uncategorized", mediaType(r.json().get("itemId").getAsString()));

        // the pre-check of an uncategorized video asks too (the upload is complete either way)
        String contentId = contentIdOf(itemId);
        sql("UPDATE items SET media_type = 'uncategorized', show_name = NULL, season_number = NULL,"
                + " episode_number = NULL WHERE id = ?", itemId);
        sql("DELETE FROM library_entries WHERE item_id = ?", itemId);
        r = json(alice, "POST", "/api/uploads", "{\"filename\":\"x.mp4\",\"size\":" + video.length
                + ",\"contentId\":\"" + contentId + "\"}");
        assertEquals(409, r.status(), r.body());
        assertEquals(itemId, r.json().getAsJsonObject("error").get("itemId").getAsString());
        String precheckUpload = r.json().getAsJsonObject("error").get("uploadId").getAsString();
        assertEquals(itemId, expect(200, json(alice, "GET", "/api/uploads/" + precheckUpload, null)).json()
                .get("itemId").getAsString(), "the upload is complete; queue.add can take it");
        assertNull(libraryVia(aliceId, itemId));
        r = expect(200, json(alice, "POST", "/api/uploads", "{\"filename\":\"x.mp4\",\"size\":" + video.length
                + ",\"contentId\":\"" + contentId + "\",\"mediaType\":\"movie\"}"));
        assertEquals(itemId, r.json().get("itemId").getAsString());
        assertEquals("movie", mediaType(itemId));
        assertEquals("upload", libraryVia(aliceId, itemId));
    }

    private void sql(String sql, String... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setString(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Two uploads of the same new file completed at once make one item. */
    @Test
    void concurrentCompletesMakeOneItem() throws Exception {
        needFfmpeg();
        byte[] mp3 = taggedMp3();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(upload(i % 2 == 0 ? alice : bob, "same.mp3", mp3, 1_000_000));
        }
        ExecutorService pool = Executors.newFixedThreadPool(ids.size());
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                String token = i % 2 == 0 ? alice : bob;
                String id = ids.get(i);
                futures.add(pool.submit((Callable<Reply>) () -> complete(token, id, null)));
            }
            AtomicInteger fresh = new AtomicInteger();
            String itemId = null;
            for (Future<Reply> f : futures) {
                JsonObject done = expect(200, f.get(60, TimeUnit.SECONDS)).json();
                if (itemId == null) {
                    itemId = done.get("itemId").getAsString();
                }
                assertEquals(itemId, done.get("itemId").getAsString());
                if (!done.get("deduplicated").getAsBoolean()) {
                    fresh.incrementAndGet();
                }
            }
            assertEquals(1, fresh.get(), "exactly one complete made the item");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM items"));
    }

    /** An uploaded FLAC with an embedded cover goes all the way through ingest: cover art, original kept. */
    @Test
    void uploadedFileIngestsWithItsEmbeddedCover() throws Exception {
        needFfmpeg();
        Path png = dir.resolve("art.png");
        ffmpeg("-f", "lavfi", "-i", "testsrc2=s=300x300", "-frames:v", "1", png.toString());
        byte[] flac = media("song.flac", "-f", "lavfi", "-i", "sine=f=440:r=44100:d=3", "-i", png.toString(),
                "-map", "0:a", "-map", "1:v", "-c:a", "flac", "-c:v", "png", "-disposition:v", "attached_pic",
                "-metadata", "title=Flac Song", "-metadata", "ARTIST=Flac Artist");
        String id = upload(alice, "song.flac", flac, 64 * 1024);
        String itemId = expect(200, complete(alice, id, null)).json().get("itemId").getAsString();
        assertEquals("Flac Song", item(itemId).get("title"));
        assertEquals("Flac Artist", item(itemId).get("artist"));

        ReferenceCard reference = ReferenceCard.ensure(tools, data.cards(), OutputEncoding.defaultFor(OutputProfile.P720_30), null);
        IngestJobs jobs = new IngestJobs(db);
        IngestQueue queue = new IngestQueue(jobs, 1, new IngestPipeline(tools, data, new IngestItems(db), reference,
                MAX_SECONDS), Clock.systemUTC(), Duration.ofMillis(100), Duration.ZERO);
        queues.add(queue);
        queue.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        IngestJob job = jobs.latestForItem(itemId).orElseThrow();
        while (!job.state().finished() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            job = jobs.latestForItem(itemId).orElseThrow();
        }
        assertEquals(IngestJob.State.DONE, job.state(), job.log());
        assertEquals("ready", item(itemId).get("status"));
        Path itemDir = IngestPipeline.itemDir(data, itemId);
        try (Stream<Path> files = Files.list(itemDir)) {
            assertEquals(List.of("original.flac", "v1"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        try (Stream<Path> files = Files.list(itemDir.resolve("v1"))) {
            assertEquals(List.of("audio.m4a", "card.mp4", "cover.jpg", "index.bin"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertArrayEquals(flac, Files.readAllBytes(itemDir.resolve("original.flac")));
        assertTrue(Files.size(itemDir.resolve("v1").resolve("cover.jpg")) > 1000, "the embedded cover became cover.jpg");
        assertFalse(Files.exists(IngestPipeline.stagingDir(data, itemId)), "the staging dir is gone");
    }

    /** Uploads untouched for a day go, with their files; so do part files without a row. */
    @Test
    void sweepDeletesAbandonedUploads() throws Exception {
        byte[] file = random(100, 4);
        String old = create(alice, "old.bin", 1000);
        expect(200, chunk(server, alice, old, Arrays.copyOf(file, 1000), 0, 100));
        clock.advance(Uploads.STALE_AFTER.toMillis() - 1000);
        String fresh = create(alice, "fresh.bin", 1000);
        Files.writeString(uploads.dir().resolve("stray.part"), "x");
        assertEquals(1, uploads.sweep(), "only the stray file so far");
        clock.advance(2000);
        assertEquals(1, uploads.sweep());
        assertEquals("upload_not_found", json(alice, "GET", "/api/uploads/" + old, null).error());
        assertEquals(List.of(fresh + ".part"), partFiles());
        assertEquals(0, received(alice, fresh));
        assertNotEquals(old, fresh);
    }
}
