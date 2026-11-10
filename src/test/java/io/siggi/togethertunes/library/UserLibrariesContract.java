package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.Tools;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code library.viewAll} browsing ({@link UserLibraries}): the user list with search, counts,
 * disabled accounts and paging; another user's library and playlists, read-only (nothing touches
 * {@code last_referenced_at}); unknown users and foreign playlists.
 */
abstract class UserLibrariesContract {
    @TempDir
    Path dir;

    protected Database db;
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private UserLibraries browse;
    private Library library;
    private Playlists playlists;
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
        YoutubeImports youtube = new YoutubeImports(db, new Tools("ffmpeg", "ffprobe",
                dir.resolve("no-yt-dlp").toString()), new IngestJobs(db, clock), () -> {
                }, OutputEncoding.defaultFor(OutputProfile.P720_30), 600, () -> null, clock);
        library = new Library(db, youtube, clock);
        playlists = new Playlists(db, youtube, clock);
        browse = new UserLibraries(db, library, playlists);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        alice = user(accounts, "alice", "Alice Liddell");
        bob = user(accounts, "Bob.B", "Bobby 100%");
        carol = user(accounts, "carol", null);
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

    private String item(String title, String kind) throws SQLException {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, artist, duration_ms,"
                    + " source, added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, ?, ?, 'Artist', 1000, 'upload', ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, title);
                ps.setString(4, alice.userId());
                ps.executeUpdate();
            }
            return null;
        });
        return id;
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

    private static String code(Executable e) {
        return assertThrows(HttpError.class, e::run).code();
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Exception;
    }

    private static List<String> usernames(UserLibraries.UserPage page) {
        List<String> out = new ArrayList<>();
        page.items().forEach(u -> out.add(u.username()));
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void usersAreListedByUsernameWithCounts() throws Exception {
        String a = item("One", "audio");
        String b = item("Two", "video");
        clock.set(1_800_000_100_000L);
        library.add(bob, a, NO_CHECK);
        library.add(bob, b, NO_CHECK);
        playlists.create(bob.userId(), "Mix", null);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE users SET disabled = 1 WHERE id = ?")) {
                ps.setString(1, carol.userId());
                ps.executeUpdate();
            }
            return null;
        });

        UserLibraries.UserPage page = browse.users(null, null, 50);
        assertEquals(List.of("alice", "Bob.B", "carol"), usernames(page));
        assertNull(page.nextCursor());
        UserLibraries.UserSummary bobRow = page.items().get(1);
        assertEquals(bob.userId(), bobRow.id());
        assertEquals("Bobby 100%", bobRow.displayName());
        assertEquals(2, bobRow.libraryCount());
        assertEquals(1, bobRow.playlistCount());
        assertFalse(bobRow.disabled());
        UserLibraries.UserSummary carolRow = page.items().get(2);
        assertEquals("carol", carolRow.displayName());
        assertTrue(carolRow.disabled(), "disabled accounts are listed, marked");
        assertEquals(0, carolRow.libraryCount());
        assertEquals(0, carolRow.playlistCount());
    }

    @Test
    void usersAreSearchableAndPaged() throws Exception {
        // by part of the username or display name, ignoring case; LIKE wildcards are literal
        assertEquals(List.of("alice"), usernames(browse.users("LIDDELL", null, 50)));
        assertEquals(List.of("Bob.B"), usernames(browse.users("b.b", null, 50)));
        assertEquals(List.of("Bob.B"), usernames(browse.users(" 100% ", null, 50)));
        assertEquals(List.of(), usernames(browse.users("_", null, 50)));
        assertEquals(List.of("alice", "Bob.B", "carol"), usernames(browse.users("  ", null, 50)));
        assertEquals("invalid_query", code(() -> browse.users("x".repeat(UserLibraries.QUERY_MAX_CHARS + 1), null, 50)));

        UserLibraries.UserPage first = browse.users(null, null, 2);
        assertEquals(List.of("alice", "Bob.B"), usernames(first));
        assertEquals("bob.b_" + bob.userId(), first.nextCursor());
        UserLibraries.UserPage second = browse.users(null, first.nextCursor(), 2);
        assertEquals(List.of("carol"), usernames(second));
        assertNull(second.nextCursor());
        for (String bad : List.of("_", "bob", "bob_", "_" + bob.userId())) {
            assertEquals("invalid_cursor", code(() -> browse.users(null, bad, 2)), bad);
        }
    }

    @Test
    void anotherUsersLibraryIsReadableWithoutReferencingItems() throws Exception {
        String song = item("Blue Song", "audio");
        String clip = item("Blue Clip", "video");
        String other = item("Red Song", "audio");
        clock.set(1_800_000_100_000L);
        library.add(bob, song, NO_CHECK);
        clock.set(1_800_000_200_000L);
        library.add(bob, clip, NO_CHECK);
        library.add(carol, other, NO_CHECK);
        long before = lastReferenced(song);
        clock.set(1_800_000_900_000L);

        Library.Page page = browse.library(bob.userId(), null, null, null, 50);
        assertEquals(List.of(clip, song), page.items().stream().map(e -> e.item().id()).toList());
        assertEquals(List.of(song), browse.library(bob.userId(), "blue", "audio", null, 50).items().stream()
                .map(e -> e.item().id()).toList());
        Library.Page paged = browse.library(bob.userId(), null, null, null, 1);
        assertEquals(List.of(song), browse.library(bob.userId(), null, null, paged.nextCursor(), 1).items().stream()
                .map(e -> e.item().id()).toList());
        assertEquals(before, lastReferenced(song), "looking isn't a reference");
        assertEquals(List.of(), browse.library(alice.userId(), null, null, null, 50).items());

        assertEquals("user_not_found", code(() -> browse.library(UuidV7.newId(), null, null, null, 50)));
        assertEquals("invalid_kind", code(() -> browse.library(bob.userId(), null, "image", null, 50)));
    }

    @Test
    void anotherUsersPlaylistsAreReadable() throws Exception {
        String song = item("Song", "audio");
        Playlists.Playlist mix = playlists.create(bob.userId(), "Mix", "For the road");
        playlists.add(bob, mix.id(), song, null, NO_CHECK);
        playlists.add(bob, mix.id(), song, null, NO_CHECK);
        clock.set(1_800_000_100_000L);
        Playlists.Playlist later = playlists.create(bob.userId(), "Later", null);
        Playlists.Playlist carols = playlists.create(carol.userId(), "Carol's", null);

        Playlists.Page page = browse.playlists(bob.userId(), null, 50);
        assertEquals(List.of(later.id(), mix.id()), page.items().stream().map(Playlists.Playlist::id).toList());
        assertEquals(2, page.items().get(1).entryCount());
        Playlists.Page paged = browse.playlists(bob.userId(), null, 1);
        assertEquals(List.of(mix.id()), browse.playlists(bob.userId(), paged.nextCursor(), 1).items().stream()
                .map(Playlists.Playlist::id).toList());

        Playlists.PlaylistPage full = browse.playlist(bob.userId(), mix.id());
        assertEquals("Mix", full.name());
        assertEquals(2, full.entries().size());
        assertEquals(song, full.entries().get(0).item().id());

        assertEquals("playlist_not_found", code(() -> browse.playlist(bob.userId(), carols.id())),
                "a playlist is only found under its owner");
        assertEquals("playlist_not_found", code(() -> browse.playlist(bob.userId(), UuidV7.newId())));
        assertEquals("user_not_found", code(() -> browse.playlists(UuidV7.newId(), null, 50)));
        assertEquals("user_not_found", code(() -> browse.playlist(UuidV7.newId(), mix.id())));
        assertEquals(List.of(), browse.playlists(alice.userId(), null, 50).items());
    }
}
