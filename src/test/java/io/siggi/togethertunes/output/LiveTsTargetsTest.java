package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LiveTsApi#targetsFrom} (token → room and join point, only for enabled {@code .ts} outputs
 * behind the server switch) and {@link LiveHlsApi#targetsFrom} (the same for HLS).
 */
class LiveTsTargetsTest {
    static final String ROOM = "0190a000-0000-7000-8000-000000000001";
    static final String LATEST_ROOM = "0190a000-0000-7000-8000-000000000002";

    @TempDir
    Path dir;
    Database db;
    Settings settings;
    LiveTsApi.Targets targets;

    @BeforeEach
    void setUp() throws Exception {
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "http://localhost:8080"), 4, dir).load(null).config();
        db = Database.open("jdbc:sqlite:" + dir.resolve("test.db"));
        MigrationRunner.migrate(db);
        settings = new Settings(db, config);
        exec("INSERT INTO users (id, username, display_name, created_at) VALUES ('u1', 'owner', 'Owner', 1)");
        room(ROOM, "room-a", "{}");
        room(LATEST_ROOM, "room-b", "{\"outputs\":{\"ts\":{\"join\":\"latest\"}}}");
        output("o1", ROOM, "ts", "tokenNextJoin000000001", 1);
        output("o2", LATEST_ROOM, "ts", "tokenLatestJoin0000001", 1);
        output("o3", ROOM, "hls", "tokenHlsOutput00000001", 1);
        output("o4", LATEST_ROOM, "ts", "tokenDisabled000000001", 0);
        targets = LiveTsApi.targetsFrom(db, settings);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    void exec(String sql) throws Exception {
        db.write(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate(sql);
            }
            return null;
        });
    }

    void room(String id, String slug, String settingsJson) throws Exception {
        exec("INSERT INTO rooms (id, slug, name, owner_id, created_at, visibility, control_mode, settings_json,"
                + " playback_state) VALUES ('" + id + "', '" + slug + "', 'Room', 'u1', 1, 'public', 'owner', '"
                + settingsJson + "', 'idle')");
    }

    void output(String id, String roomId, String kind, String token, int enabled) throws Exception {
        exec("INSERT INTO stream_outputs (id, room_id, kind, token, enabled, created_at) VALUES ('" + id + "', '"
                + roomId + "', '" + kind + "', '" + token + "', " + enabled + ", 1)");
    }

    @Test
    void resolvesEnabledTsOutputsOnly() throws Exception {
        assertEquals(new LiveTsApi.Target(ROOM, false), targets.lookup("tokenNextJoin000000001"));
        assertEquals(new LiveTsApi.Target(LATEST_ROOM, true), targets.lookup("tokenLatestJoin0000001"));
        assertNull(targets.lookup("tokenHlsOutput00000001"), "an HLS token doesn't open .ts");
        assertNull(targets.lookup("tokenDisabled000000001"));
        assertNull(targets.lookup("tokenUnknown0000000001"));
    }

    @Test
    void serverSwitchOffHidesEveryOutput() throws Exception {
        settings.set(Settings.OUTPUTS_TS_ENABLED, false);
        assertNull(targets.lookup("tokenNextJoin000000001"));
    }

    @Test
    void hlsTargetsResolveEnabledHlsOutputsOnly() throws Exception {
        LiveHlsApi.Targets hls = LiveHlsApi.targetsFrom(db, settings);
        output("o5", LATEST_ROOM, "hls", "tokenHlsDisabled000001", 0);
        assertEquals(ROOM, hls.lookup("tokenHlsOutput00000001"));
        assertNull(hls.lookup("tokenNextJoin000000001"), "a .ts token doesn't open HLS");
        assertNull(hls.lookup("tokenHlsDisabled000001"));
        assertNull(hls.lookup("tokenUnknown0000000001"));
        settings.set(Settings.OUTPUTS_HLS_ENABLED, false);
        assertNull(hls.lookup("tokenHlsOutput00000001"));
        assertEquals(new LiveTsApi.Target(ROOM, false), targets.lookup("tokenNextJoin000000001"),
                "the HLS switch leaves .ts alone");
    }
}
