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
 * The request history on a real database ({@link RoomRequests}): rows written with the queue
 * entry (who counts as a requester, the three sources, the name at request time), the listing
 * (owner and moderators only, source and requester filters, pages, purged items, gone
 * requesters), add-to-library (owner only, per-request results, YouTube refetch, expired
 * uploads) and trimming after {@code rooms.requestHistoryDays}.
 */
abstract class RoomRequestsContract {
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
    private RoomRequests requests;
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
        requests = new RoomRequests(db, rooms, library, TestUploads.policy(permissions, settings),
                settings::roomsRequestHistoryDays, clock);
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

    private long count() throws SQLException {
        return (Long) row("SELECT COUNT(*) FROM room_requests").get(0);
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

    private RoomRequests.Page all(Actor viewer) throws SQLException {
        return requests.list(viewer, roomId, null, null, null, RoomRequests.LIST_MAX);
    }

    private void mode(String mode) throws Exception {
        sql("UPDATE rooms SET control_mode = ? WHERE id = ?", mode, roomId);
        reload();
    }

    // ------------------------------------------------------------------ writing

    @Test
    void requestsAreWrittenWithTheirEntry() throws Exception {
        String song = ready();
        QueueOps.Added byBob = add(bob, song);
        List<Object> r = row("SELECT room_id, item_id, by_user_id, by_guest_id, by_name, via, at, entry_id,"
                + " added_to_owner_library FROM room_requests");
        assertEquals(List.of(roomId, song, bobId), r.subList(0, 3));
        assertNull(r.get(3));
        assertEquals(List.of("Bob", "library", T0, byBob.entryId(), 0L), r.subList(4, 9));
        RoomEvent.RequestAdded event = events.stream().filter(e -> e instanceof RoomEvent.RequestAdded)
                .map(e -> (RoomEvent.RequestAdded) e).findFirst().orElseThrow();
        assertEquals(byBob.entryId(), event.entryId());
        assertEquals("library", event.via());
        assertEquals(List.of(event.requestId()), row("SELECT id FROM room_requests"));

        // A guest; the owner never makes requests in their own room.
        QueueOps.Added byGuest = add(guest, ready());
        add(owner, ready());
        assertEquals(2, count());
        assertEquals(List.of("Quiet Walrus"), row("SELECT by_name FROM room_requests WHERE by_guest_id = ?", guestId));
        assertEquals(List.of(byGuest.entryId()), row("SELECT entry_id FROM room_requests WHERE by_guest_id = ?",
                guestId));

        // Moderators: requests in vote and open mode, not in owner mode (they add directly there).
        add(carol, ready());
        assertEquals(3, count());
        mode("open");
        add(carol, ready());
        assertEquals(4, count());
        mode("owner");
        add(carol, ready());
        assertEquals(4, count());
        QueueOps.Added suggestion = add(bob, ready());
        assertEquals(QueueEntry.State.SUGGESTED, suggestion.state());
        assertEquals(List.of(suggestion.entryId()), row("SELECT entry_id FROM room_requests WHERE at = ? AND"
                + " by_user_id = ? AND entry_id = ?", T0, bobId, suggestion.entryId()), "suggestions are requests");
        assertEquals(5, count());
        mode("vote");

        // A playlist (owner or moderators) is not a request.
        String playlistId = playlists.create(carolId, "Mix", null).id();
        playlists.add(carolActor, playlistId, ready(), null, () -> {
        });
        queue.addPlaylist(roomId, carolActor, carol, playlistId, false);
        assertEquals(5, count());

        // A refused add writes nothing.
        sql("UPDATE items SET status = 'failed' WHERE id = ?", song);
        assertEquals("item_failed", refused(() -> add(bob, song)));
        assertEquals(5, count());
    }

    @Test
    void youtubeLinksAndUploadsAreRequestsToo() throws Exception {
        String yt = item("ready", "dQw4w9WgXcQ");
        QueueOps.Added link = queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"));
        assertEquals(List.of("youtube", yt), row("SELECT via, item_id FROM room_requests WHERE entry_id = ?",
                link.entryId()));
        String uploaded = ready();
        String uploadId = UuidV7.newId();
        sql("INSERT INTO uploads (id, user_id, filename, size, created_at, updated_at, item_id)"
                + " VALUES (?, ?, 'a.mp3', 10, 1, 1, ?)", uploadId, bobId, uploaded);
        QueueOps.Added upload = queue.add(roomId, bobActor, bob, RoomQueue.Source.upload(uploadId));
        assertEquals(List.of("upload", uploaded), row("SELECT via, item_id FROM room_requests WHERE entry_id = ?",
                upload.entryId()));
        // QueueOps.add without a source (no request).
        String plain = ready();
        registry.call(roomId, tx -> QueueOps.add(tx, bob, plain));
        assertEquals(2, count());
    }

    // ------------------------------------------------------------------ reading

    @Test
    void ownerAndModeratorsListTheHistory() throws Exception {
        String a = ready();
        String yt = item("ready", "dQw4w9WgXcQ");
        add(bob, a);
        clock.advance(1000);
        add(guest, ready());
        clock.advance(1000);
        queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"));

        RoomRequests.Page page = all(aliceActor);
        assertNull(page.nextCursor());
        assertEquals(3, page.items().size());
        RoomRequests.Request newest = page.items().get(0);
        assertEquals("youtube", newest.via());
        assertEquals(yt, newest.item().id());
        assertEquals("dQw4w9WgXcQ", newest.item().youtubeId());
        assertEquals(ActorRef.user(bobId, "Bob"), newest.requester());
        assertEquals("Bob", newest.requesterName());
        assertEquals(T0 + 2000, newest.at());
        assertTrue(newest.inOwnerLibrary());
        assertFalse(newest.addedToOwnerLibrary());
        RoomRequests.Request byGuest = page.items().get(1);
        assertEquals(ActorRef.guest(guestId, "Quiet Walrus"), byGuest.requester());
        assertEquals(a, page.items().get(2).item().id());

        // Moderators read it; members, guests and other rooms' moderators don't.
        assertEquals(3, all(carolActor).items().size());
        assertEquals(403, assertThrows(HttpError.class, () -> all(bobActor)).status());
        assertEquals(401, assertThrows(HttpError.class, () -> all(guestActor)).status());
        assertEquals("room_not_found", refused(() -> requests.list(aliceActor, UuidV7.newId(), null, null, null, 10)));
        assertEquals(0, requests.list(aliceActor, otherRoomId, null, null, null, 10).items().size());

        // Filters.
        assertEquals(List.of("youtube"), requests.list(aliceActor, roomId, "youtube", null, null, 10).items()
                .stream().map(RoomRequests.Request::via).toList());
        assertEquals(2, requests.list(aliceActor, roomId, "library", null, null, 10).items().size());
        assertEquals(0, requests.list(aliceActor, roomId, "upload", null, null, 10).items().size());
        assertEquals(2, requests.list(aliceActor, roomId, null, bobId, null, 10).items().size());
        assertEquals(1, requests.list(aliceActor, roomId, "library", bobId, null, 10).items().size());
        assertEquals(1, requests.list(aliceActor, roomId, null, guestId, null, 10).items().size());
        assertEquals(0, requests.list(aliceActor, roomId, null, aliceId, null, 10).items().size());
        assertEquals("invalid_source", refused(() -> requests.list(aliceActor, roomId, "share", null, null, 10)));
        assertEquals("invalid_requester", refused(() -> requests.list(aliceActor, roomId, null, "bob", null, 10)));
        assertEquals("invalid_cursor", refused(() -> requests.list(aliceActor, roomId, null, null, "x", 10)));

        // Pages.
        RoomRequests.Page first = requests.list(aliceActor, roomId, null, null, null, 2);
        assertEquals(2, first.items().size());
        RoomRequests.Page second = requests.list(aliceActor, roomId, null, null, first.nextCursor(), 2);
        assertEquals(1, second.items().size());
        assertNull(second.nextCursor());
        assertEquals(page.items().get(2).id(), second.items().get(0).id());

        // Purged items stay listed (media expired); a gone guest keeps its name.
        sql("UPDATE items SET status = 'purged' WHERE id = ?", a);
        // (deleting a guest sets by_guest_id NULL; its queue entry would block the delete here)
        sql("UPDATE room_requests SET by_guest_id = NULL WHERE by_guest_id = ?", guestId);
        page = all(aliceActor);
        assertEquals("purged", page.items().get(2).item().status());
        assertNull(page.items().get(1).requester());
        assertEquals("Quiet Walrus", page.items().get(1).requesterName());
        // The name is the one at request time.
        sql("UPDATE users SET display_name = 'Robert' WHERE id = ?", bobId);
        assertEquals("Bob", all(aliceActor).items().get(0).requesterName());
    }

    // ------------------------------------------------------------------ add to library

    @Test
    void theOwnerAddsRequestsToTheirLibrary() throws Exception {
        String song = ready();
        String clip = ready();
        String purgedUpload = ready();
        String yt = item("ready", "dQw4w9WgXcQ");
        add(bob, song);
        add(bob, clip);
        add(guest, purgedUpload);
        queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"));
        sql("DELETE FROM library_entries WHERE user_id = ?", aliceId);
        sql("UPDATE items SET status = 'purged' WHERE id IN (?, ?)", purgedUpload, yt);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'manual')", aliceId, clip);
        Map<String, String> requestOf = new java.util.HashMap<>();
        for (RoomRequests.Request r : all(aliceActor).items()) {
            requestOf.put(r.item().id(), r.id());
            assertEquals(r.item().id().equals(clip), r.inOwnerLibrary(), r.toString());
        }
        String otherRooms = UuidV7.newId();
        sql("INSERT INTO room_requests (id, room_id, item_id, by_user_id, by_name, via, at)"
                + " VALUES (?, ?, ?, ?, 'Bob', 'library', ?)", otherRooms, otherRoomId, song, bobId, T0);

        // Moderators read only; members, guests: no.
        List<String> ids = List.of(requestOf.get(song));
        assertEquals(403, assertThrows(HttpError.class, () -> requests.addToLibrary(carolActor, roomId, ids)).status());
        assertEquals(403, assertThrows(HttpError.class, () -> requests.addToLibrary(bobActor, roomId, ids)).status());
        assertEquals(401, assertThrows(HttpError.class, () -> requests.addToLibrary(guestActor, roomId, ids)).status());
        assertEquals(List.of(), row("SELECT item_id FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, song));

        RoomRequests.AddResults results = requests.addToLibrary(aliceActor, roomId, List.of(requestOf.get(song),
                requestOf.get(clip), requestOf.get(purgedUpload), requestOf.get(yt), otherRooms, requestOf.get(song)));
        List<RoomRequests.Added> r = results.results();
        assertEquals(5, r.size(), "duplicates once: " + r);
        assertEquals(new RoomRequests.Added(requestOf.get(song), song, true, null), r.get(0));
        assertEquals(new RoomRequests.Added(requestOf.get(clip), clip, false, null), r.get(1), "already there");
        assertEquals("media_expired", r.get(2).error().code());
        assertNull(r.get(2).created());
        assertEquals(new RoomRequests.Added(requestOf.get(yt), yt, true, null), r.get(3), "a YouTube item is refetched");
        assertEquals(new RoomRequests.Added(otherRooms, null, null,
                new RoomRequests.Failure("request_not_found", "No such request in this room's history")), r.get(4));

        assertEquals(List.of("request"), row("SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, song));
        assertEquals(List.of("manual"), row("SELECT via FROM library_entries WHERE user_id = ? AND item_id = ?",
                aliceId, clip));
        assertEquals(List.of("pending"), row("SELECT status FROM items WHERE id = ?", yt));
        assertEquals(List.of("refetch"), row("SELECT kind FROM ingest_jobs WHERE item_id = ?", yt));
        assertEquals(List.of(1L), row("SELECT added_to_owner_library FROM room_requests WHERE id = ?",
                requestOf.get(song)));
        assertEquals(List.of(0L), row("SELECT added_to_owner_library FROM room_requests WHERE id = ?",
                requestOf.get(purgedUpload)));
        assertEquals(List.of(0L), row("SELECT added_to_owner_library FROM room_requests WHERE id = ?", otherRooms));
        for (RoomRequests.Request q : all(aliceActor).items()) {
            boolean added = !q.item().id().equals(purgedUpload);
            assertEquals(added, q.inOwnerLibrary(), q.toString());
            assertEquals(added, q.addedToOwnerLibrary(), q.toString());
        }

        // A YouTube refetch needs library.youtube.
        String yt2 = item("purged", "bbbbbbbbbbb");
        queue.add(roomId, bobActor, bob, RoomQueue.Source.item(yt2)); // bob's request refetches it
        sql("UPDATE items SET status = 'purged' WHERE id = ?", yt2);
        sql("DELETE FROM library_entries WHERE user_id = ? AND item_id = ?", aliceId, yt2);
        String yt2Request = (String) row("SELECT id FROM room_requests WHERE item_id = ?", yt2).get(0);
        settings.set(Settings.YTDLP_ENABLED, false);
        assertEquals("feature_disabled", requests.addToLibrary(aliceActor, roomId, List.of(yt2Request))
                .results().get(0).error().code());

        assertEquals("invalid_request_ids", refused(() -> requests.addToLibrary(aliceActor, roomId, List.of())));
        assertEquals("invalid_request_ids", refused(() -> requests.addToLibrary(aliceActor, roomId,
                List.of("not-an-id"))));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= RoomRequests.ADD_MAX; i++) {
            tooMany.add(UuidV7.newId());
        }
        assertEquals("invalid_request_ids", refused(() -> requests.addToLibrary(aliceActor, roomId, tooMany)));

        // After a transfer the new owner adds to their library; the old one (now moderator) can't.
        rooms.setRole(aliceActor, roomId, carolId, Rooms.Role.OWNER, "203.0.113.9");
        assertEquals(403, assertThrows(HttpError.class, () -> requests.addToLibrary(aliceActor, roomId, ids)).status());
        assertTrue(requests.addToLibrary(carolActor, roomId, ids).results().get(0).created());
        assertEquals(List.of(song), row("SELECT item_id FROM library_entries WHERE user_id = ?", carolId));
    }

    // ------------------------------------------------------------------ trimming

    @Test
    void historyIsTrimmedAfterRequestHistoryDays() throws Exception {
        assertEquals(90, settings.roomsRequestHistoryDays());
        add(bob, ready());
        clock.advance(DAY);
        add(bob, ready());
        settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, 1);
        clock.advance(1);
        // Past the period: no longer listed or addable, even before the trim runs.
        List<RoomRequests.Request> listed = all(aliceActor).items();
        assertEquals(1, listed.size());
        assertEquals(T0 + DAY, listed.get(0).at());
        String old = (String) row("SELECT id FROM room_requests WHERE at = ?", T0).get(0);
        assertEquals("request_not_found", requests.addToLibrary(aliceActor, roomId, List.of(old)).results().get(0)
                .error().code());
        assertEquals(1, requests.trim());
        assertEquals(List.of(), row("SELECT id FROM room_requests WHERE id = ?", old));
        assertEquals(0, requests.trim());
        assertEquals(1, count());

        // 0 keeps everything.
        settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, 0);
        clock.advance(1000 * DAY);
        assertEquals(0, requests.trim());
        assertEquals(1, all(aliceActor).items().size());
        assertEquals("rooms.requestHistoryDays: must be between 0 and 36500, got -1",
                assertThrows(IllegalArgumentException.class,
                        () -> settings.set(Settings.ROOMS_REQUEST_HISTORY_DAYS, -1)).getMessage());

        // The rows go with their room.
        rooms.delete(aliceActor, roomId, "203.0.113.9");
        assertEquals(0, count());
    }
}
