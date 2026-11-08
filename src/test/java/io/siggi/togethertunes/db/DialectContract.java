package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link Dialect} must show against its real database. Subclasses supply
 * the dialect and a fresh connection; each test gets new tables.
 */
abstract class DialectContract {
    protected Connection conn;

    protected abstract Dialect dialect();

    /** A connection to an empty database (or schema) the test may create tables in. */
    protected abstract Connection openConnection() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        conn = openConnection();
        dialect().initConnection(conn);
        exec("DROP TABLE IF EXISTS kv");
        exec("DROP TABLE IF EXISTS pairs");
        exec("DROP TABLE IF EXISTS people");
        exec("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, n BIGINT NOT NULL)");
        exec("CREATE TABLE pairs (a TEXT NOT NULL, b TEXT NOT NULL, v TEXT, PRIMARY KEY (a, b))");
        exec("CREATE TABLE people (id TEXT PRIMARY KEY, name TEXT)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    // ---------------------------------------------------------------- upsert

    @Test
    void upsertInsertsThenUpdatesOnlyTheNamedColumns() throws SQLException {
        String sql = dialect().upsert("kv", List.of("k", "v", "n"), List.of("k"), List.of("v"));
        assertEquals(1, update(sql, "a", "first", 1L));
        assertEquals(1, update(sql, "a", "second", 2L));
        assertEquals(List.of("a|second|1"), rows("SELECT k, v, n FROM kv"));
    }

    @Test
    void upsertWithCompositeKey() throws SQLException {
        String sql = dialect().upsert("pairs", List.of("a", "b", "v"), List.of("a", "b"), List.of("v"));
        update(sql, "x", "1", "one");
        update(sql, "x", "2", "two");
        update(sql, "x", "1", "uno");
        assertEquals(List.of("x|1|uno", "x|2|two"), rows("SELECT a, b, v FROM pairs ORDER BY a, b"));
    }

    @Test
    void upsertCanSetNull() throws SQLException {
        String sql = dialect().upsert("pairs", List.of("a", "b", "v"), List.of("a", "b"), List.of("v"));
        update(sql, "x", "1", "one");
        update(sql, "x", "1", null);
        assertEquals(List.of("x|1|null"), rows("SELECT a, b, v FROM pairs"));
    }

    @Test
    void insertOrIgnoreKeepsTheExistingRow() throws SQLException {
        String sql = dialect().insertOrIgnore("kv", List.of("k", "v", "n"), List.of("k"));
        assertEquals(1, update(sql, "a", "first", 1L));
        assertEquals(0, update(sql, "a", "second", 2L));
        assertEquals(List.of("a|first|1"), rows("SELECT k, v, n FROM kv"));
    }

    // ------------------------------------------------------------- RETURNING

    @Test
    void returningOnUpsertGivesTheStoredRow() throws SQLException {
        String sql = dialect().returning(
                dialect().upsert("kv", List.of("k", "v", "n"), List.of("k"), List.of("v")), "k", "v", "n");
        assertEquals(List.of("a|first|1"), query(sql, "a", "first", 1L));
        assertEquals(List.of("a|second|1"), query(sql, "a", "second", 7L));
    }

    @Test
    void returningOnIgnoredInsertGivesNoRow() throws SQLException {
        String sql = dialect().returning(dialect().insertOrIgnore("kv", List.of("k", "v", "n"), List.of("k")), "k");
        assertEquals(List.of("a"), query(sql, "a", "x", 1L));
        assertEquals(List.of(), query(sql, "a", "y", 2L));
    }

    @Test
    void returningOnUpdateAndDelete() throws SQLException {
        exec("INSERT INTO kv (k, v, n) VALUES ('a', 'x', 1), ('b', 'y', 2), ('c', 'z', 3)");
        List<String> updated = query(dialect().returning("UPDATE kv SET n = n + 10 WHERE n >= ?", "k", "n"), 2L);
        assertEquals(List.of("b|12", "c|13"), sorted(updated));
        List<String> deleted = query(dialect().returning("DELETE FROM kv WHERE k <> ?", "k"), "b");
        assertEquals(List.of("a", "c"), sorted(deleted));
        assertEquals(List.of("b|y|12"), rows("SELECT k, v, n FROM kv"));
    }

    // -------------------------------------------------- case-insensitive search

    @Test
    void containsIgnoreCaseAscii() throws SQLException {
        people("1", "Daft Punk", "2", "PUNKS NOT DEAD", "3", "Pink Floyd", "4", null);
        assertEquals(List.of("1", "2"), search("punk"));
        assertEquals(List.of("1", "2"), search("PuNk"));
        assertEquals(List.of("1", "2", "3"), search(""));
        assertEquals(List.of(), search("disco"));
    }

    @Test
    void containsIgnoreCaseBeyondAscii() throws SQLException {
        people("1", "Björk", "2", "BJÖRK LIVE", "3", "Ólafur Arnalds", "4", "ΣΙΓΟΥΡ", "5", "Jóhann Jóhannsson");
        assertEquals(List.of("1", "2"), search("björk"));
        assertEquals(List.of("1", "2"), search("BJÖRK"));
        assertEquals(List.of("3"), search("ólafur"));
        assertEquals(List.of("4"), search("σιγ"));
        assertEquals(List.of("5"), search("JÓHANNS"));
        assertEquals(List.of(), search("bjork"), "accents are not stripped");
    }

    @Test
    void likeWildcardsInTheTermAreLiteral() throws SQLException {
        people("1", "100% Pure", "2", "1000 Pure", "3", "snake_case", "4", "snakeXcase", "5", "back\\slash",
                "6", "backslash");
        assertEquals(List.of("1"), search("0%"));
        assertEquals(List.of("3"), search("e_c"));
        assertEquals(List.of("5"), search("k\\s"));
        assertEquals(List.of("1"), search("%"));
        assertEquals(List.of("3"), search("_"));
    }

    @Test
    void equalsIgnoreCase() throws SQLException {
        people("1", "Siggi", "2", "SIGGI", "3", "Siggix", "4", "Ärger", "5", null);
        assertEquals(List.of("1", "2"), equal("sIgGi"));
        assertEquals(List.of("4"), equal("äRGER"));
        assertEquals(List.of(), equal("sig"));
    }

    @Test
    void lowerPassesNullThrough() throws SQLException {
        people("1", null);
        List<String> out = rows("SELECT " + dialect().lower("name") + " FROM people");
        assertEquals(List.of("null"), out);
    }

    @Test
    void lowerMatchesJavaFolding() throws SQLException {
        // Characters whose lower case is the same in Java (Locale.ROOT) and in any
        // Unicode-aware database locale. (Special cases such as the dotted capital I
        // may differ on PostgreSQL; SQLite uses Java's folding, so it never differs.)
        String[] samples = {"ABC", "Björk", "ΣΊΓΟΥΡ", "ÆØÅ", "Straße", "ÇA VA"};
        for (int i = 0; i < samples.length; i++) {
            people(Integer.toString(i), samples[i]);
        }
        List<String> fromDb = rows("SELECT " + dialect().lower("name") + " FROM people ORDER BY id");
        List<String> expected = new ArrayList<>();
        for (String s : samples) {
            expected.add(Dialect.foldCase(s));
        }
        assertEquals(expected, fromDb);
    }

    // ---------------------------------------------------------------- helpers

    private void people(String... idNamePairs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO people (id, name) VALUES (?, ?)")) {
            for (int i = 0; i < idNamePairs.length; i += 2) {
                ps.setString(1, idNamePairs[i]);
                ps.setString(2, idNamePairs[i + 1]);
                ps.executeUpdate();
            }
        }
    }

    private List<String> search(String term) throws SQLException {
        return query("SELECT id FROM people WHERE " + dialect().containsIgnoreCase("name") + " ORDER BY id",
                Dialect.containsPattern(term));
    }

    private List<String> equal(String value) throws SQLException {
        return query("SELECT id FROM people WHERE " + dialect().equalsIgnoreCase("name") + " ORDER BY id",
                Dialect.foldCase(value));
    }

    protected void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private List<String> query(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return collect(rs);
            }
        }
    }

    private List<String> rows(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return collect(rs);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else {
                ps.setString(i + 1, (String) p);
            }
        }
    }

    private static List<String> collect(ResultSet rs) throws SQLException {
        List<String> out = new ArrayList<>();
        int cols = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int c = 1; c <= cols; c++) {
                if (c > 1) {
                    row.append('|');
                }
                row.append(rs.getString(c));
            }
            out.add(row.toString());
        }
        return out;
    }

    private static List<String> sorted(List<String> list) {
        List<String> copy = new ArrayList<>(list);
        copy.sort(null);
        return copy;
    }
}
