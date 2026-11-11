package io.siggi.togethertunes.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.fonts.CardFonts;
import io.siggi.togethertunes.ingest.CardRenderer.Filler;
import io.siggi.togethertunes.ingest.CardRenderer.Fitted;
import io.siggi.togethertunes.ingest.CardRenderer.ItemCard;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CardRendererTest {
    /** The bundled font and the installed ones, as on a server without downloaded fonts. */
    private static final CardFonts FONTS = CardFonts.bundledOnly();
    private static final String LONG = "An Extraordinarily Long Song Title That Goes On And On Well Past The Edge Of Any "
            + "Reasonable Card Layout (Extended Deluxe Anniversary Remastered Version)";

    @TempDir
    Path dir;

    private static FontRenderContext frc() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        FontRenderContext frc = g.getFontRenderContext();
        g.dispose();
        return frc;
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static double luma(int rgb) {
        return 0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255);
    }

    private static int brightPixels(BufferedImage img, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (luma(img.getRGB(x, y)) > 180) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void shortTextKeepsItsSize() {
        Fitted f = CardRenderer.fit(FONTS, "Hello", Font.BOLD, 56, 32, 600, frc());
        assertEquals("Hello", f.text());
        assertEquals(56f, f.size());
        assertFalse(f.shrunk());
        assertFalse(f.ellipsized());
        assertTrue(f.width() <= 600);
    }

    @Test
    void shrinksBeforeEllipsizing() {
        FontRenderContext frc = frc();
        String text = "A Moderately Long Title Here";
        double atFull = CardRenderer.advance(FONTS, text, Font.BOLD, 56f, frc);
        double max = atFull * 0.8; // fits at about 45
        Fitted f = CardRenderer.fit(FONTS, text, Font.BOLD, 56, 32, max, frc);
        assertEquals(text, f.text());
        assertTrue(f.shrunk());
        assertFalse(f.ellipsized());
        assertTrue(f.size() < 56 && f.size() > 32, "size " + f.size());
        assertTrue(f.width() <= max);
        // The largest size that fits: one point bigger doesn't.
        assertTrue(CardRenderer.advance(FONTS, text, Font.BOLD, f.size() + 1, frc) > max);
    }

    @Test
    void ellipsizesAtTheMinimumSize() {
        Fitted f = CardRenderer.fit(FONTS, LONG, Font.BOLD, 56, 32, 600, frc());
        assertTrue(f.ellipsized());
        assertEquals(32f, f.size());
        assertTrue(f.width() <= 600, "width " + f.width());
        assertTrue(f.text().endsWith(CardRenderer.ELLIPSIS));
        String kept = f.text().substring(0, f.text().length() - 1);
        assertTrue(LONG.startsWith(kept));
        assertFalse(kept.endsWith(" "), "no space before the ellipsis");
        assertTrue(kept.length() > 10);
        // As many graphemes as fit: one more character would overflow.
        String oneMore = LONG.substring(0, kept.length() + 1).stripTrailing() + CardRenderer.ELLIPSIS;
        if (!oneMore.equals(f.text())) {
            assertTrue(CardRenderer.advance(FONTS, oneMore, f.style(), f.size(), frc()) > 600 || LONG.charAt(kept.length()) == ' ');
        }
    }

    @Test
    void ellipsizingNeverSplitsSurrogatePairsOrClusters() {
        // Emoji outside the BMP and a flag (two regional indicators = one grapheme).
        String text = "🎵".repeat(60) + "🇮🇸".repeat(40);
        for (int max = 40; max < 800; max += 37) {
            Fitted f = CardRenderer.fit(FONTS, text, Font.PLAIN, 40, 26, max, frc());
            String kept = f.text().substring(0, f.text().length() - (f.ellipsized() ? 1 : 0));
            assertTrue(text.startsWith(kept));
            assertFalse(!kept.isEmpty() && Character.isHighSurrogate(kept.charAt(kept.length() - 1)), "split pair");
            int flagsStart = 120;
            if (kept.length() > flagsStart) {
                assertEquals(0, (kept.length() - flagsStart) % 4, "split flag at max " + max);
            }
        }
    }

    @Test
    void textNarrowerThanAnEllipsisBecomesJustTheEllipsis() {
        Fitted f = CardRenderer.fit(FONTS, "Wide", Font.BOLD, 56, 32, 1, frc());
        assertEquals(CardRenderer.ELLIPSIS, f.text());
    }

    @Test
    void cleansText() {
        assertEquals("", CardRenderer.clean(null));
        assertEquals("", CardRenderer.clean(" \t\n "));
        assertEquals("a b c", CardRenderer.clean("  a\r\n b\u0000  c\t"));
        assertEquals("Ólöf 東京", CardRenderer.clean("Ólöf  東京"));
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    void rendersAtTheProfileSize(OutputProfile profile) {
        CardRenderer r = new CardRenderer(profile);
        BufferedImage item = r.renderItem(new ItemCard("Title", "Artist", "Album", null));
        assertEquals(profile.width(), item.getWidth());
        assertEquals(profile.height(), item.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, item.getType());
        for (Filler f : Filler.values()) {
            BufferedImage img = r.renderFiller(f);
            assertEquals(profile.width(), img.getWidth());
            assertEquals(profile.height(), img.getHeight());
            assertEquals(BufferedImage.TYPE_INT_RGB, img.getType());
        }
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    void itemCardShowsArtworkAndText(OutputProfile profile) {
        CardRenderer r = new CardRenderer(profile);
        double s = profile.height() / 720.0;
        int artX = (int) Math.round(80 * s), artSize = (int) Math.round(440 * s), artY = (profile.height() - artSize) / 2;
        BufferedImage img = r.renderItem(new ItemCard("Title", "Artist", "Album", solid(500, 500, new Color(200, 30, 40))));
        Color centre = new Color(img.getRGB(artX + artSize / 2, artY + artSize / 2));
        assertEquals(200, centre.getRed(), 2);
        assertEquals(30, centre.getGreen(), 2);
        // Background is the darkened artwork: reddish but dark.
        Color corner = new Color(img.getRGB(5, 5));
        assertTrue(corner.getRed() > corner.getGreen() && luma(corner.getRGB()) < 60, corner.toString());
        // Text (white) to the right of the artwork, nothing bright beyond the right margin.
        int textX = artX + artSize;
        assertTrue(brightPixels(img, textX, 0, profile.width(), profile.height()) > 200);
        int right = profile.width() - (int) Math.round(80 * s) + 2;
        assertEquals(0, brightPixels(img, right, 0, profile.width(), profile.height()));
    }

    @Test
    void itemCardWithoutArtworkDrawsAPlaceholder() {
        CardRenderer r = new CardRenderer(OutputProfile.P720_30);
        BufferedImage img = r.renderItem(new ItemCard("Title", null, "", null));
        assertEquals(CardRenderer.TILE.getRGB(), img.getRGB(85, 145)); // inside the tile, away from the note
        BufferedImage withArtist = r.renderItem(new ItemCard("Title", "Artist", "", null));
        assertFalse(sameImage(img, withArtist));
        BufferedImage blankTitle = r.renderItem(new ItemCard("  ", null, null, null));
        BufferedImage untitled = r.renderItem(new ItemCard(CardRenderer.UNTITLED, null, null, null));
        assertTrue(sameImage(blankTitle, untitled));
    }

    @ParameterizedTest
    @EnumSource(OutputProfile.class)
    void longTextStaysInsideTheMargins(OutputProfile profile) {
        CardRenderer r = new CardRenderer(profile);
        String cjk = "東京の夜に響く長い長い歌のタイトル".repeat(4);
        BufferedImage img = r.renderItem(new ItemCard(LONG, LONG, cjk, solid(400, 300, Color.DARK_GRAY)));
        double s = profile.height() / 720.0;
        int right = profile.width() - (int) Math.round(80 * s) + 2;
        assertEquals(0, brightPixels(img, right, 0, profile.width(), profile.height()));
        BufferedImage paused = r.renderMessage(Filler.PAUSED, LONG + LONG);
        int m = (int) Math.round(80 * s) - 2;
        assertEquals(0, brightPixels(paused, 0, 0, m, profile.height()));
        assertEquals(0, brightPixels(paused, profile.width() - m, 0, profile.width(), profile.height()));
    }

    @Test
    void fillerCardsAreDistinctAndCentred() {
        CardRenderer r = new CardRenderer(OutputProfile.P720_30);
        Set<Integer> hashes = new HashSet<>();
        for (Filler f : Filler.values()) {
            BufferedImage img = r.renderFiller(f);
            hashes.add(Arrays.hashCode(img.getRGB(0, 0, 1280, 720, null, 0, 1280)));
            assertTrue(brightPixels(img, 340, 200, 940, 520) > 500, f.name());
            assertEquals(0, brightPixels(img, 0, 0, 1280, 120), f.name());
            assertEquals(0, brightPixels(img, 0, 600, 1280, 720), f.name());
            assertEquals(0, brightPixels(img, 0, 0, 200, 720), f.name());
        }
        assertEquals(Filler.values().length, hashes.size());
        assertEquals("Loading…", Filler.LOADING.label());
        assertEquals("Queue is empty", Filler.QUEUE_EMPTY.label());
        assertEquals("Paused", Filler.PAUSED.label());
        assertFalse(sameImage(r.renderFiller(Filler.QUEUE_EMPTY), r.renderMessage(Filler.QUEUE_EMPTY, "Room")));
        assertTrue(sameImage(r.renderFiller(Filler.QUEUE_EMPTY), r.renderMessage(Filler.QUEUE_EMPTY, " ")));
    }

    @Test
    void renderingIsDeterministic() {
        CardRenderer r = new CardRenderer(OutputProfile.P720_30);
        ItemCard card = new ItemCard("Song", "Band", "Record", solid(300, 300, Color.BLUE));
        assertTrue(sameImage(r.renderItem(card), r.renderItem(card)));
    }

    @Test
    void coverCropsToTheCentre() {
        // A 16:9 thumbnail with black bars around square red art: the crop is just the art.
        BufferedImage thumb = solid(1280, 720, Color.BLACK);
        Graphics2D g = thumb.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(280, 0, 720, 720);
        g.dispose();
        BufferedImage sq = CardRenderer.cover(thumb, 300, 300);
        assertEquals(300, sq.getWidth());
        assertEquals(300, sq.getHeight());
        for (int[] p : new int[][] {{2, 2}, {297, 2}, {2, 297}, {297, 297}, {150, 150}}) {
            assertTrue(new Color(sq.getRGB(p[0], p[1])).getRed() > 240, Arrays.toString(p));
        }
        // Portrait source to a landscape target.
        BufferedImage wide = CardRenderer.cover(solid(100, 400, Color.GREEN), 64, 36);
        assertEquals(64, wide.getWidth());
        assertEquals(36, wide.getHeight());
    }

    @Test
    void transparentArtworkIsFlattened() {
        BufferedImage argb = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        BufferedImage img = new CardRenderer(OutputProfile.P720_30).renderItem(new ItemCard("T", null, null, argb));
        assertEquals(CardRenderer.TILE.getRGB(), img.getRGB(80 + 220, 140 + 220));
    }

    @Test
    void writesAndReadsPng() throws Exception {
        CardRenderer r = new CardRenderer(OutputProfile.P720_30);
        BufferedImage img = r.renderFiller(Filler.PAUSED);
        Path png = dir.resolve("paused.png");
        CardRenderer.writePng(img, png);
        BufferedImage back = CardRenderer.readImage(png);
        assertNotNull(back);
        assertTrue(sameImage(img, back));
        Path junk = Files.writeString(dir.resolve("x.webp"), "not an image");
        assertEquals(null, CardRenderer.readImage(junk));

        // Optional: -Dcards.out=<dir> writes sample cards for looking at them.
        String out = System.getProperty("cards.out");
        if (out != null) {
            Path o = Files.createDirectories(Path.of(out));
            for (OutputProfile p : OutputProfile.values()) {
                CardRenderer pr = new CardRenderer(p);
                BufferedImage art = solid(640, 360, new Color(40, 90, 160));
                Graphics2D g = art.createGraphics();
                g.setColor(new Color(240, 180, 60));
                g.fillOval(200, 60, 240, 240);
                g.dispose();
                CardRenderer.writePng(pr.renderItem(new ItemCard("Bohemian Rhapsody", "Queen", "A Night at the Opera", art)), o.resolve(p.id() + "-item.png"));
                CardRenderer.writePng(pr.renderItem(new ItemCard(LONG, LONG, "東京の夜".repeat(12), null)), o.resolve(p.id() + "-item-long.png"));
                for (Filler f : Filler.values()) {
                    CardRenderer.writePng(pr.renderFiller(f), o.resolve(p.id() + "-" + f.id() + ".png"));
                }
                CardRenderer.writePng(pr.renderMessage(Filler.QUEUE_EMPTY, "Siggi's Room"), o.resolve(p.id() + "-idle-room.png"));
            }
        }
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        int w = a.getWidth(), h = a.getHeight();
        return Arrays.equals(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w));
    }
}
