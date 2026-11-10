package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MediaVersions}: layout, the clean-up of old versions after a switch, and at startup. */
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
}
