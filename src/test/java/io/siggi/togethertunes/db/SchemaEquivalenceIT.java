package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs every migration against a fresh SQLite file and a fresh PostgreSQL 16 (Docker), then
 * checks that both catalogs describe the same schema ({@link SchemaSnapshot}: tables, columns
 * with type, nullability and default, primary, foreign and unique keys, indexes, and the columns
 * of each CHECK constraint).
 */
class SchemaEquivalenceIT {
    private static PostgreSQLContainer postgres;

    @TempDir
    Path dir;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void sqliteAndPostgresSchemasAreEquivalent() throws SQLException {
        List<String> sqlite;
        List<String> pg;
        try (Database db = openSqlite()) {
            assertFalse(MigrationRunner.migrate(db).isEmpty(), "no SQLite migrations ran");
            sqlite = SchemaSnapshot.of(db);
        }
        try (Database db = openPostgres()) {
            assertFalse(MigrationRunner.migrate(db).isEmpty(), "no PostgreSQL migrations ran");
            pg = SchemaSnapshot.of(db);
        }

        // Make sure the snapshots actually captured something of every kind.
        for (String kind : List.of("table ", "column ", "primary key ", "foreign key ", "unique ", "index ",
                "check ")) {
            assertTrue(sqlite.stream().anyMatch(l -> l.startsWith(kind)), "SQLite snapshot has no '" + kind + "'");
        }
        for (String table : InitialSchemaContract.TABLES.keySet()) {
            assertTrue(sqlite.contains("table " + table), table);
        }

        // One line per difference, so a failure says exactly what doesn't match.
        List<String> onlySqlite = new ArrayList<>(sqlite);
        onlySqlite.removeAll(pg);
        List<String> onlyPg = new ArrayList<>(pg);
        onlyPg.removeAll(sqlite);
        assertEquals("", diff(onlySqlite, onlyPg), "SQLite and PostgreSQL schemas differ");
        assertEquals(sqlite, pg);
    }

    /** The comparison catches a real difference (here: a column that is nullable on one side only). */
    @Test
    void aDifferenceIsDetected() throws SQLException {
        List<String> sqlite;
        List<String> pg;
        try (Database db = openSqlite()) {
            exec(db, "CREATE TABLE t (id TEXT NOT NULL PRIMARY KEY, a REAL NOT NULL DEFAULT 0, b TEXT,"
                    + " CHECK (a >= 0))");
            sqlite = SchemaSnapshot.of(db);
        }
        try (Database db = openPostgres()) {
            exec(db, "CREATE TABLE t (id TEXT NOT NULL PRIMARY KEY, a DOUBLE PRECISION NOT NULL DEFAULT 0,"
                    + " b TEXT NOT NULL, CHECK (a >= 0))");
            pg = SchemaSnapshot.of(db);
        }
        List<String> onlySqlite = new ArrayList<>(sqlite);
        onlySqlite.removeAll(pg);
        List<String> onlyPg = new ArrayList<>(pg);
        onlyPg.removeAll(sqlite);
        assertEquals(List.of("column t.b text null"), onlySqlite);
        assertEquals(List.of("column t.b text not null"), onlyPg);
    }

    private static String diff(List<String> onlySqlite, List<String> onlyPg) {
        StringBuilder b = new StringBuilder();
        onlySqlite.forEach(l -> b.append("\n  only in SQLite:     ").append(l));
        onlyPg.forEach(l -> b.append("\n  only in PostgreSQL: ").append(l));
        return b.toString();
    }

    private Database openSqlite() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("schema-" + System.nanoTime() + ".db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }

    /** A fresh, empty {@code public} schema. */
    private static Database openPostgres() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", postgres.getUsername());
        p.setProperty("password", postgres.getPassword());
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), p);
             Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        return Database.open(postgres.getJdbcUrl(), p, Database.PoolSettings.defaults(PostgresDialect.INSTANCE));
    }

    private static void exec(Database db, String sql) throws SQLException {
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }
}
