package io.siggi.togethertunes.rooms;

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
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room timeline ({@link Playback} on a {@link RoomExecutor}): position math through play,
 * pause and seek; re-anchoring; the advance task rescheduled on every re-anchor; not-ready
 * entries skipped over and picked up once ready; resume after a restart.
 *
 * <p>The executor reads a {@link TestClock}. The advance task is scheduled in real time from that
 * clock, so after moving the clock the tests ask for a reschedule ({@link #tick}), which makes
 * an overdue task run at once.
 */
abstract class PlaybackContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private RoomRegistry registry;
    private final LinkedBlockingQueue<RoomChange> changes = new LinkedBlockingQueue<>();
    private String roomId;
    private String userId;
    private double nextPosition = 1;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        Permissions permissions = new Permissions(db, new Settings(db, config));
        Rooms rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        userId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        Actor.User alice = new Actor.User(userId, "s-alice", Actor.Via.BEARER);
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
        return new RoomRegistry(db, clock, changes::add);
    }

    /** A queued entry for a new item with this status and duration. Returns {entryId, itemId}. */
    private String[] entry(String status, Long durationMs) throws SQLException {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        double position = nextPosition++;
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, 1, ?, '720p30', 1, ?)")) {
                ps.setString(1, itemId);
                ps.setString(2, userId);
                ps.setString(3, status);
                if (durationMs == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, durationMs);
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_at, state, position, pinned) VALUES (?, ?, ?, ?, ?, 'queued', ?, 0)")) {
                ps.setString(1, entryId);
                ps.setString(2, roomId);
                ps.setString(3, itemId);
                ps.setString(4, userId);
                ps.setLong(5, clock.millis());
                ps.setDouble(6, position);
                ps.executeUpdate();
            }
            return null;
        });
        return new String[] {entryId, itemId};
    }

    private void setItem(String itemId, String status, Long durationMs) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = ?, duration_ms = ? WHERE id = ?")) {
                ps.setString(1, status);
                if (durationMs == null) {
                    ps.setNull(2, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(2, durationMs);
                }
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** {@code queue_entries.state} and {@code played_at}. */
    private List<Object> entryRow(String entryId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT state, played_at FROM queue_entries WHERE id = ?")) {
                ps.setString(1, entryId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    long played = rs.getLong(2);
                    return java.util.Arrays.asList(rs.getString(1), rs.wasNull() ? null : played);
                }
            }
        });
    }

    /** The persisted (playback_state, current_entry_id, anchor_server_ms, anchor_position_ms, version). */
    private List<Object> roomRow() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT playback_state, current_entry_id,"
                    + " anchor_server_ms, anchor_position_ms, version FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getLong(5));
                }
            }
        });
    }

    private Timeline timeline() throws Exception {
        return registry.call(roomId, tx -> tx.state().timeline());
    }

    private long advanceDelay() throws Exception {
        return registry.executor(roomId).advanceTaskDelayMs();
    }

    /** Moves the clock and has the executor reschedule its advance task, so an overdue one runs now. */
    private void tick(long ms) throws Exception {
        clock.advance(ms);
        registry.call(roomId, tx -> {
            tx.rescheduleAdvance();
            return null;
        });
    }

    /** Waits for the next committed change whose timeline points at {@code entryId} (null: idle). */
    private RoomChange awaitTimeline(String entryId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            RoomChange c = changes.poll(100, TimeUnit.MILLISECONDS);
            if (c != null && c.timelineChanged() && java.util.Objects.equals(entryId, c.timeline().entryId())) {
                return c;
            }
        }
        throw new AssertionError("no timeline change to " + entryId);
    }

    @Test
    void advancesAtTheEndSkippingEntriesThatAreNotReady() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("processing", null);
        String[] c = entry("ready", 30_000L);
        String[] failed = entry("failed", null);

        assertTrue((boolean) registry.call(roomId, Playback::startIfIdle));
        assertEquals(Timeline.playing(a[0], T0, 0), timeline());
        assertEquals(List.of("playing", T0), entryRow(a[0]));
        assertEquals(List.of("playing", a[0], T0, 0L, 1L), roomRow());
        assertFalse((boolean) registry.call(roomId, Playback::startIfIdle), "not idle any more");
        long delay = advanceDelay();
        assertTrue(delay > 55_000 && delay <= 60_000, "advance task at the end of A: " + delay);

        tick(59_000); // not yet: the task stays
        assertEquals(a[0], timeline().entryId());
        tick(1_000);
        RoomChange toC = awaitTimeline(c[0]);
        assertEquals(Timeline.playing(c[0], T0 + 60_000, 0), toC.timeline());
        assertTrue(toC.events().contains(new RoomEvent.EntryStateChanged(a[0], QueueEntry.State.PLAYED)));
        assertTrue(toC.events().contains(new RoomEvent.EntryStateChanged(c[0], QueueEntry.State.PLAYING)));
        assertEquals(List.of("played", T0), entryRow(a[0]));
        assertEquals(List.of("queued"), entryRow(b[0]).subList(0, 1), "B is skipped over, still queued");
        assertEquals(List.of("playing", T0 + 60_000), entryRow(c[0]));

        // B becomes ready while C plays: it is eligible at the next advance.
        setItem(b[1], "ready", 20_000L);
        tick(30_000);
        assertEquals(Timeline.playing(b[0], T0 + 90_000, 0), awaitTimeline(b[0]).timeline());
        assertEquals("played", entryRow(c[0]).get(0));

        tick(20_000); // only the failed entry is left: idle
        RoomChange idle = awaitTimeline(null);
        assertEquals(Timeline.IDLE, idle.timeline());
        assertEquals("played", entryRow(b[0]).get(0));
        assertEquals("queued", entryRow(failed[0]).get(0));
        assertEquals(List.of("idle"), roomRow().subList(0, 1));
        assertNull(roomRow().get(1));
        assertEquals(-1, advanceDelay());
        assertNull(registry.call(roomId, tx -> tx.state().current()));
    }

    @Test
    void nothingReadyStaysIdleUntilAnItemIsReady() throws Exception {
        String[] a = entry("pending", null);
        assertFalse((boolean) registry.call(roomId, Playback::startIfIdle));
        assertFalse((boolean) registry.call(roomId, Playback::play));
        assertEquals(Timeline.IDLE, timeline());
        assertEquals(0L, roomRow().get(4), "nothing committed");
        setItem(a[1], "ready", 10_000L);
        assertTrue((boolean) registry.call(roomId, Playback::play));
        assertEquals(Timeline.playing(a[0], T0, 0), timeline());
    }

    @Test
    void playPauseSeekReAnchorAndRescheduleTheAdvanceTask() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);

        clock.advance(5_000);
        assertTrue((boolean) registry.call(roomId, Playback::pause));
        assertEquals(Timeline.paused(a[0], T0 + 5_000, 5_000), timeline());
        assertEquals(-1, advanceDelay(), "paused: no advance task");
        assertFalse((boolean) registry.call(roomId, Playback::pause));

        clock.advance(100_000); // paused: the position stays
        assertEquals(5_000, timeline().positionAt(clock.millis()));
        assertTrue((boolean) registry.call(roomId, tx -> Playback.seek(tx, 20_000)));
        assertEquals(Timeline.paused(a[0], T0 + 105_000, 20_000), timeline());
        assertEquals(-1, advanceDelay());

        assertTrue((boolean) registry.call(roomId, Playback::play));
        assertEquals(Timeline.playing(a[0], T0 + 105_000, 20_000), timeline());
        long delay = advanceDelay();
        assertTrue(delay > 35_000 && delay <= 40_000, "rescheduled for the new anchor: " + delay);
        assertFalse((boolean) registry.call(roomId, Playback::play));

        clock.advance(1_000);
        assertTrue((boolean) registry.call(roomId, tx -> Playback.seek(tx, 55_000)));
        assertEquals(Timeline.playing(a[0], T0 + 106_000, 55_000), timeline());
        delay = advanceDelay();
        assertTrue(delay > 0 && delay <= 5_000, "rescheduled after the seek: " + delay);

        assertTrue((boolean) registry.call(roomId, tx -> Playback.seek(tx, -10)));
        assertEquals(0, timeline().anchorPositionMs(), "clamped to the start");

        // Seeking past the end clamps to the end, and the advance task runs at once.
        changes.clear();
        assertTrue((boolean) registry.call(roomId, tx -> Playback.seek(tx, 999_999)));
        assertEquals(Timeline.playing(b[0], T0 + 106_000, 0), awaitTimeline(b[0]).timeline());
        assertEquals("played", entryRow(a[0]).get(0));

        // Every re-anchor bumped the version once and was persisted.
        Timeline t = timeline();
        assertEquals(List.of("playing", b[0], t.anchorServerMs(), 0L), roomRow().subList(0, 4));
    }

    @Test
    void advancingBySkippingMarksTheEntrySkipped() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        QueueEntry now = registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(b[0], now.id());
        assertEquals("skipped", entryRow(a[0]).get(0));
        assertNull(registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED)));
        assertEquals(Timeline.IDLE, timeline());
        assertEquals("skipped", entryRow(b[0]).get(0));
    }

    @Test
    void pinnedEntriesPlayFirstThenByPosition() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        String[] c = entry("ready", 60_000L);
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE queue_entries SET pinned = 1 WHERE id = ?")) {
                ps.setString(1, c[0]);
                ps.executeUpdate();
            }
            return null;
        });
        registry.close();
        registry = newRegistry();
        List<String> order = registry.call(roomId, tx -> {
            List<String> ids = new ArrayList<>();
            for (QueueEntry e : Playback.playOrder(tx.state())) {
                ids.add(e.id());
            }
            return ids;
        });
        assertEquals(List.of(c[0], a[0], b[0]), order);
    }

    @Test
    void resumesAfterARestartFromTheComputedPosition() throws Exception {
        String[] a = entry("ready", 60_000L);
        entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        long version = (long) roomRow().get(4);

        registry.close();
        clock.advance(20_000); // down for 20 s, 40 s left
        registry = newRegistry();
        assertEquals(1, registry.resumePlaying());
        assertNotNull(registry.loaded(roomId), "loaded at start without anyone asking");
        assertEquals(Timeline.playing(a[0], T0, 0), timeline(), "same anchor: position is now 20 s");
        assertEquals(20_000, timeline().positionAt(clock.millis()));
        assertEquals(version, roomRow().get(4), "nothing written");
        long delay = advanceDelay();
        assertTrue(delay > 35_000 && delay <= 40_000, "advance task for the remaining 40 s: " + delay);
    }

    @Test
    void resumesFromTheStartIfDownLongerThanTheRemainingTime() throws Exception {
        String[] a = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        clock.advance(30_000);
        registry.call(roomId, Playback::pause);
        registry.call(roomId, Playback::play); // anchored at 30 s, 30 s left
        long version = (long) roomRow().get(4);

        registry.close();
        clock.advance(45_000);
        registry = newRegistry();
        assertEquals(1, registry.resumePlaying());
        assertEquals(Timeline.playing(a[0], T0 + 75_000, 0), timeline());
        assertEquals(List.of("playing", a[0], T0 + 75_000, 0L, version + 1), roomRow());
        long delay = advanceDelay();
        assertTrue(delay > 55_000 && delay <= 60_000, "advance task for the whole entry: " + delay);
    }

    @Test
    void resumeSkipsACurrentEntryWhoseItemIsGone() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        registry.close();
        setItem(a[1], "purged", 60_000L);
        clock.advance(1_000);
        registry = newRegistry();
        registry.resumePlaying();
        assertEquals(Timeline.playing(b[0], T0 + 1_000, 0), timeline());
        assertEquals("skipped", entryRow(a[0]).get(0));
    }

    @Test
    void pausedAndIdleRoomsAreNotLoadedAtStartAndStayPut() throws Exception {
        String[] a = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        clock.advance(10_000);
        registry.call(roomId, Playback::pause);
        registry.close();
        clock.advance(3_600_000);
        registry = newRegistry();
        assertEquals(0, registry.resumePlaying());
        assertNull(registry.loaded(roomId));
        assertEquals(Timeline.paused(a[0], T0 + 10_000, 10_000), timeline());
        assertEquals(-1, advanceDelay());
    }

    @Test
    void anUnknownStoredStateIsFrozenLikePaused() throws Exception {
        String[] a = entry("ready", 60_000L);
        registry.call(roomId, Playback::startIfIdle);
        registry.close();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET playback_state = 'live' WHERE id = ?")) {
                ps.setString(1, roomId);
                ps.executeUpdate();
            }
            return null;
        });
        clock.advance(120_000);
        registry = newRegistry();
        assertEquals(0, registry.resumePlaying());
        Timeline t = timeline();
        assertEquals(PlaybackState.PAUSED, t.state());
        assertEquals(a[0], t.entryId());
        assertEquals(0, t.positionAt(clock.millis()), "frozen, not treated as playing");
        assertEquals(-1, advanceDelay());
    }

    /** Whether the registry's live set lists each of these items, in order. */
    private List<Boolean> listed(String... itemIds) {
        PlayingItems playing = registry.playingItems();
        List<Boolean> out = new ArrayList<>();
        for (String id : itemIds) {
            out.add(playing.isCurrentOrNext(id));
        }
        return out;
    }

    @Test
    void theLiveSetFollowsTheCurrentAndNextEntries() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("processing", null);
        String[] c = entry("ready", 60_000L);
        String[] d = entry("ready", 60_000L);
        assertEquals(List.of(false, false, false, false), listed(a[1], b[1], c[1], d[1]), "room not loaded");

        registry.call(roomId, tx -> null); // loading the room publishes it
        assertEquals(List.of(true, false, false, false), listed(a[1], b[1], c[1], d[1]), "idle: A is next");

        registry.call(roomId, Playback::startIfIdle);
        // A current; B is first in line but still processing, so C is what plays next: both count.
        assertEquals(List.of(true, true, true, false), listed(a[1], b[1], c[1], d[1]));
        registry.call(roomId, Playback::pause);
        assertEquals(List.of(true, true, true, false), listed(a[1], b[1], c[1], d[1]), "paused: still current");

        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(List.of(false, true, true, true), listed(a[1], b[1], c[1], d[1]));

        // The advance task (a scheduled task, not a call) updates it too.
        registry.call(roomId, Playback::play);
        tick(60_000);
        awaitTimeline(d[0]);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.playingItems().isCurrentOrNext(c[1]) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(List.of(false, true, false, true), listed(a[1], b[1], c[1], d[1]));

        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(Timeline.IDLE, timeline());
        assertEquals(List.of(false, true, false, false), listed(a[1], b[1], c[1], d[1]), "idle: B waits");

        // A failed mutation reloads the state and republishes the committed one.
        assertThrows(IllegalStateException.class, () -> registry.call(roomId, tx -> {
            tx.state().forget(b[0]);
            throw new IllegalStateException("bug");
        }));
        assertEquals(List.of(false, true, false, false), listed(a[1], b[1], c[1], d[1]));

        registry.close();
        assertEquals(List.of(false, false, false, false), listed(a[1], b[1], c[1], d[1]), "closed: withdrawn");
    }
}
