package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.io.TempDir;

/** {@link LegacyRtmpKeysContract} on SQLite. */
class SqliteLegacyRtmpKeysTest extends LegacyRtmpKeysContract {
    @TempDir
    Path dir;

    @Override
    protected Database open() throws SQLException {
        return Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
    }
}
