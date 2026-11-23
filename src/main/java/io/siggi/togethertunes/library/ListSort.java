package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.http.HttpError;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The sort of a song-style list ({@code docs/api.md}, {@code sort=} and {@code order=} of
 * {@code GET /api/library}, its views, {@code GET /api/users/{id}/library} and {@code GET
 * /api/search}): by a column, ascending or descending, or the list's own order (no {@code sort}:
 * the default, {@code order=desc} reverses it). Ties keep the list's own order; text is compared
 * case-insensitively ({@link Dialect#lower}, by code point on both databases); a missing value
 * (null or empty) comes last in both directions.
 *
 * <p>Sorted pages use keyset cursors: {@link #where} turns the previous page's last row (its key
 * values, {@link #cursor}) into a condition over the same keys as {@link #orderBy}. The cursor of
 * a sorted list carries its sort and order, so a cursor used with another sort is {@code 400
 * invalid_cursor}. The default order keeps each list's existing cursor format.
 *
 * @param column the column, or {@code null} for the list's own order
 * @param desc   descending (for no column: the list's own order reversed)
 */
public record ListSort(Column column, boolean desc) {
    /** The list's own order. */
    public static final ListSort DEFAULT = new ListSort(null, false);

    /** A sortable column, by its wire name. */
    public enum Column {
        TITLE("title"), ARTIST("artist"), ALBUM("album"), DURATION("duration"), ADDED("added");

        private final String wire;

        Column(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * Parses the {@code sort} and {@code order} query parameters (null or empty: not given).
     *
     * @throws HttpError {@code 400 invalid_sort}, {@code 400 invalid_order}
     */
    public static ListSort parse(String sort, String order) {
        Column column = null;
        if (sort != null && !sort.isEmpty()) {
            for (Column c : Column.values()) {
                if (c.wire.equals(sort)) {
                    column = c;
                }
            }
            if (column == null) {
                throw new HttpError(400, "invalid_sort", "sort must be title, artist, album, duration or added");
            }
        }
        boolean desc = false;
        if (order != null && !order.isEmpty()) {
            if (order.equals("desc")) {
                desc = true;
            } else if (!order.equals("asc")) {
                throw new HttpError(400, "invalid_order", "order must be asc or desc");
            }
        }
        return new ListSort(column, desc);
    }

    /** Whether this is the list's own order (not reversed). */
    public boolean isDefault() {
        return column == null && !desc;
    }

    /** The type of a key's values (in the cursor and as parameters). */
    public enum Type { LONG, STRING }

    /**
     * One ORDER BY key.
     *
     * @param order       the expression in ORDER BY (may be a select alias)
     * @param where       the same expression for WHERE (no aliases), with {@code whereParams}
     * @param whereParams the parameters of {@code where}, in order
     * @param desc        descending
     * @param type        the type of its values
     */
    public record Key(String order, String where, List<Object> whereParams, boolean desc, Type type) {
        public Key {
            whereParams = List.copyOf(whereParams);
        }

        public Key(String expr, boolean desc, Type type) {
            this(expr, expr, List.of(), desc, type);
        }

        Key reversed() {
            return new Key(order, where, whereParams, !desc, type);
        }
    }

    /**
     * The sort key expression of {@link #column} (null for no column), for a list whose
     * {@code added} column is {@code addedExpr} (of {@code addedType}). Text keys are lower-cased,
     * and an empty text is null (missing).
     */
    public String keyExpression(Dialect d, String addedExpr) {
        if (column == null) {
            return null;
        }
        return switch (column) {
            case TITLE -> text(d, "i.title");
            case ARTIST -> text(d, "i.artist");
            case ALBUM -> text(d, "i.album");
            case DURATION -> "i.duration_ms";
            case ADDED -> addedExpr;
        };
    }

    private static String text(Dialect d, String column) {
        return "NULLIF(" + d.sortText(d.lower(column)) + ", '')";
    }

    /** The type of the sort key's values. */
    public Type keyType(Type addedType) {
        return column == Column.DURATION ? Type.LONG : column == Column.ADDED ? addedType : Type.STRING;
    }

    /**
     * The keys to order by: for a column, whether its key is missing (missing last), the key and
     * then {@code defaultKeys}; for the list's own order reversed, {@code defaultKeys} each
     * reversed. The sort key is selected as {@code sortAlias} (see {@link #selectKey}).
     */
    public List<Key> keys(Dialect d, String addedExpr, Type addedType, List<Key> defaultKeys) {
        List<Key> keys = new ArrayList<>();
        if (column == null) {
            for (Key k : defaultKeys) {
                keys.add(desc ? k.reversed() : k);
            }
            return keys;
        }
        String expr = keyExpression(d, addedExpr);
        keys.add(new Key("CASE WHEN " + expr + " IS NULL THEN 1 ELSE 0 END", false, Type.LONG));
        keys.add(new Key(expr, desc, keyType(addedType)));
        keys.addAll(defaultKeys);
        return keys;
    }

    /** {@code , <key> AS sort_key} for the SELECT list when sorting by a column, else empty. */
    public String selectKey(Dialect d, String addedExpr) {
        return column == null ? "" : ", " + keyExpression(d, addedExpr) + " AS sort_key";
    }

    /** {@code ORDER BY ...} over {@code keys}. */
    public static String orderBy(List<Key> keys) {
        StringBuilder sql = new StringBuilder(" ORDER BY ");
        for (int i = 0; i < keys.size(); i++) {
            Key k = keys.get(i);
            sql.append(i == 0 ? "" : ", ").append(k.order()).append(k.desc() ? " DESC" : " ASC");
        }
        return sql.toString();
    }

    /**
     * Appends {@code AND (<rows after values>)} to {@code sql}: the rows that come after a row with
     * these key values in the order of {@code keys}. A null value (a missing sort key: the key
     * before it, "missing", is then 1 for every row it is compared with) is skipped as equal.
     */
    public static void where(StringBuilder sql, List<Object> params, List<Key> keys, List<Object> values) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("one value per key");
        }
        sql.append(" AND ");
        appendAfter(sql, params, keys, values, 0);
    }

    private static void appendAfter(StringBuilder sql, List<Object> params, List<Key> keys, List<Object> values,
                                    int from) {
        int i = from;
        while (i < keys.size() && values.get(i) == null) {
            i++;
        }
        if (i >= keys.size()) {
            sql.append("1 = 0");
            return;
        }
        Key k = keys.get(i);
        Object v = values.get(i);
        sql.append("((").append(k.where()).append(k.desc() ? " < ?" : " > ?").append(")");
        params.addAll(k.whereParams());
        params.add(v);
        boolean more = false;
        for (int j = i + 1; j < keys.size(); j++) {
            more |= values.get(j) != null;
        }
        if (more) {
            sql.append(" OR (").append(k.where()).append(" = ?");
            params.addAll(k.whereParams());
            params.add(v);
            sql.append(" AND ");
            appendAfter(sql, params, keys, values, i + 1);
            sql.append(")");
        }
        sql.append(")");
    }

    /** Binds {@code params} (Long, Integer or String) to {@code ps}. */
    public static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, (String) p);
            }
        }
    }

    /** Reads a key value of {@code type} from column {@code index} (null when SQL NULL). */
    public static Object read(ResultSet rs, int index, Type type) throws SQLException {
        if (type == Type.LONG) {
            long v = rs.getLong(index);
            return rs.wasNull() ? null : v;
        }
        return rs.getString(index);
    }

    /**
     * The cursor of a sorted list after a row with these key values (one per {@link #keys} entry):
     * base64url of {@code {"sort", "order", "after": [values]}}.
     */
    public String cursor(List<Object> values) {
        JsonObject o = new JsonObject();
        o.addProperty("sort", column == null ? "" : column.wire);
        o.addProperty("order", desc ? "desc" : "asc");
        JsonArray after = new JsonArray();
        for (Object v : values) {
            if (v == null) {
                after.add(com.google.gson.JsonNull.INSTANCE);
            } else if (v instanceof Long l) {
                after.add(l);
            } else {
                after.add((String) v);
            }
        }
        o.add("after", after);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The key values of a {@link #cursor} made for this sort and these keys.
     *
     * @throws HttpError {@code 400 invalid_cursor} if malformed, or made for another sort
     */
    public List<Object> parseCursor(String cursor, List<Key> keys) {
        try {
            JsonObject o = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            String sort = o.get("sort").getAsString();
            String order = o.get("order").getAsString();
            if (!sort.equals(column == null ? "" : column.wire) || !order.equals(desc ? "desc" : "asc")) {
                throw new HttpError(400, "invalid_cursor", "The cursor belongs to another sort");
            }
            JsonArray after = o.getAsJsonArray("after");
            if (after.size() != keys.size()) {
                throw new IllegalArgumentException();
            }
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                JsonElement e = after.get(i);
                if (e.isJsonNull()) {
                    values.add(null);
                } else if (keys.get(i).type() == Type.LONG) {
                    values.add(e.getAsJsonPrimitive().getAsLong());
                } else {
                    if (!e.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException();
                    }
                    values.add(e.getAsString());
                }
            }
            return values;
        } catch (HttpError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new HttpError(400, "invalid_cursor", "Malformed cursor");
        }
    }
}
