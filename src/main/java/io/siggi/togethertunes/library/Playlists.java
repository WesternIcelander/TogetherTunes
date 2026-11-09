package io.siggi.togethertunes.library;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.PostgresDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Users' playlists ({@code docs/libraries.md#playlists}): private, owned by one user, an ordered
 * list of {@code playlist_entries}. The same item may appear any number of times. Order is kept
 * by a fractional {@code position}: an entry added or moved before another gets the midpoint
 * between that entry and its predecessor, one added at the end gets the last position + 1. When
 * two neighbours are too close for a midpoint (after about 50 insertions into the same gap) the
 * playlist's positions are renumbered 1..n, in the same transaction. Positions never leave the
 * server; clients see the order of {@code entries}.
 *
 * <p>Every call names the owner: a playlist of somebody else is reported as missing
 * ({@code 404 playlist_not_found}), so its existence doesn't leak. ({@code library.viewAll}
 * read access is a separate step.) Entry changes run in one write transaction that first locks
 * the playlist row ({@code FOR UPDATE} on PostgreSQL; SQLite serialises writers anyway), so
 * concurrent changes to one playlist can't compute positions from a stale view.
 *
 * <p>Retention: an entry references its item. Adding one sets the item's
 * {@code last_referenced_at} to now; removing an entry, and deleting a playlist (for each of its
 * items), does too (an unreference event, {@code docs/libraries.md#retention}). Adding doesn't put
 * the item in the caller's library. Item rules for adding mirror {@link Library#add}: a purged or
 * failed upload can't be added ({@code 409 media_expired} / {@code item_failed}); a purged or
 * failed YouTube item is fetched again ({@link YoutubeImports#useExisting}, after the caller's
 * {@code library.youtube} check).
 */
public final class Playlists {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    /** Longest playlist name, in code points (after cleaning). */
    public static final int NAME_MAX = 100;
    /** Longest description, in code points (after cleaning). */
    public static final int DESCRIPTION_MAX = 2000;
    /** Most playlists one user can have. */
    public static final int MAX_PLAYLISTS = 1000;
    /** Most entries in one playlist. */
    public static final int MAX_ENTRIES = 10_000;

    private final Database db;
    private final YoutubeImports youtube;
    private final Clock clock;

    /** A playlist without its entries. {@code description} is {@code null} when there is none. */
    public record Playlist(String id, String name, String description, int entryCount, long createdAt,
                           long updatedAt) {
    }

    /** A playlist with its entries, in order. */
    public record PlaylistPage(String id, String name, String description, int entryCount, long createdAt,
                               long updatedAt, List<Entry> entries) {
    }

    /** One entry: its own id (the same item can be in a playlist more than once), the item, when added. */
    public record Entry(String id, Library.ItemView item, long addedAt) {
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

    public Playlists(Database db, YoutubeImports youtube, Clock clock) {
        this.db = Objects.requireNonNull(db);
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

    // ------------------------------------------------------------------ playlists

    private static final String SUMMARY = "SELECT p.id, p.name, p.description, p.created_at, p.updated_at,"
            + " (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlist_id = p.id) FROM playlists p";

    private static Playlist summary(ResultSet rs) throws SQLException {
        return new Playlist(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(6), rs.getLong(4),
                rs.getLong(5));
    }

    /**
     * The owner's playlists, most recently changed first (then by id, descending). Changing a
     * playlist (its name, description or entries) moves it to the front.
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     */
    public Page list(String ownerId, String cursor, int limit) throws SQLException {
        Objects.requireNonNull(ownerId);
        if (limit < 1 || limit > LIST_MAX) {
            throw new IllegalArgumentException("limit must be 1.." + LIST_MAX);
        }
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
        String sql = SUMMARY + " WHERE p.owner_id = ?"
                + (cursorId != null ? " AND (p.updated_at < ? OR (p.updated_at = ? AND p.id < ?))" : "")
                + " ORDER BY p.updated_at DESC, p.id DESC LIMIT " + (limit + 1);
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            List<Playlist> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, ownerId);
                if (afterId != null) {
                    ps.setLong(2, at);
                    ps.setLong(3, at);
                    ps.setString(4, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(summary(rs));
                    }
                }
            }
            String next = null;
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
                Playlist last = items.get(limit - 1);
                next = last.updatedAt() + "_" + last.id();
            }
            return new Page(List.copyOf(items), next);
        });
    }

    /**
     * Creates an empty playlist.
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
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM playlists WHERE owner_id = ?")) {
                ps.setString(1, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= MAX_PLAYLISTS) {
                        throw new HttpError(409, "too_many_playlists",
                                "You can have at most " + MAX_PLAYLISTS + " playlists");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlists (id, owner_id, name, description,"
                    + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, ownerId);
                ps.setString(3, cleanName);
                ps.setString(4, cleanDescription);
                ps.setLong(5, now);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return new Playlist(id, cleanName, cleanDescription, 0, now, now);
        });
    }

    /** The owner's playlist with its entries in order. @throws HttpError {@code 404 playlist_not_found} */
    public PlaylistPage get(String ownerId, String playlistId) throws SQLException {
        return db.read(c -> {
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
                    p.updatedAt(), List.copyOf(entries));
        });
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
     */
    public Playlist update(String ownerId, String playlistId, Edit edit) throws SQLException {
        String newName = edit.name() != null ? name(edit.name()) : null;
        String newDescription = edit.description() != null ? description(edit.description()) : null;
        boolean clear = edit.clearDescription() || (edit.description() != null && newDescription == null);
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            Playlist before = summary(c, ownerId, playlistId);
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
            return new Playlist(before.id(), name, description, before.entryCount(), before.createdAt(), now);
        });
    }

    /**
     * Deletes the playlist and its entries; each of its items counts as referenced until now.
     *
     * @return whether the owner had that playlist
     */
    public boolean delete(String ownerId, String playlistId) throws SQLException {
        long now = EpochMs.now(clock);
        return db.write(c -> {
            if (!exists(c, ownerId, playlistId, true)) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_referenced_at = ? WHERE id IN"
                    + " (SELECT item_id FROM playlist_entries WHERE playlist_id = ?) AND last_referenced_at < ?")) {
                ps.setLong(1, now);
                ps.setString(2, playlistId);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
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

    // ------------------------------------------------------------------ entries

    private static final String ENTRY = "SELECT e.id, e.added_at, i.id, i.kind, i.title, i.artist, i.album,"
            + " i.duration_ms, i.source, i.youtube_id, i.status, i.present_as_audio"
            + " FROM playlist_entries e JOIN items i ON i.id = e.item_id";

    private static Entry entry(ResultSet rs) throws SQLException {
        long duration = rs.getLong(8);
        Long durationMs = rs.wasNull() ? null : duration;
        Library.ItemView item = new Library.ItemView(rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), durationMs, rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getInt(12) != 0);
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

    private record ItemRow(String youtubeId, String status) {
    }

    /**
     * Adds {@code itemId} to the owner's playlist, before {@code beforeEntryId} or at the end.
     * An item already in the playlist is added again (a second entry).
     *
     * @param mayFetchYoutube checked before a purged or failed YouTube item is fetched again
     *                        ({@code library.youtube}); throws to refuse
     * @throws HttpError {@code 404 playlist_not_found}, {@code 404 item_not_found},
     *                   {@code 409 media_expired} / {@code item_failed} (a purged / failed upload),
     *                   {@code 422 youtube_failed} (see {@link YoutubeImports#useExisting}),
     *                   {@code 409 before_entry_not_found} ({@code beforeEntryId} isn't in this
     *                   playlist), {@code 409 playlist_full} (at {@link #MAX_ENTRIES})
     */
    public Entry add(Actor.User owner, String playlistId, String itemId, String beforeEntryId,
                     Library.YoutubeCheck mayFetchYoutube) throws SQLException, InterruptedException {
        String ownerId = owner.userId();
        ItemRow item = db.read(c -> {
            if (!exists(c, ownerId, playlistId, false)) {
                throw playlistNotFound();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT youtube_id, status FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new ItemRow(rs.getString(1), rs.getString(2)) : null;
                }
            }
        });
        if (item == null) {
            throw new HttpError(404, "item_not_found", "No such item");
        }
        boolean gone = item.status().equals("purged") || item.status().equals("failed");
        if (gone && item.youtubeId() == null) {
            if (item.status().equals("purged")) {
                throw new HttpError(409, "media_expired",
                        "This item's media has expired; upload the file again to restore it");
            }
            throw new HttpError(409, "item_failed", "This item failed to process; upload the file again");
        }
        if (gone) {
            mayFetchYoutube.check();
            // If the insert below fails (the playlist was deleted meanwhile, or is full), the
            // refetch still runs; the item was referenced, so retention handles it as usual.
            youtube.useExisting(item.youtubeId());
        }
        String entryId = UuidV7.newId();
        long now = EpochMs.now(clock);
        return db.write(c -> {
            lock(c, ownerId, playlistId);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = ?")) {
                ps.setString(1, playlistId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= MAX_ENTRIES) {
                        throw new HttpError(409, "playlist_full",
                                "A playlist can have at most " + MAX_ENTRIES + " entries");
                    }
                }
            }
            double position = positionBefore(c, playlistId, beforeEntryId, null);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO playlist_entries (id, playlist_id, item_id,"
                    + " position, added_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, entryId);
                ps.setString(2, playlistId);
                ps.setString(3, itemId);
                ps.setDouble(4, position);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            touchItem(c, itemId, now);
            touchPlaylist(c, playlistId, now);
            return entry(c, entryId);
        });
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
     * Removes one entry; its item counts as referenced until now.
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
            touchItem(c, itemId, now);
            touchPlaylist(c, playlistId, now);
            return true;
        });
    }

    // ------------------------------------------------------------------ positions

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

    /** {@code last_referenced_at = now} (never backwards). */
    private static void touchItem(Connection c, String itemId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE items SET last_referenced_at = ? WHERE id = ? AND last_referenced_at < ?")) {
            ps.setLong(1, now);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }
}
