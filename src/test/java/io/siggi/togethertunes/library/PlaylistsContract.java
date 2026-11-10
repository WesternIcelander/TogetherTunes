package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Playlists ({@link Playlists}): listing and paging, validation, ownership, entry order with
 * fractional positions (append, insert before, move, renumbering when a gap runs out),
 * duplicates, item status rules, {@code last_referenced_at}, and concurrent adds.
 */
abstract class PlaylistsContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private IngestJobs jobs;
    private Playlists playlists;
    private Actor.User alice;
    private Actor.User bob;
    private static final Library.YoutubeCheck NO_CHECK = () -> {
        throw new AssertionError("no YouTube check expected");
    };

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        jobs = new IngestJobs(db, clock);
        YoutubeImports youtube = new YoutubeImports(db, new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString()),
                jobs, () -> {
                }, OutputEncoding.defaultFor(OutputProfile.P720_30), 600, () -> null, clock);
        playlists = new Playlists(db, youtube, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        alice = new Actor.User(accounts.createLocal("alice", "Alice", "alice-password", false, null,
                "account.register").id(), "s1", Actor.Via.BEARER);
        bob = new Actor.User(accounts.createLocal("bob", "Bob", "bob-password", false, null,
                "account.register").id(), "s2", Actor.Via.BEARER);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ fixture

    private String item(String title, String status, String youtubeId) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, duration_ms,"
                    + " source, youtube_id, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, ?, ?, 'Artist', 1000, ?, ?, ?, 1, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, youtubeId != null ? "video" : "audio");
                ps.setString(3, title);
                ps.setString(4, youtubeId != null ? "youtube" : "upload");
                ps.setString(5, youtubeId);
                ps.setString(6, alice.userId());
                ps.setString(7, status);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String song(String title) throws SQLException {
        return item(title, "ready", null);
    }

    private long lastReferenced(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_referenced_at FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private int count(String sql, String param) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    private String create(Actor.User owner, String name) throws SQLException {
        return playlists.create(owner.userId(), name, null).id();
    }

    private String add(String playlistId, String itemId, String before) throws Exception {
        return playlists.add(alice, playlistId, itemId, before, NO_CHECK).id();
    }

    /** The entry ids of alice's playlist, in order. */
    private List<String> order(String playlistId) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Playlists.Entry e : playlists.get(alice.userId(), playlistId).entries()) {
            out.add(e.id());
        }
        return out;
    }

    private static String code(Executable e) {
        return assertThrows(HttpError.class, e::run).code();
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }

    // ------------------------------------------------------------------ playlists

    @Test
    void createListAndPageMostRecentlyChangedFirst() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i != 2 && i != 3) {
                clock.advance(1000); // 2 and 3 share an updated_at with 1: the id breaks the tie
            }
            ids.add(create(alice, "List " + i));
        }
        create(bob, "Bob's");
        List<String> expected = new ArrayList<>(List.of(ids.get(4)));
        List<String> ties = new ArrayList<>(ids.subList(1, 4));
        ties.sort(Collections.reverseOrder());
        expected.addAll(ties);
        expected.add(ids.get(0));

        List<String> paged = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            Playlists.Page page = playlists.list(alice.userId(), cursor, 2);
            page.items().forEach(p -> paged.add(p.id()));
            cursor = page.nextCursor();
            assertTrue(cursor == null || cursors.add(cursor));
        } while (cursor != null);
        assertEquals(expected, paged);
        assertNull(playlists.list(alice.userId(), null, 5).nextCursor());
        for (String bad : List.of("x", "_abc", "12_", "abc_def")) {
            assertEquals("invalid_cursor", code(() -> playlists.list(alice.userId(), bad, 10)), bad);
        }

        // a change moves a playlist to the front
        clock.advance(1000);
        playlists.add(alice, ids.get(0), song("Song"), null, NO_CHECK);
        Playlists.Playlist first = playlists.list(alice.userId(), null, 10).items().get(0);
        assertEquals(ids.get(0), first.id());
        assertEquals(1, first.entryCount());
        assertEquals(clock.millis(), first.updatedAt());
        assertEquals(List.of("Bob's"), playlists.list(bob.userId(), null, 10).items().stream()
                .map(Playlists.Playlist::name).toList());
    }

    @Test
    void namesAndDescriptionsAreValidatedAndCleaned() throws Exception {
        Playlists.Playlist p = playlists.create(alice.userId(), "  Road\ttrip \u0007 mix ",
                " Line one  \r\nline​ two\n\n\n\nthree \t");
        assertEquals("Road trip mix", p.name());
        assertEquals("Line one\nline two\n\nthree", p.description());
        assertEquals(0, p.entryCount());
        assertEquals(clock.millis(), p.createdAt());
        assertEquals(p.createdAt(), p.updatedAt());
        assertNull(playlists.create(alice.userId(), "x", " \n ").description());

        assertEquals("invalid_name", code(() -> playlists.create(alice.userId(), " \u0001 ", null)));
        assertEquals("name_too_long", code(() -> playlists.create(alice.userId(), "n".repeat(101), null)));
        assertEquals("n".repeat(100), playlists.create(alice.userId(), "n".repeat(100), null).name());
        assertEquals("description_too_long", code(() -> playlists.create(alice.userId(), "x", "d".repeat(2001))));
        assertEquals(2000, playlists.create(alice.userId(), "x", "d".repeat(2000)).description().length());

        // update: rename, change and clear the description; a no-op leaves updated_at alone
        clock.advance(1000);
        Playlists.Playlist renamed = playlists.update(alice.userId(), p.id(), new Playlists.Edit(" New ", null, false));
        assertEquals("New", renamed.name());
        assertEquals(p.description(), renamed.description());
        assertEquals(clock.millis(), renamed.updatedAt());
        clock.advance(1000);
        assertEquals(renamed, playlists.update(alice.userId(), p.id(), new Playlists.Edit("New", null, false)));
        Playlists.Playlist cleared = playlists.update(alice.userId(), p.id(), new Playlists.Edit(null, null, true));
        assertNull(cleared.description());
        assertEquals(clock.millis(), cleared.updatedAt());
        assertEquals("Hi", playlists.update(alice.userId(), p.id(), new Playlists.Edit(null, "Hi", false)).description());
        assertNull(playlists.update(alice.userId(), p.id(), new Playlists.Edit(null, "  ", false)).description());
        assertEquals("invalid_name", code(() -> playlists.update(alice.userId(), p.id(),
                new Playlists.Edit("\u0002", null, false))));
        assertEquals("New", playlists.get(alice.userId(), p.id()).name());
    }

    @Test
    void playlistsArePrivate() throws Exception {
        String p = create(alice, "Mine");
        String e = add(p, song("Song"), null);
        String bobs = bob.userId();
        assertEquals("playlist_not_found", code(() -> playlists.get(bobs, p)));
        assertEquals("playlist_not_found", code(() -> playlists.update(bobs, p, new Playlists.Edit("X", null, false))));
        assertFalse(playlists.delete(bobs, p));
        assertEquals("playlist_not_found", code(() -> playlists.add(bob, p, song("Other"), null, NO_CHECK)));
        assertEquals("playlist_not_found", code(() -> playlists.move(bobs, p, e, null)));
        assertEquals("playlist_not_found", code(() -> playlists.remove(bobs, p, e)));
        assertEquals("playlist_not_found", code(() -> playlists.get(alice.userId(), UuidV7.newId())));
        assertEquals(List.of(e), order(p));
        assertEquals("Mine", playlists.get(alice.userId(), p).name());
    }

    @Test
    void thereIsALimitOnPlaylistsPerUser() throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlists (id, owner_id, name, created_at,"
                    + " updated_at) VALUES (?, ?, 'p', 1, 1)")) {
                for (int i = 0; i < Playlists.MAX_PLAYLISTS; i++) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, alice.userId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        assertEquals("too_many_playlists", code(() -> create(alice, "One too many")));
        create(bob, "Bob is fine");
    }

    // ------------------------------------------------------------------ entries

    @Test
    void entriesKeepTheirOrderAndDuplicatesAreAllowed() throws Exception {
        String p = create(alice, "Mix");
        String a = song("A");
        String b = song("B");
        String c = song("C");
        clock.advance(5000);
        String ea = add(p, a, null);
        String eb = add(p, b, null);
        String ec = add(p, c, null);
        String ea2 = add(p, a, null); // a duplicate
        String eFront = add(p, c, ea); // before the first entry
        String eMid = add(p, b, ec); // between b and c
        assertEquals(List.of(eFront, ea, eb, eMid, ec, ea2), order(p));

        Playlists.PlaylistPage page = playlists.get(alice.userId(), p);
        assertEquals(6, page.entryCount());
        assertEquals(List.of(c, a, b, b, c, a), page.entries().stream().map(e -> e.item().id()).toList());
        Playlists.Entry first = page.entries().get(0);
        assertEquals("C", first.item().title());
        assertEquals("Artist", first.item().artist());
        assertEquals("audio", first.item().kind());
        assertEquals(1000L, first.item().durationMs());
        assertEquals(clock.millis(), first.addedAt());
        assertEquals(6, page.entries().stream().map(Playlists.Entry::id).distinct().count());

        // adding references the item but doesn't put it in a library
        assertEquals(clock.millis(), lastReferenced(a));
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));

        assertEquals("before_entry_not_found", code(() -> add(p, a, UuidV7.newId())));
        String other = create(alice, "Other");
        String foreign = add(other, a, null);
        assertEquals("before_entry_not_found", code(() -> add(p, a, foreign)));
        assertEquals("item_not_found", code(() -> add(p, UuidV7.newId(), null)));
        assertEquals(6, order(p).size());
    }

    @Test
    void entriesMove() throws Exception {
        String p = create(alice, "Mix");
        List<String> e = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            e.add(add(p, song("S" + i), null));
        }
        clock.advance(1000);
        Playlists.Entry moved = playlists.move(alice.userId(), p, e.get(3), e.get(0));
        assertEquals(e.get(3), moved.id());
        assertEquals("S3", moved.item().title());
        assertEquals(List.of(e.get(3), e.get(0), e.get(1), e.get(2), e.get(4)), order(p));
        assertEquals(clock.millis(), playlists.get(alice.userId(), p).updatedAt());
        playlists.move(alice.userId(), p, e.get(3), null); // to the end
        assertEquals(List.of(e.get(0), e.get(1), e.get(2), e.get(4), e.get(3)), order(p));
        playlists.move(alice.userId(), p, e.get(0), e.get(2)); // later in the list
        assertEquals(List.of(e.get(1), e.get(0), e.get(2), e.get(4), e.get(3)), order(p));
        playlists.move(alice.userId(), p, e.get(0), e.get(2)); // already there
        playlists.move(alice.userId(), p, e.get(4), e.get(4)); // before itself
        playlists.move(alice.userId(), p, e.get(3), null); // already last
        assertEquals(List.of(e.get(1), e.get(0), e.get(2), e.get(4), e.get(3)), order(p));

        assertEquals("entry_not_found", code(() -> playlists.move(alice.userId(), p, UuidV7.newId(), null)));
        assertEquals("before_entry_not_found", code(() -> playlists.move(alice.userId(), p, e.get(1),
                UuidV7.newId())));
        String other = create(alice, "Other");
        String foreign = add(other, song("X"), null);
        assertEquals("entry_not_found", code(() -> playlists.move(alice.userId(), p, foreign, null)));
        assertEquals("before_entry_not_found", code(() -> playlists.move(alice.userId(), p, e.get(1), foreign)));
        assertEquals(List.of(foreign), order(other));
    }

    @Test
    void positionsAreRenumberedWhenAGapRunsOut() throws Exception {
        String p = create(alice, "Mix");
        String s = song("S");
        String head = add(p, s, null);
        String tail = add(p, s, null);
        // Always insert right before the tail: the gap halves every time (doubles run out after ~50).
        List<String> expected = new ArrayList<>(List.of(head));
        for (int i = 0; i < 150; i++) {
            expected.add(add(p, s, tail));
        }
        expected.add(tail);
        assertEquals(expected, order(p));
        // And the same with moves: keep moving the first entry to just before the second-to-last one.
        String last = expected.get(expected.size() - 1);
        for (int i = 0; i < 120; i++) {
            String mover = expected.remove(0);
            expected.add(expected.size() - 1, mover);
            playlists.move(alice.userId(), p, mover, last);
        }
        assertEquals(expected, order(p));
        List<Double> positions = db.read(c -> {
            List<Double> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT position FROM playlist_entries WHERE playlist_id = ? ORDER BY position")) {
                ps.setString(1, p);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getDouble(1));
                    }
                }
            }
            return out;
        });
        assertEquals(positions.size(), new HashSet<>(positions).size(), "positions are distinct");
    }

    @Test
    void removingAndDeletingMarkTheItems() throws Exception {
        String p = create(alice, "Mix");
        String a = song("A");
        String b = song("B");
        String ea = add(p, a, null);
        String eb = add(p, b, null);
        String ea2 = add(p, a, null);
        clock.set(1_800_000_500_000L);
        assertTrue(playlists.remove(alice.userId(), p, ea));
        assertEquals(1_800_000_500_000L, lastReferenced(a));
        assertEquals(List.of(eb, ea2), order(p));
        assertEquals(1_800_000_500_000L, playlists.get(alice.userId(), p).updatedAt());
        assertFalse(playlists.remove(alice.userId(), p, ea));
        assertFalse(playlists.remove(alice.userId(), p, UuidV7.newId()));

        String keep = create(alice, "Keep");
        add(keep, b, null);
        clock.set(1_800_000_600_000L);
        assertTrue(playlists.delete(alice.userId(), p));
        assertEquals(1_800_000_600_000L, lastReferenced(a));
        assertEquals(1_800_000_600_000L, lastReferenced(b));
        assertEquals(0, count("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = ?", p));
        assertEquals("playlist_not_found", code(() -> playlists.get(alice.userId(), p)));
        assertFalse(playlists.delete(alice.userId(), p));
        assertEquals(1, playlists.get(alice.userId(), keep).entryCount());
        assertEquals("ready", playlists.get(alice.userId(), keep).entries().get(0).item().status());
    }

    @Test
    void itemStatusRules() throws Exception {
        String p = create(alice, "Mix");
        // pending and processing items can be added (they are on their way)
        add(p, item("Soon", "pending", null), null);
        add(p, item("Busy", "processing", null), null);
        assertEquals("media_expired", code(() -> add(p, item("Gone", "purged", null), null)));
        assertEquals("item_failed", code(() -> add(p, item("Broken", "failed", null), null)));

        String ready = item("Ready video", "ready", "aaaaaaaaaaa");
        add(p, ready, null);
        assertTrue(jobs.latestForItem(ready).isEmpty());

        String purged = item("Old video", "purged", "bbbbbbbbbbb");
        assertEquals(403, assertThrows(HttpError.class, () -> playlists.add(alice, p, purged, null, () -> {
            throw new HttpError(403, "feature_disabled", "off");
        })).status());
        assertTrue(jobs.latestForItem(purged).isEmpty());
        assertEquals(3, order(p).size());

        AtomicInteger checks = new AtomicInteger();
        clock.set(1_800_000_200_000L);
        Playlists.Entry back = playlists.add(alice, p, purged, null, checks::incrementAndGet);
        assertEquals(1, checks.get());
        assertEquals("pending", back.item().status());
        assertEquals(IngestJob.Kind.REFETCH, jobs.latestForItem(purged).orElseThrow().kind());
        assertEquals(1_800_000_200_000L, lastReferenced(purged));
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));

        String failed = item("Failed video", "failed", "ccccccccccc");
        playlists.add(alice, p, failed, null, checks::incrementAndGet);
        assertEquals(IngestJob.Kind.YOUTUBE, jobs.latestForItem(failed).orElseThrow().kind());
        // a recent failure is refused, like an import of its link
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_jobs SET state = 'failed', finished_at = ? WHERE item_id = ?")) {
                ps.setLong(1, clock.millis());
                ps.setString(2, failed);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'failed' WHERE id = ?")) {
                ps.setString(1, failed);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals("youtube_failed", code(() -> playlists.add(alice, p, failed, null, () -> {
        })));
        assertEquals(5, order(p).size());
    }

    @Test
    void aFullPlaylistRefusesMore() throws Exception {
        String p = create(alice, "Big");
        String s = song("S");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlist_entries (id, playlist_id, item_id,"
                    + " position, added_at) VALUES (?, ?, ?, ?, 1)")) {
                for (int i = 0; i < Playlists.MAX_ENTRIES; i++) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, p);
                    ps.setString(3, s);
                    ps.setDouble(4, i + 1);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        assertEquals("playlist_full", code(() -> add(p, s, null)));
        assertEquals(Playlists.MAX_ENTRIES, playlists.get(alice.userId(), p).entryCount());
    }

    @Test
    void concurrentAddsAllLandInDistinctPlaces() throws Exception {
        String p = create(alice, "Party");
        String first = add(p, song("First"), null);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            items.add(song("S" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                String item = items.get(i);
                String before = i % 2 == 0 ? null : first;
                results.add(pool.submit(() -> add(p, item, before)));
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> f : results) {
                ids.add(f.get());
            }
            assertEquals(16, ids.size());
        } finally {
            pool.shutdownNow();
        }
        List<String> order = order(p);
        assertEquals(17, order.size());
        assertNotEquals(first, order.get(0), "odd adds went before the first entry");
        List<Double> positions = db.read(c -> {
            List<Double> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT position FROM playlist_entries WHERE playlist_id = ?")) {
                ps.setString(1, p);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getDouble(1));
                    }
                }
            }
            return out;
        });
        assertEquals(17, new HashSet<>(positions).size(), "no two entries share a position");
    }
}
