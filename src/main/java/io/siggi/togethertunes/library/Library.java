package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
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
    /** {@code library_entries.via} of an item a room owner added from the old request history (no longer written). */
    public static final String VIA_REQUEST = "request";

    private final Database db;
    private final YoutubeImports youtube;
    private final Clock clock;
    private volatile LibraryAddListener libraryAddListener = LibraryAddListener.NONE;

    /**
     * An item as lists and item pages show it: metadata and state, nothing about who has it.
     *
     * @param durationMs     {@code null} until known
     * @param youtubeId      the video ID of a YouTube item, else {@code null}
     * @param status         {@code pending}, {@code processing}, {@code ready}, {@code failed} or
     *                       {@code purged} (media expired)
     * @param presentAsAudio a video item played like an audio item, with its card (derived: a
     *                       video item of media type {@code audio})
     * @param mediaType      {@link MediaType} ({@code docs/media-types.md})
     * @param show           a {@code show}'s details, else {@code null}
     */
    public record ItemView(String id, String kind, String title, String artist, String album, Long durationMs,
                           String source, String youtubeId, String status, boolean presentAsAudio,
                           String mediaType, MediaType.Show show) {
        public ItemView(String id, String kind, String title, String artist, String album, Long durationMs,
                        String source, String youtubeId, String status, String mediaType, MediaType.Show show) {
            this(id, kind, title, artist, album, durationMs, source, youtubeId, status,
                    MediaType.presentAsAudio(kind, mediaType), mediaType, show);
        }

        /** The {@code items} columns (13) {@link #read} reads, for the table alias {@code i}. */
        public static final String COLUMNS = "i.id, i.kind, i.title, i.artist, i.album, i.duration_ms, i.source,"
                + " i.youtube_id, i.status, " + MediaType.columns("i");
        /** How many columns {@link #COLUMNS} has. */
        public static final int COLUMN_COUNT = 13;

        /** The item from the {@link #COLUMNS} starting at {@code first}. */
        public static ItemView read(java.sql.ResultSet rs, int first) throws SQLException {
            long duration = rs.getLong(first + 5);
            Long durationMs = rs.wasNull() ? null : duration;
            return new ItemView(rs.getString(first), rs.getString(first + 1), rs.getString(first + 2),
                    rs.getString(first + 3), rs.getString(first + 4), durationMs, rs.getString(first + 6),
                    rs.getString(first + 7), rs.getString(first + 8),
                    MediaType.effective(rs.getString(first + 1), rs.getString(first + 9)), MediaType.show(rs, first + 9));
        }
    }

    /**
     * One library entry.
     *
     * @param via how it got there: {@code upload}, {@code youtube}, {@code request} or
     *            {@code manual} (entries of the former item sharing became {@code manual})
     */
    public record Entry(ItemView item, long addedAt, String via) {
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

    private static final String SELECT = "SELECT " + ItemView.COLUMNS + ", e.added_at, e.via"
            + " FROM library_entries e JOIN items i ON i.id = e.item_id";

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
        return list(userId, query, kind, null, cursor, limit);
    }

    /**
     * As {@link #list(String, String, String, String, int)}, with the {@code type=} filter
     * ({@link MediaType#filter}: comma-separated media types, null for all; {@code 400 invalid_type}).
     */
    public Page list(String userId, String query, String kind, String type, String cursor, int limit)
            throws SQLException {
        Objects.requireNonNull(userId);
        List<String> types = MediaType.filter(type);
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
    public static List<String> terms(String query) {
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
        ItemView item = ItemView.read(rs, 1);
        return new Entry(item, rs.getLong(14), rs.getString(15));
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
        return add(user, itemId, VIA_MANUAL, null, mayFetchYoutube);
    }

    /**
     * {@link #add(Actor.User, String, YoutubeCheck)} with the classification an
     * {@code uncategorized} video needs to go into a library ({@link Classify}; ignored for an item
     * that doesn't need one).
     *
     * @throws Classify.NeedsClassification {@code 409 needs_classification} when the item needs a
     *                                      classification and none was given (nothing changes)
     */
    public Added add(Actor.User user, String itemId, Classify.Classification classification,
                     YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        return add(user, itemId, VIA_MANUAL, classification, mayFetchYoutube);
    }

    /**
     * {@link #add(Actor.User, String, YoutubeCheck)} with the entry's {@code via}:
     * {@link #VIA_MANUAL} or {@link #VIA_REQUEST} (the old request history; kept for tests and old rows).
     */
    public Added add(Actor.User user, String itemId, String via, YoutubeCheck mayFetchYoutube)
            throws SQLException, InterruptedException {
        return add(user, itemId, via, null, mayFetchYoutube);
    }

    private Added add(Actor.User user, String itemId, String via, Classify.Classification classification,
                      YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        if (!VIA_MANUAL.equals(via) && !VIA_REQUEST.equals(via)) {
            throw new IllegalArgumentException("via must be manual or request: " + via);
        }
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
        if (classification == null && db.read(c -> Classify.needs(c, userId, itemId))) {
            // Asked before anything happens (no refetch either); the client adds again with a type.
            throw Classify.needsClassification(db, itemId);
        }
        boolean gone = row.status().equals("purged") || row.status().equals("failed");
        if (row.youtubeId() != null) {
            if (gone) {
                mayFetchYoutube.check();
            }
            youtube.addExisting(user, row.youtubeId(), via, classification);
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
                if (!Classify.prepareAdd(c, userId, itemId, classification, userId, now)) {
                    throw new Classify.NeedsClassification(itemId, Classify.suggest(c, itemId));
                }
                try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                        List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
                    ps.setString(1, userId);
                    ps.setString(2, itemId);
                    ps.setLong(3, now);
                    ps.setString(4, via);
                    ps.executeUpdate();
                }
                References.touch(c, itemId, now);
                libraryAddListener.addedToLibrary(itemId);
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
     * Removes {@code itemId} from the user's library, and from all of the user's playlists (an item
     * in a playlist is always in its owner's library; the UI asks first), and marks the item
     * referenced until now. Playlists that lose entries get {@code updated_at = now}.
     *
     * @return whether there was an entry (in the library or one of the playlists)
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
            try (PreparedStatement ps = c.prepareStatement("UPDATE playlists SET updated_at = ? WHERE owner_id = ?"
                    + " AND id IN (SELECT pe.playlist_id FROM playlist_entries pe WHERE pe.item_id = ?)")) {
                ps.setLong(1, now);
                ps.setString(2, userId);
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM playlist_entries WHERE item_id = ?"
                    + " AND playlist_id IN (SELECT p.id FROM playlists p WHERE p.owner_id = ?)")) {
                ps.setString(1, itemId);
                ps.setString(2, userId);
                n += ps.executeUpdate();
            }
            if (n > 0) {
                References.touch(c, itemId, now);
            }
            return n > 0;
        });
    }

    /**
     * In a write transaction: makes sure {@code itemId} is in {@code userId}'s library (a playlist
     * add, {@link Playlists}), adding a {@link #VIA_MANUAL} entry if it isn't.
     *
     * @throws Classify.NeedsClassification when it isn't there and needs a classification
     */
    void ensureEntry(Connection c, String userId, String itemId, long now) throws SQLException {
        if (get(c, userId, itemId) != null) {
            return;
        }
        if (Classify.needs(c, userId, itemId)) {
            throw new Classify.NeedsClassification(itemId, Classify.suggest(c, itemId));
        }
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("library_entries",
                List.of("user_id", "item_id", "added_at", "via"), List.of("user_id", "item_id")))) {
            ps.setString(1, userId);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.setString(4, VIA_MANUAL);
            ps.executeUpdate();
        }
        libraryAddListener.addedToLibrary(itemId);
    }


    /**
     * The {@code show} items in the user's library, grouped by show name, season and episode
     * ({@link Episodes#group}; {@code docs/media-types.md#media-types}).
     *
     * @param name only the show with this name ({@link Episodes#showKey}); {@code null} for all
     */
    public List<Episodes.Show<Entry>> shows(String userId, String name) throws SQLException {
        Objects.requireNonNull(userId);
        List<Entry> episodes = showEntries(db, userId, name);
        return Episodes.group(episodes, e -> e.item().show().name(), Library::place, e -> e.item().id());
    }

    private static List<Entry> showEntries(Database db, String userId, String name) throws SQLException {
        String key = name == null ? null : Episodes.showKey(name);
        return db.read(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT
                    + " WHERE e.user_id = ? AND i.kind = 'video' AND i.media_type = 'show'")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Entry e = entry(rs);
                        if (e.item().show() != null
                                && (key == null || key.equals(Episodes.showKey(e.item().show().name())))) {
                            out.add(e);
                        }
                    }
                }
            }
            return out;
        });
    }

    private static Episodes.Place place(Entry e) {
        MediaType.Show s = e.item().show();
        return new Episodes.Place(s.season(), s.episode());
    }

    /**
     * <b>Next episode</b> ({@code docs/media-types.md#next-episode}): the item in the user's library
     * with the same show name as {@code itemId} that comes next by (season, episode)
     * ({@link Episodes#next}). Uploads that can never play again (purged or failed) are passed
     * over; a purged or failed YouTube episode counts (it is fetched again when queued).
     *
     * @throws HttpError {@code 404 item_not_found}, {@code 409 not_a_show} ({@code itemId} isn't a
     *                   {@code show} item), {@code 404 no_next_episode}
     */
    public ItemView nextEpisode(String userId, String itemId) throws SQLException {
        return nextEpisode(db, userId, itemId);
    }

    /** {@link #nextEpisode(String, String)} on {@code db}. */
    public static ItemView nextEpisode(Database db, String userId, String itemId) throws SQLException {
        Objects.requireNonNull(userId);
        MediaType.Show show = db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + ItemView.COLUMNS + " FROM items i WHERE i.id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new HttpError(404, "item_not_found", "No such item");
                    }
                    ItemView item = ItemView.read(rs, 1);
                    return MediaType.SHOW.wire().equals(item.mediaType()) ? item.show() : null;
                }
            }
        });
        if (show == null) {
            throw new HttpError(409, "not_a_show", "That isn't a show episode");
        }
        List<Entry> candidates = new ArrayList<>();
        for (Entry e : showEntries(db, userId, show.name())) {
            ItemView i = e.item();
            boolean gone = "purged".equals(i.status()) || "failed".equals(i.status());
            if (!(gone && i.youtubeId() == null)) {
                candidates.add(e);
            }
        }
        Entry next = Episodes.next(candidates, new Episodes.Place(show.season(), show.episode()), Library::place,
                e -> e.item().id());
        if (next == null) {
            throw new HttpError(404, "no_next_episode", "There's no next episode of " + show.name().strip()
                    + " in the library");
        }
        return next.item();
    }

    /** A permission check that throws an {@link HttpError} to refuse. */
    @FunctionalInterface
    public interface YoutubeCheck {
        void check() throws SQLException;
    }

    /** Sets who is told when this class adds an item to a library ({@link LibraryAddListener}). */
    public void libraryAddListener(LibraryAddListener listener) {
        this.libraryAddListener = listener == null ? LibraryAddListener.NONE : listener;
    }
}
