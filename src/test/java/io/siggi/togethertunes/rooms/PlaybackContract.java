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

    /** How far ahead the fake program output can start an item from 0:00; negative: no output running. */
    private volatile long outputStartInMs = -1;

    private RoomRegistry newRegistry() {
        return new RoomRegistry(db, clock, new PackagerHook() {
            @Override
            public void roomChanged(RoomChange change) {
                changes.add(change);
            }

            @Override
            public long itemStartAt(String room, long nowMs) {
                long in = outputStartInMs;
                return in < 0 ? nowMs : nowMs + in;
            }
        });
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

    /** Makes the item a video with an audio-only file of {@code audioOnlyMs} (null: none). */
    private void makeVideo(String itemId, Long audioOnlyMs) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET kind = 'video', audio_only_ms = ?"
                    + " WHERE id = ?")) {
                if (audioOnlyMs == null) {
                    ps.setNull(1, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(1, audioOnlyMs);
                }
                ps.setString(2, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Sets the room's settings in its live state, as {@code RoomsApi} does after a change. */
    private void liveSettings(String json) throws Exception {
        registry.call(roomId, tx -> {
            RoomSettings before = tx.state().settings();
            RoomSettings after = RoomSettings.parse(json);
            tx.state().settings(after);
            tx.event(new RoomEvent.SettingsChanged(after.changedFrom(before)));
            return null;
        });
    }

    private boolean currentAudioOnlyColumn() throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT current_audio_only FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getInt(1) != 0;
                }
            }
        });
    }

    /**
     * Audio-only rooms ({@code docs/rooms.md#audio-only-rooms}): turning the setting on while a
     * video plays keeps that video (and its length) to its end; the next entry, and everything
     * after it, plays audio-only, a video with an audio trim for its audio-only file's length.
     * The current entry's mode is kept across a restart, even when the setting changed meanwhile.
     */
    @Test
    void audioOnlySwitchAppliesFromTheNextEntry() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        String[] c = entry("ready", 45_000L);
        makeVideo(a[1], 20_000L);
        makeVideo(b[1], 20_000L);
        makeVideo(c[1], null);
        registry.close();
        registry = newRegistry();
        registry.call(roomId, Playback::startIfIdle);
        clock.advance(10_000);

        liveSettings("{\"audioOnly\": true}");
        QueueEntry current = registry.call(roomId, tx -> tx.state().current());
        assertEquals(a[0], current.id());
        assertFalse(current.audioOnly(), "the playing video keeps its mode");
        assertEquals(60_000L, current.lengthMs());
        long delay = registry.call(roomId, tx -> Playback.advanceDelayMs(tx.state(), tx.now())).getAsLong();
        assertEquals(50_000, delay, "A still ends after 60 s");
        QueueEntry next = registry.call(roomId, tx -> Playback.nextEligible(tx.state()));
        assertEquals(b[0], next.id());
        assertTrue(next.audioOnly(), "the next entry follows the setting");
        assertEquals(20_000L, next.lengthMs(), "its audio-only file's length");
        assertFalse(currentAudioOnlyColumn());

        tick(50_000);
        assertEquals(Timeline.playing(b[0], T0 + 60_000, 0), awaitTimeline(b[0]).timeline());
        current = registry.call(roomId, tx -> tx.state().current());
        assertTrue(current.audioOnly());
        assertTrue(currentAudioOnlyColumn(), "persisted with the timeline");
        delay = registry.call(roomId, tx -> Playback.advanceDelayMs(tx.state(), tx.now())).getAsLong();
        assertEquals(20_000, delay, "B plays its 20 s audio-only file");

        // switched back off (the stored settings are the defaults) and restarted: B stays audio-only
        registry.close();
        registry = newRegistry();
        current = registry.call(roomId, tx -> tx.state().current());
        assertEquals(b[0], current.id());
        assertTrue(current.audioOnly(), "the current entry's mode survives a restart");
        assertEquals(20_000L, current.lengthMs());
        next = registry.call(roomId, tx -> Playback.nextEligible(tx.state()));
        assertFalse(next.audioOnly());
        assertEquals(45_000L, next.lengthMs());

        // audio-only again: a video without an audio trim plays its whole audio
        liveSettings("{\"audioOnly\": true}");
        next = registry.call(roomId, tx -> Playback.nextEligible(tx.state()));
        assertTrue(next.audioOnly());
        assertEquals(45_000L, next.lengthMs());
        assertFalse(next.playsAudioOnlyFile());
    }

    /**
     * Transitions ({@code docs/rooms.md#transitions}): after a natural end the next entry starts
     * {@code gapMs} later (a delayed start, silent at 0 until then), at least 2 s with the
     * transition card on; a skip and a start from idle get no gap; nothing to play next goes idle
     * at once.
     */
    @Test
    void naturalEndsWaitForTheTransitionGap() throws Exception {
        String[] a = entry("ready", 10_000L);
        String[] b = entry("ready", 10_000L);
        String[] c = entry("ready", 10_000L);
        String[] d = entry("ready", 10_000L);
        liveSettings("{\"gapMs\": 1500}");
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(Timeline.playing(a[0], T0, 0), timeline(), "a start from idle has no gap");

        tick(10_000);
        RoomChange toB = awaitTimeline(b[0]);
        assertEquals(Timeline.startingAt(b[0], T0 + 11_500), toB.timeline());
        assertEquals(List.of("playing", T0 + 10_000), entryRow(b[0]), "B is current at once");
        assertEquals(-1500, toB.timeline().positionAt(T0 + 10_000));
        long delay = advanceDelay();
        assertTrue(delay > 11_000 && delay <= 11_500, "B ends 10 s after its start: " + delay);
        assertEquals(List.of("playing", b[0], T0 + 11_500, 0L), roomRow().subList(0, 4), "persisted");

        // the transition card makes the gap at least 2 s
        liveSettings("{\"gapMs\": 500, \"transitionCard\": true}");
        tick(11_500);
        assertEquals(Timeline.startingAt(c[0], T0 + 23_500), awaitTimeline(c[0]).timeline());

        // a skip has no gap
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(Timeline.startingAt(d[0], T0 + 21_500), timeline());

        // nothing next: idle at once
        tick(10_000);
        awaitTimeline(null);
        assertEquals(Timeline.IDLE, timeline());
        assertEquals("played", entryRow(d[0]).get(0));
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

    /**
     * The rooms' wanted items for the ingest claim order: the current item 0, the queue in play
     * order, then the suggestions; the listener hears every change.
     */
    @Test
    void wantedItemsRankTheWholeQueueByHowSoonItPlays() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("processing", null);
        String[] c = entry("ready", 60_000L);
        String[] s = entry("pending", null);
        db.write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE queue_entries SET state = 'suggested' WHERE id = ?")) {
                ps.setString(1, s[0]);
                ps.executeUpdate();
            }
            return null;
        });
        java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        io.siggi.togethertunes.ingest.WantedItems wanted = registry.wantedItems(changes::incrementAndGet);
        assertEquals(java.util.Map.of(), wanted.ranks(), "room not loaded");

        registry.call(roomId, tx -> null);
        assertEquals(java.util.Map.of(a[1], 1, b[1], 2, c[1], 3, s[1], 4), wanted.ranks());
        int seen = changes.get();
        assertTrue(seen >= 1);

        registry.call(roomId, Playback::startIfIdle);
        assertEquals(java.util.Map.of(a[1], 0, b[1], 1, c[1], 2, s[1], 3), wanted.ranks());
        assertTrue(changes.get() > seen);
        seen = changes.get();
        registry.call(roomId, Playback::pause);
        assertEquals(seen, changes.get(), "nothing changed, nobody is told");

        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(java.util.Map.of(b[1], 1, c[1], 0, s[1], 2), wanted.ranks(), "C plays now; B still waits");
        registry.close();
        assertEquals(java.util.Map.of(), wanted.ranks(), "a closed room wants nothing");
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
    // ---- docs/rooms.md "Entries that aren't ready" (2026-11-10: a not-ready next entry is skipped) ----

    /** The play order of the queued entries (entry ids). */
    private List<String> queueOrder() throws Exception {
        return registry.call(roomId, tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::id).toList());
    }

    /** Ends the current entry: naturally (the advance task at its end) or by {@code playback.skip}. */
    private QueueEntry end(boolean skip) throws Exception {
        if (skip) {
            return registry.call(roomId, tx -> QueueOps.next(tx, RoomActor.user(userId, Rooms.Role.OWNER)));
        }
        return registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.PLAYED));
    }

    private void caseAProcessingFirstEntry(boolean skip) throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] first = entry("processing", null);
        String[] second = entry("ready", 30_000L);
        String[] third = entry("ready", 30_000L);
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(a[0], timeline().entryId());

        // A ends: the processing entry is skipped over, the second plays
        assertEquals(second[0], end(skip).id());
        assertEquals(skip ? "skipped" : "played", entryRow(a[0]).get(0));
        assertEquals("queued", entryRow(first[0]).get(0));
        assertEquals(List.of(first[0], third[0]), queueOrder(), "still at the top of the queue");

        // it turns ready while the second plays: nothing moves until the second ends
        setItem(first[1], "ready", 20_000L);
        registry.call(roomId, Playback::itemsChanged);
        assertEquals(second[0], timeline().entryId());
        assertEquals(first[0], end(skip).id(), "plays next, before the entry queued after it");
        assertEquals(List.of(third[0]), queueOrder());
    }

    @Test
    void aProcessingFirstEntryStaysOnTopAndPlaysOnceReady() throws Exception {
        caseAProcessingFirstEntry(false);
    }

    @Test
    void skipPassesOverAProcessingEntryLikeANaturalEnd() throws Exception {
        caseAProcessingFirstEntry(true);
    }

    private void caseBFailedEntry(boolean skip) throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] failed = entry("failed", null);
        String[] second = entry("ready", 30_000L);
        String[] third = entry("ready", 30_000L);
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(second[0], end(skip).id());
        assertEquals(third[0], end(skip).id(), "a failed entry is never chosen");
        assertEquals(List.of(failed[0]), queueOrder(), "it stays in the queue");
        assertEquals("failed", registry.call(roomId, tx -> tx.state().queue().get(0).itemStatus()));
        assertNull(end(skip), "only the failed entry is left: idle");
        assertEquals(Timeline.IDLE, timeline());
        assertEquals("queued", entryRow(failed[0]).get(0));

        // retried: pending, then ready → the idle room starts it
        setItem(failed[1], "pending", null);
        registry.call(roomId, Playback::itemsChanged);
        assertEquals(Timeline.IDLE, timeline());
        setItem(failed[1], "ready", 20_000L);
        registry.call(roomId, Playback::itemsChanged);
        assertEquals(failed[0], timeline().entryId());
        assertEquals(skip ? "skipped" : "played", entryRow(a[0]).get(0));
    }

    @Test
    void aFailedEntryStaysMarkedFailedAndIsNeverChosenUntilRetried() throws Exception {
        caseBFailedEntry(false);
    }

    @Test
    void skipPassesOverAFailedEntryLikeANaturalEnd() throws Exception {
        caseBFailedEntry(true);
    }

    private void caseCNothingReady(boolean skip) throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("processing", null);
        String[] c = entry("pending", null);
        registry.call(roomId, Playback::startIfIdle);
        assertNull(end(skip), "nothing queued is ready: idle (the Idle card)");
        assertEquals(Timeline.IDLE, timeline());
        assertEquals(List.of(b[0], c[0]), queueOrder());
        assertEquals(skip ? "skipped" : "played", entryRow(a[0]).get(0));

        // the second one is ready first: it starts as today; the first plays after it once ready
        setItem(c[1], "ready", 10_000L);
        assertTrue((boolean) registry.call(roomId, Playback::itemsChanged));
        assertEquals(Timeline.playing(c[0], T0, 0), timeline());
        setItem(b[1], "ready", 10_000L);
        registry.call(roomId, Playback::itemsChanged);
        assertEquals(b[0], end(skip).id());
    }

    @Test
    void nothingReadyGoesIdleUntilOneIsReady() throws Exception {
        caseCNothingReady(false);
    }

    @Test
    void skipWithNothingReadyGoesIdleLikeANaturalEnd() throws Exception {
        caseCNothingReady(true);
    }

    /**
     * (e) A {@code ready} item whose files the program output can't use (files of an older output
     * encoding, an unreadable index: {@code ingest.PlayableFiles}) is skipped over like a
     * processing one, shows as processing, and plays once its files are usable.
     */
    @Test
    void aReadyItemWhoseFilesTheOutputCantUseIsSkippedLikeAProcessingOne() throws Exception {
        java.util.Set<String> unusable = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<io.siggi.togethertunes.ingest.PlayableFiles.Item> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.playableFiles(item -> {
            asked.add(item);
            return !unusable.contains(item.itemId());
        });
        String[] a = entry("ready", 60_000L);
        String[] stale = entry("ready", 30_000L);
        String[] good = entry("ready", 30_000L);
        String[] suggestion = entry("ready", 30_000L);
        String[] later = entry("ready", 30_000L);
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET state = 'suggested' WHERE id = ?")) {
                ps.setString(1, suggestion[0]);
                ps.executeUpdate();
            }
            return null;
        });
        unusable.add(stale[1]);
        unusable.add(suggestion[1]);
        unusable.add(later[1]);
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(a[0], timeline().entryId());
        assertTrue(asked.stream().anyMatch(i -> i.itemId().equals(stale[1]) && "audio".equals(i.kind())
                && "720p30".equals(i.outputProfile()) && i.mediaVersion() == 0), asked.toString());

        // shown as processing (entries and suggestions alike), skipped over, kept at the top
        QueueEntry staleEntry = registry.call(roomId, tx -> tx.state().waitingEntry(stale[0]));
        assertFalse(staleEntry.ready());
        assertEquals("ready", staleEntry.itemStatus());
        assertEquals("processing", RoomViews.entryStatus(staleEntry));
        assertEquals("processing", RoomViews.entryStatus(registry.call(roomId,
                tx -> tx.state().waitingEntry(suggestion[0]))));
        assertEquals(good[0], registry.call(roomId, tx -> Playback.nextEligible(tx.state())).id(),
                "the next entry the program output preloads");
        assertEquals(good[0], end(false).id());
        assertEquals(List.of(stale[0], later[0]), queueOrder());

        // the re-encode publishes: the room hears it (item status listener) and the entry is ready again
        unusable.remove(stale[1]);
        assertTrue((boolean) registry.call(roomId, Playback::itemsChanged));
        assertEquals("ready", RoomViews.entryStatus(registry.call(roomId, tx -> tx.state().waitingEntry(stale[0]))));
        assertEquals(stale[0], end(true).id(), "skip behaves the same");

        // only unusable files left: idle; it starts as soon as they are usable
        assertNull(end(false));
        assertFalse((boolean) registry.call(roomId, Playback::startIfIdle));
        assertEquals(Timeline.IDLE, timeline());
        unusable.remove(later[1]);
        registry.call(roomId, Playback::itemsChanged);
        assertEquals(later[0], timeline().entryId());
    }

    /**
     * Abrupt starts ({@code docs/rooms.md#room-state}): while the program output runs, a skip or a
     * start from idle starts the next entry at the output's next unit it can play it from 0:00
     * (at most 4 s ahead); without an output at once. A second skip in the gap skips again by the
     * same rule; a pause in the gap pauses at 0; natural ends start the next entry at once.
     */
    @Test
    void abruptStartsWaitForTheProgramOutput() throws Exception {
        String[] a = entry("ready", 60_000L);
        String[] b = entry("ready", 60_000L);
        String[] c = entry("ready", 60_000L);
        String[] d = entry("ready", 30_000L);
        String[] e = entry("ready", 30_000L);
        registry.close();
        registry = newRegistry();

        // no output: a start from idle and a skip are immediate
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(Timeline.playing(a[0], T0, 0), timeline());
        clock.advance(5_000);
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(Timeline.playing(b[0], T0 + 5_000, 0), timeline());

        // the output runs: a skip starts the next entry when the output reaches it
        outputStartInMs = 2_600;
        clock.advance(5_000);
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        Timeline t = timeline();
        assertEquals(Timeline.startingAt(c[0], T0 + 12_600), t);
        assertEquals(PlaybackState.PLAYING, t.state());
        assertEquals(-2_600, t.positionAt(clock.millis()), "silent until it starts");
        assertEquals(0, t.positionAt(clock.millis(), 60_000));
        assertEquals(62_600, registry.call(roomId, tx -> Playback.advanceDelayMs(tx.state(), tx.now())).getAsLong(),
                "it ends 60 s after it starts");
        List<Object> row = roomRow();
        assertEquals(List.of(c[0], T0 + 12_600, 0L), row.subList(1, 4), "persisted as is");

        // a second skip in the gap: skips again, by the same rule
        clock.advance(1_000);
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(Timeline.startingAt(d[0], T0 + 13_600), timeline());
        assertEquals("skipped", entryRow(c[0]).get(0));

        // a pause in the gap pauses at 0; play resumes at once
        assertTrue((boolean) registry.call(roomId, Playback::pause));
        assertEquals(Timeline.paused(d[0], T0 + 11_000, 0), timeline());
        assertTrue((boolean) registry.call(roomId, Playback::play));
        assertEquals(Timeline.playing(d[0], T0 + 11_000, 0), timeline());

        // a natural end starts the next entry at once, also while the output runs
        tick(30_000);
        Timeline natural = awaitTimeline(e[0]).timeline();
        assertEquals(Timeline.playing(e[0], T0 + 41_000, 0), natural);

        // capped at 4 s; the queue runs empty: idle; adding to an idle room is an abrupt start too
        outputStartInMs = 60_000;
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        assertEquals(Timeline.IDLE, timeline());
        String[] f = entry("ready", 30_000L);
        registry.close();
        registry = newRegistry(); // reads the new entry
        registry.call(roomId, Playback::startIfIdle);
        assertEquals(Timeline.startingAt(f[0], T0 + 41_000 + RoomTx.MAX_START_DELAY_MS), timeline());
    }
}
