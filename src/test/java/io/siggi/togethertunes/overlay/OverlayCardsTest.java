package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        FakeLookups lookups = new FakeLookups();
        ProgramMedia.OverlayCards room = cards.forRoom(ROOM, reference, overlays, lookups);

        // filler cards: one per filler, real cards with the reference avcC and the QR code
        Map<Filler, ProgramMedia.CardFile> fillers = room.fillers();
        assertNotNull(fillers);
        assertEquals(Filler.values().length, fillers.size());
        assertEquals(3, cards.encodes());
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
        assertEquals(3, cards.encodes());

        // item card: by the entry's decision
        ProgramMedia.CardFile item = room.item(ENTRY);
        assertNotNull(item);
        assertEquals(4, cards.encodes());
        assertTrue(item.file().getFileName().toString().startsWith("item-item1-v1-" + overlay.hash() + "-"
                + encoding.id() + "-c"), item.file().toString());
        assertTrue(reference.matches(Mp4Boxes.readAvcC(item.file())));
        assertEquals(BASE + "/r/chill", qr(firstFrame(tools, item.file(), data), overlay));
        assertEquals(item, room.item(ENTRY), "reused");
        assertEquals(4, cards.encodes());

        // an edited title: same name, new content and key
        lookups.item = new OverlayCards.ItemInfo(1, new CardRenderer.ItemCard("Song (edit)", "Artist", "Album", null));
        ProgramMedia.CardFile edited = room.item(ENTRY);
        assertEquals(item.file(), edited.file());
        assertNotEquals(item.key(), edited.key());
        assertEquals(5, cards.encodes());

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
}
