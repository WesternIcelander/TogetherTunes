package io.siggi.togethertunes.db;

import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.Function;
import org.sqlite.SQLiteConnection;

/**
 * SQLite (3.35+, as bundled with sqlite-jdbc). Built-in {@code lower()} and {@code LIKE}
 * only fold ASCII, so {@link #initConnection} registers {@code tt_lower(x)}, a
 * deterministic function that applies {@link Dialect#foldCase} (Java, Unicode-aware) to
 * text and passes {@code NULL} through.
 */
public final class SqliteDialect implements Dialect {
    public static final SqliteDialect INSTANCE = new SqliteDialect();

    /** Name of the case-folding SQL function registered on every connection. */
    public static final String LOWER_FUNCTION = "tt_lower";

    private SqliteDialect() {
    }

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public void initConnection(Connection connection) throws SQLException {
        Function.create(connection.unwrap(SQLiteConnection.class), LOWER_FUNCTION, new Lower(), 1,
                Function.FLAG_DETERMINISTIC);
    }

    @Override
    public String lower(String column) {
        return LOWER_FUNCTION + "(" + column + ")";
    }

    @Override
    public String toString() {
        return "SqliteDialect";
    }

    private static final class Lower extends Function {
        private static final int SQLITE_NULL = 5;

        @Override
        protected void xFunc() throws SQLException {
            if (value_type(0) == SQLITE_NULL) {
                result();
            } else {
                result(Dialect.foldCase(value_text(0)));
            }
        }
    }
}
