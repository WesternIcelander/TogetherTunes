package io.siggi.togethertunes.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dialect-neutral description of a database schema, read from the catalog of a SQLite or
 * PostgreSQL database, so two schemas can be compared line by line.
 *
 * <p>Each fact is one line of text; the snapshot is the sorted list of lines. What is captured
 * (and how it is normalised so the dialects compare equal when they mean the same thing):
 *
 * <ul>
 *   <li>{@code table T}, {@code view V}, {@code trigger T.N}.
 *   <li>{@code column T.c <type> null|not null default <expr>}: types are mapped to a common
 *       name ({@code REAL} and {@code DOUBLE PRECISION} are both {@code double}); primary-key
 *       columns count as {@code not null} (SQLite does not report this for an {@code INTEGER
 *       PRIMARY KEY}); defaults lose PostgreSQL casts such as {@code ::text}.
 *   <li>{@code primary key T (a, b)} in key order.
 *   <li>{@code foreign key T (a) -> R (b) on delete X on update Y}.
 *   <li>{@code unique T (a, b)}: unique constraints and unique indexes alike, since both
 *       dialects implement the former as the latter and name them differently.
 *   <li>{@code index T.name (a, b)}: non-unique indexes, by their (explicit) names.
 *   <li>{@code check T {a, b}}: one line per CHECK constraint, with the set of columns it uses.
 *       The expressions themselves aren't compared, since PostgreSQL rewrites them (e.g. {@code
 *       x IN (0, 1)} becomes {@code x = ANY (ARRAY[0, 1])}); {@code InitialSchemaContract}
 *       tests what the checks do.
 * </ul>
 */
final class SchemaSnapshot {
    private SchemaSnapshot() {
    }

    /** Reads the schema of {@code db}, using the catalog of its dialect. */
    static List<String> of(Database db) throws SQLException {
        String dialect = db.dialect().name();
        return db.read(c -> switch (dialect) {
            case "sqlite" -> sqlite(c);
            case "postgres" -> postgres(c);
            default -> throw new IllegalArgumentException("Unknown dialect " + dialect);
        });
    }

    // ------------------------------------------------------------------ SQLite

    static List<String> sqlite(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        Map<String, String> tableSql = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT type, name, tbl_name, sql FROM sqlite_master"
                     + " WHERE name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                String type = rs.getString("type");
                String name = rs.getString("name");
                switch (type) {
                    case "table" -> tableSql.put(name, rs.getString("sql"));
                    case "view" -> out.add("view " + name);
                    case "trigger" -> out.add("trigger " + rs.getString("tbl_name") + "." + name);
                    default -> {
                        // Indexes are read per table below.
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : tableSql.entrySet()) {
            String table = e.getKey();
            out.add("table " + table);

            List<String> columns = new ArrayList<>();
            Map<Integer, String> pk = new java.util.TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?) ORDER BY cid")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        columns.add(name);
                        int pkSeq = rs.getInt("pk");
                        if (pkSeq > 0) {
                            pk.put(pkSeq, name);
                        }
                        boolean notNull = rs.getInt("notnull") != 0 || pkSeq > 0;
                        out.add(column(table, name, type(rs.getString("type")), notNull, rs.getString("dflt_value")));
                    }
                }
            }
            if (!pk.isEmpty()) {
                out.add("primary key " + table + " " + list(new ArrayList<>(pk.values())));
            }

            // One foreign key per id; its columns in seq order.
            Map<Integer, String[]> fks = new java.util.TreeMap<>();
            Map<Integer, List<String>> fkFrom = new LinkedHashMap<>();
            Map<Integer, List<String>> fkTo = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, seq, \"table\", \"from\", \"to\", on_update, on_delete"
                            + " FROM pragma_foreign_key_list(?) ORDER BY id, seq")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        fks.putIfAbsent(id, new String[] {
                                rs.getString("table"), rs.getString("on_delete"), rs.getString("on_update")});
                        fkFrom.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("from"));
                        fkTo.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getString("to"));
                    }
                }
            }
            for (Map.Entry<Integer, String[]> fk : fks.entrySet()) {
                String[] v = fk.getValue();
                List<String> to = fkTo.get(fk.getKey());
                if (to.contains(null)) {
                    to = sqlitePrimaryKey(c, v[0]); // REFERENCES t without columns: t's primary key.
                }
                out.add(foreignKey(table, fkFrom.get(fk.getKey()), v[0], to, v[1], v[2]));
            }

            List<String[]> indexes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, \"unique\", origin, partial FROM pragma_index_list(?)")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        indexes.add(new String[] {
                                rs.getString("name"), rs.getString("unique"), rs.getString("origin"),
                                rs.getString("partial")});
                    }
                }
            }
            for (String[] index : indexes) {
                if ("pk".equals(index[2])) {
                    continue; // Covered by the primary key line.
                }
                List<String> cols = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name FROM pragma_index_info(?) ORDER BY seqno")) {
                    ps.setString(1, index[0]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String col = rs.getString("name");
                            cols.add(col == null ? "<expression>" : col);
                        }
                    }
                }
                out.add(index(table, index[0], !"0".equals(index[1]), cols, !"0".equals(index[3])));
            }

            for (Set<String> checkColumns : sqliteChecks(e.getValue(), columns)) {
                out.add("check " + table + " " + checkColumns);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> sqlitePrimaryKey(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM pragma_table_info(?) WHERE pk > 0 ORDER BY pk")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static final Pattern CHECK =Pattern.compile("\\bCHECK\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("\"([^\"]+)\"|`([^`]+)`|\\[([^\\]]+)]|([A-Za-z_][A-Za-z0-9_]*)");

    /** The column sets of the CHECK constraints in a {@code CREATE TABLE} statement. */
    static List<Set<String>> sqliteChecks(String createTable, List<String> columns) {
        String sql = stripCommentsAndStrings(createTable);
        Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        known.addAll(columns);
        List<Set<String>> out = new ArrayList<>();
        Matcher m = CHECK.matcher(sql);
        int from = 0;
        while (m.find(from)) {
            int depth = 1;
            int i = m.end();
            while (i < sql.length() && depth > 0) {
                char ch = sql.charAt(i++);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                }
            }
            String expr = sql.substring(m.end(), i - 1);
            Set<String> used = new TreeSet<>();
            Matcher id = IDENTIFIER.matcher(expr);
            while (id.find()) {
                String name = id.group(1) != null ? id.group(1) : id.group(2) != null ? id.group(2)
                        : id.group(3) != null ? id.group(3) : id.group(4);
                if (known.contains(name)) {
                    used.add(name.toLowerCase(Locale.ROOT));
                }
            }
            out.add(used);
            from = i;
        }
        return out;
    }

    /** Blanks out {@code --} and {@code /* *}{@code /} comments and the contents of string literals. */
    private static String stripCommentsAndStrings(String sql) {
        StringBuilder b = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (ch == '-' && sql.startsWith("--", i)) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
                b.append(' ');
            } else if (ch == '/' && sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                b.append(' ');
            } else if (ch == '\'') {
                int j = i + 1;
                while (j < sql.length()) {
                    if (sql.charAt(j) == '\'') {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                b.append("''");
                i = j + 1;
            } else {
                b.append(ch);
                i++;
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ PostgreSQL

    static List<String> postgres(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        query(c, "SELECT relname FROM pg_class WHERE relnamespace = current_schema()::regnamespace"
                + " AND relkind IN ('r', 'p')", rs -> out.add("table " + rs.getString(1)));
        query(c, "SELECT relname FROM pg_class WHERE relnamespace = current_schema()::regnamespace"
                + " AND relkind IN ('v', 'm')", rs -> out.add("view " + rs.getString(1)));
        query(c, "SELECT cl.relname, t.tgname FROM pg_trigger t JOIN pg_class cl ON cl.oid = t.tgrelid"
                + " WHERE cl.relnamespace = current_schema()::regnamespace AND NOT t.tgisinternal",
                rs -> out.add("trigger " + rs.getString(1) + "." + rs.getString(2)));

        query(c, "SELECT cl.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,"
                        + " pg_get_expr(d.adbin, d.adrelid)"
                        + " FROM pg_attribute a JOIN pg_class cl ON cl.oid = a.attrelid"
                        + " LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum"
                        + " WHERE cl.relnamespace = current_schema()::regnamespace AND cl.relkind IN ('r', 'p')"
                        + " AND a.attnum > 0 AND NOT a.attisdropped",
                rs -> out.add(column(rs.getString(1), rs.getString(2), type(rs.getString(3)), rs.getBoolean(4),
                        rs.getString(5))));

        // Constraints, with their columns in key order.
        String keyColumns = "ARRAY(SELECT a.attname FROM unnest(%1$s) WITH ORDINALITY k(attnum, n)"
                + " JOIN pg_attribute a ON a.attrelid = %2$s AND a.attnum = k.attnum ORDER BY k.n)";
        query(c, "SELECT cl.relname, con.contype, " + keyColumns.formatted("con.conkey", "con.conrelid") + ","
                        + " ref.relname, " + keyColumns.formatted("con.confkey", "con.confrelid") + ","
                        + " con.confdeltype, con.confupdtype"
                        + " FROM pg_constraint con JOIN pg_class cl ON cl.oid = con.conrelid"
                        + " LEFT JOIN pg_class ref ON ref.oid = con.confrelid"
                        + " WHERE cl.relnamespace = current_schema()::regnamespace AND con.contype IN ('p', 'f', 'c')",
                rs -> {
                    String table = rs.getString(1);
                    List<String> cols = strings(rs.getArray(3));
                    switch (rs.getString(2)) {
                        case "p" -> out.add("primary key " + table + " " + list(cols));
                        case "f" -> out.add(foreignKey(table, cols, rs.getString(4), strings(rs.getArray(5)),
                                action(rs.getString(6)), action(rs.getString(7))));
                        default -> out.add("check " + table + " " + new TreeSet<>(cols));
                    }
                });

        // Indexes other than the primary key's, with their columns (0 = an expression).
        query(c, "SELECT tc.relname, ic.relname, i.indisunique, i.indpred IS NOT NULL,"
                        + " ARRAY(SELECT COALESCE(a.attname, '<expression>')"
                        + " FROM unnest(i.indkey::int2[]) WITH ORDINALITY k(attnum, n)"
                        + " LEFT JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum"
                        + " ORDER BY k.n)"
                        + " FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid"
                        + " JOIN pg_class tc ON tc.oid = i.indrelid"
                        + " WHERE tc.relnamespace = current_schema()::regnamespace AND NOT i.indisprimary",
                rs -> out.add(index(rs.getString(1), rs.getString(2), rs.getBoolean(3), strings(rs.getArray(5)),
                        rs.getBoolean(4))));
        Collections.sort(out);
        return out;
    }

    private interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    private static void query(Connection c, String sql, RowHandler handler) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                handler.row(rs);
            }
        }
    }

    private static List<String> strings(java.sql.Array array) throws SQLException {
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                out.add((String) o);
            }
        }
        return out;
    }

    /** PostgreSQL's one-letter referential actions, as SQLite spells them. */
    private static String action(String code) {
        return switch (code) {
            case "a" -> "NO ACTION";
            case "r" -> "RESTRICT";
            case "c" -> "CASCADE";
            case "n" -> "SET NULL";
            case "d" -> "SET DEFAULT";
            default -> "? " + code;
        };
    }

    // ------------------------------------------------------------------ common formatting

    /** Maps a declared (SQLite) or formatted (PostgreSQL) type to a common name. */
    static String type(String declared) {
        String t = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (t) {
            case "real", "double precision", "float8", "double" -> "double";
            case "int", "int4", "integer" -> "integer";
            case "int8", "bigint" -> "bigint";
            case "bool", "boolean" -> "boolean";
            default -> t;
        };
    }

    /** Removes PostgreSQL casts ({@code 'x'::text}) and redundant outer parentheses. */
    static String normaliseDefault(String expr) {
        if (expr == null) {
            return null;
        }
        String d = expr.trim();
        while (d.startsWith("(") && d.endsWith(")") && balanced(d.substring(1, d.length() - 1))) {
            d = d.substring(1, d.length() - 1).trim();
        }
        return d.replaceAll("::[a-z ]+(\\[])?", "").trim();
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static String column(String table, String name, String type, boolean notNull, String dflt) {
        String d = normaliseDefault(dflt);
        return "column " + table + "." + name + " " + type + (notNull ? " not null" : " null")
                + (d == null ? "" : " default " + d);
    }

    private static String foreignKey(String table, List<String> from, String refTable, List<String> to,
                                     String onDelete, String onUpdate) {
        return "foreign key " + table + " " + list(from) + " -> " + refTable + " " + list(to)
                + " on delete " + onDelete.toUpperCase(Locale.ROOT) + " on update " + onUpdate.toUpperCase(Locale.ROOT);
    }

    private static String index(String table, String name, boolean unique, List<String> columns, boolean partial) {
        String suffix = partial ? " where <predicate>" : "";
        return unique
                ? "unique " + table + " " + list(columns) + suffix
                : "index " + table + "." + name + " " + list(columns) + suffix;
    }

    private static String list(List<String> items) {
        return "(" + String.join(", ", items) + ")";
    }
}
