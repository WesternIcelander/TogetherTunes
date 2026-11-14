package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.http.HttpError;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link UserSettings}: defaults, patches, stored values not understood, in both dialects. */
abstract class UserSettingsContract {
    static final long NOW = 1_800_000_000_000L;

    protected Database db;
    private UserSettings settings;
    private AuthFixtures fixtures;
    private final TestClock clock = new TestClock(NOW);

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws SQLException {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        settings = new UserSettings(db, clock);
        fixtures = new AuthFixtures(db, NOW);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private String stored(String userId) throws SQLException {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT settings_json, updated_at FROM user_settings WHERE user_id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) + "@" + rs.getLong(2) : null;
                }
            }
        });
    }

    private void store(String userId, String json) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO user_settings (user_id, settings_json, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, userId);
                ps.setString(2, json);
                ps.setLong(3, NOW);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Test
    void usersWithoutARowHaveTheDefaults() throws SQLException {
        String jane = fixtures.user("jane", false);
        assertEquals(new UserSettings.Prefs("play", "auto", false), settings.get(jane));
        assertEquals(UserSettings.Prefs.DEFAULTS, settings.patch(jane, new JsonObject()));
        assertEquals(null, stored(jane), "an empty patch stores nothing");
    }

    @Test
    void patchSetsOnlyTheGivenKeysAndStoresOnlyThose() throws SQLException {
        String jane = fixtures.user("jane", false);
        String joe = fixtures.user("joe", false);
        assertEquals(new UserSettings.Prefs("queue", "auto", false),
                settings.patch(jane, json("{\"doubleClickAlone\":\"queue\"}")));
        assertEquals(json("{\"doubleClickAlone\":\"queue\"}").toString() + "@" + NOW, stored(jane));
        clock.advance(5000);
        assertEquals(new UserSettings.Prefs("queue", "on", true),
                settings.patch(jane, json("{\"webAudio\":\"on\",\"debug\":true}")));
        assertEquals(new UserSettings.Prefs("queue", "on", true), settings.get(jane));
        assertEquals(json("{\"doubleClickAlone\":\"queue\",\"webAudio\":\"on\",\"debug\":true}"),
                json(stored(jane).split("@")[0]));
        assertEquals(NOW + 5000, Long.parseLong(stored(jane).split("@")[1]));
        // Setting a key back to its default keeps it stored (explicit), still reads as the default.
        assertEquals(new UserSettings.Prefs("play", "on", false),
                settings.patch(jane, json("{\"doubleClickAlone\":\"play\",\"debug\":false}")));
        assertEquals(UserSettings.Prefs.DEFAULTS, settings.get(joe), "other users unaffected");
    }

    @Test
    void invalidPatchesSaveNothing() throws SQLException {
        String jane = fixtures.user("jane", false);
        for (String bad : List.of("{\"doubleClickAlone\":\"skip\"}", "{\"webAudio\":\"ON\"}", "{\"debug\":1}",
                "{\"debug\":null}", "{\"doubleClickAlone\":[\"play\"]}", "{\"debug\":true,\"webAudio\":\"x\"}")) {
            HttpError e = assertThrows(HttpError.class, () -> settings.patch(jane, json(bad)), bad);
            assertEquals(400, e.status(), bad);
            assertEquals("invalid_setting", e.code(), bad);
        }
        HttpError e = assertThrows(HttpError.class, () -> settings.patch(jane, json("{\"debug\":true,\"volume\":3}")));
        assertEquals("unknown_setting", e.code());
        assertEquals(null, stored(jane));
        assertEquals(UserSettings.Prefs.DEFAULTS, settings.get(jane));
    }

    @Test
    void storedValuesNotUnderstoodReadAsDefaultsAndAreDropped() throws SQLException {
        String jane = fixtures.user("jane", false);
        store(jane, "{\"doubleClickAlone\":\"later\",\"webAudio\":\"off\",\"debug\":\"yes\",\"oldSetting\":1}");
        assertEquals(new UserSettings.Prefs("play", "off", false), settings.get(jane));
        settings.patch(jane, json("{\"debug\":true}"));
        assertEquals(json("{\"doubleClickAlone\":\"later\",\"webAudio\":\"off\",\"debug\":true}"),
                json(stored(jane).split("@")[0]), "unknown keys go on the next save");

        String joe = fixtures.user("joe", false);
        store(joe, "not json");
        assertEquals(UserSettings.Prefs.DEFAULTS, settings.get(joe));
        assertEquals(new UserSettings.Prefs("queue", "auto", false),
                settings.patch(joe, json("{\"doubleClickAlone\":\"queue\"}")));
    }

    @Test
    void concurrentPatchesOfDifferentKeysKeepBoth() throws Exception {
        for (int round = 0; round < 5; round++) {
            String user = fixtures.user("u" + round, false);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();
            for (String patch : List.of("{\"doubleClickAlone\":\"queue\"}", "{\"webAudio\":\"off\"}", "{\"debug\":true}")) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        settings.patch(user, json(patch));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(List.of(), errors);
            assertEquals(new UserSettings.Prefs("queue", "off", true), settings.get(user));
        }
    }

    @Test
    void deletingTheUserDeletesTheRow() throws SQLException {
        String jane = fixtures.user("jane", false);
        settings.patch(jane, json("{\"debug\":true}"));
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
                ps.setString(1, jane);
                ps.executeUpdate();
            }
            return null;
        });
        assertEquals(null, stored(jane));
    }
}
