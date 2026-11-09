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
