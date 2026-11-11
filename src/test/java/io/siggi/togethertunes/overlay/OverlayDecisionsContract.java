package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.auth.Roles;
import io.siggi.togethertunes.config.Config;
import io.siggi.togethertunes.config.ConfigLoader;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.overlay.OverlayDecisions.Decision;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The overlay's permission gating on a real database ({@link OverlayDecisions},
 * {@code docs/overlay.md#permissions}): server switch AND room switch for cards, plus the owner's
 * {@code output.overlayVideo} for music videos, recorded once per queue entry.
 */
abstract class OverlayDecisionsContract {
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock(T0);
    private Database db;
    private Settings settings;
    private Roles roles;
    private OverlayDecisions decisions;
    private String aliceId;
    private String roomId;
    private String itemId;
    private String overlayRoleId;

    protected abstract Database open() throws SQLException;

    @BeforeEach
    void setUp() throws Exception {
        db = open();
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL",
                "https://tunes.example"), 4, dir).load(null).config();
        settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        roles = new Roles(db);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        // The first account becomes an admin; the room owner is the second one.
        accounts.createLocal("root", "Root", "root-password", false, null, "account.register");
        aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null, "account.register").id();
        Actor.User alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        roomId = new Rooms(db, permissions, clock).create(alice, "Party", null, null, null, "203.0.113.9").id();
        overlayRoleId = roles.create("overlay", Set.of(Permission.OUTPUT_OVERLAY_VIDEO), false).id();
        itemId = UuidV7.newId();
        sql("INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms) VALUES (?, 'video', 'Clip', 'upload', ?, 1, 'ready', '720p30',"
                + " 1, 60000)", itemId, aliceId);
        decisions = new OverlayDecisions(db, settings, permissions);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private String entry() throws SQLException {
        String id = UuidV7.newId();
        sql("INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position, pinned)"
                + " VALUES (?, ?, ?, ?, 1, 'queued', 1, 0)", id, roomId, itemId, aliceId);
        return id;
    }

    private void roomOverlay(boolean on) throws SQLException {
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", on ? "{\"overlay\":{\"mode\":\"always\"}}" : "{}", roomId);
    }

    private void serverOverlay(boolean on) throws SQLException {
        settings.set(Settings.OVERLAY_ENABLED, on);
    }

    private void ownerHoldsOverlayVideo(boolean holds) throws SQLException {
        List<String> ids = new ArrayList<>();
        for (Roles.Role r : roles.userRoles(aliceId)) {
            if (!r.id().equals(overlayRoleId)) {
                ids.add(r.id());
            }
        }
        if (holds) {
            ids.add(overlayRoleId);
        }
        roles.setUserRoles(aliceId, ids);
    }

    private void sql(String statement, Object... args) throws SQLException {
        db.write(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static RoomSettings mode(RoomSettings.OverlayMode m) {
        return RoomSettings.parse("{\"overlay\":{\"mode\":\"" + m.wire() + "\"}}");
    }

    private void itemType(String kind, String mediaType) throws SQLException {
        sql("UPDATE items SET kind = ?, media_type = ? WHERE id = ?", kind, mediaType, itemId);
    }

    @Test
    void decisionTable() throws Exception {
        RoomSettings on = mode(RoomSettings.OverlayMode.ALWAYS);
        RoomSettings off = RoomSettings.DEFAULTS;
        for (boolean server : new boolean[] {false, true}) {
            for (boolean holds : new boolean[] {false, true}) {
                serverOverlay(server);
                ownerHoldsOverlayVideo(holds);
                assertEquals(Decision.OFF, decisions.evaluate(aliceId, off, "music_video"), server + "/" + holds);
                Decision expected = !server ? Decision.OFF : holds ? Decision.VIDEO : Decision.CARDS;
                assertEquals(expected, decisions.evaluate(aliceId, on, "music_video"), server + "/" + holds);
            }
        }
        // A disabled owner holds nothing: cards only.
        serverOverlay(true);
        ownerHoldsOverlayVideo(true);
        sql("UPDATE users SET disabled = 1 WHERE id = ?", aliceId);
        assertEquals(Decision.CARDS, decisions.evaluate(aliceId, on, "music_video"));
    }

    /**
     * Media type × room mode (docs/media-types.md#qr-overlay-modes): only {@code always} gives an
     * entry the overlay, and never for a movie or show; {@code idleOnly} leaves entries without it
     * (only the queue-empty card has it, {@link OverlayCards#overlaidFillers}).
     */
    @Test
    void decisionsPerTypeAndMode() throws Exception {
        serverOverlay(true);
        ownerHoldsOverlayVideo(true);
        for (RoomSettings.OverlayMode m : RoomSettings.OverlayMode.values()) {
            for (String type : List.of("audio", "uncategorized", "music_video", "movie", "show")) {
                Decision expected;
                if (m != RoomSettings.OverlayMode.ALWAYS || type.equals("movie") || type.equals("show")) {
                    expected = Decision.OFF;
                } else {
                    expected = Decision.VIDEO; // whether it's played as video is up to the caller
                }
                assertEquals(expected, decisions.evaluate(aliceId, mode(m), type), m + "/" + type);

                // The same through decide(), from the database (a video item of that type).
                sql("UPDATE rooms SET settings_json = ? WHERE id = ?", mode(m).toStoredJson(), roomId);
                itemType("video", type);
                assertEquals(expected, decisions.decide(entry()), "decide " + m + "/" + type);
            }
        }
        assertTrue(OverlayDecisions.neverOverlaid("movie"));
        assertTrue(OverlayDecisions.neverOverlaid("show"));
        assertFalse(OverlayDecisions.neverOverlaid("music_video"));
        assertFalse(OverlayDecisions.neverOverlaid("uncategorized"));
        assertFalse(OverlayDecisions.neverOverlaid("audio"));
        // An audio item's effective type is audio, whatever is stored.
        sql("UPDATE rooms SET settings_json = ? WHERE id = ?", mode(RoomSettings.OverlayMode.ALWAYS).toStoredJson(),
                roomId);
        itemType("audio", "movie");
        assertEquals(Decision.VIDEO, decisions.decide(entry()));
    }

    /** A movie or show never has the overlay, also when its entry was decided before it was classified. */
    @Test
    void aMovieNeverHasItWhateverWasRecorded() throws Exception {
        serverOverlay(true);
        roomOverlay(true);
        itemType("video", "uncategorized");
        String e = entry();
        assertEquals(Decision.CARDS, decisions.decide(e));
        itemType("video", "movie");
        assertEquals(Decision.OFF, decisions.decide(e));
        assertEquals(Decision.CARDS, decisions.recorded(e), "the record stays");
        // Classified back: the recorded decision applies again.
        itemType("video", "music_video");
        assertEquals(Decision.CARDS, decisions.decide(e));
        // A new show entry records off.
        itemType("video", "show");
        String s = entry();
        assertEquals(Decision.OFF, decisions.decide(s));
        assertEquals(Decision.OFF, decisions.recorded(s));
    }

    @Test
    void adminsHoldItThroughTheirRole() throws Exception {
        serverOverlay(true);
        String adminRole = null;
        for (Roles.Role r : roles.list()) {
            if (r.isAdmin()) {
                adminRole = r.id();
            }
        }
        roles.setUserRoles(aliceId, List.of(adminRole));
        roomOverlay(true);
        assertEquals(Decision.VIDEO, decisions.decide(entry()));
    }

    @Test
    void decisionFlags() {
        assertFalse(Decision.OFF.cards());
        assertFalse(Decision.OFF.video());
        assertTrue(Decision.CARDS.cards());
        assertFalse(Decision.CARDS.video());
        assertTrue(Decision.VIDEO.cards());
        assertTrue(Decision.VIDEO.video());
        for (Decision d : Decision.values()) {
            assertEquals(d, Decision.parse(d.wire()));
        }
        assertNull(Decision.parse(null));
        assertNull(Decision.parse("sparkles"));
    }

    @Test
    void recordedOncePerEntryAndKeptWhenThingsChange() throws Exception {
        serverOverlay(true);
        roomOverlay(true);
        ownerHoldsOverlayVideo(true);
        String first = entry();
        String second = entry();
        assertNull(decisions.recorded(first));
        assertEquals(Decision.VIDEO, decisions.decide(first));
        assertEquals(Decision.VIDEO, decisions.recorded(first));

        // The owner loses the permission: the decided entry keeps its decision, the next gets cards only.
        ownerHoldsOverlayVideo(false);
        assertEquals(Decision.VIDEO, decisions.decide(first));
        assertEquals(Decision.CARDS, decisions.decide(second));

        // The room turns its overlay off (then the server): later entries get none, decided ones keep theirs.
        roomOverlay(false);
        String third = entry();
        assertEquals(Decision.OFF, decisions.decide(third));
        roomOverlay(true);
        assertEquals(Decision.OFF, decisions.decide(third));
        serverOverlay(false);
        assertEquals(Decision.OFF, decisions.decide(entry()));
        assertEquals(Decision.VIDEO, decisions.decide(first));
        assertEquals(Decision.CARDS, decisions.decide(second));

        // Recorded in the database: a new instance (a restart) reads it back.
        OverlayDecisions restarted = new OverlayDecisions(db, settings, new Permissions(db, settings));
        assertEquals(Decision.VIDEO, restarted.recorded(first));
        assertEquals(Decision.CARDS, restarted.decide(second));
    }

    @Test
    void unknownEntry() throws Exception {
        assertNull(decisions.decide(UuidV7.newId()));
        assertNull(decisions.recorded(UuidV7.newId()));
    }

    @Test
    void concurrentFirstDecisionsAgree() throws Exception {
        serverOverlay(true);
        roomOverlay(true);
        String e = entry();
        List<Future<Decision>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> decisions.decide(e)));
            }
        }
        for (Future<Decision> f : results) {
            assertEquals(Decision.CARDS, f.get());
        }
        assertEquals(Decision.CARDS, decisions.recorded(e));
    }
}
