package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
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
import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.overlay.OverlayCards;
import io.siggi.togethertunes.overlay.OverlayDecisions;
import io.siggi.togethertunes.overlay.OverlayPool;
import io.siggi.togethertunes.overlay.OverlayRenderer;
import io.siggi.togethertunes.overlay.OverlayVideoJobs;
import io.siggi.togethertunes.overlay.Overlays;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room's overlay modes in the program output ({@code docs/media-types.md#qr-overlay-modes}),
 * on a SQLite database with the real chain ({@link OverlayDecisions}, {@link OverlayCards#lookupsFrom},
 * {@link OverlayCards#forRoom} with {@link OverlayVideoJobs}, {@link ProgramMedia}) through the
 * {@link OfflineHarness} into a {@code .ts}; every output frame's panel region is searched for the
 * room's QR code:
 *
 * <ul>
 *   <li>{@code always} with a {@code show} item (the owner may re-encode videos): no frame of the
 *       show has the QR code, played as video (no re-encode started) or audio-only (its plain item
 *       card), while the audio item's card and the filler cards have it;</li>
 *   <li>{@code idleOnly}: only the queue-empty card has it, not the paused card, the items or a
 *       loading card.</li>
 * </ul>
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class OverlayModesTest {
    private static final String BASE = "https://tunes.example";

    @TempDir
    Path dir;

    /** What a test sets up: the room with its mode and the items' types. */
    private record Setup(Database db, DataDir data, Settings settings, String roomId, String ownerId,
            OverlayDecisions decisions, OverlayCards.Lookups lookups) {
    }

    private Setup setUp(String mode, String videoType) throws Exception {
        DataDir data = DataDir.create(dir.resolve("data"));
        Database db = Database.open("jdbc:sqlite:" + data.db().resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        MigrationRunner.migrate(db);
        BuiltinRoles.ensure(db);
        Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                .load(null).config();
        Settings settings = new Settings(db, config);
        Permissions permissions = new Permissions(db, settings);
        TestClock clock = new TestClock(1_800_000_000_000L);
        Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
        accounts.createLocal("root", "Root", "root-password", false, null, "account.register");
        String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null,
                "account.register").id();
        // The owner may re-encode videos with the overlay: what keeps a show's frames clean is its type.
        Roles roles = new Roles(db);
        String overlayRole = roles.create("overlay", List.of(Permission.OUTPUT_OVERLAY_VIDEO), false).id();
        List<String> ids = new ArrayList<>();
        for (Roles.Role r : roles.userRoles(aliceId)) {
            ids.add(r.id());
        }
        ids.add(overlayRole);
        roles.setUserRoles(aliceId, ids);
        Actor.User alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
        String roomId = new Rooms(db, permissions, clock).create(alice, "Party", null, null, null,
                "203.0.113.9").id();
        settings.set(Settings.OVERLAY_ENABLED, true);
        sql(db, "UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"overlay\":{\"mode\":\"" + mode + "\"}}", roomId);
        sql(db, "INSERT INTO items (id, kind, title, artist, source, added_by_user_id, added_at, status,"
                + " output_profile, last_referenced_at, duration_ms) VALUES ('audio1', 'audio', 'Song', 'Artist',"
                + " 'upload', ?, 1, 'ready', '720p30', 1, ?)", aliceId, HarnessFixture.AUDIO_MS);
        sql(db, "INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status, output_profile,"
                + " last_referenced_at, duration_ms, media_type, show_name, season_number, episode_number) VALUES"
                + " ('video1', 'video', 'Clip', 'upload', ?, 1, 'ready', '720p30', 1, ?, ?, ?, ?, ?)", aliceId,
                HarnessFixture.VIDEO_MS, videoType, videoType.equals("show") ? "Some Show" : null,
                videoType.equals("show") ? 1 : null, videoType.equals("show") ? 2 : null);
        OverlayDecisions decisions = new OverlayDecisions(db, settings, permissions);
        return new Setup(db, data, settings, roomId, aliceId, decisions,
                OverlayCards.lookupsFrom(db, data, settings, decisions));
    }

    /** A run's output, its log, and the room's overlay panel and QR URL. */
    private record Run(Path out, OfflineHarness.Result result, OverlayRenderer.Rendered panel, String url) {
    }

    /** Runs {@code script} for {@code sessionMs} through the room's overlay into a {@code .ts} (packets checked). */
    private Run run(Tools tools, HarnessFixture.Media m, Setup s, ScriptedTimeline script, long sessionMs, String name)
            throws Exception {
        OutputEncoding enc = OutputEncoding.defaultFor(OutputProfile.P720_30);
        OverlayCards.RoomInfo room = s.lookups().room(s.roomId());
        ReferenceCard reference = ReferenceCard.ensure(tools, s.data().cards(), enc, null);
        assertArrayEquals(reference.avcC(), m.avcC(), "the mezzanines match the reference (invariant 1)");
        Overlays overlays = new Overlays(s.data().rooms(), BASE, enc::profile);
        OverlayRenderer.Rendered panel = overlays.current(s.roomId(), room.slug(), room.settings());
        Path out = dir.resolve(name + ".ts");
        OfflineHarness.Result r;
        try (OverlayPool pool = new OverlayPool(1)) {
            pool.start();
            OverlayVideoJobs jobs = new OverlayVideoJobs(s.data().rooms(), pool, tools, p -> X264Preset.DEFAULT_QUICK,
                    run -> Thread.ofVirtual().start(run));
            ProgramMedia.OverlayCards cards = new OverlayCards(s.data().rooms(), tools).forRoom(s.roomId(), reference,
                    overlays, s.lookups(), jobs);
            Path mediaDir = m.media();
            try (ProgramMedia media = new ProgramMedia(itemId -> mediaDir.resolve(itemId), enc, m.avcC(),
                    ProgramMedia.ffprobeCardLoader(tools, enc), m.fillers(), m.silence(), Runnable::run, 0,
                    script::startServerMs, cards);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                r = OfflineHarness.run(media, m.avcC(), 0, 0, script, sessionMs, os);
            }
        }
        HarnessFixture.assertPackets(tools, out, r);
        return new Run(out, r, panel, OverlayRenderer.shortUrl(BASE, room.slug()));
    }

    /**
     * Checks every frame: {@code what} names the unit a frame belongs to (from its log line), and
     * {@code expected} says whether that unit shows the QR code. Every name in {@code mustPlay}
     * must have played.
     */
    private static void assertQr(Tools tools, Run run, Function<String, String> what, Predicate<String> expected,
            List<String> mustPlay, String test) throws Exception {
        List<Long> pts = NextVideoOverlayTest.videoPts(tools, run.out());
        boolean[] qr = NextVideoOverlayTest.qrFrames(tools, run.out(), run.panel(), run.url());
        assertEquals(run.result().videoFrames(), pts.size());
        assertEquals(pts.size(), qr.length, "every frame decodes");
        List<Long> unitStarts = new ArrayList<>();
        List<String> unitWhat = new ArrayList<>();
        for (String line : run.result().log()) {
            if (line.startsWith("unit ")) {
                String[] f = line.split(" ");
                unitStarts.add(Long.parseLong(f[3].substring(0, f[3].indexOf('.'))));
                unitWhat.add(what.apply(line));
            }
        }
        Map<String, int[]> withAndWithout = new LinkedHashMap<>();
        List<String> wrong = new ArrayList<>();
        int u = 0;
        for (int i = 0; i < pts.size(); i++) {
            while (u + 1 < unitStarts.size() && pts.get(i) >= unitStarts.get(u + 1)) {
                u++;
            }
            String w = unitWhat.get(u);
            withAndWithout.computeIfAbsent(w, k -> new int[2])[qr[i] ? 0 : 1]++;
            if (qr[i] != expected.test(w)) {
                wrong.add("frame " + i + " (" + w + ", pts " + pts.get(i) + "): "
                        + (qr[i] ? "QR code where none belongs" : "no QR code"));
            }
        }
        System.out.println(test + ": frames with/without the QR code per source: " + withAndWithout.entrySet()
                .stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1]).toList());
        for (String w : mustPlay) {
            assertTrue(withAndWithout.containsKey(w), w + " played: " + withAndWithout.keySet());
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " frames wrong, first: "
                + wrong.subList(0, Math.min(10, wrong.size())));
    }

    @Test
    @Timeout(600)
    void aShowNeverGetsTheOverlayUnderAlways() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        Setup s = setUp("always", "show");
        try {
            String v0 = entry(s, "video1", 1);
            String a0 = entry(s, "audio1", 2);
            String s0 = entry(s, "video1", 3);
            long audioOnlyAt = HarnessFixture.VIDEO_MS + HarnessFixture.AUDIO_MS + 2_000;
            ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
            script.at(0).add(
                    new Entry(v0, "video1", true, false, HarnessFixture.VIDEO_MS),
                    new Entry(a0, "audio1", false, false, HarnessFixture.AUDIO_MS))
                    // The show again, audio-only: its plain item card.
                    .at(audioOnlyAt).audioOnly(true)
                    .add(new Entry(s0, "video1", true, false, HarnessFixture.VIDEO_MS, true, false));
            Run run = run(tools, m, s, script, audioOnlyAt + HarnessFixture.VIDEO_MS + 3_000, "show-always");
            HarnessFixture.assertDecodesClean(tools, run.out());

            assertEquals(OverlayDecisions.Decision.OFF, s.decisions().recorded(v0), "the show's decision");
            assertEquals(OverlayDecisions.Decision.OFF, s.decisions().recorded(s0), "the show's decision");
            assertEquals(OverlayDecisions.Decision.VIDEO, s.decisions().recorded(a0), "the audio item's decision");
            Path ov = s.data().rooms().resolve(s.roomId()).resolve("ov");
            if (Files.exists(ov)) {
                try (Stream<Path> files = Files.walk(ov)) {
                    List<Path> made = files.filter(Files::isRegularFile).toList();
                    assertTrue(made.isEmpty(), "no re-encode of the show was started: " + made);
                }
            }
            assertQr(tools, run, line -> line.contains("video item as audio") ? "show-card"
                            : line.contains("video item video1") ? "show" : line.contains("audio item") ? "audio"
                            : line.contains("filler") ? "filler" : "other",
                    w -> w.equals("audio") || w.equals("filler"), List.of("show", "show-card", "audio", "filler"),
                    "OverlayModesTest (always, show)");
        } finally {
            s.db().close();
        }
    }

    @Test
    @Timeout(600)
    void idleOnlyPutsItOnTheQueueEmptyCardOnly() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        Setup s = setUp("idleOnly", "music_video");
        try {
            String a0 = entry(s, "audio1", 1);
            String v0 = entry(s, "video1", 2);
            ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
            script.at(0).add(
                    new Entry(a0, "audio1", false, false, HarnessFixture.AUDIO_MS),
                    new Entry(v0, "video1", true, false, HarnessFixture.VIDEO_MS))
                    .at(2_000).pause()
                    .at(6_000).play();
            long end = 4_000 + HarnessFixture.AUDIO_MS + HarnessFixture.VIDEO_MS + 5_000;
            // Not assertDecodesClean: a pause gives sub-frame splices that make plain `ffmpeg -f null`
            // complain although the timestamps are valid (PLAN.md, the CBR padding step); assertPackets
            // checks them, and the frames are decoded below with -fps_mode passthrough.
            Run run = run(tools, m, s, script, end, "idle-only");

            assertEquals(OverlayDecisions.Decision.OFF, s.decisions().recorded(a0));
            assertEquals(OverlayDecisions.Decision.OFF, s.decisions().recorded(v0));
            assertQr(tools, run, line -> line.contains("filler idle") ? "idle" : line.contains("filler paused")
                            ? "paused" : line.contains("filler") ? "filler" : line.contains("video item video1")
                            ? "video" : line.contains("audio item") ? "audio" : "other",
                    w -> w.equals("idle"), List.of("idle", "paused", "audio", "video"),
                    "OverlayModesTest (idleOnly)");
        } finally {
            s.db().close();
        }
    }

    private static String entry(Setup s, String itemId, int position) throws SQLException {
        String id = UuidV7.newId();
        sql(s.db(), "INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position,"
                + " pinned) VALUES (?, ?, ?, ?, 1, 'queued', ?, 0)", id, s.roomId(), itemId, s.ownerId(), position);
        return id;
    }

    private static void sql(Database db, String statement, Object... args) throws SQLException {
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
}
