package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.MigrationRunner.MigrationException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of {@link MigrationRunner} that must hold for both dialects. */
abstract class MigrationContract {
    protected Database db;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    protected static final Migration CREATE_A = Migration.of("0001_create_a.sql", """
            -- the first table; a ; in a comment
            CREATE TABLE a (
                id TEXT PRIMARY KEY,
                note TEXT NOT NULL DEFAULT 'x;y' /* ; in a block comment */
            );
            INSERT INTO a (id) VALUES ('first');
            """);
    protected static final Migration CREATE_B = Migration.of("0002_create_b.sql", """
            CREATE TABLE b (id TEXT PRIMARY KEY, a_id TEXT NOT NULL REFERENCES a (id));
            CREATE INDEX b_a_id ON b (a_id);
            """);
    protected static final Migration ADD_COLUMN = Migration.of("0003_add_column.sql",
            "ALTER TABLE a ADD COLUMN created_at BIGINT");

    protected MigrationRunner runner(Migration... migrations) {
        return new MigrationRunner(db, List.of(migrations));
    }

    protected boolean tableExists(String table) throws SQLException {
        return db.read(c -> {
            try (ResultSet rs = c.getMetaData().getTables(null, null, table, new String[] {"TABLE"})) {
                return rs.next();
            }
        });
    }

    protected long count(String table) throws SQLException {
        return db.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void appliesPendingMigrationsInOrderAndRecordsThem() throws SQLException {
        long before = EpochMs.now();
        // Given out of order: the runner sorts by version.
        MigrationRunner runner = runner(CREATE_B, ADD_COLUMN, CREATE_A);
        assertEquals(List.of(CREATE_A, CREATE_B, ADD_COLUMN), runner.migrate());

        assertTrue(tableExists("a"));
        assertTrue(tableExists("b"));
        assertEquals(1, count("a"));
        String note = db.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT note, created_at FROM a")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertEquals("x;y", note);

        Map<Integer, MigrationRunner.Applied> applied = runner.applied();
        assertEquals(List.of(1, 2, 3), new ArrayList<>(applied.keySet()));
        MigrationRunner.Applied first = applied.get(1);
        assertEquals("create_a", first.name());
        assertEquals(CREATE_A.checksum(), first.checksum());
        assertTrue(first.appliedAt() >= before && first.appliedAt() <= EpochMs.now());
    }

    @Test
    void secondRunAppliesNothing() throws SQLException {
        runner(CREATE_A, CREATE_B).migrate();
        assertEquals(List.of(), runner(CREATE_A, CREATE_B).migrate());
        assertEquals(1, count("a"));
    }

    @Test
    void appliesOnlyNewMigrationsLater() throws SQLException {
        runner(CREATE_A).migrate();
        assertEquals(List.of(CREATE_B, ADD_COLUMN), runner(CREATE_A, CREATE_B, ADD_COLUMN).migrate());
        assertEquals(3, count(MigrationRunner.TABLE));
    }

    @Test
    void noMigrationsJustCreatesTheTable() throws SQLException {
        assertEquals(List.of(), runner().migrate());
        assertTrue(tableExists(MigrationRunner.TABLE));
        assertEquals(0, count(MigrationRunner.TABLE));
    }

    @Test
    void failingMigrationIsRolledBackCompletely() throws SQLException {
        Migration broken = Migration.of("0002_broken.sql", """
                CREATE TABLE c (id TEXT PRIMARY KEY);
                INSERT INTO c (id) VALUES ('one');
                INSERT INTO a (id) VALUES ('second');
                INSERT INTO no_such_table (id) VALUES ('boom');
                """);
        SQLException e = assertThrows(SQLException.class, () -> runner(CREATE_A, broken, ADD_COLUMN).migrate());
        assertTrue(e.getMessage().contains("0002_broken.sql"), e.getMessage());
        assertTrue(e.getMessage().contains("no_such_table"), e.getMessage());

        // 0001 stays applied; nothing of 0002 (DDL included) remains; 0003 never ran.
        assertTrue(tableExists("a"));
        assertFalse(tableExists("c"));
        assertEquals(1, count("a"));
        assertEquals(List.of(1), new ArrayList<>(runner(CREATE_A).applied().keySet()));

        // Fixed file: the next start applies it and continues.
        Migration fixed = Migration.of("0002_broken.sql", "CREATE TABLE c (id TEXT PRIMARY KEY)");
        assertEquals(List.of(fixed, ADD_COLUMN), runner(CREATE_A, fixed, ADD_COLUMN).migrate());
        assertTrue(tableExists("c"));
    }

    @Test
    void editedMigrationStopsStartup() throws SQLException {
        runner(CREATE_A, CREATE_B).migrate();
        Migration edited = Migration.of("0002_create_b.sql", "CREATE TABLE b (id TEXT PRIMARY KEY)");
        MigrationException e = assertThrows(MigrationException.class, () -> runner(CREATE_A, edited, ADD_COLUMN).migrate());
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertEquals(2, count(MigrationRunner.TABLE)); // 0003 not applied
    }

    @Test
    void lineEndingsDontChangeTheChecksum() throws SQLException {
        runner(CREATE_A).migrate();
        Migration crlf = Migration.of("0001_create_a.sql", CREATE_A.sql().replace("\n", "\r\n"));
        assertEquals(List.of(), runner(crlf).migrate());
    }

    @Test
    void missingFileForAppliedMigrationStopsStartup() throws SQLException {
        runner(CREATE_A, CREATE_B).migrate();
        MigrationException e = assertThrows(MigrationException.class, () -> runner(CREATE_A).migrate());
        assertTrue(e.getMessage().contains("0002_create_b.sql"), e.getMessage());
    }

    @Test
    void newMigrationBelowAnAppliedOneIsRefused() throws SQLException {
        runner(CREATE_A, ADD_COLUMN).migrate();
        MigrationException e = assertThrows(MigrationException.class,
                () -> runner(CREATE_A, CREATE_B, ADD_COLUMN).migrate());
        assertTrue(e.getMessage().contains("0002_create_b.sql"), e.getMessage());
        assertFalse(tableExists("b"));
    }

    @Test
    void duplicateVersionsAreRefused() {
        Migration other = Migration.of("0001_other.sql", "CREATE TABLE other (id TEXT)");
        assertThrows(MigrationException.class, () -> runner(CREATE_A, other));
    }

    @Test
    void transactionControlIsRefused() throws SQLException {
        Migration tx = Migration.of("0001_tx.sql", "CREATE TABLE t (id TEXT);\nCOMMIT;\nCREATE TABLE u (id TEXT);");
        MigrationException e = assertThrows(MigrationException.class, () -> runner(tx).migrate());
        assertTrue(e.getMessage().contains("COMMIT"), e.getMessage());
        assertFalse(tableExists("t"));
        assertEquals(0, count(MigrationRunner.TABLE));
    }

    @Test
    void applicationMigrationsApplyToAnEmptyDatabase() throws SQLException {
        List<Migration> files = MigrationRunner.loadClasspath(getClass().getClassLoader(),
                MigrationRunner.CLASSPATH_ROOT, db.dialect());
        assertEquals(files, MigrationRunner.migrate(db));
        assertEquals(List.of(), MigrationRunner.migrate(db));
        assertEquals(files.size(), count(MigrationRunner.TABLE));
    }

    /**
     * 0017 moves the RTMP idle stop's stored values to the auto turn-off's names: the room setting
     * {@code outputs.rtmp.idleStopMinutes} → {@code outputs.autoOffMinutes} (other settings kept,
     * an emptied {@code outputs.rtmp} dropped, invalid JSON left alone) and the server setting
     * {@code outputs.rtmp.maxIdleStopMinutes} → {@code outputs.maxAutoOffMinutes}.
     */
    @Test
    void autoOffMigrationMovesTheIdleStopSettings() throws SQLException {
        Migration autoOff = MigrationRunner.loadClasspath(getClass().getClassLoader(), MigrationRunner.CLASSPATH_ROOT,
                db.dialect()).stream().filter(m -> m.name().contains("output_auto_off")).findFirst().orElseThrow();
        Migration tables = Migration.of("0001_tables.sql", """
                CREATE TABLE rooms (id TEXT PRIMARY KEY, settings_json TEXT NOT NULL);
                CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL);
                INSERT INTO rooms (id, settings_json) VALUES
                    ('only', '{"outputs":{"rtmp":{"idleStopMinutes":25}}}'),
                    ('mixed', '{"gapMs":5,"outputs":{"rtmp":{"idleStopMinutes":3,"playbackUrl":"https://x.example/v"},"ts":{"join":"latest"}}}'),
                    ('none', '{"gapMs":5}'),
                    ('broken', 'not json'),
                    ('array', '[1]');
                INSERT INTO settings (key, value_json) VALUES ('outputs.rtmp.maxIdleStopMinutes', '30'),
                    ('outputs.ts.enabled', 'false');
                """);
        Migration moved = new Migration(2, autoOff.name(), autoOff.sql(), autoOff.checksum());
        runner(tables, moved).migrate();
        Map<String, String> rooms = new java.util.HashMap<>();
        Map<String, String> settings = new java.util.HashMap<>();
        db.read(c -> {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT id, settings_json FROM rooms")) {
                    while (rs.next()) {
                        rooms.put(rs.getString(1), rs.getString(2));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT key, value_json FROM settings")) {
                    while (rs.next()) {
                        settings.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return null;
        });
        assertEquals(com.google.gson.JsonParser.parseString("{\"outputs\":{\"autoOffMinutes\":25}}"),
                com.google.gson.JsonParser.parseString(rooms.get("only")));
        assertEquals(com.google.gson.JsonParser.parseString("{\"gapMs\":5,\"outputs\":{\"rtmp\":{\"playbackUrl\":"
                        + "\"https://x.example/v\"},\"ts\":{\"join\":\"latest\"},\"autoOffMinutes\":3}}"),
                com.google.gson.JsonParser.parseString(rooms.get("mixed")));
        assertEquals(com.google.gson.JsonParser.parseString("{\"gapMs\":5}"),
                com.google.gson.JsonParser.parseString(rooms.get("none")));
        assertEquals("not json", rooms.get("broken"));
        assertEquals("[1]", rooms.get("array"));
        assertEquals(Map.of("outputs.maxAutoOffMinutes", "30", "outputs.ts.enabled", "false"), settings);
    }

    /**
     * 0023 adds the media types (docs/media-types.md): audio items and video items presented as
     * audio become {@code audio}, other videos {@code uncategorized}; {@code present_as_audio} is
     * gone; media version rows take the type (an audio version {@code audio}).
     */
    @Test
    void mediaTypeMigrationFoldsPresentAsAudio() throws SQLException {
        List<Migration> files = MigrationRunner.loadClasspath(getClass().getClassLoader(),
                MigrationRunner.CLASSPATH_ROOT, db.dialect());
        Migration mediaTypes = files.stream().filter(m -> m.name().contains("media_types")).findFirst().orElseThrow();
        new MigrationRunner(db, files.stream().filter(m -> m.version() < mediaTypes.version()).toList()).migrate();
        exec("INSERT INTO users (id, username, display_name, created_at) VALUES ('u1', 'u', 'U', 1)");
        String insert = "INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, present_as_audio) VALUES ";
        exec(insert + "('a', 'audio', 't', 'upload', 'u1', 1, 'ready', '720p30', 1, 0)");
        exec(insert + "('v', 'video', 't', 'upload', 'u1', 1, 'ready', '720p30', 1, 0)");
        exec(insert + "('p', 'video', 't', 'youtube', 'u1', 1, 'ready', '720p30', 1, 1)");
        String version = "INSERT INTO media_versions (item_id, version, published_at, kind, output_profile) VALUES ";
        exec(version + "('v', 1, 1, 'video', '720p30')");
        exec(version + "('v', 2, 1, 'audio', '720p30')");
        exec(version + "('p', 1, 1, 'video', '720p30')");
        new MigrationRunner(db, files).migrate();
        Map<String, String> types = new java.util.HashMap<>();
        Map<String, String> versions = new java.util.HashMap<>();
        db.read(c -> {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT id, media_type, show_name, season_number, episode_number,"
                        + " last_viewed_at, pinned_at, pinned_by_user_id FROM items")) {
                    while (rs.next()) {
                        types.put(rs.getString(1), rs.getString(2));
                        for (int i = 3; i <= 8; i++) {
                            rs.getObject(i);
                            assertTrue(rs.wasNull(), rs.getString(1) + " column " + i);
                        }
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT item_id, version, media_type FROM media_versions")) {
                    while (rs.next()) {
                        versions.put(rs.getString(1) + rs.getInt(2), rs.getString(3));
                    }
                }
            }
            return null;
        });
        assertEquals(Map.of("a", "audio", "v", "uncategorized", "p", "audio"), types);
        assertEquals(Map.of("v1", "uncategorized", "v2", "audio", "p1", "audio"), versions);
        assertThrows(SQLException.class, () -> exec("SELECT present_as_audio FROM items"));
        assertThrows(SQLException.class, () -> exec("UPDATE items SET media_type = 'song' WHERE id = 'v'"));
        assertThrows(SQLException.class, () -> exec("UPDATE items SET episode_number = -1 WHERE id = 'v'"));
        // new rows default to uncategorized
        exec(insert.replace(", present_as_audio)", ")")
                + "('n', 'video', 't', 'upload', 'u1', 1, 'ready', '720p30', 1)");
        assertEquals("uncategorized", db.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT media_type FROM items WHERE id = 'n'")) {
                rs.next();
                return rs.getString(1);
            }
        }));
    }

    /** Runs {@code sql} on the write connection. */
    protected void exec(String sql) throws SQLException {
        db.write((Connection c) -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }
}
