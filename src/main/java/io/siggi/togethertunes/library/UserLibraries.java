package io.siggi.togethertunes.library;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only access to other users' libraries and playlists, for {@code library.viewAll}
 * ({@code docs/libraries.md#visibility}): find a user by part of their username or display name,
 * then list their library (with the same search as one's own) and their playlists. Nothing here
 * changes anything, and nothing touches {@code last_referenced_at}: looking isn't a reference.
 *
 * <p>The permission check belongs to the caller ({@link UserLibrariesApi}). Every user can be
 * looked at, disabled ones too (their entries still keep items from retention). A guest id, or
 * any id that isn't a user, is {@code 404 user_not_found}.
 */
public final class UserLibraries {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest accepted user search, in characters. */
    public static final int QUERY_MAX_CHARS = 100;

    private final Database db;
    private final Library library;
    private final Playlists playlists;

    /**
     * A user as the "browse libraries" list shows them.
     *
     * @param disabled      the account is disabled (its library and playlists are still there)
     * @param libraryCount  entries in their library
     * @param playlistCount their playlists
     */
    public record UserSummary(String id, String username, String displayName, boolean disabled, int libraryCount,
                              int playlistCount) {
    }

    /** One page of users; {@code nextCursor} is {@code null} on the last page. */
    public record UserPage(List<UserSummary> items, String nextCursor) {
    }

    public UserLibraries(Database db, Library library, Playlists playlists) {
        this.db = Objects.requireNonNull(db);
        this.library = Objects.requireNonNull(library);
        this.playlists = Objects.requireNonNull(playlists);
    }

    private static final String SELECT = "SELECT id, username, display_name, disabled,"
            + " (SELECT COUNT(*) FROM library_entries e WHERE e.user_id = users.id),"
            + " (SELECT COUNT(*) FROM playlists p WHERE p.owner_id = users.id) FROM users";

    /**
     * Users ordered by username (ignoring case, then by id).
     *
     * @param query  part of the username or display name, ignoring case; {@code null}/blank for
     *               all. {@code 400 invalid_query} if longer than {@link #QUERY_MAX_CHARS}
     * @param cursor the previous page's {@code nextCursor}, or {@code null}
     */
    public UserPage users(String query, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (query != null && query.length() > QUERY_MAX_CHARS) {
            throw new HttpError(400, "invalid_query", "The search is too long (at most " + QUERY_MAX_CHARS
                    + " characters)");
        }
        String cursorName = null;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.lastIndexOf('_');
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorName = cursor.substring(0, sep);
            cursorId = cursor.substring(sep + 1);
        }
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<String> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String pattern = Dialect.containsPattern(query.strip());
            sql.append(" AND (").append(d.containsIgnoreCase("username"))
                    .append(" OR ").append(d.containsIgnoreCase("display_name")).append(")");
            params.add(pattern);
            params.add(pattern);
        }
        // Usernames are ASCII (Accounts), so lower() folds them the same way on both databases.
        if (cursorId != null) {
            sql.append(" AND (lower(username) > ? OR (lower(username) = ? AND id > ?))");
            params.add(cursorName);
            params.add(cursorName);
            params.add(cursorId);
        }
        sql.append(" ORDER BY lower(username), id LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<UserSummary> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setString(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(new UserSummary(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4) != 0, rs.getInt(5), rs.getInt(6)));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                UserSummary last = items.get(limit - 1);
                next = last.username().toLowerCase(Locale.ROOT) + "_" + last.id();
            }
            return new UserPage(List.copyOf(items), next);
        });
    }

    /** {@code userId}'s library, as {@link Library#list}. @throws HttpError {@code 404 user_not_found} */
    public Library.Page library(String userId, String query, String kind, String cursor, int limit)
            throws SQLException {
        requireUser(userId);
        return library.list(userId, query, kind, cursor, limit);
    }

    /** {@code userId}'s playlists, as {@link Playlists#list}. @throws HttpError {@code 404 user_not_found} */
    public Playlists.Page playlists(String userId, String cursor, int limit) throws SQLException {
        requireUser(userId);
        return playlists.list(userId, cursor, limit);
    }

    /**
     * One of {@code userId}'s playlists with its entries, as {@link Playlists#get}.
     *
     * @throws HttpError {@code 404 user_not_found}, {@code 404 playlist_not_found} (also for a
     *                   playlist of another user)
     */
    public Playlists.PlaylistPage playlist(String userId, String playlistId) throws SQLException {
        requireUser(userId);
        return playlists.get(userId, playlistId);
    }

    private void requireUser(String userId) throws SQLException {
        boolean exists = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
        if (!exists) {
            throw new HttpError(404, "user_not_found", "No such user");
        }
    }
}
