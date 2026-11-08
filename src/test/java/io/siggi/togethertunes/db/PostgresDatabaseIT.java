package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** {@link DatabaseContract} and the hand-written pool against PostgreSQL 16 in Docker. */
class PostgresDatabaseIT extends DatabaseContract {
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

    @Override
    protected Database open(Database.PoolSettings settings) throws SQLException {
        return Database.open(postgres.getJdbcUrl(), credentials(), settings);
    }

    @Override
    protected int maxOpenConnections() {
        return POOL_SIZE;
    }

    /** Server-side count of our pool's connections, from an independent admin connection. */
    private static int serverSideConnections() throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), credentials());
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pg_stat_activity"
                     + " WHERE application_name = 'TogetherTunes' AND pid <> pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void terminateOurConnections() throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), credentials());
             Statement st = admin.createStatement()) {
            st.executeQuery("SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                    + " WHERE application_name = 'TogetherTunes' AND pid <> pg_backend_pid()").close();
        }
    }

    @Test
    void readRunsInAReadOnlyTransaction() {
        SQLException e = assertThrows(SQLException.class, () -> db.read(c -> {
            insertParent(c, "a");
            return null;
        }));
        assertEquals("25006", e.getSQLState()); // read_only_sql_transaction
    }

    @Test
    void poolNeverOpensMoreThanItsSizeOnTheServer() throws Exception {
        concurrently(30, () -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SELECT pg_sleep(0.02)");
            }
            return null;
        }));
        int onServer = serverSideConnections();
        assertTrue(onServer >= 1 && onServer <= POOL_SIZE, "server-side connections: " + onServer);
        assertEquals(onServer, db.openConnections());
    }

    @Test
    void idleConnectionsAreReusedNotReopened() throws SQLException {
        Connection first = db.read(c -> c.unwrap(Connection.class));
        for (int i = 0; i < 20; i++) {
            assertSame(first, db.read(c -> c.unwrap(Connection.class)));
        }
        assertEquals(1, db.openConnections());
    }

    @Test
    void recoversWhenTheServerDropsConnections() throws Exception {
        // Validate on every borrow, so idle connections killed by the server are noticed.
        db.close();
        db = open(new Database.PoolSettings(POOL_SIZE, Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ZERO));
        concurrently(10, () -> db.read(c -> {
            pause(20);
            return counter(c);
        }));
        terminateOurConnections();
        assertEquals(0L, (long) db.<Long>read(DatabaseContract::counter));
        db.write(c -> {
            insertParent(c, "after-restart");
            return null;
        });
        assertEquals(1L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void connectionKilledMidTransactionIsDiscarded() throws Exception {
        SQLException e = assertThrows(SQLException.class, () -> db.write(c -> {
            insertParent(c, "lost");
            terminateOurConnections();
            insertParent(c, "fails");
            return null;
        }));
        assertTrue(e.getSQLState() != null && (e.getSQLState().startsWith("08") || e.getSQLState().startsWith("57P")),
                "SQLState " + e.getSQLState());
        assertEquals(0, db.openConnections(), "broken connection must not go back to the pool");
        assertEquals(0L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void badCredentialsFailAtOpen() {
        Properties wrong = credentials();
        wrong.setProperty("password", "wrong");
        assertThrows(SQLException.class, () -> Database.open(postgres.getJdbcUrl(), wrong, settings()));
    }
}
