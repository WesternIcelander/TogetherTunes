package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.togethertunes.TestClock;
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
import io.siggi.togethertunes.http.Json;
import io.siggi.togethertunes.library.ItemSearch;
import io.siggi.togethertunes.library.Library;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room request search ({@link RequestSearch}) and the shared item search ({@link ItemSearch})
 * on a real database: by default the owner's library and playlists plus the searching user's
 * library (not for guests), with {@code all} the whole server; {@code libraryRequests} off; who
 * may search; filters and pagination; and that a result never tells whose libraries hold an item.
 */
abstract class RequestSearchContract {
    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Database db;
    private Settings settings;
    private RequestSearch search;
    private String roomId;
    private String aliceId;
    private String bobId;
    private Actor.User alice; // owner
    private Actor.User bob; // moderator
    private Actor.User carol; // member
    private Actor.User dave; // no role
    private Actor.Guest guest;
    private String ownLib; // alice's library only
    private String ownList; // only in alice's playlist
    private String shared; // alice's library (shared by carol) and carol's library
    private String carolOnly; // carol's library only
    private String daveOnly; // dave's library only
    private String nobody; // in no library
    private String failedUpload; // alice's library, failed upload
    private String purgedYoutube; // alice's library, purged YouTube item
    private String video; // alice's library, a video

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        Rooms rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        String carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        String daveId = accounts.createLocal("dave", "Dave", "dave-password1", false, null, "account.register").id();
        alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bob = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        carol = new Actor.User(carolId, "s-carol", Actor.Via.BEARER);
        dave = new Actor.User(daveId, "s-dave", Actor.Via.BEARER);
        String guestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', ?, 'Quiet Walrus', 1, 1)", guestId, "hash-" + guestId);
        guest = new Actor.Guest(guestId, GuestIdentity.COOKIE, false);
        roomId = rooms.create(alice, "Party", null, Rooms.Visibility.PUBLIC, null, "203.0.113.9").id();
        rooms.setRole(alice, roomId, bobId, Rooms.Role.MODERATOR, null);
        rooms.setRole(alice, roomId, carolId, Rooms.Role.MEMBER, null);

        ownLib = item("Blue Monday", "New Order", "upload", null, "ready", "audio");
        ownList = item("Bizarre Love Triangle", "New Order", "upload", null, "ready", "audio");
        shared = item("Shared Song", "Someone", "upload", null, "processing", "audio");
        carolOnly = item("Carol's Secret", "Hidden Artist", "upload", null, "ready", "audio");
        daveOnly = item("Dave's Tune", "Dave Band", "upload", null, "ready", "audio");
        nobody = item("Orphan", "Nobody", "upload", null, "ready", "audio");
        failedUpload = item("Broken", "New Order", "upload", null, "failed", "audio");
        purgedYoutube = item("Old Video", "Tuber", "youtube", "dQw4w9WgXcQ", "purged", "video");
        video = item("Music Video", "New Order", "upload", null, "ready", "video");
        library(aliceId, ownLib, "upload");
        library(aliceId, shared, "manual");
        library(aliceId, failedUpload, "upload");
        library(aliceId, purgedYoutube, "youtube");
        library(aliceId, video, "upload");
        library(carolId, shared, "upload");
        library(carolId, carolOnly, "upload");
        library(daveId, daveOnly, "upload");
        String playlist = UuidV7.newId();
        sql("INSERT INTO playlists (id, owner_id, name, created_at, updated_at) VALUES (?, ?, 'Mix', 1, 1)",
                playlist, aliceId);
        // ownList twice in the playlist, ownLib also in it: each still appears once
        for (String item : List.of(ownList, ownList, ownLib)) {
            sql("INSERT INTO playlist_entries (id, playlist_id, item_id, position, added_at) VALUES (?, ?, ?, 1, 1)",
                    UuidV7.newId(), playlist, item);
        }
        // carol's playlist is only found by a whole-server search
        String carolList = UuidV7.newId();
        sql("INSERT INTO playlists (id, owner_id, name, created_at, updated_at) VALUES (?, ?, 'C', 1, 1)",
                carolList, carolId);
        sql("INSERT INTO playlist_entries (id, playlist_id, item_id, position, added_at) VALUES (?, ?, ?, 1, 1)",
                UuidV7.newId(), carolList, nobody);
        search = new RequestSearch(db, rooms, new RoomGuests(db, settings, permissions, rooms,
                new RoomRegistry(db, clock, PackagerHook.NONE)));
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private String item(String title, String artist, String source, String youtubeId, String status, String kind)
            throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, artist, source, youtube_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at, duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?,"
                + " '720p30', 1, 60000)", id, kind, title, artist, source, youtubeId, aliceId, status);
        return id;
    }

    private void library(String userId, String itemId, String via) throws SQLException {
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, ?, ?)",
                userId, itemId, clock.millis(), via);
        clock.advance(1000);
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

    private void roomSettings(String json) throws SQLException {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", json, roomId);
    }

    private Set<String> ids(Actor actor, String q) throws SQLException {
        return ids(search.search(actor, roomId, q, null, null, RequestSearch.LIST_MAX));
    }

    private Set<String> allIds(Actor actor, String q) throws SQLException {
        return ids(search.search(actor, roomId, q, null, null, RequestSearch.LIST_MAX, true));
    }

    private static Set<String> ids(RequestSearch.Page page) {
        Set<String> out = new TreeSet<>();
        for (Library.ItemView i : page.items()) {
            out.add(i.id());
        }
        assertEquals(page.items().size(), out.size(), "each item once");
        return out;
    }

    private static String refused(Executable e) {
        return assertThrows(HttpError.class, e).code();
    }

    @Test
    void theOwnersAndTheRequestersLibraries() throws Exception {
        Set<String> base = new TreeSet<>(List.of(ownLib, ownList, shared, purgedYoutube, video));
        Set<String> withCarol = new TreeSet<>(base);
        withCarol.add(carolOnly);
        Set<String> withDave = new TreeSet<>(base);
        withDave.add(daveOnly);
        assertEquals(base, ids(alice, null));
        assertEquals(withCarol, ids(carol, null));
        assertEquals(withDave, ids(dave, null), "a user without a room role in a public room");
        assertEquals(base, ids(bob, null), "bob's library is empty");
        assertEquals(base, ids(guest, null), "guests have no library: the owner's only");
        assertEquals(base, ids(Actor.ANONYMOUS, null));
        assertEquals(Set.of(carolOnly), ids(carol, "secret"));
        assertEquals(Set.of(), ids(dave, "secret"), "not someone else's library by default");
        // the slug works too
        String slug = (String) db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT slug FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertEquals(withCarol, ids(search.search(carol, slug, null, null, null, 50)));
        // a stored requestScope from before is ignored
        roomSettings("{\"requestScope\":\"none\"}");
        assertEquals(withDave, ids(dave, null));
    }

    @Test
    void theWholeServer() throws Exception {
        // every item that isn't purged: a third user's library, a playlist, no library at all
        Set<String> server = new TreeSet<>(List.of(ownLib, ownList, shared, carolOnly, daveOnly, nobody, video));
        for (Actor a : List.of(alice, bob, carol, dave, guest, Actor.ANONYMOUS)) {
            assertEquals(server, allIds(a, null), "whole server for " + a);
        }
        assertEquals(Set.of(carolOnly), allIds(dave, "secret"), "carol's item found by dave");
        assertEquals(Set.of(), allIds(dave, "old video"), "a purged item isn't on the server");
        assertEquals(Set.of(), allIds(dave, "broken"), "nor a failed upload");
        // the shared search behind it: whether it is in the searcher's own library, nothing more
        ItemSearch items = new ItemSearch(db);
        ItemSearch.Page page = items.search(ItemSearch.Scope.server(), carol.userId(), null, null, null, 50);
        for (ItemSearch.Hit hit : page.items()) {
            assertEquals(hit.item().id().equals(shared) || hit.item().id().equals(carolOnly), hit.inYourLibrary(),
                    hit.item().title());
        }
        assertTrue(items.search(ItemSearch.Scope.server(), null, null, null, null, 50).items().stream()
                .noneMatch(ItemSearch.Hit::inYourLibrary));
        assertEquals(List.of(), items.search(ItemSearch.Scope.of(List.of(), List.of()), carol.userId(), null, null,
                null, 50).items());
    }

    @Test
    void wholeServerWithTheSearchersLibraryFirst() throws Exception {
        // GET /api/search: the whole server, carol's own library first, each part newest first
        ItemSearch items = new ItemSearch(db);
        List<String> mine = new ArrayList<>(List.of(shared, carolOnly));
        List<String> rest = new ArrayList<>(List.of(ownLib, ownList, daveOnly, nobody, video));
        mine.sort(java.util.Comparator.reverseOrder());
        rest.sort(java.util.Comparator.reverseOrder());
        List<String> expected = new ArrayList<>(mine);
        expected.addAll(rest);
        ItemSearch.Page page = items.searchMineFirst(carol.userId(), null, null, null, null, 50);
        assertEquals(expected, page.items().stream().map(h -> h.item().id()).toList());
        for (ItemSearch.Hit hit : page.items()) {
            assertEquals(mine.contains(hit.item().id()), hit.inYourLibrary(), hit.item().title());
        }
        assertNull(page.nextCursor());
        // pages of one, across the boundary between the two parts
        for (int size : List.of(1, 2, 3)) {
            List<String> paged = new ArrayList<>();
            String cursor = null;
            do {
                ItemSearch.Page p = items.searchMineFirst(carol.userId(), null, null, null, cursor, size);
                p.items().forEach(h -> paged.add(h.item().id()));
                assertTrue(p.items().size() <= size);
                cursor = p.nextCursor();
            } while (cursor != null);
            assertEquals(expected, paged, "pages of " + size);
        }
        assertTrue(items.searchMineFirst(carol.userId(), null, null, null, null, 1).nextCursor().startsWith("1_"));
        // alice: the failed upload and the purged YouTube item in her library aren't on the server
        List<String> aliceMine = new ArrayList<>(List.of(ownLib, shared, video));
        aliceMine.sort(java.util.Comparator.reverseOrder());
        assertEquals(aliceMine, items.searchMineFirst(aliceId, null, null, null, null, 50).items().stream()
                .limit(3).map(h -> h.item().id()).toList());
        // nobody's library (guests, anonymous visitors): newest first
        List<String> server = new ArrayList<>(expected);
        server.sort(java.util.Comparator.reverseOrder());
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            ItemSearch.Page p = items.searchMineFirst(null, null, null, null, cursor, 2);
            p.items().forEach(h -> {
                paged.add(h.item().id());
                assertFalse(h.inYourLibrary());
            });
            cursor = p.nextCursor();
            assertTrue(cursor == null || cursor.startsWith("0_"), cursor);
        } while (cursor != null);
        assertEquals(server, paged);
        // filters as everywhere else
        List<String> newOrder = new ArrayList<>(List.of(ownLib, ownList, video));
        newOrder.sort(java.util.Comparator.reverseOrder());
        List<String> aliceFirst = new ArrayList<>(newOrder);
        aliceFirst.remove(ownList);
        aliceFirst.add(ownList);
        assertEquals(aliceFirst, items.searchMineFirst(aliceId, "new order", null, null, null, 50).items()
                .stream().map(h -> h.item().id()).toList(), "alice's two first, then the playlist-only one");
        assertEquals(List.of(video), items.searchMineFirst(carol.userId(), null, "video", null, null, 50).items()
                .stream().map(h -> h.item().id()).toList());
        assertEquals(List.of(carolOnly), items.searchMineFirst(dave.userId(), "secret", null, null, null, 50).items()
                .stream().map(h -> h.item().id()).toList(), "carol's item found by dave");
        assertEquals("invalid_type", refused(() -> items.searchMineFirst(null, null, null, "film", null, 50)));
        assertEquals("invalid_kind", refused(() -> items.searchMineFirst(null, null, "image", null, null, 50)));
        for (String bad : List.of("x", shared, "2_" + shared, "1_nope", "0_")) {
            assertEquals("invalid_cursor", refused(() -> items.searchMineFirst(carol.userId(), null, null, null, bad,
                    50)), bad);
        }
        assertEquals("invalid_cursor", refused(() -> items.searchMineFirst(null, null, null, null, "1_" + shared, 50)),
                "no own library part without a user");
    }

    @Test
    void mayRequestFromTheRoom() throws Exception {
        // the check GET /api/search uses for guests and anonymous visitors
        assertEquals(roomId, search.requireMayRequest(guest, roomId).id());
        assertEquals(roomId, search.requireMayRequest(Actor.ANONYMOUS, roomId).id());
        roomSettings("{\"guests\":{\"canRequestLibrary\":false}}");
        assertEquals("guest_library_requests_disabled", refused(() -> search.requireMayRequest(guest, roomId)));
        roomSettings("{\"libraryRequests\":false}");
        assertEquals("library_requests_off", refused(() -> search.requireMayRequest(guest, roomId)));
        assertEquals(roomId, search.requireMayRequest(bob, roomId).id(), "moderators");
        sql("UPDATE rooms SET visibility = 'private' WHERE id = ?", roomId);
        assertEquals("room_not_found", refused(() -> search.requireMayRequest(guest, roomId)));
        assertEquals("room_not_found", refused(() -> search.requireMayRequest(guest, "no-such-room")));
    }

    @Test
    void libraryRequestsOff() throws Exception {
        roomSettings("{\"libraryRequests\":false}");
        for (Actor a : List.of(carol, dave, guest, Actor.ANONYMOUS)) {
            assertEquals("library_requests_off", refused(() -> ids(a, null)), "off for " + a);
            assertEquals("library_requests_off", refused(() -> allIds(a, null)), "off for " + a);
        }
        Set<String> base = Set.of(ownLib, ownList, shared, purgedYoutube, video);
        assertEquals(base, ids(alice, null), "the owner is unaffected");
        assertEquals(base, ids(bob, null), "moderators are unaffected");
        assertEquals(7, allIds(bob, null).size());
    }

    @Test
    void whoMaySearch() throws Exception {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"guests\":{\"canRequestLibrary\":false}}", roomId);
        assertEquals("guest_library_requests_disabled", refused(() -> ids(guest, null)));
        assertEquals("guest_library_requests_disabled", refused(() -> ids(Actor.ANONYMOUS, null)));
        assertEquals(6, ids(carol, null).size(), "users are not affected");
        sql("UPDATE rooms SET settings_json = '{}' WHERE id = ?", roomId);
        settings.setJson("guests.canRequestLibrary", "false");
        assertEquals("guest_library_requests_disabled", refused(() -> ids(guest, null)), "server switch off");
        settings.setJson("guests.canRequestLibrary", "true");
        settings.setJson("guests.enabled", "false");
        assertEquals("guest_library_requests_disabled", refused(() -> ids(guest, null)), "guests off");
        settings.setJson("guests.enabled", "true");
        Actor.Guest banned = new Actor.Guest(guest.guestId(), GuestIdentity.COOKIE, true);
        assertEquals("guest_library_requests_disabled", refused(() -> ids(banned, null)), "banned server-wide");
        // a user banned from the room, a private room, an unknown room
        sql("UPDATE room_members SET role = 'banned' WHERE room_id = ? AND user_id = ?", roomId, carol.userId());
        assertEquals("banned_from_room", refused(() -> ids(carol, null)));
        sql("UPDATE rooms SET visibility = 'private' WHERE id = ?", roomId);
        assertEquals("room_not_found", refused(() -> ids(dave, null)));
        assertEquals("room_not_found", refused(() -> ids(guest, null)));
        assertEquals(5, ids(bob, null).size(), "a moderator of the private room");
        assertEquals("room_not_found", refused(() -> search.search(alice, "no-such-room", null, null, null, 50)));
    }

    @Test
    void queryKindAndPages() throws Exception {
        assertEquals(Set.of(ownLib, ownList, video), ids(carol, "new order"), "words in any order and field");
        assertEquals(Set.of(ownList), ids(carol, "LOVE order"), "case-insensitive");
        assertEquals(Set.of(), ids(carol, "Broken"), "a failed upload is left out");
        assertEquals(Set.of(purgedYoutube), ids(carol, "old"), "a purged YouTube item is listed");
        assertEquals(Set.of(video, purgedYoutube), ids(search.search(carol, roomId, null, "video", null, 50)));
        assertEquals(Set.of(ownLib, ownList, shared, carolOnly),
                ids(search.search(carol, roomId, null, "audio", null, 50)));
        assertEquals(Set.of(video), ids(search.search(carol, roomId, null, "video", null, 50, true)));
        assertEquals("invalid_kind", refused(() -> search.search(carol, roomId, null, "image", null, 50)));
        // the type= filter (docs/media-types.md)
        assertEquals(Set.of(video, purgedYoutube),
                ids(search.search(carol, roomId, null, null, "uncategorized", null, 50, false)));
        assertEquals(Set.of(ownLib, ownList, shared, carolOnly),
                ids(search.search(carol, roomId, null, null, "audio", null, 50, false)));
        assertEquals(Set.of(), ids(search.search(carol, roomId, null, null, "movie,show", null, 50, true)));
        assertEquals("invalid_type", refused(() -> search.search(carol, roomId, null, null, "song", null, 50, false)));
        assertEquals("invalid_query", refused(() -> search.search(carol, roomId, "x".repeat(201), null, null, 50)));
        assertEquals("invalid_cursor", refused(() -> search.search(carol, roomId, null, null, "nope", 50)));
        // pages of 2: newest item first, no item twice, nothing skipped
        List<String> all = new ArrayList<>();
        String cursor = null;
        do {
            RequestSearch.Page page = search.search(carol, roomId, null, null, cursor, 2);
            for (Library.ItemView i : page.items()) {
                all.add(i.id());
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        List<String> expected = new ArrayList<>(List.of(ownLib, ownList, shared, carolOnly, purgedYoutube, video));
        expected.sort(java.util.Comparator.reverseOrder());
        assertEquals(expected, all);
        assertNull(search.search(carol, roomId, null, null, null, 6).nextCursor());
    }

    @Test
    void noLibraryMembership() throws Exception {
        for (boolean all : List.of(false, true)) {
            RequestSearch.Page page = search.search(carol, roomId, null, null, null, 50, all);
            JsonObject json = Json.GSON.toJsonTree(page).getAsJsonObject();
            Set<String> allowed = Set.of("id", "kind", "title", "artist", "album", "durationMs", "source",
                    "youtubeId", "status", "presentAsAudio", "mediaType", "show");
            for (JsonElement e : json.getAsJsonArray("items")) {
                for (String key : e.getAsJsonObject().keySet()) {
                    assertFalse(!allowed.contains(key), "unexpected field " + key + " in " + e);
                }
            }
            String text = json.toString();
            for (String leak : List.of("addedAt", "via", "sharedBy", carol.userId(), aliceId, "Alice")) {
                assertFalse(text.contains(leak), leak + " in " + text);
            }
            for (String key : json.keySet()) {
                assertFalse(!key.equals("items") && !key.equals("nextCursor"), "unexpected field " + key);
            }
        }
    }
}
