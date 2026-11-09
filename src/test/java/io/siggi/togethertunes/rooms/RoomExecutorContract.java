package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RoomExecutor} and {@link RoomRegistry}: mutate → persist → bump version → broadcast →
 * packager hook, in that order, one mutation at a time; read-only mutations change nothing;
 * failures roll the in-memory state back to the committed one; deletion closes the executor.
 */
abstract class RoomExecutorContract {
    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(1_800_000_000_000L);
    private Database db;
    private Rooms rooms;
    private RoomRegistry registry;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final LinkedBlockingQueue<RoomChange> packaged = new LinkedBlockingQueue<>();
    private Actor.User alice;
    private String roomId;
    private String userId;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        Permissions permissions = new Permissions(db, new Settings(db, config));
        rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        userId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        alice = new Actor.User(userId, "s-alice", Actor.Via.BEARER);
        roomId = rooms.create(alice, "Party", null, null, null, "203.0.113.9").id();
        registry = newRegistry();
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

    private RoomRegistry newRegistry() {
        return new RoomRegistry(db, clock, new PackagerHook() {
            @Override
            public void roomChanged(RoomChange change) {
                calls.add("packager " + change.version());
                packaged.add(change);
            }

            @Override
            public void roomClosed(String id, boolean deleted) {
                calls.add("packager closed " + deleted);
            }
        });
    }

    /** A queue entry (with a new ready item) in the room, for timelines that point at an entry. */
    private String entry() throws SQLException {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', '720p30', 1)")) {
                ps.setString(1, itemId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_at, state, position, pinned) VALUES (?, ?, ?, ?, 1, 'queued', 1, 0)")) {
                ps.setString(1, entryId);
                ps.setString(2, roomId);
                ps.setString(3, itemId);
                ps.setString(4, userId);
                ps.executeUpdate();
            }
            return null;
        });
        return entryId;
    }

    /** The persisted (version, playback_state, current_entry_id, anchor_server_ms, anchor_position_ms). */
    private List<Object> row() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT version, playback_state, current_entry_id,"
                    + " anchor_server_ms, anchor_position_ms FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return java.util.Arrays.asList(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5));
                }
            }
        });
    }

    private RoomListener recorder(List<RoomChange> changes) {
        return new RoomListener() {
            @Override
            public void onChange(RoomState state, RoomChange change) {
                calls.add("listener " + change.version() + " state.version=" + state.version());
                changes.add(change);
            }

            @Override
            public void onClosed(String id, boolean deleted) {
                calls.add("listener closed " + deleted);
            }
        };
    }

    @Test
    void mutationPersistsBumpsBroadcastsThenNotifiesPackager() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        List<RoomChange> changes = Collections.synchronizedList(new ArrayList<>());
        ex.addListener(recorder(changes));
        String entryId = entry();
        Timeline playing = new Timeline(PlaybackState.PLAYING, entryId, clock.millis(), 12_000);

        String result = registry.call(roomId, tx -> {
            assertTrue(ex.onExecutorThread());
            assertEquals(0, tx.state().version());
            assertEquals(clock.millis(), tx.now());
            tx.timeline(playing);
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(List.of(1L, "playing", entryId, clock.millis(), 12_000L), row());
        assertEquals(List.of("listener 1 state.version=1", "packager 1"), calls);
        assertEquals(1, changes.size());
        RoomChange change = changes.get(0);
        assertEquals(roomId, change.roomId());
        assertEquals(1, change.version());
        assertEquals(playing, change.timeline());
        assertTrue(change.timelineChanged());
        assertEquals(List.of(new RoomEvent.TimelineChanged()), change.events());
        assertSame(change, packaged.poll());
        assertEquals(1L, (long) registry.call(roomId, tx -> tx.state().version()));
        assertFalse(ex.onExecutorThread());
    }

    @Test
    void mutationWritesShareTheTransactionWithTheVersionBump() throws Exception {
        registry.call(roomId, tx -> tx.persist(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET settings_json = ? WHERE id = ?")) {
                ps.setString(1, "{\"noRepeatWindow\":5}");
                ps.setString(2, roomId);
                return ps.executeUpdate();
            }
        }));
        assertEquals(1L, row().get(0));
        assertEquals("{\"noRepeatWindow\":5}", db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT settings_json FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }));
        assertEquals(List.of("packager 1"), calls);
    }

    @Test
    void readOnlyMutationChangesNothing() throws Exception {
        List<RoomChange> changes = Collections.synchronizedList(new ArrayList<>());
        registry.executor(roomId).addListener(recorder(changes));
        assertEquals("Party", registry.call(roomId, tx -> tx.state().name()));
        assertEquals(java.util.Arrays.asList(0L, "idle", null, 0L, 0L), row());
        assertTrue(changes.isEmpty());
        assertTrue(calls.isEmpty());
    }

    @Test
    void mutationsRunOneAtATimeInOrderWithConsecutiveVersions() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        List<RoomChange> changes = Collections.synchronizedList(new ArrayList<>());
        ex.addListener(recorder(changes));
        String entryId = entry();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        int n = 200;
        List<CompletableFuture<Long>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < n / 8; j++) {
                        CompletableFuture<Long> f = ex.submit(tx -> {
                            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Timeline t = tx.state().timeline();
                            long next = t.anchorPositionMs() + 1;
                            Thread.yield();
                            tx.timeline(new Timeline(PlaybackState.PAUSED, entryId, tx.now(), next));
                            inside.decrementAndGet();
                            return next;
                        });
                        synchronized (futures) {
                            futures.add(f);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        List<Long> results = new ArrayList<>();
        for (CompletableFuture<Long> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        Collections.sort(results);
        List<Long> expected = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            expected.add(i);
        }
        assertEquals(expected, results);
        assertEquals(1, maxInside.get());
        assertEquals(n, changes.size());
        for (int i = 0; i < n; i++) {
            assertEquals(i + 1, changes.get(i).version());
            assertEquals(i + 1, changes.get(i).timeline().anchorPositionMs());
        }
        assertEquals((long) n, row().get(0));
        assertEquals((long) n, row().get(4));
    }

    @Test
    void refusedMutationKeepsStateAndVersion() throws Exception {
        registry.executor(roomId);
        HttpError e = assertThrows(HttpError.class, () -> registry.call(roomId, tx -> {
            throw HttpError.forbidden("no");
        }));
        assertEquals(403, e.status());
        assertEquals(0L, (long) registry.call(roomId, tx -> tx.state().version()));
        assertTrue(calls.isEmpty());
        assertEquals(0L, row().get(0));
    }

    @Test
    void failedWriteRollsMemoryBackToTheCommittedState() throws Exception {
        String entryId = entry();
        Timeline paused = new Timeline(PlaybackState.PAUSED, entryId, clock.millis(), 5_000);
        registry.call(roomId, tx -> tx.timeline(paused));
        RoomExecutor ex = registry.executor(roomId);

        assertThrows(SQLException.class, () -> registry.call(roomId, tx -> {
            tx.timeline(new Timeline(PlaybackState.PLAYING, entryId, clock.millis(), 9_000));
            tx.persist(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE no_such_table SET x = 1")) {
                    return ps.executeUpdate();
                }
            });
            return null;
        }));
        assertSame(ex, registry.executor(roomId), "the executor stays");
        assertEquals(paused, registry.call(roomId, tx -> tx.state().timeline()));
        assertEquals(1L, (long) registry.call(roomId, tx -> tx.state().version()));
        assertEquals(List.of(1L, "paused", entryId, clock.millis(), 5_000L), row());
        assertEquals(List.of("packager 1"), calls);

        // A mutation that fails with something other than a refusal after changing memory: reloaded too.
        assertThrows(IllegalStateException.class, () -> registry.call(roomId, tx -> {
            tx.timeline(Timeline.IDLE);
            throw new IllegalStateException("bug");
        }));
        assertEquals(paused, registry.call(roomId, tx -> tx.state().timeline()));
        assertEquals(List.of("packager 1"), calls);
    }

    @Test
    void callFromTheExecutorThreadIsRefusedButSubmitWorks() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        assertThrows(IllegalStateException.class, () -> registry.call(roomId, tx -> ex.call(t -> null)));
        CompletableFuture<CompletableFuture<String>> nested = new CompletableFuture<>();
        registry.call(roomId, tx -> nested.complete(ex.submit(t -> "later")));
        assertEquals("later", nested.get().get(10, TimeUnit.SECONDS));
    }

    @Test
    void scheduledMutationsRunOnTheExecutorAndCanBeCancelled() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        String entryId = entry();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger cancelledRuns = new AtomicInteger();
        ScheduledFuture<?> cancelled = ex.schedule(Duration.ofMillis(200), tx -> cancelledRuns.incrementAndGet());
        ex.schedule(Duration.ofMillis(20), tx -> {
            assertTrue(ex.onExecutorThread());
            tx.timeline(new Timeline(PlaybackState.PLAYING, entryId, tx.now(), 0));
            ran.countDown();
            return null;
        });
        assertTrue(cancelled.cancel(false));
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        RoomChange change = packaged.poll(10, TimeUnit.SECONDS);
        assertEquals(1, change.version());
        Thread.sleep(400);
        assertEquals(0, cancelledRuns.get());
        assertEquals(1L, row().get(0));
    }

    @Test
    void registryLoadsOnceAndReloadsPersistedStateAfterRestart() throws Exception {
        assertThrows(HttpError.class, () -> registry.executor(UuidV7.newId()));
        HttpError notFound = assertThrows(HttpError.class, () -> registry.call("not-a-room", tx -> null));
        assertEquals(404, notFound.status());
        RoomExecutor ex = registry.executor(roomId);
        assertSame(ex, registry.executor(roomId));
        assertSame(ex, registry.loaded(roomId));
        assertEquals(1, registry.loadedCount());
        String entryId = entry();
        Timeline playing = new Timeline(PlaybackState.PLAYING, entryId, 1_799_999_000_000L, 42_000);
        registry.call(roomId, tx -> tx.timeline(playing));
        registry.call(roomId, tx -> tx.timeline(playing));

        List<RoomChange> closes = new ArrayList<>();
        ex.addListener(recorder(closes));
        registry.close();
        assertTrue(ex.isClosed());
        assertTrue(calls.contains("listener closed false"));
        assertTrue(calls.contains("packager closed false"));
        assertThrows(RoomClosedException.class, () -> ex.call(tx -> null));
        HttpError stopping = assertThrows(HttpError.class, () -> registry.executor(roomId));
        assertEquals(503, stopping.status());

        registry = newRegistry(); // a restart
        assertNull(registry.loaded(roomId));
        RoomExecutor again = registry.executor(roomId);
        assertNotSame(ex, again);
        assertEquals(2L, (long) registry.call(roomId, tx -> tx.state().version()));
        assertEquals(playing, registry.call(roomId, tx -> tx.state().timeline()));
        assertEquals("party", registry.call(roomId, tx -> tx.state().slug()));
        assertEquals(userId, registry.call(roomId, tx -> tx.state().ownerId()));
    }

    @Test
    void closingTheRoomFailsQueuedWorkAndUnloadsIt() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        List<RoomChange> changes = new ArrayList<>();
        ex.addListener(recorder(changes));
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> blocker = ex.submit(tx -> {
            release.await();
            rooms.delete(alice, roomId, "203.0.113.9");
            tx.closeRoom();
            return null;
        });
        CompletableFuture<Object> queued = ex.submit(tx -> tx.timeline(Timeline.IDLE));
        release.countDown();
        blocker.get(10, TimeUnit.SECONDS);
        Exception e = assertThrows(Exception.class, () -> queued.get(10, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof RoomClosedException, String.valueOf(e.getCause()));
        assertTrue(ex.isClosed());
        assertNull(registry.loaded(roomId));
        assertEquals(List.of("listener closed true", "packager closed true"), calls);
        assertTrue(changes.isEmpty());
        HttpError gone = assertThrows(HttpError.class, () -> registry.call(roomId, tx -> null));
        assertEquals(404, gone.status());
        assertNull(row());
    }

    @Test
    void roomDeletedBehindTheExecutorsBackClosesIt() throws Exception {
        RoomExecutor ex = registry.executor(roomId);
        rooms.delete(alice, roomId, "203.0.113.9");
        HttpError e = assertThrows(HttpError.class, () -> registry.call(roomId, tx -> tx.timeline(Timeline.IDLE)));
        assertEquals(404, e.status());
        assertTrue(ex.isClosed());
        assertEquals(List.of("packager closed true"), calls);
    }
}
