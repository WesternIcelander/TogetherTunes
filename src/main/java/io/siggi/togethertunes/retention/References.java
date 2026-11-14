package io.siggi.togethertunes.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Maintains {@code items.last_referenced_at}, the clock retention counts from
 * ({@code docs/libraries.md#retention}). An item is referenced while it is in a library, in a
 * playlist, queued/suggested/playing in a room, or has a running ingest job; the sweeper purges
 * one only when it is unreferenced and {@code last_referenced_at} is older than
 * {@code retention.unreferencedDays}.
 *
 * <p>Every place that creates an item writes {@code last_referenced_at = now} in its insert.
 * Every event that makes an item referenced, or <b>stops</b> it being referenced, calls one of
 * these methods inside the transaction that makes the change:
 * <ul>
 *   <li>library entry added or removed ({@code Library}, {@code Uploads},
 *       {@code YoutubeImports});</li>
 *   <li>playlist entry added or removed, playlist deleted ({@code Playlists});</li>
 *   <li>ingest job finished or failed ({@code IngestJobs});</li>
 *   <li>queue entry added, or changing state ({@code RoomStore.setEntryState}: played, skipped,
 *       removed, a rejected suggestion, back to queued or playing), room deleted ({@code Rooms}).</li>
 * </ul>
 * The value never moves backwards, so a stale clock or a reordered event can't shorten an
 * item's retention.
 */
public final class References {
    private References() {
    }

    /** {@code last_referenced_at = now} for one item (never backwards). */
    public static void touch(Connection c, String itemId, long now) throws SQLException {
        Objects.requireNonNull(itemId);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE items SET last_referenced_at = ? WHERE id = ? AND last_referenced_at < ?")) {
            ps.setLong(1, now);
            ps.setString(2, itemId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    /**
     * {@code last_referenced_at = now} (never backwards) for every item in a playlist. Call it
     * before the entries are deleted.
     */
    public static void touchPlaylist(Connection c, String playlistId, long now) throws SQLException {
        Objects.requireNonNull(playlistId);
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_referenced_at = ? WHERE id IN"
                + " (SELECT item_id FROM playlist_entries WHERE playlist_id = ?) AND last_referenced_at < ?")) {
            ps.setLong(1, now);
            ps.setString(2, playlistId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    /**
     * {@code last_referenced_at = now} (never backwards) for the item of one queue entry. Called
     * on every state change of the entry: a change between the referenced states
     * ({@code queued}, {@code suggested}, {@code playing}) and the finished ones ({@code played},
     * {@code skipped}, {@code removed}), either way, is a reference event; one within either
     * group only extends retention, which is harmless.
     */
    public static void touchQueueEntry(Connection c, String entryId, long now) throws SQLException {
        Objects.requireNonNull(entryId);
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_referenced_at = ? WHERE id IN"
                + " (SELECT item_id FROM queue_entries WHERE id = ?) AND last_referenced_at < ?")) {
            ps.setLong(1, now);
            ps.setString(2, entryId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    /**
     * {@code last_referenced_at = now} (never backwards) for every item queued, suggested or
     * playing in a room. Call it before the room (and with it its queue) is deleted.
     */
    public static void touchRoomQueue(Connection c, String roomId, long now) throws SQLException {
        Objects.requireNonNull(roomId);
        try (PreparedStatement ps = c.prepareStatement("UPDATE items SET last_referenced_at = ? WHERE id IN"
                + " (SELECT item_id FROM queue_entries WHERE room_id = ? AND state IN ('queued', 'suggested',"
                + " 'playing')) AND last_referenced_at < ?")) {
            ps.setLong(1, now);
            ps.setString(2, roomId);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }
}
