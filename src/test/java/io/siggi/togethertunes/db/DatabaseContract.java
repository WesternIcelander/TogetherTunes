package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behaviour of {@link Database} that must hold for both dialects. */
abstract class DatabaseContract {
    protected static final int POOL_SIZE = 3;

    protected Database db;

    /** Opens a fresh, empty database with the given settings. */
    protected abstract Database open(Database.PoolSettings settings) throws SQLException;

    protected Database.PoolSettings settings() {
        return new Database.PoolSettings(POOL_SIZE, Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(10));
    }

    @BeforeEach
    void setUp() throws SQLException {
        db = open(settings());
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS child");
                st.execute("DROP TABLE IF EXISTS parent");
                st.execute("DROP TABLE IF EXISTS counter");
                st.execute("CREATE TABLE parent (id TEXT PRIMARY KEY, name TEXT)");
                st.execute("CREATE TABLE child (id TEXT PRIMARY KEY, parent_id TEXT NOT NULL REFERENCES parent (id))");
                st.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, n BIGINT NOT NULL)");
                st.execute("INSERT INTO counter (id, n) VALUES (1, 0)");
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    protected static void insertParent(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO parent (id, name) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "name " + id);
            ps.executeUpdate();
        }
    }

    protected static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    protected static long counter(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT n FROM counter WHERE id = 1")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void afterCommitRunsOnceTheOutermostTransactionCommittedAndNotOnRollback() throws Exception {
        List<String> ran = new ArrayList<>();
        db.write(c -> {
            insertParent(c, "a");
            db.write(inner -> {
                db.afterCommit(() -> {
                    // runs after the commit, with the connection back in the pool: it sees the row
                    // and may use the database itself
                    try {
                        ran.add("inner:" + db.read(r -> count(r, "parent")));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
                return null;
            });
            db.afterCommit(() -> ran.add("outer"));
            assertEquals(List.of(), ran);
            return null;
        });
        assertEquals(List.of("inner:1", "outer"), ran);

        ran.clear();
        assertThrows(SQLException.class, () -> db.write(c -> {
            db.afterCommit(() -> ran.add("rolled back"));
            throw new SQLException("boom");
        }));
        db.afterCommit(() -> ran.add("no transaction"));
        assertEquals(List.of("no transaction"), ran);
    }

    @Test
    void writeCommitsWhenWorkReturns() throws SQLException {
        String result = db.write(c -> {
            insertParent(c, "a");
            return "done";
        });
        assertEquals("done", result);
        assertEquals(1L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void writeRollsBackWhenWorkThrows() throws SQLException {
        assertThrows(IllegalStateException.class, () -> db.write(c -> {
            insertParent(c, "a");
            throw new IllegalStateException("boom");
        }));
        SQLException e = assertThrows(SQLException.class, () -> db.write(c -> {
            insertParent(c, "b");
            insertParent(c, "b"); // duplicate key
            return null;
        }));
        assertTrue(e.getMessage() != null);
        assertEquals(0L, (long) db.<Long>read(c -> count(c, "parent")));
        // The connection is still usable afterwards.
        db.write(c -> {
            insertParent(c, "c");
            return null;
        });
        assertEquals(1L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void readCannotWrite() throws SQLException {
        assertThrows(SQLException.class, () -> db.read(c -> {
            insertParent(c, "a");
            return null;
        }));
        assertEquals(0L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void foreignKeysAreEnforced() {
        SQLException e = assertThrows(SQLException.class, () -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO child (id, parent_id) VALUES ('x', 'missing')");
            }
            return null;
        }));
        assertTrue(e.getMessage().toLowerCase().contains("foreign key"), e.getMessage());
    }

    @Test
    void nestedCallsJoinTheOuterTransaction() throws SQLException {
        assertThrows(IllegalStateException.class, () -> db.write(outer -> {
            insertParent(outer, "a");
            db.write(inner -> {
                assertSame(outer, inner);
                insertParent(inner, "b");
                return null;
            });
            // A nested read sees the outer transaction's uncommitted rows.
            assertEquals(2L, (long) db.<Long>read(inner -> count(inner, "parent")));
            throw new IllegalStateException("roll back everything");
        }));
        assertEquals(0L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void writeInsideReadIsRefused() throws SQLException {
        assertThrows(IllegalStateException.class, () -> db.read(c -> db.write(w -> null)));
        // Both kinds still work afterwards.
        db.write(c -> {
            insertParent(c, "a");
            return null;
        });
        assertEquals(1L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void dialectIsInitialisedOnEveryConnection() throws SQLException {
        Dialect d = db.dialect();
        String sql = "SELECT " + d.lower("'ÄBC'");
        SqlCheck check = c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                assertEquals("äbc", rs.getString(1));
            }
        };
        db.write(c -> {
            check.run(c);
            return null;
        });
        // Several readers at once, so more than one read connection gets opened.
        concurrently(10, () -> db.read(c -> {
            check.run(c);
            pause(20);
            return null;
        }));
    }

    @Test
    void concurrentWritesAllCommit() throws Exception {
        int n = 100;
        concurrently(n, () -> db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE counter SET n = n + 1 WHERE id = 1");
            }
            return null;
        }));
        assertEquals((long) n, (long) db.<Long>read(DatabaseContract::counter));
    }

    @Test
    void connectionCountStaysBounded() throws Exception {
        concurrently(40, () -> db.read(c -> {
            pause(10);
            return counter(c);
        }));
        assertTrue(db.openConnections() <= maxOpenConnections(), "open: " + db.openConnections());
    }

    /** Upper bound for {@link Database#openConnections()} with {@link #settings()}. */
    protected abstract int maxOpenConnections();

    @FunctionalInterface
    interface SqlCheck {
        void run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    interface Task {
        Object run() throws Exception;
    }

    protected static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Runs {@code task} on {@code n} virtual threads at once and rethrows the first failure. */
    protected static void concurrently(int n, Task task) {
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(ex.submit(task::run));
            }
            for (Future<Object> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
