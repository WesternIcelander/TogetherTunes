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
                    + " source, youtube_id, added_by_user_id, added_at, status, output_profile, last_referenced_at,"
                    + " media_type) VALUES (?, ?, ?, 'Artist', 1000, ?, ?, ?, 1, ?, '720p30', 1, ?)")) {
                ps.setString(1, id);
                ps.setString(2, youtubeId != null ? "video" : "audio");
                ps.setString(3, title);
                ps.setString(4, youtubeId != null ? "youtube" : "upload");
                ps.setString(5, youtubeId);
                ps.setString(6, alice.userId());
                ps.setString(7, status);
                ps.setString(8, youtubeId != null ? "music_video" : "audio");
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
    void createListAndPageInTheOwnersOrder() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clock.advance(1000);
            ids.add(create(alice, "List " + i));
        }
        create(bob, "Bob's");
        // a new playlist goes to the top
        List<String> expected = new ArrayList<>(ids);
        Collections.reverse(expected);
        assertEquals(expected, pagedIds(alice, 2));
        assertNull(playlists.list(alice.userId(), null, 5).nextCursor());
        for (String bad : List.of("x", "_abc", "12_", "abc_def", "1_2_", "2_1_x", "1_x_y")) {
            assertEquals("invalid_cursor", code(() -> playlists.list(alice.userId(), bad, 10)), bad);
        }

        // a change doesn't move a playlist
        clock.advance(1000);
        playlists.add(alice, ids.get(0), song("Song"), null, NO_CHECK);
        Playlists.Playlist last = playlists.list(alice.userId(), null, 10).items().get(4);
        assertEquals(ids.get(0), last.id());
        assertEquals(1, last.entryCount());
        assertEquals(clock.millis(), last.updatedAt());
        assertNull(last.builtin());
        assertEquals(List.of("Bob's"), playlists.list(bob.userId(), null, 10).items().stream()
                .map(Playlists.Playlist::name).toList());

        // Liked Songs, once it exists, comes first
        playlists.like(alice, song("Liked"), null, NO_CHECK);
        List<String> withLikes = pagedIds(alice, 2);
        assertEquals(6, withLikes.size());
        assertEquals(expected, withLikes.subList(1, 6));
        Playlists.Playlist likes = playlists.list(alice.userId(), null, 1).items().get(0);
        assertEquals(Playlists.BUILTIN_LIKES, likes.builtin());
        assertEquals("Liked Songs", likes.name());
    }

    /** The ids of all of {@code owner}'s playlists, page by page. */
    private List<String> pagedIds(Actor.User owner, int limit) throws SQLException {
        List<String> paged = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        do {
            Playlists.Page page = playlists.list(owner.userId(), cursor, limit);
            page.items().forEach(p -> paged.add(p.id()));
            cursor = page.nextCursor();
            assertTrue(cursor == null || cursors.add(cursor));
        } while (cursor != null);
        return paged;
    }

    private List<String> listIds(Actor.User owner) throws SQLException {
        return playlists.list(owner.userId(), null, Playlists.LIST_MAX).items().stream()
                .map(Playlists.Playlist::id).toList();
    }

    @Test
    void playlistsAreReordered() throws Exception {
        String a = create(alice, "A");
        String b = create(alice, "B");
        String c = create(alice, "C");
        String d = create(alice, "D");
        assertEquals(List.of(d, c, b, a), listIds(alice));
        long updated = playlists.get(alice.userId(), a).updatedAt();
        clock.advance(1000);
        playlists.movePlaylists(alice.userId(), List.of(a), d); // to the top
        assertEquals(List.of(a, d, c, b), listIds(alice));
        assertEquals(updated, playlists.get(alice.userId(), a).updatedAt(), "the order isn't a change");
        playlists.movePlaylists(alice.userId(), List.of(a), null); // to the end
        assertEquals(List.of(d, c, b, a), listIds(alice));
        playlists.movePlaylists(alice.userId(), List.of(a, d), c); // several, in the given order
        assertEquals(List.of(a, d, c, b), listIds(alice));
        playlists.movePlaylists(alice.userId(), List.of(b, c), d);
        assertEquals(List.of(a, b, c, d), listIds(alice));
        playlists.movePlaylists(alice.userId(), List.of(d, b), b); // before one that moves: before the next one
        assertEquals(List.of(a, d, b, c), listIds(alice));
        playlists.movePlaylists(alice.userId(), List.of(b, c, d), null);
        assertEquals(List.of(a, b, c, d), listIds(alice));
        String e = create(alice, "E"); // new ones still go to the top
        assertEquals(List.of(e, a, b, c, d), listIds(alice));

        String likes = playlists.likes(alice.userId()).id();
        assertEquals(List.of(likes, e, a, b, c, d), listIds(alice));
        assertEquals("builtin_playlist", code(() -> playlists.movePlaylists(alice.userId(), List.of(likes), null)));
        assertEquals("builtin_playlist", code(() -> playlists.movePlaylists(alice.userId(), List.of(e), likes)));
        String bobs = create(bob, "Bob's");
        assertEquals("playlist_not_found", code(() -> playlists.movePlaylists(alice.userId(), List.of(bobs), null)));
        assertEquals("before_playlist_not_found", code(() -> playlists.movePlaylists(alice.userId(), List.of(e),
                bobs)));
        assertEquals(List.of(likes, e, a, b, c, d), listIds(alice));
        assertEquals(List.of(bobs), listIds(bob));
    }

    @Test
    void reorderPlacesMovedIdsBeforeTheAnchor() {
        List<String> order = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("d", "a", "b", "c", "e"), Playlists.reorder(order, List.of("d"), "a"));
        assertEquals(List.of("a", "c", "d", "e", "b"), Playlists.reorder(order, List.of("b"), null));
        assertEquals(List.of("a", "e", "c", "b", "d"), Playlists.reorder(order, List.of("e", "c", "b"), "b"));
        assertEquals(List.of("a", "b", "c", "d", "e"), Playlists.reorder(order, List.of("d"), "e"));
        assertEquals(List.of("a", "e", "d"), Playlists.reorder(List.of("a", "d", "e"), List.of("e", "d"), "d"));
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

        // adding references the item and puts it in the owner's library
        assertEquals(clock.millis(), lastReferenced(a));
        assertEquals(3, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));

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
        assertEquals(4, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));

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

    // ------------------------------------------------------------------ Liked Songs

    @Test
    void likedSongs() throws Exception {
        assertEquals(List.of(), listIds(alice), "made on demand");
        assertFalse(playlists.liked(alice.userId(), song("Unknown")));
        assertFalse(playlists.unlike(alice.userId(), song("Never liked")));
        assertEquals(List.of(), listIds(alice), "unlike doesn't make it");

        String a = song("A");
        String b = song("B");
        clock.advance(1000);
        Playlists.Added first = playlists.like(alice, a, null, NO_CHECK);
        assertTrue(first.created());
        assertEquals(a, first.entry().item().id());
        assertTrue(playlists.liked(alice.userId(), a));
        assertFalse(playlists.liked(bob.userId(), a));
        assertEquals(clock.millis(), lastReferenced(a));
        assertEquals(1, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));
        String likes = playlists.likes(alice.userId()).id();
        Playlists.Added second = playlists.like(alice, b, null, NO_CHECK);
        Playlists.Added again = playlists.like(alice, a, null, NO_CHECK);
        assertFalse(again.created());
        assertEquals(first.entry().id(), again.entry().id());
        // a like goes to the top; the generic add doesn't duplicate either
        assertEquals(List.of(second.entry().id(), first.entry().id()), order(likes));
        Playlists.Added generic = playlists.add(alice, likes, a, null, null, NO_CHECK);
        assertFalse(generic.created());
        assertEquals(first.entry().id(), generic.entry().id());
        // entries move like in any playlist
        playlists.move(alice.userId(), likes, second.entry().id(), null);
        assertEquals(List.of(first.entry().id(), second.entry().id()), order(likes));

        Playlists.PlaylistPage page = playlists.likes(alice.userId());
        assertEquals(likes, page.id());
        assertEquals("Liked Songs", page.name());
        assertEquals(Playlists.BUILTIN_LIKES, page.builtin());
        assertEquals(2, page.entryCount());
        assertEquals("builtin_playlist", code(() -> playlists.update(alice.userId(), likes,
                new Playlists.Edit("Mine", null, false))));
        assertEquals("builtin_playlist", code(() -> playlists.delete(alice.userId(), likes)));
        assertEquals("playlist_not_found", code(() -> playlists.get(bob.userId(), likes)));
        assertEquals(1, count("SELECT COUNT(*) FROM playlists WHERE owner_id = ?", alice.userId()));

        clock.advance(1000);
        assertTrue(playlists.unlike(alice.userId(), a));
        assertFalse(playlists.unlike(alice.userId(), a));
        assertFalse(playlists.liked(alice.userId(), a));
        assertEquals(clock.millis(), lastReferenced(a));
        assertEquals(List.of(second.entry().id()), order(likes));
        assertTrue(new Library(db, youtube(), clock).get(alice.userId(), a) != null, "stays in the library");
    }

    @Test
    void likedSongsIsMadeOnceUnderConcurrency() throws Exception {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(song("S" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Playlists.Added>> results = new ArrayList<>();
            for (String item : items) {
                results.add(pool.submit(() -> playlists.like(alice, item, null, NO_CHECK)));
                results.add(pool.submit(() -> playlists.like(alice, item, null, NO_CHECK)));
            }
            for (Future<Playlists.Added> f : results) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count("SELECT COUNT(*) FROM playlists WHERE owner_id = ?", alice.userId()));
        assertEquals(8, playlists.likes(alice.userId()).entryCount(), "each item once");
    }

    @Test
    void likedSongsDoesNotCountTowardsTheLimit() throws Exception {
        playlists.likes(alice.userId());
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlists (id, owner_id, name, created_at,"
                    + " updated_at) VALUES (?, ?, 'p', 1, 1)")) {
                for (int i = 0; i < Playlists.MAX_PLAYLISTS - 1; i++) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, alice.userId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        create(alice, "The last one");
        assertEquals("too_many_playlists", code(() -> create(alice, "One too many")));
    }

    // ------------------------------------------------------------------ playlist ⊂ library

    private YoutubeImports youtube() {
        return new YoutubeImports(db, new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString()),
                jobs, () -> {
                }, OutputEncoding.defaultFor(OutputProfile.P720_30), 600, () -> null, clock);
    }

    private String uncategorizedVideo(String title) throws SQLException {
        String id = item(title, "ready", null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET kind = 'video', media_type = 'uncategorized' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String mediaType(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT media_type FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    @Test
    void anUncategorizedVideoNeedsATypeToGoIntoAPlaylist() throws Exception {
        String p = create(alice, "Mix");
        String video = uncategorizedVideo("Clip");
        HttpError e = assertThrows(HttpError.class, () -> playlists.add(alice, p, video, null, null, NO_CHECK));
        assertEquals("needs_classification", e.code());
        assertEquals(409, e.status());
        assertEquals(video, ((Classify.NeedsClassification) e).itemId());
        assertEquals("needs_classification", code(() -> playlists.like(alice, video, null, NO_CHECK)));
        assertEquals(0, playlists.get(alice.userId(), p).entryCount());
        assertEquals(0, playlists.likes(alice.userId()).entryCount());
        assertEquals(0, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));
        assertEquals("uncategorized", mediaType(video));

        Playlists.Added added = playlists.add(alice, p, video, null,
                new Classify.Classification(MediaType.MUSIC_VIDEO, null), NO_CHECK);
        assertTrue(added.created());
        assertEquals("music_video", mediaType(video));
        assertEquals("music_video", added.entry().item().mediaType());
        assertEquals(1, count("SELECT COUNT(*) FROM library_entries WHERE user_id = ?", alice.userId()));
        // already in the library: no more asking (a type sent along is ignored)
        playlists.add(alice, p, video, null, null, NO_CHECK);
        // an uncategorized video already in the library (e.g. from before media types) doesn't ask
        String old = uncategorizedVideo("Old clip");
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via) VALUES (?, ?, 1, 'upload')")) {
                ps.setString(1, alice.userId());
                ps.setString(2, old);
                ps.executeUpdate();
            }
            return null;
        });
        playlists.like(alice, old, null, NO_CHECK);
        assertEquals("uncategorized", mediaType(old));
        // bob needn't classify the clip (a music video now), but the old one is still uncategorized
        String bobs = create(bob, "Bob's");
        playlists.add(bob, bobs, video, null, null, NO_CHECK);
        assertEquals("needs_classification", code(() -> playlists.add(bob, bobs, old, null, null, NO_CHECK)));
    }

    @Test
    void removingFromTheLibraryRemovesFromTheOwnersPlaylists() throws Exception {
        Library library = new Library(db, youtube(), clock);
        String p = create(alice, "Mix");
        String q = create(alice, "Other");
        String a = song("A");
        String b = song("B");
        add(p, a, null);
        String eb = add(p, b, null);
        add(p, a, null);
        add(q, a, null);
        playlists.like(alice, a, null, NO_CHECK);
        String bobs = create(bob, "Bob's");
        playlists.add(bob, bobs, a, null, NO_CHECK);
        assertEquals(List.of(playlists.likes(alice.userId()).id(), q, p),
                playlists.containing(alice.userId(), a).stream().map(Playlists.Playlist::id).toList());
        assertEquals(List.of(p), playlists.containing(alice.userId(), b).stream().map(Playlists.Playlist::id).toList());

        clock.advance(5000);
        assertTrue(library.remove(alice.userId(), a));
        assertEquals(List.of(eb), order(p));
        assertEquals(0, playlists.get(alice.userId(), q).entryCount());
        assertEquals(0, playlists.likes(alice.userId()).entryCount());
        assertEquals(clock.millis(), playlists.get(alice.userId(), p).updatedAt());
        assertEquals(clock.millis(), lastReferenced(a));
        assertEquals(List.of(), playlists.containing(alice.userId(), a));
        assertEquals(1, playlists.get(bob.userId(), bobs).entryCount(), "other users keep theirs");
        assertTrue(library.get(bob.userId(), a) != null);
        assertFalse(library.remove(alice.userId(), a));

        // removing from a playlist (or deleting one) keeps the library entry
        playlists.remove(alice.userId(), p, eb);
        assertTrue(library.get(alice.userId(), b) != null);
        add(q, b, null);
        playlists.delete(alice.userId(), q);
        assertTrue(library.get(alice.userId(), b) != null);
    }

    // ------------------------------------------------------------------ multi-entry moves

    private List<String> moveMany(String p, List<String> ids, String before) throws SQLException {
        return playlists.moveEntries(alice.userId(), p, ids, before).entries().stream()
                .map(Playlists.Entry::id).toList();
    }

    @Test
    void severalEntriesMoveInOneCall() throws Exception {
        String p = create(alice, "Mix");
        List<String> e = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            e.add(add(p, song("S" + i), null));
        }
        clock.advance(1000);
        // two later ones into the middle, in the given order
        assertEquals(List.of(e.get(0), e.get(5), e.get(3), e.get(1), e.get(2), e.get(4)),
                moveMany(p, List.of(e.get(5), e.get(3)), e.get(1)));
        assertEquals(clock.millis(), playlists.get(alice.userId(), p).updatedAt());
        // to the front and to the end
        assertEquals(List.of(e.get(4), e.get(2), e.get(0), e.get(5), e.get(3), e.get(1)),
                moveMany(p, List.of(e.get(4), e.get(2)), e.get(0)));
        assertEquals(List.of(e.get(0), e.get(5), e.get(3), e.get(1), e.get(4), e.get(2)),
                moveMany(p, List.of(e.get(4), e.get(2)), null));
        // before one of the moved entries: before the next one that stays
        assertEquals(List.of(e.get(0), e.get(1), e.get(5), e.get(3), e.get(4), e.get(2)),
                moveMany(p, List.of(e.get(1), e.get(5)), e.get(5)));
        // all of them
        List<String> reversed = new ArrayList<>(order(p));
        Collections.reverse(reversed);
        assertEquals(reversed, moveMany(p, reversed, null));
        // a move that changes nothing leaves updated_at alone
        clock.advance(1000);
        long before = playlists.get(alice.userId(), p).updatedAt();
        moveMany(p, List.of(reversed.get(1), reversed.get(2)), reversed.get(3));
        assertEquals(before, playlists.get(alice.userId(), p).updatedAt());

        assertEquals("entry_not_found", code(() -> moveMany(p, List.of(e.get(0), UuidV7.newId()), null)));
        assertEquals("before_entry_not_found", code(() -> moveMany(p, List.of(e.get(0)), UuidV7.newId())));
        String other = create(alice, "Other");
        String foreign = add(other, song("X"), null);
        assertEquals("entry_not_found", code(() -> moveMany(p, List.of(foreign), null)));
        assertEquals("playlist_not_found", code(() -> playlists.moveEntries(bob.userId(), p, List.of(e.get(0)), null)));
        assertEquals(reversed, order(p));
    }

    @Test
    void multiEntryMovesRenumberWhenTheGapIsTooSmall() throws Exception {
        String p = create(alice, "Mix");
        String s = song("S");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            expected.add(add(p, s, null));
        }
        // keep moving the first three to just before the last one: the gap there shrinks fast
        String last = expected.get(expected.size() - 1);
        for (int i = 0; i < 80; i++) {
            List<String> moved = new ArrayList<>(expected.subList(0, 3));
            expected.removeAll(moved);
            expected.addAll(expected.size() - 1, moved);
            assertEquals(expected, moveMany(p, moved, last));
        }
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
        assertEquals(12, new HashSet<>(positions).size(), "positions are distinct");
    }
}
