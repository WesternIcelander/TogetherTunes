package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestPipeline;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ContentIdBackfill}: upload items from before content IDs get the ID of their kept
 * original, if its SHA-256 matches; purged items, YouTube items, missing files and items that
 * already have an ID are left alone.
 */
class ContentIdBackfillTest {
    @TempDir
    Path dir;

    private Database db;
    private DataDir data;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        data = DataDir.create(dir.resolve("data"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** An upload item (or YouTube item) with its original written as given; returns the id. */
    private String item(String source, String status, byte[] original, String sha256, String contentId)
            throws Exception {
        String id = UuidV7.newId();
        String user = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, 'u', 1, 0)")) {
                ps.setString(1, user);
                ps.setString(2, "u" + user.substring(user.length() - 10));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source, youtube_id,"
                    + " content_sha256, content_id, original_file, added_by_user_id, added_at, status, output_profile,"
                    + " last_referenced_at) VALUES (?, 'audio', 't', ?, ?, ?, ?, 'original.mp3', ?, 1, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, source);
                ps.setString(3, source.equals("youtube") ? "abcdefghijk" : null);
                ps.setString(4, sha256);
                ps.setString(5, contentId);
                ps.setString(6, user);
                ps.setString(7, status);
                ps.executeUpdate();
            }
            return null;
        });
        if (original != null) {
            Path itemDir = IngestPipeline.itemDir(data, id);
            Files.createDirectories(itemDir);
            Files.write(itemDir.resolve("original.mp3"), original);
        }
        return id;
    }

    private String contentIdOf(String id) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT content_id FROM items WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void recordsTheIdOfTheKeptOriginal() throws Exception {
        byte[] a = random(10_000, 1);
        byte[] b = random(20_000, 2);
        String sha = Uploads.sha256Hex(new ByteArrayInputStream(a));
        String id = ContentId.of(new ByteArrayInputStream(a));
        String ready = item("upload", "ready", a, sha, null);
        String noSha = item("upload", "pending", b, null, null);
        String mismatch = item("upload", "ready", b, sha, null);
        String missing = item("upload", "ready", null, sha, null);
        String purged = item("upload", "purged", a, sha, null);
        String youtube = item("youtube", "ready", a, null, null);
        String already = item("upload", "ready", a, sha, "sha256c8m:1:" + "0".repeat(64));

        assertEquals(2, new ContentIdBackfill(db, data).run());
        assertEquals(id, contentIdOf(ready));
        assertEquals(ContentId.of(new ByteArrayInputStream(b)), contentIdOf(noSha));
        assertNull(contentIdOf(mismatch), "the file isn't the uploaded bytes");
        assertNull(contentIdOf(missing));
        assertNull(contentIdOf(purged));
        assertNull(contentIdOf(youtube));
        assertEquals("sha256c8m:1:" + "0".repeat(64), contentIdOf(already));
        assertEquals(0, new ContentIdBackfill(db, data).run(), "nothing left to do");

        Thread t = new ContentIdBackfill(db, data).start();
        t.join(10_000);
        assertEquals(Thread.MIN_PRIORITY, t.getPriority());
    }
}
