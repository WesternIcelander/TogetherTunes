package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.storage.DiskSpace;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The library export zip upload over HTTP ({@link LibraryImportUploads}, {@link LibraryImportApi}):
 * chunks and resume, the size cap, the free-space check, admins only, and the cleanup.
 */
class LibraryImportUploadsTest {
    static final long CHUNK = LibraryImportUploads.CHUNK_SIZE;

    @TempDir
    Path dir;

    private Database db;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private LibraryImports imports;
    private LibraryImportUploads zips;
    private final AtomicLong free = new AtomicLong(Long.MAX_VALUE / 4);
    private String admin;
    private String user;
    private String aliceId;
    private String adminId;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example", "TOGETHERTUNES_LIBRARY_IMPORT_MAX_ZIP_BYTES", "100 MiB"), 4, dir)
                .load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Settings settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        Tools tools = Tools.from(config);
        imports = new LibraryImports(db, TestUploads.uploads(db, dir, config, tools, clock),
                new Playlists(db, TestUploads.youtube(db, config, tools, clock), clock),
                dir.resolve("data/tmp/imports"), List.of(), clock);
        zips = new LibraryImportUploads(imports, config.libraryImportMaxZipBytes(), clock);
        zips.diskSpace(new DiskSpace(() -> new DiskSpace.Usage(free.get(), Long.MAX_VALUE / 2), () -> 1000,
                Duration.ZERO, System::nanoTime));
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        new LibraryImportApi(imports, zips, permissions).register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        adminId = accounts.createLocal("root", "Root", "root-password", true, null, "account.setup").id();
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        Sessions sessions = new Sessions(db, clock);
        admin = sessions.create(adminId, Actor.Via.BEARER, "test", null).token();
        user = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() throws Exception {
        imports.stopWorker();
        server.close();
        db.close();
    }

    private HttpResponse<String> call(String token, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> call(String token, String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static void expectError(int status, String code, HttpResponse<String> r) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, json(r).getAsJsonObject("error").get("code").getAsString(), r.body());
    }

    private static final String BASE = "/api/admin/library-import/uploads";

    private String create(long size) throws Exception {
        HttpResponse<String> r = call(admin, "POST", BASE, "{\"filename\":\"export.zip\",\"size\":" + size + "}");
        assertEquals(201, r.statusCode(), r.body());
        JsonObject o = json(r);
        assertEquals(CHUNK, o.get("chunkSize").getAsLong());
        assertEquals(size, o.get("size").getAsLong());
        assertEquals(0, o.get("receivedBytes").getAsLong());
        assertFalse(o.get("complete").getAsBoolean());
        return o.get("uploadId").getAsString();
    }

    private HttpResponse<String> chunk(String id, int n, byte[] data) throws Exception {
        long start = n * CHUNK;
        byte[] part = Arrays.copyOfRange(data, (int) start, (int) Math.min(data.length, start + CHUNK));
        return call(admin, "PUT", BASE + "/" + id + "/chunks/" + n, part);
    }

    /** A zip of {@code entries} (name → content), padded with a stored random entry to over two chunks. */
    static byte[] zip(Map<String, byte[]> entries, int padBytes) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
            if (padBytes > 0) {
                byte[] pad = new byte[padBytes];
                new Random(1).nextBytes(pad);
                z.putNextEntry(new ZipEntry("padding.bin"));
                z.write(pad);
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Path importsDir() {
        return dir.resolve("data/tmp/imports");
    }

    @Test
    void chunkedUploadResumesAndCompletes() throws Exception {
        byte[] data = zip(Map.of("Library.xml", LibraryImportsTest.libraryXml().getBytes(StandardCharsets.UTF_8)),
                (int) (2 * CHUNK + 1000));
        assertTrue(data.length > 2 * CHUNK && data.length < 3 * CHUNK);
        String id = create(data.length);

        // chunk 1 before chunk 0: refused, nothing written
        // (small bodies where the server refuses before reading)
        expectError(409, "upload_offset_mismatch", call(admin, "PUT", BASE + "/" + id + "/chunks/1", new byte[10]));
        HttpResponse<String> r = chunk(id, 0, data);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(CHUNK, json(r).get("receivedBytes").getAsLong());
        // repeating chunk 0 (a lost response) is harmless
        assertEquals(CHUNK, json(chunk(id, 0, data)).get("receivedBytes").getAsLong());
        // a short chunk is refused (its bytes stay; the next try sends it again)
        HttpResponse<String> shortChunk = call(admin, "PUT", BASE + "/" + id + "/chunks/1",
                Arrays.copyOfRange(data, (int) CHUNK, (int) CHUNK + 10));
        expectError(400, "chunk_length_mismatch", shortChunk);
        expectError(400, "invalid_chunk", call(admin, "PUT", BASE + "/" + id + "/chunks/3", new byte[10]));
        expectError(400, "invalid_chunk", call(admin, "PUT", BASE + "/" + id + "/chunks/x", new byte[10]));
        expectError(409, "upload_incomplete", call(admin, "POST", BASE + "/" + id + "/complete", (String) null));

        // resume: GET says where to go on
        JsonObject status = json(call(admin, "GET", BASE + "/" + id, (String) null));
        long received = status.get("receivedBytes").getAsLong();
        assertTrue(received >= CHUNK && received < 2 * CHUNK, status.toString());
        for (int n = (int) (received / CHUNK); n < 3; n++) {
            HttpResponse<String> c = chunk(id, n, data);
            assertEquals(200, c.statusCode(), c.body());
        }
        JsonObject done = json(call(admin, "POST", BASE + "/" + id + "/complete", (String) null));
        assertTrue(done.get("complete").getAsBoolean());
        assertEquals(data.length, done.get("receivedBytes").getAsLong());
        // repeating complete answers the same
        assertEquals(done, json(call(admin, "POST", BASE + "/" + id + "/complete", (String) null)));
        expectError(409, "upload_complete", chunk(id, 2, data));
        Path zip = importsDir().resolve(id + ".zip");
        assertArrayEquals(data, Files.readAllBytes(zip));
        assertFalse(Files.exists(importsDir().resolve(id + ".part")));
        assertFalse(Files.exists(importsDir().resolve(id + ".json")));

        // the upload id is an import source; a cancelled import deletes its zip
        HttpResponse<String> check = call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import",
                "{\"source\":{\"uploadId\":\"" + id + "\"}}");
        assertEquals(201, check.statusCode(), check.body());
        assertEquals("upload", json(check).getAsJsonObject("source").get("type").getAsString());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertEquals("ready", imports.latest(aliceId).state());
        assertTrue(Files.exists(zip), "kept while the import waits for Import");
        expectError(404, "upload_not_found", call(admin, "GET", BASE + "/" + UuidV7.newId(), (String) null));
        assertEquals(200, call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import/cancel",
                (String) null).statusCode());
        assertFalse(Files.exists(zip), "deleted with the cancelled import");
        expectError(404, "upload_not_found", call(admin, "GET", BASE + "/" + id, (String) null));
    }

    @Test
    void refusedUpFront() throws Exception {
        expectError(413, "payload_too_large", call(admin, "POST", BASE,
                "{\"filename\":\"export.zip\",\"size\":" + (100L * 1024 * 1024 + 1) + "}"));
        expectError(400, "invalid_filename", call(admin, "POST", BASE, "{\"filename\":\"export.tar\",\"size\":10}"));
        expectError(400, "invalid_size", call(admin, "POST", BASE, "{\"filename\":\"export.zip\",\"size\":0}"));
        expectError(400, "invalid_size", call(admin, "POST", BASE, "{\"filename\":\"export.zip\"}"));
        // free space: 1000 bytes minimum
        free.set(50_000);
        expectError(507, DiskSpace.ERROR_CODE, call(admin, "POST", BASE,
                "{\"filename\":\"export.zip\",\"size\":49001}"));
        String id = create(49_000);
        // the space went meanwhile: the rest no longer fits
        free.set(10_000);
        expectError(507, DiskSpace.ERROR_CODE, call(admin, "PUT", BASE + "/" + id + "/chunks/0", new byte[49_000]));
        free.set(Long.MAX_VALUE / 4);
        // not a zip: refused on complete and deleted
        assertEquals(200, call(admin, "PUT", BASE + "/" + id + "/chunks/0", new byte[49_000]).statusCode());
        expectError(400, "invalid_zip", call(admin, "POST", BASE + "/" + id + "/complete", (String) null));
        expectError(404, "upload_not_found", call(admin, "GET", BASE + "/" + id, (String) null));
        try (var files = Files.list(importsDir())) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void adminsOnly() throws Exception {
        String id = create(100);
        for (String token : new String[] {null, user}) {
            int want = token == null ? 401 : 403;
            assertEquals(want, call(token, "POST", BASE, "{\"filename\":\"a.zip\",\"size\":10}").statusCode());
            assertEquals(want, call(token, "GET", BASE + "/" + id, (String) null).statusCode());
            assertEquals(want, call(token, "PUT", BASE + "/" + id + "/chunks/0", new byte[100]).statusCode());
            assertEquals(want, call(token, "POST", BASE + "/" + id + "/complete", (String) null).statusCode());
            assertEquals(want, call(token, "DELETE", BASE + "/" + id, (String) null).statusCode());
        }
        assertEquals(0, json(call(admin, "GET", BASE + "/" + id, (String) null)).get("receivedBytes").getAsLong());
        assertEquals(204, call(admin, "DELETE", BASE + "/" + id, (String) null).statusCode());
        expectError(404, "upload_not_found", call(admin, "DELETE", BASE + "/" + id, (String) null));
        expectError(404, "upload_not_found", call(admin, "GET", BASE + "/not-an-id", (String) null));
    }

    /** A completed upload of a zip with just a Library.xml. */
    private String uploaded() throws Exception {
        byte[] data = zip(Map.of("Library.xml", LibraryImportsTest.libraryXml().getBytes(StandardCharsets.UTF_8)), 0);
        String id = create(data.length);
        assertEquals(200, chunk(id, 0, data).statusCode());
        assertEquals(200, call(admin, "POST", BASE + "/" + id + "/complete", (String) null).statusCode());
        return id;
    }

    private void setState(String state) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE library_imports SET state = ? WHERE id = ?")) {
                ps.setString(1, state);
                ps.setString(2, imports.latest(aliceId).id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void cleanup() throws Exception {
        Path imp = importsDir();
        // in progress, completed and unused, completed and used by a ready import
        String partial = create(1000);
        String unused = uploaded();
        String used = uploaded();
        assertEquals(201, call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import",
                "{\"source\":{\"uploadId\":\"" + used + "\"}}").statusCode());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        Files.writeString(imp.resolve("stray.txt"), "x");

        // at startup: a completed zip no import uses goes, the rest stays
        assertEquals(2, zips.sweep(true));
        assertFalse(Files.exists(imp.resolve(unused + ".zip")));
        assertFalse(Files.exists(imp.resolve("stray.txt")));
        assertTrue(Files.exists(imp.resolve(partial + ".part")));
        assertTrue(Files.exists(imp.resolve(used + ".zip")));

        // after 7 days unused: everything not being imported from goes; a ready import of it fails
        clock.advance(Duration.ofDays(7).toMillis() + 1);
        setState("importing");
        assertEquals(1, zips.sweep(false), "the partial upload");
        assertTrue(Files.exists(imp.resolve(used + ".zip")), "an import is reading it");
        setState("ready");
        assertEquals(1, zips.sweep(false));
        assertFalse(Files.exists(imp.resolve(used + ".zip")));
        LibraryImports.Import i = imports.latest(aliceId);
        assertEquals("failed", i.state());
        assertTrue(i.error().contains("7 days"), i.error());
        try (var files = Files.list(imp)) {
            assertEquals(0, files.count());
        }

        // a recently touched upload is kept
        String fresh = create(10);
        Files.setLastModifiedTime(imp.resolve(fresh + ".part"), FileTime.fromMillis(clock.millis() - 1000));
        assertEquals(0, zips.sweep(false));
        assertTrue(Files.exists(imp.resolve(fresh + ".part")));
    }

    @Test
    void importEndsDeleteTheZip() throws Exception {
        // a check that can't work (no Library.xml) deletes the upload
        byte[] data = zip(Map.of("readme.txt", "x".getBytes(StandardCharsets.UTF_8)), 0);
        String id = create(data.length);
        assertEquals(200, chunk(id, 0, data).statusCode());
        assertEquals(200, call(admin, "POST", BASE + "/" + id + "/complete", (String) null).statusCode());
        expectError(400, "no_library_xml", call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import",
                "{\"source\":{\"uploadId\":\"" + id + "\"}}"));
        assertFalse(Files.exists(importsDir().resolve(id + ".zip")));

        // a re-check of the same zip replaces the ready import but keeps the zip
        String again = uploaded();
        String src = "{\"source\":{\"uploadId\":\"" + again + "\"}}";
        assertEquals(201, call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import", src).statusCode());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertEquals(201, call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import", src).statusCode());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertTrue(Files.exists(importsDir().resolve(again + ".zip")));
        // deleting it while the import is checking or importing is refused; a ready one fails
        setState("importing");
        expectError(409, "import_running", call(admin, "DELETE", BASE + "/" + again, (String) null));
        setState("ready");
        // Import: nothing to import (every track is missing), so it is done at once and the zip goes
        HttpResponse<String> start = call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import/start",
                (String) null);
        assertEquals(200, start.statusCode(), start.body());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertEquals("done", imports.latest(aliceId).state());
        assertFalse(Files.exists(importsDir().resolve(again + ".zip")));
        // starting again needs a check; a ready import whose zip is gone can't start
        String third = uploaded();
        assertEquals(201, call(admin, "POST", "/api/admin/users/" + aliceId + "/library-import",
                "{\"source\":{\"uploadId\":\"" + third + "\"}}").statusCode());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        Files.delete(importsDir().resolve(third + ".zip"));
        expectError(404, "upload_not_found", call(admin, "POST", "/api/admin/users/" + aliceId
                + "/library-import/start", (String) null));
    }
}
