package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
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
import io.siggi.togethertunes.library.Uploads;
import io.siggi.togethertunes.library.YoutubeImports;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room's history on a real database ({@link RoomHistory}): how entries record their source and
 * the adder's name at that time, the listing (everyone who may open the room, newest first, pages,
 * purged items, per-viewer library and no-repeat flags), adding an entry's item again
 * ({@code historyEntryId}: owner and moderators always, others by the request rules but without the
 * request scope), trimming after {@code rooms.requestHistoryDays}, and migration 0011 (the request
 * history's rows carried over to their entries).
 */
abstract class RoomHistoryContract {
    private static final long T0 = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private Settings settings;
    private Permissions permissions;
    private Rooms rooms;
    private RoomRegistry registry;
    private RoomQueue queue;
    private RoomHistory history;
    private Library library;
    private YoutubeImports youtube;
    private Playlists playlists;
    private Uploads uploads;
    private String roomId;
    private String otherRoomId;
    private String aliceId;
    private String bobId;
    private String carolId;
    private String guestId;
    private Actor.User aliceActor;
    private Actor.User bobActor;
    private Actor.User carolActor;
    private Actor.Guest guestActor;
    private RoomActor owner;
    private RoomActor bob;
    private RoomActor carol; // a moderator
    private RoomActor guest;
    private final List<RoomEvent> events = new CopyOnWriteArrayList<>();

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
        guestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', ?, 'Quiet Walrus', 1, 1)", guestId, "hash-" + guestId);
        aliceActor = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bobActor = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        carolActor = new Actor.User(carolId, "s-carol", Actor.Via.BEARER);
        guestActor = new Actor.Guest(guestId, GuestIdentity.COOKIE, false);
        roomId = rooms.create(aliceActor, "Party", null, null, null, "203.0.113.9").id();
        otherRoomId = rooms.create(aliceActor, "Other", null, null, null, "203.0.113.9").id();
        rooms.setRole(aliceActor, roomId, carolId, Rooms.Role.MODERATOR, "203.0.113.9");
        sql("UPDATE rooms SET settings_json = ?", "{\"requestsPerActor\":100,\"noRepeatWindow\":0}");
        owner = RoomActor.user(aliceId, Rooms.Role.OWNER);
        bob = RoomActor.user(bobId, Rooms.Role.MEMBER);
        carol = RoomActor.user(carolId, Rooms.Role.MODERATOR);
        guest = RoomActor.guest(guestId, true, true);
        Tools tools = new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString());
        youtube = TestUploads.youtube(db, config, tools, clock);
        playlists = new Playlists(db, youtube, clock);
        uploads = TestUploads.uploads(db, dir, config, tools, clock);
        library = new Library(db, youtube, clock);
        history = new RoomHistory(db, rooms, settings::roomsRequestHistoryDays, clock);
        reload();
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

    private void reload() throws SQLException {
        if (registry != null) {
            registry.close();
        }
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        registry.executor(roomId).addListener((state, change) -> events.addAll(change.events()));
        queue = new RoomQueue(registry, db, uploads, youtube, playlists, TestUploads.policy(permissions, settings),
                new Random(42));
    }

    /** A 60 s item with this status (an upload, or a YouTube item with {@code youtubeId}), in the owner's library. */
    private String item(String status, String youtubeId) throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, youtube_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', ?, ?, ?, 1, ?,"
                + " '720p30', 1, 60000)", id, youtubeId == null ? "upload" : "youtube", youtubeId, aliceId, status);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')", aliceId, id);
        return id;
    }

    private String ready() throws SQLException {
        return item("ready", null);
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

    private List<Object> row(String query, Object... args) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Object> out = new ArrayList<>();
                    while (rs.next()) {
                        for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                            Object v = rs.getObject(col);
                            out.add(v instanceof Number n ? (Object) n.longValue() : v);
                        }
                    }
                    return out;
                }
            }
        });
    }

    private Actor actorOf(RoomActor a) {
        if (a == guest) {
            return guestActor;
        }
        return a == owner ? aliceActor : a == bob ? bobActor : carolActor;
    }

    private QueueOps.Added add(RoomActor a, String itemId) throws Exception {
        return queue.add(roomId, actorOf(a), a, RoomQueue.Source.item(itemId));
    }

    private static String refused(Executable e) {
        return assertThrows(HttpError.class, e).code();
    }

    private RoomHistory.Page all(Actor viewer) throws SQLException {
        return history.list(viewer, roomId, null, RoomHistory.LIST_MAX);
    }

    /** Skips the current entry a second later (it ends as {@code skipped}). */
    private void skip() throws Exception {
        clock.advance(1000);
        registry.call(roomId, tx -> QueueOps.next(tx, owner));
    }

    private void settingsJson(String json) throws Exception {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", json, roomId);
        reload();
    }

    private List<String> entryIds(RoomHistory.Page page) {
        return page.items().stream().map(RoomHistory.Row::entryId).toList();
    }

    private void mode(String mode) throws Exception {
        sql("UPDATE rooms SET control_mode = ? WHERE id = ?", mode, roomId);
        reload();
    }

    // ------------------------------------------------------------------ entries

    @Test
    void entriesRecordHowTheyWereAddedAndTheAddersName() throws Exception {
        QueueOps.Added byBob = add(bob, ready());
        assertEquals(List.of("library", "Bob"), row("SELECT via, added_by_name FROM queue_entries WHERE id = ?",
                byBob.entryId()));
        RoomEvent.RequestAdded event = events.stream().filter(e -> e instanceof RoomEvent.RequestAdded)
                .map(e -> (RoomEvent.RequestAdded) e).findFirst().orElseThrow();
        assertEquals(byBob.entryId(), event.entryId());
        assertEquals("library", event.via());

        QueueOps.Added byGuest = add(guest, ready());
        assertEquals(List.of("library", "Quiet Walrus"), row("SELECT via, added_by_name FROM queue_entries"
                + " WHERE id = ?", byGuest.entryId()));
        // the owner's own adds are recorded, but are no requests
        events.clear();
        QueueOps.Added byOwner = add(owner, ready());
        assertEquals(List.of("library", "Alice"), row("SELECT via, added_by_name FROM queue_entries WHERE id = ?",
                byOwner.entryId()));
        assertTrue(events.stream().noneMatch(e -> e instanceof RoomEvent.RequestAdded), events.toString());

        String yt = item("ready", "dQw4w9WgXcQ");
        QueueOps.Added link = queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(List.of("youtube", yt), row("SELECT via, item_id FROM queue_entries WHERE id = ?",
                link.entryId()));
        String uploaded = ready();
        String uploadId = UuidV7.newId();
        sql("INSERT INTO uploads (id, user_id, filename, size, created_at, updated_at, item_id)"
                + " VALUES (?, ?, 'a.mp3', 10, 1, 1, ?)", uploadId, bobId, uploaded);
        QueueOps.Added upload = queue.add(roomId, bobActor, bob, RoomQueue.Source.upload(uploadId));
        assertEquals(List.of("upload"), row("SELECT via FROM queue_entries WHERE id = ?", upload.entryId()));

        String playlistId = playlists.create(carolId, "Mix", null).id();
        playlists.add(carolActor, playlistId, ready(), null, () -> {
        });
        String fromPlaylist = queue.addPlaylist(roomId, carolActor, carol, playlistId, false).entryIds().get(0);
        assertEquals(List.of("playlist", "Carol"), row("SELECT via, added_by_name FROM queue_entries WHERE id = ?",
                fromPlaylist));

        // QueueOps.add without a source records none.
        QueueOps.Added plain = registry.call(roomId, tx -> QueueOps.add(tx, bob, ready()));
        List<Object> none = row("SELECT via FROM queue_entries WHERE id = ?", plain.entryId());
        assertEquals(1, none.size());
        assertNull(none.get(0));
    }

    // ------------------------------------------------------------------ reading

    @Test
    void everyoneInTheRoomSeesTheHistoryNewestFirst() throws Exception {
        String a = ready();
        String yt = item("ready", "dQw4w9WgXcQ");
        QueueOps.Added first = add(bob, a); // starts playing at T0
        QueueOps.Added second = add(guest, ready());
        QueueOps.Added third = queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"));
        QueueOps.Added fourth = add(owner, ready());
        skip(); // first skipped, second plays at T0 + 1000
        skip(); // third at T0 + 2000
        skip(); // fourth at T0 + 3000, current: not in the history yet

        RoomHistory.Page page = all(aliceActor);
        assertNull(page.nextCursor());
        assertEquals(List.of(third.entryId(), second.entryId(), first.entryId()), entryIds(page));
        RoomHistory.Row newest = page.items().get(0);
        assertEquals("skipped", newest.state());
        assertEquals(T0 + 2000, newest.playedAt());
        assertEquals(yt, newest.item().id());
        assertEquals("dQw4w9WgXcQ", newest.item().youtubeId());
        assertEquals(ActorRef.user(bobId, "Bob"), newest.addedBy());
        assertEquals("youtube", newest.via());
        assertEquals(Boolean.TRUE, newest.inYourLibrary(), "alice's library");
        assertEquals(ActorRef.guest(guestId, "Quiet Walrus"), page.items().get(1).addedBy());
        assertEquals("library", page.items().get(1).via());
        assertEquals(a, page.items().get(2).item().id());
        assertEquals(T0, page.items().get(2).playedAt());
        assertEquals(0, page.noRepeatWindow());

        // Everyone who may open the room sees it, each with their own library flag.
        assertEquals(Boolean.FALSE, all(bobActor).items().get(0).inYourLibrary());
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'manual')", bobId, yt);
        assertEquals(Boolean.TRUE, all(bobActor).items().get(0).inYourLibrary());
        assertEquals(Boolean.FALSE, all(bobActor).items().get(1).inYourLibrary());
        assertNull(all(guestActor).items().get(0).inYourLibrary(), "guests have no library");
        assertNull(all(Actor.ANONYMOUS).items().get(0).inYourLibrary());
        assertEquals(3, all(carolActor).items().size());
        assertEquals("room_not_found", refused(() -> history.list(aliceActor, UuidV7.newId(), null, 10)));
        assertEquals(0, history.list(aliceActor, otherRoomId, null, 10).items().size());
        sql("UPDATE rooms SET visibility = 'private' WHERE id = ?", roomId);
        assertEquals("room_not_found", refused(() -> all(Actor.ANONYMOUS)));
        assertEquals(3, all(carolActor).items().size(), "a member still sees it");
        sql("UPDATE rooms SET visibility = 'public' WHERE id = ?", roomId);

        // Pages.
        RoomHistory.Page p1 = history.list(aliceActor, roomId, null, 2);
        assertEquals(List.of(third.entryId(), second.entryId()), entryIds(p1));
        RoomHistory.Page p2 = history.list(aliceActor, roomId, p1.nextCursor(), 2);
        assertEquals(List.of(first.entryId()), entryIds(p2));
        assertNull(p2.nextCursor());
        assertEquals("invalid_cursor", refused(() -> history.list(aliceActor, roomId, "x", 10)));
        assertEquals("invalid_cursor", refused(() -> history.list(aliceActor, roomId, "12_x", 10)));
        assertEquals("invalid_cursor", refused(() -> history.list(aliceActor, roomId, "x_" + UuidV7.newId(), 10)));

        // Purged items stay listed; the name is the one the adder had then.
        sql("UPDATE items SET status = 'purged' WHERE id = ?", a);
        sql("UPDATE users SET display_name = 'Robert' WHERE id = ?", bobId);
        page = all(aliceActor);
        assertEquals("purged", page.items().get(2).item().status());
        assertEquals("Bob", page.items().get(0).addedBy().name());
        // An entry made before names were kept shows the current name.
        sql("UPDATE queue_entries SET added_by_name = NULL, via = NULL WHERE id = ?", third.entryId());
        RoomHistory.Row old = all(aliceActor).items().get(0);
        assertEquals("Robert", old.addedBy().name());
        assertNull(old.via());
        assertEquals(fourth.entryId(), row("SELECT current_entry_id FROM rooms WHERE id = ?", roomId).get(0));
    }

    @Test
    void recentlyPlayedFollowsTheNoRepeatWindow() throws Exception {
        settingsJson("{\"requestsPerActor\":100,\"noRepeatWindow\":2}");
        String a = ready();
        String b = ready();
        String c = ready();
        add(owner, a);
        add(owner, b);
        add(owner, c);
        skip();
        skip(); // c plays; the last 2 entries that started are c and b
        RoomHistory.Page page = all(bobActor);
        assertEquals(2, page.noRepeatWindow());
        assertEquals(List.of(b, a), page.items().stream().map(r -> r.item().id()).toList());
        assertTrue(page.items().get(0).recentlyPlayed());
        assertFalse(page.items().get(1).recentlyPlayed());
        settingsJson("{\"requestsPerActor\":100,\"noRepeatWindow\":0}");
        assertFalse(all(bobActor).items().get(0).recentlyPlayed());
    }

    // ------------------------------------------------------------------ add to queue

    @Test
    void anEntryOfTheHistoryIsAddedAgain() throws Exception {
        settingsJson("{\"requestsPerActor\":100,\"noRepeatWindow\":2,\"requestScope\":\"none\"}");
        String a = ready();
        String b = ready();
        String c = ready();
        QueueOps.Added ea = add(owner, a);
        QueueOps.Added eb = add(owner, b);
        QueueOps.Added ec = add(owner, c);
        skip();
        skip(); // c current; b recent
        // Library requests by id are off for bob (requestScope none), but the history works.
        assertEquals("library_requests_disabled", refused(() -> add(bob, a)));
        events.clear();
        QueueOps.Added again = queue.add(roomId, bobActor, bob, RoomQueue.Source.history(ea.entryId()));
        assertEquals(a, again.itemId());
        assertEquals(QueueEntry.State.QUEUED, again.state());
        assertEquals(List.of("history", "Bob"), row("SELECT via, added_by_name FROM queue_entries WHERE id = ?",
                again.entryId()));
        assertTrue(events.stream().anyMatch(e -> e instanceof RoomEvent.RequestAdded r && r.via().equals("history")));
        // noRepeatWindow: refused for others, not for the owner and moderators
        assertEquals("recently_played", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.history(eb.entryId()))));
        assertEquals("recently_played", refused(() -> queue.add(roomId, guestActor, guest,
                RoomQueue.Source.history(eb.entryId()))));
        assertEquals(b, queue.add(roomId, carolActor, carol, RoomQueue.Source.history(eb.entryId())).itemId());
        assertEquals(b, queue.add(roomId, aliceActor, owner, RoomQueue.Source.history(eb.entryId())).itemId());
        // the current entry has started, so it can be added again too (by those exempt from the window)
        assertEquals(c, queue.add(roomId, aliceActor, owner, RoomQueue.Source.history(ec.entryId())).itemId());
        // guests need guests.canRequestLibrary
        RoomActor noLibrary = new RoomActor(null, guestId, null, false, true, true, true);
        assertEquals("guest_library_requests_disabled", refused(() -> queue.add(roomId, guestActor, noLibrary,
                RoomQueue.Source.history(ea.entryId()))));
        // owner mode: a request becomes a suggestion
        mode("owner");
        assertEquals(QueueEntry.State.SUGGESTED, queue.add(roomId, guestActor, guest,
                RoomQueue.Source.history(ea.entryId())).state());
        // not a started entry of this room
        assertEquals("history_entry_not_found", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.history(UuidV7.newId()))));
        assertEquals("history_entry_not_found", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.history(again.entryId()))), "queued, never played");
        assertEquals("history_entry_not_found", refused(() -> queue.add(otherRoomId, aliceActor, owner,
                RoomQueue.Source.history(ea.entryId()))));
        assertEquals("invalid_source", refused(() -> queue.add(roomId, bobActor, bob,
                new RoomQueue.Source(a, null, null, ea.entryId()))));
        // an expired upload can't be added; a purged YouTube item is fetched again
        sql("UPDATE items SET status = 'purged' WHERE id = ?", a);
        assertEquals("media_expired", refused(() -> queue.add(roomId, aliceActor, owner,
                RoomQueue.Source.history(ea.entryId()))));
        String yt = item("ready", "dQw4w9WgXcQ");
        mode("vote");
        QueueOps.Added link = queue.add(roomId, aliceActor, owner, RoomQueue.Source.item(yt));
        while (!yt.equals(row("SELECT q.item_id FROM rooms r JOIN queue_entries q ON q.id = r.current_entry_id"
                + " WHERE r.id = ?", roomId).get(0))) {
            skip();
        }
        skip();
        sql("UPDATE items SET status = 'purged' WHERE id = ?", yt);
        settingsJson("{\"requestsPerActor\":100,\"noRepeatWindow\":0}");
        QueueOps.Added refetched = queue.add(roomId, bobActor, bob, RoomQueue.Source.history(link.entryId()));
        assertEquals(yt, refetched.itemId());
        assertEquals(List.of("pending"), row("SELECT status FROM items WHERE id = ?", yt));
    }

    // ------------------------------------------------------------------ trimming

    @Test
    void historyIsTrimmedAfterRequestHistoryDays() throws Exception {
        assertEquals(90, settings.roomsRequestHistoryDays());
        QueueOps.Added old = add(owner, ready()); // plays at T0
        QueueOps.Added recent = add(owner, ready());
        QueueOps.Added current = add(owner, ready());
        clock.advance(DAY);
        registry.call(roomId, tx -> QueueOps.next(tx, owner)); // recent plays at T0 + DAY
        skip(); // current plays
        settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, 1);
        clock.advance(1);
        // Past the period: no longer listed, even before the trim runs.
        assertEquals(List.of(recent.entryId()), entryIds(all(aliceActor)));
        assertEquals(1, history.trim());
        assertEquals(List.of(), row("SELECT id FROM queue_entries WHERE id = ?", old.entryId()));
        assertEquals(0, history.trim());
        // The current entry is never trimmed, however long it has been playing.
        clock.advance(10 * DAY);
        assertEquals(1, history.trim());
        assertEquals(List.of(current.entryId()), row("SELECT id FROM queue_entries WHERE room_id = ?", roomId));

        // 0 keeps everything.
        settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, 0);
        skip();
        clock.advance(1000 * DAY);
        assertEquals(0, history.trim());
        assertEquals(1, all(aliceActor).items().size());
        assertEquals("rooms.requestHistoryDays: must be between 0 and 36500, got -1",
                assertThrows(IllegalArgumentException.class,
                        () -> settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, -1)).getMessage());
    }

    // ------------------------------------------------------------------ migration

    @Test
    void migration0011CarriesRequestsOverToTheirEntries() throws Exception {
        registry.close();
        registry = null;
        db.close();
        java.nio.file.Files.deleteIfExists(dir.resolve("test.db")); // SQLite: a fresh file (PostgreSQL: a fresh schema)
        db = open();
        List<io.siggi.togethertunes.db.Migration> all = MigrationRunner.loadClasspath(getClass().getClassLoader(),
                MigrationRunner.CLASSPATH_ROOT, db.dialect());
        new MigrationRunner(db, all.stream().filter(m -> m.version() <= 10).toList()).migrate();
        sql("INSERT INTO users (id, username, display_name, created_at) VALUES ('u1', 'u1', 'Now', 1)");
        sql("INSERT INTO rooms (id, slug, name, owner_id, created_at, visibility, control_mode, settings_json,"
                + " playback_state, anchor_server_ms, anchor_position_ms, version)"
                + " VALUES ('r1', 'r1', 'R', 'u1', 1, 'public', 'vote', '{}', 'idle', 0, 0, 0)");
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES ('i1', 'audio', 't', 'upload', 'u1', 1, 'ready', '720p30', 1)");
        for (String q : List.of("q1", "q2")) {
            sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position,"
                    + " played_at) VALUES (?, 'r1', 'i1', 'u1', 1, 'played', 1, 5)", q);
        }
        sql("INSERT INTO room_requests (id, room_id, item_id, by_user_id, by_name, via, at, entry_id)"
                + " VALUES ('rq1', 'r1', 'i1', 'u1', 'Then', 'youtube', 1, 'q1')");
        assertFalse(MigrationRunner.migrate(db).isEmpty());
        assertEquals(List.of("youtube", "Then"), row("SELECT via, added_by_name FROM queue_entries WHERE id = 'q1'"));
        List<Object> q2 = row("SELECT via, added_by_name FROM queue_entries WHERE id = 'q2'");
        assertNull(q2.get(0));
        assertNull(q2.get(1));
        assertThrows(SQLException.class, () -> row("SELECT COUNT(*) FROM room_requests"));
    }
}
