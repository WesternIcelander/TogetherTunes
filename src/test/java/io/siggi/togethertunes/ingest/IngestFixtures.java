package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Inserts a user and items straight into a migrated database, for ingest queue tests. */
final class IngestFixtures {
    private final Database db;
    private String userId;

    IngestFixtures(Database db) {
        this.db = db;
    }

    private String user() throws SQLException {
        if (userId == null) {
            String id = UuidV7.newId();
            db.write(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, ?, ?, ?, 0)")) {
                    ps.setString(1, id);
                    ps.setString(2, "uploader");
                    ps.setString(3, "uploader");
                    ps.setLong(4, 1_700_000_000_000L);
                    ps.executeUpdate();
                }
                return null;
            });
            userId = id;
        }
        return userId;
    }

    /** An item with the given status. */
    String item(String status) throws SQLException {
        String user = user();
        String id = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (id, kind, title, source,"
                    + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                    + " VALUES (?, 'audio', 'Song', 'upload', ?, ?, ?, '720p30', ?)")) {
                ps.setString(1, id);
                ps.setString(2, user);
                ps.setLong(3, 1_700_000_000_000L);
                ps.setString(4, status);
                ps.setLong(5, 1_700_000_000_000L);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /** Sets an item's kind and the preset columns of its files. */
    void encoded(String itemId, String kind, String preset, boolean quality) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE items SET kind = ?, encode_preset = ?, encode_quality = ? WHERE id = ?")) {
                ps.setString(1, kind);
                ps.setString(2, preset);
                ps.setInt(3, quality ? 1 : 0);
                ps.setString(4, itemId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Puts the item into the fixture user's library. */
    void addToLibrary(String itemId) throws SQLException {
        String user = user();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO library_entries (user_id, item_id, added_at, via)"
                    + " VALUES (?, ?, ?, 'upload')")) {
                ps.setString(1, user);
                ps.setString(2, itemId);
                ps.setLong(3, 1_700_000_000_000L);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Puts the item into a new room's queue with {@code state}; returns the entry id. */
    String queue(String itemId, String state) throws SQLException {
        String user = user();
        String room = UuidV7.newId();
        String entry = UuidV7.newId();
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO rooms (id, slug, name, owner_id, created_at,"
                    + " visibility, control_mode, settings_json, playback_state) VALUES (?, ?, 'Room', ?, 0, 'public',"
                    + " 'owner', '{}', 'stopped')")) {
                ps.setString(1, room);
                ps.setString(2, "r" + room.substring(room.length() - 12));
                ps.setString(3, user);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO queue_entries (id, room_id, item_id,"
                    + " added_by_user_id, added_at, state, position) VALUES (?, ?, ?, ?, 0, ?, 1)")) {
                ps.setString(1, entry);
                ps.setString(2, room);
                ps.setString(3, itemId);
                ps.setString(4, user);
                ps.setString(5, state);
                ps.executeUpdate();
            }
            return null;
        });
        return entry;
    }

    /** {encode_preset, encode_quality} of an item. */
    Object[] encode(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT encode_preset, encode_quality FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Object[] {rs.getString(1), rs.getInt(2) != 0};
                }
            }
        });
    }

    /** An item's {@code last_referenced_at}. */
    long lastReferencedAt(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT last_referenced_at FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** {status, error} of an item. */
    String[] itemStatus(String itemId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT status, error FROM items WHERE id = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null;
                }
            }
        });
    }
}
