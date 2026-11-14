package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.TestUploads;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorResolver;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Sessions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.Router;
import io.siggi.togethertunes.http.WebServer;
import io.siggi.togethertunes.settings.GuestIdentity;
import io.siggi.togethertunes.rooms.PlayingItems;
import io.siggi.togethertunes.rooms.RoomViewers;
import io.siggi.togethertunes.settings.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /media/{id}/{file}} over HTTP ({@link MediaApi}): access rules, Range, content types,
 * 404s; and views ({@code POST /api/items/{id}/view}).
 */
class MediaApiTest {
    @TempDir
    Path dir;

    private Database db;
    private Settings settings;
    private WebServer server;
    private MediaApi api;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final TestClock clock = new TestClock(1_800_000_000_000L);
    /** The stubbed rooms: room id -> its current and next items. */
    private final Map<String, Set<String>> roomItems = new ConcurrentHashMap<>();
    /** What room {@code r1} plays now or next; the anonymous test client (127.0.0.1) is in it. */
    private final Set<String> playing = ConcurrentHashMap.newKeySet();
    /** Who is in which room (the real tracker, over the stubbed rooms). */
    private RoomViewers viewers;
    private String aliceId;
    private String alice;
    private Path media;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new Settings(db, config);
        Router router = new Router(new ActorResolver(db, settings, clock, false).hook());
        roomItems.put("r1", playing);
        viewers = new RoomViewers(new PlayingItems() {
            @Override
            public boolean isCurrentOrNext(String itemId) {
                return roomItems.values().stream().anyMatch(s -> s.contains(itemId));
            }

            @Override
            public boolean isCurrentOrNextIn(String roomId, String itemId) {
                Set<String> items = roomItems.get(roomId);
                return items != null && items.contains(itemId);
            }
        });
        // the HTTP client below: an anonymous socket from the same address is in room r1
        viewers.enter("anon-socket", Actor.ANONYMOUS, "127.0.0.1", "r1");
        api = TestUploads.mediaApi(db, dir, viewers, clock);
        api.register(router);
        server = WebServer.start("127.0.0.1", 0, router, dir.toFile());
        media = dir.resolve("data").resolve("media");
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        Sessions sessions = new Sessions(db, clock);
        aliceId = accounts.createLocal("alice", null, "alice-password", false, null, "account.register").id();
        alice = sessions.create(aliceId, Actor.Via.BEARER, "test", null).token();
    }

    @AfterEach
    void tearDown() {
        server.close();
        db.close();
        http.close();
    }

    private HttpResponse<byte[]> call(String method, String path, String token, String range) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (range != null) {
            b.header("Range", range);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    private static String code(HttpResponse<byte[]> r) {
        return JsonParser.parseString(new String(r.body())).getAsJsonObject().getAsJsonObject("error")
                .get("code").getAsString();
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private String item(String kind, String status, String originalFile) throws Exception {
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, original_file)"
                    + " VALUES (?, ?, 'Song', 'upload', ?, 3, ?, '720p30', 1, ?)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, aliceId);
                ps.setString(4, status);
                ps.setString(5, originalFile);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private byte[] write(String itemId, String file, int size) throws Exception {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 7 + file.length());
        }
        Path target = media.resolve(itemId).resolve(file);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        return content;
    }

    @Test
    void usersGetEveryFileWithRangeAndTheRightContentType() throws Exception {
        String video = item("video", "ready", null);
        byte[] mezz = write(video, "mezz.mp4", 50_000);
        byte[] audio = write(video, "audio.m4a", 20_000);
        byte[] cover = write(video, "cover.jpg", 3_000);
        String song = item("audio", "ready", "original.flac");
        byte[] original = write(song, "original.flac", 40_000);

        HttpResponse<byte[]> r = get("/media/" + video + "/mezz.mp4", alice);
        assertEquals(200, r.statusCode());
        assertEquals("video/mp4", header(r, "Content-Type"));
        assertEquals("private, no-cache", header(r, "Cache-Control"));
        assertEquals("nosniff", header(r, "X-Content-Type-Options"));
        assertEquals("bytes", header(r, "Accept-Ranges"));
        assertArrayEquals(mezz, r.body());

        HttpResponse<byte[]> part = call("GET", "/media/" + video + "/audio.m4a", alice, "bytes=100-199");
        assertEquals(206, part.statusCode());
        assertEquals("audio/mp4", header(part, "Content-Type"));
        assertEquals("bytes 100-199/20000", header(part, "Content-Range"));
        assertArrayEquals(Arrays.copyOfRange(audio, 100, 200), part.body());

        // a video's audio-only file (its audio trim), for audio-only rooms
        byte[] audioOnly = write(video, "audio-only.m4a", 7_000);
        HttpResponse<byte[]> ao = get("/media/" + video + "/audio-only.m4a", alice);
        assertEquals(200, ao.statusCode());
        assertEquals("audio/mp4", header(ao, "Content-Type"));
        assertArrayEquals(audioOnly, ao.body());

        HttpResponse<byte[]> img = get("/media/" + video + "/cover.jpg", alice);
        assertEquals("image/jpeg", header(img, "Content-Type"));
        assertArrayEquals(cover, img.body());

        HttpResponse<byte[]> orig = call("GET", "/media/" + song + "/original", alice, "bytes=-10");
        assertEquals(206, orig.statusCode());
        assertEquals("audio/flac", header(orig, "Content-Type"));
        assertArrayEquals(Arrays.copyOfRange(original, original.length - 10, original.length), orig.body());

        HttpResponse<byte[]> head = call("HEAD", "/media/" + song + "/original", alice, null);
        assertEquals(200, head.statusCode());
        assertEquals("40000", header(head, "Content-Length"));
        assertEquals(0, head.body().length);

        // ETag revalidation works, so no-cache costs only a 304
        String etag = header(r, "ETag");
        if (etag != null) {
            HttpResponse<byte[]> again = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + server.port() + "/media/" + video + "/mezz.mp4")).header("Authorization", "Bearer " + alice)
                    .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(304, again.statusCode());
        }
    }

    @Test
    void guestsAndAnonymousOnlyGetWhatTheirRoomPlays() throws Exception {
        String id = item("video", "ready", null);
        write(id, "mezz.mp4", 1000);
        write(id, "audio.m4a", 1000);
        write(id, "cover.jpg", 100);

        for (String file : new String[] {"mezz.mp4", "audio.m4a", "original"}) {
            HttpResponse<byte[]> refused = get("/media/" + id + "/" + file, null);
            assertEquals(403, refused.statusCode(), file);
            assertEquals("forbidden", code(refused));
        }
        // a refused caller learns nothing about which items exist
        assertEquals(403, get("/media/" + UuidV7.newId() + "/audio.m4a", null).statusCode());
        assertEquals(403, get("/media/not-an-id/audio.m4a", null).statusCode());
        // covers are always allowed: metadata is visible by direct link
        assertEquals(200, get("/media/" + id + "/cover.jpg", null).statusCode());

        // current or next in a room the visitor isn't in: still refused
        roomItems.put("r2", Set.of(id));
        assertEquals(403, get("/media/" + id + "/mezz.mp4", null).statusCode());
        assertEquals(403, get(MediaApi.url(id, 0, "mezz.mp4"), null).statusCode());

        playing.add(id); // current or next in the visitor's room
        assertEquals(200, get("/media/" + id + "/mezz.mp4", null).statusCode());
        assertEquals(206, call("GET", "/media/" + id + "/audio.m4a", null, "bytes=0-9").statusCode());
        playing.remove(id); // the next Range request is refused
        assertEquals(403, call("GET", "/media/" + id + "/audio.m4a", null, "bytes=10-19").statusCode());

        // the visitor leaves its room while the item plays there: refused
        playing.add(id);
        assertEquals(200, get("/media/" + id + "/audio.m4a", null).statusCode());
        viewers.exit("anon-socket");
        assertEquals(403, get("/media/" + id + "/audio.m4a", null).statusCode());
        assertEquals(403, get(MediaApi.url(id, 0, "audio.m4a"), null).statusCode());
        // and the original: never for a visitor whose room doesn't play it
        assertEquals(403, get("/media/" + id + "/original", null).statusCode());
    }

    @Test
    void theRuleByActor() throws Exception {
        String id = UuidV7.newId();
        String other = UuidV7.newId();
        Actor guest = new Actor.Guest(UuidV7.newId(), GuestIdentity.COOKIE, false);
        Actor guest2 = new Actor.Guest(UuidV7.newId(), GuestIdentity.COOKIE, false);
        Actor user = new Actor.User(aliceId, UuidV7.newId(), Actor.Via.COOKIE);
        String away = "10.0.0.9";
        // the media access matrix: user / guest / anonymous x in the room or not x playing or not
        for (String file : new String[] {"audio.m4a", "mezz.mp4", "original", "audio-only.m4a"}) {
            assertTrue(api.allowed(user, away, id, file), "users: any item, in a room or not");
            assertTrue(api.allowed(user, null, id, file));
            assertFalse(api.allowed(guest, away, id, file));
            assertFalse(api.allowed(Actor.ANONYMOUS, "127.0.0.1", id, file), "in r1, but r1 doesn't play it");
            assertFalse(api.allowed(Actor.ANONYMOUS, away, id, file));
        }
        assertTrue(api.allowed(guest, away, id, "cover.jpg"));
        assertTrue(api.allowed(Actor.ANONYMOUS, away, id, "cover.jpg"));

        playing.add(id); // r1 plays it
        assertTrue(api.allowed(Actor.ANONYMOUS, "127.0.0.1", id, "audio.m4a"), "the anonymous visitor in r1");
        assertFalse(api.allowed(Actor.ANONYMOUS, "127.0.0.1", other, "audio.m4a"), "only what r1 plays");
        assertFalse(api.allowed(Actor.ANONYMOUS, away, id, "audio.m4a"), "another address isn't in r1");
        assertFalse(api.allowed(Actor.ANONYMOUS, null, id, "audio.m4a"));
        assertFalse(api.allowed(guest, away, id, "audio.m4a"), "a guest not in r1");
        // a guest's request from the address of an anonymous visitor in r1 counts as that visitor's
        assertTrue(api.allowed(guest, "127.0.0.1", id, "audio.m4a"));

        // the guest joins r1 (from another address): its requests count from anywhere, other guests' don't
        viewers.enter("guest-socket", guest, away, "r1");
        assertTrue(api.allowed(guest, away, id, "mezz.mp4"));
        assertTrue(api.allowed(guest, "192.0.2.1", id, "mezz.mp4"));
        assertFalse(api.allowed(guest2, away, id, "mezz.mp4"), "a guest is recorded by its id, not its address");
        assertFalse(api.allowed(Actor.ANONYMOUS, away, id, "mezz.mp4"));
        assertFalse(api.allowed(guest, away, other, "mezz.mp4"));

        // the guest's socket moves to r2: only what r2 plays
        roomItems.put("r2", Set.of(other));
        viewers.enter("guest-socket", guest, away, "r2");
        assertTrue(api.allowed(guest, away, other, "audio.m4a"));
        assertFalse(api.allowed(guest, away, id, "audio.m4a"), "no longer in r1");
        // two sockets of one guest: leaving with one keeps the other's room
        viewers.enter("guest-socket-2", guest, away, "r1");
        assertTrue(api.allowed(guest, away, id, "audio.m4a"));
        viewers.exit("guest-socket");
        assertFalse(api.allowed(guest, away, other, "audio.m4a"));
        assertTrue(api.allowed(guest, away, id, "audio.m4a"));
        viewers.exit("guest-socket-2");
        assertFalse(api.allowed(guest, away, id, "audio.m4a"));

        // an anonymous visitor is recognised by its IP key: one IPv6 /64
        viewers.enter("v6-socket", Actor.ANONYMOUS, "2001:db8:1:2::1", "r1");
        assertTrue(api.allowed(Actor.ANONYMOUS, "2001:db8:1:2::abcd", id, "audio.m4a"));
        assertFalse(api.allowed(Actor.ANONYMOUS, "2001:db8:1:3::1", id, "audio.m4a"));
        viewers.exit("v6-socket");
        assertFalse(api.allowed(Actor.ANONYMOUS, "2001:db8:1:2::abcd", id, "audio.m4a"));

        // a user's sockets aren't recorded (users need no room)
        viewers.enter("user-socket", user, away, "r1");
        assertFalse(api.allowed(Actor.ANONYMOUS, away, id, "audio.m4a"));
        viewers.exit("user-socket");
    }

    @Test
    void missingThingsAre404() throws Exception {
        String audioItem = item("audio", "ready", null);
        write(audioItem, "audio.m4a", 100);
        String pending = item("video", "pending", null);
        String purged = item("audio", "purged", "original.mp3");
        write(purged, "original.mp3", 100); // a leftover; never served for a purged item
        String odd = item("audio", "ready", "../secret.key");
        Files.writeString(dir.resolve("data").resolve("secret.key"), "x");

        assertEquals("item_not_found", code(get("/media/" + UuidV7.newId() + "/audio.m4a", alice)));
        int traversal = get("/media/..%2F..%2Fsecret.key/audio.m4a", alice).statusCode();
        assertTrue(traversal == 400 || traversal == 404, "refused: " + traversal);
        assertEquals("item_not_found", code(get("/media/not-an-id/audio.m4a", alice)));
        assertEquals(404, get("/media/" + audioItem + "/index.bin", alice).statusCode());
        assertEquals(404, get("/media/" + audioItem + "/card.mp4", alice).statusCode());
        assertEquals("file_not_found", code(get("/media/" + audioItem + "/mezz.mp4", alice)));
        assertEquals("file_not_found", code(get("/media/" + audioItem + "/original", alice)));
        assertEquals("file_not_found", code(get("/media/" + audioItem + "/cover.jpg", alice)));
        assertEquals("file_not_found", code(get("/media/" + pending + "/audio.m4a", alice)));
        assertEquals("file_not_found", code(get("/media/" + purged + "/original", alice)));
        assertEquals("file_not_found", code(get("/media/" + odd + "/original", alice)));
        // a video item's original is kept for re-encodes only, never served
        String video = item("video", "ready", "original.mkv");
        write(video, "original.mkv", 100);
        assertEquals("file_not_found", code(get("/media/" + video + "/original", alice)));
    }

    private void setVersion(String itemId, int version) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET media_version = ? WHERE id = ?")) {
                ps.setInt(1, version);
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Versioned URLs: {@code /media/{id}/v/{N}/{file}} serves version N for as long as it exists,
     * also after the item switched to a newer one (a player that started on it keeps getting its
     * bytes), and may be cached as immutable; the unversioned URL follows the current version.
     */
    @Test
    void versionedUrlsKeepServingTheirVersion() throws Exception {
        String id = item("video", "ready", null);
        byte[] flat = write(id, "mezz.mp4", 2000); // version 0: the flat layout
        assertArrayEquals(flat, get("/media/" + id + "/v/0/mezz.mp4", alice).body());
        byte[] v1 = write(id, "v1/mezz.mp4", 3000);
        setVersion(id, 1); // a re-encode published version 1
        HttpResponse<byte[]> current = get("/media/" + id + "/mezz.mp4", alice);
        assertArrayEquals(v1, current.body());
        assertEquals("private, no-cache", header(current, "Cache-Control"));
        // a player that started on version 0 goes on reading it by Range
        HttpResponse<byte[]> old = call("GET", "/media/" + id + "/v/0/mezz.mp4", alice, "bytes=1000-1099");
        assertEquals(206, old.statusCode());
        assertArrayEquals(Arrays.copyOfRange(flat, 1000, 1100), old.body());
        assertEquals(MediaApi.IMMUTABLE, header(old, "Cache-Control"));
        HttpResponse<byte[]> v1r = get(MediaApi.url(id, 1, "mezz.mp4"), alice);
        assertArrayEquals(v1, v1r.body());
        assertEquals("video/mp4", header(v1r, "Content-Type"));
        assertEquals(MediaApi.IMMUTABLE, header(v1r, "Cache-Control"));

        // once deleted, an old version is gone; a version that never existed is too
        Files.delete(media.resolve(id).resolve("mezz.mp4"));
        assertEquals("file_not_found", code(get("/media/" + id + "/v/0/mezz.mp4", alice)));
        assertEquals("file_not_found", code(get("/media/" + id + "/v/7/mezz.mp4", alice)));
        // only plain numbers; no other files
        for (String bad : new String[] {"01", "-1", "x", "1.0", "9999999999"}) {
            assertEquals(404, get("/media/" + id + "/v/" + bad + "/mezz.mp4", alice).statusCode(), bad);
        }
        assertEquals(404, get("/media/" + id + "/v/1/index.bin", alice).statusCode());

        // the original isn't versioned: the same file under every version, revalidated
        String song = item("audio", "ready", "original.flac");
        byte[] original = write(song, "original.flac", 500);
        write(song, "v3/audio.m4a", 100);
        setVersion(song, 3);
        HttpResponse<byte[]> orig = get("/media/" + song + "/v/3/original", alice);
        assertArrayEquals(original, orig.body());
        assertEquals("private, no-cache", header(orig, "Cache-Control"));

        // the same access rules
        assertEquals(403, get(MediaApi.url(id, 1, "mezz.mp4"), null).statusCode());
        playing.add(id);
        assertEquals(200, get(MediaApi.url(id, 1, "mezz.mp4"), null).statusCode());
        // a purged item serves no version
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'purged' WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals("file_not_found", code(get(MediaApi.url(id, 1, "mezz.mp4"), alice)));
    }

    @Test
    void onlyGetAndHead() throws Exception {
        String id = item("audio", "ready", null);
        write(id, "audio.m4a", 100);
        for (String method : new String[] {"PUT", "DELETE", "POST"}) {
            assertEquals(405, call(method, "/media/" + id + "/audio.m4a", alice, null).statusCode(), method);
        }
        HttpResponse<byte[]> options = call("OPTIONS", "/media/" + id + "/audio.m4a", alice, null);
        assertTrue(options.statusCode() < 300, "OPTIONS answered by the router: " + options.statusCode());
        assertArrayEquals(Files.readAllBytes(media.resolve(id).resolve("audio.m4a")),
                get("/media/" + id + "/audio.m4a", alice).body(), "the file is untouched");
        assertEquals(100, Files.size(media.resolve(id).resolve("audio.m4a")));
    }

    private Long lastViewedAt(String itemId) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_viewed_at FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        });
    }

    private void setStatus(String itemId, String status) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Fetching a media file, whole, by Range or HEAD, is never a view. */
    @Test
    void fileGetsAreNeverViews() throws Exception {
        String video = item("video", "ready", null);
        write(video, "mezz.mp4", 5_000);
        write(video, "audio.m4a", 2_000);
        write(video, "audio-only.m4a", 2_000);
        write(video, "cover.jpg", 100);
        for (String file : new String[] {"mezz.mp4", "audio.m4a", "audio-only.m4a", "cover.jpg"}) {
            assertEquals(200, get("/media/" + video + "/" + file, alice).statusCode(), file);
            assertEquals(200, get(MediaApi.url(video, 0, file), alice).statusCode(), file);
            assertEquals(206, call("GET", "/media/" + video + "/" + file, alice, "bytes=0-9").statusCode(), file);
            assertEquals(200, call("HEAD", "/media/" + video + "/" + file, alice, null).statusCode(), file);
        }
        playing.add(video); // guests and anonymous visitors playing it in a room: not a view either
        assertEquals(200, get("/media/" + video + "/mezz.mp4", null).statusCode());
        String song = item("audio", "ready", "original.flac");
        write(song, "audio.m4a", 1_000);
        write(song, "original.flac", 1_000);
        assertEquals(200, get("/media/" + song + "/audio.m4a", alice).statusCode());
        assertEquals(200, get("/media/" + song + "/original", alice).statusCode());
        assertEquals(null, lastViewedAt(video));
        assertEquals(null, lastViewedAt(song));
    }

    /**
     * A view reported by a client: by whoever may play the item, sets {@code last_viewed_at};
     * one per actor and item a minute is recorded (later reports within it answer 204 and write nothing).
     */
    @Test
    void reportedViews() throws Exception {
        String id = item("audio", "ready", null);
        write(id, "audio.m4a", 100);
        long t0 = clock.millis();
        HttpResponse<byte[]> r = call("POST", "/api/items/" + id + "/view", alice, null);
        assertEquals(204, r.statusCode());
        assertEquals(t0, lastViewedAt(id));

        // within the minute: answered, not written
        clock.advance(30_000);
        assertEquals(204, call("POST", "/api/items/" + id + "/view", alice, null).statusCode());
        assertEquals(t0, lastViewedAt(id));
        // a minute after the recorded one: recorded
        clock.advance(30_000);
        assertEquals(204, call("POST", "/api/items/" + id + "/view", alice, null).statusCode());
        assertEquals(t0 + 60_000, lastViewedAt(id));
        // the limit is per item: another item is recorded at once
        String other = item("video", "ready", null);
        assertEquals(204, call("POST", "/api/items/" + other + "/view", alice, null).statusCode());
        assertEquals(t0 + 60_000, lastViewedAt(other));

        // guests and anonymous visitors: only what they may play (refused before the item is looked up)
        clock.advance(1_000);
        HttpResponse<byte[]> refused = call("POST", "/api/items/" + id + "/view", null, null);
        assertEquals(403, refused.statusCode());
        assertEquals("forbidden", code(refused));
        assertEquals(403, call("POST", "/api/items/" + UuidV7.newId() + "/view", null, null).statusCode());
        assertEquals(t0 + 60_000, lastViewedAt(id));
        playing.add(id);
        // the limit is per actor too: alice's recent view doesn't hold back an anonymous one
        assertEquals(204, call("POST", "/api/items/" + id + "/view", null, null).statusCode());
        assertEquals(t0 + 61_000, lastViewedAt(id));
        playing.clear(); // no longer played in the visitor's room: refused
        clock.advance(60_000);
        assertEquals(403, call("POST", "/api/items/" + id + "/view", null, null).statusCode());
        assertEquals(t0 + 61_000, lastViewedAt(id));
        roomItems.put("r2", Set.of(id)); // played in a room the visitor isn't in: refused too
        assertEquals(403, call("POST", "/api/items/" + id + "/view", null, null).statusCode());
        playing.add(id);
        assertEquals(204, call("POST", "/api/items/" + id + "/view", null, null).statusCode());
        assertEquals(t0 + 121_000, lastViewedAt(id));

        // nothing to play: unknown, not ready, purged
        assertEquals("item_not_found", code(call("POST", "/api/items/" + UuidV7.newId() + "/view", alice, null)));
        assertEquals("item_not_found", code(call("POST", "/api/items/not-an-id/view", alice, null)));
        String pending = item("video", "pending", null);
        HttpResponse<byte[]> notReady = call("POST", "/api/items/" + pending + "/view", alice, null);
        assertEquals(409, notReady.statusCode());
        assertEquals("item_not_ready", code(notReady));
        setStatus(other, "purged");
        clock.advance(120_000);
        assertEquals("item_not_ready", code(call("POST", "/api/items/" + other + "/view", alice, null)));
        assertEquals(null, lastViewedAt(pending));
        assertEquals(t0 + 60_000, lastViewedAt(other));
    }

    @Test
    void originalContentTypes() {
        assertEquals("audio/mpeg", MediaApi.contentType("original.mp3"));
        assertEquals("audio/flac", MediaApi.contentType("original.flac"));
        assertEquals("audio/wav", MediaApi.contentType("original.wav"));
        assertEquals("audio/aac", MediaApi.contentType("original.aac"));
        assertEquals("audio/ogg", MediaApi.contentType("original.ogg"));
        assertEquals("audio/ogg", MediaApi.contentType("original.opus"));
        assertEquals("audio/mp4", MediaApi.contentType("original.m4a"));
        assertEquals("audio/webm", MediaApi.contentType("original.webm"));
        assertEquals("audio/x-matroska", MediaApi.contentType("original.mka"));
        assertEquals("application/octet-stream", MediaApi.contentType("original.bin"));
        assertEquals("audio/mp4", MediaApi.contentType("audio.m4a"));
        assertEquals("video/mp4", MediaApi.contentType("mezz.mp4"));
        assertEquals("image/jpeg", MediaApi.contentType("cover.jpg"));
    }
}
