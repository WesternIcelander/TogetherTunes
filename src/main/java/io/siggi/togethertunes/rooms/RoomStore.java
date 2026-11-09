package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads a {@link RoomState} from the {@code rooms} row and its queue entries, and writes its
 * version and timeline back.
 */
final class RoomStore {
    private static final System.Logger LOG = System.getLogger(RoomStore.class.getName());

    private final Database db;

    RoomStore(Database db) {
        this.db = Objects.requireNonNull(db);
    }

    Database db() {
        return db;
    }

    /** The room's state as persisted, or {@code null} if there is no such room. */
    RoomState load(String roomId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT slug, name, owner_id, visibility, control_mode,"
                    + " settings_json, playback_state, current_entry_id, anchor_server_ms, anchor_position_ms, version"
                    + " FROM rooms WHERE id = ?")) {
                ps.setString(1, roomId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    String stateName = rs.getString(7);
                    PlaybackState state = PlaybackState.parse(stateName);
                    String entryId = rs.getString(8);
                    long anchorServer = rs.getLong(9);
                    long anchorPosition = rs.getLong(10);
                    if (state == null) {
                        // Written by a newer version. Freeze it, as clients do with an unknown state.
                        LOG.log(System.Logger.Level.WARNING, "Room " + roomId + " has unknown playback_state '"
                                + stateName + "'; treating it as paused");
                        state = entryId == null ? PlaybackState.IDLE : PlaybackState.PAUSED;
                    }
                    String slug = rs.getString(1);
                    String name = rs.getString(2);
                    String ownerId = rs.getString(3);
                    Rooms.Visibility visibility = Objects.requireNonNull(Rooms.Visibility.parse(rs.getString(4)),
                            "visibility");
                    Rooms.ControlMode mode = Objects.requireNonNull(Rooms.ControlMode.parse(rs.getString(5)),
                            "control_mode");
                    String settings = rs.getString(6);
                    long version = rs.getLong(11);
                    List<QueueEntry> entries = entries(c, roomId, entryId);
                    QueueEntry current = null;
                    List<QueueEntry> queue = new ArrayList<>();
                    for (QueueEntry e : entries) {
                        if (e.id().equals(entryId)) {
                            current = e;
                        } else {
                            queue.add(e);
                        }
                    }
                    Timeline timeline = switch (state) {
                        case IDLE -> Timeline.IDLE;
                        case PLAYING, PAUSED -> current == null ? Timeline.IDLE
                                : new Timeline(state, entryId, anchorServer, anchorPosition);
                    };
                    if (timeline.entryId() == null) {
                        current = null;
                    }
                    return new RoomState(roomId, slug, name, ownerId, visibility, mode, settings, timeline, version,
                            current, queue);
                }
            }
        });
    }

    private static final String ENTRY_COLUMNS = "q.id, q.item_id, q.added_by_user_id, q.added_by_guest_id,"
            + " q.added_at, q.state, q.position, q.pinned, q.played_at, i.status, i.duration_ms";

    /**
     * The room's {@code queued} entries and the entry {@code currentId} (whatever its state; may
     * be {@code null}), with their items' status and duration. Entries with a state this version
     * doesn't know are left out (logged).
     */
    static List<QueueEntry> entries(Connection c, String roomId, String currentId) throws SQLException {
        List<QueueEntry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + ENTRY_COLUMNS
                + " FROM queue_entries q JOIN items i ON i.id = q.item_id"
                + " WHERE q.room_id = ? AND (q.state = 'queued' OR q.id = ?)"
                + " ORDER BY q.position, q.added_at, q.id")) {
            ps.setString(1, roomId);
            ps.setString(2, currentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    QueueEntry.State state = QueueEntry.State.parse(rs.getString(6));
                    if (state == null) {
                        LOG.log(System.Logger.Level.WARNING, "Queue entry " + rs.getString(1) + " in room " + roomId
                                + " has unknown state '" + rs.getString(6) + "'; ignoring it");
                        continue;
                    }
                    long playedAt = rs.getLong(9);
                    Long played = rs.wasNull() ? null : playedAt;
                    long duration = rs.getLong(11);
                    Long durationMs = rs.wasNull() ? null : duration;
                    out.add(new QueueEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), state, rs.getDouble(7), rs.getInt(8) != 0, played, rs.getString(10),
                            durationMs));
                }
            }
        }
        return out;
    }

    /**
     * The current status and duration of the items of {@code state}'s current and queued
     * entries ({@code entryId -> item}): ingest finishes on its own threads, so the executor reads
     * them again before choosing what to play.
     */
    Map<String, RoomState.ItemInfo> itemInfo(RoomState state) throws SQLException {
        String currentId = state.current() == null ? null : state.current().id();
        return db.read(c -> {
            Map<String, RoomState.ItemInfo> out = new HashMap<>();
            for (QueueEntry e : entries(c, state.id(), currentId)) {
                out.put(e.id(), new RoomState.ItemInfo(e.itemStatus(), e.durationMs()));
            }
            return out;
        });
    }

    /** Sets an entry's state (and {@code played_at}, if not {@code null}). */
    static void setEntryState(Connection c, String roomId, String entryId, QueueEntry.State state, Long playedAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET state = ?,"
                + " played_at = COALESCE(?, played_at) WHERE id = ? AND room_id = ?")) {
            ps.setString(1, state.wire());
            if (playedAt == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, playedAt);
            }
            ps.setString(3, entryId);
            ps.setString(4, roomId);
            ps.executeUpdate();
        }
    }

    /**
     * Writes the version and the timeline of {@code state} (with {@code version}) to its row.
     *
     * @return {@code false} if the row is gone (the room was deleted)
     */
    static boolean save(Connection c, RoomState state, long version) throws SQLException {
        Timeline t = state.timeline();
        try (PreparedStatement ps = c.prepareStatement("UPDATE rooms SET version = ?, playback_state = ?,"
                + " current_entry_id = ?, anchor_server_ms = ?, anchor_position_ms = ? WHERE id = ?")) {
            ps.setLong(1, version);
            ps.setString(2, t.state().wire());
            ps.setString(3, t.entryId());
            ps.setLong(4, t.anchorServerMs());
            ps.setLong(5, t.anchorPositionMs());
            ps.setString(6, state.id());
            return ps.executeUpdate() == 1;
        }
    }
}
