package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputProfile;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Renders card images with Java2D at the output profile's size
 * ({@code docs/library-and-ingest.md#cards}): the item card (artwork, title, artist, album) and
 * the server filler cards ({@link Filler}). Encoding the images into 2 s GOPs is the card
 * encoder's job.
 *
 * <p>All sizes are designed for 720 px height and scaled with the profile, so a 1080p30 card is
 * the 720p30 card at 1.5×. Text is white or light grey on a dark background (strong contrast,
 * meaning carried by text labels, never by colour alone). Each text line that is too wide is first
 * shrunk down to a minimum size, then ellipsized at grapheme boundaries ({@link #fit}).
 *
 * <p>Text uses the logical {@code SansSerif} font, whose fallback chain covers the scripts the
 * system has fonts for; a server image must therefore ship fonts (e.g. Noto incl. CJK). Requires
 * {@code java.awt.headless=true} on a server ({@code Main} sets it). Instances are immutable and
 * thread-safe; each call draws into its own image.
 */
public final class CardRenderer {
    /** The server-wide filler cards. Their labels are the visible text. */
    public enum Filler {
        PAUSED("paused", "Paused"),
        QUEUE_EMPTY("idle", "Queue is empty"),
        LOADING("loading", "Loading…");

        private final String id;
        private final String label;

        Filler(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** Stable name for file names, e.g. {@code paused}. */
        public String id() {
            return id;
        }

        /** The text shown on the card. */
        public String label() {
            return label;
        }
    }

    /**
     * What an item card shows. {@code title} falls back to "Untitled" when blank; blank
     * {@code artist}/{@code album} lines are left out; {@code artwork} may be null (a placeholder
     * tile is drawn). Line breaks and control characters in the text are replaced by spaces.
     */
    public record ItemCard(String title, String artist, String album, BufferedImage artwork) {
    }

    /** One fitted line of text: the font size chosen and the (possibly ellipsized) text. */
    record Fitted(String text, Font font, double width, boolean shrunk, boolean ellipsized) {
    }

    static final String FONT_FAMILY = Font.SANS_SERIF;
    static final String ELLIPSIS = "…";
    static final String UNTITLED = "Untitled";

    // Design units (at 720 px height).
    static final double DESIGN_HEIGHT = 720;
    static final double MARGIN = 80;
    static final double ART_SIZE = 440;
    static final double ART_GAP = 64;
    static final double TITLE_SIZE = 56, TITLE_MIN = 32;
    static final double ARTIST_SIZE = 40, ARTIST_MIN = 26;
    static final double ALBUM_SIZE = 32, ALBUM_MIN = 22;
    static final double LINE_GAP = 14;
    static final double FILLER_SIZE = 72, FILLER_MIN = 36;
    static final double SUBTITLE_SIZE = 40, SUBTITLE_MIN = 24;
    static final double ICON_SIZE = 120;

    static final Color BG_TOP = new Color(0x1c1c24);
    static final Color BG_BOTTOM = new Color(0x0b0b0f);
    static final Color TILE = new Color(0x2c2c36);
    static final Color TEXT = Color.WHITE;
    static final Color TEXT_DIM = new Color(0xd0d0d8);
    static final Color ICON = new Color(0xe8e8ee);
    /** How much the blurred artwork background is darkened (alpha of black over it). */
    static final float BACKDROP_DIM = 0.72f;

    private final OutputProfile profile;
    private final int width;
    private final int height;
    private final double scale;

    public CardRenderer(OutputProfile profile) {
        this.profile = Objects.requireNonNull(profile);
        this.width = profile.width();
        this.height = profile.height();
        this.scale = height / DESIGN_HEIGHT;
    }

    public OutputProfile profile() {
        return profile;
    }

    /** Renders an item card: artwork on the left, title/artist/album to its right. */
    public BufferedImage renderItem(ItemCard item) {
        Objects.requireNonNull(item);
        BufferedImage image = newImage();
        Graphics2D g = graphics(image);
        try {
            BufferedImage art = item.artwork() == null ? null : toRgb(item.artwork());
            if (art != null) {
                drawBackdrop(g, art);
            } else {
                drawBackground(g);
            }

            double margin = MARGIN * scale;
            int artSize = (int) Math.round(ART_SIZE * scale);
            int artX = (int) Math.round(margin);
            int artY = (height - artSize) / 2;
            drawArtwork(g, art, artX, artY, artSize);

            double textX = artX + artSize + ART_GAP * scale;
            double maxWidth = width - margin - textX;
            FontRenderContext frc = g.getFontRenderContext();

            List<Fitted> lines = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            String title = clean(item.title());
            lines.add(fit(title.isEmpty() ? UNTITLED : title, Font.BOLD, TITLE_SIZE * scale, TITLE_MIN * scale, maxWidth, frc));
            colors.add(TEXT);
            String artist = clean(item.artist());
            if (!artist.isEmpty()) {
                lines.add(fit(artist, Font.PLAIN, ARTIST_SIZE * scale, ARTIST_MIN * scale, maxWidth, frc));
                colors.add(TEXT);
            }
            String album = clean(item.album());
            if (!album.isEmpty()) {
                lines.add(fit(album, Font.PLAIN, ALBUM_SIZE * scale, ALBUM_MIN * scale, maxWidth, frc));
                colors.add(TEXT_DIM);
            }
            drawLinesCentered(g, lines, colors, textX, false, height / 2.0);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Renders one of the server filler cards. */
    public BufferedImage renderFiller(Filler filler) {
        return renderMessage(filler, null);
    }

    /**
     * A filler card with an optional second line under the label (e.g. a room's name on its idle
     * card). Blank {@code subtitle} means none.
     */
    public BufferedImage renderMessage(Filler filler, String subtitle) {
        Objects.requireNonNull(filler);
        BufferedImage image = newImage();
        Graphics2D g = graphics(image);
        try {
            drawBackground(g);
            FontRenderContext frc = g.getFontRenderContext();
            double maxWidth = width - 2 * MARGIN * scale;
            List<Fitted> lines = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            lines.add(fit(filler.label(), Font.BOLD, FILLER_SIZE * scale, FILLER_MIN * scale, maxWidth, frc));
            colors.add(TEXT);
            String sub = clean(subtitle);
            if (!sub.isEmpty()) {
                lines.add(fit(sub, Font.PLAIN, SUBTITLE_SIZE * scale, SUBTITLE_MIN * scale, maxWidth, frc));
                colors.add(TEXT_DIM);
            }

            double iconSize = ICON_SIZE * scale;
            double gap = 2 * LINE_GAP * scale;
            double textHeight = blockHeight(lines);
            double top = (height - (iconSize + gap + textHeight)) / 2;
            drawIcon(g, filler, width / 2.0, top + iconSize / 2, iconSize);
            drawLinesCentered(g, lines, colors, width / 2.0, true, top + iconSize + gap + textHeight / 2);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Writes an image as PNG (atomicity is the caller's business). */
    public static void writePng(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("no PNG writer");
        }
    }

    /**
     * Reads artwork with ImageIO (PNG, JPEG, GIF, BMP), or returns null if no installed reader
     * understands the file (e.g. WebP, which the caller must convert first, for example with
     * ffmpeg).
     */
    public static BufferedImage readImage(Path file) throws IOException {
        return ImageIO.read(file.toFile());
    }

    // ---- text fitting ----

    /**
     * Fits {@code text} into {@code maxWidth}: the largest whole size from {@code size} down to
     * {@code minSize} at which it fits; if it doesn't fit even at {@code minSize}, it is cut at a
     * grapheme boundary and {@link #ELLIPSIS} appended (trailing whitespace before it removed).
     */
    static Fitted fit(String text, int style, double size, double minSize, double maxWidth, FontRenderContext frc) {
        Font base = new Font(FONT_FAMILY, style, 1);
        double s = Math.round(size);
        double min = Math.min(s, Math.round(minSize));
        while (true) {
            Font font = base.deriveFont((float) s);
            double w = advance(font, text, frc);
            if (w <= maxWidth) {
                return new Fitted(text, font, w, s < Math.round(size), false);
            }
            if (s <= min) {
                return ellipsize(text, font, maxWidth, frc);
            }
            s = Math.max(min, s - 1);
        }
    }

    private static Fitted ellipsize(String text, Font font, double maxWidth, FontRenderContext frc) {
        List<Integer> bounds = new ArrayList<>();
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(text);
        for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
            bounds.add(b);
        }
        // Largest prefix (in graphemes) whose ellipsized form fits; at least the ellipsis alone.
        int lo = 0, hi = bounds.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (advance(font, withEllipsis(text, bounds.get(mid)), frc) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        String result = withEllipsis(text, bounds.get(lo));
        return new Fitted(result, font, advance(font, result, frc), true, true);
    }

    private static String withEllipsis(String text, int end) {
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    static double advance(Font font, String text, FontRenderContext frc) {
        return text.isEmpty() ? 0 : font.getStringBounds(text, frc).getWidth();
    }

    /** Null-safe; control characters (incl. line breaks) become spaces, runs of spaces collapse. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ws = Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c);
            if (ws) {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) {
                sb.append(' ');
            }
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- drawing ----

    private BufferedImage newImage() {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Greyscale AA: subpixel (LCD) text would leave colour fringes after chroma subsampling.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private void drawBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, height, BG_BOTTOM));
        g.fillRect(0, 0, width, height);
    }

    /** The artwork, cover-scaled to the frame, heavily blurred (tiny then upscaled) and darkened. */
    private void drawBackdrop(Graphics2D g, BufferedImage art) {
        int tw = 32, th = Math.max(1, (int) Math.round(32.0 * height / width));
        BufferedImage tiny = cover(art, tw, th);
        Graphics2D bg = (Graphics2D) g.create();
        try {
            bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            bg.drawImage(tiny, 0, 0, width, height, null);
            bg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, BACKDROP_DIM));
            bg.setColor(Color.BLACK);
            bg.fillRect(0, 0, width, height);
        } finally {
            bg.dispose();
        }
    }

    private void drawArtwork(Graphics2D g, BufferedImage art, int x, int y, int size) {
        double arc = 16 * scale;
        RoundRectangle2D tile = new RoundRectangle2D.Double(x, y, size, size, arc, arc);
        Graphics2D a = (Graphics2D) g.create();
        try {
            a.setColor(TILE);
            a.fill(tile);
            if (art != null) {
                a.setClip(tile);
                a.drawImage(cover(art, size, size), x, y, null);
            } else {
                drawNote(a, x + size / 2.0, y + size / 2.0, size * 0.42);
            }
        } finally {
            a.dispose();
        }
    }

    /** A simple beamed-eighth-notes placeholder glyph, drawn from shapes (no font needed). */
    private void drawNote(Graphics2D g, double cx, double cy, double size) {
        g.setColor(new Color(0x8a8a98));
        double headW = size * 0.34, headH = size * 0.24;
        double stem = size * 0.07;
        double left = cx - size * 0.45, right = cx + size * 0.25;
        double bottom = cy + size * 0.38, top = cy - size * 0.42;
        for (double hx : new double[] {left, right}) {
            g.fill(new Ellipse2D.Double(hx, bottom - headH, headW, headH));
            g.fill(new Rectangle2D.Double(hx + headW - stem, top, stem, bottom - headH / 2 - top));
        }
        g.fill(new Rectangle2D.Double(left + headW - stem, top, right - left + stem, size * 0.12));
    }

    private void drawIcon(Graphics2D g, Filler filler, double cx, double cy, double size) {
        Graphics2D i = (Graphics2D) g.create();
        try {
            i.setColor(ICON);
            switch (filler) {
                case PAUSED -> {
                    double barW = size * 0.26, barH = size * 0.8, gap = size * 0.18, arc = barW * 0.4;
                    i.fill(new RoundRectangle2D.Double(cx - gap / 2 - barW, cy - barH / 2, barW, barH, arc, arc));
                    i.fill(new RoundRectangle2D.Double(cx + gap / 2, cy - barH / 2, barW, barH, arc, arc));
                }
                case QUEUE_EMPTY -> {
                    // An empty list: three lines with bullets, the last one faint.
                    float stroke = (float) (size * 0.08);
                    i.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    double w = size * 0.8, x0 = cx - w / 2;
                    for (int n = 0; n < 3; n++) {
                        double y = cy - size * 0.3 + n * size * 0.3;
                        if (n == 2) {
                            i.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
                        }
                        double dot = stroke * 1.5;
                        i.fill(new Ellipse2D.Double(x0 - dot / 2, y - dot / 2, dot, dot));
                        i.draw(new Line2D.Double(x0 + size * 0.2, y, x0 + w, y));
                    }
                }
                case LOADING -> {
                    // A still spinner: 8 dots in a ring, fading round.
                    double r = size * 0.38, dot = size * 0.16;
                    for (int n = 0; n < 8; n++) {
                        double angle = -Math.PI / 2 + n * Math.PI / 4;
                        float alpha = 1f - n * 0.1f;
                        i.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                        i.fill(new Ellipse2D.Double(cx + r * Math.cos(angle) - dot / 2, cy + r * Math.sin(angle) - dot / 2, dot, dot));
                    }
                }
            }
        } finally {
            i.dispose();
        }
    }

    private double blockHeight(List<Fitted> lines) {
        double h = 0;
        for (int n = 0; n < lines.size(); n++) {
            Font f = lines.get(n).font();
            h += f.getSize2D() * 1.2;
            if (n > 0) {
                h += LINE_GAP * scale;
            }
        }
        return h;
    }

    /**
     * Draws lines stacked, the block vertically centred on {@code centerY}. Each line occupies
     * 1.2 × its font size, baseline at 0.95 × size from the line top (a stable, font-independent
     * layout, so fallback fonts with odd metrics don't move things).
     */
    private void drawLinesCentered(Graphics2D g, List<Fitted> lines, List<Color> colors, double x, boolean centerX,
                                   double centerY) {
        double y = centerY - blockHeight(lines) / 2;
        for (int n = 0; n < lines.size(); n++) {
            Fitted line = lines.get(n);
            double size = line.font().getSize2D();
            g.setFont(line.font());
            g.setColor(colors.get(n));
            double lx = centerX ? x - line.width() / 2 : x;
            g.drawString(line.text(), (float) lx, (float) (y + size * 0.95));
            y += size * 1.2 + LINE_GAP * scale;
        }
    }

    // ---- images ----

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(TILE); // transparent artwork shows on the tile colour
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Scales {@code src} to cover {@code w}×{@code h} (preserving aspect, centre crop). Square art
     * on a 16:9 YouTube thumbnail with black bars comes out as just the art. Large reductions go
     * in halving steps, so the result isn't aliased.
     */
    static BufferedImage cover(BufferedImage src, int w, int h) {
        double s = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        double cropW = w / s, cropH = h / s;
        int cx = (int) Math.round((src.getWidth() - cropW) / 2);
        int cy = (int) Math.round((src.getHeight() - cropH) / 2);
        BufferedImage cur = src.getSubimage(Math.max(0, cx), Math.max(0, cy),
                Math.min(src.getWidth(), Math.max(1, (int) Math.round(cropW))),
                Math.min(src.getHeight(), Math.max(1, (int) Math.round(cropH))));
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return resize(cur, w, h);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
