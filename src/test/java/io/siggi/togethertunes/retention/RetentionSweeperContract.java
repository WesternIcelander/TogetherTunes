package io.siggi.togethertunes.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.library.Items;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RetentionSweeper}: which items it purges (ready, unreferenced, last referenced before
 * the cutoff), the in-transaction re-check, paging, the audit row, and {@code 0 = disabled}.
 */
abstract class RetentionSweeperContract {
    static final long DAY = 24L * 60 * 60 * 1000;
    static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(NOW);
    private DataDir data;
    private IngestJobs jobs;
    private RetentionSweeper sweeper;
    private String userId;
    private String roomId;
    private int days = 14;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        data = DataDir.create(dir.resolve("data"));
        jobs = new IngestJobs(db, clock);
        sweeper = new RetentionSweeper(db, new Items(db, data, clock), () -> days, clock);
        userId = UuidV7.newId();
        exec("INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, 'u', 'u', ?, 0)",
                userId, 1L);
        roomId = UuidV7.newId();
        exec("INSERT INTO rooms (id, slug, name, owner_id, created_at, visibility, control_mode, settings_json,"
                + " playback_state) VALUES (?, 'r', 'Room', ?, 1, 'public', 'owner', '{}', 'paused')", roomId, userId);
    }

    @AfterEach
    void tearDown() {
        sweeper.close();
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ fixture

    private String item(String status, long lastReferencedAt) throws Exception {
        String id = UuidV7.newId();
        exec("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES (?, 'audio', 'Song', 'upload', ?, 1, ?, '720p30', ?)",
                id, userId, status, lastReferencedAt);
        Path itemDir = IngestPipeline.itemDir(data, id);
        Files.createDirectories(itemDir);
        Files.writeString(itemDir.resolve("audio.m4a"), "audio");
        return id;
    }

    private String old() throws Exception {
        return item("ready", NOW - 15 * DAY);
    }

    private void libraryEntry(String itemId) throws SQLException {
        exec("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'manual')",
                userId, itemId);
    }

    private void playlistEntry(String itemId) throws SQLException {
        String playlist = UuidV7.newId();
        exec("INSERT INTO playlists (id, owner_id, name, created_at, updated_at) VALUES (?, ?, 'p', 1, 1)",
                playlist, userId);
        exec("INSERT INTO playlist_entries (id, playlist_id, item_id, position, added_at) VALUES (?, ?, ?, 1, 1)",
                UuidV7.newId(), playlist, itemId);
    }

    private void queueEntry(String itemId, String state) throws SQLException {
        exec("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position)"
                + " VALUES (?, ?, ?, ?, 1, ?, 1)", UuidV7.newId(), roomId, itemId, userId, state);
    }

    private String status(String itemId) throws SQLException {
        return column(itemId, "status");
    }

    private String column(String itemId, String column) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private boolean hasFiles(String itemId) {
        return Files.exists(IngestPipeline.itemDir(data, itemId).resolve("audio.m4a"));
    }

    private List<String> audit() throws SQLException {
        return db.read(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT actor_user_id, actor_guest_id, ip, target,"
                    + " details_json FROM audit_log WHERE action = ? ORDER BY target")) {
                ps.setString(1, Items.AUDIT_PURGED);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " "
                                + rs.getString(4) + " " + rs.getString(5));
                    }
                }
            }
            return rows;
        });
    }

    private void exec(String sql, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void purgesOnlyReadyUnreferencedItemsPastTheRetentionPeriod() throws Exception {
        String expired = old();
        String recent = item("ready", NOW - 13 * DAY);
        String boundary = item("ready", NOW - 14 * DAY);
        String justPast = item("ready", NOW - 14 * DAY - 1);
        String inLibrary = old();
        libraryEntry(inLibrary);
        String inPlaylist = old();
        playlistEntry(inPlaylist);
        String queued = old();
        queueEntry(queued, "queued");
        String suggested = old();
        queueEntry(suggested, "suggested");
        String playing = old();
        queueEntry(playing, "playing");
        String played = old();
        queueEntry(played, "played");
        String running = old();
        jobs.enqueue(running, IngestJob.Kind.REENCODE);
        assertEquals(running, jobs.claimNext().itemId());
        String queuedJob = old();
        IngestJob reencode = jobs.enqueue(queuedJob, IngestJob.Kind.REENCODE);
        String pending = item("pending", NOW - 15 * DAY);
        String failed = item("failed", NOW - 15 * DAY);

        assertEquals(new RetentionSweeper.Result(4, 0, 0), sweeper.sweep());

        for (String id : List.of(expired, justPast, played, queuedJob)) {
            assertEquals("purged", status(id), id);
            assertEquals(String.valueOf(NOW), column(id, "purged_at"));
            assertEquals("Song", column(id, "title"), "the row and its metadata stay");
            assertFalse(hasFiles(id));
        }
        assertEquals(IngestJob.State.FAILED, jobs.get(reencode.id()).orElseThrow().state(),
                "a queued job doesn't protect the item and is cancelled");
        for (String id : List.of(recent, boundary, inLibrary, inPlaylist, queued, suggested, playing, running)) {
            assertEquals("ready", status(id), id);
            assertNull(column(id, "purged_at"));
            assertTrue(hasFiles(id));
        }
        assertEquals("pending", status(pending));
        assertEquals("failed", status(failed));
        assertTrue(hasFiles(pending));

        List<String> expected = new ArrayList<>();
        for (String id : List.of(expired, justPast, played, queuedJob).stream().sorted().toList()) {
            expected.add("null null null " + id
                    + " {\"previousStatus\":\"ready\",\"reason\":\"retention\",\"unreferencedDays\":14}");
        }
        assertEquals(expected, audit(), "audited without an actor or IP");

        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep(), "nothing left to do");
    }

    @Test
    void zeroDaysDisablesIt() throws Exception {
        String expired = item("ready", 1);
        days = 0;
        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep());
        assertEquals("ready", status(expired));
        assertTrue(hasFiles(expired));

        days = 1;
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep(), "the setting is read at each sweep");
        assertEquals("purged", status(expired));
    }

    @Test
    void reChecksEachItemInsideThePurgeTransaction() throws Exception {
        String addedToLibrary = old();
        String touched = old();
        String queuedInRoom = old();
        String untouched = old();
        sweeper.afterSelect = ids -> {
            assertEquals(4, ids.size());
            // Referenced again between the selection and the purge.
            libraryEntry(addedToLibrary);
            exec("UPDATE items SET last_referenced_at = ? WHERE id = ?", NOW, touched);
            queueEntry(queuedInRoom, "queued");
        };
        assertEquals(new RetentionSweeper.Result(1, 3, 0), sweeper.sweep());
        for (String id : List.of(addedToLibrary, touched, queuedInRoom)) {
            assertEquals("ready", status(id), id);
            assertTrue(hasFiles(id));
        }
        assertEquals("purged", status(untouched));
        assertFalse(hasFiles(untouched));
        assertEquals(1, audit().size(), "no audit row for the skipped items");
    }

    @Test
    void pagesThroughAllCandidates() throws Exception {
        sweeper.pageSize = 2;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(old());
        }
        String keep = old();
        libraryEntry(keep);
        AtomicInteger pages = new AtomicInteger();
        sweeper.afterSelect = page -> pages.incrementAndGet();
        assertEquals(new RetentionSweeper.Result(5, 0, 0), sweeper.sweep());
        assertEquals(3, pages.get());
        for (String id : ids) {
            assertEquals("purged", status(id));
        }
        assertEquals("ready", status(keep));
    }

    @Test
    void startAndCloseDontSweepRightAway() throws Exception {
        String expired = old();
        sweeper.start();
        sweeper.close();
        sweeper.close();
        assertEquals("ready", status(expired), "the first sweep waits FIRST_DELAY");
        assertNotNull(RetentionSweeper.INTERVAL);
    }
}
