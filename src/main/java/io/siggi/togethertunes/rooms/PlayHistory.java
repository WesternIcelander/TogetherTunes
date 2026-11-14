package io.siggi.togethertunes.rooms;

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
import java.util.List;
import java.util.Objects;

/**
 * A user's own play history ({@code play_history}, {@code GET /api/me/history};
 * {@code docs/ui.md#alone-and-in-rooms}: "Recents while alone shows the user's own play history;
 * songs that played in a room say which room").
 *
 * <p>A row is written whenever an entry starts playing ({@link #started}: the advance to the
 * next entry, and {@code previous}): in an alone room for its user; in any other room for every
 * user present in it then (a room socket in listen or requests-only mode), recording the room and
 * its name (kept when the room is deleted). Guests and anonymous visitors have none. Rows older
 * than {@code rooms.requestHistoryDays} are never listed, and {@link #trim} deletes them (the
 * room history's setting; 0 keeps them).
 */
public final class PlayHistory {
    public static final int LIST_DEFAULT = 50;
    public static final int LIST_MAX = 200;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * One row: the item, when it started, and where: {@code room} is {@code null} for the user's
     * alone room, else the room ({@code id} {@code null} once it was deleted; {@code name} as it was).
     */
    public record Row(String id, long playedAt, Library.ItemView item, RoomRef room) {
    }

    /** Where a song played: a room's id ({@code null}: deleted since) and its name then. */
    public record RoomRef(String id, String name) {
    }

    /** One page, newest first; {@code nextCursor} is {@code null} on the last page. */
    public record Page(List<Row> items, String nextCursor) {
    }

    private final Database db;
    private final RoomHistory.Days days;
    private final Clock clock;

    public PlayHistory(Database db, RoomHistory.Days days, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.days = Objects.requireNonNull(days);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Records that {@code entry} started playing in the mutation's room (see the class comment).
     * Called on the room's executor thread; the rows are written with the mutation.
     */
    static void started(RoomTx tx, QueueEntry entry) {
        RoomState s = tx.state();
        List<String> users = s.aloneUserId() != null ? List.of(s.aloneUserId()) : s.presence().userIds();
        if (users.isEmpty()) {
            return;
        }
        String roomId = s.aloneUserId() != null ? null : s.id();
        String roomName = roomId == null ? null : s.name();
        String itemId = entry.itemId();
        long now = tx.now();
        tx.persist(c -> {
            // only users that exist (a socket's user may have been deleted meanwhile)
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO play_history (id, user_id, item_id, room_id,"
                    + " room_name, played_at) SELECT ?, u.id, ?, ?, ?, ? FROM users u WHERE u.id = ?")) {
                for (String userId : users) {
                    ps.setString(1, UuidV7.newId());
                    ps.setString(2, itemId);
                    ps.setString(3, roomId);
                    ps.setString(4, roomName);
                    ps.setLong(5, now);
                    ps.setString(6, userId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private long cutoff() throws SQLException {
        int d = days.get();
        return d <= 0 ? Long.MIN_VALUE : EpochMs.now(clock) - d * DAY_MS;
    }

    /**
     * The user's history, newest first (then by row id, descending).
     *
     * @param cursor the previous page's {@code nextCursor} ({@code <playedAt>_<id>}), or
     *               {@code null}; {@code 400 invalid_cursor} if malformed
     */
    public Page list(String userId, String cursor, int limit) throws SQLException {
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
        StringBuilder sql = new StringBuilder("SELECT h.id, h.played_at, h.room_id, h.room_name, "
                + Library.ItemView.COLUMNS + " FROM play_history h JOIN items i ON i.id = h.item_id"
                + " WHERE h.user_id = ? AND h.played_at >= ?");
        if (cursorId != null) {
            sql.append(" AND (h.played_at < ? OR (h.played_at = ? AND h.id < ?))");
        }
        sql.append(" ORDER BY h.played_at DESC, h.id DESC LIMIT ").append(limit + 1);
        long cutoff = cutoff();
        long at = cursorAt;
        String afterId = cursorId;
        return db.read(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                ps.setString(1, userId);
                ps.setLong(2, cutoff);
                if (afterId != null) {
                    ps.setLong(3, at);
                    ps.setLong(4, at);
                    ps.setString(5, afterId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String roomName = rs.getString(4);
                        RoomRef room = roomName == null ? null : new RoomRef(rs.getString(3), roomName);
                        rows.add(new Row(rs.getString(1), rs.getLong(2), Library.ItemView.read(rs, 5), room));
                    }
                }
            }
            String next = null;
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
                Row last = rows.get(limit - 1);
                next = last.playedAt() + "_" + last.id();
            }
            return new Page(List.copyOf(rows), next);
        });
    }

    /**
     * Deletes the rows older than {@code rooms.requestHistoryDays} (none when it is 0).
     *
     * @return how many were deleted
     */
    public int trim() throws SQLException {
        long cutoff = cutoff();
        if (cutoff == Long.MIN_VALUE) {
            return 0;
        }
        return db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM play_history WHERE played_at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }
}
