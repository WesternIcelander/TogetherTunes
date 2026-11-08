package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SQL generation, shared by both dialects. */
class DialectTest {
    private static final Dialect SQLITE = SqliteDialect.INSTANCE;
    private static final Dialect PG = PostgresDialect.INSTANCE;

    @Test
    void forJdbcUrl() {
        assertSame(SQLITE, Dialect.forJdbcUrl("jdbc:sqlite:data/db/tt.db"));
        assertSame(PG, Dialect.forJdbcUrl("jdbc:postgresql://localhost/tt"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.forJdbcUrl("jdbc:mysql://x/y"));
        assertEquals("sqlite", SQLITE.name());
        assertEquals("postgres", PG.name());
    }

    @Test
    void upsertSql() {
        String expected = "INSERT INTO kv (k, v, n) VALUES (?, ?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v, n = excluded.n";
        assertEquals(expected, SQLITE.upsert("kv", List.of("k", "v", "n"), List.of("k"), List.of("v", "n")));
        assertEquals(expected, PG.upsert("kv", List.of("k", "v", "n"), List.of("k"), List.of("v", "n")));
        assertEquals("INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT (k) DO NOTHING",
                SQLITE.insertOrIgnore("kv", List.of("k", "v"), List.of("k")));
    }

    @Test
    void upsertRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> SQLITE.upsert("kv", List.of(), List.of("k"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SQLITE.upsert("kv", List.of("k"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SQLITE.upsert("kv", List.of("k"), List.of("x"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SQLITE.upsert("kv", List.of("k"), List.of("k"), List.of("v")));
        assertThrows(IllegalArgumentException.class,
                () -> SQLITE.upsert("kv; DROP TABLE x", List.of("k"), List.of("k"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SQLITE.upsert("kv", List.of("k", "v = 1--"), List.of("k"), List.of()));
    }

    @Test
    void returningSql() {
        assertEquals("DELETE FROM kv WHERE k = ? RETURNING k, n", PG.returning("  DELETE FROM kv WHERE k = ?\n", "k", "n"));
        assertThrows(IllegalArgumentException.class, () -> PG.returning("DELETE FROM kv"));
        assertThrows(IllegalArgumentException.class, () -> PG.returning("DELETE FROM kv;", "k"));
        assertThrows(IllegalArgumentException.class, () -> PG.returning("DELETE FROM kv", "k, (SELECT 1)"));
    }

    @Test
    void searchSql() {
        assertEquals("tt_lower(name) LIKE ? ESCAPE '\\'", SQLITE.containsIgnoreCase("name"));
        assertEquals("lower(name) LIKE ? ESCAPE '\\'", PG.containsIgnoreCase("name"));
        assertEquals("tt_lower(username) = ?", SQLITE.equalsIgnoreCase("username"));
        assertEquals("lower(username) = ?", PG.equalsIgnoreCase("username"));
        assertThrows(IllegalArgumentException.class, () -> PG.containsIgnoreCase("name) OR (1=1"));
    }

    @Test
    void patterns() {
        assertEquals("%björk%", Dialect.containsPattern("BJÖRK"));
        assertEquals("%100\\%\\_a\\\\b%", Dialect.containsPattern("100%_A\\b"));
        assertEquals("%%", Dialect.containsPattern(""));
        assertEquals("istanbul", Dialect.foldCase("ISTANBUL"), "Locale.ROOT, not Turkish");
    }
}
