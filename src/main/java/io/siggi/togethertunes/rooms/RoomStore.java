package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Loads a {@link RoomState} from the {@code rooms} row and writes its version and timeline back. */
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
                    Timeline timeline = switch (state) {
                        case IDLE -> Timeline.IDLE;
                        case PLAYING, PAUSED -> entryId == null ? Timeline.IDLE
                                : new Timeline(state, entryId, anchorServer, anchorPosition);
                    };
                    return new RoomState(roomId, rs.getString(1), rs.getString(2), rs.getString(3),
                            Objects.requireNonNull(Rooms.Visibility.parse(rs.getString(4)), "visibility"),
                            Objects.requireNonNull(Rooms.ControlMode.parse(rs.getString(5)), "control_mode"),
                            rs.getString(6), timeline, rs.getLong(11));
                }
            }
        });
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
