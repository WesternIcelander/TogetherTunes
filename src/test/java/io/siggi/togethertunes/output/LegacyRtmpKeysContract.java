package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.Migration;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.secrets.SecretCipher;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Migration 0007 and {@link LegacyRtmpKeys}: a database at version 6 with encrypted stream keys is
 * upgraded, the keys end up in plain text in {@code rtmp_key}, {@code rtmp_key_enc} is cleared,
 * and a key that can't be decrypted is dropped with a warning that doesn't print it.
 */
abstract class LegacyRtmpKeysContract {
    static final String KEY = "live_123456789_abcdWXYZ";
    static final String OTHER_KEY = "live_987654321_secretQQ";

    Database db;

    /** A fresh, empty database. */
    protected abstract Database open() throws SQLException;

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private void exec(String sql) throws SQLException {
        db.write(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    private String column(String sql) throws SQLException {
        return db.read(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test
    void encryptedKeysBecomePlainTextAfterTheUpgrade() throws Exception {
        db = open();
        List<Migration> all = MigrationRunner.loadClasspath(getClass().getClassLoader(), MigrationRunner.CLASSPATH_ROOT,
                db.dialect());
        new MigrationRunner(db, all.stream().filter(m -> m.version() <= 6).toList()).migrate();

        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        SecretCipher cipher = new SecretCipher(secret);
        byte[] otherSecret = new byte[32];
        new SecureRandom().nextBytes(otherSecret);
        exec("INSERT INTO users (id, username, display_name, created_at) VALUES ('u1', 'alice', 'Alice', 1)");
        for (String r : List.of("r1", "r2", "r3")) {
            exec("INSERT INTO rooms (id, slug, name, owner_id, created_at, visibility, control_mode, settings_json,"
                    + " playback_state) VALUES ('" + r + "', '" + r + "', 'Room', 'u1', 1, 'public', 'owner', '{}',"
                    + " 'idle')");
        }
        String good = cipher.encrypt(KEY, LegacyRtmpKeys.context("o1"));
        String bad = new SecretCipher(otherSecret).encrypt(OTHER_KEY, LegacyRtmpKeys.context("o2"));
        exec("INSERT INTO stream_outputs (id, room_id, kind, rtmp_url, rtmp_key_enc, enabled, created_at)"
                + " VALUES ('o1', 'r1', 'rtmp', 'rtmp://ingest.example/live', '" + good + "', 1, 1)");
        exec("INSERT INTO stream_outputs (id, room_id, kind, rtmp_url, rtmp_key_enc, enabled, created_at)"
                + " VALUES ('o2', 'r2', 'rtmp', 'rtmp://ingest.example/live', '" + bad + "', 1, 1)");
        exec("INSERT INTO stream_outputs (id, room_id, kind, token, enabled, created_at)"
                + " VALUES ('o3', 'r3', 'ts', 'tok', 1, 1)");

        assertFalse(MigrationRunner.migrate(db).isEmpty(), "0007 should run");
        ByteArrayOutputStream warnings = new ByteArrayOutputStream();
        LegacyRtmpKeys.Result result = LegacyRtmpKeys.convert(db, cipher,
                new PrintStream(warnings, true, StandardCharsets.UTF_8));
        assertEquals(new LegacyRtmpKeys.Result(1, 1), result);

        assertEquals(KEY, column("SELECT rtmp_key FROM stream_outputs WHERE id = 'o1'"));
        assertNull(column("SELECT rtmp_key_enc FROM stream_outputs WHERE id = 'o1'"));
        assertNull(column("SELECT rtmp_key FROM stream_outputs WHERE id = 'o2'"));
        assertNull(column("SELECT rtmp_key_enc FROM stream_outputs WHERE id = 'o2'"));
        assertNull(column("SELECT rtmp_key FROM stream_outputs WHERE id = 'o3'"));
        String w = warnings.toString(StandardCharsets.UTF_8);
        assertTrue(w.contains("o2"), w);
        assertFalse(w.contains(OTHER_KEY) || w.contains(bad) || w.contains(KEY), w);

        // the next start converts nothing
        assertEquals(new LegacyRtmpKeys.Result(0, 0), LegacyRtmpKeys.convert(db, cipher, new PrintStream(warnings)));
        // and the rows read back with the plain key
        StreamOutputs outputs = new StreamOutputs(db, new SecureRandom(), java.time.Clock.systemUTC());
        StreamOutputs.Output o1 = outputs.get("r1", "o1");
        assertEquals(KEY, o1.rtmpKey());
        assertFalse(o1.toString().contains(KEY), o1.toString());
        assertFalse(outputs.get("r2", "o2").hasRtmpKey());
    }
}
