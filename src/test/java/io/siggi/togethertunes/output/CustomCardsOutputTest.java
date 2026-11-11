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
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.MigrationRunner;
import io.siggi.togethertunes.db.SqliteDialect;
import io.siggi.togethertunes.db.UuidV7;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.ToolResult;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.output.ProgramCue.Entry;
import io.siggi.togethertunes.overlay.CustomCards;
import io.siggi.togethertunes.overlay.OverlayCards;
import io.siggi.togethertunes.overlay.OverlayDecisions;
import io.siggi.togethertunes.overlay.OverlayRenderer;
import io.siggi.togethertunes.overlay.Overlays;
import io.siggi.togethertunes.rooms.Rooms;
import io.siggi.togethertunes.settings.Settings;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Custom card images in the program output ({@code docs/media-types.md#custom-card-images}), on a
 * SQLite database with the real chain ({@link CustomCards#ownerHolds}, {@link OverlayCards#lookupsFrom},
 * {@link OverlayCards#forRoom}, {@link ProgramMedia}) through the {@link OfflineHarness} into a
 * {@code .ts}. The server has a paused image (4:3, so it is drawn with black bars) and an idle
 * image; the room (its owner holds {@code rooms.customCards}) has its own idle image, which wins.
 * The room's overlay mode is {@code idleOnly}. Every output frame is decoded: the paused units show
 * the server's image with bars and no QR code, the queue-empty units the room's image with the QR
 * code, and the audio item its own card without the QR code.
 *
 * <p>Needs ffmpeg and ffprobe; skipped without them, except in CI (see {@link HarnessFixture#tools()}).
 */
class CustomCardsOutputTest {
    private static final String BASE = "https://tunes.example";
    private static final Color RED = new Color(0xE0, 0x20, 0x20);
    private static final Color GREEN = new Color(0x20, 0xC0, 0x20);
    private static final Color BLUE = new Color(0x20, 0x20, 0xE0);
    /** Decoded frames are scaled down to this for the colour checks. */
    private static final int TW = 32;
    private static final int TH = 18;

    @TempDir
    Path dir;

    @Test
    @Timeout(600)
    void theRoomsOutputShowsTheImagesWithTheQrCodeByMode() throws Exception {
        Tools tools = HarnessFixture.tools();
        HarnessFixture.Media m = HarnessFixture.media(tools, dir);
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
            accounts.createLocal("root", "Root", "root-password", false, null, "account.register");
            String aliceId = accounts.createLocal("alice", "Alice", "alice-password", false, null,
                    "account.register").id();
            Roles roles = new Roles(db);
            String cardsRole = roles.create("cards", List.of(Permission.ROOMS_CUSTOM_CARDS), false).id();
            List<String> ids = new ArrayList<>();
            for (Roles.Role r : roles.userRoles(aliceId)) {
                ids.add(r.id());
            }
            ids.add(cardsRole);
            roles.setUserRoles(aliceId, ids);
            Actor.User alice = new Actor.User(aliceId, "s-alice", Actor.Via.BEARER);
            String roomId = new Rooms(db, permissions, clock).create(alice, "Party", null, null, null,
                    "203.0.113.9").id();
            settings.set(Settings.OVERLAY_ENABLED, true);
            sql(db, "UPDATE rooms SET settings_json = ? WHERE id = ?", "{\"overlay\":{\"mode\":\"idleOnly\"}}", roomId);
            sql(db, "INSERT INTO items (id, kind, title, artist, source, added_by_user_id, added_at, status,"
                    + " output_profile, last_referenced_at, duration_ms) VALUES ('audio1', 'audio', 'Song', 'Artist',"
                    + " 'upload', ?, 1, 'ready', '720p30', 1, ?)", aliceId, HarnessFixture.AUDIO_MS);

            CustomCards custom = new CustomCards(data.customCards(), data.rooms(),
                    CustomCards.ownerHolds(db, permissions));
            Path up = Files.createDirectories(dir.resolve("up"));
            custom.put(null, CustomCards.Screen.PAUSED, png(up.resolve("paused.png"), 800, 600, RED));
            custom.put(null, CustomCards.Screen.IDLE, png(up.resolve("server-idle.png"), 1280, 720, GREEN));
            custom.put(roomId, CustomCards.Screen.IDLE, png(up.resolve("room-idle.png"), 1920, 1080, BLUE));
            OverlayCards.Lookups lookups = OverlayCards.lookupsFrom(db, data, settings,
                    new OverlayDecisions(db, settings, permissions), custom);

            String a0 = UuidV7.newId();
            sql(db, "INSERT INTO queue_entries (id, room_id, item_id, added_by_user_id, added_at, state, position,"
                    + " pinned) VALUES (?, ?, 'audio1', ?, 1, 'queued', 1, 0)", a0, roomId, aliceId);
            ScriptedTimeline script = new ScriptedTimeline(HarnessFixture.SCRIPT_START_MS);
            script.at(0).add(new Entry(a0, "audio1", false, false, HarnessFixture.AUDIO_MS))
                    .at(2_000).pause()
                    .at(6_000).play();
            long end = 4_000 + HarnessFixture.AUDIO_MS + 5_000;

            OutputEncoding enc = OutputEncoding.defaultFor(OutputProfile.P720_30);
            ReferenceCard reference = ReferenceCard.ensure(tools, data.cards(), enc, null);
            assertArrayEquals(reference.avcC(), m.avcC(), "the mezzanines match the reference (invariant 1)");
            Overlays overlays = new Overlays(data.rooms(), BASE, enc::profile);
            OverlayCards.RoomInfo room = lookups.room(roomId);
            OverlayRenderer.Rendered panel = overlays.current(roomId, room.slug(), room.settings());
            ProgramMedia.OverlayCards cards = new OverlayCards(data.rooms(), tools).forRoom(roomId, reference,
                    overlays, lookups);
            Path out = dir.resolve("custom-cards.ts");
            OfflineHarness.Result r;
            Path mediaDir = m.media();
            try (ProgramMedia media = new ProgramMedia(itemId -> mediaDir.resolve(itemId), enc, m.avcC(),
                    ProgramMedia.ffprobeCardLoader(tools, enc), m.fillers(), m.silence(), Runnable::run, 0,
                    script::startServerMs, cards);
                    OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                r = OfflineHarness.run(media, m.avcC(), 0, 0, script, end, os);
            }
            // Not assertDecodesClean: a pause gives sub-frame splices that plain `ffmpeg -f null`
            // complains about although the timestamps are valid (see OverlayModesTest).
            HarnessFixture.assertPackets(tools, out, r);

            List<Long> pts = NextVideoOverlayTest.videoPts(tools, out);
            boolean[] qr = NextVideoOverlayTest.qrFrames(tools, out, panel, OverlayRenderer.shortUrl(BASE, room.slug()));
            byte[] thumbs = thumbnails(tools, out);
            int size = TW * TH * 3;
            assertEquals(pts.size(), qr.length);
            assertEquals(pts.size() * size, thumbs.length, "every frame decodes");

            List<Long> unitStarts = new ArrayList<>();
            List<String> unitWhat = new ArrayList<>();
            for (String line : r.log()) {
                if (line.startsWith("unit ")) {
                    String[] f = line.split(" ");
                    unitStarts.add(Long.parseLong(f[3].substring(0, f[3].indexOf('.'))));
                    unitWhat.add(line.contains("filler idle") ? "idle" : line.contains("filler paused") ? "paused"
                            : line.contains("audio item") ? "audio" : "other");
                }
            }
            Map<String, Integer> seen = new LinkedHashMap<>();
            List<String> wrong = new ArrayList<>();
            int u = 0;
            for (int i = 0; i < pts.size(); i++) {
                while (u + 1 < unitStarts.size() && pts.get(i) >= unitStarts.get(u + 1)) {
                    u++;
                }
                String w = unitWhat.get(u);
                seen.merge(w, 1, Integer::sum);
                int off = i * size;
                int[] centre = pixel(thumbs, off, TW / 2, TH / 2);
                int[] edge = pixel(thumbs, off, 1, TH / 2);
                String problem = switch (w) {
                    case "paused" -> !near(centre, RED) ? "not the server's paused image"
                            : !near(edge, Color.BLACK) ? "no black bar beside the 4:3 image"
                            : qr[i] ? "a QR code on the paused card (idleOnly)" : null;
                    case "idle" -> near(centre, GREEN) ? "the server's idle image, not the room's"
                            : !near(centre, BLUE) || !near(edge, BLUE) ? "not the room's idle image"
                            : !qr[i] ? "no QR code on the queue-empty card (idleOnly)" : null;
                    case "audio" -> qr[i] ? "a QR code on the item (idleOnly)"
                            : near(centre, RED) || near(centre, BLUE) ? "a custom image on the item card" : null;
                    default -> null;
                };
                if (problem != null) {
                    wrong.add("frame " + i + " (" + w + "): " + problem);
                }
            }
            System.out.println("CustomCardsOutputTest: frames per source: " + seen);
            for (String w : List.of("paused", "idle", "audio")) {
                assertTrue(seen.containsKey(w), w + " played: " + seen);
            }
            assertTrue(wrong.isEmpty(), wrong.size() + " frames wrong, first: "
                    + wrong.subList(0, Math.min(10, wrong.size())));
        } finally {
            db.close();
        }
    }

    private static Path png(Path file, int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        assertTrue(ImageIO.write(img, "png", file.toFile()));
        return file;
    }

    /** Every video frame scaled to {@link #TW}x{@link #TH}, RGB. */
    private static byte[] thumbnails(Tools tools, Path ts) throws Exception {
        ToolResult res = tools.ffmpeg("-v", "error", "-i", ts.toString(), "-map", "0:v",
                "-vf", "scale=" + TW + ":" + TH + ":flags=area,format=rgb24", "-fps_mode", "passthrough",
                "-f", "rawvideo", "pipe:1").stdoutToString(1 << 30).timeout(Duration.ofMinutes(10)).run();
        return res.stdout();
    }

    private static int[] pixel(byte[] px, int off, int x, int y) {
        int i = off + (y * TW + x) * 3;
        return new int[] {px[i] & 0xFF, px[i + 1] & 0xFF, px[i + 2] & 0xFF};
    }

    private static boolean near(int[] rgb, Color c) {
        return Math.abs(rgb[0] - c.getRed()) <= 32 && Math.abs(rgb[1] - c.getGreen()) <= 32
                && Math.abs(rgb[2] - c.getBlue()) <= 32;
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
