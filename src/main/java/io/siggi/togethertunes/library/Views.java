package io.siggi.togethertunes.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Views of an item ({@code docs/media-types.md#views-auto-delete-and-pins}): a view is any
 * playback at all, however short: an entry of the item becoming current in a room
 * ({@link #queueEntry}, from {@code rooms.RoomStore#setEntryState}) or a client reporting playback
 * outside a room ({@code POST /api/items/{id}/view}, {@link MediaApi}). Each sets
 * {@code items.last_viewed_at}, which the auto-delete timers of movies and shows read. A
 * {@code GET} of a media file is never a view. The value never moves backwards.
 */
public final class Views {
    private Views() {
    }

    /** {@code last_viewed_at = now} for one item (never backwards). Joins the caller's transaction. */
    public static void record(Connection c, String itemId, long now) throws SQLException {
        Objects.requireNonNull(itemId);
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_viewed_at = ? WHERE id = ?"
                + " AND (last_viewed_at IS NULL OR last_viewed_at < ?)")) {
            ps.setLong(1, now);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    /** {@link #record} for the item of one queue entry (the entry just became current in its room). */
    public static void queueEntry(Connection c, String entryId, long now) throws SQLException {
        Objects.requireNonNull(entryId);
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_viewed_at = ? WHERE id IN"
                + " (SELECT item_id FROM queue_entries WHERE id = ?)"
                + " AND (last_viewed_at IS NULL OR last_viewed_at < ?)")) {
            ps.setLong(1, now);
            ps.setString(2, entryId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }
}
