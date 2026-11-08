package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** {@link DialectContract} against an in-memory SQLite database. */
class SqliteDialectTest extends DialectContract {
    @Override
    protected Dialect dialect() {
        return SqliteDialect.INSTANCE;
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @Test
    void bundledSqliteSupportsReturning() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
            rs.next();
            String[] v = rs.getString(1).split("\\.");
            int version = Integer.parseInt(v[0]) * 1000 + Integer.parseInt(v[1]);
            assertEquals(true, version >= 3035, "RETURNING needs SQLite 3.35+, have " + rs.getString(1));
        }
    }

    @Test
    void foldFunctionIsMissingWithoutInitConnection() throws SQLException {
        try (Connection plain = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = plain.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeQuery("SELECT tt_lower('A')"));
        }
    }
}
