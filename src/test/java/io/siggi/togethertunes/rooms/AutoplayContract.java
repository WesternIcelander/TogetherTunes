package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.library.Playlists;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The autoplay engine on a real database ({@link Autoplay}, {@code docs/rooms.md#autoplay}) with a
 * seeded random: refilling the autoplay section from the selections, an empty pool, playlists
 * edited and deselected while selected, taking turns, the order of requests and autoplay songs,
 * ↑ turning an autoplay entry into a request, removing by the selecting person, a lowered
 * {@code autoplay.upcoming}, and all of it surviving a reload.
 */
abstract class AutoplayContract {
    private static final long T0 = 1_800_000_000_000L;
    private static final Library.YoutubeCheck ANY = () -> {
    };

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private RoomRegistry registry;
    private RoomQueue queue;
    private Rooms rooms;
    private Playlists playlists;
    private Library library;
    private AutoplaySelections selections;
    private YoutubeImports youtube;
    private Permissions permissions;
    private Settings settings;
    private String roomId;
    private String aliceId;
    private String bobId;
    private String carolId;
    private Actor.User aliceActor;
    private Actor.User bobActor;
    private RoomActor owner;
    private RoomActor bob;
    private RoomActor carol;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        settings = new Settings(db, config);
        permissions = new Permissions(db, settings);
        rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        aliceActor = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bobActor = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        roomId = rooms.create(aliceActor, "Party", null, null, null, "203.0.113.9").id();
        rooms.setRole(aliceActor, roomId, bobId, Rooms.Role.MEMBER, "203.0.113.9");
        owner = RoomActor.user(aliceId, Rooms.Role.OWNER);
        bob = RoomActor.user(bobId, Rooms.Role.MEMBER);
        carol = RoomActor.user(carolId, null);
        Tools tools = new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString());
        youtube = TestUploads.youtube(db, config, tools, clock);
        library = new Library(db, youtube, clock);
        playlists = new Playlists(db, library, youtube, clock);
        // as Main wires it: playlist changes reach the rooms autoplaying them
        library.playlistsChangeListener(userId -> registry.playlistsChanged(userId));
        start();
    }

    /** A fresh registry (a server restart) with a seeded random. */
    private void start() {
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        registry.autoplayRandom(new Random(42));
        queue = new RoomQueue(registry, db, TestUploads.uploads(db, dir, configFor(), toolsFor(), clock), youtube,
                playlists, TestUploads.policy(permissions, settings), new Random(42));
        selections = new AutoplaySelections(rooms, playlists, registry);
    }

    private Config configFor() {
        try {
            return new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                    "https://tunes.example"), 4, dir).load(null).config();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Tools toolsFor() {
        return new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString());
    }

    private void restart() {
        registry.close();
        start();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A ready 60 s audio item. */
    private String song() throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30',"
                + " 1, 60000)", id, aliceId);
        return id;
    }

    /** A playlist of {@code user} with {@code n} new songs. */
    private String playlist(Actor.User user, String name, int n, List<String> songs) throws Exception {
        String id = playlists.create(user.userId(), name, null).id();
        for (int i = 0; i < n; i++) {
            String song = song();
            songs.add(song);
            playlists.add(user, id, song, null, ANY);
        }
        return id;
    }

    private void sql(String statement, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private List<String> column(String query, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                    return out;
                }
            }
        });
    }

    private void settings(String json) throws Exception {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", json, roomId);
        restart();
        call(tx -> null); // load the room: it fills (or trims) its autoplay section
    }

    private void mode(String mode) throws Exception {
        sql("UPDATE rooms SET control_mode = ? WHERE id = ?", mode, roomId);
        restart();
    }

    private <T> T call(RoomMutation<T> m) throws Exception {
        return registry.call(roomId, m);
    }

    private List<QueueEntry> order() throws Exception {
        return call(tx -> Playback.playOrder(tx.state()));
    }

    private List<QueueEntry> section(QueueEntry.Section section) throws Exception {
        return call(tx -> Playback.sectionOrder(tx.state(), section));
    }

    private QueueEntry current() throws Exception {
        return call(tx -> tx.state().current());
    }

    private static Set<String> items(List<QueueEntry> entries) {
        Set<String> out = new HashSet<>();
        for (QueueEntry e : entries) {
            out.add(e.itemId());
        }
        return out;
    }

    private interface Op {
        void run() throws Exception;
    }

    private String refused(Op op) {
        return assertThrows(HttpError.class, op::run).code();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void selectingFillsTheSectionAndPlayingRefillsIt() throws Exception {
        List<String> songs = new ArrayList<>();
        String list = playlist(aliceActor, "Mix", 8, songs);
        assertEquals(List.of(), order(), "nothing selected, nothing added");
        selections.select(aliceActor, roomId, list);

        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(5, auto.size(), "autoplay.upcoming defaults to 5");
        assertEquals(5, items(auto).size(), "no song twice");
        assertTrue(songs.containsAll(items(auto)));
        for (QueueEntry e : auto) {
            assertEquals(aliceId, e.addedByUserId(), "added for the person whose playlist it came from");
            assertEquals(QueueEntry.Section.AUTOPLAY, e.section());
        }
        assertNull(current(), "autoplay doesn't start an idle room");
        assertEquals(List.of("autoplay"), column("SELECT DISTINCT via FROM queue_entries WHERE room_id = ?", roomId));

        queue.play(roomId, owner);
        QueueEntry playing = current();
        assertEquals(auto.get(0).id(), playing.id(), "play starts the first one");
        assertEquals(5, section(QueueEntry.Section.AUTOPLAY).size(), "refilled");
        assertFalse(items(section(QueueEntry.Section.AUTOPLAY)).contains(playing.itemId()),
                "the song playing now has weight 0");

        // Many songs later: every song plays, and none comes back right after it played.
        List<String> played = new ArrayList<>();
        played.add(playing.itemId());
        for (int i = 0; i < 40; i++) {
            queue.next(roomId, owner);
            QueueEntry now = current();
            assertNotEquals(played.get(played.size() - 1), now.itemId(), "never the song that just played");
            played.add(now.itemId());
            assertEquals(5, section(QueueEntry.Section.AUTOPLAY).size());
        }
        assertEquals(Set.copyOf(songs), Set.copyOf(played), "every song comes round");

        List<String> before = section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::id).toList();
        String playingNow = current().id();
        restart();
        assertEquals(before, section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::id).toList(),
                "the section survives a restart");
        assertEquals(playingNow, current().id());
    }

    @Test
    void anEmptyPoolAddsNothingUntilThePlaylistGetsSongs() throws Exception {
        String list = playlists.create(aliceId, "Empty", null).id();
        selections.select(aliceActor, roomId, list);
        assertEquals(List.of(), order());
        String song = song();
        playlists.add(aliceActor, list, song, null, ANY);
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(1, auto.size(), "a playlist edited while selected: the new song comes in");
        assertEquals(song, auto.get(0).itemId());

        // Fewer songs than autoplay.upcoming: a small pool repeats once it played.
        queue.play(roomId, owner);
        assertEquals(song, current().itemId());
        assertEquals(List.of(song), section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::itemId).toList(),
                "the only song comes again (all weights 0: picked anyway)");

        // Items that aren't ready aren't picked.
        sql("UPDATE items SET status = 'processing' WHERE id = ?", song);
        selections.deselect(aliceActor, roomId, list);
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY), "deselected: its entries go");
        selections.select(aliceActor, roomId, list);
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY), "nothing ready to pick");
    }

    @Test
    void editingAndDeselectingPlaylistsUpdatesTheSection() throws Exception {
        List<String> aliceSongs = new ArrayList<>();
        String a = playlist(aliceActor, "A", 6, aliceSongs);
        settings("{\"autoplay\":{\"upcoming\":3,\"selectors\":\"members\"}}");
        selections.select(aliceActor, roomId, a);
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, auto.size());

        // A song removed from the playlist leaves the section; another takes its place.
        String gone = auto.get(1).itemId();
        String entry = column("SELECT id FROM playlist_entries WHERE playlist_id = ? AND item_id = ?", a, gone).get(0);
        assertTrue(playlists.remove(aliceId, a, entry));
        auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, auto.size());
        assertFalse(items(auto).contains(gone));
        assertEquals(List.of("removed"), column("SELECT state FROM queue_entries WHERE room_id = ? AND item_id = ?",
                roomId, gone));

        // A song removed from the library leaves the playlist, so the section too.
        String fromLibrary = auto.get(0).itemId();
        assertTrue(library.remove(aliceId, fromLibrary));
        assertFalse(items(section(QueueEntry.Section.AUTOPLAY)).contains(fromLibrary));
        assertEquals(3, section(QueueEntry.Section.AUTOPLAY).size());

        // Bob's playlist joins; deselecting Alice's leaves only Bob's songs.
        List<String> bobSongs = new ArrayList<>();
        String b = playlist(bobActor, "B", 4, bobSongs);
        selections.select(bobActor, roomId, b);
        selections.deselect(aliceActor, roomId, a);
        auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, auto.size());
        assertTrue(bobSongs.containsAll(items(auto)));
        for (QueueEntry e : auto) {
            assertEquals(bobId, e.addedByUserId());
        }

        // Bob loses the right to select (as RoomsApi does it): his selections and entries go.
        rooms.setRole(aliceActor, roomId, bobId, Rooms.Role.BANNED, "203.0.113.9");
        registry.autoplayChanged(roomId);
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY));

        // Deleting a selected playlist empties its part.
        selections.select(aliceActor, roomId, a);
        assertEquals(3, section(QueueEntry.Section.AUTOPLAY).size());
        assertTrue(playlists.delete(aliceId, a));
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY));
    }

    @Test
    void peopleTakeTurns() throws Exception {
        // Both select before autoplay adds anything (upcoming 0), then it fills 6.
        settings("{\"autoplay\":{\"upcoming\":0,\"mix\":\"turns\",\"selectors\":\"members\"}}");
        List<String> aliceSongs = new ArrayList<>();
        List<String> bobSongs = new ArrayList<>();
        String a = playlist(aliceActor, "A", 10, aliceSongs);
        String b = playlist(bobActor, "B", 10, bobSongs);
        selections.select(aliceActor, roomId, a);
        clock.advance(1);
        selections.select(bobActor, roomId, b);
        settings("{\"autoplay\":{\"upcoming\":6,\"mix\":\"turns\",\"selectors\":\"members\"}}");
        assertEquals(aliceId, section(QueueEntry.Section.AUTOPLAY).get(0).addedByUserId(),
                "the first to select has the first turn");
        List<String> users = section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::addedByUserId).toList();
        assertEquals(6, users.size());
        for (int i = 1; i < users.size(); i++) {
            assertNotEquals(users.get(i - 1), users.get(i), "turns alternate: " + users);
        }
        // The turns go on where they left off.
        queue.play(roomId, owner);
        queue.next(roomId, owner);
        List<String> after = section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::addedByUserId).toList();
        for (int i = 1; i < after.size(); i++) {
            assertNotEquals(after.get(i - 1), after.get(i), "still alternating: " + after);
        }
        for (QueueEntry e : section(QueueEntry.Section.AUTOPLAY)) {
            assertTrue((e.addedByUserId().equals(aliceId) ? aliceSongs : bobSongs).contains(e.itemId()),
                    "each turn picks from that person's playlists");
        }
    }

    @Test
    void requestsFirstOrAlternating() throws Exception {
        List<String> songs = new ArrayList<>();
        String list = playlist(aliceActor, "Mix", 8, songs);
        settings("{\"autoplay\":{\"upcoming\":3}}");
        selections.select(aliceActor, roomId, list);
        String r1 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        // the request started the idle room; two more wait
        assertEquals(r1, current().id());
        String r2 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        String r3 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        List<String> ids = order().stream().map(QueueEntry::id).toList();
        assertEquals(List.of(r2, r3, auto.get(0).id(), auto.get(1).id(), auto.get(2).id()), ids,
                "first: every request before the autoplay songs");

        settings("{\"autoplay\":{\"upcoming\":3,\"requestsFirst\":\"alternate\"}}");
        auto = section(QueueEntry.Section.AUTOPLAY);
        ids = order().stream().map(QueueEntry::id).toList();
        assertEquals(List.of(auto.get(0).id(), r2, auto.get(1).id(), r3, auto.get(2).id()), ids,
                "a request plays now, so an autoplay song comes next, then a request, ...");
        queue.next(roomId, owner);
        assertEquals(auto.get(0).id(), current().id());
        auto = section(QueueEntry.Section.AUTOPLAY);
        ids = order().stream().map(QueueEntry::id).toList();
        assertEquals(r2, ids.get(0), "after an autoplay song, a request");
        assertEquals(auto.get(0).id(), ids.get(1));
    }

    @Test
    void upTurnsAnAutoplayEntryIntoARequest() throws Exception {
        mode("owner");
        List<String> songs = new ArrayList<>();
        String list = playlist(aliceActor, "Mix", 8, songs);
        settings("{\"autoplay\":{\"upcoming\":3}}");
        selections.select(aliceActor, roomId, list);
        QueueEntry target = section(QueueEntry.Section.AUTOPLAY).get(2);

        // owner mode: a member's request is a suggestion
        assertEquals(QueueEntry.State.SUGGESTED, queue.request(roomId, bob, target.id()));
        QueueEntry suggested = call(tx -> tx.state().waitingEntry(target.id()));
        assertEquals(QueueEntry.State.SUGGESTED, suggested.state());
        assertEquals(QueueEntry.Section.MANUAL, suggested.section());
        assertEquals(bobId, suggested.addedByUserId(), "now Bob's request");
        assertEquals(3, section(QueueEntry.Section.AUTOPLAY).size(), "autoplay filled its section again");
        assertEquals(List.of("manual", bobId, "suggested"), List.of(
                column("SELECT section FROM queue_entries WHERE id = ?", target.id()).get(0),
                column("SELECT added_by_user_id FROM queue_entries WHERE id = ?", target.id()).get(0),
                column("SELECT state FROM queue_entries WHERE id = ?", target.id()).get(0)));
        assertEquals("not_autoplay", refused(() -> queue.request(roomId, bob, target.id())));
        assertEquals("entry_not_found", refused(() -> queue.request(roomId, bob, "nope")));
        assertEquals("identity_required", refused(() -> queue.request(roomId, RoomActor.ANONYMOUS,
                section(QueueEntry.Section.AUTOPLAY).get(0).id())));

        // vote mode: it joins the requests with the voter's vote; votes and pins need a request first
        sql("UPDATE rooms SET control_mode = 'vote' WHERE id = ?", roomId);
        restart();
        QueueEntry next = section(QueueEntry.Section.AUTOPLAY).get(0);
        assertEquals("autoplay_entry", refused(() -> queue.vote(roomId, bob, next.id(), 1)));
        assertEquals("autoplay_entry", refused(() -> queue.pin(roomId, owner, next.id(), true)));
        assertEquals(QueueEntry.State.QUEUED, queue.request(roomId, bob, next.id()));
        assertEquals(1, (int) call(tx -> tx.state().voteTotal(next.id())));
        assertEquals(next.id(), section(QueueEntry.Section.MANUAL).get(0).id());
        assertEquals(next.id(), order().get(0).id(), "requests play first");

        // the request limit counts it, but not autoplay entries
        settings("{\"autoplay\":{\"upcoming\":3},\"requestsPerActor\":1}");
        assertEquals("request_limit", refused(() -> queue.request(roomId, bob,
                section(QueueEntry.Section.AUTOPLAY).get(0).id())));
    }

    @Test
    void theSelectingPersonRemovesTheirAutoplayEntries() throws Exception {
        settings("{\"autoplay\":{\"upcoming\":3,\"selectors\":\"members\"}}");
        List<String> songs = new ArrayList<>();
        String b = playlist(bobActor, "B", 6, songs);
        selections.select(bobActor, roomId, b);
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, auto.size());
        assertEquals("forbidden", refused(() -> queue.remove(roomId, carol, auto.get(0).id())));
        queue.remove(roomId, bob, auto.get(0).id());
        assertEquals("removed", column("SELECT state FROM queue_entries WHERE id = ?", auto.get(0).id()).get(0));
        List<QueueEntry> after = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, after.size(), "and autoplay adds another");
        assertFalse(after.stream().anyMatch(e -> e.id().equals(auto.get(0).id())));

        // Bob's autoplay entries aren't his requests: he may still request up to the limit.
        settings("{\"autoplay\":{\"upcoming\":3,\"selectors\":\"members\"},\"requestsPerActor\":1}");
        sql("UPDATE rooms SET control_mode = 'open' WHERE id = ?", roomId);
        restart();
        queue.add(roomId, bobActor, bob, RoomQueue.Source.item(song()));
    }

    @Test
    void movesWithinASectionAndALoweredUpcomingTrims() throws Exception {
        mode("owner");
        List<String> songs = new ArrayList<>();
        String list = playlist(aliceActor, "Mix", 8, songs);
        selections.select(aliceActor, roomId, list);
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(5, auto.size());
        String m1 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        // an idle room: the request started; add another to have a manual entry waiting
        String m2 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        assertEquals(m1, current().id());

        assertTrue(queue.move(roomId, owner, auto.get(4).id(), auto.get(0).id()));
        List<String> moved = section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::id).toList();
        assertEquals(List.of(auto.get(4).id(), auto.get(0).id(), auto.get(1).id(), auto.get(2).id(),
                auto.get(3).id()), moved);
        assertTrue(queue.move(roomId, owner, auto.get(1).id(), null));
        assertEquals(auto.get(1).id(), section(QueueEntry.Section.AUTOPLAY).get(4).id(), "to the end of its section");
        assertEquals("before_entry_not_found", refused(() -> queue.move(roomId, owner, m2, UuidV7.newId())));
        assertEquals("before_entry_not_found", refused(() -> queue.move(roomId, owner, List.of(m2), auto.get(0).id(),
                QueueEntry.Section.MANUAL)), "the entry to move before isn't in the section asked for");
        assertEquals(m2, order().get(0).id());

        settings("{\"autoplay\":{\"upcoming\":2}}");
        List<String> trimmed = section(QueueEntry.Section.AUTOPLAY).stream().map(QueueEntry::id).toList();
        assertEquals(List.of(auto.get(4).id(), auto.get(0).id()), trimmed, "the ones at the end go");

        settings("{\"autoplay\":{\"upcoming\":0}}");
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY), "0: autoplay adds nothing");
        assertEquals(List.of(m2), order().stream().map(QueueEntry::id).toList());
    }

    private static List<String> ids(List<QueueEntry> entries) {
        return entries.stream().map(QueueEntry::id).toList();
    }

    @Test
    void movingBetweenSectionsInOwnerMode() throws Exception {
        mode("owner");
        settings("{\"autoplay\":{\"upcoming\":3}}");
        List<String> songs = new ArrayList<>();
        String list = playlist(aliceActor, "Mix", 10, songs);
        String playing = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        assertEquals(playing, current().id());
        String m1 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        String m2 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        String m3 = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
        selections.select(aliceActor, roomId, list);
        List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, auto.size());

        // only the owner and moderators edit the queue in owner mode
        assertEquals("forbidden", refused(() -> queue.move(roomId, bob, List.of(m3), m1, null)));
        assertEquals("identity_required", refused(() -> queue.move(roomId, RoomActor.ANONYMOUS, List.of(m3), m1,
                null)));
        assertEquals("entry_not_found", refused(() -> queue.move(roomId, owner, List.of(playing), m1, null)),
                "the current entry doesn't move");
        assertEquals("invalid_entry_ids", refused(() -> queue.move(roomId, owner, List.of(m1, m1), null, null)));
        assertEquals("invalid_entry_ids", refused(() -> queue.move(roomId, owner, List.of(), null, null)));

        // several entries at once, in the given order, before one of them = before the next that stays
        assertTrue(queue.move(roomId, owner, List.of(m3, m2), m1, null));
        assertEquals(List.of(m3, m2, m1), ids(section(QueueEntry.Section.MANUAL)));
        assertTrue(queue.move(roomId, owner, List.of(m1, m3), m3, null));
        assertEquals(List.of(m1, m3, m2), ids(section(QueueEntry.Section.MANUAL)));
        assertFalse(queue.move(roomId, owner, List.of(m1, m3), m2, null), "already there");

        // an autoplay entry into the requests: a manual entry now, still the selecting person's
        String a0 = auto.get(0).id();
        assertTrue(queue.move(roomId, owner, List.of(a0), m3, null));
        assertEquals(List.of(m1, a0, m3, m2), ids(section(QueueEntry.Section.MANUAL)));
        QueueEntry nowManual = call(tx -> tx.state().waitingEntry(a0));
        assertEquals(QueueEntry.Section.MANUAL, nowManual.section());
        assertEquals(aliceId, nowManual.addedByUserId());
        assertEquals(List.of("manual", "autoplay"), column("SELECT section FROM queue_entries WHERE id = ?"
                + " UNION ALL SELECT via FROM queue_entries WHERE id = ?", a0, a0));
        assertEquals(3, section(QueueEntry.Section.AUTOPLAY).size(), "autoplay filled its section again");

        // a request into From Playlist (to the end of the section): it stays there for good
        assertTrue(queue.move(roomId, owner, List.of(m2), null, QueueEntry.Section.AUTOPLAY));
        List<QueueEntry> fromPlaylist = section(QueueEntry.Section.AUTOPLAY);
        assertEquals(3, fromPlaylist.size(), "it counts: the last autoplay song made room");
        assertEquals(m2, fromPlaylist.get(2).id(), "not trimmed although it is last");
        assertEquals("autoplay", column("SELECT section FROM queue_entries WHERE id = ?", m2).get(0));
        // and before an entry of the other section
        assertTrue(queue.move(roomId, owner, List.of(m1), fromPlaylist.get(0).id(), null));
        assertEquals(m1, section(QueueEntry.Section.AUTOPLAY).get(0).id());
        assertEquals(List.of(a0, m3), ids(section(QueueEntry.Section.MANUAL)));
        assertEquals(List.of(m1, fromPlaylist.get(0).id(), m2), ids(section(QueueEntry.Section.AUTOPLAY)),
                "over upcoming only by moved-in entries: autoplay songs make room");

        // survives a restart; deselecting removes autoplay's songs, not the moved-in ones
        restart();
        assertEquals(List.of(m1, fromPlaylist.get(0).id(), m2), ids(section(QueueEntry.Section.AUTOPLAY)));
        selections.deselect(aliceActor, roomId, list);
        assertEquals(List.of(m1, m2), ids(section(QueueEntry.Section.AUTOPLAY)));
        assertEquals(List.of(a0, m3, m1, m2), ids(order()), "requests first, then From Playlist");
        // and back into the requests
        assertTrue(queue.move(roomId, owner, List.of(m2, m1), a0, QueueEntry.Section.MANUAL));
        assertEquals(List.of(m2, m1, a0, m3), ids(section(QueueEntry.Section.MANUAL)));
        assertEquals(List.of(), section(QueueEntry.Section.AUTOPLAY));
    }

    @Test
    void queueEditingPerControlMode() throws Exception {
        settings("{\"autoplay\":{\"upcoming\":2,\"selectors\":\"members\"}}");
        List<String> songs = new ArrayList<>();
        String list = playlist(bobActor, "B", 8, songs);
        RoomActor banned = RoomActor.user(carolId, Rooms.Role.BANNED);
        for (String m : List.of("owner", "vote", "open")) {
            mode(m);
            call(tx -> null);
            if (current() == null) {
                queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song()));
            }
            String byAlice = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(song())).entryId();
            String byBob = queue.add(roomId, bobActor, bob, RoomQueue.Source.item(song())).entryId();
            if (m.equals("owner")) {
                queue.accept(roomId, owner, byBob); // a suggestion first
            }
            selections.select(bobActor, roomId, list);
            List<QueueEntry> auto = section(QueueEntry.Section.AUTOPLAY);
            assertEquals(2, auto.size(), m);

            // moves (and moves between sections): owner mode the owner and moderators, open mode
            // anyone with request rights, never in vote mode
            switch (m) {
                case "owner" -> {
                    assertTrue(queue.move(roomId, owner, List.of(byBob), byAlice, null));
                    assertEquals("forbidden", refused(() -> queue.move(roomId, bob, List.of(byAlice), byBob, null)));
                    assertEquals("forbidden", refused(() -> queue.move(roomId, bob, List.of(auto.get(1).id()),
                            byBob, null)));
                }
                case "vote" -> {
                    for (RoomActor a : List.of(owner, bob)) {
                        assertEquals("wrong_control_mode", refused(() -> queue.move(roomId, a, List.of(byBob), byAlice,
                                null)), m);
                        assertEquals("wrong_control_mode", refused(() -> queue.move(roomId, a,
                                List.of(auto.get(0).id()), null, QueueEntry.Section.MANUAL)), m);
                    }
                    // pin to top stays (requests only)
                    assertTrue(queue.pin(roomId, owner, byBob, true));
                    assertEquals(byBob, order().get(0).id());
                    assertEquals("forbidden", refused(() -> queue.pin(roomId, bob, byAlice, true)));
                    assertEquals("autoplay_entry", refused(() -> queue.pin(roomId, owner, auto.get(0).id(), true)));
                }
                case "open" -> {
                    assertTrue(queue.move(roomId, bob, List.of(auto.get(1).id(), byAlice), null,
                            QueueEntry.Section.MANUAL), "a member with request rights");
                    assertEquals(QueueEntry.Section.MANUAL, call(tx -> tx.state().waitingEntry(auto.get(1).id()))
                            .section());
                    assertTrue(queue.move(roomId, bob, List.of(byBob), auto.get(0).id(), null));
                    assertEquals(byBob, section(QueueEntry.Section.AUTOPLAY).get(0).id());
                    assertEquals("cannot_request", refused(() -> queue.move(roomId, banned, List.of(byBob), null,
                            null)));
                }
                default -> throw new AssertionError(m);
            }

            // × : the owner, a moderator or the adder (for autoplay entries the selecting person);
            // in open mode anyone with request rights
            List<QueueEntry> fromPlaylist = section(QueueEntry.Section.AUTOPLAY);
            QueueEntry autoEntry = fromPlaylist.stream().filter(e -> e.itemId() != null && songs.contains(e.itemId()))
                    .findFirst().orElseThrow();
            if (m.equals("open")) {
                queue.remove(roomId, carol, byAlice); // anyone with request rights
                assertEquals("forbidden", refused(() -> queue.remove(roomId, banned, autoEntry.id())));
            } else {
                assertEquals("forbidden", refused(() -> queue.remove(roomId, carol, byAlice)), m);
                assertEquals("forbidden", refused(() -> queue.remove(roomId, bob, byAlice)), m);
                queue.remove(roomId, owner, byAlice);
            }
            assertEquals("removed", column("SELECT state FROM queue_entries WHERE id = ?", byAlice).get(0), m);
            queue.remove(roomId, bob, autoEntry.id()); // Bob selected it
            queue.remove(roomId, bob, byBob); // his own request
            assertEquals("removed", column("SELECT state FROM queue_entries WHERE id = ?", autoEntry.id()).get(0));
            assertEquals("removed", column("SELECT state FROM queue_entries WHERE id = ?", byBob).get(0));
            selections.deselect(bobActor, roomId, list);
            for (QueueEntry e : call(tx -> List.copyOf(tx.state().queue()))) {
                queue.remove(roomId, owner, e.id());
            }
        }
    }
}
