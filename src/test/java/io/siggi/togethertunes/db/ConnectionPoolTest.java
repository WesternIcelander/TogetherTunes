package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectionPoolTest {
    private final List<Connection> opened = new ArrayList<>();
    private final AtomicInteger opens = new AtomicInteger();
    private ConnectionPool pool;

    private ConnectionPool pool(int max, Duration borrowTimeout, Duration maxLifetime, Duration validateAfterIdle) {
        pool = new ConnectionPool("test", () -> {
            opens.incrementAndGet();
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            synchronized (opened) {
                opened.add(c);
            }
            return c;
        }, max, borrowTimeout, maxLifetime, validateAfterIdle);
        return pool;
    }

    private ConnectionPool pool(int max) {
        return pool(max, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (pool != null) {
            pool.close();
        }
        for (Connection c : opened) {
            c.close();
        }
    }

    @Test
    void opensLazilyAndReusesLastReleased() throws SQLException {
        ConnectionPool p = pool(3);
        assertEquals(0, p.openConnections());
        ConnectionPool.Lease a = p.borrow();
        ConnectionPool.Lease b = p.borrow();
        assertEquals(2, p.openConnections());
        p.release(a, false);
        p.release(b, false);
        assertEquals(2, p.idleConnections());
        assertSame(b.connection, p.borrow().connection, "LIFO reuse");
        assertEquals(2, opens.get());
    }

    @Test
    void borrowWaitsForReleaseThenTimesOut() throws Exception {
        ConnectionPool p = pool(1, Duration.ofMillis(200), Duration.ofHours(1), Duration.ofHours(1));
        ConnectionPool.Lease a = p.borrow();
        long start = System.nanoTime();
        assertThrows(SQLTransientConnectionException.class, p::borrow);
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(190));

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ConnectionPool.Lease> waiting = ex.submit(p::borrow);
            Thread.sleep(50);
            p.release(a, false);
            assertSame(a.connection, waiting.get(5, TimeUnit.SECONDS).connection);
        }
    }

    @Test
    void neverExceedsMaxConnections() throws Exception {
        ConnectionPool p = pool(3);
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(ex.submit(() -> {
                    ConnectionPool.Lease l = p.borrow();
                    try {
                        peak.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inUse.decrementAndGet();
                    } finally {
                        p.release(l, false);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        assertTrue(peak.get() <= 3, "peak " + peak.get());
        assertTrue(p.openConnections() <= 3);
    }

    @Test
    void brokenConnectionIsClosedAndReplaced() throws SQLException {
        ConnectionPool p = pool(1);
        ConnectionPool.Lease a = p.borrow();
        p.release(a, true);
        assertTrue(a.connection.isClosed());
        assertEquals(0, p.openConnections());
        assertNotSame(a.connection, p.borrow().connection);
    }

    @Test
    void invalidIdleConnectionIsReplacedOnBorrow() throws SQLException {
        ConnectionPool p = pool(1, Duration.ofSeconds(5), Duration.ofHours(1), Duration.ZERO);
        ConnectionPool.Lease a = p.borrow();
        p.release(a, false);
        a.connection.close(); // dies while idle, e.g. the server restarted
        ConnectionPool.Lease b = p.borrow();
        assertNotSame(a.connection, b.connection);
        assertTrue(b.connection.isValid(1));
        assertEquals(1, p.openConnections());
    }

    @Test
    void expiredConnectionIsReplaced() throws Exception {
        ConnectionPool p = pool(1, Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofHours(1));
        ConnectionPool.Lease a = p.borrow();
        p.release(a, false);
        Thread.sleep(80);
        ConnectionPool.Lease b = p.borrow();
        assertNotSame(a.connection, b.connection);
        assertTrue(a.connection.isClosed());
        assertEquals(1, p.openConnections());
    }

    @Test
    void factoryFailureFreesTheSlot() throws SQLException {
        AtomicInteger calls = new AtomicInteger();
        pool = new ConnectionPool("failing", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new SQLException("down");
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            opened.add(c);
            return c;
        }, 1, Duration.ofMillis(100), Duration.ofHours(1), Duration.ofHours(1));
        assertThrows(SQLException.class, pool::borrow);
        pool.borrow(); // the failed open didn't leak the only permit
    }

    @Test
    void closeClosesIdleNowAndBorrowedOnRelease() throws SQLException {
        ConnectionPool p = pool(2);
        ConnectionPool.Lease a = p.borrow();
        ConnectionPool.Lease b = p.borrow();
        p.release(a, false);
        p.close();
        assertTrue(a.connection.isClosed());
        assertTrue(!b.connection.isClosed());
        p.release(b, false);
        assertTrue(b.connection.isClosed());
        assertEquals(0, p.openConnections());
        assertThrows(SQLException.class, p::borrow);
    }

    @Test
    void interruptedBorrowKeepsInterruptFlag() throws Exception {
        ConnectionPool p = pool(1);
        ConnectionPool.Lease a = p.borrow();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger outcome = new AtomicInteger();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                p.borrow();
                outcome.set(1);
            } catch (SQLException e) {
                outcome.set(Thread.currentThread().isInterrupted() ? 2 : 3);
            }
            done.countDown();
        });
        Thread.sleep(50);
        t.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, outcome.get());
        p.release(a, false);
    }
}
