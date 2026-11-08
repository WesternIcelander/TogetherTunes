package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link MigrationContract} against PostgreSQL 16 in Docker, plus PostgreSQL-only cases. */
class PostgresMigrationIT extends MigrationContract {
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

    private static Properties credentials() {
        Properties p = new Properties();
        p.setProperty("user", postgres.getUsername());
        p.setProperty("password", postgres.getPassword());
        return p;
    }

    private static Database connect() throws SQLException {
        return Database.open(postgres.getJdbcUrl(), credentials(), Database.PoolSettings.defaults(PostgresDialect.INSTANCE));
    }

    @Override
    protected Database open() throws SQLException {
        // A fresh, empty schema for every test.
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), credentials());
             Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        return connect();
    }

    @Test
    void dollarQuotedFunctionBody() throws SQLException {
        Migration function = Migration.of("0002_function.sql", """
                CREATE FUNCTION a_count() RETURNS BIGINT LANGUAGE plpgsql AS $body$
                BEGIN
                    RETURN (SELECT COUNT(*) FROM a); -- a ; inside
                END;
                $body$;
                DO $$ BEGIN INSERT INTO a (id) VALUES ('from-do'); END $$;
                """);
        runner(CREATE_A, function).migrate();
        long n = db.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT a_count()")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        assertEquals(2, n);
    }

    @Test
    void concurrentRunnersApplyEachMigrationOnce() throws Exception {
        int runners = 4;
        List<Database> databases = new ArrayList<>();
        for (int i = 0; i < runners; i++) {
            databases.add(connect());
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> appliedCounts = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Migration>>> results = new ArrayList<>();
            for (Database d : databases) {
                results.add(pool.submit(() -> {
                    start.await();
                    return new MigrationRunner(d, List.of(CREATE_A, CREATE_B, ADD_COLUMN)).migrate();
                }));
            }
            start.countDown();
            for (Future<List<Migration>> f : results) {
                appliedCounts.add(f.get().size());
            }
        } finally {
            databases.forEach(Database::close);
        }
        assertEquals(3, appliedCounts.stream().mapToInt(Integer::intValue).sum(), appliedCounts.toString());
        assertEquals(3, count(MigrationRunner.TABLE));
        assertEquals(1, count("a"));
    }
}
