package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverBarsTest {
    private static final Color GREY = new Color(0x40, 0x40, 0x40);

    /** Busy "artwork": blocks of random colours (no row or column is one colour). */
    private static void art(BufferedImage img, int x, int y, int w, int h, long seed) {
        Random r = new Random(seed);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int bx = i / 8;
                int by = j / 8;
                int base = (int) ((bx * 73856093L ^ by * 19349663L ^ seed) & 0xFFFFFF);
                int noise = r.nextInt(40);
                int red = Math.min(255, ((base >> 16) & 0xFF) / 2 + 60 + noise);
                int green = Math.min(255, ((base >> 8) & 0xFF) / 2 + 60 + noise);
                int blue = Math.min(255, (base & 0xFF) / 2 + 60 + noise);
                img.setRGB(x + i, y + j, (red << 16) | (green << 8) | blue);
            }
        }
    }

    private static BufferedImage filled(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage jpegRoundTrip(BufferedImage img, Path dir) throws IOException {
        Path f = dir.resolve("round.jpg");
        CoverBars.writeJpeg(img, f);
        return ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(f)));
    }

    @Test
    void squareArtInGreySixteenByNineBars() {
        BufferedImage img = filled(1280, 720, GREY);
        art(img, 280, 0, 720, 720, 1);
        assertEquals(new Rectangle(280, 0, 720, 720), CoverBars.bounds(img));
        BufferedImage t = CoverBars.trim(img);
        assertEquals(720, t.getWidth());
        assertEquals(720, t.getHeight());
        assertEquals(img.getRGB(280, 0), t.getRGB(0, 0));
    }

    @Test
    void blackFourByThreeAroundGreySixteenByNineAroundSquareArt() {
        BufferedImage img = filled(480, 360, Color.BLACK);
        Graphics2D g = img.createGraphics();
        g.setColor(GREY);
        g.fillRect(0, 45, 480, 270);
        g.dispose();
        art(img, 105, 45, 270, 270, 2);
        assertEquals(new Rectangle(105, 45, 270, 270), CoverBars.bounds(img));
    }

    @Test
    void blackFourByThreeAroundSixteenByNineContent() {
        BufferedImage img = filled(480, 360, Color.BLACK);
        art(img, 0, 45, 480, 270, 3);
        assertEquals(new Rectangle(0, 45, 480, 270), CoverBars.bounds(img));
    }

    @Test
    void darkEdgeOfRealContentIsKept() {
        BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        art(img, 0, 0, 1280, 720, 4);
        Random r = new Random(4);
        for (int y = 0; y < 720; y++) {
            for (int x = 0; x < 120; x++) {
                int v = r.nextInt(60);
                img.setRGB(x, y, (v << 16) | (v << 8) | r.nextInt(60));
            }
        }
        assertNull(CoverBars.bounds(img));
        assertSame(img, CoverBars.trim(img));
    }

    /** A one-colour area on one side only is content (a poster's black top, a plain background). */
    @Test
    void oneColourFrameOnOneSideOnlyIsKept() {
        BufferedImage img = filled(1000, 800, Color.WHITE);
        art(img, 100, 0, 900, 800, 5);
        assertNull(CoverBars.bounds(img));
        BufferedImage poster = filled(800, 1200, Color.BLACK);
        art(poster, 0, 70, 800, 1130, 5);
        assertNull(CoverBars.bounds(poster));
    }

    @Test
    void unevenOppositeBarsAreContent() {
        BufferedImage img = filled(1200, 1200, Color.MAGENTA);
        art(img, 0, 74, 1200, 1200 - 74 - 39, 12); // 74 above, 39 below
        assertNull(CoverBars.bounds(img));
        BufferedImage near = filled(480, 360, Color.BLACK);
        art(near, 0, 60, 480, 250, 13); // 60 above, 50 below: a letterbox, a little uneven
        assertEquals(new Rectangle(0, 60, 480, 250), CoverBars.bounds(near));
    }

    @Test
    void allOneColourIsLeftAlone() {
        assertNull(CoverBars.bounds(filled(640, 360, Color.BLACK)));
        assertNull(CoverBars.bounds(filled(640, 360, GREY)));
    }

    @Test
    void oneColourArtBetweenBarsKeepsTheCrop() {
        BufferedImage img = filled(1280, 720, GREY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(280, 0, 720, 720);
        g.dispose();
        assertEquals(new Rectangle(280, 0, 720, 720), CoverBars.bounds(img));
    }

    @Test
    void jpegNoiseStillFindsTheBars(@TempDir Path dir) throws IOException {
        BufferedImage img = filled(1280, 720, GREY);
        art(img, 280, 0, 720, 720, 6);
        BufferedImage noisy = jpegRoundTrip(img, dir);
        Rectangle r = CoverBars.bounds(noisy);
        assertNotNull(r);
        assertTrue(r.x >= 276 && r.x <= 284, "x " + r.x);
        assertTrue(r.width >= 712 && r.width <= 724, "width " + r.width);
        assertEquals(0, r.y);
        assertEquals(720, r.height);
    }

    @Test
    void aFewOutlierPixelsInABar() {
        BufferedImage img = filled(1280, 720, GREY);
        art(img, 280, 0, 720, 720, 7);
        img.setRGB(10, 100, 0xFFFFFF);
        img.setRGB(1270, 300, 0xFF0000);
        assertEquals(new Rectangle(280, 0, 720, 720), CoverBars.bounds(img));
    }

    @Test
    void barsUnderTwoPercentDontCount() {
        BufferedImage img = filled(1000, 1000, Color.BLACK);
        art(img, 10, 0, 980, 1000, 8); // 1 % each side
        assertNull(CoverBars.bounds(img));
    }

    @Test
    void tooLittleLeftIsNotCropped() {
        BufferedImage img = filled(1280, 720, GREY);
        art(img, 540, 160, 200, 400, 9); // 16 % of the width
        assertNull(CoverBars.bounds(img));
    }

    @Test
    void oppositeBarsOfDifferentColoursDontCount() {
        BufferedImage img = filled(1280, 720, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(1000, 0, 280, 720);
        g.dispose();
        art(img, 280, 0, 720, 720, 10);
        assertNull(CoverBars.bounds(img));
    }

    @Test
    void trimFileRewritesOnlyWhenThereAreBars(@TempDir Path dir) throws IOException {
        BufferedImage img = filled(1280, 720, GREY);
        art(img, 280, 0, 720, 720, 11);
        Path f = dir.resolve("cover.jpg");
        CoverBars.writeJpeg(img, f);
        CoverBars.Result res = CoverBars.trimFile(f);
        assertTrue(res.trimmed());
        BufferedImage back = ImageIO.read(f.toFile());
        assertTrue(Math.abs(back.getWidth() - 720) <= 8, "width " + back.getWidth());
        assertEquals(720, back.getHeight());
        assertEquals(back.getWidth(), res.image().getWidth());

        byte[] before = Files.readAllBytes(f);
        CoverBars.Result again = CoverBars.trimFile(f);
        assertFalse(again.trimmed());
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(f));
        try (var s = Files.list(dir)) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void unreadableFileIsNull(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("cover.jpg");
        Files.writeString(f, "not an image");
        assertNull(CoverBars.trimFile(f));
    }
}
