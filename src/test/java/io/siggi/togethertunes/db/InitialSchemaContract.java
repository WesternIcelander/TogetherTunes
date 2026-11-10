package io.siggi.togethertunes.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The application's migrations create every table of {@code docs/data-model.md}, with its
 * columns, keys and actor checks, in both dialects.
 */
abstract class InitialSchemaContract {
    protected Database db;

    /** Opens a fresh, empty database. */
    protected abstract Database open() throws SQLException;

    /** Every table and column from docs/data-model.md, in document order. */
    static final Map<String, List<String>> TABLES = new LinkedHashMap<>();

    static {
        t("users", "id", "username", "display_name", "email", "created_at", "disabled");
        t("password_credentials", "user_id", "hash", "updated_at", "must_change");
        t("user_identities", "id", "user_id", "provider_id", "subject", "email", "created_at", "last_login_at");
        t("oidc_providers", "id", "slug", "display_name", "issuer", "client_id", "client_secret_enc", "scopes",
                "enabled", "allow_signup", "claim_mapping_json");
        t("roles", "id", "name", "builtin", "is_default");
        t("role_permissions", "role_id", "permission");
        t("user_roles", "user_id", "role_id");
        t("sessions", "id", "user_id", "token_hash", "kind", "created_at", "last_seen_at", "expires_at",
                "user_agent", "ip");
        t("guests", "id", "kind", "key_hash", "ip_last", "display_name", "name_chosen", "created_at",
                "last_seen_at", "banned");
        t("settings", "key", "value_json");
        t("audit_log", "id", "at", "actor_user_id", "actor_guest_id", "ip", "action", "target", "details_json");
        t("items", "id", "kind", "title", "artist", "album", "duration_ms", "source", "youtube_id",
                "content_sha256", "added_by_user_id", "added_by_guest_id", "added_at", "status", "error",
                "output_profile", "loudness_lufs", "metadata_json", "original_file", "original_codec",
                "original_web", "original_gain_db", "last_referenced_at", "purged_at", "present_as_audio", "encode_preset",
                "encode_quality");
        t("ingest_jobs", "id", "item_id", "kind", "state", "attempts", "progress", "created_at", "started_at",
                "finished_at", "log", "tier");
        t("library_entries", "user_id", "item_id", "added_at", "via", "shared_by_user_id");
        t("playlists", "id", "owner_id", "name", "description", "created_at", "updated_at");
        t("playlist_entries", "id", "playlist_id", "item_id", "position", "added_at");
        t("shares", "id", "item_id", "from_user_id", "to_user_id", "message", "created_at", "seen");
        t("rooms", "id", "slug", "name", "owner_id", "created_at", "visibility", "control_mode", "settings_json",
                "playback_state", "current_entry_id", "anchor_server_ms", "anchor_position_ms", "version");
        t("room_members", "room_id", "user_id", "role", "joined_at");
        t("room_guest_bans", "room_id", "guest_id", "created_at");
        t("queue_entries", "id", "room_id", "item_id", "added_by_user_id", "added_by_guest_id", "added_at",
                "state", "position", "pinned", "played_at");
        t("votes", "entry_id", "voter_user_id", "voter_guest_id", "value", "at");
        t("skip_votes", "entry_id", "voter_user_id", "voter_guest_id", "at");
        t("room_requests", "id", "room_id", "item_id", "by_user_id", "by_guest_id", "by_name", "via", "at",
                "entry_id", "added_to_owner_library");
        t("stream_outputs", "id", "room_id", "kind", "token", "rtmp_url", "rtmp_key_enc", "enabled",
                "created_at", "rtmp_key");
        t("uploads", "id", "user_id", "guest_id", "room_id", "filename", "size", "created_at", "updated_at",
                "item_id", "deduplicated");
        t("guest_upload_starts", "id", "guest_id", "ip_key_hash", "created_at");
    }

    private static void t(String table, String... columns) {
        TABLES.put(table, List.of(columns));
    }

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void everyDocumentedTableExistsWithItsColumns() throws SQLException {
        Map<String, Set<String>> actual = db.read(c -> {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            try (ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    out.put(rs.getString("TABLE_NAME"), new LinkedHashSet<>());
                }
            }
            for (Map.Entry<String, Set<String>> e : out.entrySet()) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, e.getKey(), "%")) {
                    while (rs.next()) {
                        e.getValue().add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            return out;
        });
        actual.remove(MigrationRunner.TABLE);
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        TABLES.forEach((k, v) -> expected.put(k, new LinkedHashSet<>(v)));
        assertEquals(expected.keySet(), actual.keySet());
        for (String table : expected.keySet()) {
            assertEquals(expected.get(table), actual.get(table), table);
        }
    }

    @Test
    void actorPairsNeedExactlyOneSide() throws SQLException {
        seedUserGuestItemRoom();
        // Neither side set.
        assertThrows(SQLException.class, () -> exec("INSERT INTO queue_entries (id, room_id, item_id, added_at,"
                + " state, position) VALUES ('q0', 'r1', 'i1', 1, 'queued', 1)"));
        // Both sides set.
        assertThrows(SQLException.class, () -> exec("INSERT INTO queue_entries (id, room_id, item_id,"
                + " added_by_user_id, added_by_guest_id, added_at, state, position)"
                + " VALUES ('q0', 'r1', 'i1', 'u1', 'g1', 1, 'queued', 1)"));
        assertThrows(SQLException.class, () -> exec("INSERT INTO votes (entry_id, value, at) VALUES ('q1', 1, 1)"));
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO items (id, kind, title, source, added_at, status, output_profile, last_referenced_at)"
                        + " VALUES ('i9', 'audio', 't', 'upload', 1, 'pending', '720p30', 1)"));
        // One side: fine.
        exec("INSERT INTO votes (entry_id, voter_guest_id, value, at) VALUES ('q1', 'g1', 1, 1)");
        exec("INSERT INTO skip_votes (entry_id, voter_user_id, at) VALUES ('q1', 'u1', 1)");
        // One vote per voter and entry.
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO votes (entry_id, voter_guest_id, value, at) VALUES ('q1', 'g1', -1, 2)"));
    }

    @Test
    void foreignKeysAreEnforced() throws SQLException {
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO sessions (id, user_id, token_hash, kind, created_at, last_seen_at, expires_at)"
                        + " VALUES ('s1', 'nobody', 'h', 'cookie', 1, 1, 2)"));
    }

    @Test
    void youtubeIdIsUnique() throws SQLException {
        seedUserGuestItemRoom();
        assertThrows(SQLException.class, () -> exec("INSERT INTO items (id, kind, title, source, youtube_id,"
                + " added_by_user_id, added_at, status, output_profile, last_referenced_at)"
                + " VALUES ('i2', 'video', 't', 'youtube', 'yt1', 'u1', 1, 'pending', '720p30', 1)"));
    }

    @Test
    void deletingARoomRemovesItsRowsButKeepsItems() throws SQLException {
        seedUserGuestItemRoom();
        exec("UPDATE rooms SET current_entry_id = 'q1', playback_state = 'playing' WHERE id = 'r1'");
        exec("INSERT INTO votes (entry_id, voter_user_id, value, at) VALUES ('q1', 'u1', 1, 1)");
        exec("INSERT INTO room_requests (id, room_id, item_id, by_guest_id, by_name, via, at, entry_id)"
                + " VALUES ('rq1', 'r1', 'i1', 'g1', 'Calm Otter', 'youtube', 1, 'q1')");
        exec("INSERT INTO stream_outputs (id, room_id, kind, token, created_at) VALUES ('o1', 'r1', 'ts', 'tok', 1)");
        exec("INSERT INTO room_members (room_id, user_id, role, joined_at) VALUES ('r1', 'u1', 'owner', 1)");
        exec("INSERT INTO room_guest_bans (room_id, guest_id, created_at) VALUES ('r1', 'g1', 1)");

        exec("DELETE FROM rooms WHERE id = 'r1'");

        for (String table : List.of("rooms", "queue_entries", "votes", "room_requests", "stream_outputs",
                "room_members", "room_guest_bans")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(1, count("items"));
    }

    @Test
    void removingTheCurrentEntryClearsItAndRequestsKeepTheirHistory() throws SQLException {
        seedUserGuestItemRoom();
        exec("UPDATE rooms SET current_entry_id = 'q1' WHERE id = 'r1'");
        exec("INSERT INTO room_requests (id, room_id, item_id, by_guest_id, by_name, via, at, entry_id)"
                + " VALUES ('rq1', 'r1', 'i1', 'g1', 'Calm Otter', 'youtube', 1, 'q1')");
        exec("DELETE FROM queue_entries WHERE id = 'q1'");
        assertNull(string("SELECT current_entry_id FROM rooms WHERE id = 'r1'"));
        assertNull(string("SELECT entry_id FROM room_requests WHERE id = 'rq1'"));
        exec("DELETE FROM guests WHERE id = 'g1'");
        assertNull(string("SELECT by_guest_id FROM room_requests WHERE id = 'rq1'"));
        assertEquals("Calm Otter", string("SELECT by_name FROM room_requests WHERE id = 'rq1'"));
    }

    @Test
    void fractionalPositionsKeepDoublePrecision() throws SQLException {
        seedUserGuestItemRoom();
        double between = 1.0 + 1e-12;
        db.write((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE queue_entries SET position = ? WHERE id = 'q1'")) {
                ps.setDouble(1, between);
                ps.executeUpdate();
            }
            return null;
        });
        double read = db.read(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT position FROM queue_entries WHERE id = 'q1'")) {
                rs.next();
                return rs.getDouble(1);
            }
        });
        assertEquals(between, read);
    }

    /** User u1, guest g1, item i1 (YouTube 'yt1', added by u1), room r1 owned by u1 with entry q1. */
    private void seedUserGuestItemRoom() throws SQLException {
        exec("INSERT INTO users (id, username, display_name, created_at) VALUES ('u1', 'alice', 'Alice', 1)");
        exec("INSERT INTO guests (id, kind, key_hash, display_name, created_at, last_seen_at)"
                + " VALUES ('g1', 'cookie', 'kh', 'Calm Otter', 1, 1)");
        exec("INSERT INTO items (id, kind, title, source, youtube_id, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at)"
                + " VALUES ('i1', 'video', 'Song', 'youtube', 'yt1', 'u1', 1, 'ready', '720p30', 1)");
        exec("INSERT INTO rooms (id, slug, name, owner_id, created_at, visibility, control_mode, settings_json,"
                + " playback_state) VALUES ('r1', 'lounge', 'Lounge', 'u1', 1, 'public', 'owner', '{}', 'idle')");
        exec("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position)"
                + " VALUES ('q1', 'r1', 'i1', 'u1', 1, 'queued', 1)");
    }

    private String string(String sql) throws SQLException {
        return db.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    private long count(String table) throws SQLException {
        return db.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private void exec(String sql) throws SQLException {
        db.write((Connection c) -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }
}
