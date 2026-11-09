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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The queue operations on a real database ({@link QueueOps} through {@link RoomQueue}): add by
 * item, upload and YouTube link (cached, so no yt-dlp), suggestions in {@code owner} mode,
 * {@code requestsPerActor} and {@code noRepeatWindow}, addPlaylist with and without shuffle,
 * remove, move (incl. renumbering), and play / pause / seek / next / previous with their
 * permissions, all surviving a reload from the database.
 */
abstract class QueueOpsContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private RoomRegistry registry;
    private RoomQueue queue;
    private Settings settings;
    private Playlists playlists;
    private YoutubeImports youtube;
    private Uploads uploads;
    private Permissions permissions;
    private String roomId;
    private String aliceId;
    private String bobId;
    private String carolId;
    private String guestId;
    private Actor.User aliceActor;
    private Actor.User bobActor;
    private Actor.Guest guestActor;
    private RoomActor owner;
    private RoomActor bob;
    private RoomActor carol;
    private RoomActor guest;

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
        Rooms rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        bobId = accounts.createLocal("bob", "Bob", "bob-password1", false, null, "account.register").id();
        carolId = accounts.createLocal("carol", "Carol", "carol-password", false, null, "account.register").id();
        guestId = UuidV7.newId();
        sql("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES (?, 'cookie', ?, 'Quiet Walrus', 1, 1)", guestId, "hash-" + guestId);
        aliceActor = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        bobActor = new Actor.User(bobId, "s-bob", Actor.Via.BEARER);
        guestActor = new Actor.Guest(guestId, GuestIdentity.COOKIE, false);
        roomId = rooms.create(aliceActor, "Party", null, null, null, "203.0.113.9").id();
        owner = RoomActor.user(aliceId, Rooms.Role.OWNER);
        bob = RoomActor.user(bobId, Rooms.Role.MEMBER);
        carol = RoomActor.user(carolId, null);
        guest = RoomActor.guest(guestId, true, true);
        // No yt-dlp: only cached YouTube items work, which is what these tests use.
        Tools tools = new Tools("ffmpeg", "ffprobe", dir.resolve("no-yt-dlp").toString());
        youtube = TestUploads.youtube(db, config, tools, clock);
        playlists = new Playlists(db, youtube, clock);
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        uploads = TestUploads.uploads(db, dir, config, tools, clock);
        queue = new RoomQueue(registry, db, uploads, youtube, playlists, TestUploads.policy(permissions, settings),
                new Random(42));
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

    private void reload() {
        registry.close();
        registry = new RoomRegistry(db, clock, PackagerHook.NONE);
        queue = new RoomQueue(registry, db, uploads, youtube, playlists, TestUploads.policy(permissions, settings),
                new Random(42));
    }

    /** A 60 s item with this status (an upload, or a YouTube item with {@code youtubeId}). */
    private String item(String status, String youtubeId) throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, youtube_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at, duration_ms) VALUES (?, 'audio', 'Song', ?, ?, ?, 1, ?,"
                + " '720p30', 1, 60000)", id, youtubeId == null ? "upload" : "youtube", youtubeId, aliceId, status);
        // In the owner's library, so it is in the room's request scope.
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
                            out.add(rs.getObject(col));
                        }
                    }
                    return out;
                }
            }
        });
    }

    private String stateOf(String entryId) throws SQLException {
        return (String) row("SELECT state FROM queue_entries WHERE id = ?", entryId).get(0);
    }

    private void settings(String json) throws Exception {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", json, roomId);
        reload();
    }

    private void mode(String mode) throws Exception {
        sql("UPDATE rooms SET control_mode = ? WHERE id = ?", mode, roomId);
        reload();
    }

    private <T> T call(RoomMutation<T> m) throws Exception {
        return registry.call(roomId, m);
    }

    private List<String> order() throws Exception {
        return call(tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::id).toList());
    }

    private String current() throws Exception {
        return call(tx -> tx.state().current() == null ? null : tx.state().current().id());
    }

    private Timeline timeline() throws Exception {
        return call(tx -> tx.state().timeline());
    }

    private String add(RoomActor a, String itemId) throws Exception {
        return queue.add(roomId, actorOf(a), a, RoomQueue.Source.item(itemId)).entryId();
    }

    private Actor actorOf(RoomActor a) {
        if (!a.identified()) {
            return new Actor.Anonymous();
        }
        if (a.guestId() != null) {
            return guestActor;
        }
        if (aliceId.equals(a.userId())) {
            return aliceActor;
        }
        if (bobId.equals(a.userId())) {
            return bobActor;
        }
        return new Actor.User(a.userId(), "s", Actor.Via.BEARER);
    }

    private interface Op {
        void run() throws Exception;
    }

    private String refused(Op op) {
        return assertThrows(HttpError.class, op::run).code();
    }

    @Test
    void addQueuesAtTheEndAndStartsAnIdleRoom() throws Exception {
        String i1 = ready();
        String i2 = ready();
        String i3 = item("processing", null);
        QueueOps.Added a1 = queue.add(roomId, bobActor, bob, RoomQueue.Source.item(i1));
        assertEquals(QueueEntry.State.QUEUED, a1.state());
        assertEquals(a1.entryId(), current(), "an idle room starts the first ready entry");
        assertEquals("playing", stateOf(a1.entryId()));
        clock.advance(1);
        String e2 = add(guest, i2);
        clock.advance(1);
        String e3 = add(bob, i3);
        assertEquals(List.of(e2, e3), order());
        assertEquals(List.of(clock.millis()), row("SELECT last_referenced_at FROM items WHERE id = ?", i3)
                .stream().map(o -> ((Number) o).longValue()).toList(), "adding references the item");
        assertEquals(List.of(guestId), row("SELECT added_by_guest_id FROM queue_entries WHERE id = ?", e2));

        reload();
        assertEquals(List.of(e2, e3), order(), "entries survive a reload");
        assertEquals(a1.entryId(), current());

        // Refusals.
        assertEquals("identity_required", refused(() -> add(RoomActor.ANONYMOUS, i1)));
        assertEquals("cannot_request", refused(() -> add(RoomActor.guest(guestId, false, true), i1)));
        assertEquals("cannot_request", refused(() -> add(RoomActor.user(carolId, Rooms.Role.BANNED), i1)));
        assertEquals("item_not_found", refused(() -> add(owner, UuidV7.newId())));
        assertEquals("item_not_found", refused(() -> add(owner, "not-an-id")));
        assertEquals("item_failed", refused(() -> add(owner, item("failed", null))));
        assertEquals("media_expired", refused(() -> add(owner, item("purged", null))));
        assertEquals("invalid_source", refused(() -> queue.add(roomId, aliceActor, owner,
                new RoomQueue.Source(i1, "x", null))));
        // A guest needs the capability of the request path it uses (server AND room setting).
        RoomActor uploadOnly = new RoomActor(null, guestId, null, false, true, false, true);
        assertEquals("guest_library_requests_disabled", refused(() -> add(uploadOnly, i1)));
        assertEquals("guest_youtube_disabled", refused(() -> queue.add(roomId, guestActor, uploadOnly,
                RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"))));
        RoomActor libraryOnly = new RoomActor(null, guestId, null, true, false, false, true);
        assertEquals("guest_uploads_disabled", refused(() -> queue.add(roomId, guestActor, libraryOnly,
                RoomQueue.Source.upload(UuidV7.newId()))));
    }

    @Test
    void requestsByIdFollowTheRequestScope() throws Exception {
        String owners = ready();
        String bobs = ready();
        sql("DELETE FROM library_entries WHERE item_id = ?", bobs);
        sql("INSERT INTO library_entries (user_id, item_id, added_at, via) VALUES (?, ?, 1, 'upload')", bobId, bobs);
        String listed = ready();
        sql("DELETE FROM library_entries WHERE item_id = ?", listed);
        String playlist = UuidV7.newId();
        sql("INSERT INTO playlists (id, owner_id, name, created_at, updated_at) VALUES (?, ?, 'Mix', 1, 1)",
                playlist, aliceId);
        sql("INSERT INTO playlist_entries (id, playlist_id, item_id, position, added_at) VALUES (?, ?, ?, 1, 1)",
                UuidV7.newId(), playlist, listed);
        // owner (the default): the owner's library and playlists
        assertEquals("not_in_request_scope", refused(() -> add(bob, bobs)));
        assertEquals("not_in_request_scope", refused(() -> add(guest, bobs)));
        assertEquals("not_in_request_scope", refused(() -> add(bob, UuidV7.newId())), "same as out of scope");
        add(guest, owners);
        add(bob, listed);
        // owner+self: also the requester's own library
        settings("{\"requestScope\":\"owner+self\"}");
        String e = add(bob, bobs);
        assertEquals("not_in_request_scope", refused(() -> add(carol, bobs)), "not someone else's");
        queue.remove(roomId, bob, e);
        // none: no requests by id, but uploads and links still work; owner and moderators add anything
        settings("{\"requestScope\":\"none\"}");
        assertEquals("library_requests_disabled", refused(() -> add(bob, owners)));
        assertEquals("library_requests_disabled", refused(() -> add(guest, owners)));
        add(owner, bobs);
        add(RoomActor.user(carolId, Rooms.Role.MODERATOR), ready());
    }

    @Test
    void ownerModeTurnsRequestsIntoSuggestions() throws Exception {
        mode("owner");
        String s = add(bob, ready());
        assertEquals("suggested", stateOf(s));
        assertNull(current(), "a suggestion doesn't start the room");
        assertEquals(List.of(), order());
        String q = add(owner, ready());
        assertEquals(q, current(), "the owner adds to the queue");
        reload();
        assertEquals(List.of(s), call(tx -> tx.state().suggestions().stream().map(QueueEntry::id).toList()));
    }

    @Test
    void requestsPerActorLimitsWaitingEntries() throws Exception {
        String playing = add(bob, ready());
        assertEquals(playing, current());
        add(bob, ready());
        add(bob, ready());
        String third = add(bob, ready());
        assertEquals("request_limit", refused(() -> add(bob, ready())), "3 waiting (the playing one doesn't count)");
        add(guest, ready()); // per actor
        add(owner, ready()); // owner and moderators are exempt
        add(owner, ready());
        add(owner, ready());
        add(owner, ready());

        // A removed entry frees a slot.
        queue.remove(roomId, bob, third);
        add(bob, ready());

        settings("{\"requestsPerActor\":1}");
        add(carol, ready());
        assertEquals("request_limit", refused(() -> add(carol, ready())));
        add(RoomActor.user(carolId, Rooms.Role.MODERATOR), ready()); // as a moderator: exempt
        add(RoomActor.user(carolId, Rooms.Role.MODERATOR), ready());
        // In owner mode suggestions count too.
        mode("owner");
        assertEquals("request_limit", refused(() -> add(carol, ready())));
        assertEquals("request_limit", refused(() -> queue.add(roomId, guestActor, guest,
                RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"))), "checked before any import");
    }

    @Test
    void noRepeatWindowRefusesRecentlyPlayedItems() throws Exception {
        String song = ready();
        add(bob, song);
        assertEquals("recently_played", refused(() -> add(bob, song)), "the current entry counts");
        add(owner, song); // owner and moderators are exempt

        settings("{\"noRepeatWindow\":2,\"requestsPerActor\":100}");
        List<String> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            others.add(ready());
        }
        // Clear the queue except what plays: the owner's copy of the song plays next.
        call(tx -> QueueOps.next(tx, owner)); // song (again) is current
        assertEquals("recently_played", refused(() -> add(bob, song)));
        for (String other : others) {
            add(owner, other);
        }
        clock.advance(1);
        call(tx -> QueueOps.next(tx, owner));
        assertEquals("recently_played", refused(() -> add(bob, song)), "1 entry since: still in the window of 2");
        clock.advance(1);
        call(tx -> QueueOps.next(tx, owner));
        add(bob, song); // 2 entries since: out of the window

        settings("{\"noRepeatWindow\":0,\"requestsPerActor\":100}");
        add(bob, others.get(1)); // currently playing, but the window is off
    }

    @Test
    void youtubeLinksAndUploadsBecomeEntries() throws Exception {
        String yt = item("ready", "dQw4w9WgXcQ");
        QueueOps.Added a = queue.add(roomId, bobActor, bob, RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ?t=5"));
        assertEquals(yt, a.itemId(), "a cached YouTube item is reused (no yt-dlp here)");
        assertEquals(List.of(), row("SELECT item_id FROM library_entries WHERE user_id = ?", bobId),
                "a room request doesn't add to the requester's library");
        assertEquals("invalid_youtube_url", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.youtube("https://example.com/"))));
        settings.set(Settings.YTDLP_ENABLED, false);
        assertEquals("feature_disabled", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.youtube("https://youtu.be/dQw4w9WgXcQ"))));
        settings.set(Settings.YTDLP_ENABLED, true);

        // A purged YouTube item added by id is fetched again (a refetch job), and queued as pending.
        String purged = item("purged", "aaaaaaaaaaa");
        String e = add(bob, purged);
        assertEquals(List.of("pending"), row("SELECT status FROM items WHERE id = ?", purged));
        assertEquals(List.of("refetch"), row("SELECT kind FROM ingest_jobs WHERE item_id = ?", purged));
        assertTrue(order().contains(e));

        // Uploads: the item their complete produced, only the uploader's, only once completed.
        String uploaded = ready();
        String done = UuidV7.newId();
        String open = UuidV7.newId();
        sql("INSERT INTO uploads (id, user_id, filename, size, created_at, updated_at, item_id)"
                + " VALUES (?, ?, 'a.mp3', 10, 1, 1, ?)", done, bobId, uploaded);
        sql("INSERT INTO uploads (id, user_id, filename, size, created_at, updated_at, item_id)"
                + " VALUES (?, ?, 'b.mp3', 10, 1, 1, NULL)", open, bobId);
        assertEquals(uploaded, queue.add(roomId, bobActor, bob, RoomQueue.Source.upload(done)).itemId());
        assertEquals("upload_incomplete", refused(() -> queue.add(roomId, bobActor, bob,
                RoomQueue.Source.upload(open))));
        assertEquals("upload_not_found", refused(() -> queue.add(roomId, aliceActor, owner,
                RoomQueue.Source.upload(done))));
    }

    @Test
    void addPlaylistInOrderOrShuffled() throws Exception {
        String playlistId = playlists.create(aliceId, "Mix", null).id();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String item = ready();
            items.add(item);
            playlists.add(aliceActor, playlistId, item, null, () -> {
            });
        }
        String failed = item("ready", null);
        playlists.add(aliceActor, playlistId, failed, null, () -> {
        });
        sql("UPDATE items SET status = 'failed' WHERE id = ?", failed);

        assertEquals("forbidden", refused(() -> queue.addPlaylist(roomId, bobActor, bob, playlistId, false)));
        assertEquals("playlist_not_found", refused(() -> queue.addPlaylist(roomId, aliceActor, owner,
                UuidV7.newId(), false)));

        QueueOps.PlaylistAdded added = queue.addPlaylist(roomId, aliceActor, owner, playlistId, false);
        assertEquals(8, added.entryIds().size());
        assertEquals(1, added.skipped(), "the failed item is left out");
        assertEquals(added.entryIds().get(0), current());
        List<String> playedItems = new ArrayList<>();
        playedItems.add(call(tx -> tx.state().current().itemId()));
        playedItems.addAll(call(tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::itemId).toList()));
        assertEquals(items, playedItems, "in playlist order");

        mode("open");
        QueueOps.PlaylistAdded shuffled = queue.addPlaylist(roomId, aliceActor, owner, playlistId, true);
        List<String> shuffledItems = call(tx -> {
            List<String> out = new ArrayList<>();
            for (String id : shuffled.entryIds()) {
                out.add(tx.state().waitingEntry(id).itemId());
            }
            return out;
        });
        assertEquals(new HashSet<>(items), new HashSet<>(shuffledItems));
        assertFalse(items.equals(shuffledItems), "shuffled (seeded)");
        List<String> tail = order().subList(order().size() - 8, order().size());
        assertEquals(shuffled.entryIds(), tail, "at the end of the queue, in the shuffled order");
    }

    @Test
    void removeByRequesterModeratorsOrAnyoneInOpenMode() throws Exception {
        add(owner, ready()); // playing
        String byBob = add(bob, ready());
        String byGuest = add(guest, ready());
        assertEquals("forbidden", refused(() -> queue.remove(roomId, guest, byBob)));
        assertEquals("identity_required", refused(() -> queue.remove(roomId, RoomActor.ANONYMOUS, byBob)));
        clock.advance(10);
        queue.remove(roomId, bob, byBob);
        assertEquals("removed", stateOf(byBob));
        assertEquals(List.of(byGuest), order());
        assertEquals("entry_not_found", refused(() -> queue.remove(roomId, bob, byBob)));
        assertEquals("entry_not_found", refused(() -> queue.remove(roomId, owner, current())),
                "the current entry is skipped, not removed");

        mode("open");
        queue.remove(roomId, bob, byGuest);
        assertEquals(List.of(), order());
        reload();
        assertEquals(List.of(), order());
    }

    @Test
    void moveReordersAManualQueue() throws Exception {
        mode("open");
        add(owner, ready()); // playing
        String a = add(owner, ready());
        String b = add(owner, ready());
        String c = add(owner, ready());
        String d = add(bob, ready());
        assertTrue(queue.move(roomId, bob, d, a));
        assertEquals(List.of(d, a, b, c), order());
        assertTrue(queue.move(roomId, bob, d, null));
        assertEquals(List.of(a, b, c, d), order());
        assertFalse(queue.move(roomId, bob, d, null), "already last");
        assertFalse(queue.move(roomId, bob, a, b), "already before B");
        assertFalse(queue.move(roomId, bob, a, a));
        assertTrue(queue.move(roomId, bob, c, b));
        assertEquals(List.of(a, c, b, d), order());
        assertEquals("before_entry_not_found", refused(() -> queue.move(roomId, bob, a, UuidV7.newId())));
        assertEquals("entry_not_found", refused(() -> queue.move(roomId, bob, UuidV7.newId(), a)));
        assertEquals("cannot_request", refused(() -> queue.move(roomId, RoomActor.guest(guestId, false, true), a, b)));
        reload();
        assertEquals(List.of(a, c, b, d), order(), "positions persist");

        // No room left between two neighbours: the queue is numbered again.
        sql("UPDATE queue_entries SET position = ? WHERE id = ?", 1.0, a);
        sql("UPDATE queue_entries SET position = ? WHERE id = ?", Math.nextUp(1.0), c);
        sql("UPDATE queue_entries SET position = ? WHERE id = ?", 5.0, b);
        sql("UPDATE queue_entries SET position = ? WHERE id = ?", 6.0, d);
        reload();
        assertTrue(queue.move(roomId, bob, d, c));
        assertEquals(List.of(a, d, c, b), order());
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0), List.of(a, d, c, b).stream().map(id -> {
            try {
                return ((Number) row("SELECT position FROM queue_entries WHERE id = ?", id).get(0)).doubleValue();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).toList());

        mode("owner");
        assertEquals("forbidden", refused(() -> queue.move(roomId, bob, a, null)));
        assertTrue(queue.move(roomId, owner, a, null));
        mode("vote");
        assertEquals("wrong_control_mode", refused(() -> queue.move(roomId, owner, a, null)));
    }

    @Test
    void playPauseSeekNextFollowWhoCanSkip() throws Exception {
        String e1 = add(owner, ready());
        String e2 = add(owner, ready());
        // Vote mode: owner and moderators only.
        assertEquals("forbidden", refused(() -> queue.pause(roomId, bob)));
        assertEquals("forbidden", refused(() -> queue.next(roomId, guest)));
        assertEquals("identity_required", refused(() -> queue.play(roomId, RoomActor.ANONYMOUS)));
        clock.advance(10_000);
        assertTrue(queue.pause(roomId, owner));
        assertFalse(queue.pause(roomId, owner), "already paused");
        assertEquals(new Timeline(PlaybackState.PAUSED, e1, clock.millis(), 10_000), timeline());
        queue.seek(roomId, RoomActor.user(carolId, Rooms.Role.MODERATOR), 30_000);
        assertEquals(new Timeline(PlaybackState.PAUSED, e1, clock.millis(), 30_000), timeline());
        assertEquals("invalid_position", refused(() -> queue.seek(roomId, owner, -1)));
        assertTrue(queue.play(roomId, owner));
        assertEquals(new Timeline(PlaybackState.PLAYING, e1, clock.millis(), 30_000), timeline());

        queue.next(roomId, owner);
        assertEquals("skipped", stateOf(e1));
        assertEquals(e2, current());
        queue.next(roomId, owner);
        assertNull(current());
        assertEquals("nothing_playing", refused(() -> queue.next(roomId, owner)));
        assertEquals("nothing_playing", refused(() -> queue.seek(roomId, owner, 0)));

        // Open mode: anyone with request rights.
        mode("open");
        add(bob, ready());
        assertTrue(queue.pause(roomId, bob));
        assertTrue(queue.play(roomId, guest));
        assertEquals("forbidden", refused(() -> queue.pause(roomId, RoomActor.guest(guestId, false, true))));
    }

    @Test
    void previousGoesBackOrRestarts() throws Exception {
        String e1 = add(owner, ready());
        String e2 = add(owner, ready());
        String e3 = add(owner, ready());
        clock.advance(1);
        queue.next(roomId, owner);
        assertEquals(e2, current());

        // More than 3 s in: restart the current entry.
        clock.advance(5_000);
        assertTrue(queue.previous(roomId, owner));
        assertEquals(e2, current());
        assertEquals(new Timeline(PlaybackState.PLAYING, e2, clock.millis(), 0), timeline());

        // Within 3 s: back to E1; E2 goes to the front of the queue (in vote mode: the top pin).
        call(tx -> QueueControl.vote(tx, bob, e3, 1));
        clock.advance(1_000);
        assertTrue(queue.previous(roomId, owner));
        assertEquals(e1, current());
        assertEquals("playing", stateOf(e1));
        assertEquals(List.of(e2, e3), order());
        assertEquals(List.of("queued", 1), List.of(stateOf(e2), ((Number) row(
                "SELECT pinned FROM queue_entries WHERE id = ?", e2).get(0)).intValue()));
        assertEquals("forbidden", refused(() -> queue.previous(roomId, bob)));

        reload();
        assertEquals(e1, current());
        assertEquals(List.of(e2, e3), order());

        // Nothing before E1 now (E2 is queued again): restart.
        clock.advance(1_000);
        assertTrue(queue.previous(roomId, owner));
        assertEquals(e1, current());
        assertEquals(new Timeline(PlaybackState.PLAYING, e1, clock.millis(), 0), timeline());

        // A paused room stays paused when it goes back.
        clock.advance(1);
        queue.next(roomId, owner); // E2
        queue.pause(roomId, owner);
        clock.advance(1);
        assertTrue(queue.previous(roomId, owner));
        assertEquals(new Timeline(PlaybackState.PAUSED, e1, clock.millis(), 0), timeline());

        // An idle room plays the last entry again.
        mode("open");
        call(tx -> {
            while (tx.state().current() != null) {
                QueueOps.next(tx, owner);
            }
            return null;
        });
        assertNull(current());
        String last = (String) row("SELECT id FROM queue_entries WHERE room_id = ? ORDER BY played_at DESC, id DESC"
                + " LIMIT 1", roomId).get(0);
        assertTrue(queue.previous(roomId, owner));
        assertEquals(last, current());
    }
}
