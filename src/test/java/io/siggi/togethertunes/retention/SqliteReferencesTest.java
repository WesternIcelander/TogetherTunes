package io.siggi.togethertunes.retention;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.SqliteDialect;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;

/** {@link ReferencesContract} on a SQLite file. */
class SqliteReferencesTest extends ReferencesContract {
    @TempDir
    Path dir;

    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }
}
