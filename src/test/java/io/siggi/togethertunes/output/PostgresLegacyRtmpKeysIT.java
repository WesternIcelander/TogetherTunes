package io.siggi.togethertunes.output;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.PostgresDialect;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link LegacyRtmpKeysContract} against PostgreSQL 16 in Docker. */
class PostgresLegacyRtmpKeysIT extends LegacyRtmpKeysContract {
    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Override
    protected Database open() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", postgres.getUsername());
        p.setProperty("password", postgres.getPassword());
        return Database.open(postgres.getJdbcUrl(), p, Database.PoolSettings.defaults(PostgresDialect.INSTANCE));
    }
}
