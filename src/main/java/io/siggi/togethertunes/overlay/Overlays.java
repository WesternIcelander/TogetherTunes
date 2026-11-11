package io.siggi.togethertunes.overlay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.rooms.RoomSettings;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * The rooms' overlay images ({@code docs/overlay.md#the-overlay-image}):
 * {@code rooms/<roomId>/overlay.png}, with {@code overlay.json} next to it holding the
 * {@link OverlayRenderer.Spec#key() spec} it was rendered from and its position in the frame.
 *
 * <p>{@link #current} gives a room's overlay for its slug and settings, the server's
 * {@code publicBaseUrl} and the output profile in use. The image is regenerated whenever any of
 * them changed since it was made (a new slug, other overlay settings, another public URL after a
 * restart, another profile, or a new {@link OverlayRenderer#RENDER_VERSION}); otherwise the stored
 * one is reused (also across restarts). {@link OverlayRenderer.Rendered#hash() overlayHash} is
 * computed from the image's pixels and position, so it is part of every overlay cache key and a
 * stale render is never used. {@link #roomEdited} regenerates eagerly when a room's slug or
 * overlay settings change.
 *
 * <p>Thread-safe. Lookups that find the current overlay in memory don't lock; regenerating is
 * serialised (it takes milliseconds).
 */
public final class Overlays {
    private static final System.Logger LOG = System.getLogger(Overlays.class.getName());
    static final String PNG = "overlay.png";
    static final String META = "overlay.json";

    private final Path roomsDir;
    private final String publicBaseUrl;
    private final Supplier<OutputProfile> profile;
    private final OverlayRenderer renderer = new OverlayRenderer();
    private final Map<String, OverlayRenderer.Rendered> cache = new ConcurrentHashMap<>();
    private volatile java.util.function.BiConsumer<String, String> hashListener = (roomId, hash) -> {
    };

    /**
     * @param roomsDir      {@code <data>/rooms}
     * @param publicBaseUrl {@code http.publicBaseUrl}
     * @param profile       the output profile in use (it can change at runtime)
     */
    public Overlays(Path roomsDir, String publicBaseUrl, Supplier<OutputProfile> profile) {
        this.roomsDir = Objects.requireNonNull(roomsDir);
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl);
        this.profile = Objects.requireNonNull(profile);
    }

    /**
     * Told {@code (roomId, overlayHash)} when a room's current overlay becomes known in this run or
     * changes (the overlay cache then drops what was made with another one). Called on the thread
     * that asked {@link #current}, outside any lock here.
     */
    public void hashListener(java.util.function.BiConsumer<String, String> listener) {
        this.hashListener = Objects.requireNonNull(listener);
    }

    /** {@code rooms/<roomId>/overlay.png}. */
    public Path png(String roomId) {
        return roomDir(roomId).resolve(PNG);
    }

    private Path roomDir(String roomId) {
        Path dir = roomsDir.resolve(roomId).normalize();
        if (!dir.getParent().equals(roomsDir.normalize()) || roomId.isEmpty()) {
            throw new IllegalArgumentException("bad room id");
        }
        return dir;
    }

    /** The spec a room's overlay has now. */
    public OverlayRenderer.Spec spec(String slug, RoomSettings settings) {
        return OverlayRenderer.spec(publicBaseUrl, slug, settings, profile.get());
    }

    /**
     * The room's current overlay, rendered and stored first if there is none for this spec yet.
     *
     * @throws IOException if it had to be rendered and couldn't be written
     */
    public OverlayRenderer.Rendered current(String roomId, String slug, RoomSettings settings) throws IOException {
        OverlayRenderer.Spec spec = spec(slug, settings);
        OverlayRenderer.Rendered known = cache.get(roomId);
        if (known != null && known.spec().equals(spec)) {
            return known;
        }
        OverlayRenderer.Rendered stored;
        synchronized (this) {
            known = cache.get(roomId);
            if (known != null && known.spec().equals(spec)) {
                return known;
            }
            stored = load(roomId, spec);
            if (stored == null) {
                stored = renderer.render(spec);
                store(roomId, stored);
                LOG.log(System.Logger.Level.INFO, "Rendered the overlay of room " + roomId + " (overlayHash "
                        + stored.hash() + ")");
            }
            cache.put(roomId, stored);
        }
        if (known == null || !known.hash().equals(stored.hash())) {
            try {
                hashListener.accept(roomId, stored.hash());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Overlay of room " + roomId + ": " + e, e);
            }
        }
        return stored;
    }

    /**
     * After a room was edited: regenerates its overlay now if the overlay is on (the room's mode
     * isn't {@code off} and the server's switch is on) and the slug or overlay settings changed, so later users find it ready.
     * With the overlay off nothing happens; {@link #current} renders on demand anyway.
     */
    public void roomEdited(String roomId, String slug, RoomSettings settings, boolean serverEnabled)
            throws IOException {
        if (serverEnabled && settings.overlayEnabled()) {
            current(roomId, slug, settings); // a no-op when the spec is unchanged
        }
    }

    /**
     * A preview for the management view ({@code GET /api/rooms/{room}/overlay/preview.png}): the
     * room's idle card (with its name) at the output size, with the overlay for {@code settings}
     * drawn on it as the stream would show it. Nothing is stored or cached, so unsaved settings can
     * be previewed; it renders whatever the room's overlay mode (the queue-empty card has the
     * overlay in both {@code always} and {@code idleOnly}).
     */
    public BufferedImage preview(String slug, String roomName, RoomSettings settings) throws IOException {
        return preview(slug, roomName, settings, null);
    }

    /**
     * {@link #preview(String, String, RoomSettings)} on the room's custom queue-empty image
     * ({@code idle}, null: the built-in card), fitted as the stream shows it ({@link CustomCards#render}).
     */
    public BufferedImage preview(String slug, String roomName, RoomSettings settings, CustomCards.Image idle)
            throws IOException {
        OverlayRenderer.Spec spec = spec(slug, settings);
        OverlayRenderer.Rendered rendered = renderer.render(spec);
        OutputProfile p = profile.get();
        BufferedImage card = idle != null ? CustomCards.render(idle, p.width(), p.height())
                : new CardRenderer(p).renderMessage(CardRenderer.Filler.QUEUE_EMPTY, roomName);
        return OverlayCards.composite(card, rendered);
    }

    /** Forgets the room's overlay in memory (e.g. after the room was deleted). */
    public void forget(String roomId) {
        cache.remove(roomId);
    }

    /** The stored overlay if it was made for {@code spec}, else null (missing, stale or unreadable). */
    private OverlayRenderer.Rendered load(String roomId, OverlayRenderer.Spec spec) {
        Path dir = roomDir(roomId);
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(dir.resolve(META), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!spec.key().equals(meta.get("spec").getAsString())) {
                return null;
            }
            int x = meta.get("x").getAsInt();
            int y = meta.get("y").getAsInt();
            BufferedImage read = ImageIO.read(dir.resolve(PNG).toFile());
            if (read == null) {
                return null;
            }
            BufferedImage image = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int[] px = read.getRGB(0, 0, read.getWidth(), read.getHeight(), null, 0, read.getWidth());
            image.setRGB(0, 0, read.getWidth(), read.getHeight(), px, 0, read.getWidth());
            return new OverlayRenderer.Rendered(spec, image, x, y,
                    OverlayRenderer.hash(image, x, y, spec.frameWidth(), spec.frameHeight()));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Re-rendering the overlay of room " + roomId
                    + ": the stored one is unreadable (" + e + ")");
            return null;
        }
    }

    /** Writes the PNG, then the metadata (each atomically): the metadata never names a missing image. */
    private void store(String roomId, OverlayRenderer.Rendered r) throws IOException {
        Path dir = roomDir(roomId);
        Files.createDirectories(dir);
        // The old metadata goes first, so a crash between the two writes leaves no stale match.
        Files.deleteIfExists(dir.resolve(META));
        Path tmpPng = Files.createTempFile(dir, ".overlay", ".png.tmp");
        try {
            if (!ImageIO.write(r.image(), "png", tmpPng.toFile())) {
                throw new IOException("no PNG writer");
            }
            move(tmpPng, dir.resolve(PNG));
        } finally {
            Files.deleteIfExists(tmpPng);
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("spec", r.spec().key());
        meta.addProperty("x", r.x());
        meta.addProperty("y", r.y());
        meta.addProperty("width", r.width());
        meta.addProperty("height", r.height());
        meta.addProperty("overlayHash", r.hash());
        Path tmpMeta = Files.createTempFile(dir, ".overlay", ".json.tmp");
        try {
            Files.writeString(tmpMeta, meta.toString(), StandardCharsets.UTF_8);
            move(tmpMeta, dir.resolve(META));
        } finally {
            Files.deleteIfExists(tmpMeta);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
