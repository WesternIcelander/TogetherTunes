package io.siggi.togethertunes.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Applies the numbered SQL migrations of a dialect: {@code db/migrations/sqlite/NNNN_name.sql}
 * or {@code db/migrations/postgres/NNNN_name.sql} on the classpath ({@code
 * src/main/resources/db/migrations/...} in the source tree).
 *
 * <p>Each pending migration runs in <em>its own</em> transaction ({@link Database#write}),
 * together with the insert of its row into {@code schema_migrations} (version, name, checksum,
 * applied_at in epoch ms), so a failing migration leaves no trace and the next start retries
 * it. Migrations run in version order. Rules for migration files:
 *
 * <ul>
 *   <li>Every schema change has a file for <b>both</b> dialects, with the same number and name.
 *   <li>No transaction control ({@code BEGIN}, {@code COMMIT}, ...) and nothing that can't run
 *       inside a transaction (PostgreSQL {@code CREATE INDEX CONCURRENTLY}, SQLite {@code PRAGMA
 *       foreign_keys}, {@code VACUUM}).
 *   <li>An applied file is never edited: its checksum is checked on every start. Add a new
 *       migration instead.
 * </ul>
 *
 * <p>Startup fails ({@link MigrationException}) if an applied migration's file is missing or was
 * changed, or if a new file has a lower version than one already applied (it would never run in
 * the order the author intended). Concurrent runners (several processes on one PostgreSQL
 * database) are safe: each migration transaction takes a transaction-scoped advisory lock and
 * re-checks whether its version was applied meanwhile; SQLite write transactions are already
 * exclusive ({@code BEGIN IMMEDIATE}).
 */
public final class MigrationRunner {
    private static final System.Logger LOG = System.getLogger(MigrationRunner.class.getName());

    /** Classpath directory holding one subdirectory per dialect ({@link Dialect#name()}). */
    public static final String CLASSPATH_ROOT = "db/migrations";

    /** The bookkeeping table. */
    public static final String TABLE = "schema_migrations";

    /** Key of the PostgreSQL advisory lock that serialises migration runners. */
    static final long POSTGRES_LOCK_KEY = 0x54546d6967726174L; // "TTmigrat"

    private final Database database;
    private final List<Migration> migrations;

    /**
     * A runner for {@code migrations} (in any order; sorted by version here).
     *
     * @throws MigrationException if two migrations have the same version
     */
    public MigrationRunner(Database database, List<Migration> migrations) {
        this.database = database;
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::version));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version() == sorted.get(i - 1).version()) {
                throw new MigrationException("Two migrations with version " + sorted.get(i).version() + ": "
                        + sorted.get(i - 1) + " and " + sorted.get(i));
            }
        }
        this.migrations = List.copyOf(sorted);
    }

    /** Applies the application's migrations for the database's dialect from the classpath. */
    public static List<Migration> migrate(Database database) throws SQLException {
        List<Migration> migrations = loadClasspath(MigrationRunner.class.getClassLoader(), CLASSPATH_ROOT,
                database.dialect());
        return new MigrationRunner(database, migrations).migrate();
    }

    /** The migrations, sorted by version. */
    public List<Migration> migrations() {
        return migrations;
    }

    /**
     * Creates {@code schema_migrations} if needed, checks the applied migrations against the
     * files, and applies the pending ones, one transaction each.
     *
     * @return the migrations applied by this call, in order (empty if the schema was current)
     * @throws MigrationException if the applied history doesn't match the files
     * @throws SQLException       if a migration fails (its transaction is rolled back; earlier
     *                            migrations of this call stay applied)
     */
    public List<Migration> migrate() throws SQLException {
        database.write(c -> {
            lock(c);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "version INTEGER PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "checksum TEXT NOT NULL, "
                        + "applied_at BIGINT NOT NULL)");
            }
            return null;
        });

        Map<Integer, Applied> applied = database.read(MigrationRunner::readApplied);
        int highestApplied = verify(applied);

        List<Migration> done = new ArrayList<>();
        for (Migration m : migrations) {
            if (applied.containsKey(m.version())) {
                continue;
            }
            if (m.version() < highestApplied) {
                throw new MigrationException("Migration " + m + " is new, but version " + highestApplied
                        + " is already applied; give it a number above " + highestApplied);
            }
            boolean ran = database.write(c -> apply(c, m));
            if (ran) {
                LOG.log(System.Logger.Level.INFO, "Applied migration " + database.dialect().name() + "/" + m);
                done.add(m);
            }
        }
        return done;
    }

    /** One row of {@code schema_migrations}. */
    public record Applied(int version, String name, String checksum, long appliedAt) {
        String fileName() {
            return String.format("%04d_%s.sql", version, name);
        }
    }

    /** The rows of {@code schema_migrations}, by version (call after {@link #migrate}). */
    public Map<Integer, Applied> applied() throws SQLException {
        return database.read(MigrationRunner::readApplied);
    }

    private static Map<Integer, Applied> readApplied(Connection c) throws SQLException {
        Map<Integer, Applied> applied = new TreeMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, name, checksum, applied_at FROM " + TABLE)) {
            while (rs.next()) {
                Applied a = new Applied(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4));
                applied.put(a.version(), a);
            }
        }
        return applied;
    }

    /** Checks applied rows against the files; returns the highest applied version (0 if none). */
    private int verify(Map<Integer, Applied> applied) {
        Map<Integer, Migration> byVersion = new TreeMap<>();
        for (Migration m : migrations) {
            byVersion.put(m.version(), m);
        }
        int highest = 0;
        for (Applied a : applied.values()) {
            int version = a.version();
            highest = Math.max(highest, version);
            Migration m = byVersion.get(version);
            String appliedName = a.fileName();
            if (m == null) {
                throw new MigrationException("The database has migration " + appliedName
                        + " applied, but there is no such file (is the database from a newer version?)");
            }
            if (!m.checksum().equals(a.checksum())) {
                throw new MigrationException("Migration " + m + " was changed after it was applied as "
                        + appliedName + " (checksum mismatch); add a new migration instead of editing it");
            }
        }
        return highest;
    }

    /** Runs one migration in the caller's transaction; false if another runner applied it first. */
    private boolean apply(Connection c, Migration m) throws SQLException {
        lock(c);
        try (PreparedStatement check = c.prepareStatement("SELECT checksum FROM " + TABLE + " WHERE version = ?")) {
            check.setInt(1, m.version());
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    if (!m.checksum().equals(rs.getString(1))) {
                        throw new MigrationException("Migration " + m + " was applied concurrently with a different checksum");
                    }
                    return false;
                }
            }
        }
        List<String> statements;
        try {
            statements = SqlScript.split(m.sql());
        } catch (IllegalArgumentException e) {
            throw new MigrationException("Can't parse migration " + m + ": " + e.getMessage(), e);
        }
        try (Statement st = c.createStatement()) {
            for (String sql : statements) {
                rejectTransactionControl(m, sql);
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("Migration " + database.dialect().name() + "/" + m + " failed: "
                            + e.getMessage() + "\nStatement: " + sql, e.getSQLState(), e.getErrorCode(), e);
                }
            }
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO " + TABLE + " (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, m.version());
            insert.setString(2, m.name());
            insert.setString(3, m.checksum());
            insert.setLong(4, EpochMs.now());
            insert.executeUpdate();
        }
        return true;
    }

    private static void rejectTransactionControl(Migration m, String sql) {
        String first = sql.split("[\\s(;]+", 2)[0].toUpperCase(Locale.ROOT);
        switch (first) {
            case "BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "VACUUM" ->
                    throw new MigrationException("Migration " + m + " must not contain " + first
                            + ": each migration already runs in its own transaction");
            default -> {
            }
        }
    }

    /** Serialises concurrent runners on PostgreSQL until the current transaction ends. */
    private void lock(Connection c) throws SQLException {
        if (database.dialect() instanceof PostgresDialect) {
            try (Statement st = c.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(" + POSTGRES_LOCK_KEY + ")");
            }
        }
    }

    // ---------------------------------------------------------------- loading

    /**
     * Loads {@code <root>/<dialect name>/*.sql} from the classpath, from a directory
     * ({@code target/classes}) or from a jar (the shaded application jar, which needn't contain
     * directory entries). A missing directory means no migrations.
     *
     * @throws MigrationException if a file name doesn't match {@code NNNN_name.sql} or can't be read
     */
    public static List<Migration> loadClasspath(ClassLoader loader, String root, Dialect dialect) {
        String dir = root + "/" + dialect.name();
        Map<String, String> files = new TreeMap<>();
        try {
            URL dirUrl = loader.getResource(dir);
            if (dirUrl != null && dirUrl.getProtocol().equals("file")) {
                return loadDirectory(Path.of(dirUrl.toURI()));
            }
            // In a jar: find the jar through a class of ours or the directory itself, then list it.
            URL anchor = dirUrl != null ? dirUrl
                    : loader.getResource(MigrationRunner.class.getName().replace('.', '/') + ".class");
            if (anchor != null && anchor.getProtocol().equals("jar")) {
                JarURLConnection connection = (JarURLConnection) anchor.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    String prefix = dir + "/";
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(prefix)) {
                            continue;
                        }
                        String fileName = name.substring(prefix.length());
                        if (fileName.contains("/") || fileName.startsWith(".")) {
                            continue;
                        }
                        try (InputStream in = jar.getInputStream(entry)) {
                            files.put(fileName, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
            } else if (dirUrl != null) {
                throw new MigrationException("Can't list migrations at " + dirUrl);
            }
        } catch (IOException | URISyntaxException e) {
            throw new MigrationException("Can't read migrations from classpath " + dir + ": " + e.getMessage(), e);
        }
        return parse(files);
    }

    /**
     * Loads the {@code NNNN_name.sql} files directly in {@code dir} (hidden files are ignored).
     * A missing directory means no migrations.
     */
    public static List<Migration> loadDirectory(Path dir) {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String fileName = p.getFileName().toString();
                if (fileName.startsWith(".") || Files.isDirectory(p)) {
                    continue;
                }
                files.put(fileName, Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException | UncheckedIOException e) {
            throw new MigrationException("Can't read migrations from " + dir + ": " + e.getMessage(), e);
        }
        return parse(files);
    }

    private static List<Migration> parse(Map<String, String> files) {
        List<Migration> out = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            try {
                out.add(Migration.of(e.getKey(), e.getValue()));
            } catch (IllegalArgumentException ex) {
                throw new MigrationException(ex.getMessage(), ex);
            }
        }
        out.sort(Comparator.comparingInt(Migration::version));
        return out;
    }

    /** The migration files and the database's history don't fit together. */
    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message) {
            super(message);
        }

        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
