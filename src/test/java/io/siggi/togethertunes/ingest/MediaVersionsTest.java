package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.storage.DiskSpace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MediaVersions}: layout, the clean-up of old versions after a switch, and at startup; undo
 * (the previous version kept for the window, restored with its settings, redo by undo, expiry,
 * early deletion under the free-space minimum).
 */
class MediaVersionsTest {
    @TempDir
    Path dir;

    private DataDir data;
    private Database db;
    private IngestFixtures fixtures;

    @BeforeEach
    void setUp() throws Exception {
        data = DataDir.create(dir.resolve("data"));
        db = Database.open("jdbc:sqlite:" + data.db().resolve("test.db"));
        MigrationRunner.migrate(db);
        fixtures = new IngestFixtures(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void setVersion(String item, int version) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET media_version = ? WHERE id = ?")) {
                ps.setInt(1, version);
                ps.setString(2, item);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private Path write(String item, String file) throws Exception {
        Path f = IngestPipeline.itemDir(data, item).resolve(file);
        Files.createDirectories(f.getParent());
        Files.writeString(f, file);
        return f;
    }

    private List<String> files(String item) throws Exception {
        Path itemDir = IngestPipeline.itemDir(data, item);
        try (Stream<Path> s = Files.walk(itemDir)) {
            return s.filter(Files::isRegularFile).map(p -> itemDir.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void layout() throws Exception {
        String item = UuidV7.newId();
        Path itemDir = IngestPipeline.itemDir(data, item);
        assertEquals(itemDir, MediaVersions.versionDir(data, item, 0), "version 0 is the flat layout");
        assertEquals(itemDir.resolve("v12"), MediaVersions.versionDir(data, item, 12));
        assertThrows(IllegalArgumentException.class, () -> MediaVersions.versionDir(itemDir, -1));
        assertEquals(OptionalInt.of(3), MediaVersions.parseVersionName("v3"));
        for (String bad : new String[] {"v", "v0", "v03", "vx", "x3", "original.mp4", "v12345678901"}) {
            assertTrue(MediaVersions.parseVersionName(bad).isEmpty(), bad);
        }
        assertEquals(1, MediaVersions.nextVersion(itemDir, 0), "no directory yet");
        Files.createDirectories(itemDir.resolve("v4"));
        Files.writeString(itemDir.resolve("v9"), "a file, not a version");
        assertEquals(5, MediaVersions.nextVersion(itemDir, 2), "above a leftover version");
        assertEquals(8, MediaVersions.nextVersion(itemDir, 7));

        String row = fixtures.item("ready");
        assertEquals(OptionalInt.of(0), MediaVersions.current(db, row));
        setVersion(row, 3);
        assertEquals(IngestPipeline.itemDir(data, row).resolve("v3"), MediaVersions.currentDir(db, data, row));
        assertTrue(MediaVersions.current(db, UuidV7.newId()).isEmpty());
    }

    /**
     * An old version is deleted once the item is neither current nor next anywhere and its
     * duration plus the grace period have passed since the switch; version 0 (flat) by its file
     * names, so the original and the newer versions stay.
     */
    @Test
    void retiredVersionsGoAfterThePlayAndTheGrace() throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        Set<String> playing = ConcurrentHashMap.newKeySet();
        MediaVersions versions = new MediaVersions(data, now::get, Duration.ofMinutes(5));
        versions.inUse(playing::contains);
        String a = UuidV7.newId();
        for (String f : MediaVersions.ENCODE_FILES) {
            write(a, f);
        }
        write(a, "original.flac");
        write(a, "v1/audio.m4a");
        String b = UuidV7.newId();
        write(b, "v1/mezz.mp4");
        write(b, "v2/mezz.mp4");
        playing.add(b);

        versions.retired(a, 0, 60_000);
        versions.retired(b, 1, 10_000);
        assertEquals(0, versions.sweep());
        now.addAndGet(10_000 + Duration.ofMinutes(5).toMillis());
        assertEquals(0, versions.sweep(), "a's time hasn't come, b is playing");
        playing.clear();
        assertEquals(1, versions.sweep());
        assertEquals(List.of("v2/mezz.mp4"), files(b));
        now.addAndGet(50_000);
        assertEquals(1, versions.sweep());
        assertEquals(List.of("original.flac", "v1/audio.m4a"), files(a));
        assertEquals(List.of(), versions.pending());

        // a version already gone (e.g. the item was purged) is dropped quietly
        versions.retired(b, 1, 0);
        now.addAndGet(Duration.ofMinutes(5).toMillis());
        assertEquals(0, versions.sweep());
        assertEquals(List.of(), versions.pending());
    }

    /**
     * At startup every version but the current one goes, including a {@code v*} a crash left
     * before its database switch; unknown and purged items' directories are left alone.
     */
    @Test
    void startupKeepsOnlyTheCurrentVersion() throws Exception {
        String flat = fixtures.item("ready"); // version 0, never re-encoded
        write(flat, "audio.m4a");
        write(flat, "original.wav");
        write(flat, "v1/audio.m4a"); // a crash after the rename, before the switch
        String switched = fixtures.item("ready");
        setVersion(switched, 2);
        write(switched, "mezz.mp4");
        write(switched, "index.bin");
        write(switched, "v1/mezz.mp4");
        write(switched, "v2/mezz.mp4");
        write(switched, "original.mkv");
        String purged = fixtures.item("purged");
        setVersion(purged, 1);
        write(purged, "v2/audio.m4a");
        String unknown = UuidV7.newId();
        write(unknown, "v3/audio.m4a");
        Files.createDirectories(data.media().resolve(".ingest-" + UuidV7.newId()).resolve("v1"));

        assertEquals(3, MediaVersions.cleanUpAtStartup(db, data));
        assertEquals(List.of("audio.m4a", "original.wav"), files(flat));
        assertEquals(List.of("original.mkv", "v2/mezz.mp4"), files(switched));
        assertEquals(List.of("v2/audio.m4a"), files(purged));
        assertEquals(List.of("v3/audio.m4a"), files(unknown));
        assertEquals(0, MediaVersions.cleanUpAtStartup(db, data), "nothing left to do");
        assertFalse(Files.exists(IngestPipeline.itemDir(data, switched).resolve("v1")));
    }

    // ------------------------------------------------------------------ undo

    private static final long HOUR = 3_600_000L;
    private static final long WINDOW = 48 * HOUR;

    /** Writes version {@code v}'s audio.m4a (its content names the version) and publishes it. */
    private void publish(String item, int v, Integer kept, boolean undoable, String edit, long durationMs,
                         OutputEncoding encoding, long at) throws Exception {
        Path f = MediaVersions.versionDir(data, item, v).resolve(IngestPipeline.AUDIO);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "audio of v" + v);
        new IngestItems(db).published(item, false, durationMs, encoding, X264Preset.VERYFAST, true, null, null, v,
                new IngestItems.VersionChange(kept, undoable, edit, at));
    }

    private String currentAudio(String item) throws Exception {
        return Files.readString(MediaVersions.currentDir(db, data, item).resolve(IngestPipeline.AUDIO));
    }

    private long duration(String item) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT duration_ms FROM items WHERE id = ?")) {
                ps.setString(1, item);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private OptionalLong undoable(MediaVersions versions, String item) throws Exception {
        return db.read(c -> versions.undoableSince(c, item));
    }

    private String refusal(MediaVersions versions, String item) {
        return assertThrows(MediaVersions.UndoRefused.class, () -> versions.undo(item, (c, u) -> { })).code();
    }

    /** An item with v1 (edit settings A, 10 s) and then an edit to v2 (settings B, 8 s), v1 kept for undo. */
    private String edited(MediaVersions versions, AtomicLong now) throws Exception {
        String item = fixtures.item("ready");
        publish(item, 1, null, false, "{\"videoStartMs\":0}", 10_000, OutputEncoding.DEFAULT, now.get());
        publish(item, 2, 1, true, "{\"videoStartMs\":4000}", 8_000, OutputEncoding.DEFAULT, now.get());
        versions.retired(item, 1, 8_000);
        return item;
    }

    private MediaVersions undoVersions(AtomicLong now) {
        MediaVersions versions = new MediaVersions(data, db, now::get, Duration.ofMinutes(5));
        versions.undoWindow(() -> WINDOW);
        return versions;
    }

    /**
     * Undo goes back to the previous version's files (linked into a new version directory, so the
     * numbers keep growing) with its items columns and edit settings; the version undone becomes
     * the kept one, so undoing the undo goes forward again.
     */
    @Test
    void undoRestoresTheOldVersionAndItsSettingsAndCanBeUndone() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        MediaVersions versions = undoVersions(now);
        List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        versions.statusListener(told::add);
        String item = edited(versions, now);
        assertEquals(OptionalLong.of(now.get()), undoable(versions, item));

        now.addAndGet(HOUR);
        MediaVersions.Undone undone = versions.undo(item, (c, u) -> { });
        assertEquals(new MediaVersions.Undone(2, 1, 3, "{\"videoStartMs\":0}"), undone);
        assertEquals(OptionalInt.of(3), MediaVersions.current(db, item));
        assertEquals("audio of v1", currentAudio(item));
        assertEquals(10_000, duration(item), "the items columns of v1 are back");
        assertEquals("{\"videoStartMs\":0}", MediaVersions.currentEditJson(db, item), "the trim settings of v1");
        assertEquals(List.of(item), told);
        assertTrue(Files.exists(MediaVersions.versionDir(data, item, 2)), "the version undone is kept");
        assertEquals(OptionalLong.of(now.get()), undoable(versions, item), "the undo can be undone");

        // redo by undo
        now.addAndGet(HOUR);
        assertEquals(new MediaVersions.Undone(3, 2, 4, "{\"videoStartMs\":4000}"), versions.undo(item, (c, u) -> { }));
        assertEquals("audio of v2", currentAudio(item));
        assertEquals(8_000, duration(item));
        assertEquals("{\"videoStartMs\":4000}", MediaVersions.currentEditJson(db, item));

        // only one step back: v1 and v2 only wait for their plays, v3 is the kept one
        now.addAndGet(Duration.ofMinutes(5).toMillis() + 10_000);
        assertEquals(2, versions.sweep());
        assertEquals(List.of("v3/audio.m4a", "v4/audio.m4a"), files(item));
        assertEquals(1, versions.pending().size());
        assertTrue(versions.pending().get(0).undo());
        assertEquals("audio of v1", Files.readString(MediaVersions.versionDir(data, item, 3).resolve(IngestPipeline.AUDIO)),
                "linked files outlive the directory they came from");
    }

    private void publishKind(String item, int v, Integer kept, boolean video, long at) throws Exception {
        Path f = MediaVersions.versionDir(data, item, v).resolve(IngestPipeline.AUDIO);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "audio of v" + v);
        new IngestItems(db).published(item, video, 10_000, OutputEncoding.DEFAULT, X264Preset.VERYFAST, true, null,
                null, v, new IngestItems.VersionChange(kept, true, null, at));
    }

    private String column(String item, String column) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
                ps.setString(1, item);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private void setType(String item, String type, String showName, Integer episode) throws Exception {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET media_type = ?, show_name = ?,"
                    + " episode_number = ? WHERE id = ?")) {
                ps.setString(1, type);
                ps.setString(2, showName);
                ps.setObject(3, episode);
                ps.setString(4, item);
                ps.executeUpdate();
            }
            MediaVersions.typeChanged(c, item, MediaVersions.current(db, item).orElseThrow(), type);
            return null;
        });
    }

    /**
     * The media type is part of a version: a video published as audio (type {@code audio}) comes
     * back as its video type with an undo, and the other way round; a classification between video
     * types made after the change stays.
     */
    @Test
    void undoRestoresTheMediaTypeWhenAudioIsInvolved() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        MediaVersions versions = undoVersions(now);
        String item = fixtures.item("ready");
        publishKind(item, 1, null, true, now.get());
        setType(item, "music_video", null, null);
        publishKind(item, 2, 1, false, now.get()); // published as audio (e.g. a still image)
        assertEquals("audio", column(item, "kind"));
        assertEquals("audio", column(item, "media_type"));
        now.addAndGet(HOUR);
        versions.undo(item, (c, u) -> { });
        assertEquals("video", column(item, "kind"));
        assertEquals("music_video", column(item, "media_type"), "the video type is back");
        now.addAndGet(HOUR);
        versions.undo(item, (c, u) -> { });
        assertEquals("audio", column(item, "media_type"), "and audio again");

        // a trim between video versions, classified afterwards: the classification stays
        String clip = fixtures.item("ready");
        publishKind(clip, 1, null, true, now.get());
        publishKind(clip, 2, 1, true, now.get());
        setType(clip, "show", "Series", 4);
        now.addAndGet(HOUR);
        versions.undo(clip, (c, u) -> { });
        assertEquals("show", column(clip, "media_type"));
        assertEquals("Series", column(clip, "show_name"));
    }

    /** After the window the kept version goes, and Undo with it; a window of 0 turns undo off. */
    @Test
    void theKeptVersionExpiresAfterTheWindow() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        MediaVersions versions = undoVersions(now);
        String item = edited(versions, now);
        now.addAndGet(HOUR);
        assertEquals(0, versions.sweep(), "the play is over, but the window isn't");
        assertTrue(undoable(versions, item).isPresent());

        now.addAndGet(WINDOW - HOUR - 1);
        assertTrue(undoable(versions, item).isPresent(), "the last millisecond of the window");
        now.addAndGet(1);
        assertFalse(undoable(versions, item).isPresent());
        assertEquals("nothing_to_undo", refusal(versions, item));
        assertEquals(1, versions.sweep());
        assertEquals(List.of("v2/audio.m4a"), files(item));
        assertEquals(0, count("SELECT COUNT(*) FROM media_undo WHERE item_id = ?", item), "forgotten");
        assertEquals(1, count("SELECT COUNT(*) FROM media_versions WHERE item_id = ?", item), "only the current one");

        String off = edited(versions, now);
        versions.undoWindow(() -> 0);
        assertFalse(undoable(versions, off).isPresent());
        assertEquals("nothing_to_undo", refusal(versions, off));
        now.addAndGet(Duration.ofMinutes(5).toMillis() + 8_000);
        assertEquals(1, versions.sweep(), "without undo only the plays keep it");
    }

    private int count(String sql, String item) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, item);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /**
     * A re-encode made by the system keeps the previous version for the window without Undo; a kept
     * version made for another output encoding can't come back; nor while a job runs.
     */
    @Test
    void onlyAPersonsChangeCanBeUndone() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        MediaVersions versions = undoVersions(now);
        String item = fixtures.item("ready");
        publish(item, 1, null, false, null, 10_000, OutputEncoding.DEFAULT, now.get());
        publish(item, 2, 1, false, null, 10_000, OutputEncoding.DEFAULT, now.get()); // a quality re-encode
        versions.retired(item, 1, 10_000);
        assertFalse(undoable(versions, item).isPresent());
        assertEquals("nothing_to_undo", refusal(versions, item));
        now.addAndGet(HOUR);
        assertEquals(0, versions.sweep(), "kept for the window all the same");

        String profile = fixtures.item("ready");
        publish(profile, 1, null, false, null, 10_000, OutputEncoding.defaultFor(OutputProfile.P1080_30), now.get());
        publish(profile, 2, 1, true, null, 10_000, OutputEncoding.DEFAULT, now.get());
        assertFalse(undoable(versions, profile).isPresent());
        assertEquals("nothing_to_undo", refusal(versions, profile));

        String busy = edited(versions, now);
        new IngestJobs(db).enqueue(busy, IngestJob.Kind.REENCODE);
        assertTrue(undoable(versions, busy).isPresent(), "shown; the undo itself says to wait");
        assertEquals("item_busy", refusal(versions, busy));
        assertEquals(OptionalInt.of(2), MediaVersions.current(db, busy));
        assertEquals(List.of("v1/audio.m4a", "v2/audio.m4a"), files(busy), "nothing left behind");
    }

    /**
     * While the free space is below the minimum, kept undo versions whose plays are over are
     * deleted early, oldest first, until enough is freed.
     */
    @Test
    void keptVersionsGoEarlyWhileTheFreeSpaceIsLow() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        AtomicLong free = new AtomicLong(100);
        MediaVersions versions = undoVersions(now);
        versions.diskSpace(new DiskSpace(() -> new DiskSpace.Usage(free.get(), 1000), () -> 100, Duration.ZERO,
                System::nanoTime));
        String older = edited(versions, now);
        now.addAndGet(1000);
        String newer = edited(versions, now);
        now.addAndGet(HOUR);
        assertEquals(0, versions.sweep(), "enough space");

        free.set(99); // one byte short: the oldest kept version is enough
        assertEquals(1, versions.sweep());
        assertEquals(List.of("v2/audio.m4a"), files(older));
        assertEquals(List.of("v1/audio.m4a", "v2/audio.m4a"), files(newer));
        assertFalse(undoable(versions, older).isPresent());
        assertTrue(undoable(versions, newer).isPresent());

        free.set(0);
        assertEquals(1, versions.sweep());
        assertEquals(List.of("v2/audio.m4a"), files(newer));
        assertEquals(List.of(), versions.pending());
    }

    /** At startup the kept version stays while it is within the window and is swept after it. */
    @Test
    void startupKeepsTheUndoVersionWithinTheWindow() throws Exception {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        String item;
        String expired;
        {
            MediaVersions before = undoVersions(now);
            expired = edited(before, now);
            now.addAndGet(2 * HOUR);
            item = edited(before, now);
            write(item, "v3/audio.m4a"); // a crash before its switch
        }
        now.addAndGet(WINDOW - HOUR); // the first edit is past the window, the second within it
        MediaVersions after = undoVersions(now);
        assertEquals(2, after.cleanUpAtStartup());
        assertEquals(List.of("v1/audio.m4a", "v2/audio.m4a"), files(item));
        assertEquals(List.of("v2/audio.m4a"), files(expired));
        assertEquals(0, count("SELECT COUNT(*) FROM media_undo WHERE item_id = ?", expired));
        assertTrue(undoable(after, item).isPresent());
        assertEquals("audio of v1", Files.readString(MediaVersions.versionDir(data, item, 1).resolve(IngestPipeline.AUDIO)));

        assertEquals(0, after.sweep());
        now.addAndGet(HOUR);
        assertEquals(1, after.sweep());
        assertEquals(List.of("v2/audio.m4a"), files(item));
        assertNull(MediaVersions.currentEditJson(db, UuidV7.newId()));
    }
}
