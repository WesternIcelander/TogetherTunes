package io.siggi.togethertunes.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.sqlite.SQLiteConfig;

/**
 * The application's database: connections, transactions and the {@link Dialect}.
 *
 * <p>All database work goes through {@link #read} and {@link #write}. Each runs a callback in
 * one transaction on a pooled connection: commit when the callback returns, roll back when it
 * throws. The callback must not close the connection or change its auto-commit, read-only or
 * isolation settings. A {@code read} or {@code write} called inside another one on the same
 * thread joins the outer transaction (and doesn't commit on its own); a {@code write} inside a
 * {@code read} is refused with {@link IllegalStateException}. {@link #afterCommit} runs code once
 * the outermost transaction has committed.
 *
 * <p><b>SQLite</b> ({@code jdbc:sqlite:}): every connection gets {@code journal_mode=WAL},
 * {@code foreign_keys=ON} and {@code busy_timeout=5000}, and {@link Dialect#initConnection}.
 * Writes go through a <em>single</em> write connection (a pool of one, so writers queue up in
 * this process instead of failing with {@code SQLITE_BUSY}); its transactions start with
 * {@code BEGIN IMMEDIATE}, so a lock held by another process is waited for (busy timeout) up
 * front instead of failing halfway. Reads use a small pool of read-only connections, which in
 * WAL mode run concurrently with the writer and see the last committed state. An in-memory
 * database ({@code jdbc:sqlite::memory:} and friends) exists per connection, so it uses the
 * one write connection for reads as well. For a file database, missing parent directories are
 * created.
 *
 * <p><b>PostgreSQL</b> ({@code jdbc:postgresql:}): one pool for reads and writes (default 10
 * connections); a {@code read} runs as a {@code READ ONLY} transaction. Credentials come from
 * the URL ({@code ?user=...&password=...}) or the {@code Properties} given to {@link #open}.
 *
 * <p>A connection that fails with a connection-level error (SQLState class {@code 08}, an
 * administrator shutdown {@code 57P01..03}, or a closed connection) is dropped, not reused, and
 * idle connections are validated before reuse, so the pool recovers from a database restart.
 */
public final class Database implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Database.class.getName());

    /** SQLite busy timeout in milliseconds ({@code docs/data-model.md}). */
    public static final int SQLITE_BUSY_TIMEOUT_MS = 5000;

    /** One callback run in a transaction. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Pool sizing and timing.
     *
     * @param maxConnections    PostgreSQL: connections in the pool; SQLite: read connections
     *                          (the write connection comes on top)
     * @param borrowTimeout     how long {@code read}/{@code write} wait for a free connection
     * @param maxLifetime       connections older than this are closed and replaced
     * @param validateAfterIdle connections idle for longer are checked before reuse
     */
    public record PoolSettings(int maxConnections, Duration borrowTimeout, Duration maxLifetime,
                               Duration validateAfterIdle) {
        public PoolSettings {
            if (maxConnections < 1) {
                throw new IllegalArgumentException("maxConnections must be at least 1");
            }
        }

        /** Defaults for a dialect: 4 SQLite readers, 10 PostgreSQL connections. */
        public static PoolSettings defaults(Dialect dialect) {
            int size = dialect instanceof SqliteDialect ? 4 : 10;
            return new PoolSettings(size, Duration.ofSeconds(30), Duration.ofMinutes(30), Duration.ofSeconds(10));
        }
    }

    private record Tx(Connection connection, boolean write, List<Runnable> afterCommit) {
    }

    private final Dialect dialect;
    private final ConnectionPool writePool;
    private final ConnectionPool readPool;
    private final boolean setReadOnly;
    private final ThreadLocal<Tx> current = new ThreadLocal<>();

    private Database(Dialect dialect, ConnectionPool writePool, ConnectionPool readPool, boolean setReadOnly) {
        this.dialect = dialect;
        this.writePool = writePool;
        this.readPool = readPool;
        this.setReadOnly = setReadOnly;
    }

    /** Opens the database at {@code jdbcUrl} with default pool settings. */
    public static Database open(String jdbcUrl) throws SQLException {
        return open(jdbcUrl, new Properties(), PoolSettings.defaults(Dialect.forJdbcUrl(jdbcUrl)));
    }

    /**
     * Opens the database at {@code jdbcUrl}. {@code properties} are passed to the driver (e.g.
     * {@code user}, {@code password}). Opens one connection right away, so a bad URL, missing
     * credentials or an unreachable server fail here.
     */
    public static Database open(String jdbcUrl, Properties properties, PoolSettings settings) throws SQLException {
        Dialect dialect = Dialect.forJdbcUrl(jdbcUrl);
        Database db = switch (dialect) {
            case SqliteDialect sqlite -> openSqlite(jdbcUrl, properties, settings);
            case PostgresDialect postgres -> openPostgres(jdbcUrl, properties, settings);
        };
        try {
            db.write(connection -> null);
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw e;
        }
        return db;
    }

    private static Database openSqlite(String url, Properties properties, PoolSettings settings) throws SQLException {
        String target = url.substring("jdbc:sqlite:".length());
        boolean memory = isInMemory(target);
        if (!memory) {
            createParentDirectories(target);
        }

        SQLiteConfig writeConfig = sqliteConfig(properties);
        writeConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        writeConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        Properties writeProps = writeConfig.toProperties();
        ConnectionPool writePool = new ConnectionPool("sqlite-write", () -> {
            Connection c = DriverManager.getConnection(url, writeProps);
            return init(SqliteDialect.INSTANCE, c, memory ? null : "wal");
        }, 1, settings.borrowTimeout(), settings.maxLifetime(), settings.validateAfterIdle());
        if (memory) {
            return new Database(SqliteDialect.INSTANCE, writePool, writePool, false);
        }

        // Readers don't set journal_mode: the write connection made the file WAL (persistent),
        // and changing it needs write access.
        SQLiteConfig readConfig = sqliteConfig(properties);
        readConfig.setReadOnly(true);
        Properties readProps = readConfig.toProperties();
        ConnectionPool readPool = new ConnectionPool("sqlite-read", () -> {
            Connection c = DriverManager.getConnection(url, readProps);
            return init(SqliteDialect.INSTANCE, c, "wal");
        }, settings.maxConnections(), settings.borrowTimeout(), settings.maxLifetime(), settings.validateAfterIdle());
        return new Database(SqliteDialect.INSTANCE, writePool, readPool, false);
    }

    private static SQLiteConfig sqliteConfig(Properties properties) {
        // SQLiteConfig keeps (and toProperties() returns) the Properties object it is given, so
        // each config needs its own copy or the read-only flag would leak into the writer's.
        Properties copy = new Properties();
        copy.putAll(properties);
        SQLiteConfig config = new SQLiteConfig(copy);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS);
        return config;
    }

    private static Database openPostgres(String url, Properties properties, PoolSettings settings) {
        Properties props = new Properties();
        props.putAll(properties);
        props.putIfAbsent("ApplicationName", "TogetherTunes");
        ConnectionPool pool = new ConnectionPool("postgres", () -> {
            Connection c = DriverManager.getConnection(url, props);
            return init(PostgresDialect.INSTANCE, c, null);
        }, settings.maxConnections(), settings.borrowTimeout(), settings.maxLifetime(), settings.validateAfterIdle());
        return new Database(PostgresDialect.INSTANCE, pool, pool, true);
    }

    /** Runs the dialect's per-connection setup; for SQLite also checks the journal mode. */
    private static Connection init(Dialect dialect, Connection c, String expectedJournalMode) throws SQLException {
        try {
            dialect.initConnection(c);
            if (expectedJournalMode != null) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                    String mode = rs.next() ? rs.getString(1) : null;
                    if (!expectedJournalMode.equalsIgnoreCase(mode)) {
                        LOG.log(System.Logger.Level.WARNING, "SQLite journal_mode is " + mode + ", not "
                                + expectedJournalMode + " (is the database on a filesystem without shared memory?)");
                    }
                }
            }
            return c;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** Whether the part after {@code jdbc:sqlite:} names an in-memory database. */
    static boolean isInMemory(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.startsWith(":memory:") || lower.startsWith("file::memory:")
                || lower.contains("mode=memory");
    }

    private static void createParentDirectories(String target) throws SQLException {
        if (target.startsWith("file:") || target.startsWith(":")) {
            return; // URI or special form (":resource:"); leave it to the driver.
        }
        int query = target.indexOf('?');
        String file = query >= 0 ? target.substring(0, query) : target;
        try {
            Path parent = Path.of(file).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException | InvalidPathException e) {
            throw new SQLException("Cannot create the directory for the SQLite database " + file + ": " + e.getMessage(), e);
        }
    }

    /** The SQL dialect of this database. */
    public Dialect dialect() {
        return dialect;
    }

    /**
     * Runs {@code work} in a read-only transaction and returns its result. SQLite: on a reader
     * connection, concurrently with writes. PostgreSQL: {@code READ ONLY} transaction.
     */
    public <T> T read(SqlWork<T> work) throws SQLException {
        return run(readPool, false, work);
    }

    /**
     * Runs {@code work} in a read-write transaction and returns its result: committed if it
     * returns, rolled back if it throws. SQLite writes are serialised on one connection, so keep
     * these short (no network or file I/O inside).
     */
    public <T> T write(SqlWork<T> work) throws SQLException {
        return run(writePool, true, work);
    }

    private <T> T run(ConnectionPool pool, boolean write, SqlWork<T> work) throws SQLException {
        Tx outer = current.get();
        if (outer != null) {
            if (write && !outer.write()) {
                throw new IllegalStateException("write() inside read(): use write() for the outer transaction");
            }
            return work.run(outer.connection());
        }
        List<Runnable> after = new ArrayList<>();
        T result = runOutermost(pool, write, work, after);
        // After the connection went back to the pool: an action may use the database itself.
        runAfterCommit(after);
        return result;
    }

    private <T> T runOutermost(ConnectionPool pool, boolean write, SqlWork<T> work, List<Runnable> after)
            throws SQLException {
        ConnectionPool.Lease lease = pool.borrow();
        Connection c = lease.connection;
        boolean broken = false;
        try {
            if (setReadOnly) {
                c.setReadOnly(!write);
            }
            c.setAutoCommit(false);
            current.set(new Tx(c, write, after));
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                broken = isConnectionFailure(c, e);
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                    broken = true;
                }
                throw e;
            } finally {
                current.remove();
            }
        } catch (SQLException | RuntimeException | Error e) {
            broken |= isConnectionFailure(c, e);
            throw e;
        } finally {
            if (!broken) {
                try {
                    c.setAutoCommit(true);
                    if (setReadOnly) {
                        c.setReadOnly(false);
                    }
                } catch (SQLException e) {
                    broken = true;
                }
            }
            pool.release(lease, broken);
        }
    }

    /**
     * Runs {@code action} once the current transaction on this thread has committed (the outermost
     * one, if {@code write}s are nested), or right away if there is none. Dropped if the
     * transaction rolls back. For telling other parts of the server about a change only once
     * they can read it. Actions run on the committing thread, in order; one that throws is
     * logged and the rest still run.
     */
    public void afterCommit(Runnable action) {
        Objects.requireNonNull(action);
        Tx tx = current.get();
        if (tx == null) {
            runAfterCommit(List.of(action));
        } else {
            tx.afterCommit().add(action);
        }
    }

    private static void runAfterCommit(List<Runnable> actions) {
        for (Runnable r : actions) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "After-commit action failed", e);
            }
        }
    }

    /** Whether {@code failure} means the connection itself is unusable. */
    static boolean isConnectionFailure(Connection c, Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("08") || state.startsWith("57P")) {
                    return true;
                }
            }
        }
        try {
            return c.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    /** Number of open connections over all pools (for tests and diagnostics). */
    int openConnections() {
        return readPool == writePool ? writePool.openConnections()
                : writePool.openConnections() + readPool.openConnections();
    }

    /**
     * Closes all idle connections and stops handing out new ones; connections still in use are
     * closed when their transaction ends. For SQLite the write connection is closed last, which
     * checkpoints the WAL.
     */
    @Override
    public void close() {
        if (readPool != writePool) {
            readPool.close();
        }
        writePool.close();
    }
}
