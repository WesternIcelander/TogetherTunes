package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DatabaseContract} on a SQLite file, plus the SQLite-specific connection handling. */
class SqliteDatabaseTest extends DatabaseContract {
    @TempDir
    Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("sub/dir/test.db");
    }

    @Override
    protected Database open(Database.PoolSettings settings) throws SQLException {
        return Database.open(url(), new java.util.Properties(), settings);
    }

    @Override
    protected int maxOpenConnections() {
        return POOL_SIZE + 1; // readers plus the single writer
    }

    private static String pragma(Connection c, String name) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void assertPragmas(Connection c) throws SQLException {
        assertEquals("wal", pragma(c, "journal_mode"));
        assertEquals("1", pragma(c, "foreign_keys"));
        assertEquals("5000", pragma(c, "busy_timeout"));
    }

    @Test
    void createsParentDirectoriesAndUsesWal() throws SQLException {
        assertTrue(Files.isRegularFile(dir.resolve("sub/dir/test.db")));
        db.write(c -> {
            assertPragmas(c);
            return null;
        });
        db.read(c -> {
            assertPragmas(c);
            return null;
        });
        assertTrue(Files.exists(dir.resolve("sub/dir/test.db-wal")));
    }

    @Test
    void readsUseReadOnlyConnectionsAndWritesTheSingleWriter() throws SQLException {
        Connection w1 = db.write(c -> c.unwrap(Connection.class));
        Connection w2 = db.write(c -> c.unwrap(Connection.class));
        assertSame(w1, w2, "single write connection");
        Connection r = db.read(c -> {
            assertTrue(c.isReadOnly());
            return c.unwrap(Connection.class);
        });
        assertNotSame(w1, r);
        assertFalse(db.write(Connection::isReadOnly));
    }

    @Test
    void writesAreSerialised() {
        // Read-modify-write in Java would lose updates if two writers overlapped.
        int n = 100;
        concurrently(n, () -> db.write(c -> {
            long value = counter(c);
            pause(1);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE counter SET n = " + (value + 1) + " WHERE id = 1");
            }
            return null;
        }));
        try {
            assertEquals((long) n, (long) db.<Long>read(DatabaseContract::counter));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readersRunWhileAWriteIsOpenAndSeeCommittedState() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch readDone = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> writer = ex.submit(() -> db.write(c -> {
                insertParent(c, "a");
                inserted.countDown();
                try {
                    assertTrue(readDone.await(10, TimeUnit.SECONDS), "reader was blocked by the writer");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            long seen = db.read(c -> count(c, "parent"));
            readDone.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertEquals(0, seen, "uncommitted row visible to a reader");
        }
        assertEquals(1L, (long) db.<Long>read(c -> count(c, "parent")));
    }

    @Test
    void anotherProcessHoldingTheWriteLockIsWaitedFor() throws Exception {
        // A second, independent connection (like another process) holds the write lock briefly.
        try (Connection other = java.sql.DriverManager.getConnection(url());
             Statement st = other.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            st.executeUpdate("UPDATE counter SET n = 41 WHERE id = 1");
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Object> writer = ex.submit(() -> db.write(c -> {
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("UPDATE counter SET n = n + 1 WHERE id = 1");
                    }
                    return null;
                }));
                Thread.sleep(300);
                assertFalse(writer.isDone(), "writer should wait on busy_timeout");
                st.execute("COMMIT");
                writer.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(42L, (long) db.<Long>read(DatabaseContract::counter));
    }

    @Test
    void inMemoryDatabaseUsesOneConnection() throws SQLException {
        try (Database mem = Database.open("jdbc:sqlite::memory:")) {
            mem.write(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE t (x TEXT)");
                    st.execute("INSERT INTO t VALUES ('Ä')");
                    assertEquals("1", pragma(c, "foreign_keys"));
                    assertEquals("5000", pragma(c, "busy_timeout"));
                }
                return null;
            });
            // Reads see the same database (they share the connection).
            assertEquals(1L, (long) mem.<Long>read(c -> count(c, "t")));
            assertEquals("ä", mem.read(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT " + mem.dialect().lower("x") + " FROM t")) {
                    rs.next();
                    return rs.getString(1);
                }
            }));
            assertEquals(1, mem.openConnections());
        }
    }

    @Test
    void inMemoryUrlDetection() {
        assertTrue(Database.isInMemory(""));
        assertTrue(Database.isInMemory(":memory:"));
        assertTrue(Database.isInMemory("file::memory:?cache=shared"));
        assertTrue(Database.isInMemory("file:test?mode=memory&cache=shared"));
        assertFalse(Database.isInMemory("data/db/media.db"));
        assertFalse(Database.isInMemory("/tmp/memory.db"));
    }

    @Test
    void closeEndsAllConnections() throws SQLException {
        Connection w = db.write(c -> c.unwrap(Connection.class));
        Connection r = db.read(c -> c.unwrap(Connection.class));
        db.close();
        assertTrue(w.isClosed());
        assertTrue(r.isClosed());
        assertEquals(0, db.openConnections());
        // Closing the last connection checkpoints and removes the WAL file.
        assertFalse(Files.exists(dir.resolve("sub/dir/test.db-wal")));
    }
}
