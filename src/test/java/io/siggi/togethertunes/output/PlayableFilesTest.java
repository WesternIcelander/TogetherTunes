package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.IngestJob;
import io.siggi.togethertunes.ingest.IngestJobs;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.ingest.PlayableFiles;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.rooms.Playback;
import io.siggi.togethertunes.rooms.QueueEntry;
import io.siggi.togethertunes.rooms.RoomRegistry;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.rooms.Timeline;
import io.siggi.togethertunes.settings.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayableFiles}: the one notion of "playable" the rooms and the packager share
 * ({@code docs/rooms.md#room-state}, "Entries that aren't ready"; {@code docs/program-output.md}).
 * A {@code ready} item whose files the program output can't use (an older encoding waiting for its
 * re-encode, {@code index.bin} missing or unreadable) isn't chosen by a room, so the output never
 * sits on Loading for it; once a re-encode publishes usable files the room picks it up.
 */
class PlayableFilesTest {
    static final OutputEncoding ENC = OutputEncoding.defaultFor(OutputProfile.P720_30);
    static final OutputEncoding OTHER = OutputEncoding.defaultFor(OutputProfile.P1080_30);

    @TempDir
    Path media;

    final AtomicReference<OutputEncoding> encoding = new AtomicReference<>(ENC);
    final AtomicReference<byte[]> reference = new AtomicReference<>(SourcesTest.AVCC);
    Database db;
    RoomRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        if (db != null) {
            db.close();
        }
    }

    PlayableFiles check() {
        return new PlayableFiles((id, v) -> MediaVersions.versionDir(media.resolve(id), v), encoding::get,
                reference::get);
    }

    Path write(String itemId, int version, SourcesTest.Media m, SampleIndex index) throws IOException {
        Path dir = Files.createDirectories(MediaVersions.versionDir(media.resolve(itemId), version));
        for (int i = 0; i < m.names.size(); i++) {
            Files.write(dir.resolve(m.names.get(i)), m.files.get(i));
        }
        index.write(dir.resolve(SampleIndex.FILE_NAME));
        if (!m.names.contains("card.mp4")) {
            Files.write(dir.resolve("card.mp4"), new byte[] {1});
        }
        return dir;
    }

    static SampleIndex indexFor(SourcesTest.Media m, OutputEncoding enc, byte[] avcC) {
        return new SampleIndex(enc, m.names, m.tracks, avcC);
    }

    static PlayableFiles.Item audio(String id, int version) {
        return new PlayableFiles.Item(id, "audio", ENC.id(), version);
    }

    @Test
    void filesOfTheCurrentEncodingWithAReadableIndexAreUsable() throws IOException {
        PlayableFiles check = check();
        SourcesTest.Media a = SourcesTest.audioItem();
        write("a", 0, a, a.index());
        assertTrue(check.usable(audio("a", 0)));
        SourcesTest.Media v = SourcesTest.videoItem();
        write("v", 3, v, v.index());
        assertTrue(check.usable(new PlayableFiles.Item("v", "video", ENC.id(), 3)), "versioned dir v3");
        assertFalse(check.usable(new PlayableFiles.Item("v", "video", ENC.id(), 0)), "another version: no files");
    }

    @Test
    void anOlderEncodingIsNotUsableUntilItsReencodePublishes() throws IOException {
        PlayableFiles check = check();
        SourcesTest.Media a = SourcesTest.audioItem();
        write("a", 0, a, indexFor(a, OTHER, SourcesTest.AVCC));
        assertFalse(check.usable(new PlayableFiles.Item("a", "audio", OTHER.id(), 0)), "row says another encoding");
        assertFalse(check.usable(new PlayableFiles.Item("a", "audio", null, 0)));
        assertFalse(check.usable(audio("a", 0)), "row says current, index says otherwise");
        write("a", 1, a, a.index());
        assertTrue(check.usable(audio("a", 1)), "the re-encode's version");
        // the admin switches the encoding: the same files aren't usable any more
        encoding.set(OTHER);
        assertFalse(check.usable(audio("a", 1)));
        assertFalse(check.usable(new PlayableFiles.Item("a", "audio", OTHER.id(), 1)));
    }

    @Test
    void missingOrUnreadableFilesAreNotUsable() throws IOException {
        PlayableFiles check = check();
        SourcesTest.Media a = SourcesTest.audioItem();
        Path dir = write("noindex", 0, a, a.index());
        Files.delete(dir.resolve(SampleIndex.FILE_NAME));
        assertFalse(check.usable(audio("noindex", 0)));

        dir = write("corrupt", 0, a, a.index());
        Files.write(dir.resolve(SampleIndex.FILE_NAME), new byte[] {1, 2, 3, 4, 5});
        assertFalse(check.usable(audio("corrupt", 0)));

        dir = write("noaudio", 0, a, a.index());
        Files.delete(dir.resolve("audio.m4a"));
        assertFalse(check.usable(audio("noaudio", 0)));

        SourcesTest.Media v = SourcesTest.videoItem();
        dir = write("nocard", 0, v, v.index());
        Files.delete(dir.resolve("card.mp4"));
        assertFalse(check.usable(new PlayableFiles.Item("nocard", "video", ENC.id(), 0)),
                "a video item needs card.mp4 to be presented as audio");

        write("avcc", 0, a, indexFor(a, ENC, new byte[] {1, 2, 3}));
        assertFalse(check.usable(audio("avcc", 0)), "avcC differs from the reference (invariant 1)");

        // a positive result is kept per media version (published versions don't change)
        write("ok", 0, a, a.index());
        assertTrue(check.usable(audio("ok", 0)));
        Files.delete(media.resolve("ok").resolve(SampleIndex.FILE_NAME));
        assertTrue(check.usable(audio("ok", 0)));
    }

    // ------------------------------------------------------------------ rooms + packager

    String userId;
    String roomId;

    void openRoom(TestClock clock) throws Exception {
        db = Database.open("jdbc:sqlite:" + media.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, media).load(null).config();
        Permissions permissions = new Permissions(db, new Settings(db, config));
        Rooms rooms = new Rooms(db, permissions, clock);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        userId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        Actor.User alice = new Actor.User(userId, "s-alice", Actor.Via.BEARER);
        roomId = rooms.create(alice, "Party", null, null, null, "203.0.113.9").id();
        registry = new RoomRegistry(db, clock, change -> {
        });
        registry.playableFiles(check()::usable);
    }

    /** A ready audio item (5 s) queued in the room; returns {entryId, itemId}. */
    String[] queued(String outputProfile, double position, TestClock clock) throws Exception {
        String itemId = UuidV7.newId();
        String entryId = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at, duration_ms)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, 1, 'ready', ?, 1, 5000)")) {
                ps.setString(1, itemId);
                ps.setString(2, userId);
                ps.setString(3, outputProfile);
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

    ProgramMedia programMedia() throws IOException {
        Map<Filler, CardGop> fillers = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            fillers.put(f, CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(5000 + f.ordinal() * 100)));
        }
        return new ProgramMedia(media, ENC, SourcesTest.AVCC,
                file -> CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(7000)), fillers,
                SourcesTest.audioFrame(9999), r -> Thread.ofVirtual().start(r), 5000, System::currentTimeMillis);
    }

    ProgramCue.Entry cueEntry() throws Exception {
        QueueEntry e = registry.call(roomId, tx -> tx.state().current());
        return e == null ? null : new ProgramCue.Entry(e.id(), e.itemId(), false, false, e.durationMs());
    }

    @Test
    void theRoomSkipsWhatThePackagerCantPlayAndPicksItUpOnceReencoded() throws Exception {
        TestClock clock = new TestClock(1_800_000_000_000L);
        openRoom(clock);
        SourcesTest.Media a = SourcesTest.audioItem();
        // an old encoding waiting for its re-encode, a current one whose index.bin is missing, a good one
        String[] stale = queued(OTHER.id(), 1, clock);
        write(stale[1], 0, a, indexFor(a, OTHER, SourcesTest.AVCC));
        String[] broken = queued(ENC.id(), 2, clock);
        Files.delete(write(broken[1], 0, a, a.index()).resolve(SampleIndex.FILE_NAME));
        String[] good = queued(ENC.id(), 3, clock);
        write(good[1], 0, a, a.index());

        registry.call(roomId, Playback::startIfIdle);
        assertEquals(good[0], registry.call(roomId, tx -> tx.state().timeline().entryId()));
        try (ProgramMedia pm = programMedia()) {
            assertNotNull(pm.item(cueEntry(), 0), "the output plays it (no Loading for the whole item)");

            // it ends: nothing else is playable, so the room is idle (Idle card), not on Loading
            assertNull(registry.call(roomId, tx -> Playback.advance(tx, QueueEntry.State.PLAYED)));
            assertEquals(Timeline.IDLE, registry.call(roomId, tx -> tx.state().timeline()));
            assertEquals(List.of(stale[0], broken[0]), registry.call(roomId,
                    tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::id).toList()), "both stay queued");

            // the re-encode publishes v1 for the current encoding; finishing the job tells the rooms
            IngestJobs jobs = new IngestJobs(db, clock);
            jobs.statusListener(registry);
            write(stale[1], 1, a, a.index());
            jobs.enqueue(stale[1], IngestJob.Kind.REENCODE);
            IngestJob job = jobs.claimNext();
            assertEquals(stale[1], job.itemId());
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET output_profile = ?,"
                        + " media_version = 1 WHERE id = ?")) {
                    ps.setString(1, ENC.id());
                    ps.setString(2, stale[1]);
                    ps.executeUpdate();
                }
                return null;
            });
            assertTrue(jobs.complete(job.id(), "done"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline
                    && !stale[0].equals(registry.call(roomId, tx -> tx.state().timeline().entryId()))) {
                Thread.sleep(20);
            }
            assertEquals(stale[0], registry.call(roomId, tx -> tx.state().timeline().entryId()),
                    "the idle room starts the re-encoded item");
        }
        // the program output reads the item's current version: v1
        ProgramMedia.ItemDirs dirs = id -> MediaVersions.versionDir(media.resolve(id), 1);
        Map<Filler, CardGop> fillers = new EnumMap<>(Filler.class);
        for (Filler f : Filler.values()) {
            fillers.put(f, CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(5000)));
        }
        try (ProgramMedia pm = new ProgramMedia(dirs, ENC, SourcesTest.AVCC,
                file -> CardGop.of(SourcesTest.AVCC, SourcesTest.cardFrames(7000)), fillers,
                SourcesTest.audioFrame(9999), r -> Thread.ofVirtual().start(r), 5000, System::currentTimeMillis)) {
            assertNotNull(pm.item(cueEntry(), 0));
        }
        // the broken one stays queued and skipped
        assertEquals(List.of(broken[0]), registry.call(roomId,
                tx -> Playback.playOrder(tx.state()).stream().map(QueueEntry::id).toList()));
    }
}
