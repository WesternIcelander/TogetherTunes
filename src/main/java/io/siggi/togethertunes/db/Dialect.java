package io.siggi.togethertunes.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The few SQL differences between SQLite and PostgreSQL that repository classes need:
 * upserts, {@code RETURNING}, and case-insensitive search.
 *
 * <p>Both databases accept the same upsert ({@code INSERT ... ON CONFLICT (...) DO UPDATE
 * SET c = excluded.c} / {@code DO NOTHING}, SQLite 3.24+) and the same {@code RETURNING}
 * clause (SQLite 3.35+), so those builders are shared. Case-insensitive search differs:
 * SQLite's {@code lower()} and {@code LIKE} only fold ASCII, so {@link SqliteDialect}
 * registers a Unicode-aware {@code tt_lower()} function on each connection (see
 * {@link #initConnection}); PostgreSQL's {@code lower()} is Unicode-aware for a UTF-8
 * database with a non-{@code C} ctype.
 *
 * <p>Identifiers passed to the builders must be plain SQL identifiers ({@code
 * [A-Za-z_][A-Za-z0-9_]*}); anything else is rejected, so a builder can never be used to
 * inject SQL. Values always go through {@code ?} parameters.
 */
public sealed interface Dialect permits SqliteDialect, PostgresDialect {

    /** The escape character used by {@link #containsIgnoreCase} patterns. */
    char LIKE_ESCAPE = '\\';

    /**
     * Short name, also the migrations subdirectory: {@code "sqlite"} or {@code "postgres"}.
     */
    String name();

    /**
     * Prepares a freshly opened connection for use with this dialect. Must be called once
     * on every connection before it is used (the connection pool does this). SQLite
     * registers {@code tt_lower()} here; PostgreSQL needs nothing.
     */
    void initConnection(Connection connection) throws SQLException;

    /**
     * SQL expression that lower-cases {@code column} the same way {@link #foldCase} does
     * in Java (exactly for SQLite, as closely as the database allows for PostgreSQL).
     */
    String lower(String column);

    /** The dialect for a JDBC URL ({@code jdbc:sqlite:...} or {@code jdbc:postgresql:...}). */
    static Dialect forJdbcUrl(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            return SqliteDialect.INSTANCE;
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return PostgresDialect.INSTANCE;
        }
        throw new IllegalArgumentException("Unsupported JDBC URL (expected jdbc:sqlite: or jdbc:postgresql:)");
    }

    // ---------------------------------------------------------------- upsert

    /**
     * {@code INSERT INTO table (c1, c2, ...) VALUES (?, ?, ...) ON CONFLICT (k1, ...) DO
     * UPDATE SET u1 = excluded.u1, ...}. With no {@code updateColumns} it becomes {@code DO
     * NOTHING}. Bind one parameter per insert column, in order. {@code conflictColumns} must
     * match a primary key or unique constraint exactly.
     */
    default String upsert(String table, List<String> insertColumns, List<String> conflictColumns,
                          List<String> updateColumns) {
        if (insertColumns.isEmpty()) {
            throw new IllegalArgumentException("upsert needs at least one insert column");
        }
        if (conflictColumns.isEmpty()) {
            throw new IllegalArgumentException("upsert needs at least one conflict column");
        }
        for (String c : conflictColumns) {
            if (!insertColumns.contains(c)) {
                throw new IllegalArgumentException("conflict column " + c + " is not inserted");
            }
        }
        for (String c : updateColumns) {
            if (!insertColumns.contains(c)) {
                throw new IllegalArgumentException("update column " + c + " is not inserted");
            }
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(identifier(table)).append(" (");
        joinIdentifiers(sql, insertColumns);
        sql.append(") VALUES (");
        for (int i = 0; i < insertColumns.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ON CONFLICT (");
        joinIdentifiers(sql, conflictColumns);
        sql.append(") DO ");
        if (updateColumns.isEmpty()) {
            sql.append("NOTHING");
        } else {
            sql.append("UPDATE SET ");
            for (int i = 0; i < updateColumns.size(); i++) {
                String c = identifier(updateColumns.get(i));
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(c).append(" = excluded.").append(c);
            }
        }
        return sql.toString();
    }

    /** {@link #upsert} with {@code DO NOTHING}: insert unless the row already exists. */
    default String insertOrIgnore(String table, List<String> insertColumns, List<String> conflictColumns) {
        return upsert(table, insertColumns, conflictColumns, List.of());
    }

    // ------------------------------------------------------------- RETURNING

    /**
     * Appends {@code RETURNING c1, c2, ...} to an {@code INSERT}, {@code UPDATE} or {@code
     * DELETE}. Run the result with {@code executeQuery()}; it yields one row per affected row
     * (none for an upsert that hit {@code DO NOTHING}). Don't rely on {@code RETURNING} rows
     * of a SQLite {@code DELETE}/{@code UPDATE} coming back in any particular order.
     */
    default String returning(String sql, String... columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("RETURNING needs at least one column");
        }
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) {
            throw new IllegalArgumentException("statement must not end with ';'");
        }
        StringBuilder out = new StringBuilder(trimmed).append(" RETURNING ");
        joinIdentifiers(out, List.of(columns));
        return out.toString();
    }

    // -------------------------------------------------- case-insensitive search

    /**
     * Predicate {@code <lower(column)> LIKE ? ESCAPE '\'}. Bind {@link #containsPattern(String)}
     * of the search term: matches rows whose column contains the term, ignoring case.
     */
    default String containsIgnoreCase(String column) {
        return lower(identifier(column)) + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
    }

    /**
     * Predicate {@code <lower(column)> = ?}. Bind {@link #foldCase(String)} of the value.
     */
    default String equalsIgnoreCase(String column) {
        return lower(identifier(column)) + " = ?";
    }

    /** Lower-cases a value the way {@link #lower} does in SQL ({@link Locale#ROOT}). */
    static String foldCase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * The {@code LIKE} pattern for {@link #containsIgnoreCase}: the case-folded term with
     * {@code %}, {@code _} and the escape character escaped, wrapped in {@code %...%}.
     */
    static String containsPattern(String term) {
        return "%" + escapeLike(foldCase(term)) + "%";
    }

    /** Escapes {@code %}, {@code _} and {@link #LIKE_ESCAPE} for a {@code LIKE} pattern. */
    static String escapeLike(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' || ch == '_' || ch == LIKE_ESCAPE) {
                out.append(LIKE_ESCAPE);
            }
            out.append(ch);
        }
        return out.toString();
    }

    // ------------------------------------------------------------- identifiers

    /** Returns {@code name} if it is a plain SQL identifier, else throws. */
    static String identifier(String name) {
        if (name == null || !Identifiers.PLAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("not a plain SQL identifier: " + name);
        }
        return name;
    }

    private static void joinIdentifiers(StringBuilder sql, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(identifier(names.get(i)));
        }
    }

    /** Holder so the pattern isn't a public interface constant. */
    final class Identifiers {
        private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        private Identifiers() {
        }
    }
}
