package io.siggi.togethertunes.library;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.SqliteDialect;
import java.sql.SQLException;
import java.util.Properties;

/** {@link PlaylistsContract} on a SQLite file. */
class SqlitePlaylistsTest extends PlaylistsContract {
    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
    }
}
