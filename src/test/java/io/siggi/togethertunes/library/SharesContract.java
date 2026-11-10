package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sharing ({@link Shares}): the recipient's library entry, recipients by username, item status
 * rules, messages, both directions with paging, privacy of the sender's view, unseen counts and
 * marking seen, and the hourly limit.
 */
abstract class SharesContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private IngestJobs jobs;
    private Shares shares;
    private Library library;
    private Actor.User alice;
    private Actor.User bob;
    private Actor.User carol;
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
        shares = new Shares(db, youtube, clock);
        library = new Library(db, youtube, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        alice = user(accounts, "alice", "Alice");
        bob = user(accounts, "Bob.B", "Bob");
        carol = user(accounts, "carol", "Carol");
    }

    private static Actor.User user(Accounts accounts, String username, String displayName) throws Exception {
        return new Actor.User(accounts.createLocal(username, displayName, username + "-password", false, null,
                "account.register").id(), "s-" + username, Actor.Via.BEARER);
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

    private void sql(String sql, String param) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, param);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String code(Executable e) {
        return assertThrows(HttpError.class, e::run).code();
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }

    private Shares.Share share(Actor.User from, String itemId, String to, String message) throws Exception {
        return shares.share(from, itemId, to, message, NO_CHECK);
    }

    private List<String> ids(Shares.Page page) {
        List<String> out = new ArrayList<>();
        page.items().forEach(s -> out.add(s.id()));
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aShareAddsTheItemToTheRecipientsLibrary() throws Exception {
        String song = song("Song");
        clock.set(1_800_000_100_000L);
        Shares.Share s = share(alice, song, "bob.b", "  Listen\r\n\r\n\r\nto  this \t ");
        assertEquals(song, s.item().id());
        assertEquals("Song", s.item().title());
        assertEquals("ready", s.item().status());
        assertEquals(alice.userId(), s.from().id());
        assertEquals("Alice", s.from().name());
        assertEquals(bob.userId(), s.to().id());
        assertEquals("Bob", s.to().name());
        assertEquals("Listen\n\nto  this", s.message());
        assertEquals(1_800_000_100_000L, s.createdAt());
        assertNull(s.seen(), "the sender doesn't learn whether it was seen");

        Library.Entry entry = library.get(bob.userId(), song);
        assertNotNull(entry);
        assertEquals("share", entry.via());
        assertEquals(alice.userId(), entry.sharedBy().id());
        assertEquals("Alice", entry.sharedBy().name());
        assertEquals(1_800_000_100_000L, entry.addedAt());
        assertEquals(1_800_000_100_000L, lastReferenced(song));
        assertNull(library.get(alice.userId(), song), "sharing doesn't touch the sender's library");

        // a second share of an item the recipient already has: a new notification, the entry stays
        clock.set(1_800_000_200_000L);
        Shares.Share again = share(carol, song, "BOB.B", null);
        assertNull(again.message());
        Library.Entry kept = library.get(bob.userId(), song);
        assertEquals(1_800_000_100_000L, kept.addedAt());
        assertEquals(alice.userId(), kept.sharedBy().id());
        assertEquals(2, shares.unseenCount(bob.userId()));
        assertEquals(1_800_000_200_000L, lastReferenced(song));

        // a manually added item keeps via='manual'
        String other = song("Other");
        library.add(carol, other, NO_CHECK);
        share(alice, other, "carol", null);
        assertEquals("manual", library.get(carol.userId(), other).via());

        // the recipient can remove it like any other entry; the share history stays
        assertTrue(library.remove(bob.userId(), song));
        assertEquals(2, shares.list(bob.userId(), Shares.Direction.IN, null, 10).items().size());
    }

    @Test
    void recipientsAndBodiesAreChecked() throws Exception {
        String song = song("Song");
        assertEquals("user_not_found", code(() -> share(alice, song, "nobody", null)));
        assertEquals("user_not_found", code(() -> share(alice, song, "", null)));
        assertEquals("user_not_found", code(() -> share(alice, song, "bob%", null)));
        assertEquals("user_not_found", code(() -> share(alice, song, "x".repeat(500), null)));
        assertEquals("cannot_share_with_self", code(() -> share(alice, song, "ALICE", null)));
        assertEquals("item_not_found", code(() -> share(alice, UuidV7.newId(), "carol", null)));
        assertEquals("message_too_long", code(() -> share(alice, song, "carol", "x".repeat(Shares.MESSAGE_MAX + 1))));
        assertEquals(Shares.MESSAGE_MAX, share(alice, song, " carol ", "x".repeat(Shares.MESSAGE_MAX)).message().length());
        assertNull(share(alice, song, "carol", " \n\t ").message());

        sql("UPDATE users SET disabled = 1 WHERE id = ?", bob.userId());
        assertEquals("user_not_found", code(() -> share(alice, song, "bob.b", null)));
        assertNull(library.get(bob.userId(), song));
        assertEquals(0, count("SELECT COUNT(*) FROM shares WHERE to_user_id = ?", bob.userId()));
    }

    @Test
    void itemStatusRulesMirrorTheLibrary() throws Exception {
        assertEquals("media_expired", code(() -> share(alice, item("Gone", "purged", null), "bob.b", null)));
        assertEquals("item_failed", code(() -> share(alice, item("Broken", "failed", null), "bob.b", null)));
        share(alice, item("Pending", "pending", null), "bob.b", null);

        String ready = item("Ready video", "ready", "aaaaaaaaaaa");
        share(alice, ready, "bob.b", null);
        assertTrue(jobs.latestForItem(ready).isEmpty());

        String purged = item("Old video", "purged", "bbbbbbbbbbb");
        assertEquals(403, assertThrows(HttpError.class, () -> shares.share(alice, purged, "bob.b", null, () -> {
            throw new HttpError(403, "feature_disabled", "off");
        })).status());
        assertTrue(jobs.latestForItem(purged).isEmpty());
        assertNull(library.get(bob.userId(), purged));

        AtomicInteger checks = new AtomicInteger();
        clock.set(1_800_000_300_000L);
        Shares.Share back = shares.share(alice, purged, "bob.b", null, checks::incrementAndGet);
        assertEquals(1, checks.get());
        assertEquals("pending", back.item().status());
        assertEquals(IngestJob.Kind.REFETCH, jobs.latestForItem(purged).orElseThrow().kind());
        assertEquals(1_800_000_300_000L, lastReferenced(purged));
        assertEquals("share", library.get(bob.userId(), purged).via());
        assertEquals(3, count("SELECT COUNT(*) FROM shares WHERE to_user_id = ?", bob.userId()));
    }

    @Test
    void bothDirectionsPageNewestFirst() throws Exception {
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            clock.set(1_800_000_000_000L + (i / 3) * 1000); // ties of three
            sent.add(share(alice, song("S" + i), "bob.b", "m" + i).id());
        }
        share(carol, song("From carol"), "bob.b", null);
        share(bob, song("To alice"), "alice", null);

        List<String> in = new ArrayList<>();
        String cursor = null;
        do {
            Shares.Page page = shares.list(bob.userId(), Shares.Direction.IN, cursor, 3);
            assertTrue(page.items().size() <= 3);
            assertEquals(8, page.unseenCount());
            in.addAll(ids(page));
            for (Shares.Share s : page.items()) {
                assertEquals(Boolean.FALSE, s.seen());
                assertEquals(bob.userId(), s.to().id());
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(8, in.size());
        // newest first; ties by id descending (UUIDv7 ids grow, so later shares come first):
        // carol's share has the same time as the last of alice's
        assertEquals(sentReversed(sent), in.subList(1, 8));

        Shares.Page out = shares.list(alice.userId(), Shares.Direction.OUT, null, 50);
        assertEquals(sentReversed(sent), ids(out));
        assertNull(out.nextCursor());
        for (Shares.Share s : out.items()) {
            assertNull(s.seen());
            assertEquals("Bob", s.to().name());
        }
        assertEquals(1, out.unseenCount(), "the caller's own unseen count, also in the outgoing list");

        assertEquals("invalid_cursor", code(() -> shares.list(bob.userId(), Shares.Direction.IN, "nope", 3)));
        assertEquals("invalid_cursor", code(() -> shares.list(bob.userId(), Shares.Direction.IN, "12_", 3)));
        assertEquals("invalid_cursor", code(() -> shares.list(bob.userId(), Shares.Direction.IN, "_x", 3)));
        assertEquals(0, shares.list(carol.userId(), Shares.Direction.IN, null, 3).items().size());
    }

    private static List<String> sentReversed(List<String> sent) {
        List<String> r = new ArrayList<>(sent);
        java.util.Collections.reverse(r);
        return r;
    }

    @Test
    void markingSeen() throws Exception {
        String a = share(alice, song("A"), "bob.b", null).id();
        String b = share(alice, song("B"), "bob.b", null).id();
        String c = share(alice, song("C"), "bob.b", null).id();
        String toCarol = share(alice, song("D"), "carol", null).id();
        assertEquals(3, shares.unseenCount(bob.userId()));

        assertEquals(2, shares.markSeen(bob.userId(), List.of(a, a, toCarol, "not-an-id")));
        assertEquals(1, shares.unseenCount(carol.userId()), "someone else's share isn't touched");
        assertEquals(2, shares.markSeen(bob.userId(), List.of()));
        for (Shares.Share s : shares.list(bob.userId(), Shares.Direction.IN, null, 10).items()) {
            assertEquals(s.id().equals(a), s.seen(), s.id());
        }
        assertEquals(1, shares.markSeen(bob.userId(), List.of(b)));
        assertEquals(0, shares.markSeen(bob.userId(), null));
        assertEquals(0, shares.unseenCount(bob.userId()));
        assertEquals(c, shares.list(bob.userId(), Shares.Direction.IN, null, 1).items().get(0).id());
        assertEquals(1, shares.unseenCount(carol.userId()));
        assertEquals(0, shares.markSeen(carol.userId(), null));
    }

    @Test
    void sendersAreLimitedPerHour() throws Exception {
        String song = song("Song");
        for (int i = 0; i < Shares.MAX_PER_HOUR; i++) {
            clock.set(1_800_000_000_000L + i * 10_000L);
            share(alice, song, i % 2 == 0 ? "bob.b" : "carol", null);
        }
        HttpError limited = assertThrows(HttpError.class, () -> share(alice, song, "bob.b", null));
        assertEquals(429, limited.status());
        assertEquals("too_many_shares", limited.code());
        // the oldest share leaves the window an hour after it was made
        long now = 1_800_000_000_000L + (Shares.MAX_PER_HOUR - 1) * 10_000L;
        assertEquals((1_800_000_000_000L + 3_600_000L - now) / 1000, limited.retryAfterSeconds());

        // a purged YouTube item isn't refetched for an over-limit sender
        String purged = item("Old video", "purged", "bbbbbbbbbbb");
        assertEquals("too_many_shares", code(() -> shares.share(alice, purged, "bob.b", null, () -> {
        })));
        assertTrue(jobs.latestForItem(purged).isEmpty());

        share(bob, song, "carol", null); // others aren't affected
        clock.set(1_800_000_000_000L + 3_600_000L);
        share(alice, song, "bob.b", null);
    }
}
