package io.siggi.togethertunes.auth;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.PostgresDialect;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link UserSettingsContract} against PostgreSQL 16 in Docker. */
class PostgresUserSettingsIT extends UserSettingsContract {
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
        // A fresh, empty schema for every test.
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), p);
             Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        return Database.open(postgres.getJdbcUrl(), p, Database.PoolSettings.defaults(PostgresDialect.INSTANCE));
    }
}
