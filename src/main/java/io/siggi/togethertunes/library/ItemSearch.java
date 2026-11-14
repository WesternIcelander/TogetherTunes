package io.siggi.togethertunes.library;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one item search behind the room request search and the Library page's "Search the whole
 * server" ({@code docs/libraries.md#visibility}): items matching a query, either from a set of
 * users' libraries (and playlists), or from the whole server.
 *
 * <p><b>Library membership is private.</b> A hit is only the item's metadata ({@link Library.ItemView})
 * plus whether it is in the <em>searching</em> user's own library: nothing says whose other
 * libraries or playlists hold it, when or how it got there, or who shared it. An item appears once.
 * Hits are ordered by item id, newest item first (the item's creation, the same for everyone),
 * never by when a library got it, so the order tells nothing about membership either.
 *
 * <p>What is left out: uploads that failed or whose media expired (nobody can play them again);
 * in a {@linkplain Scope#server() whole-server} search also purged YouTube items (only items that
 * are "on the server"). Failed and purged YouTube items in a library search are listed (requesting
 * or adding one fetches it again).
 */
public final class ItemSearch {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    /**
     * What to search: the libraries of {@code libraryUserIds} and the playlists of
     * {@code playlistOwnerIds}, or ({@code wholeServer}) every item on the server.
     */
    public record Scope(boolean wholeServer, List<String> libraryUserIds, List<String> playlistOwnerIds) {
        public Scope {
            libraryUserIds = List.copyOf(libraryUserIds);
            playlistOwnerIds = List.copyOf(playlistOwnerIds);
        }

        /** Every item on the server that isn't purged. */
        public static Scope server() {
            return new Scope(true, List.of(), List.of());
        }

        /** These users' libraries and playlists ({@code null} entries are skipped). */
        public static Scope of(List<String> libraryUserIds, List<String> playlistOwnerIds) {
            return new Scope(false, libraryUserIds.stream().filter(Objects::nonNull).distinct().toList(),
                    playlistOwnerIds.stream().filter(Objects::nonNull).distinct().toList());
        }
    }

    /**
     * One hit.
     *
     * @param inYourLibrary whether the item is in the searching user's library ({@code false} for
     *                      guests and anonymous callers)
     */
    public record Hit(Library.ItemView item, boolean inYourLibrary) {
    }

    /** One page of hits; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Hit> items, String nextCursor) {
    }

    private final Database db;

    public ItemSearch(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    /**
     * Searches.
     *
     * @param viewerUserId the searching user (for {@link Hit#inYourLibrary}), or {@code null}
     * @param query        words that must each appear in the title, artist or album
     *                     (case-insensitive, substring); {@code null}/blank for all. {@code 400
     *                     invalid_query} if too long
     * @param kind         {@code audio}, {@code video} or {@code null}; {@code 400 invalid_kind}
     * @param cursor       the previous page's {@code nextCursor}, or {@code null}; {@code 400 invalid_cursor}
     */
    public Page search(Scope scope, String viewerUserId, String query, String kind, String cursor, int limit)
            throws SQLException {
        return search(scope, viewerUserId, query, kind, null, cursor, limit);
    }

    /**
     * As {@link #search(Scope, String, String, String, String, int)}, with the {@code type=} filter
     * ({@link MediaType#filter}: comma-separated media types, null for all; {@code 400 invalid_type}).
     */
    public Page search(Scope scope, String viewerUserId, String query, String kind, String type, String cursor,
                       int limit) throws SQLException {
        Objects.requireNonNull(scope);
        List<String> types = MediaType.filter(type);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (kind != null && !kind.equals("audio") && !kind.equals("video")) {
            throw new HttpError(400, "invalid_kind", "kind must be audio or video");
        }
        if (cursor != null && !UuidV7.isValid(cursor)) {
            throw new HttpError(400, "invalid_cursor", "Malformed cursor");
        }
        List<String> terms = Library.terms(query);
        if (!scope.wholeServer() && scope.libraryUserIds().isEmpty() && scope.playlistOwnerIds().isEmpty()) {
            return new Page(List.of(), null);
        }
        Dialect d = db.dialect();
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + Library.ItemView.COLUMNS + ", ");
        if (viewerUserId != null) {
            sql.append("CASE WHEN EXISTS (SELECT 1 FROM library_entries v WHERE v.item_id = i.id AND v.user_id = ?)"
                    + " THEN 1 ELSE 0 END");
            params.add(viewerUserId);
        } else {
            sql.append("0");
        }
        sql.append(" FROM items i WHERE ");
        if (scope.wholeServer()) {
            sql.append("i.status <> 'purged' AND NOT (i.youtube_id IS NULL AND i.status = 'failed')");
        } else {
            sql.append("(");
            String or = "";
            for (String user : scope.libraryUserIds()) {
                sql.append(or).append("i.id IN (SELECT item_id FROM library_entries WHERE user_id = ?)");
                params.add(user);
                or = " OR ";
            }
            for (String owner : scope.playlistOwnerIds()) {
                sql.append(or).append("i.id IN (SELECT pe.item_id FROM playlist_entries pe")
                        .append(" JOIN playlists p ON p.id = pe.playlist_id WHERE p.owner_id = ?)");
                params.add(owner);
                or = " OR ";
            }
            sql.append(") AND NOT (i.youtube_id IS NULL AND i.status IN ('failed', 'purged'))");
        }
        appendMatch(d, sql, params, kind, types, terms);
        if (cursor != null) {
            sql.append(" AND i.id < ?");
            params.add(cursor);
        }
        sql.append(" ORDER BY i.id DESC LIMIT ").append(limit + 1);
        return run(sql.toString(), params, limit, false);
    }

    /**
     * The whole-server search of {@code GET /api/search} ({@code docs/libraries.md#visibility}):
     * every item on the server that isn't purged ({@link Scope#server()}), the items in the
     * searching user's own library first, each part newest item first (by item id). For a guest or
     * anonymous caller ({@code viewerUserId} null) there is no own library: newest first. Only the
     * caller's own library affects the order, so nothing tells whose other libraries hold an item.
     * The cursor is {@code <1|0>_<itemId>} (1 while in the caller's library part).
     *
     * @throws HttpError as {@link #search(Scope, String, String, String, String, String, int)}
     */
    public Page searchMineFirst(String viewerUserId, String query, String kind, String type, String cursor,
                                int limit) throws SQLException {
        List<String> types = MediaType.filter(type);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (kind != null && !kind.equals("audio") && !kind.equals("video")) {
            throw new HttpError(400, "invalid_kind", "kind must be audio or video");
        }
        int cursorMine = -1;
        String cursorId = null;
        if (cursor != null) {
            if (cursor.length() < 3 || cursor.charAt(1) != '_' || (cursor.charAt(0) != '0' && cursor.charAt(0) != '1')
                    || !UuidV7.isValid(cursor.substring(2))
                    || (viewerUserId == null && cursor.charAt(0) == '1')) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorMine = cursor.charAt(0) - '0';
            cursorId = cursor.substring(2);
        }
        List<String> terms = Library.terms(query);
        Dialect d = db.dialect();
        List<String> params = new ArrayList<>();
        String mine = "EXISTS (SELECT 1 FROM library_entries v WHERE v.item_id = i.id AND v.user_id = ?)";
        StringBuilder sql = new StringBuilder("SELECT " + Library.ItemView.COLUMNS + ", ");
        if (viewerUserId != null) {
            sql.append("CASE WHEN ").append(mine).append(" THEN 1 ELSE 0 END AS mine");
            params.add(viewerUserId);
        } else {
            sql.append("0 AS mine");
        }
        sql.append(" FROM items i WHERE i.status <> 'purged' AND NOT (i.youtube_id IS NULL AND i.status = 'failed')");
        appendMatch(d, sql, params, kind, types, terms);
        if (cursorId != null) {
            if (viewerUserId == null) {
                sql.append(" AND i.id < ?");
                params.add(cursorId);
            } else if (cursorMine == 1) {
                // still in the caller's library part: later ones there, then everything else
                sql.append(" AND ((").append(mine).append(" AND i.id < ?) OR NOT ").append(mine).append(")");
                params.add(viewerUserId);
                params.add(cursorId);
                params.add(viewerUserId);
            } else {
                sql.append(" AND NOT ").append(mine).append(" AND i.id < ?");
                params.add(viewerUserId);
                params.add(cursorId);
            }
        }
        sql.append(viewerUserId == null ? " ORDER BY i.id DESC" : " ORDER BY mine DESC, i.id DESC")
                .append(" LIMIT ").append(limit + 1);
        return run(sql.toString(), params, limit, true);
    }

    private static void appendMatch(Dialect d, StringBuilder sql, List<String> params, String kind,
                                    List<String> types, List<String> terms) {
        if (kind != null) {
            sql.append(" AND i.kind = ?");
            params.add(kind);
        }
        MediaType.appendFilter(sql, params, types);
        String like = " LIKE ? ESCAPE '" + Dialect.LIKE_ESCAPE + "'";
        for (String term : terms) {
            sql.append(" AND (").append(d.lower("i.title")).append(like)
                    .append(" OR ").append(d.lower("i.artist")).append(like)
                    .append(" OR ").append(d.lower("i.album")).append(like).append(")");
            String pattern = Dialect.containsPattern(term);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
    }

    private Page run(String sql, List<String> params, int limit, boolean rankedCursor) throws SQLException {
        return db.read(c -> {
            List<Hit> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new Hit(Library.ItemView.read(rs, 1), rs.getInt(14) != 0));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Hit last = items.get(limit - 1);
                next = rankedCursor ? (last.inYourLibrary() ? "1_" : "0_") + last.item().id()
                        : last.item().id();
            }
            return new Page(List.copyOf(items), next);
        });
    }
}
