package io.siggi.togethertunes.settings;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.SqliteDialect;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;

/** {@link SettingsContract} on a SQLite file. */
class SqliteSettingsTest extends SettingsContract {
    @TempDir
    Path dir;

    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }
}
