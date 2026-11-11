package io.siggi.togethertunes.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.TestClock;
import io.siggi.togethertunes.auth.Accounts;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.auth.BuiltinRoles;
import io.siggi.togethertunes.auth.Passwords;
import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Without {@code output.overlayVideo}, music videos play without the overlay while cards still
 * show it ({@code docs/overlay.md#permissions}). The real gating on a SQLite database: the server's
 * and the room's {@code overlay.enabled} on, the room owner holding only the default {@code user}
 * role, so {@link OverlayDecisions} records {@code cards} for every entry. The real chain
 * ({@link OverlayCards#lookupsFrom}, {@link OverlayCards#forRoom} with {@link OverlayVideoJobs} on
 * an {@link OverlayPool}, {@link ProgramMedia}) through the {@link OfflineHarness} into a
 * {@code .ts}; then every output frame's panel region is searched for the room's QR code: it must
 * show on every frame of the audio item's card and of the filler cards, and on no frame of the
 * music video, and no re-encode may be started.
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class NoVideoOverlayPermissionTest {
    private static final String BASE = "https://tunes.example";
    private static final long SESSION_MS = HarnessFixture.AUDIO_MS + HarnessFixture.VIDEO_MS + 4_000;

    @TempDir
    Path dir;

    @Test
    @Timeout(600)
    void musicVideosPlayWithoutOverlayWhileCardsShowIt() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
        OutputEncoding enc = OutputEncoding.defaultFor(OutputProfile.P720_30);
        DataDir data = DataDir.create(dir.resolve("data"));

        Database db = Database.open("jdbc:sqlite:" + data.db().resolve("test.db"), new Properties(),
                Database.PoolSettings.defaults(SqliteDialect.INSTANCE));
        try {
            MigrationRunner.migrate(db);
            BuiltinRoles.ensure(db);
            Config config = new ConfigLoader(Map.of("PATH", "", "TOGETHERTUNES_HTTP_PUBLIC_BASE_URL", BASE), 4, dir)
                    .load(null).config();
            Settings settings = new Settings(db, config);
            Permissions permissions = new Permissions(db, settings);
            TestClock clock = new TestClock(1_800_000_000_000L);
            Accounts accounts = new Accounts(db, new Passwords(new Passwords.Params(64, 1, 1)), clock);
            // the first account becomes an admin; the room owner is the second one (role "user")
            accounts.createLocal("root", "Root", "root-password", false, null, "account.register");
            String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null,
                    "account.register").id();
            Actor.User alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
            String roomId = new Rooms(db, permissions, clock).create(alice, "Party", null, null, null,
                    "203.0.113.9").id();
            settings.set(Settings.OVERLAY_ENABLED, true);
            sql(db, "UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"overlay\":{\"enabled\":true}}", roomId);
            assertFalse(permissions.held(aliceId).contains(Permission.OUTPUT_OVERLAY_VIDEO),
                    "the owner doesn't hold output.overlayVideo");
            // the harness media's item ids, as library items
            sql(db, "INSERT INTO items (id, kind, title, artist, source, added_by_user_id, added_at, status,"
                    + " output_profile, last_referenced_at, duration_ms) VALUES ('audio1', 'audio', 'Song', 'Artist',"
                    + " 'upload', ?, 1, 'ready', '720p30', 1, ?)", aliceId, HarnessFixture.AUDIO_MS);
            sql(db, "INSERT INTO items (id, kind, title, source, added_by_user_id, added_at, status,"
                    + " output_profile, last_referenced_at, duration_ms) VALUES ('video1', 'video', 'Clip', 'upload',"
                    + " ?, 1, 'ready', '720p30', 1, ?)", aliceId, HarnessFixture.VIDEO_MS);
            String a0 = entry(db, roomId, "audio1", aliceId, 1);
            String v0 = entry(db, roomId, "video1", aliceId, 2);

            OverlayDecisions decisions = new OverlayDecisions(db, settings, permissions);
            OverlayCards.Lookups lookups = OverlayCards.lookupsFrom(db, data, settings, decisions);
            OverlayCards.RoomInfo room = lookups.room(roomId);
            ReferenceCard reference = ReferenceCard.ensure(tools, data.cards(), enc, null);
            assertArrayEquals(reference.avcC(), m.avcC(), "the mezzanines match the reference (invariant 1)");
            Overlays overlays = new Overlays(data.rooms(), BASE, enc::profile);
            OverlayRenderer.Rendered panel = overlays.current(roomId, room.slug(), room.settings());
            String url = OverlayRenderer.shortUrl(BASE, room.slug());

            Path out = dir.resolve("no-video-overlay.ts");
            OfflineHarness.Result r;
            try (OverlayPool pool = new OverlayPool(1)) {
                pool.start();
                OverlayVideoJobs jobs = new OverlayVideoJobs(data.rooms(), pool, tools, p -> X264Preset.DEFAULT_QUICK,
                        run -> Thread.ofVirtual().start(run));
                ProgramMedia.OverlayCards cards = new OverlayCards(data.rooms(), tools).forRoom(roomId, reference,
                        overlays, lookups, jobs);
                ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
                script.at(0).add(
                        new Entry(a0, "audio1", false, false, HarnessFixture.AUDIO_MS),
                        new Entry(v0, "video1", true, false, HarnessFixture.VIDEO_MS));
                Path mediaDir = m.media();
                try (ProgramMedia media = new ProgramMedia(itemId -> mediaDir.resolve(itemId), enc, m.avcC(),
                        ProgramMedia.ffprobeCardLoader(tools, enc), m.fillers(), m.silence(), Runnable::run, 0,
                        script::startServerMs, cards);
                        OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                    r = OfflineHarness.run(media, m.avcC(), 0, script, SESSION_MS, os);
                }
            }

            assertEquals(OverlayDecisions.Decision.CARDS, decisions.recorded(a0), "a0's recorded decision");
            assertEquals(OverlayDecisions.Decision.CARDS, decisions.recorded(v0), "v0's recorded decision");
            Path ov = data.rooms().resolve(roomId).resolve("ov");
            if (Files.exists(ov)) {
                try (Stream<Path> s = Files.walk(ov)) {
                    List<Path> files = s.filter(Files::isRegularFile).toList();
                    assertTrue(files.isEmpty(), "no music-video re-encode was started: " + files);
                }
            }
            HarnessFixture.assertDecodesClean(tools, out);
            HarnessFixture.assertPackets(tools, out, r);

            List<Long> pts = NextVideoOverlayTest.videoPts(tools, out);
            boolean[] qr = NextVideoOverlayTest.qrFrames(tools, out, panel, url);
            assertEquals(r.videoFrames(), pts.size());
            assertEquals(pts.size(), qr.length, "every frame decodes");
            List<Long> unitStarts = new ArrayList<>();
            List<String> unitWhat = new ArrayList<>();
            for (String line : r.log()) {
                if (line.startsWith("unit ")) {
                    String[] f = line.split(" ");
                    unitStarts.add(Long.parseLong(f[3].substring(0, f[3].indexOf('.'))));
                    unitWhat.add(line.contains("video item video1") ? "v0" : line.contains("audio item") ? "a0"
                            : "filler");
                }
            }
            Map<String, int[]> withAndWithout = new LinkedHashMap<>();
            List<String> wrong = new ArrayList<>();
            int u = 0;
            for (int i = 0; i < pts.size(); i++) {
                while (u + 1 < unitStarts.size() && pts.get(i) >= unitStarts.get(u + 1)) {
                    u++;
                }
                String what = unitWhat.get(u);
                boolean expected = !what.equals("v0");
                withAndWithout.computeIfAbsent(what, k -> new int[2])[qr[i] ? 0 : 1]++;
                if (qr[i] != expected) {
                    wrong.add("frame " + i + " (" + what + ", pts " + pts.get(i) + "): "
                            + (qr[i] ? "QR code on the music video" : "no QR code on a card"));
                }
            }
            System.out.println("NoVideoOverlayPermissionTest: frames with/without the QR code per source: "
                    + withAndWithout.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/"
                    + e.getValue()[1]).toList());
            for (String what : List.of("a0", "v0", "filler")) {
                assertTrue(withAndWithout.containsKey(what), what + " played: " + withAndWithout.keySet());
            }
            assertTrue(wrong.isEmpty(), wrong.size() + " frames wrong, first: "
                    + wrong.subList(0, Math.min(10, wrong.size())));
        } finally {
            db.close();
        }
    }

    private static String entry(Database db, String roomId, String itemId, String userId, int position)
            throws SQLException {
        String id = UuidV7.newId();
        sql(db, "INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position, pinned)"
                + " VALUES (?, ?, ?, ?, 1, 'queued', ?, 0)", id, roomId, itemId, userId, position);
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
