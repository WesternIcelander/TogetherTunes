package io.siggi.togethertunes.overlay;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.awt.Color;
import io.siggi.togethertunes.fonts.CardFonts;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a room's QR overlay ({@code docs/overlay.md#the-overlay-image}) with Java2D: a QR code
 * for {@code {publicBaseUrl}/r/{slug}} (ZXing, error correction M, 4-module quiet zone, black on
 * white) and the line "Requests at {url}" (the URL without its scheme), on a rounded,
 * semi-opaque dark panel. The text goes on the side of the QR code that faces the middle of the
 * frame (below it in the bottom corners, above it in the top corners), aligned to the same edge.
 *
 * <p>The result is the panel only (ARGB, transparent outside the rounded corners) plus where it
 * goes in the frame ({@link Rendered#x()}, {@link Rendered#y()}), so cards composite it and the
 * music-video encoder overlays it at that position. Position and size are even (4:2:0 chroma).
 *
 * <p>Sizes: {@code overlay.heightPct} is the side of the QR code's white square (quiet zone
 * included) in % of the frame height, rounded down to whole pixels per module (at least
 * {@value #MIN_MODULE_PX}) so the modules stay sharp (neighbouring percentages can therefore give
 * the same image and the same {@code overlayHash}); {@code overlay.margin} is the panel's
 * distance from the two nearest frame edges, in % of the frame height. The text is sized from the QR code and shrunk to fit the
 * frame width (never cut: a shortened URL would be useless).
 *
 * <p>Instances are stateless and thread-safe. Requires {@code java.awt.headless=true} on a server.
 */
public final class OverlayRenderer {
    /**
     * Version of the drawing code. It is part of every {@link Spec}, so a change in how the overlay
     * looks makes stored renders (and through {@code overlayHash}, every cache made from them)
     * stale. Bump it whenever the output of {@link #render} changes for the same spec.
     */
    public static final int RENDER_VERSION = 1;

    static final int QUIET_ZONE = 4;
    /** Fewer pixels per module don't survive scaling and video compression. */
    static final int MIN_MODULE_PX = 2;
    static final String TEXT_PREFIX = "Requests at ";
    /** Text size as a fraction of the QR code's side, and the floor in fractions of the frame height. */
    static final double TEXT_PER_QR = 0.16;
    static final double TEXT_MIN_PER_HEIGHT = 0.03;
    /** Below this (fraction of the frame height) the text isn't shrunk any further to fit. */
    static final double TEXT_FLOOR_PER_HEIGHT = 0.015;
    /** Panel padding around the content, as a fraction of the QR code's side. */
    static final double PAD_PER_QR = 0.07;
    static final Color PANEL = new Color(0, 0, 0, 176);
    static final Color TEXT = Color.WHITE;

    /**
     * Everything the overlay image depends on. Two equal specs render the same image.
     *
     * @param url         the QR code's content, {@code {publicBaseUrl}/r/{slug}}
     * @param corner      {@code br}, {@code bl}, {@code tr} or {@code tl}
     * @param heightPct   QR code side in % of the frame height
     * @param margin      distance from the frame edges in % of the frame height
     * @param frameWidth  output frame width
     * @param frameHeight output frame height
     * @param version     {@link #RENDER_VERSION} at the time
     */
    public record Spec(String url, String corner, int heightPct, int margin, int frameWidth, int frameHeight,
                       int version) {
        public Spec {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(corner, "corner");
            if (!corner.equals("br") && !corner.equals("bl") && !corner.equals("tr") && !corner.equals("tl")) {
                throw new IllegalArgumentException("corner " + corner);
            }
            if (frameWidth <= 0 || frameHeight <= 0 || heightPct <= 0 || margin < 0) {
                throw new IllegalArgumentException("bad overlay geometry");
            }
        }

        /** The text line under (or above) the QR code. */
        public String text() {
            return TEXT_PREFIX + displayUrl(url);
        }

        boolean bottom() {
            return corner.charAt(0) == 'b';
        }

        boolean right() {
            return corner.charAt(1) == 'r';
        }

        /** A stable one-line form, stored next to the image to tell whether it is still current. */
        public String key() {
            // The fonts the text is drawn with (docs/library-and-ingest.md#card-fonts): another card
            // font or a newly downloaded one for its characters renders the overlay again.
            return version + "|" + frameWidth + "x" + frameHeight + "|" + corner + "|" + heightPct + "|" + margin
                    + "|" + url + "|" + CardFonts.installed().key(text());
        }
    }

    /**
     * A rendered overlay.
     *
     * @param image the panel, {@code TYPE_INT_ARGB}; callers must not modify it
     * @param x     left edge in the frame
     * @param y     top edge in the frame
     * @param hash  {@code overlayHash}: see {@link #hash}
     */
    public record Rendered(Spec spec, BufferedImage image, int x, int y, String hash) {
        public int width() {
            return image.getWidth();
        }

        public int height() {
            return image.getHeight();
        }
    }

    /** The spec for a room: its slug, its overlay settings, the server's public URL and the output profile. */
    public static Spec spec(String publicBaseUrl, String slug, RoomSettings settings, OutputProfile profile) {
        return new Spec(shortUrl(publicBaseUrl, slug), settings.overlayCorner(), settings.overlayHeightPct(),
                settings.overlayMargin(), profile.width(), profile.height(), RENDER_VERSION);
    }

    /** {@code {publicBaseUrl}/r/{slug}} (a trailing slash on the base URL is dropped). */
    public static String shortUrl(String publicBaseUrl, String slug) {
        String base = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/r/" + Objects.requireNonNull(slug, "slug");
    }

    /** The URL as shown in the text: without {@code https://} or {@code http://}. */
    public static String displayUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String scheme : new String[] {"https://", "http://"}) {
            if (lower.startsWith(scheme)) {
                return url.substring(scheme.length());
            }
        }
        return url;
    }

    /** The QR code's modules, quiet zone included ({@code true} = dark). */
    static BitMatrix qr(String url) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(url)) {
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        }
        try {
            // Size 0: one pixel per module, so the matrix is exactly the code plus its quiet zone.
            return new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("can't encode the overlay URL as a QR code", e);
        }
    }

    /** Renders the overlay for {@code spec}. */
    public Rendered render(Spec spec) {
        Objects.requireNonNull(spec);
        int frameW = spec.frameWidth(), frameH = spec.frameHeight();
        BitMatrix matrix = qr(spec.url());
        int modules = matrix.getWidth();
        int target = (int) Math.round(frameH * spec.heightPct() / 100.0);
        int modulePx = Math.max(MIN_MODULE_PX, target / modules);
        int qrSide = modules * modulePx;

        int margin = even((int) Math.round(frameH * spec.margin() / 100.0));
        int pad = Math.max(4, (int) Math.round(qrSide * PAD_PER_QR));
        int gap = Math.max(2, (int) Math.round(pad * 0.6));

        // The text: sized from the QR code, shrunk to fit inside the frame's margins.
        FontRenderContext frc = fontContext();
        String text = spec.text();
        CardFonts fonts = CardFonts.installed();
        double size = Math.max(qrSide * TEXT_PER_QR, frameH * TEXT_MIN_PER_HEIGHT);
        double maxTextWidth = frameW - 2.0 * margin - 2.0 * pad;
        double width = fonts.advance(text, Font.BOLD, (float) size, frc);
        if (width > maxTextWidth) {
            size = Math.max(frameH * TEXT_FLOOR_PER_HEIGHT, Math.floor(size * maxTextWidth / width));
            while (size > frameH * TEXT_FLOOR_PER_HEIGHT
                    && fonts.advance(text, Font.BOLD, (float) size, frc) > maxTextWidth) {
                size -= 1;
            }
        }
        double textWidth = fonts.advance(text, Font.BOLD, (float) size, frc);
        double lineHeight = size * 1.2;

        int panelW = even((int) Math.ceil(Math.max(qrSide, textWidth) + 2.0 * pad));
        int panelH = even((int) Math.ceil(qrSide + gap + lineHeight + 2.0 * pad));
        panelW = Math.min(panelW, even(frameW - 2 * margin));
        int x = spec.right() ? frameW - margin - panelW : margin;
        int y = spec.bottom() ? frameH - margin - panelH : margin;
        x = Math.max(0, x - (x & 1));
        y = Math.max(0, y - (y & 1));

        BufferedImage image = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Greyscale text AA: subpixel AA would leave colour fringes after chroma subsampling.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            double arc = pad * 2.0;
            g.setColor(PANEL);
            g.fill(new RoundRectangle2D.Double(0, 0, panelW, panelH, arc, arc));

            int qrX = spec.right() ? panelW - pad - qrSide : pad;
            int qrY = spec.bottom() ? pad : panelH - pad - qrSide;
            // Modules on whole pixels, no antialiasing: crisp edges for scanning.
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(qrX, qrY, qrSide, qrSide);
            g.setColor(Color.BLACK);
            for (int my = 0; my < modules; my++) {
                for (int mx = 0; mx < modules; mx++) {
                    if (matrix.get(mx, my)) {
                        g.fillRect(qrX + mx * modulePx, qrY + my * modulePx, modulePx, modulePx);
                    }
                }
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            double lineTop = spec.bottom() ? qrY + qrSide + gap : qrY - gap - lineHeight;
            double textX = spec.right() ? panelW - pad - textWidth : pad;
            g.setColor(TEXT);
            fonts.draw(g, text, Font.BOLD, (float) size, (float) textX, (float) (lineTop + size * 0.95));
        } finally {
            g.dispose();
        }
        return new Rendered(spec, image, x, y, hash(image, x, y, frameW, frameH));
    }

    /**
     * {@code overlayHash}: SHA-256 over the frame size, the position and the pixels (ARGB) of the
     * image, as 16 hex digits. Equal images at the same place give the same hash, also after a PNG
     * round trip.
     */
    public static String hash(BufferedImage image, int x, int y, int frameWidth, int frameHeight) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int w = image.getWidth(), h = image.getHeight();
        ByteBuffer head = ByteBuffer.allocate(6 * 4);
        head.putInt(frameWidth).putInt(frameHeight).putInt(x).putInt(y).putInt(w).putInt(h);
        md.update("tt-overlay".getBytes(StandardCharsets.US_ASCII));
        md.update(head.array());
        int[] row = new int[w];
        ByteBuffer bytes = ByteBuffer.allocate(w * 4);
        for (int r = 0; r < h; r++) {
            image.getRGB(0, r, w, 1, row, 0, w);
            bytes.clear();
            bytes.asIntBuffer().put(row);
            md.update(bytes.array());
        }
        return HexFormat.of().formatHex(md.digest(), 0, 8);
    }

    private static int even(int v) {
        return v + (v & 1);
    }

    private static FontRenderContext fontContext() {
        return new FontRenderContext(null, true, true);
    }
}
