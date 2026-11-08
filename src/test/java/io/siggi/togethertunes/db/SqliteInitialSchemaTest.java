package io.siggi.togethertunes.db;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;

/** {@link InitialSchemaContract} on a SQLite file. */
class SqliteInitialSchemaTest extends InitialSchemaContract {
    @TempDir
    Path dir;

    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }
}
