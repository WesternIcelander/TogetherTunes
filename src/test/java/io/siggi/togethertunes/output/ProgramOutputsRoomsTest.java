package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.rooms.Playback;
import io.siggi.togethertunes.rooms.PlaybackState;
import io.siggi.togethertunes.rooms.QueueEntry;
import io.siggi.togethertunes.rooms.RoomRegistry;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProgramOutputs} as the rooms' packager hook (SQLite, real {@link RoomRegistry}): an output
 * started without having seen a change reads the room; committed changes reach the running output
 * as cues (item kinds from the database, {@code playedOut} after a natural end); deleting the room
 * stops its output.
 */
class ProgramOutputsRoomsTest {
    @TempDir
    Path dir;

    final TestClock clock = new TestClock(1_800_000_000_000L);
    Database db;
    Rooms rooms;
    RoomRegistry registry;
    ProgramOutputs outputs;
    String userId;
    Actor.User alice;
    String roomId;
    final LinkedBlockingQueue<ProgramCue> updates = new LinkedBlockingQueue<>();
    volatile ProgramCue initial;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
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
        outputs = new ProgramOutputs((room, cue) -> {
            initial = cue;
            return fakeOutput(room);
        }, ProgramOutputs.itemKindsFrom(db), clock::millis, 0);
        registry = new RoomRegistry(db, clock, outputs);
        outputs.snapshotsFrom(registry);
    }

    @AfterEach
    void tearDown() {
        if (outputs != null) {
            outputs.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    ProgramOutputs.Output fakeOutput(String room) {
        TimeSource time = TimeSource.system(clock);
        PacingClock pacing = new PacingClock(time);
        Packager p = new Packager(room, new UnitProducer() {
            @Override
            public long nextUnitStart() {
                return OutputClock.START + 90_000L * 3600; // never emits during the test
            }

            @Override
            public Unit nextUnit() throws IOException {
                throw new IOException("unused");
            }

            @Override
            public void close() {
            }
        }, u -> new byte[0], pacing, time);
        p.start();
        return new ProgramOutputs.Output() {
            @Override
            public Packager packager() {
                return p;
            }

            @Override
            public void update(ProgramCue cue) {
                updates.add(cue);
            }

            @Override
            public void close() {
                p.close();
            }
        };
    }

    /** A queued entry with a new ready item of {@code kind}. */
    String entry(String kind, boolean presentAsAudio, long durationMs, double position) throws SQLException {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms,"
                    + " present_as_audio) VALUES (?, ?, 'Song', 'upload', ?, 1, 'ready', '720p30', 1, ?, ?)")) {
                ps.setString(1, itemId);
                ps.setString(2, kind);
                ps.setString(3, userId);
                ps.setLong(4, durationMs);
                ps.setInt(5, presentAsAudio ? 1 : 0);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_at, state, position, pinned) VALUES (?, ?, ?, ?, 1, 'queued', ?, 0)")) {
                ps.setString(1, entryId);
                ps.setString(2, roomId);
                ps.setString(3, itemId);
                ps.setString(4, userId);
                ps.setDouble(5, position);
                ps.executeUpdate();
            }
            return null;
        });
        return entryId;
    }

    ProgramCue nextUpdate() throws InterruptedException {
        ProgramCue c = updates.poll(5, TimeUnit.SECONDS);
        assertNotNull(c, "no cue reached the output");
        return c;
    }

    @Test
    void followsTheRoom() throws Exception {
        String video = entry("video", false, 60_000, 1);
        String asAudio = entry("video", true, 30_000, 2);
        String audio = entry("audio", false, 20_000, 3);
        registry.call(roomId, Playback::startIfIdle);

        // started without a change seen: the room is read
        ProgramOutputs.Lease lease = outputs.acquire(roomId);
        assertEquals(PlaybackState.PLAYING, initial.state());
        assertEquals(video, initial.current().entryId());
        assertTrue(initial.current().videoItem());
        assertEquals(60_000, initial.current().durationMs());
        assertEquals(asAudio, initial.next().entryId());
        assertTrue(initial.next().presentAsAudio());
        assertNull(initial.playedOut());

        clock.advance(5000);
        registry.call(roomId, Playback::pause);
        ProgramCue c = nextUpdate();
        assertEquals(PlaybackState.PAUSED, c.state());
        assertEquals(5000, c.anchorPositionMs());

        registry.call(roomId, Playback::play);
        assertEquals(PlaybackState.PLAYING, nextUpdate().state());

        // natural end: the next entry, and the one that ended is played out
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.PLAYED));
        c = nextUpdate();
        assertEquals(asAudio, c.current().entryId());
        assertTrue(c.current().videoItem() && c.current().presentAsAudio());
        assertEquals(audio, c.next().entryId());
        assertEquals(video, c.playedOut());
        // a later change that doesn't move the timeline keeps it
        entry("audio", false, 10_000, 4);
        registry.call(roomId, Playback::itemsChanged);
        // a skip clears it
        registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.SKIPPED));
        ProgramCue skipped;
        do {
            skipped = nextUpdate();
            if (skipped.current() != null && skipped.current().entryId().equals(asAudio)) {
                assertEquals(video, skipped.playedOut());
            }
        } while (!skipped.current().entryId().equals(audio));
        assertNull(skipped.playedOut());
        assertTrue(!skipped.current().videoItem());
        lease.close();

        // deleting the room stops its output
        // (deleted behind the executor's back: its next commit finds the row gone and closes as deleted)
        rooms.delete(alice, roomId, "203.0.113.9");
        try {
            registry.call(roomId, Playback::pause);
        } catch (Exception expected) {
            // 404
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (outputs.running(roomId) != null && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertNull(outputs.running(roomId));
    }
}
