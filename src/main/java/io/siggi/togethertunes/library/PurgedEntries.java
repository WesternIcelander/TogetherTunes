package io.siggi.togethertunes.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Removing the library and playlist entries of items whose media is gone
 * ({@code docs/media-types.md#after-media-is-deleted}): {@code retention.purgedEntryDays} after a
 * purge (the retention sweeper), right at the purge when it is {@code 0} ({@link Items#purge}),
 * and with the whole record ({@link Items#deleteRecord}). A playlist that loses entries gets
 * {@code updated_at = now}. Room history (finished queue entries) is not touched here.
 *
 * <p>{@code last_referenced_at} isn't touched: retention only purges {@code ready} items, and a
 * purged item that is refetched starts referenced again.
 */
public final class PurgedEntries {
    private PurgedEntries() {
    }

    /** The library and playlist entries of one item; returns how many were removed. */
    public static int removeForItem(Connection c, String itemId, long now) throws SQLException {
        Objects.requireNonNull(itemId);
        return remove(c, "?", new Object[] {itemId}, now);
    }

    /**
     * The library and playlist entries of every {@code purged} item purged at or before
     * {@code cutoff}; returns how many were removed.
     */
    public static int removePurgedBefore(Connection c, long cutoff, long now) throws SQLException {
        return remove(c, "SELECT i.id FROM items i WHERE i.status = 'purged' AND i.purged_at <= ?",
                new Object[] {cutoff}, now);
    }

    /** {@code items}: SQL for the item ids (a single {@code ?} or a subquery), with its parameters. */
    private static int remove(Connection c, String items, Object[] params, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE playlists SET updated_at = ? WHERE id IN"
                + " (SELECT pe.playlist_id FROM playlist_entries pe WHERE pe.item_id IN (" + items + "))")) {
            ps.setLong(1, now);
            bind(ps, 2, params);
            ps.executeUpdate();
        }
        int removed;
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM playlist_entries WHERE item_id IN (" + items + ")")) {
            bind(ps, 1, params);
            removed = ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM library_entries WHERE item_id IN (" + items + ")")) {
            bind(ps, 1, params);
            removed += ps.executeUpdate();
        }
        return removed;
    }

    private static void bind(PreparedStatement ps, int first, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(first + i, params[i]);
        }
    }
}
