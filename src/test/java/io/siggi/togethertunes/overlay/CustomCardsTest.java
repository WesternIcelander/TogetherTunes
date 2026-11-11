package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.output.FillerSource.Filler;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Custom card images: storage, the spec warnings and the letterboxing ({@link CustomCards}). */
class CustomCardsTest {
    private static final String ROOM = "01a1c2fe-121a-74ee-ae2c-143f98971e63";

    @TempDir
    Path dir;

    static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static Path write(Path dir, String name, BufferedImage img, String format) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        assertTrue(ImageIO.write(img, format, f.toFile()));
        return f;
    }

    private static List<String> codes(List<CustomCards.Warning> w) {
        return w.stream().map(CustomCards.Warning::code).toList();
    }

    @Test
    void specWarnings() {
        assertEquals(List.of(), CustomCards.warnings(1280, 720, OutputProfile.P720_30));
        assertEquals(List.of(), CustomCards.warnings(3840, 2160, OutputProfile.P720_30));
        assertEquals(List.of(), CustomCards.warnings(1366, 768, OutputProfile.P720_30), "close enough to 16:9");
        assertEquals(List.of("aspect"), codes(CustomCards.warnings(1600, 1200, OutputProfile.P720_30)));
        assertEquals(List.of("resolution"), codes(CustomCards.warnings(640, 360, OutputProfile.P720_30)));
        assertEquals(List.of("aspect", "resolution"), codes(CustomCards.warnings(800, 600, OutputProfile.P720_30)));
        // Re-checked for another output: 1280x720 is too small for 1080p30.
        List<CustomCards.Warning> w = CustomCards.warnings(1280, 720, OutputProfile.P1080_30);
        assertEquals(List.of("resolution"), codes(w));
        assertEquals("The image is 1280×720, smaller than the 1920×1080 output (1080p30): it is scaled up and may"
                + " look blurry. Use at least 1920×1080.", w.get(0).message());
        assertEquals("The image is 800×600 (4:3), not 16:9: it is shown scaled to fit, with black bars. Use a 16:9"
                + " image such as 1280×720 to fill the screen.",
                CustomCards.warnings(800, 600, OutputProfile.P720_30).get(0).message());
        assertEquals("7:3", CustomCards.ratioText(2520, 1080));
        assertEquals("1.37:1", CustomCards.ratioText(1001, 731));
    }

    @Test
    void letterboxing() {
        int red = Color.RED.getRGB();
        int black = Color.BLACK.getRGB();
        // 4:3: bars left and right (pillarbox), 960 wide in the middle.
        BufferedImage pillar = CustomCards.fit(solid(800, 600, Color.RED), 1280, 720);
        assertEquals(1280, pillar.getWidth());
        assertEquals(720, pillar.getHeight());
        assertEquals(black, pillar.getRGB(0, 360));
        assertEquals(black, pillar.getRGB(158, 360));
        assertEquals(red, pillar.getRGB(162, 360));
        assertEquals(red, pillar.getRGB(640, 0));
        assertEquals(red, pillar.getRGB(1117, 719));
        assertEquals(black, pillar.getRGB(1122, 360));
        // Wider than 16:9: bars at the top and bottom, nothing cropped.
        BufferedImage letter = CustomCards.fit(solid(2560, 720, Color.RED), 1280, 720);
        assertEquals(black, letter.getRGB(640, 0));
        assertEquals(black, letter.getRGB(640, 178));
        assertEquals(red, letter.getRGB(0, 360));
        assertEquals(red, letter.getRGB(1279, 360));
        assertEquals(black, letter.getRGB(640, 719));
        // 16:9 at another size fills the frame; a near-16:9 one too (no 1-pixel bar).
        BufferedImage big = CustomCards.fit(solid(3840, 2160, Color.RED), 1280, 720);
        assertEquals(red, big.getRGB(0, 0));
        assertEquals(red, big.getRGB(1279, 719));
        BufferedImage near = CustomCards.fit(solid(1366, 768, Color.RED), 1280, 720);
        assertEquals(red, near.getRGB(0, 0));
        assertEquals(red, near.getRGB(1279, 719));
        // A small one is scaled up; transparency shows black; the picture is drawn as given.
        BufferedImage small = new BufferedImage(160, 90, BufferedImage.TYPE_INT_ARGB);
        small.setRGB(0, 0, 0x00FFFFFF); // fully transparent white
        for (int x = 80; x < 160; x++) {
            for (int y = 0; y < 90; y++) {
                small.setRGB(x, y, 0xFF0000FF);
            }
        }
        BufferedImage up = CustomCards.fit(small, 1280, 720);
        assertEquals(black, up.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), up.getRGB(1000, 400));
    }

    @Test
    void storesReplacesAndDeletes() throws Exception {
        CustomCards cards = new CustomCards(dir.resolve("cards/custom"), dir.resolve("rooms"), id -> true);
        assertNull(cards.server(CustomCards.Screen.PAUSED));
        Path png = write(dir.resolve("up"), "a.png", solid(1280, 720, Color.RED), "png");
        CustomCards.Image a = cards.put(null, CustomCards.Screen.PAUSED, png);
        assertEquals("png", a.format());
        assertEquals("image/png", a.contentType());
        assertEquals(1280, a.width());
        assertEquals(720, a.height());
        assertEquals(16, a.hash().length());
        assertEquals("paused-" + a.hash() + ".png", a.file().getFileName().toString());
        assertEquals(Files.size(png), a.size());
        assertTrue(Files.exists(png), "the upload is the caller's");
        assertEquals(a, cards.server(CustomCards.Screen.PAUSED));
        assertNull(cards.server(CustomCards.Screen.IDLE));

        // Replaced by a JPEG: the old file is gone, the original bytes are kept.
        Path jpg = write(dir.resolve("up"), "b.jpg", solid(800, 600, Color.GREEN), "jpeg");
        CustomCards.Image b = cards.put(null, CustomCards.Screen.PAUSED, jpg);
        assertEquals("jpeg", b.format());
        assertNotEquals(a.hash(), b.hash());
        assertFalse(Files.exists(a.file()));
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(jpg), Files.readAllBytes(b.file())));
        try (Stream<Path> files = Files.list(dir.resolve("cards/custom"))) {
            assertEquals(1, files.count());
        }

        assertTrue(cards.delete(null, CustomCards.Screen.PAUSED));
        assertFalse(cards.delete(null, CustomCards.Screen.PAUSED));
        assertNull(cards.server(CustomCards.Screen.PAUSED));
    }

    @Test
    void rejectsWhatIsNotAnAcceptableImage() throws Exception {
        CustomCards cards = new CustomCards(dir.resolve("cards/custom"), dir.resolve("rooms"), id -> true);
        Path text = dir.resolve("x.png");
        Files.writeString(text, "not an image at all");
        CustomCards.InvalidImageException e = assertThrows(CustomCards.InvalidImageException.class,
                () -> cards.put(null, CustomCards.Screen.IDLE, text));
        assertEquals("Only PNG and JPEG images are accepted", e.getMessage());
        Path gif = write(dir, "x.gif", solid(1280, 720, Color.RED), "gif");
        assertThrows(CustomCards.InvalidImageException.class, () -> cards.put(null, CustomCards.Screen.IDLE, gif));
        // A PNG cut short: the header reads, the pixels don't.
        Path png = write(dir, "full.png", solid(1280, 720, Color.RED), "png");
        byte[] bytes = Files.readAllBytes(png);
        Path cut = dir.resolve("cut.png");
        Files.write(cut, java.util.Arrays.copyOf(bytes, 40));
        assertThrows(CustomCards.InvalidImageException.class, () -> cards.put(null, CustomCards.Screen.IDLE, cut));
        // Too many pixels (checked from the header, before decoding).
        Path huge = write(dir, "huge.png", new BufferedImage(8193, 2, BufferedImage.TYPE_BYTE_BINARY), "png");
        e = assertThrows(CustomCards.InvalidImageException.class, () -> cards.put(null, CustomCards.Screen.IDLE, huge));
        assertTrue(e.getMessage().contains("8193×2"), e.getMessage());
        assertNull(cards.server(CustomCards.Screen.IDLE));
        assertFalse(Files.exists(dir.resolve("cards/custom")) && Files.list(dir.resolve("cards/custom")).findAny()
                .isPresent());
    }

    @Test
    void theRoomsImageBeatsTheServersWhileItApplies() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        CustomCards cards = new CustomCards(dir.resolve("cards/custom"), dir.resolve("rooms"),
                id -> allowed.get() && id.equals(ROOM));
        assertEquals(Map.of(), cards.effective(ROOM));
        CustomCards.Image serverIdle = cards.put(null, CustomCards.Screen.IDLE,
                write(dir.resolve("up"), "s.png", solid(1280, 720, Color.RED), "png"));
        CustomCards.Image serverGap = cards.put(null, CustomCards.Screen.GAP,
                write(dir.resolve("up"), "g.png", solid(1280, 720, Color.GRAY), "png"));
        CustomCards.Image roomIdle = cards.put(ROOM, CustomCards.Screen.IDLE,
                write(dir.resolve("up"), "r.png", solid(1280, 720, Color.BLUE), "png"));
        CustomCards.Image roomPaused = cards.put(ROOM, CustomCards.Screen.PAUSED,
                write(dir.resolve("up"), "p.png", solid(1280, 720, Color.GREEN), "png"));
        assertTrue(roomIdle.file().startsWith(dir.resolve("rooms").resolve(ROOM).resolve(CustomCards.ROOM_DIR)));
        assertEquals(Map.of(Filler.IDLE, roomIdle, Filler.PAUSED, roomPaused, Filler.GAP, serverGap),
                cards.effective(ROOM));
        // Another room only has the server's.
        assertEquals(Map.of(Filler.IDLE, serverIdle, Filler.GAP, serverGap),
                cards.effective("01a1c2fe-121a-74ee-ae2c-000000000000"));
        // The owner lost rooms.customCards: the server's again (the room's stay stored).
        allowed.set(false);
        assertEquals(Map.of(Filler.IDLE, serverIdle, Filler.GAP, serverGap), cards.effective(ROOM));
        assertEquals(roomIdle, cards.room(ROOM, CustomCards.Screen.IDLE));
        allowed.set(true);
        // Deleting the room's goes back to the server's.
        assertTrue(cards.delete(ROOM, CustomCards.Screen.IDLE));
        assertEquals(Map.of(Filler.IDLE, serverIdle, Filler.PAUSED, roomPaused, Filler.GAP, serverGap),
                cards.effective(ROOM));
        assertThrows(IllegalArgumentException.class, () -> cards.room("../x", CustomCards.Screen.IDLE));
    }
}
