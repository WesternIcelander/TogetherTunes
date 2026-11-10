package io.siggi.togethertunes.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OverlayRendererTest {
    private static final String BASE = "https://tunes.example.com";
    private final OverlayRenderer renderer = new OverlayRenderer();

    static RoomSettings settings(String corner, int heightPct, int margin) {
        return RoomSettings.DEFAULTS.patch(JsonParser.parseString("{\"overlay\":{\"enabled\":true,\"corner\":\""
                + corner + "\",\"heightPct\":" + heightPct + ",\"margin\":" + margin + "}}").getAsJsonObject());
    }

    private OverlayRenderer.Rendered render(String corner, int heightPct, int margin, OutputProfile profile) {
        return renderer.render(OverlayRenderer.spec(BASE, "chill-room", settings(corner, heightPct, margin), profile));
    }

    /** The overlay composited onto a mid-grey frame, as the stream would show it. */
    private static BufferedImage frame(OverlayRenderer.Rendered r) {
        BufferedImage f = new BufferedImage(r.spec().frameWidth(), r.spec().frameHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = f.createGraphics();
        g.setColor(new Color(0x808890));
        g.fillRect(0, 0, f.getWidth(), f.getHeight());
        g.drawImage(r.image(), r.x(), r.y(), null);
        g.dispose();
        return f;
    }

    static Result decode(BufferedImage image) throws Exception {
        int[] px = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(image.getWidth(), image.getHeight(), px)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, true));
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
    void cornersPlaceThePanelAndTheTextFacingTheMiddle(String corner) throws Exception {
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

        // The QR's white square and the text rows (white-ish pixels outside it).
        BufferedImage img = r.image();
        int[] qr = whiteSquare(img);
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
        // Aligned to the QR code's edge (within a few pixels of glyph side bearing).
        if (right) {
            assertTrue(Math.abs(textRight - (qrLeft + qrSide - 1)) <= 6, "right aligned: " + textRight + " vs " + (qrLeft + qrSide));
        } else {
            assertTrue(Math.abs(textLeft - qrLeft) <= 6, "left aligned: " + textLeft + " vs " + qrLeft);
        }
        // The panel is semi-opaque: a corner pixel is transparent (rounded), an edge pixel half-covering.
        assertEquals(0, alpha(img.getRGB(0, 0)));
        int edge = alpha(img.getRGB(img.getWidth() / 2, right ? 1 : 1));
        assertTrue(edge > 100 && edge < 255, "semi-opaque panel, alpha " + edge);
    }

    /** {left, top, side} of the largest opaque white square (the QR code with its quiet zone). */
    private static int[] whiteSquare(BufferedImage img) {
        // The quiet zone's top-left module is white; scan for the first fully white opaque pixel run.
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                if (p == 0xffffffff) {
                    int run = 0;
                    while (x + run < img.getWidth() && img.getRGB(x + run, y) == 0xffffffff) {
                        run++;
                    }
                    if (run > 20) {
                        return new int[] {x, y, run};
                    }
                    x += run;
                }
            }
        }
        throw new AssertionError("no QR code");
    }

    private static int luma(int argb) {
        return (int) (0.299 * ((argb >> 16) & 255) + 0.587 * ((argb >> 8) & 255) + 0.114 * (argb & 255));
    }

    private static int alpha(int argb) {
        return argb >>> 24;
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
