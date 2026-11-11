package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.output.CardGop;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue;
import io.siggi.togethertunes.output.ProgramMedia;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Overlaid filler and item cards ({@code docs/overlay.md#cards-cheap-path}); real encodes, needs ffmpeg. */
class OverlayCardsTest {
    private static final String ROOM = "01a1c2fe-121a-74ee-ae2c-143f98971e63";
    private static final String BASE = "https://a.example.com";

    @TempDir
    Path data;

    static Tools tools() {
        String ffmpeg = onPath("ffmpeg");
        String ffprobe = onPath("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null, "ffmpeg/ffprobe not on PATH");
        return new Tools(ffmpeg, ffprobe, null);
    }

    private static String onPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String candidate : List.of(entry, "/opt/homebrew/bin", "/usr/local/bin")) {
                Path p = Path.of(candidate, name);
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    /** Lookups whose answers the test sets. */
    static final class FakeLookups implements OverlayCards.Lookups {
        volatile boolean server = true;
        volatile OverlayCards.RoomInfo room = new OverlayCards.RoomInfo("chill",
                OverlayRendererTest.settings("br", 22, 3));
        volatile OverlayDecisions.Decision decision = OverlayDecisions.Decision.CARDS;
        volatile OverlayCards.ItemInfo item = new OverlayCards.ItemInfo(1,
                new CardRenderer.ItemCard("Song", "Artist", "Album", null));
        volatile Map<Filler, CustomCards.Image> custom = Map.of();

        @Override
        public Map<Filler, CustomCards.Image> customCards(String roomId) {
            return custom;
        }

        @Override
        public boolean serverEnabled() {
            return server;
        }

        @Override
        public OverlayCards.RoomInfo room(String roomId) {
            return room;
        }

        @Override
        public OverlayDecisions.Decision decide(String entryId) {
            return decision;
        }

        @Override
        public OverlayCards.ItemInfo item(String itemId) {
            return item;
        }
    }

    static final ProgramCue.Entry ENTRY = new ProgramCue.Entry("e1", "item1", false, false, 5000);

    /** The card's first frame, decoded. */
    private static BufferedImage firstFrame(Tools tools, Path card, Path dir) throws Exception {
        Path png = Files.createTempFile(dir, "frame", ".png");
        tools.ffmpeg(List.of("-v", "error", "-y", "-i", card.toString(), "-frames:v", "1", png.toString())).run();
        return ImageIO.read(png.toFile());
    }

    /** Decodes the QR code inside the overlay panel's region of a frame. */
    private static String qr(BufferedImage frame, OverlayRenderer.Rendered overlay) throws Exception {
        return OverlayRendererTest.decode(frame.getSubimage(overlay.x(), overlay.y(), overlay.width(),
                overlay.height())).getText();
    }

    @Test
    void fillersOverlaidPerRoomMode() {
        assertEquals(java.util.Set.of(), OverlayCards.overlaidFillers(RoomSettings.OverlayMode.OFF));
        assertEquals(java.util.EnumSet.allOf(io.siggi.togethertunes.output.FillerSource.Filler.class),
                OverlayCards.overlaidFillers(RoomSettings.OverlayMode.ALWAYS));
        assertEquals(java.util.Set.of(io.siggi.togethertunes.output.FillerSource.Filler.IDLE),
                OverlayCards.overlaidFillers(RoomSettings.OverlayMode.IDLE_ONLY));
    }

    @Test
    void compositeDrawsThePanelAtItsPosition() throws Exception {
        Overlays overlays = new Overlays(data.resolve("rooms"), BASE, () -> OutputProfile.P720_30);
        OverlayRenderer.Rendered r = overlays.current(ROOM, "chill", OverlayRendererTest.settings("tl", 22, 3));
        BufferedImage card = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 1280, 720);
        g.dispose();
        BufferedImage out = OverlayCards.composite(card, r);
        assertEquals(BASE + "/r/chill", qr(out, r));
        assertEquals(Color.RED.getRGB(), out.getRGB(1279, 719), "the rest of the card is untouched");
        // Only the QR square and the text's backdrop cover the card: beside the QR square (tl: the
        // QR code at the image's bottom left, the wider text backdrop above it) the card shows through.
        assertEquals(Color.RED.getRGB(), out.getRGB(r.x() + r.width() - 1, r.y() + r.height() - 1),
                "no panel around the QR code");
        assertEquals(Color.RED.getRGB() & 0xFFFFFF, card.getRGB(r.x() + r.width() / 2, r.y() + r.height() / 2) & 0xFFFFFF,
                "the card itself isn't drawn on");
        assertThrows(java.io.IOException.class, () -> OverlayCards.composite(new BufferedImage(640, 360,
                BufferedImage.TYPE_INT_RGB), r), "another frame size");
    }

    @Test
    @Timeout(180)
    void fillerAndItemCardsAreMadeOnceAndFollowTheirKeys() throws Exception {
        Tools tools = tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard reference = ReferenceCard.ensure(tools, data.resolve("cards"), encoding, null);
        Path roomsDir = data.resolve("rooms");
        Overlays overlays = new Overlays(roomsDir, BASE, encoding::profile);
        OverlayCards cards = new OverlayCards(roomsDir, tools);
        OverlayCache cache = new OverlayCache(roomsDir, () -> Long.MAX_VALUE, Runnable::run);
        cache.evictors(OverlayCache::deleteTree, cards::evict);
        cards.cache(cache);
        overlays.hashListener(cache::roomOverlay);
        FakeLookups lookups = new FakeLookups();
        ProgramMedia.OverlayCards room = cards.forRoom(ROOM, reference, overlays, lookups);

        // filler cards: one per filler, real cards with the reference avcC and the QR code
        Map<Filler, ProgramMedia.CardFile> fillers = room.fillers();
        assertNotNull(fillers);
        assertEquals(Filler.values().length, fillers.size());
        assertEquals(Filler.values().length, cards.encodes());
        OverlayRenderer.Rendered overlay = overlays.current(ROOM, "chill", lookups.room.settings());
        for (Map.Entry<Filler, ProgramMedia.CardFile> f : fillers.entrySet()) {
            Path file = f.getValue().file();
            assertEquals(roomsDir.resolve(ROOM).resolve("cards"), file.getParent());
            assertTrue(file.getFileName().toString().contains(overlay.hash()), file.toString());
            assertTrue(file.getFileName().toString().contains(encoding.id()), file.toString());
            assertTrue(reference.matches(Mp4Boxes.readAvcC(file)), "invariant 1: " + file);
            assertTrue(Files.isRegularFile(file.resolveSibling(file.getFileName() + ".key")));
        }
        Path paused = fillers.get(Filler.PAUSED).file();
        CardGop gop = CardGop.load(tools, encoding, paused, null);
        assertEquals(60, gop.frameCount());
        assertTrue(gop.size() < 200_000, "a skip-frame card: " + gop.size() + " bytes");
        assertEquals(BASE + "/r/chill", qr(firstFrame(tools, paused, data), overlay));

        // looked up again (and after a restart): reused, nothing encoded
        assertEquals(fillers, room.fillers());
        OverlayCards restarted = new OverlayCards(roomsDir, tools);
        assertEquals(fillers, restarted.forRoom(ROOM, reference, overlays, lookups).fillers());
        assertEquals(0, restarted.encodes());
        assertEquals(Filler.values().length, cards.encodes());

        // item card: by the entry's decision
        ProgramMedia.CardFile item = room.item(ENTRY);
        assertNotNull(item);
        assertEquals(Filler.values().length + 1, cards.encodes());
        assertTrue(item.file().getFileName().toString().startsWith("item-item1-v1-" + overlay.hash() + "-"
                + encoding.id() + "-c"), item.file().toString());
        assertTrue(reference.matches(Mp4Boxes.readAvcC(item.file())));
        assertEquals(BASE + "/r/chill", qr(firstFrame(tools, item.file(), data), overlay));
        assertEquals(item, room.item(ENTRY), "reused");
        assertEquals(Filler.values().length + 1, cards.encodes());

        // an edited title: same name, new content and key
        lookups.item = new OverlayCards.ItemInfo(1, new CardRenderer.ItemCard("Song (edit)", "Artist", "Album", null));
        ProgramMedia.CardFile edited = room.item(ENTRY);
        assertEquals(item.file(), edited.file());
        assertNotEquals(item.key(), edited.key());
        assertEquals(Filler.values().length + 2, cards.encodes());

        // a new media version: a new card
        lookups.item = new OverlayCards.ItemInfo(2, new CardRenderer.ItemCard("Song (edit)", "Artist", "Album", null));
        assertTrue(room.item(ENTRY).file().getFileName().toString().startsWith("item-item1-v2-"));

        // the overlay changes (new slug): new overlayHash, new cards
        lookups.room = new OverlayCards.RoomInfo("renamed", lookups.room.settings());
        Map<Filler, ProgramMedia.CardFile> renamed = room.fillers();
        OverlayRenderer.Rendered overlay2 = overlays.current(ROOM, "renamed", lookups.room.settings());
        assertNotEquals(overlay.hash(), overlay2.hash());
        assertTrue(renamed.get(Filler.IDLE).file().getFileName().toString().contains(overlay2.hash()));
        assertEquals(BASE + "/r/renamed", qr(firstFrame(tools, renamed.get(Filler.IDLE).file(), data), overlay2));

        // the overlay cache counts the cards, and the old overlayHash's went when the hash changed
        for (ProgramMedia.CardFile f : renamed.values()) {
            assertTrue(cache.contains(f.file()), f.file().toString());
        }
        for (ProgramMedia.CardFile f : fillers.values()) {
            assertFalse(Files.exists(f.file()), "stale: " + f.file());
            assertFalse(cache.contains(f.file()));
        }
        assertFalse(Files.exists(item.file()));
        Path idle = renamed.get(Filler.IDLE).file();
        assertTrue(cards.evict(idle));
        assertFalse(Files.exists(idle) || Files.exists(idle.resolveSibling(idle.getFileName() + ".key")));
        assertEquals(renamed, room.fillers(), "an evicted card is made again when asked for");
        assertTrue(Files.exists(idle));
    }

    /** Whether a frame's pixel is close to {@code rgb} (after the encode). */
    private static boolean near(BufferedImage frame, int x, int y, int rgb) {
        int p = frame.getRGB(x, y);
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((p >> shift) & 0xFF) - ((rgb >> shift) & 0xFF)) > 24) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasQr(BufferedImage frame, OverlayRenderer.Rendered overlay) {
        try {
            return (BASE + "/r/chill").equals(qr(frame, overlay));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @Timeout(180)
    void customImagesReplaceTheFillersWithTheOverlayByMode() throws Exception {
        Tools tools = tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard reference = ReferenceCard.ensure(tools, data.resolve("cards"), encoding, null);
        Path roomsDir = data.resolve("rooms");
        Overlays overlays = new Overlays(roomsDir, BASE, encoding::profile);
        OverlayCards cards = new OverlayCards(roomsDir, tools);
        OverlayCache cache = new OverlayCache(roomsDir, () -> Long.MAX_VALUE, Runnable::run);
        cache.evictors(OverlayCache::deleteTree, cards::evict);
        cards.cache(cache);
        overlays.hashListener(cache::roomOverlay);
        CustomCards custom = new CustomCards(data.resolve("cards/custom"), roomsDir, id -> true);
        int red = 0xE02020;
        int blue = 0x2020E0;
        CustomCards.Image paused = custom.put(null, CustomCards.Screen.PAUSED, CustomCardsTest.write(data.resolve("up"),
                "p.png", CustomCardsTest.solid(800, 600, new java.awt.Color(red)), "png"));
        CustomCards.Image idle = custom.put(ROOM, CustomCards.Screen.IDLE, CustomCardsTest.write(data.resolve("up"),
                "i.jpg", CustomCardsTest.solid(1920, 1080, new java.awt.Color(blue)), "jpeg"));
        FakeLookups lookups = new FakeLookups();
        lookups.custom = custom.effective(ROOM);
        ProgramMedia.OverlayCards room = cards.forRoom(ROOM, reference, overlays, lookups);

        // The server's overlay switch off: the two screens with an image get it, without the overlay.
        lookups.server = false;
        Map<Filler, ProgramMedia.CardFile> plain = room.fillers();
        assertEquals(java.util.Set.of(Filler.PAUSED, Filler.IDLE), plain.keySet());
        assertEquals(2, cards.encodes());
        Path pausedFile = plain.get(Filler.PAUSED).file();
        assertEquals("custom-paused-" + paused.hash() + "-plain-" + encoding.id() + "-c"
                + io.siggi.togethertunes.ingest.CardEncoder.CARD_VERSION + ".mp4", pausedFile.getFileName().toString());
        assertTrue(reference.matches(Mp4Boxes.readAvcC(pausedFile)), "invariant 1");
        assertTrue(CardGop.load(tools, encoding, pausedFile, null).size() < 200_000, "a skip-frame card");
        OverlayRenderer.Rendered overlay = overlays.current(ROOM, "chill", lookups.room.settings());
        BufferedImage frame = firstFrame(tools, pausedFile, data);
        assertTrue(near(frame, 640, 360, red), "the image");
        assertTrue(near(frame, 20, 360, 0) && near(frame, 1260, 360, 0), "4:3 drawn with black bars");
        assertTrue(near(frame, 200, 20, red), "scaled to the full height");
        assertFalse(hasQr(frame, overlay));
        BufferedImage idleFrame = firstFrame(tools, plain.get(Filler.IDLE).file(), data);
        assertTrue(near(idleFrame, 10, 10, blue) && near(idleFrame, 1270, 710, blue), "16:9 fills the frame");
        assertFalse(hasQr(idleFrame, overlay));

        // Switched on, mode idleOnly: the idle image gets the QR code; the paused card is reused.
        lookups.server = true;
        lookups.room = new OverlayCards.RoomInfo("chill", RoomSettings.DEFAULTS.patch(com.google.gson.JsonParser
                .parseString("{\"overlay\":{\"mode\":\"idleOnly\",\"corner\":\"br\",\"heightPct\":22,"
                        + "\"margin\":3}}").getAsJsonObject()));
        overlay = overlays.current(ROOM, "chill", lookups.room.settings());
        Map<Filler, ProgramMedia.CardFile> idleOnly = room.fillers();
        assertEquals(java.util.Set.of(Filler.PAUSED, Filler.IDLE), idleOnly.keySet());
        assertEquals(plain.get(Filler.PAUSED), idleOnly.get(Filler.PAUSED));
        assertEquals(3, cards.encodes());
        Path idleQr = idleOnly.get(Filler.IDLE).file();
        assertEquals("custom-idle-" + idle.hash() + "-" + overlay.hash() + "-" + encoding.id() + "-c"
                + io.siggi.togethertunes.ingest.CardEncoder.CARD_VERSION + ".mp4", idleQr.getFileName().toString());
        BufferedImage withQr = firstFrame(tools, idleQr, data);
        assertTrue(hasQr(withQr, overlay));
        assertTrue(near(withQr, 100, 100, blue));

        // always: every screen has the QR code; those without an image are the built-in overlaid cards.
        lookups.room = new OverlayCards.RoomInfo("chill", OverlayRendererTest.settings("br", 22, 3));
        Map<Filler, ProgramMedia.CardFile> always = room.fillers();
        assertEquals(Filler.values().length, always.size());
        assertEquals(idleOnly.get(Filler.IDLE), always.get(Filler.IDLE));
        assertTrue(always.get(Filler.PAUSED).file().getFileName().toString().startsWith("custom-paused-"
                + paused.hash() + "-" + overlay.hash() + "-"));
        assertTrue(always.get(Filler.LOADING).file().getFileName().toString().startsWith("filler-loading-"));
        BufferedImage pausedQr = firstFrame(tools, always.get(Filler.PAUSED).file(), data);
        assertTrue(hasQr(pausedQr, overlay));
        assertTrue(near(pausedQr, 640, 360, red));

        // A new image: a new card; the plain cards are never stale in the overlay cache.
        CustomCards.Image paused2 = custom.put(null, CustomCards.Screen.PAUSED, CustomCardsTest.write(
                data.resolve("up"), "p2.png", CustomCardsTest.solid(1280, 720, java.awt.Color.GREEN), "png"));
        lookups.custom = custom.effective(ROOM);
        Path replaced = room.fillers().get(Filler.PAUSED).file();
        assertTrue(replaced.getFileName().toString().startsWith("custom-paused-" + paused2.hash() + "-"));
        assertTrue(Files.exists(pausedFile), "plain: kept until evicted by size");
        cache.roomOverlay(ROOM, "0000000000000000");
        cache.trim();
        assertTrue(Files.exists(pausedFile), "a plain custom card doesn't depend on the overlay");
        assertFalse(Files.exists(idleQr), "an overlaid one does");

        // No images and the overlay off: nothing, as before.
        lookups.custom = Map.of();
        lookups.room = new OverlayCards.RoomInfo("chill", RoomSettings.DEFAULTS);
        assertNull(room.fillers());
    }

    @Test
    @Timeout(60)
    void noOverlayWhenSwitchedOffOrDecidedOff() throws Exception {
        Tools tools = tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard reference = ReferenceCard.ensure(tools, data.resolve("cards"), encoding, null);
        Path roomsDir = data.resolve("rooms");
        OverlayCards cards = new OverlayCards(roomsDir, tools);
        FakeLookups lookups = new FakeLookups();
        ProgramMedia.OverlayCards room = cards.forRoom(ROOM, reference,
                new Overlays(roomsDir, BASE, encoding::profile), lookups);

        lookups.server = false;
        assertNull(room.fillers(), "server switch off");
        lookups.server = true;
        lookups.room = new OverlayCards.RoomInfo("chill", RoomSettings.DEFAULTS);
        assertNull(room.fillers(), "room switch off");
        lookups.room = null;
        assertNull(room.fillers(), "no such room");

        lookups.room = new OverlayCards.RoomInfo("chill", OverlayRendererTest.settings("br", 22, 3));
        lookups.decision = OverlayDecisions.Decision.OFF;
        assertNull(room.item(ENTRY), "decided off");
        lookups.decision = null;
        assertNull(room.item(ENTRY), "no such entry");
        lookups.decision = OverlayDecisions.Decision.VIDEO;
        lookups.item = null;
        assertNull(room.item(ENTRY), "no such item");
        assertEquals(0, cards.encodes());
        assertTrue(!Files.exists(roomsDir.resolve(ROOM).resolve("cards")));

        assertThrows(IllegalArgumentException.class, () -> cards.forRoom("../x", reference,
                new Overlays(roomsDir, BASE, encoding::profile), lookups));
    }

    @Test
    @Timeout(60)
    void musicVideosGetTheRoomsReencodeOnlyWhenDecidedVideo() throws Exception {
        Tools tools = tools();
        OutputEncoding encoding = OutputEncoding.defaultFor(OutputProfile.P720_30);
        ReferenceCard reference = ReferenceCard.ensure(tools, data.resolve("cards"), encoding, null);
        Path roomsDir = data.resolve("rooms");
        Overlays overlays = new Overlays(roomsDir, BASE, encoding::profile);
        OverlayVideoJobsTest.FakeEncoder encoder = new OverlayVideoJobsTest.FakeEncoder();
        try (OverlayPool pool = new OverlayPool(1)) {
            pool.start();
            OverlayVideoJobs jobs = new OverlayVideoJobs(roomsDir, pool, encoder,
                    p -> io.siggi.togethertunes.config.X264Preset.VERYFAST, Runnable::run, System::currentTimeMillis);
            FakeLookups lookups = new FakeLookups();
            ProgramMedia.OverlayCards room = new OverlayCards(roomsDir, tools).forRoom(ROOM, reference, overlays,
                    lookups, jobs);
            ProgramCue.Entry video = new ProgramCue.Entry("e7", "item7", true, false, 10_000);
            Path dir = data.resolve("media/item7/v2");

            lookups.decision = OverlayDecisions.Decision.CARDS;
            room.videoNext(video, dir, OverlayVideoJobsTest.INDEX);
            assertNull(room.video(video, dir, OverlayVideoJobsTest.INDEX).get().gop(0));
            assertEquals(0, jobs.submitted(), "cards only: no re-encode");

            ProgramCue.Entry other = new ProgramCue.Entry("e8", "item7", true, false, 10_000);
            lookups.decision = OverlayDecisions.Decision.VIDEO;
            room.videoWanted(java.util.Set.of(other.entryId()));
            room.videoNext(other, dir, OverlayVideoJobsTest.INDEX);
            OverlayVideoJobsTest.Run run = encoder.next();
            OverlayRenderer.Rendered overlay = overlays.current(ROOM, "chill", lookups.room.settings());
            assertEquals(overlay.x(), run.request.x());
            assertEquals(overlay.y(), run.request.y());
            assertEquals(encoding, run.request.encoding());
            assertTrue(java.util.Arrays.equals(reference.avcC(), run.request.referenceAvcC()));
            assertEquals(roomsDir.resolve(ROOM).resolve("ov/item7/v2-" + overlay.hash() + "-" + encoding.id() + "-g0"),
                    run.gops.dir());
            BufferedImage png = ImageIO.read(run.request.overlayPng().toFile());
            assertEquals(overlay.width(), png.getWidth());
            assertEquals(overlay.height(), png.getHeight());
            run.finish();
        }
    }
}
