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
    private int unviewedDays = 14;
    private int afterViewHours = 72;
    private int purgedEntryDays = -1;
    static final long HOUR = 60L * 60 * 1000;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        data = DataDir.create(dir.resolve("data"));
        jobs = new IngestJobs(db, clock);
        sweeper = new RetentionSweeper(db, new Items(db, data, clock),
                () -> new RetentionSweeper.Limits(days, unviewedDays, afterViewHours, purgedEntryDays), clock);
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

    /**
     * A ready video item of {@code mediaType}, added at {@code addedAt}, last viewed at
     * {@code lastViewedAt} (null: never), and referenced just now (so only auto-delete applies).
     */
    private String video(String mediaType, long addedAt, Long lastViewedAt) throws Exception {
        String id = UuidV7.newId();
        exec("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, media_type, last_viewed_at) VALUES (?, 'video', 'Film', 'upload', ?, ?,"
                + " 'ready', '720p30', ?, ?, ?)", id, userId, addedAt, NOW, mediaType, lastViewedAt);
        Path itemDir = IngestPipeline.itemDir(data, id);
        Files.createDirectories(itemDir);
        Files.writeString(itemDir.resolve("audio.m4a"), "media");
        return id;
    }

    private void pin(String itemId) throws SQLException {
        exec("UPDATE items SET pinned_at = ?, pinned_by_user_id = ? WHERE id = ?", NOW, userId, itemId);
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
                    + " details_json FROM audit_log WHERE action = ? ORDER BY target, details_json")) {
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

    @Test
    void choresRunAfterEachSweepAndFailIndependently() {
        List<String> ran = new ArrayList<>();
        sweeper.alsoRun("first", () -> {
            ran.add("first");
            return 2;
        });
        sweeper.alsoRun("broken", () -> {
            ran.add("broken");
            throw new SQLException("boom");
        });
        sweeper.alsoRun("last", () -> {
            ran.add("last");
            return 0;
        });
        sweeper.runChores();
        assertEquals(List.of("first", "broken", "last"), ran, "a failing chore doesn't stop the others");
    }

    @Test
    void pinnedItemsAreNeverPurgedByTheUnreferencedRule() throws Exception {
        String pinned = old();
        pin(pinned);
        String expired = old();
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep());
        assertEquals("ready", status(pinned));
        assertTrue(hasFiles(pinned));
        assertEquals("purged", status(expired));

        exec("UPDATE items SET pinned_at = NULL, pinned_by_user_id = NULL WHERE id = ?", pinned);
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep(), "unpinned: the rule applies again");
        assertEquals("purged", status(pinned));
    }

    @Test
    void autoDeletesMoviesAndShowsByTheirTimers() throws Exception {
        // Never viewed: 14 days after added_at.
        String unviewedMovie = video("movie", NOW - 14 * DAY - 1, null);
        String unviewedShow = video("show", NOW - 20 * DAY, null);
        String unviewedBoundary = video("movie", NOW - 14 * DAY, null);
        String unviewedRecent = video("show", NOW - 13 * DAY, null);
        // Viewed: 72 hours after last_viewed_at, whenever it was added.
        String viewedMovie = video("movie", NOW - 100 * DAY, NOW - 72 * HOUR - 1);
        String viewedBoundary = video("show", NOW - 100 * DAY, NOW - 72 * HOUR);
        String viewedRecently = video("movie", NOW - 100 * DAY, NOW - HOUR);
        String viewedButNew = video("show", NOW - DAY, NOW - 2 * HOUR);
        // Library and playlist entries don't protect movies and shows.
        String inLibrary = video("movie", NOW - 20 * DAY, null);
        libraryEntry(inLibrary);
        String inPlaylist = video("show", NOW - 20 * DAY, NOW - 80 * HOUR);
        playlistEntry(inPlaylist);
        String played = video("movie", NOW - 20 * DAY, null);
        queueEntry(played, "played");
        // Protected: pinned, current/queued/suggested in a room, a running ingest job.
        String pinned = video("movie", NOW - 20 * DAY, NOW - 80 * HOUR);
        pin(pinned);
        String queued = video("show", NOW - 20 * DAY, null);
        queueEntry(queued, "queued");
        String suggested = video("show", NOW - 20 * DAY, null);
        queueEntry(suggested, "suggested");
        String playing = video("movie", NOW - 20 * DAY, NOW - 80 * HOUR);
        queueEntry(playing, "playing");
        String running = video("movie", NOW - 20 * DAY, null);
        jobs.enqueue(running, IngestJob.Kind.REENCODE);
        assertEquals(running, jobs.claimNext().itemId());
        // Other media types never auto-delete.
        String musicVideo = video("music_video", NOW - 20 * DAY, NOW - 80 * HOUR);
        String uncategorized = video("uncategorized", NOW - 20 * DAY, null);
        String audio = video("audio", NOW - 20 * DAY, null);
        String failedMovie = video("movie", NOW - 20 * DAY, null);
        exec("UPDATE items SET status = 'failed' WHERE id = ?", failedMovie);

        List<String> gone = List.of(unviewedMovie, unviewedShow, viewedMovie, inLibrary, inPlaylist, played);
        assertEquals(new RetentionSweeper.Result(gone.size(), 0, 0), sweeper.sweep());
        for (String id : gone) {
            assertEquals("purged", status(id), id);
            assertEquals(String.valueOf(NOW), column(id, "purged_at"));
            assertFalse(hasFiles(id));
        }
        for (String id : List.of(unviewedBoundary, unviewedRecent, viewedBoundary, viewedRecently, viewedButNew,
                pinned, queued, suggested, playing, running, musicVideo, uncategorized, audio)) {
            assertEquals("ready", status(id), id);
            assertTrue(hasFiles(id), id);
        }
        assertEquals("failed", status(failedMovie));

        List<String> expected = new ArrayList<>();
        for (String id : gone.stream().sorted().toList()) {
            expected.add("null null null " + id + " {\"previousStatus\":\"ready\",\"reason\":\"auto-delete\","
                    + "\"videoUnviewedDays\":14,\"videoAfterViewHours\":72}");
        }
        assertEquals(expected, audit(), "audited without an actor or IP");

        // The fake clock moves on: the boundary items and the recent ones expire in turn.
        clock.advance(1);
        assertEquals(new RetentionSweeper.Result(2, 0, 0), sweeper.sweep());
        assertEquals("purged", status(unviewedBoundary));
        assertEquals("purged", status(viewedBoundary));
        clock.advance(DAY);
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep());
        assertEquals("purged", status(unviewedRecent));
        clock.advance(2 * DAY);
        assertEquals(new RetentionSweeper.Result(2, 0, 0), sweeper.sweep());
        assertEquals("purged", status(viewedRecently));
        assertEquals("purged", status(viewedButNew));
        assertEquals("ready", status(pinned), "pinned: never");
    }

    @Test
    void eachAutoDeleteTimerCanBeTurnedOff() throws Exception {
        String unviewed = video("movie", NOW - 20 * DAY, null);
        String viewed = video("show", NOW - 20 * DAY, NOW - 80 * HOUR);
        days = 0;
        unviewedDays = 0;
        afterViewHours = 0;
        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep());

        afterViewHours = 72;
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep(), "the settings are read at each sweep");
        assertEquals("purged", status(viewed));
        assertEquals("ready", status(unviewed), "never viewed: its own timer, off");

        afterViewHours = 0;
        unviewedDays = 14;
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep());
        assertEquals("purged", status(unviewed));
        assertTrue(audit().stream().anyMatch(r -> r.startsWith("null null null " + viewed)
                && r.contains("\"videoUnviewedDays\":0,\"videoAfterViewHours\":72")), "the limits in force");
    }

    /** Runs a refetch of {@code itemId} to the end now (the fetch of a purged YouTube item). */
    private void refetched(String itemId) throws Exception {
        jobs.enqueue(itemId, IngestJob.Kind.REFETCH);
        IngestJob job = jobs.claimNext();
        assertEquals(itemId, job.itemId());
        assertTrue(jobs.complete(job.id(), null));
    }

    /**
     * A purged movie or show fetched again (added to a library again, say) starts its timers over:
     * it counts as never viewed since the refetch, so it isn't purged again at the next sweep
     * because of a view or an {@code added_at} from before it was purged.
     */
    @Test
    void aRefetchStartsTheAutoDeleteTimersOver() throws Exception {
        days = 0;
        String viewedBefore = video("movie", NOW - 100 * DAY, NOW - 80 * HOUR);
        refetched(viewedBefore);
        String neverViewed = video("show", NOW - 100 * DAY, null);
        refetched(neverViewed);
        String notRefetched = video("movie", NOW - 100 * DAY, NOW - 80 * HOUR);
        String otherJob = video("movie", NOW - 100 * DAY, null);
        jobs.enqueue(otherJob, IngestJob.Kind.REENCODE);
        assertTrue(jobs.complete(jobs.claimNext().id(), null));

        assertEquals(new RetentionSweeper.Result(2, 0, 0), sweeper.sweep());
        assertEquals("purged", status(notRefetched));
        assertEquals("purged", status(otherJob), "only a refetch starts the timers over");
        assertEquals("ready", status(viewedBefore), "the old view doesn't count");
        assertEquals("ready", status(neverViewed));

        // not viewed since the refetch: the never-viewed timer, from the refetch
        clock.advance(80 * HOUR);
        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep());
        // a view after the refetch: the after-view timer
        exec("UPDATE items SET last_viewed_at = ? WHERE id = ?", clock.millis(), viewedBefore);
        clock.advance(72 * HOUR);
        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep());
        clock.advance(1);
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep());
        assertEquals("purged", status(viewedBefore));
        // 14 days after the refetch
        clock.set(NOW + 14 * DAY);
        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep());
        clock.advance(1);
        assertEquals(new RetentionSweeper.Result(1, 0, 0), sweeper.sweep());
        assertEquals("purged", status(neverViewed));
    }

    @Test
    void autoDeleteReChecksEachItemInsideThePurgeTransaction() throws Exception {
        days = 0;
        String viewed = video("movie", NOW - 20 * DAY, null);
        String pinned = video("show", NOW - 20 * DAY, null);
        String queuedInRoom = video("movie", NOW - 20 * DAY, null);
        String untouched = video("show", NOW - 20 * DAY, null);
        String inLibrary = video("movie", NOW - 20 * DAY, null);
        sweeper.afterSelect = ids -> {
            assertEquals(5, ids.size());
            exec("UPDATE items SET last_viewed_at = ? WHERE id = ?", NOW, viewed);
            pin(pinned);
            queueEntry(queuedInRoom, "queued");
            libraryEntry(inLibrary);
        };
        assertEquals(new RetentionSweeper.Result(2, 3, 0), sweeper.sweep());
        for (String id : List.of(viewed, pinned, queuedInRoom)) {
            assertEquals("ready", status(id), id);
            assertTrue(hasFiles(id));
        }
        assertEquals("purged", status(untouched));
        assertEquals("purged", status(inLibrary), "a library entry doesn't protect a movie or show");
        assertEquals(2, audit().size(), "no audit row for the skipped items");
    }

    private long count(String sql, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private String purgedAt(long purgedAt) throws Exception {
        String id = item("purged", NOW - 100 * DAY);
        exec("UPDATE items SET purged_at = ? WHERE id = ?", purgedAt, id);
        libraryEntry(id);
        playlistEntry(id);
        queueEntry(id, "played");
        return id;
    }

    private long entries(String itemId) throws SQLException {
        return count("SELECT (SELECT COUNT(*) FROM library_entries WHERE item_id = ?)"
                + " + (SELECT COUNT(*) FROM playlist_entries WHERE item_id = ?)", itemId, itemId);
    }

    /**
     * {@code retention.purgedEntryDays}: library and playlist entries of items purged (for any
     * reason) that long ago go; room history stays; -1 keeps them, 0 removes all.
     */
    @Test
    void removesTheEntriesOfItemsPurgedLongAgo() throws Exception {
        days = 0;
        unviewedDays = 0;
        afterViewHours = 0;
        String old = purgedAt(NOW - 30 * DAY - 1);
        String boundary = purgedAt(NOW - 30 * DAY);
        String recent = purgedAt(NOW - 29 * DAY);
        String ready = item("ready", NOW);
        libraryEntry(ready);
        playlistEntry(ready);
        // purged long ago, then fetched again: it's ready, its entries stay
        String refetched = item("ready", NOW);
        exec("UPDATE items SET purged_at = ? WHERE id = ?", NOW - 100 * DAY, refetched);
        libraryEntry(refetched);

        assertEquals(new RetentionSweeper.Result(0, 0, 0), sweeper.sweep(), "-1: kept forever");
        assertEquals(2, entries(old));

        purgedEntryDays = 30;
        assertEquals(new RetentionSweeper.Result(0, 0, 0, 4), sweeper.sweep());
        assertEquals(0, entries(old));
        assertEquals(0, entries(boundary), "purged exactly 30 days ago");
        assertEquals(2, entries(recent));
        assertEquals(2, entries(ready));
        assertEquals(1, entries(refetched));
        assertEquals(1, count("SELECT COUNT(*) FROM queue_entries WHERE item_id = ?", old), "room history stays");
        assertEquals("purged", status(old), "the row stays");
        assertEquals(2, count("SELECT COUNT(*) FROM playlists WHERE updated_at = ?", NOW),
                "the playlists that lost an entry were updated");

        purgedEntryDays = 0;
        assertEquals(new RetentionSweeper.Result(0, 0, 0, 2), sweeper.sweep());
        assertEquals(0, entries(recent));
        assertEquals(2, entries(ready));
    }

    /** At 0, an item the sweeper purges loses its entries in the same purge. */
    @Test
    void purgedEntryDaysZeroRemovesTheEntriesOfAnAutoDeletedItemAtOnce() throws Exception {
        purgedEntryDays = 0;
        Items items = new Items(db, data, clock);
        items.purgedEntryDays(() -> purgedEntryDays);
        RetentionSweeper s = new RetentionSweeper(db, items,
                () -> new RetentionSweeper.Limits(days, unviewedDays, afterViewHours, purgedEntryDays), clock);
        String movie = video("movie", NOW - 20 * DAY, null);
        libraryEntry(movie);
        playlistEntry(movie);
        assertEquals(new RetentionSweeper.Result(1, 0, 0), s.sweep(), "removed by the purge, not the entries pass");
        assertEquals("purged", status(movie));
        assertEquals(0, entries(movie));
    }
}
