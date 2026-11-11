package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.InvertedLuminanceSource;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.ResultMetadataType;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class OverlayRendererTest {
    private static final String BASE = "https://tunes.example.com";
    private final OverlayRenderer renderer = new OverlayRenderer();

    static RoomSettings settings(String corner, int heightPct, int margin) {
        return RoomSettings.DEFAULTS.patch(JsonParser.parseString("{\"overlay\":{\"mode\":\"always\",\"corner\":\""
                + corner + "\",\"heightPct\":" + heightPct + ",\"margin\":" + margin + "}}").getAsJsonObject());
    }

    private OverlayRenderer.Rendered render(String corner, int heightPct, int margin, OutputProfile profile) {
        return renderer.render(OverlayRenderer.spec(BASE, "chill-room", settings(corner, heightPct, margin), profile));
    }

    /** The overlay composited onto a mid-grey frame, as the stream would show it. */
    private static BufferedImage frame(OverlayRenderer.Rendered r) {
        return frame(r, "grey");
    }

    /** The overlay composited onto a frame: {@code black}, {@code white}, {@code grey} or {@code noise}. */
    static BufferedImage frame(OverlayRenderer.Rendered r, String background) {
        int w = r.spec().frameWidth(), h = r.spec().frameHeight();
        BufferedImage f = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = f.createGraphics();
        switch (background) {
            case "black" -> g.setColor(Color.BLACK);
            case "white" -> g.setColor(Color.WHITE);
            case "grey" -> g.setColor(new Color(0x808890));
            case "noise" -> g.setColor(Color.BLACK);
            default -> throw new IllegalArgumentException(background);
        }
        g.fillRect(0, 0, w, h);
        if (background.equals("noise")) {
            // A busy picture: random colours per pixel plus random bright and dark blocks about a
            // module's size, the worst case for a translucent backdrop.
            Random rnd = new Random(42);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    f.setRGB(x, y, rnd.nextInt(0x1000000));
                }
            }
            for (int i = 0; i < 20000; i++) {
                g.setColor(rnd.nextBoolean() ? Color.WHITE : new Color(rnd.nextInt(0x1000000)));
                g.fillRect(rnd.nextInt(w), rnd.nextInt(h), 1 + rnd.nextInt(8), 1 + rnd.nextInt(8));
            }
        }
        g.drawImage(r.image(), r.x(), r.y(), null);
        g.dispose();
        return f;
    }

    /**
     * Decodes a QR code from {@code image}, also an inverted one (light modules on a dark ground,
     * as the overlay draws it), like phone camera apps do.
     */
    static Result decode(BufferedImage image) throws Exception {
        int[] px = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        return decode(new RGBLuminanceSource(image.getWidth(), image.getHeight(), px));
    }

    /** {@link #decode(BufferedImage)} for any luminance source: as is, then inverted. */
    public static Result decode(LuminanceSource source) throws Exception {
        QRCodeReader reader = new QRCodeReader();
        Map<DecodeHintType, Object> hints = Map.of(DecodeHintType.TRY_HARDER, true);
        try {
            return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
        } catch (NotFoundException | com.google.zxing.ChecksumException | com.google.zxing.FormatException e) {
            reader.reset();
            return reader.decode(new BinaryBitmap(new HybridBinarizer(new InvertedLuminanceSource(source))), hints);
        }
    }

    @Test
    void urlAndText() {
        assertEquals("https://tunes.example.com/r/chill", OverlayRenderer.shortUrl("https://tunes.example.com/", "chill"));
        assertEquals("http://h:8080/tt/r/x", OverlayRenderer.shortUrl("http://h:8080/tt", "x"));
        OverlayRenderer.Spec spec = OverlayRenderer.spec(BASE, "chill-room", RoomSettings.DEFAULTS, OutputProfile.P720_30);
        assertEquals("Requests at tunes.example.com/r/chill-room", spec.text());
        assertEquals("br", spec.corner());
        assertEquals(22, spec.heightPct());
        assertEquals(3, spec.margin());
    }

    @Test
    void qrCodeHasLevelMAndAFourModuleQuietZone() throws Exception {
        String url = BASE + "/r/chill-room";
        BitMatrix m = OverlayRenderer.qr(url);
        // Quiet zone: the outer 4 modules are light, the finder pattern starts right after.
        for (int i = 0; i < m.getWidth(); i++) {
            for (int q = 0; q < OverlayRenderer.QUIET_ZONE; q++) {
                assertTrue(!m.get(i, q) && !m.get(q, i) && !m.get(i, m.getHeight() - 1 - q) && !m.get(m.getWidth() - 1 - q, i));
            }
        }
        assertTrue(m.get(4, 4), "finder pattern at the quiet zone's edge");
        OverlayRenderer.Rendered r = render("br", 22, 3, OutputProfile.P720_30);
        Result result = decode(frame(r));
        assertEquals(url, result.getText());
        assertEquals(ErrorCorrectionLevel.M.toString(),
                result.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"br", "bl", "tr", "tl"})
    void cornersPlaceTheOverlayAndTheTextFacingTheMiddle(String corner) throws Exception {
        OverlayRenderer.Rendered r = render(corner, 22, 3, OutputProfile.P720_30);
        int margin = 22; // 3% of 720 = 21.6 → 22 (even)
        boolean right = corner.endsWith("r"), bottom = corner.startsWith("b");
        if (right) {
            assertEquals(1280 - margin, r.x() + r.width());
        } else {
            assertEquals(margin, r.x());
        }
        if (bottom) {
            assertEquals(720 - margin, r.y() + r.height());
        } else {
            assertEquals(margin, r.y());
        }
        assertEquals(0, r.x() % 2);
        assertEquals(0, r.y() % 2);
        assertEquals(0, r.width() % 2);
        assertEquals(0, r.height() % 2);
        assertEquals(BASE + "/r/chill-room", decode(frame(r)).getText());

        // The QR square and the text rows (white-ish pixels outside it).
        BufferedImage img = r.image();
        int[] qr = qrSquare(img, bottom);
        int qrLeft = qr[0], qrTop = qr[1], qrSide = qr[2];
        assertTrue(Math.abs(qrSide - 0.22 * 720) <= 0.22 * 720 / 4, "QR side " + qrSide);
        int textTop = Integer.MAX_VALUE, textBottom = -1, textLeft = Integer.MAX_VALUE, textRight = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                boolean inQr = x >= qrLeft && x < qrLeft + qrSide && y >= qrTop && y < qrTop + qrSide;
                if (!inQr && luma(img.getRGB(x, y)) > 200 && alpha(img.getRGB(x, y)) == 255) {
                    textTop = Math.min(textTop, y);
                    textBottom = Math.max(textBottom, y);
                    textLeft = Math.min(textLeft, x);
                    textRight = Math.max(textRight, x);
                }
            }
        }
        assertTrue(textBottom >= 0, "text drawn");
        if (bottom) {
            assertTrue(textTop > qrTop + qrSide, "text below the QR code in " + corner);
        } else {
            assertTrue(textBottom < qrTop, "text above the QR code in " + corner);
        }
        // Aligned to the QR code's edge (inset by the backdrop's padding, under the text's height).
        int inset = textBottom - textTop;
        if (right) {
            int d = (qrLeft + qrSide - 1) - textRight;
            assertTrue(d > 0 && d <= inset, "right aligned: " + textRight + " vs " + (qrLeft + qrSide));
        } else {
            int d = textLeft - qrLeft;
            assertTrue(d > 0 && d <= inset, "left aligned: " + textLeft + " vs " + qrLeft);
        }
        // The QR square at the outer edges, apart from the text's backdrop.
        assertEquals(right ? img.getWidth() - qrSide : 0, qrLeft, "QR square at the outer edge");
        assertEquals(bottom ? 0 : img.getHeight() - qrSide, qrTop, "QR square at the outer edge");
        assertInvertedQr(img, qrLeft, qrTop, qrSide, BASE + "/r/chill-room");
        int[] bd = backdrop(img, qrTop, qrSide);
        int bdLeft = bd[0], bdTop = bd[1], bdRight = bd[2], bdBottom = bd[3];
        assertEquals(bottom ? img.getHeight() - 1 : 0, bottom ? bdBottom : bdTop, "backdrop at the outer edge");
        assertEquals(right ? img.getWidth() - 1 : 0, right ? bdRight : bdLeft, "backdrop aligned with the QR code");
        int gapRows = bottom ? bdTop - (qrTop + qrSide) : qrTop - bdBottom - 1;
        assertTrue(gapRows >= 2, "transparent gap between QR and backdrop: " + gapRows);
        // The backdrop covers the text's bounds, with padding, and hugs it.
        assertTrue(textLeft > bdLeft && textRight < bdRight && textTop > bdTop && textBottom < bdBottom,
                "text " + textLeft + "," + textTop + "-" + textRight + "," + textBottom + " inside backdrop "
                        + java.util.Arrays.toString(bd));
        int textH = textBottom - textTop + 1;
        assertTrue(bdBottom - bdTop + 1 < textH * 2, "backdrop hugs the text vertically");
        assertTrue((textLeft - bdLeft) + (bdRight - textRight) < textH * 2, "backdrop hugs the text horizontally");
        // Every pixel is either in the QR square, in the backdrop's box, or fully transparent.
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int a = alpha(img.getRGB(x, y));
                boolean inQr = x >= qrLeft && x < qrLeft + qrSide && y >= qrTop && y < qrTop + qrSide;
                boolean inBd = x >= bdLeft && x <= bdRight && y >= bdTop && y <= bdBottom;
                if (!inQr && !inBd) {
                    assertEquals(0, a, "transparent outside QR square and backdrop at " + x + "," + y);
                }
            }
        }
        // The backdrop is semi-opaque with rounded corners; text pixels sit on it.
        assertEquals(0, alpha(img.getRGB(bdLeft, bdTop)), "rounded corner");
        assertEquals(0, alpha(img.getRGB(bdRight, bdBottom)), "rounded corner");
        int edge = alpha(img.getRGB((bdLeft + bdRight) / 2, bdTop));
        assertTrue(edge > 100 && edge < 255, "semi-opaque backdrop, alpha " + edge);
        for (int y = bdTop; y <= bdBottom; y++) {
            for (int x = bdLeft; x <= bdRight; x++) {
                int p = img.getRGB(x, y);
                if (luma(p) > 60 && alpha(p) > 0) {
                    assertTrue(alpha(p) >= OverlayRenderer.BACKDROP.getAlpha(), "text pixel on the backdrop at " + x + "," + y);
                }
            }
        }
    }

    /** {left, top, right, bottom} (inclusive) of the non-transparent pixels outside the QR square's rows. */
    private static int[] backdrop(BufferedImage img, int qrTop, int qrSide) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            if (y >= qrTop && y < qrTop + qrSide) {
                continue;
            }
            for (int x = 0; x < img.getWidth(); x++) {
                if (alpha(img.getRGB(x, y)) > 0) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertTrue(bottom >= 0, "a backdrop");
        return new int[] {left, top, right, bottom};
    }

    /**
     * {left, top, side} of the QR square inside {@code r}'s image (not the frame), for decoding
     * just the code from a frame the way a phone camera aims at it.
     */
    public static int[] qrSquare(OverlayRenderer.Rendered r) {
        return qrSquare(r.image(), r.spec().bottom());
    }

    /**
     * {left, top, side} of the QR square: the bounding box of the non-transparent pixels in the
     * band of rows at the image's outer edge (top for the bottom corners), which a fully
     * transparent gap separates from the text's backdrop.
     */
    private static int[] qrSquare(BufferedImage img, boolean bottom) {
        int h = img.getHeight();
        int start = bottom ? 0 : h - 1, step = bottom ? 1 : -1;
        int left = Integer.MAX_VALUE, right = -1, top = Integer.MAX_VALUE, last = -1;
        for (int y = start; y >= 0 && y < h; y += step) {
            boolean any = false;
            for (int x = 0; x < img.getWidth(); x++) {
                if (alpha(img.getRGB(x, y)) > 0) {
                    any = true;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            if (!any) {
                break;
            }
            top = Math.min(top, y);
            last = Math.max(last, y);
        }
        assertTrue(last >= 0, "a QR square");
        int side = right - left + 1;
        assertEquals(side, last - top + 1, "the QR code is square");
        return new int[] {left, top, side};
    }

    /**
     * The QR square is drawn inverted on the backdrop: every dark module is opaque white, every
     * light module (quiet zone included) is the backdrop's colour and alpha, and only the rounded
     * corners (inside the quiet zone) are transparent or partly so.
     */
    private static void assertInvertedQr(BufferedImage img, int left, int top, int side, String url) {
        BitMatrix m = OverlayRenderer.qr(url);
        int modules = m.getWidth();
        assertEquals(0, side % modules, "whole pixels per module");
        int mp = side / modules;
        int backdrop = OverlayRenderer.BACKDROP.getRGB();
        int corners = 0;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int p = img.getRGB(left + x, top + y);
                int mx = x / mp, my = y / mp;
                boolean inCornerZone = (mx < OverlayRenderer.QUIET_ZONE || mx >= modules - OverlayRenderer.QUIET_ZONE)
                        && (my < OverlayRenderer.QUIET_ZONE || my >= modules - OverlayRenderer.QUIET_ZONE);
                if (m.get(mx, my)) {
                    assertEquals(0xffffffff, p, "dark module drawn white at " + mx + "," + my);
                } else if (p != backdrop) {
                    assertTrue(inCornerZone && (p & 0xFFFFFF) == 0 && alpha(p) < OverlayRenderer.BACKDROP.getAlpha(),
                            "light module is backdrop at " + mx + "," + my + ": " + Integer.toHexString(p));
                    corners++;
                }
            }
        }
        assertTrue(corners > 0, "rounded corners");
        for (int[] c : new int[][] {{0, 0}, {side - 1, 0}, {0, side - 1}, {side - 1, side - 1}}) {
            assertEquals(0, alpha(img.getRGB(left + c[0], top + c[1])), "rounded corner");
        }
        assertEquals(backdrop, img.getRGB(left + side / 2, top), "backdrop at the edge's middle");
        // The rounding stays inside the quiet zone: its innermost ring is all backdrop.
        int q = OverlayRenderer.QUIET_ZONE * mp - 1;
        for (int i = q; i < side - q; i++) {
            assertEquals(backdrop, img.getRGB(left + i, top + q));
            assertEquals(backdrop, img.getRGB(left + q, top + i));
        }
    }

    private static int luma(int argb) {
        return (int) (0.299 * ((argb >> 16) & 255) + 0.587 * ((argb >> 8) & 255) + 0.114 * (argb & 255));
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    /**
     * The backdrop is translucent, so the contrast between the white modules and the backdrop
     * depends on the picture behind it: the code must decode over black, white, mid-grey and a
     * busy, noisy picture, in every corner and at the smallest size, at the default alpha (no
     * extra alpha for the QR part was needed). Decoded from the whole frame and from the overlay's
     * region framed by some of the picture around it (as a phone camera aims at the code); over
     * per-pixel noise only the framed region, because ZXing's whole-frame finder search drowns in
     * 2 million random pixels (it fails there the same way with a fully opaque backdrop, so it
     * says nothing about contrast).
     */
    @ParameterizedTest
    @ValueSource(strings = {"black", "white", "grey", "noise"})
    void invertedCodeDecodesOverAnyPicture(String background) throws Exception {
        java.util.List<OverlayRenderer.Rendered> renders = new java.util.ArrayList<>();
        for (String corner : new String[] {"br", "bl", "tr", "tl"}) {
            renders.add(render(corner, 22, 3, OutputProfile.P720_30));
            renders.add(render(corner, RoomSettings.MIN_OVERLAY_HEIGHT_PCT, 0, OutputProfile.P720_30));
            renders.add(render(corner, RoomSettings.MIN_OVERLAY_HEIGHT_PCT, 3, OutputProfile.P1080_30));
            renders.add(render(corner, 22, 3, OutputProfile.P1080_30));
        }
        for (OverlayRenderer.Rendered r : renders) {
            String what = r.spec().corner() + " " + r.spec().heightPct() + "% " + r.spec().frameHeight() + "p over "
                    + background;
            BufferedImage f = frame(r, background);
            if (!background.equals("noise")) {
                assertEquals(BASE + "/r/chill-room", decode(f).getText(), what + ", whole frame");
            }
            int pad = r.height() / 2;
            int x0 = Math.max(0, r.x() - pad), y0 = Math.max(0, r.y() - pad);
            int x1 = Math.min(f.getWidth(), r.x() + r.width() + pad), y1 = Math.min(f.getHeight(), r.y() + r.height() + pad);
            assertEquals(BASE + "/r/chill-room", decode(f.getSubimage(x0, y0, x1 - x0, y1 - y0)).getText(),
                    what + ", framed");
        }
    }

    @Test
    void sizesScaleWithTheProfileAndSettings() throws Exception {
        OverlayRenderer.Rendered small = render("br", 10, 0, OutputProfile.P720_30);
        OverlayRenderer.Rendered big = render("br", 40, 0, OutputProfile.P720_30);
        assertTrue(big.height() > small.height() * 2);
        assertEquals(1280, small.x() + small.width());
        assertEquals(720, small.y() + small.height());
        OverlayRenderer.Rendered hd = render("br", 22, 3, OutputProfile.P1080_30);
        OverlayRenderer.Rendered sd = render("br", 22, 3, OutputProfile.P720_30);
        assertTrue(hd.height() > sd.height() * 1.3);
        assertEquals(BASE + "/r/chill-room", decode(frame(hd)).getText());
        assertEquals(BASE + "/r/chill-room", decode(frame(small)).getText());
        // The smallest setting still gives whole-pixel modules that scan.
        OverlayRenderer.Rendered tiny = render("tl", RoomSettings.MIN_OVERLAY_HEIGHT_PCT, 0, OutputProfile.P720_30);
        assertEquals(BASE + "/r/chill-room", decode(frame(tiny)).getText());
    }

    @Test
    void longUrlsShrinkTheTextToFitTheFrame() throws Exception {
        String slug = "a-very-long-room-slug-that-goes-on-and-on-and-on-forever";
        OverlayRenderer.Rendered r = renderer.render(OverlayRenderer.spec(BASE + "/some/deep/prefix", slug,
                settings("bl", 22, 3), OutputProfile.P720_30));
        assertTrue(r.x() + r.width() <= 1280 - 22);
        assertEquals(BASE + "/some/deep/prefix/r/" + slug, decode(frame(r)).getText());
    }

    @Test
    void hashIsStableAndFollowsEverythingThatChangesTheImage() {
        String h = render("br", 22, 3, OutputProfile.P720_30).hash();
        assertEquals(16, h.length());
        assertEquals(h, render("br", 22, 3, OutputProfile.P720_30).hash());
        assertNotEquals(h, render("bl", 22, 3, OutputProfile.P720_30).hash());
        assertNotEquals(h, render("br", 30, 3, OutputProfile.P720_30).hash());
        // Neighbouring sizes that round to the same pixels per module give the same image and hash.
        assertEquals(h, render("br", 23, 3, OutputProfile.P720_30).hash());
        assertNotEquals(h, render("br", 22, 4, OutputProfile.P720_30).hash());
        assertNotEquals(h, render("br", 22, 3, OutputProfile.P1080_30).hash());
        assertNotEquals(h, renderer.render(OverlayRenderer.spec(BASE, "other-room", settings("br", 22, 3),
                OutputProfile.P720_30)).hash());
        assertNotEquals(h, renderer.render(OverlayRenderer.spec("https://other.example.com", "chill-room",
                settings("br", 22, 3), OutputProfile.P720_30)).hash());
    }
}
