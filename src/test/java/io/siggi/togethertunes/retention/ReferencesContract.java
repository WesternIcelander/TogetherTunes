package io.siggi.togethertunes.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link References}: {@code last_referenced_at} only moves forward, in both dialects. */
abstract class ReferencesContract {
    static final long T0 = 1_700_000_000_000L;

    protected Database db;
    private String userId;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        userId = UuidV7.newId();
        exec("INSERT INTO users (id, username, display_name, created_at, disabled) VALUES (?, 'u', 'u', ?, 0)",
                userId, T0);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void touchMovesForwardOnly() throws SQLException {
        String item = item(T0);
        String other = item(T0);
        db.write(c -> {
            References.touch(c, item, T0 + 5000);
            return null;
        });
        assertEquals(T0 + 5000, lastReferencedAt(item));
        assertEquals(T0, lastReferencedAt(other), "only the named item");

        db.write(c -> {
            References.touch(c, item, T0 + 1000);
            return null;
        });
        assertEquals(T0 + 5000, lastReferencedAt(item), "never backwards");
    }

    @Test
    void touchPlaylistTouchesEveryItemInIt() throws SQLException {
        String a = item(T0);
        String b = item(T0 + 9000);
        String outside = item(T0);
        String playlist = UuidV7.newId();
        exec("INSERT INTO playlists (id, owner_id, name, created_at, updated_at) VALUES (?, ?, 'p', ?, ?)",
                playlist, userId, T0, T0);
        int pos = 0;
        for (String item : new String[] {a, a, b}) {
            exec("INSERT INTO playlist_entries (id, playlist_id, item_id, position, added_at) VALUES (?, ?, ?, ?, ?)",
                    UuidV7.newId(), playlist, item, (double) ++pos, T0);
        }
        db.write(c -> {
            References.touchPlaylist(c, playlist, T0 + 5000);
            return null;
        });
        assertEquals(T0 + 5000, lastReferencedAt(a));
        assertEquals(T0 + 9000, lastReferencedAt(b), "never backwards");
        assertEquals(T0, lastReferencedAt(outside));
    }

    private String item(long lastReferencedAt) throws SQLException {
        String id = UuidV7.newId();
        exec("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at) VALUES (?, 'audio', 'Song', 'upload', ?, ?, 'ready', '720p30', ?)",
                id, userId, T0, lastReferencedAt);
        return id;
    }

    private long lastReferencedAt(String itemId) throws SQLException {
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

    private void exec(String sql, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }
}
