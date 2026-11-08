package io.siggi.togethertunes.db;

import java.sql.Connection;

/**
 * PostgreSQL (13+). Case-insensitive search uses the built-in {@code lower()}, which is
 * Unicode-aware when the database is UTF-8 with a non-{@code C} ctype (e.g. {@code
 * en_US.UTF-8}, the default of the official Docker image). An expression index on {@code
 * lower(col)} can serve {@link #equalsIgnoreCase} lookups.
 */
public final class PostgresDialect implements Dialect {
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public void initConnection(Connection connection) {
        // Nothing to set up.
    }

    @Override
    public String lower(String column) {
        return "lower(" + column + ")";
    }

    @Override
    public String toString() {
        return "PostgresDialect";
    }
}
