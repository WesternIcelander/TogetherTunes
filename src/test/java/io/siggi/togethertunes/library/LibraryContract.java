package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user's library ({@link Library}): listing order and cursor pagination (with ties), search by
 * words over title/artist/album, the kind filter, adding by id for every item status, removing,
 * and {@code last_referenced_at}.
 */
abstract class LibraryContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private IngestJobs jobs;
    private Library library;
    private final AtomicInteger wakes = new AtomicInteger();
    private Actor.User alice;
    private Actor.User bob;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        jobs = new IngestJobs(db, clock);
        // addExisting never runs yt-dlp, so a missing tool is fine.
        YoutubeImports youtube = new YoutubeImports(db, new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString()),
                jobs, wakes::incrementAndGet, OutputEncoding.defaultFor(OutputProfile.P720_30), 600, () -> null, clock);
        library = new Library(db, youtube, clock);
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

    private String item(String kind, String title, String artist, String album, String status, String youtubeId)
            throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, album,"
                    + " duration_ms, source, youtube_id, added_by_user_id, added_at, status, output_profile,"
                    + " last_referenced_at) VALUES (?, ?, ?, ?, ?, 1000, ?, ?, ?, 1, ?, '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, title);
                ps.setString(4, artist);
                ps.setString(5, album);
                ps.setString(6, youtubeId != null ? "youtube" : "upload");
                ps.setString(7, youtubeId);
                ps.setString(8, alice.userId());
                ps.setString(9, status);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private String song(String title, String artist, String album) throws SQLException {
        return item("audio", title, artist, album, "ready", null);
    }

    private void entry(Actor.User user, String itemId, long addedAt, String via, String sharedBy) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at,"
                    + " via, shared_by_user_id) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, user.userId());
                ps.setString(2, itemId);
                ps.setLong(3, addedAt);
                ps.setString(4, via);
                ps.setString(5, sharedBy);
                ps.executeUpdate();
            }
            return null;
        });
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

    private List<String> ids(Library.Page page) {
        List<String> out = new ArrayList<>();
        for (Library.Entry e : page.items()) {
            out.add(e.item().id());
        }
        return out;
    }

    private List<String> all(Actor.User user, String q, String kind) throws SQLException {
        return ids(library.list(user.userId(), q, kind, null, Library.LIST_MAX));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void newestFirstWithCursorPagesAcrossTies() throws Exception {
        List<String> expected = new ArrayList<>();
        // 7 entries; three share added_at 300, so the id breaks the tie (descending).
        long[] at = {100, 200, 300, 300, 300, 400, 500};
        List<String> created = new ArrayList<>();
        for (long t : at) {
            String id = song("Song " + t, null, null);
            created.add(id);
            entry(alice, id, t, "upload", null);
        }
        expected.add(created.get(6));
        expected.add(created.get(5));
        List<String> ties = new ArrayList<>(created.subList(2, 5));
        ties.sort(java.util.Comparator.reverseOrder());
        expected.addAll(ties);
        expected.add(created.get(1));
        expected.add(created.get(0));
        // bob's library is separate
        entry(bob, song("Bob's", null, null), 999, "upload", null);

        assertEquals(expected, all(alice, null, null));
        List<String> paged = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Library.Page page = library.list(alice.userId(), null, null, cursor, 2);
            assertTrue(page.items().size() <= 2);
            paged.addAll(ids(page));
            cursor = page.nextCursor();
            assertTrue(cursor == null || cursors.add(cursor));
            pages++;
        } while (cursor != null);
        assertEquals(expected, paged);
        assertEquals(4, pages);
        // exactly a full last page: no further cursor
        assertNull(library.list(alice.userId(), null, null, null, 7).nextCursor());
        assertNotNull(library.list(alice.userId(), null, null, null, 6).nextCursor());

        for (String bad : List.of("x", "_abc", "12_", "abc_def", "")) {
            HttpError e = assertThrows(HttpError.class, () -> library.list(alice.userId(), null, null, bad, 10),
                    bad);
            assertEquals("invalid_cursor", e.code());
        }
        assertEquals(List.of(), ids(library.list(bob.userId() + "x", null, null, null, 10)));
    }

    @Test
    void entriesCarryTheItemAndHowItCameThere() throws Exception {
        String id = item("video", "Clip", "Band", "LP", "ready", "dQw4w9WgXcQ");
        entry(alice, id, 50, "share", bob.userId());
        Library.Entry e = library.list(alice.userId(), null, null, null, 10).items().get(0);
        assertEquals(new Library.ItemView(id, "video", "Clip", "Band", "LP", 1000L, "youtube", "dQw4w9WgXcQ",
                "ready", "uncategorized", null), e.item());
        assertFalse(e.item().presentAsAudio());
        assertEquals(50, e.addedAt());
        assertEquals("share", e.via());
        assertEquals(bob.userId(), e.sharedBy().id());
        assertEquals("Bob", e.sharedBy().name());
        assertFalse(e.sharedBy().isGuest());
        assertEquals(e, library.get(alice.userId(), id));
        assertNull(library.get(bob.userId(), id));
    }

    @Test
    void searchMatchesEveryWordInTitleArtistOrAlbum() throws Exception {
        String a = song("Bohemian Rhapsody", "Queen", "A Night at the Opera");
        String b = song("Don't Stop Me Now", "Queen", "Jazz");
        String c = song("Über den Wolken", "Reinhard Mey", null);
        String d = song("100% Pure_Love", "Crystal Waters", null);
        String e = item("video", "Opera Video", "Somebody", null, "ready", null);
        long t = 1;
        for (String id : List.of(a, b, c, d, e)) {
            entry(alice, id, t++, "upload", null);
        }
        entry(bob, song("Queen B-side", "Queen", null), 1, "upload", null);

        assertEquals(List.of(b, a), all(alice, "queen", null));
        assertEquals(List.of(a), all(alice, "  QUEEN   opera ", null)); // words in different columns
        assertEquals(List.of(e, a), all(alice, "opera", null));
        assertEquals(List.of(a), all(alice, "opera", "audio"));
        assertEquals(List.of(e), all(alice, "opera", "video"));
        assertEquals(List.of(e), all(alice, null, "video"));
        assertEquals(List.of(c), all(alice, "über", null)); // Unicode case folding
        assertEquals(List.of(c), all(alice, "ÜBER mey", null));
        assertEquals(List.of(d), all(alice, "%", null)); // LIKE wildcards are literal
        assertEquals(List.of(d), all(alice, "e_l", null));
        assertEquals(List.of(), all(alice, "_x", null));
        assertEquals(List.of(), all(alice, "queen jazz opera", null));
        assertEquals(5, all(alice, "   ", null).size());
        assertEquals(5, all(alice, "", null).size());

        assertEquals("invalid_kind", assertThrows(HttpError.class,
                () -> library.list(alice.userId(), null, "podcast", null, 10)).code());
        // the type= filter (docs/media-types.md): audio items are audio, the video uncategorized until set
        java.util.function.Function<String, List<String>> typed = type -> {
            try {
                return library.list(alice.userId(), null, null, type, null, 50).items().stream()
                        .map(x -> x.item().id()).toList();
            } catch (java.sql.SQLException ex) {
                throw new RuntimeException(ex);
            }
        };
        assertEquals(List.of(e), typed.apply("uncategorized"));
        assertEquals(List.of(d, c, b, a), typed.apply("audio"));
        assertEquals(List.of(), typed.apply("movie,show"));
        db.write(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "UPDATE items SET media_type = 'movie' WHERE id = ?")) {
                ps.setString(1, e);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(List.of(e), typed.apply("movie,show"));
        assertEquals("movie", library.list(alice.userId(), null, null, "movie", null, 50).items().get(0).item().mediaType());
        assertEquals("invalid_type", assertThrows(HttpError.class,
                () -> library.list(alice.userId(), null, null, "song", null, 10)).code());
        assertEquals("invalid_query", assertThrows(HttpError.class,
                () -> library.list(alice.userId(), "x".repeat(Library.QUERY_MAX_CHARS + 1), null, null, 10)).code());
        assertEquals(List.of(), all(alice, "x".repeat(Library.QUERY_MAX_CHARS - 10) + " jazz", null));
        assertEquals(List.of("a", "b"), Library.terms(" a  b a "));
        assertEquals(Library.QUERY_MAX_TERMS, Library.terms("1 2 3 4 5 6 7 8 9 10 11 12").size());
    }

    @Test
    void addByIdIsIdempotentAndReferencesTheItem() throws Exception {
        String ready = song("Ready", null, null);
        String pending = item("audio", "Pending", null, null, "pending", null);
        List<String> told = new java.util.ArrayList<>();
        library.libraryAddListener(told::add); // the server queues the quality re-encode there
        clock.set(1_800_000_100_000L);
        Library.Added added = library.add(alice, ready, () -> {
            throw new AssertionError("no YouTube check for an upload");
        });
        assertTrue(added.created());
        assertEquals("manual", added.entry().via());
        assertEquals(1_800_000_100_000L, added.entry().addedAt());
        assertEquals(1_800_000_100_000L, lastReferenced(ready));
        assertEquals(List.of(ready), told);

        clock.advance(5000);
        Library.Added again = library.add(alice, ready, () -> {
        });
        assertFalse(again.created());
        assertEquals(added.entry(), again.entry());
        // an existing "upload" entry keeps its via
        entry(bob, ready, 7, "upload", null);
        assertEquals("upload", library.add(bob, ready, () -> {
        }).entry().via());

        assertTrue(library.add(alice, pending, () -> {
        }).created());
        assertEquals(List.of(pending, ready), all(alice, null, null));

        HttpError missing = assertThrows(HttpError.class, () -> library.add(alice, UuidV7.newId(), () -> {
        }));
        assertEquals(404, missing.status());
        assertEquals("item_not_found", missing.code());
    }

    @Test
    void purgedOrFailedUploadsCantBeAdded() throws Exception {
        String purged = item("audio", "Gone", null, null, "purged", null);
        String failed = item("audio", "Broken", null, null, "failed", null);
        HttpError p = assertThrows(HttpError.class, () -> library.add(alice, purged, () -> {
        }));
        assertEquals(409, p.status());
        assertEquals("media_expired", p.code());
        HttpError f = assertThrows(HttpError.class, () -> library.add(alice, failed, () -> {
        }));
        assertEquals("item_failed", f.code());
        assertEquals(List.of(), all(alice, null, null));
        assertTrue(jobs.latestForItem(purged).isEmpty());
        // already in the library (from before it expired): just reported
        entry(alice, purged, 3, "upload", null);
        assertFalse(library.add(alice, purged, () -> {
        }).created());
    }

    @Test
    void aPurgedYoutubeItemIsFetchedAgainWhenAdded() throws Exception {
        String ready = item("video", "Ready video", null, null, "ready", "aaaaaaaaaaa");
        String purged = item("video", "Old video", null, null, "purged", "bbbbbbbbbbb");
        String failed = item("video", "Failed video", null, null, "failed", "ccccccccccc");

        AtomicInteger checks = new AtomicInteger();
        Library.Added r = library.add(alice, ready, checks::incrementAndGet);
        assertTrue(r.created());
        assertEquals("manual", r.entry().via());
        assertEquals(0, checks.get(), "a ready item needs no download");
        assertTrue(jobs.latestForItem(ready).isEmpty());

        // refused by the check: nothing happens
        assertEquals(403, assertThrows(HttpError.class, () -> library.add(alice, purged, () -> {
            throw new HttpError(403, "feature_disabled", "off");
        })).status());
        assertNull(library.get(alice.userId(), purged));
        assertTrue(jobs.latestForItem(purged).isEmpty());

        clock.set(1_800_000_200_000L);
        Library.Added back = library.add(alice, purged, checks::incrementAndGet);
        assertEquals(1, checks.get());
        assertTrue(back.created());
        assertEquals("manual", back.entry().via());
        assertEquals("pending", back.entry().item().status());
        assertEquals(IngestJob.Kind.REFETCH, jobs.latestForItem(purged).orElseThrow().kind());
        assertEquals(1_800_000_200_000L, lastReferenced(purged));
        assertTrue(wakes.get() >= 1);

        // a failed one is retried like an import of its link (it has no job yet → retried now)
        assertTrue(library.add(alice, failed, checks::incrementAndGet).created());
        assertEquals(IngestJob.Kind.YOUTUBE, jobs.latestForItem(failed).orElseThrow().kind());
        // and refused again while its last attempt is recent
        String recent = item("video", "Failed again", null, null, "failed", "ddddddddddd");
        jobs.enqueue(recent, IngestJob.Kind.YOUTUBE);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_jobs SET state = 'failed', finished_at = ? WHERE item_id = ?")) {
                ps.setLong(1, clock.millis());
                ps.setString(2, recent);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'failed' WHERE id = ?")) {
                ps.setString(1, recent);
                ps.executeUpdate();
            }
            return null;
        });
        HttpError wait = assertThrows(HttpError.class, () -> library.add(bob, recent, () -> {
        }));
        assertEquals("youtube_failed", wait.code());
        assertNull(library.get(bob.userId(), recent));
    }

    @Test
    void removeDeletesOnlyTheEntryAndMarksTheItem() throws Exception {
        String id = song("Song", null, null);
        entry(alice, id, 10, "upload", null);
        entry(bob, id, 11, "share", alice.userId());
        clock.set(1_800_000_300_000L);
        assertTrue(library.remove(alice.userId(), id));
        assertEquals(1_800_000_300_000L, lastReferenced(id));
        assertEquals(List.of(), all(alice, null, null));
        assertEquals(List.of(id), all(bob, null, null));
        assertFalse(library.remove(alice.userId(), id));
        assertFalse(library.remove(alice.userId(), UuidV7.newId()));
        // never backwards
        clock.set(1_800_000_000_000L);
        assertTrue(library.remove(bob.userId(), id));
        assertEquals(1_800_000_300_000L, lastReferenced(id));
    }
}
