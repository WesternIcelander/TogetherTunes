package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MigrationContract} on a SQLite file, plus SQLite-only syntax. */
class SqliteMigrationTest extends MigrationContract {
    @TempDir
    Path dir;

    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }

    @Test
    void triggerBodyKeepsItsSemicolons() throws SQLException {
        Migration trigger = Migration.of("0002_trigger.sql", """
                CREATE TABLE log (msg TEXT NOT NULL);
                CREATE TRIGGER a_insert AFTER INSERT ON a
                BEGIN
                    INSERT INTO log (msg) VALUES (CASE WHEN NEW.id = 'x' THEN 'x;' ELSE 'other' END);
                    INSERT INTO log (msg) VALUES ('second');
                END;
                INSERT INTO a (id) VALUES ('x');
                """);
        runner(CREATE_A, trigger).migrate();
        assertEquals(2, count("log"));
        String first = db.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT msg FROM log ORDER BY rowid LIMIT 1")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertEquals("x;", first);
    }

    @Test
    void reopeningTheFileKeepsTheHistory() throws SQLException {
        runner(CREATE_A, CREATE_B).migrate();
        db.close();
        db = open();
        assertEquals(List.of(ADD_COLUMN), runner(CREATE_A, CREATE_B, ADD_COLUMN).migrate());
    }
}
