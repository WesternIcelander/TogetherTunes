package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.retention.References;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Users' playlists ({@code docs/libraries.md#playlists}): private, owned by one user, an ordered
 * list of {@code playlist_entries}. The same item may appear any number of times (except in Liked
 * Songs). Order is kept by a fractional {@code position}: an entry added or moved before another
 * gets the midpoint between that entry and its predecessor, one added at the end gets the last
 * position + 1. When two neighbours are too close for a midpoint (after about 50 insertions into
 * the same gap) the playlist's positions are renumbered 1..n, in the same transaction. Positions
 * never leave the server; clients see the order of {@code entries}.
 *
 * <p><b>Liked Songs</b>: one built-in playlist per user ({@code playlists.builtin = 'likes'}),
 * made on demand (the first like, or the first look at it). It is an ordinary playlist row (it can
 * be read, its entries moved and removed like any other's), but it can't be renamed or deleted
 * ({@code 409 builtin_playlist}), it holds each item at most once (adding a liked item again
 * returns the existing entry), and a like puts the item at the top. It doesn't count towards
 * {@link #MAX_PLAYLISTS} and is always listed first.
 *
 * <p><b>Playlist order</b>: each user orders their own playlists ({@code playlists.position});
 * a new playlist goes to the top, {@link #movePlaylists} moves some of them to a place.
 *
 * <p><b>An item in a playlist is always in its owner's library</b>: adding an entry adds the item
 * to the owner's library first ({@link Library#add}, so an {@code uncategorized} video needs a
 * {@link Classify.Classification}: {@code 409 needs_classification}), and the insert transaction
 * makes sure the library entry is still there. {@link Library#remove} removes the item from the
 * user's playlists.
 *
 * <p>Every call names the owner: a playlist of somebody else is reported as missing
 * ({@code 404 playlist_not_found}), so its existence doesn't leak. ({@code library.viewAll}
 * read access goes through {@link UserLibraries}, which names the owner the same way.) Entry
 * changes run in one write transaction that first locks the playlist row ({@code FOR UPDATE} on
 * PostgreSQL; SQLite serialises writers anyway), so concurrent changes to one playlist can't
 * compute positions from a stale view.
 *
 * <p>Retention: an entry references its item. Adding one sets the item's
 * {@code last_referenced_at} to now; removing an entry, and deleting a playlist (for each of its
 * items), does too (an unreference event, {@code docs/libraries.md#retention}). Item rules for
 * adding mirror {@link Library#add}: a purged or failed upload can't be added ({@code 409
 * media_expired} / {@code item_failed}); a purged or failed YouTube item is fetched again (after
 * the caller's {@code library.youtube} check).
 */
public final class Playlists {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest playlist name, in code points (after cleaning). */
    public static final int NAME_MAX = 100;
    /** Longest description, in code points (after cleaning). */
    public static final int DESCRIPTION_MAX = 2000;
    /** Most playlists one user can have (Liked Songs not counted). */
    public static final int MAX_PLAYLISTS = 1000;
    /** Most entries in one playlist. */
    public static final int MAX_ENTRIES = 10_000;
    /** {@code playlists.builtin} of a user's Liked Songs. */
    public static final String BUILTIN_LIKES = "likes";
    /** The name Liked Songs is stored with. */
    public static final String LIKES_NAME = "Liked Songs";

    private final Database db;
    private final Library library;
    private final YoutubeImports youtube;
    private final Clock clock;

    /**
     * A playlist without its entries. {@code description} is {@code null} when there is none;
     * {@code builtin} is {@code null} for an ordinary playlist, {@value #BUILTIN_LIKES} for Liked
     * Songs.
     */
    public record Playlist(String id, String name, String description, int entryCount, long createdAt,
                           long updatedAt, String builtin) {
    }

    /** A playlist with its entries, in order. */
    public record PlaylistPage(String id, String name, String description, int entryCount, long createdAt,
                               long updatedAt, String builtin, List<Entry> entries) {
    }

    /** One entry: its own id (the same item can be in a playlist more than once), the item, when added. */
    public record Entry(String id, Library.ItemView item, long addedAt) {
    }

    /** The result of an add: the entry, and whether this call made it (false: already liked). */
    public record Added(Entry entry, boolean created) {
    }

    /** One page of playlists; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Playlist> items, String nextCursor) {
    }

    /**
     * A change to a playlist's name and description. {@code null} leaves a field as it is;
     * {@code clearDescription} removes the description.
     */
    public record Edit(String name, String description, boolean clearDescription) {
    }

    /** With a {@link Library} of its own (no library-add listener); for tests. */
    public Playlists(Database db, YoutubeImports youtube, Clock clock) {
        this(db, new Library(db, youtube, clock), youtube, clock);
    }

    /** @param library the server's library (its add listener queues quality re-encodes) */
    public Playlists(Database db, Library library, YoutubeImports youtube, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.library = Objects.requireNonNull(library);
        this.youtube = Objects.requireNonNull(youtube);
        this.clock = Objects.requireNonNull(clock);
    }

    // ------------------------------------------------------------------ validation

    /**
     * A cleaned playlist name (like upload metadata: control characters and runs of whitespace
     * become one space, trimmed).
     *
     * @throws HttpError {@code 400 invalid_name} (blank), {@code 400 name_too_long}
     */
    public static String name(String raw) {
        String name = UploadMetadata.clean(raw);
        if (name == null) {
            throw new HttpError(400, "invalid_name", "The name can't be empty");
        }
        if (raw.codePointCount(0, raw.length()) > UploadMetadata.MAX_CHARS
                || name.codePointCount(0, name.length()) > NAME_MAX) {
            throw new HttpError(400, "name_too_long", "At most " + NAME_MAX + " characters");
        }
        return name;
    }

    /**
     * A cleaned description ({@link UploadMetadata#cleanMultiline}: line breaks kept, at most two
     * in a row).
     *
     * @return {@code null} when nothing is left
     * @throws HttpError {@code 400 description_too_long}
     */
    public static String description(String raw) {
        return UploadMetadata.cleanMultiline(raw, DESCRIPTION_MAX, "description_too_long");
    }

    private static HttpError playlistNotFound() {
        return new HttpError(404, "playlist_not_found", "No such playlist");
    }

    private static HttpError entryNotFound() {
        return new HttpError(404, "entry_not_found", "No such entry in this playlist");
    }

    private static HttpError builtinPlaylist() {
        return new HttpError(409, "builtin_playlist", "Liked Songs can't be renamed, deleted or moved");
    }

    // ------------------------------------------------------------------ playlists

    private static final String SUMMARY = "SELECT p.id, p.name, p.description, p.created_at, p.updated_at,"
            + " (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id), p.builtin, p.position"
            + " FROM playlists p";
    /** 0 for the built-in playlist (listed first), 1 for the others. */
    private static final String RANK = "(CASE WHEN p.builtin IS NULL THEN 1 ELSE 0 END)";

    private static Playlist summary(ResultSet rs) throws SQLException {
        return new Playlist(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(6), rs.getLong(4),
                rs.getLong(5), rs.getString(7));
    }

    /**
     * The owner's playlists: Liked Songs first (once it exists), then the others in the owner's
     * order ({@link #movePlaylists}; a new playlist goes to the top).
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(String ownerId, String cursor, int limit) throws SQLException {
        Objects.requireNonNull(ownerId);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
        int cursorRank = 0;
        long cursorPosition = 0;
        String cursorId = null;
        if (cursor != null) {
            String[] parts = cursor.split("_", 3);
            try {
                if (parts.length != 3 || parts[2].isEmpty()) {
                    throw new NumberFormatException();
                }
                cursorRank = Integer.parseInt(parts[0]);
                cursorPosition = Long.parseLong(parts[1]);
                if (cursorRank != 0 && cursorRank != 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = parts[2];
        }
        String sql = SUMMARY + " WHERE p.owner_id = ?"
                + (cursorId != null ? " AND (" + RANK + " > ? OR (" + RANK + " = ? AND (p.position > ?"
                + " OR (p.position = ? AND p.id > ?))))" : "")
                + " ORDER BY " + RANK + ", p.position, p.id LIMIT " + (limit + 1);
        int rank = cursorRank;
        long position = cursorPosition;
        String afterId = cursorId;
        return db.read(c -> {
            List<Playlist> items = new ArrayList<>();
            List<Long> positions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, ownerId);
                if (afterId != null) {
                    ps.setInt(2, rank);
                    ps.setInt(3, rank);
                    ps.setLong(4, position);
                    ps.setLong(5, position);
                    ps.setString(6, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(summary(rs));
                        positions.add(rs.getLong(8));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Playlist last = items.get(limit - 1);
                next = (last.builtin() == null ? 1 : 0) + "_" + positions.get(limit - 1) + "_" + last.id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    /**
     * Creates an empty playlist, at the top of the owner's order.
     *
     * @param name        raw; see {@link #name}
     * @param description raw, or {@code null}; see {@link #description}
     * @throws HttpError {@code 409 too_many_playlists} (over {@link #MAX_PLAYLISTS}), and the
     *                   validation errors
     */
    public Playlist create(String ownerId, String name, String description) throws SQLException {
        String cleanName = name(name);
        String cleanDescription = description(description);
        String id = UuidV7.newId();
        long now = EpochMs.now(clock);
        return db.write(c -> {
            long top;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), MIN(position) FROM playlists"
                    + " WHERE owner_id = ? AND builtin IS NULL")) {
                ps.setString(1, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= MAX_PLAYLISTS) {
                        throw new HttpError(409, "too_many_playlists",
                                "You can have at most " + MAX_PLAYLISTS + " playlists");
                    }
                    long min = rs.getLong(2);
                    top = rs.wasNull() ? 1 : min - 1;
                }
            }
            insert(c, id, ownerId, cleanName, cleanDescription, null, top, now);
            return new Playlist(id, cleanName, cleanDescription, 0, now, now, null);
        });
    }

    private static void insert(Connection c, String id, String ownerId, String name, String description,
                               String builtin, long position, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlists (id, owner_id, name, description,"
                + " created_at, updated_at, builtin, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, ownerId);
            ps.setString(3, name);
            ps.setString(4, description);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, builtin);
            ps.setLong(8, position);
            ps.executeUpdate();
        }
    }

    /**
     * The owner's playlist without its entries.
     *
     * @throws HttpError {@code 404 playlist_not_found}
     */
    public Playlist summary(String ownerId, String playlistId) throws SQLException {
        Playlist p = db.read(c -> summary(c, ownerId, playlistId));
        if (p == null) {
            throw playlistNotFound();
        }
        return p;
    }

    /** The owner's playlist with its entries in order. @throws HttpError {@code 404 playlist_not_found} */
    public PlaylistPage get(String ownerId, String playlistId) throws SQLException {
        return db.read(c -> page(c, ownerId, playlistId));
    }

    private static PlaylistPage page(Connection c, String ownerId, String playlistId) throws SQLException {
        Playlist p = summary(c, ownerId, playlistId);
        if (p == null) {
            throw playlistNotFound();
        }
        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(ENTRY + " WHERE e.playlist_id = ?"
                + " ORDER BY e.position, e.id")) {
            ps.setString(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(entry(rs));
                }
            }
        }
        return new PlaylistPage(p.id(), p.name(), p.description(), entries.size(), p.createdAt(),
                p.updatedAt(), p.builtin(), List.copyOf(entries));
    }

    private static Playlist summary(Connection c, String ownerId, String playlistId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SUMMARY + " WHERE p.id = ? AND p.owner_id = ?")) {
            ps.setString(1, playlistId);
            ps.setString(2, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? summary(rs) : null;
            }
        }
    }

    /**
     * Renames the playlist and/or changes its description. An edit that changes nothing leaves
     * {@code updated_at} alone.
     *
     * @param edit raw values; see {@link #name} and {@link #description}
     * @throws HttpError {@code 404 playlist_not_found}, {@code 409 builtin_playlist} (Liked Songs)
     */
    public Playlist update(String ownerId, String playlistId, Edit edit) throws SQLException {
        String newName = edit.name() != null ? name(edit.name()) : null;
        String newDescription = edit.description() != null ? description(edit.description()) : null;
        boolean clear = edit.clearDescription() || (edit.description() != null && newDescription == null);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            Playlist before = summary(c, ownerId, playlistId);
            if (before.builtin() != null) {
                throw builtinPlaylist();
            }
            String name = newName != null ? newName : before.name();
            String description = clear ? null : newDescription != null ? newDescription : before.description();
            if (name.equals(before.name()) && Objects.equals(description, before.description())) {
                return before;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE playlists SET name = ?, description = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, description);
                ps.setLong(3, now);
                ps.setString(4, playlistId);
                ps.executeUpdate();
            }
            return new Playlist(before.id(), name, description, before.entryCount(), before.createdAt(), now, null);
        });
    }

    /**
     * Deletes the playlist and its entries; each of its items counts as referenced until now. The
     * items stay in the owner's library.
     *
     * @return whether the owner had that playlist
     * @throws HttpError {@code 409 builtin_playlist} (Liked Songs)
     */
    public boolean delete(String ownerId, String playlistId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            if (!exists(c, ownerId, playlistId, true)) {
                return false;
            }
            if (summary(c, ownerId, playlistId).builtin() != null) {
                throw builtinPlaylist();
            }
            References.touchPlaylist(c, playlistId, now);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM playlist_entries WHERE playlist_id = ?")) {
                ps.setString(1, playlistId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM playlists WHERE id = ?")) {
                ps.setString(1, playlistId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /**
     * Moves the owner's playlists {@code playlistIds} (in that order) before {@code beforeId}, or to
     * the end when that is {@code null}. Before one of the moved playlists means before the first
     * playlist after it that isn't moved. Doesn't change {@code updated_at}.
     *
     * @param playlistIds distinct, at least one
     * @throws HttpError {@code 404 playlist_not_found} (one of {@code playlistIds}),
     *                   {@code 409 before_playlist_not_found}, {@code 409 builtin_playlist}
     *                   (Liked Songs is always first)
     */
    public void movePlaylists(String ownerId, List<String> playlistIds, String beforeId) throws SQLException {
        if (playlistIds.isEmpty() || new HashSet<>(playlistIds).size() != playlistIds.size()) {
            throw new IllegalArgumentException("playlistIds must be distinct and not empty");
        }
        boolean forUpdate = db.dialect() instanceof PostgresDialect;
        db.write(c -> {
            List<String> order = new ArrayList<>();
            List<Long> positions = new ArrayList<>();
            String builtinId = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT id, position, builtin FROM playlists"
                    + " WHERE owner_id = ? ORDER BY position, id" + (forUpdate ? " FOR UPDATE" : ""))) {
                ps.setString(1, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getString(3) != null) {
                            builtinId = rs.getString(1);
                        } else {
                            order.add(rs.getString(1));
                            positions.add(rs.getLong(2));
                        }
                    }
                }
            }
            for (String id : playlistIds) {
                if (id.equals(builtinId)) {
                    throw builtinPlaylist();
                }
                if (!order.contains(id)) {
                    throw playlistNotFound();
                }
            }
            if (beforeId != null && beforeId.equals(builtinId)) {
                throw builtinPlaylist();
            }
            if (beforeId != null && !order.contains(beforeId)) {
                throw new HttpError(409, "before_playlist_not_found",
                        "beforePlaylistId isn't one of your playlists (any more)");
            }
            List<String> reordered = reorder(order, playlistIds, beforeId);
            try (PreparedStatement ps = c.prepareStatement("UPDATE playlists SET position = ? WHERE id = ?")) {
                for (int i = 0; i < reordered.size(); i++) {
                    long wanted = i + 1;
                    if (!reordered.get(i).equals(order.get(i)) || positions.get(i) != wanted) {
                        ps.setLong(1, wanted);
                        ps.setString(2, reordered.get(i));
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * {@code order} with {@code moved} (in that order) placed before {@code beforeId} (the first
     * one after it that isn't moved, if it is moved itself), or at the end.
     */
    static List<String> reorder(List<String> order, List<String> moved, String beforeId) {
        Set<String> movedSet = new HashSet<>(moved);
        String anchor = null;
        if (beforeId != null) {
            boolean seen = false;
            for (String id : order) {
                if (id.equals(beforeId)) {
                    seen = true;
                }
                if (seen && !movedSet.contains(id)) {
                    anchor = id;
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>(order.size());
        for (String id : order) {
            if (id.equals(anchor)) {
                out.addAll(moved);
            }
            if (!movedSet.contains(id)) {
                out.add(id);
            }
        }
        if (anchor == null) {
            out.addAll(moved);
        }
        return out;
    }

    // ------------------------------------------------------------------ Liked Songs

    /** The id of the owner's Liked Songs, made now if it doesn't exist yet. */
    private String ensureLikes(Connection c, String ownerId) throws SQLException {
        String id = likesId(c, ownerId);
        if (id != null) {
            return id;
        }
        long now = EpochMs.now(clock);
        try (PreparedStatement ps = c.prepareStatement(db.dialect().insertOrIgnore("playlists",
                List.of("id", "owner_id", "name", "description", "created_at", "updated_at", "builtin", "position"),
                List.of("owner_id", "builtin")))) {
            ps.setString(1, UuidV7.newId());
            ps.setString(2, ownerId);
            ps.setString(3, LIKES_NAME);
            ps.setString(4, null);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, BUILTIN_LIKES);
            ps.setLong(8, 0);
            ps.executeUpdate();
        }
        return likesId(c, ownerId);
    }

    private static String likesId(Connection c, String ownerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM playlists WHERE owner_id = ? AND builtin = '" + BUILTIN_LIKES + "'")) {
            ps.setString(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The owner's Liked Songs with its entries (made now if it doesn't exist yet). */
    public PlaylistPage likes(String ownerId) throws SQLException {
        String id = db.write(c -> ensureLikes(c, ownerId));
        return get(ownerId, id);
    }

    /** Whether {@code itemId} is in the owner's Liked Songs. */
    public boolean liked(String ownerId, String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM playlist_entries e JOIN playlists p"
                    + " ON p.id = e.playlist_id WHERE p.owner_id = ? AND p.builtin = '" + BUILTIN_LIKES + "'"
                    + " AND e.item_id = ?")) {
                ps.setString(1, ownerId);
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Likes {@code itemId}: puts it at the top of the owner's Liked Songs (and in their library,
     * as every playlist add does). An item already liked stays where it is ({@code created} false).
     *
     * @see #add(Actor.User, String, String, String, Classify.Classification, Library.YoutubeCheck)
     */
    public Added like(Actor.User owner, String itemId, Classify.Classification classification,
                      Library.YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        String id = db.write(c -> ensureLikes(c, owner.userId()));
        return add(owner, id, itemId, null, true, classification, mayFetchYoutube);
    }

    /**
     * Takes {@code itemId} out of the owner's Liked Songs (it stays in their library).
     *
     * @return whether it was liked
     */
    public boolean unlike(String ownerId, String itemId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            String id = likesId(c, ownerId);
            if (id == null) {
                return false;
            }
            lock(c, ownerId, id);
            int n;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM playlist_entries WHERE playlist_id = ? AND item_id = ?")) {
                ps.setString(1, id);
                ps.setString(2, itemId);
                n = ps.executeUpdate();
            }
            if (n > 0) {
                References.touch(c, itemId, now);
                touchPlaylist(c, id, now);
            }
            return n > 0;
        });
    }

    /** The owner's playlists that hold {@code itemId}, in list order (without entries). */
    public List<Playlist> containing(String ownerId, String itemId) throws SQLException {
        return db.read(c -> {
            List<Playlist> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SUMMARY + " WHERE p.owner_id = ? AND EXISTS (SELECT 1"
                    + " FROM playlist_entries x WHERE x.playlist_id = p.id AND x.item_id = ?)"
                    + " ORDER BY " + RANK + ", p.position, p.id")) {
                ps.setString(1, ownerId);
                ps.setString(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(summary(rs));
                    }
                }
            }
            return List.copyOf(out);
        });
    }

    // ------------------------------------------------------------------ entries

    private static final String ENTRY = "SELECT e.id, e.added_at, " + Library.ItemView.COLUMNS
            + " FROM playlist_entries e JOIN items i ON i.id = e.item_id";

    private static Entry entry(ResultSet rs) throws SQLException {
        Library.ItemView item = Library.ItemView.read(rs, 3);
        return new Entry(rs.getString(1), item, rs.getLong(2));
    }

    private static Entry entry(Connection c, String entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(ENTRY + " WHERE e.id = ?")) {
            ps.setString(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? entry(rs) : null;
            }
        }
    }

    private static String entryOfItem(Connection c, String playlistId, String itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM playlist_entries WHERE playlist_id = ? AND item_id = ? ORDER BY position, id")) {
            ps.setString(1, playlistId);
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private record Before(String builtin, int entries, String youtubeId, String status, String likedEntryId) {
    }

    /**
     * Adds {@code itemId} to the owner's playlist, at the end; see
     * {@link #add(Actor.User, String, String, String, Classify.Classification, Library.YoutubeCheck)}.
     */
    public Entry add(Actor.User owner, String playlistId, String itemId, String beforeEntryId,
                     Library.YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        return add(owner, playlistId, itemId, beforeEntryId, null, mayFetchYoutube).entry();
    }

    /**
     * Adds {@code itemId} to the owner's playlist, before {@code beforeEntryId} or at the end, and to
     * the owner's library ({@link Library#add}) if it isn't there yet. An item already in the
     * playlist is added again (a second entry), except in Liked Songs, which returns the existing
     * entry.
     *
     * @param classification  for an {@code uncategorized} video not yet in the owner's library;
     *                        ignored otherwise
     * @param mayFetchYoutube checked before a purged or failed YouTube item is fetched again
     *                        ({@code library.youtube}); throws to refuse
     * @throws HttpError {@code 404 playlist_not_found}, {@code 404 item_not_found},
     *                   {@code 409 media_expired} / {@code item_failed} (a purged / failed upload),
     *                   {@code 409 needs_classification} ({@link Classify}),
     *                   {@code 422 youtube_failed} (see {@link YoutubeImports#useExisting}),
     *                   {@code 409 before_entry_not_found} ({@code beforeEntryId} isn't in this
     *                   playlist), {@code 409 playlist_full} (at {@link #MAX_ENTRIES})
     */
    public Added add(Actor.User owner, String playlistId, String itemId, String beforeEntryId,
                     Classify.Classification classification, Library.YoutubeCheck mayFetchYoutube)
            throws SQLException, InterruptedException {
        return add(owner, playlistId, itemId, beforeEntryId, false, classification, mayFetchYoutube);
    }

    private Added add(Actor.User owner, String playlistId, String itemId, String beforeEntryId, boolean atTop,
                      Classify.Classification classification, Library.YoutubeCheck mayFetchYoutube)
            throws SQLException, InterruptedException {
        String ownerId = owner.userId();
        Before before = db.read(c -> {
            Playlist p = summary(c, ownerId, playlistId);
            if (p == null) {
                throw playlistNotFound();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT youtube_id, status FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Before(p.builtin(), p.entryCount(), rs.getString(1), rs.getString(2),
                            p.builtin() != null ? entryOfItem(c, playlistId, itemId) : null);
                }
            }
        });
        if (before == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        if (before.likedEntryId() != null) {
            Entry existing = db.read(c -> entry(c, before.likedEntryId()));
            if (existing != null) {
                return new Added(existing, false);
            }
        }
        boolean gone = before.status().equals("purged") || before.status().equals("failed");
        if (gone && before.youtubeId() == null) {
            if (before.status().equals("purged")) {
                throw new HttpError(409, "media_expired",
                        "This item's media has expired; upload the file again to restore it");
            }
            throw new HttpError(409, "item_failed", "This item failed to process; upload the file again");
        }
        if (before.entries() >= MAX_ENTRIES) {
            throw playlistFull();
        }
        if (library.get(ownerId, itemId) == null) {
            // Asks for a classification, fetches a purged or failed YouTube item again (after the
            // check) and adds the library entry. If the insert below fails (the playlist was
            // deleted meanwhile, or is full), the item stays in the library.
            library.add(owner, itemId, classification, mayFetchYoutube);
        } else if (gone) {
            mayFetchYoutube.check();
            youtube.useExisting(before.youtubeId());
        }
        String entryId = UuidV7.newId();
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            if (before.builtin() != null) {
                String existing = entryOfItem(c, playlistId, itemId);
                if (existing != null) {
                    return new Added(entry(c, existing), false);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = ?")) {
                ps.setString(1, playlistId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= MAX_ENTRIES) {
                        throw playlistFull();
                    }
                }
            }
            // Removed from the library meanwhile: put it back (asking again if it needs a type).
            library.ensureEntry(c, ownerId, itemId, now);
            double position = atTop ? positionTop(c, playlistId) : positionBefore(c, playlistId, beforeEntryId, null);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlist_entries (id, playlist_id, item_id,"
                    + " position, added_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, entryId);
                ps.setString(2, playlistId);
                ps.setString(3, itemId);
                ps.setDouble(4, position);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            References.touch(c, itemId, now);
            touchPlaylist(c, playlistId, now);
            return new Added(entry(c, entryId), true);
        });
    }

    private static HttpError playlistFull() {
        return new HttpError(409, "playlist_full", "A playlist can have at most " + MAX_ENTRIES + " entries");
    }

    /**
     * Moves an entry before {@code beforeEntryId}, or to the end when that is {@code null}.
     * Moving an entry before itself changes nothing.
     *
     * @throws HttpError {@code 404 playlist_not_found}, {@code 404 entry_not_found},
     *                   {@code 409 before_entry_not_found}
     */
    public Entry move(String ownerId, String playlistId, String entryId, String beforeEntryId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            if (position(c, playlistId, entryId) == null) {
                throw entryNotFound();
            }
            if (!entryId.equals(beforeEntryId)) {
                double position = positionBefore(c, playlistId, beforeEntryId, entryId);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE playlist_entries SET position = ? WHERE id = ?")) {
                    ps.setDouble(1, position);
                    ps.setString(2, entryId);
                    ps.executeUpdate();
                }
                touchPlaylist(c, playlistId, now);
            }
            return entry(c, entryId);
        });
    }

    /**
     * Moves several entries, in the given order, before {@code beforeEntryId} (or to the end when
     * that is {@code null}), in one transaction. Before one of the moved entries means before the
     * first entry after it that isn't moved. A move that leaves the order as it was changes nothing.
     *
     * @param entryIds distinct, at least one
     * @return the playlist afterwards
     * @throws HttpError {@code 404 playlist_not_found}, {@code 404 entry_not_found} (any of
     *                   {@code entryIds}), {@code 409 before_entry_not_found}
     */
    public PlaylistPage moveEntries(String ownerId, String playlistId, List<String> entryIds, String beforeEntryId)
            throws SQLException {
        if (entryIds.isEmpty() || new LinkedHashSet<>(entryIds).size() != entryIds.size()) {
            throw new IllegalArgumentException("entryIds must be distinct and not empty");
        }
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            List<String> order = new ArrayList<>();
            List<Double> positions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, position FROM playlist_entries WHERE playlist_id = ? ORDER BY position, id")) {
                ps.setString(1, playlistId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        order.add(rs.getString(1));
                        positions.add(rs.getDouble(2));
                    }
                }
            }
            Set<String> present = new HashSet<>(order);
            for (String id : entryIds) {
                if (!present.contains(id)) {
                    throw entryNotFound();
                }
            }
            if (beforeEntryId != null && !present.contains(beforeEntryId)) {
                throw new HttpError(409, "before_entry_not_found",
                        "beforeEntryId isn't an entry of this playlist (any more)");
            }
            List<String> reordered = reorder(order, entryIds, beforeEntryId);
            if (!reordered.equals(order)) {
                placeMoved(c, order, positions, reordered, entryIds);
                touchPlaylist(c, playlistId, now);
            }
            return page(c, ownerId, playlistId);
        });
    }

    /**
     * Writes positions for {@code moved} (a run inside {@code reordered}): spread over the gap
     * between their new neighbours, or a renumbering of the whole playlist when the gap is too
     * small.
     */
    private static void placeMoved(Connection c, List<String> order, List<Double> positions, List<String> reordered,
                                   List<String> moved) throws SQLException {
        int k = moved.size();
        int first = reordered.indexOf(moved.get(0));
        Double prev = first > 0 ? positions.get(order.indexOf(reordered.get(first - 1))) : null;
        Double next = first + k < reordered.size() ? positions.get(order.indexOf(reordered.get(first + k))) : null;
        double[] wanted = new double[k];
        boolean fits = true;
        if (prev == null && next == null) {
            for (int i = 0; i < k; i++) {
                wanted[i] = i + 1;
            }
        } else if (next == null) {
            for (int i = 0; i < k; i++) {
                wanted[i] = Math.floor(prev) + 1 + i;
            }
        } else if (prev == null) {
            for (int i = 0; i < k; i++) {
                wanted[i] = next - k + i;
            }
        } else {
            double step = (next - prev) / (k + 1);
            double last = prev;
            for (int i = 0; i < k; i++) {
                wanted[i] = prev + step * (i + 1);
                if (!(wanted[i] > last)) {
                    fits = false;
                    break;
                }
                last = wanted[i];
            }
            fits = fits && last < next;
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE playlist_entries SET position = ? WHERE id = ?")) {
            if (fits) {
                for (int i = 0; i < k; i++) {
                    ps.setDouble(1, wanted[i]);
                    ps.setString(2, moved.get(i));
                    ps.addBatch();
                }
            } else {
                for (int i = 0; i < reordered.size(); i++) {
                    ps.setDouble(1, i + 1);
                    ps.setString(2, reordered.get(i));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    /**
     * Removes one entry; its item counts as referenced until now. The item stays in the owner's
     * library.
     *
     * @return whether the playlist had that entry
     * @throws HttpError {@code 404 playlist_not_found}
     */
    public boolean remove(String ownerId, String playlistId, String entryId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            String itemId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item_id FROM playlist_entries WHERE id = ? AND playlist_id = ?")) {
                ps.setString(1, entryId);
                ps.setString(2, playlistId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    itemId = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM playlist_entries WHERE id = ?")) {
                ps.setString(1, entryId);
                ps.executeUpdate();
            }
            References.touch(c, itemId, now);
            touchPlaylist(c, playlistId, now);
            return true;
        });
    }

    // ------------------------------------------------------------------ positions

    /** The position for an entry placed before every other one. */
    private static double positionTop(Connection c, String playlistId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MIN(position) FROM playlist_entries WHERE playlist_id = ?")) {
            ps.setString(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                double min = rs.getDouble(1);
                return rs.wasNull() ? 1.0 : Math.ceil(min) - 1.0;
            }
        }
    }

    /**
     * The position for an entry placed before {@code beforeEntryId} (or at the end when
     * {@code null}), ignoring {@code movingEntryId}. Renumbers the playlist when there is no room.
     */
    private double positionBefore(Connection c, String playlistId, String beforeEntryId, String movingEntryId)
            throws SQLException {
        String exclude = movingEntryId != null ? movingEntryId : "";
        if (beforeEntryId == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MAX(position) FROM playlist_entries WHERE playlist_id = ? AND id <> ?")) {
                ps.setString(1, playlistId);
                ps.setString(2, exclude);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    double max = rs.getDouble(1);
                    return rs.wasNull() ? 1.0 : Math.floor(max) + 1.0;
                }
            }
        }
        for (int attempt = 0; ; attempt++) {
            Double next = position(c, playlistId, beforeEntryId);
            if (next == null) {
                throw new HttpError(409, "before_entry_not_found",
                        "beforeEntryId isn't an entry of this playlist (any more)");
            }
            Double prev;
            try (PreparedStatement ps = c.prepareStatement("SELECT MAX(position) FROM playlist_entries"
                    + " WHERE playlist_id = ? AND id <> ? AND id <> ? AND position <= ?")) {
                ps.setString(1, playlistId);
                ps.setString(2, exclude);
                ps.setString(3, beforeEntryId);
                ps.setDouble(4, next);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    double p = rs.getDouble(1);
                    prev = rs.wasNull() ? null : p;
                }
            }
            if (prev == null) {
                return next - 1.0;
            }
            double mid = prev + (next - prev) / 2;
            if (mid > prev && mid < next) {
                return mid;
            }
            if (attempt > 0) {
                throw new IllegalStateException("No room between playlist positions after renumbering");
            }
            renumber(c, playlistId);
        }
    }

    /** Positions 1..n in the current order. */
    private static void renumber(Connection c, String playlistId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM playlist_entries WHERE playlist_id = ? ORDER BY position, id")) {
            ps.setString(1, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE playlist_entries SET position = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setDouble(1, i + 1);
                ps.setString(2, ids.get(i));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static Double position(Connection c, String playlistId, String entryId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT position FROM playlist_entries WHERE id = ? AND playlist_id = ?")) {
            ps.setString(1, entryId);
            ps.setString(2, playlistId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Locks the owner's playlist row for this transaction. @throws HttpError {@code 404 playlist_not_found} */
    private void lock(Connection c, String ownerId, String playlistId) throws SQLException {
        if (!exists(c, ownerId, playlistId, true)) {
            throw playlistNotFound();
        }
    }

    private boolean exists(Connection c, String ownerId, String playlistId, boolean lock) throws SQLException {
        // SQLite has no FOR UPDATE; its writes are serialised on one connection anyway.
        boolean forUpdate = lock && db.dialect() instanceof PostgresDialect;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM playlists WHERE id = ? AND owner_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setString(1, playlistId);
            ps.setString(2, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void touchPlaylist(Connection c, String playlistId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE playlists SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, playlistId);
            ps.executeUpdate();
        }
    }

}
