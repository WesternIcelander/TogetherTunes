package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Dialect;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A user's library: their {@code library_entries}, each a reference to a shared item
 * ({@code docs/libraries.md}). Listing with search and cursor pagination, adding an existing item
 * by id ({@code via='manual'}) and removing an entry. Removing only deletes the entry; the item
 * stays until retention or {@code library.purge} deletes it, and its {@code last_referenced_at}
 * is set to now (an unreference event, {@code docs/libraries.md#retention}).
 *
 * <p>Adding a purged or failed <b>YouTube</b> item fetches it again through
 * {@link YoutubeImports#addExisting} (the caller checks {@code library.youtube} first). A purged
 * or failed <b>upload</b> can't be restored this way: {@code 409 media_expired} /
 * {@code 409 item_failed} (it has to be uploaded again).
 */
public final class Library {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest accepted {@code q}, in characters. */
    public static final int QUERY_MAX_CHARS = 200;
    /** At most this many search words are used; the rest are ignored. */
    public static final int QUERY_MAX_TERMS = 10;
    /** {@code library_entries.via} of an item added by id ({@code PUT /api/library/{itemId}}). */
    public static final String VIA_MANUAL = "manual";

    private final Database db;
    private final YoutubeImports youtube;
    private final Clock clock;

    /**
     * An item as lists and item pages show it: metadata and state, nothing about who has it.
     *
     * @param durationMs     {@code null} until known
     * @param youtubeId      the video ID of a YouTube item, else {@code null}
     * @param status         {@code pending}, {@code processing}, {@code ready}, {@code failed} or
     *                       {@code purged} (media expired)
     * @param presentAsAudio a video item played like an audio item, with its card
     */
    public record ItemView(String id, String kind, String title, String artist, String album, Long durationMs,
                           String source, String youtubeId, String status, boolean presentAsAudio) {
    }

    /**
     * One library entry.
     *
     * @param via      how it got there: {@code upload}, {@code youtube}, {@code request},
     *                 {@code share} or {@code manual}
     * @param sharedBy the user who shared it ({@code via='share'}), else {@code null}
     */
    public record Entry(ItemView item, long addedAt, String via, ActorRef sharedBy) {
    }

    /** One page; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Entry> items, String nextCursor) {
    }

    /** The result of {@link #add}: the entry, and whether this call created it. */
    public record Added(Entry entry, boolean created) {
    }

    public Library(Database db, YoutubeImports youtube, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.youtube = Objects.requireNonNull(youtube);
        this.clock = Objects.requireNonNull(clock);
    }

    private static final String SELECT = "SELECT i.id, i.kind, i.title, i.artist, i.album, i.duration_ms, i.source,"
            + " i.youtube_id, i.status, i.present_as_audio, e.added_at, e.via, e.shared_by_user_id, s.display_name"
            + " FROM library_entries e JOIN items i ON i.id = e.item_id"
            + " LEFT JOIN users s ON s.id = e.shared_by_user_id";

    /**
     * The user's entries, most recently added first (then by item id, descending).
     *
     * @param query  words that must each appear (case-insensitively, as part of a word or not) in
     *               the title, artist or album; {@code null}/blank for all. {@code 400
     *               invalid_query} if longer than {@link #QUERY_MAX_CHARS}
     * @param kind   {@code audio}, {@code video} or {@code null} for both (the stored kind:
     *               a video item with {@code presentAsAudio} is still {@code video});
     *               {@code 400 invalid_kind} otherwise
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(String userId, String query, String kind, String cursor, int limit) throws SQLException {
        Objects.requireNonNull(userId);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        if (kind != null && !kind.equals("audio") && !kind.equals("video")) {
            throw new HttpError(400, "invalid_kind", "kind must be audio or video");
        }
        List<String> terms = terms(query);
        long cursorAt = 0;
        String cursorId = null;
        if (cursor != null) {
            int sep = cursor.indexOf('_');
            try {
                cursorAt = Long.parseLong(cursor.substring(0, Math.max(sep, 0)));
            } catch (NumberFormatException e) {
                sep = -1;
            }
            if (sep <= 0 || sep == cursor.length() - 1) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        Dialect d = db.dialect();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE e.user_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (kind != null) {
            sql.append(" AND i.kind = ?");
            params.add(kind);
        }
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
        if (cursorId != null) {
            sql.append(" AND (e.added_at < ? OR (e.added_at = ? AND e.item_id < ?))");
            params.add(cursorAt);
            params.add(cursorAt);
            params.add(cursorId);
        }
        sql.append(" ORDER BY e.added_at DESC, e.item_id DESC LIMIT ").append(limit + 1);
        return db.read(c -> {
            List<Entry> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i) instanceof Long l) {
                        ps.setLong(i + 1, l);
                    } else {
                        ps.setString(i + 1, (String) params.get(i));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(entry(rs));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Entry last = items.get(limit - 1);
                next = last.addedAt() + "_" + last.item().id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    /** The search words of {@code query}: split at whitespace, at most {@link #QUERY_MAX_TERMS}. */
    static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        if (query.length() > QUERY_MAX_CHARS) {
            throw new HttpError(400, "invalid_query", "The search is too long (at most " + QUERY_MAX_CHARS
                    + " characters)");
        }
        List<String> out = new ArrayList<>();
        for (String word : query.strip().split("\\s+")) {
            if (!word.isEmpty() && out.size() < QUERY_MAX_TERMS && !out.contains(word)) {
                out.add(word);
            }
        }
        return List.copyOf(out);
    }

    /** The user's entry for {@code itemId}, or {@code null}. */
    public Entry get(String userId, String itemId) throws SQLException {
        return db.read(c -> get(c, userId, itemId));
    }

    private static Entry get(Connection c, String userId, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE e.user_id = ? AND e.item_id = ?")) {
            ps.setString(1, userId);
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs) : null;
            }
        }
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        long duration = rs.getLong(6);
        Long durationMs = rs.wasNull() ? null : duration;
        ItemView item = new ItemView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), durationMs, rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10) != 0);
        String sharedById = rs.getString(13);
        String sharedByName = rs.getString(14);
        ActorRef sharedBy = sharedById != null && sharedByName != null ? ActorRef.user(sharedById, sharedByName) : null;
        return new Entry(item, rs.getLong(11), rs.getString(12), sharedBy);
    }

    private record ItemRow(String source, String youtubeId, String status) {
    }

    /**
     * Adds {@code itemId} to {@code user}'s library ({@code via='manual'}); nothing changes if it is
     * already there. A YouTube item goes through {@link YoutubeImports#addExisting} (a purged one is
     * fetched again). The item counts as referenced now.
     *
     * @param mayFetchYoutube checked before a purged or failed YouTube item is fetched again
     *                        ({@code library.youtube}); throws to refuse
     * @throws HttpError {@code 404 item_not_found}, {@code 409 media_expired} (a purged upload),
     *                   {@code 409 item_failed} (an upload that failed to ingest),
     *                   {@code 422 youtube_failed} (see {@link YoutubeImports#addExisting})
     */
    public Added add(Actor.User user, String itemId, YoutubeCheck mayFetchYoutube)
            throws SQLException, InterruptedException {
        String userId = user.userId();
        ItemRow row = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT source, youtube_id, status FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new ItemRow(rs.getString(1), rs.getString(2), rs.getString(3)) : null;
                }
            }
        });
        if (row == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        Entry before = get(userId, itemId);
        if (before != null) {
            return new Added(before, false);
        }
        boolean gone = row.status().equals("purged") || row.status().equals("failed");
        if (row.youtubeId() != null) {
            if (gone) {
                mayFetchYoutube.check();
            }
            youtube.addExisting(user, row.youtubeId(), VIA_MANUAL);
        } else {
            if (row.status().equals("purged")) {
                throw new HttpError(409, "media_expired",
                        "This item's media has expired; upload the file again to restore it");
            }
            if (row.status().equals("failed")) {
                throw new HttpError(409, "item_failed", "This item failed to process; upload the file again");
            }
            long now = EpochMs.now(clock);
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                        List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
                    ps.setString(1, userId);
                    ps.setString(2, itemId);
                    ps.setLong(3, now);
                    ps.setString(4, VIA_MANUAL);
                    ps.executeUpdate();
                }
                References.touch(c, itemId, now);
                return null;
            });
        }
        Entry after = get(userId, itemId);
        if (after == null) {
            // Removed again concurrently; report what was asked for.
            throw new HttpError(409, "conflict", "The library changed while adding; try again");
        }
        // It wasn't there a moment ago (two concurrent adds may both say so).
        return new Added(after, true);
    }

    /**
     * Removes {@code itemId} from the user's library and marks the item referenced until now.
     *
     * @return whether there was an entry
     */
    public boolean remove(String userId, String itemId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM library_entries WHERE user_id = ? AND item_id = ?")) {
                ps.setString(1, userId);
                ps.setString(2, itemId);
                n = ps.executeUpdate();
            }
            if (n > 0) {
                References.touch(c, itemId, now);
            }
            return n > 0;
        });
    }


    /** A permission check that throws an {@link HttpError} to refuse. */
    @FunctionalInterface
    public interface YoutubeCheck {
        void check() throws SQLException;
    }
}
