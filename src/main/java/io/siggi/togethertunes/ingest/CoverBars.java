package io.siggi.togethertunes.ingest;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Trims uniform bars off a cover ({@code docs/library-and-ingest.md#cards}, "Letterbox bars"): a
 * YouTube thumbnail is 16:9 (or 4:3 for {@code hqdefault}), so a song upload's square art arrives
 * between bars, and a 4:3 thumbnail of a 16:9 video has bars top and bottom as well.
 *
 * <p>From each edge inward, a row or column is part of a bar while (nearly) all its pixels are
 * within {@link #TOLERANCE} per channel of the edge line's colour (any colour, not only black; up to
 * {@link #outliers} pixels may differ, for JPEG noise). Bars count only in pairs, as a letterbox or
 * pillarbox has them: both opposite edges have one, of the same colour and about the same width
 * ({@link #MAX_UNEVEN}), each at least {@link #MIN_BAR} of the original size. A one-colour area on
 * one side only (a dark sky, a poster's black top, a plain background) is part of the picture: on
 * Siggi's library, one-sided "bars" were always content. This repeats on what is left until nothing changes (4:3 → 16:9 → square). A
 * crop happens only when at least one pair was found and what is left is at least {@link #MIN_KEEP}
 * of the original's width and height; a picture that is all one colour is left alone. Never upscales.
 */
public final class CoverBars {
    /** Per-channel difference (0..255) a bar pixel may have from the bar's colour. */
    static final int TOLERANCE = 20;
    /** The smallest bar, as a fraction of the original width or height. */
    static final double MIN_BAR = 0.02;
    /**
     * How much two opposite bars may differ in width, as a fraction of the wider one (a letterbox is
     * centred; JPEG blur and a logo near the edge make them a little uneven).
     */
    static final double MAX_UNEVEN = 0.25;
    /** ... and in pixels at least. */
    static final int MAX_UNEVEN_PX = 3;
    /** The smallest picture that may be left, as a fraction of the original width and height. */
    static final double MIN_KEEP = 0.40;
    /** JPEG quality of a rewritten cover (ffmpeg's {@code -q:v 2} is about as good). */
    private static final float JPEG_QUALITY = 0.92f;

    private CoverBars() {
    }

    /**
     * The part of {@code image} without its bars, or null when nothing is to be cropped (no bar, too
     * little left, or one colour throughout).
     */
    public static Rectangle bounds(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < 3 || h < 3) {
            return null;
        }
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        int minBarX = Math.max(1, (int) Math.ceil(w * MIN_BAR));
        int minBarY = Math.max(1, (int) Math.ceil(h * MIN_BAR));
        int x0 = 0;
        int x1 = w;
        int y0 = 0;
        int y1 = h;
        while (true) {
            // rows: top and bottom, across the current width
            int[] top = bar(px, w, y0, y1, x0, x1, true, false);
            int[] bottom = bar(px, w, y0, y1, x0, x1, true, true);
            int[] left = bar(px, w, x0, x1, y0, y1, false, false);
            int[] right = bar(px, w, x0, x1, y0, y1, false, true);
            int[] tb = pair(top, bottom, minBarY);
            int[] lr = pair(left, right, minBarX);
            if (top != null && top[0] >= y1 - y0 || left != null && left[0] >= x1 - x0
                    || tb[0] + tb[1] >= y1 - y0 || lr[0] + lr[1] >= x1 - x0) {
                // what is left is one colour: a picture that is all one colour is left as it is,
                // and one-colour art between bars keeps the crop so far
                break;
            }
            if (tb[0] + tb[1] + lr[0] + lr[1] == 0) {
                break;
            }
            y0 += tb[0];
            y1 -= tb[1];
            x0 += lr[0];
            x1 -= lr[1];
        }
        if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) {
            return null;
        }
        if (x1 - x0 < w * MIN_KEEP || y1 - y0 < h * MIN_KEEP) {
            return null;
        }
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    /**
     * The bar widths that count on one axis: {@code {first, second}}, or {@code {0, 0}} unless both
     * edges have a bar of at least {@code minBar}, of the same colour and about the same width.
     */
    private static int[] pair(int[] a, int[] b, int minBar) {
        if (a == null || b == null || a[0] < minBar || b[0] < minBar || !close(a[1], b[1])
                || Math.abs(a[0] - b[0]) > Math.max(MAX_UNEVEN_PX, Math.max(a[0], b[0]) * MAX_UNEVEN)) {
            return new int[] {0, 0};
        }
        return new int[] {a[0], b[0]};
    }

    /**
     * The bar at one edge of the region: {@code {lines, colour}}, or null when the edge line isn't
     * uniform. {@code rows}: scans rows (lines {@code from..to} are y, {@code a..b} the x range),
     * else columns. {@code fromEnd}: from the last line inward.
     */
    private static int[] bar(int[] px, int stride, int from, int to, int a, int b, boolean rows, boolean fromEnd) {
        int edge = fromEnd ? to - 1 : from;
        int colour = median(px, stride, edge, a, b, rows);
        int n = 0;
        int max = to - from;
        while (n < max) {
            int line = fromEnd ? to - 1 - n : from + n;
            if (!uniform(px, stride, line, a, b, rows, colour)) {
                break;
            }
            n++;
        }
        return n == 0 ? null : new int[] {n, colour};
    }

    /** How many pixels of a line of {@code length} may differ from the bar's colour. */
    static int outliers(int length) {
        return Math.max(2, length / 100);
    }

    private static boolean uniform(int[] px, int stride, int line, int a, int b, boolean rows, int colour) {
        int allowed = outliers(b - a);
        int off = 0;
        for (int i = a; i < b; i++) {
            int p = rows ? px[line * stride + i] : px[i * stride + line];
            if (!close(p, colour) && ++off > allowed) {
                return false;
            }
        }
        return true;
    }

    /** The per-channel median colour of a line. */
    private static int median(int[] px, int stride, int line, int a, int b, boolean rows) {
        int n = b - a;
        int[] r = new int[n];
        int[] g = new int[n];
        int[] bl = new int[n];
        for (int i = 0; i < n; i++) {
            int p = rows ? px[line * stride + a + i] : px[(a + i) * stride + line];
            r[i] = (p >> 16) & 0xFF;
            g[i] = (p >> 8) & 0xFF;
            bl[i] = p & 0xFF;
        }
        Arrays.sort(r);
        Arrays.sort(g);
        Arrays.sort(bl);
        return (r[n / 2] << 16) | (g[n / 2] << 8) | bl[n / 2];
    }

    private static boolean close(int p, int q) {
        return Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) <= TOLERANCE
                && Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) <= TOLERANCE
                && Math.abs((p & 0xFF) - (q & 0xFF)) <= TOLERANCE;
    }

    /** {@code image} without its bars (a new RGB image), or {@code image} itself when there are none. */
    public static BufferedImage trim(BufferedImage image) {
        Rectangle r = bounds(image);
        if (r == null) {
            return image;
        }
        BufferedImage out = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, -r.x, -r.y, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A cover read and, if it had bars, trimmed: {@code image} as it is now; {@code from} its size before (null: unchanged). */
    public record Result(BufferedImage image, Rectangle from) {
        public boolean trimmed() {
            return from != null;
        }

        /** For logs: {@code 1280x720 -> 720x720}. */
        public String describe() {
            return from.width + "x" + from.height + " -> " + image.getWidth() + "x" + image.getHeight();
        }
    }

    /**
     * Reads the JPEG {@code file} and, when it has bars, rewrites it without them (through a temporary
     * file next to it, so a reader never sees half a file).
     *
     * @return the image as the file has it now, or null when it can't be read
     */
    public static Result trimFile(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            return null;
        }
        BufferedImage trimmed = trim(image);
        if (trimmed == image) {
            return new Result(image, null);
        }
        Path tmp = file.resolveSibling("." + file.getFileName() + ".trim-" + java.util.UUID.randomUUID() + ".jpg");
        try {
            writeJpeg(trimmed, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return new Result(trimmed, new Rectangle(0, 0, image.getWidth(), image.getHeight()));
    }

    /** Writes {@code image} as a JPEG of the quality covers have. */
    public static void writeJpeg(BufferedImage image, Path out) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG writer");
        }
        ImageWriter writer = writers.next();
        try (java.io.OutputStream os = Files.newOutputStream(out);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
