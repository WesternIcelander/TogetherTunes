package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.ActorRef;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.library.Library;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A room's history ({@code docs/rooms.md#history}): every entry that started playing in the room
 * and has ended (played or skipped), newest first, read from {@code queue_entries}. It replaces the
 * owner's request history: one history, visible to everyone who may open the room.
 *
 * <p>Each row is the item's metadata (a purged item keeps its row, so it stays readable), when it
 * played, who added it (with the display name they had then, {@code queue_entries.added_by_name};
 * the current one for entries made before that was kept), how ({@code via}) and whether it was
 * skipped. For the viewer: whether the item is in their library (users only) and whether it is
 * within the room's {@code noRepeatWindow} (an add by anyone but the owner and moderators is
 * refused then: {@code 409 recently_played}). Adding an item again goes through {@code queue.add}
 * with {@code historyEntryId} ({@link RoomQueue.Source#history}); adding it to one's library through
 * {@code PUT /api/library/{itemId}}.
 *
 * <p>Rows older than {@code rooms.requestHistoryDays} are never listed, and {@link #trim} deletes
 * them. No IP address is read or returned: an adder is an {@link ActorRef}.
 */
public final class RoomHistory {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** The {@code rooms.requestHistoryDays} setting. */
    @FunctionalInterface
    public interface Days {
        int get() throws SQLException;
    }

    /**
     * One entry of the history.
     *
     * @param state          {@code played} or {@code skipped}
     * @param addedBy        who added it (the name it had then), or {@code null} if unknown
     * @param via            {@code library}, {@code upload}, {@code youtube}, {@code playlist},
     *                       {@code history}, or {@code null} (an entry made before this was kept
     *                       that wasn't a request)
     * @param inYourLibrary  the item is in the viewer's library; {@code null} for guests and
     *                       anonymous viewers (they have no library)
     * @param recentlyPlayed the item started playing within the room's last {@code noRepeatWindow}
     *                       entries (the current one included), so only the owner and moderators
     *                       may add it now
     */
    public record Row(String entryId, String state, long playedAt, Library.ItemView item, ActorRef addedBy,
                      String via, Boolean inYourLibrary, boolean recentlyPlayed) {
    }

    /**
     * One page; {@code nextCursor} is {@code null} on the last page. {@code noRepeatWindow} is the
     * room's setting (0: off).
     */
    public record Page(List<Row> items, String nextCursor, int noRepeatWindow) {
    }

    private final Database db;
    private final Rooms rooms;
    private final Days days;
    private final Clock clock;

    public RoomHistory(Database db, Rooms rooms, Days days, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.rooms = Objects.requireNonNull(rooms);
        this.days = Objects.requireNonNull(days);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The oldest {@code played_at} still kept, or {@link Long#MIN_VALUE} when the history is never trimmed. */
    private long cutoff() throws SQLException {
        int d = days.get();
        return d <= 0 ? Long.MIN_VALUE : EpochMs.now(clock) - d * DAY_MS;
    }

    private static final String SELECT = "SELECT q.id, q.state, q.played_at, q.added_by_user_id,"
            + " q.added_by_guest_id, COALESCE(q.added_by_name, u.display_name, g.display_name), q.via,"
            + " i.id, i.kind, i.title, i.artist, i.album, i.duration_ms, i.source, i.youtube_id, i.status,"
            + " i.present_as_audio,"
            + " CASE WHEN EXISTS (SELECT 1 FROM library_entries le WHERE le.user_id = ? AND le.item_id = q.item_id)"
            + " THEN 1 ELSE 0 END"
            + " FROM queue_entries q JOIN items i ON i.id = q.item_id"
            + " LEFT JOIN users u ON u.id = q.added_by_user_id"
            + " LEFT JOIN guests g ON g.id = q.added_by_guest_id"
            + " WHERE q.room_id = ? AND q.state IN ('played', 'skipped') AND q.played_at IS NOT NULL"
            + " AND q.played_at >= ?";

    /**
     * The room's history, newest first (then by entry id, descending). Anyone who may open the
     * room ({@link Rooms#access}).
     *
     * @param cursor the previous page's {@code nextCursor}, or {@code null}; {@code 400
     *               invalid_cursor} if malformed
     * @throws HttpError {@code 404 room_not_found}, {@code 403 banned_from_room}, as {@link Rooms#access}
     */
    public Page list(Actor actor, String idOrSlug, String cursor, int limit) throws SQLException {
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
            if (sep <= 0 || !UuidV7.isValid(cursor.substring(sep + 1))) {
                throw new HttpError(400, "invalid_cursor", "Malformed cursor");
            }
            cursorId = cursor.substring(sep + 1);
        }
        Rooms.Access access = rooms.access(actor, idOrSlug);
        String roomId = access.room().id();
        int window = Math.max(0, access.room().settings().control().noRepeatWindow());
        String viewer = actor instanceof Actor.User u ? u.userId() : null;
        long cutoff = cutoff();
        StringBuilder sql = new StringBuilder(SELECT);
        if (cursorId != null) {
            sql.append(" AND (q.played_at < ? OR (q.played_at = ? AND q.id < ?))");
        }
        sql.append(" ORDER BY q.played_at DESC, q.id DESC LIMIT ").append(limit + 1);
        long at = cursorAt;
        String id = cursorId;
        return db.read(c -> {
            Set<String> recent = new HashSet<>(RoomStore.recentlyPlayedItems(c, roomId, window));
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                ps.setString(1, viewer == null ? "" : viewer);
                ps.setString(2, roomId);
                ps.setLong(3, cutoff);
                if (id != null) {
                    ps.setLong(4, at);
                    ps.setLong(5, at);
                    ps.setString(6, id);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(row(rs, viewer != null, recent));
                    }
                }
            }
            String next = null;
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
                Row last = rows.get(limit - 1);
                next = last.playedAt() + "_" + last.entryId();
            }
            return new Page(List.copyOf(rows), next, window);
        });
    }

    private static Row row(ResultSet rs, boolean user, Set<String> recent) throws SQLException {
        String userId = rs.getString(4);
        String guestId = rs.getString(5);
        String name = rs.getString(6);
        if (name == null || name.isEmpty()) {
            name = "?";
        }
        ActorRef by = userId != null ? ActorRef.user(userId, name)
                : guestId != null ? ActorRef.guest(guestId, name) : null;
        long duration = rs.getLong(13);
        Long durationMs = rs.wasNull() ? null : duration;
        String itemId = rs.getString(8);
        Library.ItemView item = new Library.ItemView(itemId, rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), durationMs, rs.getString(14), rs.getString(15), rs.getString(16),
                rs.getInt(17) != 0);
        return new Row(rs.getString(1), rs.getString(2), rs.getLong(3), item, by, rs.getString(7),
                user ? (Boolean) (rs.getInt(18) != 0) : null, recent.contains(itemId));
    }

    /**
     * The item of an entry of this room that started playing (played, skipped or playing now),
     * or {@code null} if there is no such entry. For {@code queue.add} with {@code historyEntryId}.
     */
    static String itemOf(Database db, String roomId, String entryId) throws SQLException {
        if (entryId == null || !UuidV7.isValid(entryId)) {
            return null;
        }
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT item_id FROM queue_entries"
                    + " WHERE id = ? AND room_id = ? AND played_at IS NOT NULL")) {
                ps.setString(1, entryId);
                ps.setString(2, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    /**
     * Deletes the history entries (played or skipped) older than {@code rooms.requestHistoryDays}
     * (none when it is 0), except a room's current entry. Their votes go with them.
     *
     * @return how many were deleted
     */
    public int trim() throws SQLException {
        long cutoff = cutoff();
        if (cutoff == Long.MIN_VALUE) {
            return 0;
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM queue_entries"
                    + " WHERE state IN ('played', 'skipped') AND played_at IS NOT NULL AND played_at < ?"
                    + " AND NOT EXISTS (SELECT 1 FROM rooms r WHERE r.current_entry_id = queue_entries.id)")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }
}
