package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.auth.Permission;
import io.siggi.togethertunes.auth.Permissions;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.output.FillerSource.Filler;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Custom card images ({@code docs/media-types.md#custom-card-images}): images that replace the
 * filler screens of the program output (paused, queue empty, loading/next-up and the transition
 * gap's blank card). An admin sets one per screen server-wide ({@code <data>/cards/custom/}); a room
 * owner whose role has {@code rooms.customCards} sets their own per room
 * ({@code rooms/<roomId>/custom-cards/}), which overrides the server's.
 *
 * <ul>
 *   <li><b>Files:</b> the original as uploaded, {@code <screen>-<hash>.png} or {@code .jpg}, where
 *       {@code hash} is the first 16 hex digits of the file's SHA-256 (so a card made from it can be
 *       named after it without reading it again). Replacing an image writes the new file, then
 *       deletes the old one.</li>
 *   <li><b>Accepted:</b> PNG or JPEG (by content, not name), at most {@link #MAX_BYTES} bytes and
 *       {@link #MAX_PIXELS} pixels ({@link #MAX_SIDE} per side), and it must decode.</li>
 *   <li><b>Spec</b> ({@link #warnings}): 16:9 and at least the output resolution. An image that
 *       isn't is accepted with warnings; it is drawn scaled to fit with black bars
 *       ({@link #fit}). Warnings are computed for the output profile in use whenever they are
 *       asked, so they follow an encoding change.</li>
 *   <li><b>Which image applies</b> ({@link #effective}): the room's, while the room's owner holds
 *       {@code rooms.customCards} ({@link RoomCheck}); else the server's; else the built-in card.</li>
 * </ul>
 *
 * <p>The cards themselves are made by {@link OverlayCards} like the overlaid filler cards (same
 * cache and {@code .key} fingerprints), with the QR overlay drawn on top by the room's mode.
 *
 * <p>Thread-safe: changes are serialised, lookups only list a directory.
 */
public final class CustomCards {
    /** The largest file accepted. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    /** The largest side accepted, in pixels. */
    public static final int MAX_SIDE = 8192;
    /** The most pixels accepted (8K UHD). */
    public static final long MAX_PIXELS = 7680L * 4320;
    /** How far the aspect ratio may be from 16:9 (relative) and still count as 16:9 (e.g. 1366×768). */
    static final double ASPECT_TOLERANCE = 0.01;
    /** {@code rooms/<roomId>/<dir>}. */
    static final String ROOM_DIR = "custom-cards";

    private static final Pattern FILE = Pattern.compile("([a-z]+)-([0-9a-f]{16})\\.(png|jpg)");
    private static final Pattern SAFE_ROOM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A screen whose card can be replaced. */
    public enum Screen {
        PAUSED("paused", "Paused", Filler.PAUSED),
        IDLE("idle", "Queue is empty", Filler.IDLE),
        LOADING("loading", "Loading / next up", Filler.LOADING),
        GAP("gap", "Gap between items", Filler.GAP);

        private final String id;
        private final String label;
        private final Filler filler;

        Screen(String id, String label, Filler filler) {
            this.id = id;
            this.label = label;
            this.filler = filler;
        }

        /** The name in the API and in file names. */
        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** The program output's filler this image replaces. */
        public Filler filler() {
            return filler;
        }

        /** The screen with this id, or null. */
        public static Screen byId(String id) {
            for (Screen s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** A stored image. */
    public record Image(Screen screen, Path file, String hash, String format, int width, int height, long size,
            long uploadedAt) {
        /** {@code image/png} or {@code image/jpeg}. */
        public String contentType() {
            return format.equals("png") ? "image/png" : "image/jpeg";
        }
    }

    /** A spec warning: what is wrong and what is expected. */
    public record Warning(String code, String message) {
    }

    /** Thrown for a file that isn't an acceptable image. */
    public static final class InvalidImageException extends Exception {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    /** Whether a room's own images apply now (its owner holds {@code rooms.customCards}). */
    @FunctionalInterface
    public interface RoomCheck {
        boolean roomImagesApply(String roomId) throws SQLException;
    }

    /**
     * The production {@link RoomCheck}: the room's owner holds {@code rooms.customCards} through their
     * roles (a disabled owner holds nothing). Read at every check, so a role change applies at the
     * output's next look at its cards.
     */
    public static RoomCheck ownerHolds(Database db, Permissions permissions) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(permissions);
        return roomId -> {
            String ownerId = db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT owner_id FROM rooms WHERE id = ?")) {
                    ps.setString(1, roomId);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            return ownerId != null && permissions.held(ownerId).contains(Permission.ROOMS_CUSTOM_CARDS);
        };
    }

    private final Path serverDir;
    private final Path roomsDir;
    private final RoomCheck roomCheck;
    /** Width and height of a stored file (its name holds its hash, so it never changes). */
    private final Map<Path, int[]> sizes = new ConcurrentHashMap<>();

    /**
     * @param serverDir {@code <data>/cards/custom}
     * @param roomsDir  {@code <data>/rooms}
     */
    public CustomCards(Path serverDir, Path roomsDir, RoomCheck roomCheck) {
        this.serverDir = Objects.requireNonNull(serverDir).toAbsolutePath().normalize();
        this.roomsDir = Objects.requireNonNull(roomsDir).toAbsolutePath().normalize();
        this.roomCheck = Objects.requireNonNull(roomCheck);
    }

    /** The directory of a room's images, or the server's for a null room. */
    Path dir(String roomId) {
        if (roomId == null) {
            return serverDir;
        }
        if (!SAFE_ROOM.matcher(roomId).matches() || roomId.contains("..")) {
            throw new IllegalArgumentException("bad room id");
        }
        return roomsDir.resolve(roomId).resolve(ROOM_DIR);
    }

    /** The server's image for a screen, or null. */
    public Image server(Screen screen) throws IOException {
        return image(null, screen);
    }

    /** The room's own image for a screen (whether it applies or not), or null. */
    public Image room(String roomId, Screen screen) throws IOException {
        return image(Objects.requireNonNull(roomId), screen);
    }

    /**
     * The images that replace the room's filler cards now: per screen the room's own (while its
     * images apply), else the server's. Screens without one are left out.
     */
    public Map<Filler, Image> effective(String roomId) throws IOException, SQLException {
        Map<Screen, Image> server = images(null);
        Map<Screen, Image> room = roomId == null ? Map.of() : images(roomId);
        Map<Filler, Image> out = new EnumMap<>(Filler.class);
        if (server.isEmpty() && room.isEmpty()) {
            return out;
        }
        boolean roomApplies = !room.isEmpty() && roomCheck.roomImagesApply(roomId);
        for (Screen s : Screen.values()) {
            Image img = roomApplies ? room.get(s) : null;
            if (img == null) {
                img = server.get(s);
            }
            if (img != null) {
                out.put(s.filler(), img);
            }
        }
        return out;
    }

    /** Whether the room's own images apply now. */
    public boolean roomImagesApply(String roomId) throws SQLException {
        return roomCheck.roomImagesApply(roomId);
    }

    private Image image(String roomId, Screen screen) throws IOException {
        return images(roomId).get(Objects.requireNonNull(screen));
    }

    /** The stored images of a room (or the server), by screen; the newest when there are several. */
    private Map<Screen, Image> images(String roomId) throws IOException {
        Path dir = dir(roomId);
        Map<Screen, Image> out = new EnumMap<>(Screen.class);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                Matcher m = FILE.matcher(f.getFileName().toString());
                Screen screen = m.matches() ? Screen.byId(m.group(1)) : null;
                if (screen == null || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Image img;
                try {
                    int[] size = sizes.get(f);
                    if (size == null) {
                        size = dimensions(f);
                        sizes.put(f, size);
                    }
                    img = new Image(screen, f, m.group(2), m.group(3).equals("png") ? "png" : "jpeg", size[0],
                            size[1], Files.size(f), Files.getLastModifiedTime(f).toMillis());
                } catch (NoSuchFileException e) {
                    continue; // replaced meanwhile
                } catch (IOException e) {
                    continue; // unreadable: ignored (it was checked when it was stored)
                }
                Image known = out.get(screen);
                if (known == null || img.uploadedAt() > known.uploadedAt()) {
                    out.put(screen, img);
                }
            }
        } catch (NoSuchFileException e) {
            return out;
        }
        return out;
    }

    /**
     * Stores {@code upload} as the room's (null: the server's) image for {@code screen}, replacing
     * the one there was. The upload file is moved (or copied) away; the caller deletes what is left.
     *
     * @throws InvalidImageException if it isn't an acceptable PNG or JPEG
     */
    public Image put(String roomId, Screen screen, Path upload) throws IOException, InvalidImageException {
        Objects.requireNonNull(screen);
        long size = Files.size(upload);
        if (size > MAX_BYTES) {
            throw new InvalidImageException("The image is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
        }
        String format = format(upload);
        int[] dims = dimensions(upload, format);
        if (dims[0] > MAX_SIDE || dims[1] > MAX_SIDE || (long) dims[0] * dims[1] > MAX_PIXELS) {
            throw new InvalidImageException("The image is " + dims[0] + "×" + dims[1] + " pixels; at most "
                    + MAX_SIDE + " pixels per side and 7680×4320 in all are accepted");
        }
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(upload.toFile());
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException("The image can't be read: " + e.getMessage());
        }
        if (decoded == null) {
            throw new InvalidImageException("The image can't be read");
        }
        String hash = sha256(upload).substring(0, 16);
        Path dir = dir(roomId);
        synchronized (this) {
            Files.createDirectories(dir);
            Path target = dir.resolve(screen.id() + "-" + hash + "." + (format.equals("png") ? "png" : "jpg"));
            Path tmp = Files.createTempFile(dir, "." + screen.id() + "-", ".tmp");
            try {
                Files.copy(upload, tmp, StandardCopyOption.REPLACE_EXISTING);
                move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
            // The upload time is now even when the same file was there already.
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            sizes.put(target, dims);
            deleteOthers(dir, screen, target);
        }
        Image img = image(roomId, screen);
        if (img == null) {
            throw new IOException("the image was stored but can't be found");
        }
        return img;
    }

    /** Deletes the room's (null: the server's) image for {@code screen}; false if there was none. */
    public boolean delete(String roomId, Screen screen) throws IOException {
        Objects.requireNonNull(screen);
        Path dir = dir(roomId);
        synchronized (this) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return deleteOthers(dir, screen, null) > 0;
        }
    }

    private int deleteOthers(Path dir, Screen screen, Path keep) throws IOException {
        List<Path> gone = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                Matcher m = FILE.matcher(f.getFileName().toString());
                if (m.matches() && m.group(1).equals(screen.id()) && !f.equals(keep)) {
                    gone.add(f);
                }
            }
        }
        for (Path f : gone) {
            Files.deleteIfExists(f);
            sizes.remove(f);
        }
        return gone.size();
    }

    // ------------------------------------------------------------------ the spec

    /**
     * What doesn't meet the spec for {@code profile}: not 16:9 ({@code aspect}), smaller than the
     * output ({@code resolution}). Empty when it fits.
     */
    public static List<Warning> warnings(int width, int height, OutputProfile profile) {
        List<Warning> out = new ArrayList<>();
        double ratio = (double) width / height;
        double want = 16.0 / 9.0;
        if (Math.abs(ratio - want) / want > ASPECT_TOLERANCE) {
            out.add(new Warning("aspect", "The image is " + width + "×" + height + " (" + ratioText(width, height)
                    + "), not 16:9: it is shown scaled to fit, with black bars. Use a 16:9 image such as "
                    + profile.width() + "×" + profile.height() + " to fill the screen."));
        }
        if (width < profile.width() || height < profile.height()) {
            out.add(new Warning("resolution", "The image is " + width + "×" + height + ", smaller than the "
                    + profile.width() + "×" + profile.height() + " output (" + profile.id()
                    + "): it is scaled up and may look blurry. Use at least " + profile.width() + "×"
                    + profile.height() + "."));
        }
        return out;
    }

    /** {@code 4:3}, or {@code 2.33:1} when the reduced ratio has large terms. */
    static String ratioText(int width, int height) {
        int g = gcd(width, height);
        int w = width / g;
        int h = height / g;
        if (w <= 32 && h <= 32) {
            return w + ":" + h;
        }
        return String.format(Locale.ROOT, "%.2f:1", (double) width / height);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * The image drawn as given into a {@code width}×{@code height} frame: scaled to fit without
     * cropping or stretching, centred, with black bars where it doesn't fill the frame
     * (transparency shows black). Nothing else is drawn.
     */
    public static BufferedImage fit(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            // Within a pixel of the frame: fill it (no 1-pixel bar from rounding).
            if (Math.abs(w - width) <= 1) {
                w = width;
            }
            if (Math.abs(h - height) <= 1) {
                h = height;
            }
            BufferedImage src = shrink(image, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, (width - w) / 2, (height - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Halves the image while it is more than twice the target (a smooth downscale). */
    private static BufferedImage shrink(BufferedImage image, int w, int h) {
        BufferedImage cur = image;
        while (cur.getWidth() >= 2 * w && cur.getHeight() >= 2 * h) {
            int nw = cur.getWidth() / 2;
            int nh = cur.getHeight() / 2;
            BufferedImage next = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, nw, nh, null);
            } finally {
                g.dispose();
            }
            cur = next;
        }
        return cur;
    }

    /** Reads a stored image and fits it into the frame ({@link #fit}). */
    public static BufferedImage render(Image image, int width, int height) throws IOException {
        BufferedImage read = ImageIO.read(image.file().toFile());
        if (read == null) {
            throw new IOException("can't read the custom card image " + image.file().getFileName());
        }
        return fit(read, width, height);
    }

    // ------------------------------------------------------------------ files

    /** {@code png} or {@code jpeg}, from the file's first bytes. */
    static String format(Path file) throws IOException, InvalidImageException {
        byte[] head = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return "png";
        }
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        throw new InvalidImageException("Only PNG and JPEG images are accepted");
    }

    private static int[] dimensions(Path file) throws IOException {
        try {
            return dimensions(file, format(file));
        } catch (InvalidImageException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Width and height from the image header, without decoding the pixels. */
    private static int[] dimensions(Path file, String format) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("can't open " + file.getFileName());
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw new IOException("no " + format + " reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } catch (RuntimeException e) {
                throw new IOException("can't read the image header: " + e.getMessage(), e);
            } finally {
                reader.dispose();
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Every screen, in API order. */
    public static List<Screen> screens() {
        return Collections.unmodifiableList(List.of(Screen.values()));
    }
}
