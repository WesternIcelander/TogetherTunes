package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.settings.Settings;
import io.siggi.togethertunes.storage.DiskSpace;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Library imports end to end ({@link LibraryImports}): a generated export (short sine-wave files
 * and a {@code Library.xml}) checked and imported into a user's library through the upload path
 * (real ffprobe; no ingest workers, so the items stay pending), and the path rules.
 */
class LibraryImportsTest {
    static final String ROOT = "/Users/someone/Music/Music/Media.localized/";
    static final long ADDED_ONE = Instant.parse("2015-01-01T00:00:00Z").toEpochMilli();
    static final long ADDED_TWO = Instant.parse("2016-06-06T06:06:06Z").toEpochMilli();
    static final String NFC_NAME = Normalizer.normalize("Café", Normalizer.Form.NFC);
    static final String NFD_NAME = Normalizer.normalize("Café", Normalizer.Form.NFD);

    @TempDir
    static Path fixtures;
    static Path allowed;
    static Path export;
    static String ffmpeg;
    static String ffprobe;

    @TempDir
    Path dir;
    Database db;
    Uploads uploads;
    Playlists playlists;
    LibraryImports imports;
    String adminId;
    String aliceId;

    @BeforeAll
    static void makeExport() throws Exception {
        ffmpeg = UploadsContract.onPath("ffmpeg");
        ffprobe = UploadsContract.onPath("ffprobe");
        allowed = fixtures.resolve("allowed");
        export = allowed.resolve("export");
        Path music = export.resolve("Media.localized/Music");
        Files.createDirectories(music.resolve("Artist A/Album"));
        Files.createDirectories(music.resolve("Artist B/Other"));
        Files.createDirectories(music.resolve("Movies"));
        Files.writeString(export.resolve("Library.xml"), libraryXml(), StandardCharsets.UTF_8);
        Files.write(music.resolve("Artist B/Other/Locked.m4p"), new byte[] {1, 2, 3, 4});
        Files.writeString(music.resolve("Artist B/Other/notes.txt"), "not in the list");
        if (ffmpeg == null || ffprobe == null) {
            before = snapshot();
            return;
        }
        media(music.resolve("Artist A/Album/01 One.m4a"), "-f", "lavfi", "-i", "sine=f=440:d=1", "-c:a", "aac");
        Files.copy(music.resolve("Artist A/Album/01 One.m4a"), music.resolve("Artist A/Album/01 One copy.m4a"));
        media(music.resolve("Artist B/Other/" + NFC_NAME + ".mp3"), "-f", "lavfi", "-i", "sine=f=550:d=1", "-c:a",
                "libmp3lame");
        media(music.resolve("Artist A/Album/03 Three.flac"), "-f", "lavfi", "-i", "sine=f=660:d=1", "-c:a", "flac");
        media(music.resolve("Movies/Clip.m4v"), "-f", "lavfi", "-i", "testsrc2=s=160x120:r=25:d=1", "-f", "lavfi",
                "-i", "sine=f=700:d=1", "-c:v", "libx264", "-c:a", "aac", "-shortest", "-f", "mp4");
        before = snapshot();
    }

    private static void media(Path out, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y"));
        cmd.addAll(List.of(args));
        cmd.add(out.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), log);
    }

    private static String loc(String path) {
        return "file://" + (ROOT + path).replace(" ", "%20");
    }

    private static String track(int id, String name, String artist, String location, String extra) {
        return "<key>" + id + "</key><dict><key>Track ID</key><integer>" + id + "</integer>"
                + "<key>Name</key><string>" + name + "</string>"
                + (artist == null ? "" : "<key>Artist</key><string>" + artist + "</string>")
                + "<key>Persistent ID</key><string>T" + id + "</string>"
                + (location == null ? "" : "<key>Location</key><string>" + location + "</string>") + extra + "</dict>\n";
    }

    private static String items(int... ids) {
        StringBuilder sb = new StringBuilder("<key>Playlist Items</key><array>");
        for (int id : ids) {
            sb.append("<dict><key>Track ID</key><integer>").append(id).append("</integer></dict>");
        }
        return sb.append("</array>").toString();
    }

    static String libraryXml() {
        return LibraryXmlTest.HEADER + "<plist version=\"1.0\"><dict>\n"
                + "<key>Music Folder</key><string>file://" + ROOT + "</string>\n<key>Tracks</key><dict>\n"
                + track(101, "One", "Artist A", loc("Music/Artist A/Album/01 One.m4a"),
                "<key>Album</key><string>Album</string><key>Track Number</key><integer>1</integer>"
                        + "<key>Track Count</key><integer>3</integer><key>Year</key><integer>2001</integer>"
                        + "<key>Date Added</key><date>2015-01-01T00:00:00Z</date><key>Kind</key><string>AAC audio file</string>"
                        + "<key>Loved</key><true/>")
                + track(102, "Two &amp; Co", "Artist B", loc("Music/Artist B/Other/" + NFD_NAME + ".mp3"),
                "<key>Date Added</key><date>2016-06-06T06:06:06Z</date><key>Genre</key><string>Pop</string>")
                + track(103, "Three", "Artist A", loc("music/artist a/album/03 three.FLAC"),
                "<key>Album Artist</key><string>Various</string><key>Disc Number</key><integer>1</integer>"
                        + "<key>Disc Count</key><integer>2</integer><key>Track Number</key><integer>99999</integer>")
                + track(104, "One again", "Artist A", loc("Music/Artist A/Album/01 One copy.m4a"), "")
                + track(105, "Locked", "Artist B", loc("Music/Artist B/Other/Locked.m4p"),
                "<key>Kind</key><string>Protected AAC audio file</string>")
                + track(106, "Gone", "Nobody", loc("Music/Nobody/Gone.m4a"), "")
                + track(107, "Clip", null, loc("Music/Movies/Clip.m4v"), "<key>Kind</key><string>MPEG-4 video file</string>")
                + "</dict>\n<key>Playlists</key><array>\n"
                + "<dict><key>Name</key><string>Library</string><key>Master</key><true/>"
                + "<key>Playlist Persistent ID</key><string>M</string>" + items(101, 102, 103) + "</dict>\n"
                + "<dict><key>Name</key><string>Folder</string><key>Folder</key><true/>"
                + "<key>Playlist Persistent ID</key><string>F</string></dict>\n"
                + "<dict><key>Name</key><string>Road</string><key>Playlist Persistent ID</key><string>R</string>"
                + "<key>Parent Persistent ID</key><string>F</string>" + items(103, 101, 105, 106, 102) + "</dict>\n"
                + "<dict><key>Name</key><string>Top</string><key>Playlist Persistent ID</key><string>T</string>"
                + items(102) + "</dict>\n"
                + "<dict><key>Name</key><string>Recent</string><key>Playlist Persistent ID</key><string>S</string>"
                + "<key>Smart Info</key><data>AQ==</data>" + items(101) + "</dict>\n"
                + "</array></dict></plist>\n";
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), Clock.systemUTC());
        adminId = accounts.createLocal("root", "Root", "root-password", true, null, "account.setup").id();
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        DataDir data = DataDir.create(dir.resolve("data"));
        Tools tools = new Tools(ffmpeg == null ? "ffmpeg" : ffmpeg, ffprobe == null ? "ffprobe" : ffprobe, null);
        IngestJobs jobs = new IngestJobs(db, Clock.systemUTC());
        var config = new io.siggi.togethertunes.config.ConfigLoader(Map.of("PATH", "",
                "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", "https://tunes.example"), 4, dir).load(null).config();
        uploads = new Uploads(db, data, tools, jobs, () -> {
        }, OutputEncoding.defaultFor(OutputProfile.P720_30), 100_000_000, 600,
                new GuestUploadQuota(db, new Settings(db, config), Clock.systemUTC()), Clock.systemUTC());
        YoutubeImports youtube = new YoutubeImports(db, new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString()),
                jobs, () -> {
                }, OutputEncoding.defaultFor(OutputProfile.P720_30), 600, () -> null, Clock.systemUTC());
        playlists = new Playlists(db, youtube, Clock.systemUTC());
        imports = newImports(() -> false);
    }

    private LibraryImports newImports(BooleanSupplier busy) {
        LibraryImports i = new LibraryImports(db, uploads, playlists, dir.resolve("data/tmp/imports"), List.of(allowed),
                Clock.systemUTC());
        i.polls(Duration.ofMillis(10), Duration.ofMillis(10));
        i.ingestBusy(busy);
        return i;
    }

    @AfterEach
    void tearDown() throws Exception {
        imports.stopWorker();
        db.close();
    }

    private void needFfmpeg() {
        Assumptions.assumeTrue(ffmpeg != null && ffprobe != null, "needs ffmpeg and ffprobe");
    }

    private static JsonObject source(Path p) {
        JsonObject s = new JsonObject();
        s.addProperty("path", p.toString());
        return s;
    }

    private LibraryImports.Import checked(boolean smart) throws Exception {
        LibraryImports.Import i = imports.check(adminId, aliceId, source(export), smart);
        assertEquals("checking", i.state());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        LibraryImports.Import ready = imports.latest(aliceId);
        assertEquals("ready", ready.state(), String.valueOf(ready.error()));
        return ready;
    }

    private LibraryImports.Import imported(boolean smart) throws Exception {
        checked(smart);
        imports.start(adminId, aliceId);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(60)));
        return imports.latest(aliceId);
    }

    private <T> T q(String sql, Object... params) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    @SuppressWarnings("unchecked")
                    T v = rs.next() ? (T) rs.getObject(1) : null;
                    return v;
                }
            }
        });
    }

    private Map<String, String> itemsByTitle() throws SQLException {
        return db.read(c -> {
            Map<String, String> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT i.title, i.id FROM library_entries e"
                    + " JOIN items i ON i.id = e.item_id WHERE e.user_id = ?")) {
                ps.setString(1, aliceId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertNull(out.put(rs.getString(1), rs.getString(2)), "one entry per title");
                    }
                }
            }
            return out;
        });
    }

    private List<String> playlistEntries(String name) throws SQLException {
        return db.read(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT i.title FROM playlist_entries e JOIN playlists p"
                    + " ON p.id = e.playlist_id JOIN items i ON i.id = e.item_id WHERE p.owner_id = ? AND p.name = ?"
                    + " ORDER BY e.position")) {
                ps.setString(1, aliceId);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    private static int n(JsonObject counts, String key) {
        return counts.get(key).getAsInt();
    }

    // ------------------------------------------------------------------ check and import

    @Test
    void checkCountsWithoutImporting() throws Exception {
        needFfmpeg();
        LibraryImports.Import i = checked(false);
        JsonObject c = i.counts();
        assertEquals(7, n(c, "tracks"));
        assertEquals(6, n(c, "matched"));
        assertEquals(1, n(c, "missing"));
        assertEquals(1, c.getAsJsonObject("skipped").get("protected").getAsInt());
        assertEquals(5, n(c, "toImport"));
        assertEquals(1, n(c, "videos"));
        assertEquals(0, n(c, "alreadyInLibrary"));
        assertEquals(1, n(c, "unreferencedFiles")); // notes.txt
        assertEquals(2, c.getAsJsonObject("playlists").get("regular").getAsInt());
        assertEquals(1, c.getAsJsonObject("playlists").get("smart").getAsInt());
        assertEquals(1, c.getAsJsonObject("playlists").get("folders").getAsInt());
        assertEquals(2, i.problems().size());
        JsonObject missing = i.problems().get(1).getAsJsonObject();
        assertEquals("missing", missing.get("kind").getAsString());
        assertEquals("not_found", missing.get("reason").getAsString());
        assertEquals("Gone", missing.get("title").getAsString());
        assertEquals("Nobody", missing.get("artist").getAsString());
        assertFalse(missing.has("path"), "a missing track's path is outside the source");
        assertEquals("Media.localized/Music/Artist B/Other/Locked.m4p",
                i.problems().get(0).getAsJsonObject().get("path").getAsString());
        assertEquals(0L, ((Number) q("SELECT COUNT(*) FROM items")).longValue());
        // A second check while this one waits for Import replaces it.
        imports.check(adminId, aliceId, source(export), false);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertEquals("cancelled", q("SELECT state FROM library_imports WHERE id = ?", i.id()));
    }

    @Test
    void importsTracksPlaylistsAndLikes() throws Exception {
        needFfmpeg();
        LibraryImports.Import done = imported(false);
        assertEquals("done", done.state(), String.valueOf(done.error()));
        JsonObject c = done.counts();
        assertEquals(5, n(c, "done"));
        assertEquals(4, n(c, "imported")); // One, Two, Three, Clip
        assertEquals(1, n(c, "already")); // the copy of One
        assertEquals(1, n(c, "liked"));
        assertEquals(2, n(c, "playlistsMade"));

        Map<String, String> items = itemsByTitle();
        assertEquals(Map.of("One", items.get("One"), "Two & Co", items.get("Two & Co"), "Three", items.get("Three"),
                "Clip", items.get("Clip")), items);
        String one = items.get("One");
        assertEquals("Artist A", q("SELECT artist FROM items WHERE id = ?", one));
        assertEquals("Album", q("SELECT album FROM items WHERE id = ?", one));
        assertEquals(1, ((Number) q("SELECT track_no FROM items WHERE id = ?", one)).intValue());
        assertEquals(3, ((Number) q("SELECT track_total FROM items WHERE id = ?", one)).intValue());
        assertEquals("2001", q("SELECT release_date FROM items WHERE id = ?", one));
        assertEquals(ADDED_ONE, ((Number) q("SELECT added_at FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, one)).longValue());
        assertEquals(ADDED_TWO, ((Number) q("SELECT added_at FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, items.get("Two & Co"))).longValue());
        assertEquals("Pop", q("SELECT genre FROM items WHERE id = ?", items.get("Two & Co")));
        String three = items.get("Three");
        assertEquals("Various", q("SELECT album_artist FROM items WHERE id = ?", three));
        assertEquals(2, ((Number) q("SELECT disc_total FROM items WHERE id = ?", three)).intValue());
        assertNull(q("SELECT track_no FROM items WHERE id = ?", three)); // 99999 doesn't fit: dropped
        assertEquals("video", q("SELECT kind FROM items WHERE id = ?", items.get("Clip")));
        assertEquals("uncategorized", q("SELECT media_type FROM items WHERE id = ?", items.get("Clip")));
        assertEquals("upload", q("SELECT source FROM items WHERE id = ?", one));
        assertEquals(aliceId, q("SELECT added_by_user_id FROM items WHERE id = ?", one));

        assertEquals(List.of("Three", "One", "Two & Co"), playlistEntries("Folder / Road"));
        assertEquals(List.of("Two & Co"), playlistEntries("Top"));
        assertTrue(playlistEntries("Recent").isEmpty(), "smart playlists only when asked");
        assertTrue(playlists.liked(aliceId, one));
        assertEquals(2, done.problems().size()); // the protected file and the missing one
        assertEquals(LibraryImports.AUDIT_FINISHED, q("SELECT action FROM audit_log WHERE action = ?",
                LibraryImports.AUDIT_FINISHED));
        String audit = q("SELECT details_json FROM audit_log WHERE action = ?", LibraryImports.AUDIT_STARTED);
        assertTrue(audit.contains("\"name\":\"export\""), audit);
        assertFalse(audit.contains(allowed.toString()), "no full paths in the audit log");

        // Again: nothing is added twice, the playlists are refilled, not duplicated; now with the smart one.
        LibraryImports.Import again = imported(true);
        assertEquals("done", again.state());
        assertEquals(5, n(again.counts(), "alreadyInLibrary")); // One (twice), Two, Three, Clip
        assertEquals(5, n(again.counts(), "already"));
        assertEquals(0, n(again.counts(), "imported"));
        assertEquals(2, n(again.counts(), "playlistsUpdated"));
        assertEquals(1, n(again.counts(), "playlistsMade")); // the smart one
        assertEquals(items, itemsByTitle());
        assertEquals(4L, ((Number) q("SELECT COUNT(*) FROM playlists WHERE owner_id = ?", aliceId)).longValue());
        assertEquals(List.of("Three", "One", "Two & Co"), playlistEntries("Folder / Road"));
        assertEquals(List.of("One"), playlistEntries("Recent"));
        assertEquals(ADDED_ONE, ((Number) q("SELECT added_at FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, one)).longValue());
    }

    @Test
    void anotherUsersCopyIsShared() throws Exception {
        needFfmpeg();
        imported(false);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), Clock.systemUTC());
        String bobId = accounts.createLocal("bob", "Bob", "bob-password", false, null, "account.register").id();
        imports.check(adminId, bobId, source(export), false);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        imports.start(adminId, bobId);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(60)));
        LibraryImports.Import done = imports.latest(bobId);
        assertEquals("done", done.state());
        assertEquals(4, n(done.counts(), "imported")); // new to Bob's library, same items
        assertEquals(4L, ((Number) q("SELECT COUNT(*) FROM items")).longValue());
    }

    @Test
    void resumesAfterARestart() throws Exception {
        needFfmpeg();
        AtomicInteger calls = new AtomicInteger();
        imports = newImports(() -> calls.incrementAndGet() > 2);
        checked(false);
        imports.start(adminId, aliceId);
        long deadline = System.currentTimeMillis() + 30_000;
        while (n(imports.latest(aliceId).counts(), "done") < 2 || calls.get() < 4) {
            assertTrue(System.currentTimeMillis() < deadline, "two files imported");
            Thread.sleep(20);
        }
        imports.stopWorker(); // the server stops
        assertEquals("importing", imports.latest(aliceId).state());
        assertEquals(2, n(imports.latest(aliceId).counts(), "done"));

        imports = newImports(() -> false);
        imports.resume();
        assertTrue(imports.awaitIdle(Duration.ofSeconds(60)));
        LibraryImports.Import done = imports.latest(aliceId);
        assertEquals("done", done.state());
        assertEquals(5, n(done.counts(), "done"));
        assertEquals(4, itemsByTitle().size());
        assertEquals(4L, ((Number) q("SELECT COUNT(*) FROM items")).longValue());
    }

    @Test
    void pausesBelowTheFreeSpaceMinimum() throws Exception {
        needFfmpeg();
        AtomicLong free = new AtomicLong(0);
        imports.diskSpace(new DiskSpace(() -> new DiskSpace.Usage(free.get(), 1L << 40), () -> 1L << 30,
                Duration.ZERO, System::nanoTime));
        checked(false);
        imports.start(adminId, aliceId);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!imports.latest(aliceId).state().equals("paused")) {
            assertTrue(System.currentTimeMillis() < deadline, "paused");
            Thread.sleep(10);
        }
        assertEquals(0, n(imports.latest(aliceId).counts(), "done"));
        free.set(1L << 40);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(60)));
        assertEquals("done", imports.latest(aliceId).state());
        assertEquals(4, n(imports.latest(aliceId).counts(), "imported"));
    }

    @Test
    void cancelStopsAfterTheCurrentFile() throws Exception {
        needFfmpeg();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean hold = new AtomicBoolean(true);
        imports = newImports(() -> calls.incrementAndGet() > 1 && hold.get());
        checked(false);
        imports.start(adminId, aliceId);
        long deadline = System.currentTimeMillis() + 30_000;
        while (n(imports.latest(aliceId).counts(), "done") < 1 || calls.get() < 3) {
            assertTrue(System.currentTimeMillis() < deadline, "one file imported");
            Thread.sleep(20);
        }
        LibraryImports.Import cancelled = imports.cancel(adminId, aliceId);
        assertEquals("cancelled", cancelled.state());
        hold.set(false);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        LibraryImports.Import after = imports.latest(aliceId);
        assertEquals("cancelled", after.state());
        assertEquals(1, n(after.counts(), "done"));
        assertEquals(1, itemsByTitle().size()); // what is imported stays
        assertEquals(0L, ((Number) q("SELECT COUNT(*) FROM playlists WHERE owner_id = ? AND builtin IS NULL",
                aliceId)).longValue());
        assertNotNull(q("SELECT 1 FROM audit_log WHERE action = ?", LibraryImports.AUDIT_CANCELLED));
        assertEquals("import_not_running", assertThrows(HttpError.class, () -> imports.cancel(adminId, aliceId)).code());
    }

    @Test
    void oneImportAtATime() throws Exception {
        needFfmpeg();
        AtomicBoolean hold = new AtomicBoolean(true);
        imports = newImports(hold::get);
        checked(false);
        imports.start(adminId, aliceId);
        assertEquals("import_running", assertThrows(HttpError.class,
                () -> imports.check(adminId, adminId, source(export), false)).code());
        assertEquals("import_not_ready", assertThrows(HttpError.class, () -> imports.start(adminId, aliceId)).code());
        assertEquals("alice", imports.runningForOther(adminId).username());
        assertNull(imports.runningForOther(aliceId));
        imports.cancel(adminId, aliceId);
        hold.set(false);
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
    }

    @Test
    void fromAZip() throws Exception {
        needFfmpeg();
        Path zip = allowed.resolve("export-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip));
             Stream<Path> walk = Files.walk(export)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new ZipEntry("My Export/" + export.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        try {
            LibraryImports.Import i = imports.check(adminId, aliceId, source(zip), false);
            assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
            JsonObject c = imports.latest(aliceId).counts();
            assertEquals(7, n(c, "tracks"));
            assertEquals(6, n(c, "matched"));
            assertEquals("path", i.source().get("type").getAsString());
        } finally {
            Files.delete(zip);
        }
    }

    // ------------------------------------------------------------------ the path rules

    private String refused(String path) {
        return assertThrows(HttpError.class, () -> imports.check(adminId, aliceId, source(Path.of(path)), false)).code();
    }

    @Test
    void pathRules() throws Exception {
        assertEquals("path_not_allowed", refused(fixtures.resolve("elsewhere").toString()));
        assertEquals("path_not_allowed", refused(allowed + "/../elsewhere"));
        assertEquals("path_not_found", refused(allowed.resolve("nothing-here").toString()));
        JsonObject relative = new JsonObject();
        relative.addProperty("path", "relative/export");
        assertEquals("invalid_path", assertThrows(HttpError.class,
                () -> imports.check(adminId, aliceId, relative, false)).code());

        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("Library.xml"), libraryXml());
        Path link = allowed.resolve("link-" + System.nanoTime());
        Files.createSymbolicLink(link, outside);
        try {
            assertEquals("path_not_allowed", refused(link.toString()));
        } finally {
            Files.delete(link);
        }
        Path notes = export.resolve("Media.localized/Music/Artist B/Other/notes.txt");
        assertEquals("invalid_source", refused(notes.toString()));
        assertEquals("no_library_xml", refused(export.resolve("Media.localized").toString()));
        assertEquals("user_not_found", assertThrows(HttpError.class,
                () -> imports.check(adminId, "0190a000-0000-7000-8000-000000000000", source(export), false)).code());
        assertTrue(q("SELECT COUNT(*) FROM library_imports").toString().equals("0"));

        LibraryImports off = new LibraryImports(db, uploads, playlists, dir, List.of(), Clock.systemUTC());
        assertEquals("path_not_allowed", assertThrows(HttpError.class,
                () -> off.check(adminId, aliceId, source(export), false)).code());
    }

    @Test
    void severalLibraryXmlFiles() throws Exception {
        Path two = Files.createDirectories(allowed.resolve("two-" + System.nanoTime()));
        try {
            Files.writeString(Files.createDirectories(two.resolve("a")).resolve("Library.xml"), libraryXml());
            Files.writeString(Files.createDirectories(two.resolve("b")).resolve("library.XML"), libraryXml());
            assertEquals("several_library_xml", refused(two.toString()));
        } finally {
            try (Stream<Path> walk = Files.walk(two)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @Test
    void anUploadedZipByItsId() throws Exception {
        String uploadId = io.siggi.togethertunes.db.UuidV7.newId();
        JsonObject s = new JsonObject();
        s.addProperty("uploadId", uploadId);
        assertEquals("upload_not_found", assertThrows(HttpError.class,
                () -> imports.check(adminId, aliceId, s, false)).code());
        Path zip = Files.createDirectories(dir.resolve("data/tmp/imports")).resolve(uploadId + ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("Library.xml"));
            z.write(libraryXml().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        LibraryImports.Import i = imports.check(adminId, aliceId, s, false);
        assertEquals("upload", i.source().get("type").getAsString());
        assertTrue(imports.awaitIdle(Duration.ofSeconds(30)));
        assertEquals(7, n(imports.latest(aliceId).counts(), "missing"));
    }

    /** The export's files with their sizes and modification times. */
    static Map<String, String> snapshot() throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(export)) {
            for (Path p : walk.toList()) {
                out.put(export.relativize(p).toString(), Files.isDirectory(p) ? "dir " + Files.getLastModifiedTime(p)
                        : Files.size(p) + " " + Files.getLastModifiedTime(p));
            }
        }
        return out;
    }

    static Map<String, String> before;

    /** The source is only ever read: after every test, the export is exactly as it was. */
    @org.junit.jupiter.api.AfterAll
    static void sourceIsUnchanged() throws IOException {
        assertEquals(before, snapshot());
    }
}
