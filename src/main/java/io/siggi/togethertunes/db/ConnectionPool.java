package io.siggi.togethertunes.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A small, bounded JDBC connection pool (the project uses no pool library).
 *
 * <p>At most {@code maxConnections} connections exist at once; {@link #borrow} waits up to
 * {@code borrowTimeout} for one and then fails with {@link SQLTransientConnectionException}.
 * Idle connections are reused last-in first-out, so a quiet pool keeps reusing the same
 * connection. A connection is replaced when it is older than {@code maxLifetime}, and one that
 * sat idle for longer than {@code validateAfterIdle} is checked with {@link
 * Connection#isValid} before it is handed out. Connections are opened lazily.
 *
 * <p>Callers return every borrowed connection with {@link #release}, saying whether it is
 * broken (then it's closed instead of reused). Callers also reset its state (auto-commit,
 * read-only) before returning it; {@link Database} does all of this, so nothing else in the
 * application uses the pool directly. Uses only {@link ReentrantLock} and {@link Semaphore},
 * so waiting virtual threads don't pin their carrier.
 */
final class ConnectionPool implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ConnectionPool.class.getName());
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    /** Opens and initialises a new connection. */
    @FunctionalInterface
    interface Factory {
        Connection open() throws SQLException;
    }

    /** A borrowed connection plus the bookkeeping the pool needs. */
    static final class Lease {
        final Connection connection;
        final long createdNanos;
        long lastReleasedNanos;

        private Lease(Connection connection, long createdNanos) {
            this.connection = connection;
            this.createdNanos = createdNanos;
            this.lastReleasedNanos = createdNanos;
        }
    }

    private final String name;
    private final Factory factory;
    private final long borrowTimeoutNanos;
    private final long maxLifetimeNanos;
    private final long validateAfterIdleNanos;
    private final Semaphore permits;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Lease> idle = new ArrayDeque<>();
    private final AtomicInteger open = new AtomicInteger();
    private volatile boolean closed;

    ConnectionPool(String name, Factory factory, int maxConnections, Duration borrowTimeout,
                   Duration maxLifetime, Duration validateAfterIdle) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be at least 1");
        }
        this.name = name;
        this.factory = factory;
        this.borrowTimeoutNanos = borrowTimeout.toNanos();
        this.maxLifetimeNanos = maxLifetime.toNanos();
        this.validateAfterIdleNanos = validateAfterIdle.toNanos();
        this.permits = new Semaphore(maxConnections, true);
    }

    /**
     * Takes an idle connection or opens a new one, waiting for a free slot if the pool is at
     * its limit. Every lease must be given back with {@link #release}.
     */
    Lease borrow() throws SQLException {
        if (closed) {
            throw new SQLException("Connection pool " + name + " is closed");
        }
        try {
            if (!permits.tryAcquire(borrowTimeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new SQLTransientConnectionException("No connection available from pool " + name
                        + " within " + Duration.ofNanos(borrowTimeoutNanos).toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection from pool " + name, e);
        }
        try {
            while (true) {
                Lease lease = pollIdle();
                if (lease == null) {
                    return openNew();
                }
                if (usable(lease)) {
                    return lease;
                }
                discard(lease);
            }
        } catch (SQLException | RuntimeException | Error e) {
            permits.release();
            throw e;
        }
    }

    /**
     * Gives a borrowed connection back. A {@code broken} one, an expired one, or any connection
     * returned after {@link #close} is closed instead of kept.
     */
    void release(Lease lease, boolean broken) {
        try {
            long now = System.nanoTime();
            if (broken || expired(lease, now)) {
                discard(lease);
                return;
            }
            lease.lastReleasedNanos = now;
            lock.lock();
            try {
                if (!closed) {
                    idle.addLast(lease);
                    return;
                }
            } finally {
                lock.unlock();
            }
            discard(lease);
        } finally {
            permits.release();
        }
    }

    /** Number of open connections, idle or borrowed. */
    int openConnections() {
        return open.get();
    }

    /** Number of idle connections. */
    int idleConnections() {
        lock.lock();
        try {
            return idle.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the idle connections and stops lending; borrowed connections are closed when they
     * are released. Idempotent.
     */
    @Override
    public void close() {
        ArrayDeque<Lease> toClose;
        lock.lock();
        try {
            closed = true;
            toClose = new ArrayDeque<>(idle);
            idle.clear();
        } finally {
            lock.unlock();
        }
        for (Lease lease : toClose) {
            discard(lease);
        }
    }

    private Lease pollIdle() {
        lock.lock();
        try {
            return idle.pollLast();
        } finally {
            lock.unlock();
        }
    }

    private Lease openNew() throws SQLException {
        Connection connection = factory.open();
        open.incrementAndGet();
        return new Lease(connection, System.nanoTime());
    }

    private boolean usable(Lease lease) {
        long now = System.nanoTime();
        if (expired(lease, now)) {
            return false;
        }
        if (now - lease.lastReleasedNanos < validateAfterIdleNanos) {
            return true;
        }
        try {
            return lease.connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean expired(Lease lease, long now) {
        return now - lease.createdNanos >= maxLifetimeNanos;
    }

    private void discard(Lease lease) {
        open.decrementAndGet();
        try {
            lease.connection.close();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.DEBUG, "Closing a connection of pool " + name + " failed", e);
        }
    }
}
