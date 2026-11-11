package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.ingest.CardEncoder;
import io.siggi.togethertunes.ingest.CardRenderer;
import io.siggi.togethertunes.ingest.FillerCards;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.MediaVersions;
import io.siggi.togethertunes.ingest.ReferenceCard;
import io.siggi.togethertunes.ingest.ToolException;
import io.siggi.togethertunes.ingest.Tools;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.output.FillerSource.Filler;
import io.siggi.togethertunes.output.ProgramCue;
import io.siggi.togethertunes.output.ProgramMedia;
import io.siggi.togethertunes.output.VideoOverlay;
import io.siggi.togethertunes.rooms.RoomSettings;
import io.siggi.togethertunes.settings.Settings;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Cards with the room's QR overlay ({@code docs/overlay.md#cards-cheap-path}): the card image with
 * {@code overlay.png} composited at its position in Java2D, encoded as a card like every other
 * ({@link CardEncoder#encode}: a skip-frame card, avcC checked against the reference), and kept in
 * {@code rooms/<roomId>/cards/}.
 *
 * <ul>
 *   <li><b>Filler cards</b> (paused, idle, loading):
 *       {@code filler-<id>-<overlayHash>-<encoding>-c<cardVersion>.mp4}, made when the room's
 *       overlay is on and an output of the room needs them.</li>
 *   <li><b>Item cards</b> (audio items, video items played as audio), for an entry that is current
 *       or next: {@code item-<itemId>-v<mediaVersion>-<overlayHash>-<encoding>-c<cardVersion>.mp4},
 *       so the key is {@code (itemId, overlayHash, profile)} plus the encoding's bitrate, the card
 *       format and the media version.</li>
 * </ul>
 *
 * <p>Each card has a fingerprint ({@link FillerCards#fingerprint}: card format, encoder arguments,
 * reference avcC and the composited pixels) in a {@code .key} file next to it, so a card whose
 * picture changed under the same name (an edited title or cover, new fonts, a new renderer) is
 * encoded again, and an unchanged one is reused, also across restarts. The fingerprint is the card's
 * {@link ProgramMedia.CardFile#key() key} for the program output.
 *
 * <p>Thread-safe: concurrent requests for the same card encode it once. Old cards (another
 * overlayHash or media version) stay on disk until the overlay cache's clean-up removes them.
 */
public final class OverlayCards {
    private static final System.Logger LOG = System.getLogger(OverlayCards.class.getName());
    static final String CARDS_DIR = "cards";
    static final String KEY_SUFFIX = ".key";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Makes a card file from an image (tests replace the encode). */
    @FunctionalInterface
    interface Encoder {
        void encode(BufferedImage image, Path out, ReferenceCard reference)
                throws IOException, ToolException, InterruptedException, ReferenceCard.AvcCMismatchException;
    }

    /** Reads a card's avcC, to check a stored card against the reference ({@link Mp4Boxes#readAvcC}). */
    @FunctionalInterface
    interface AvcCReader {
        byte[] read(Path card) throws IOException;
    }

    private final Path roomsDir;
    private final Encoder encoder;
    private final AvcCReader avcC;
    /** Card files known to be current, with their fingerprints (filler cards only: their names say it all). */
    private final Map<Path, String> verified = new ConcurrentHashMap<>();
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();
    private int encodes;

    /**
     * @param roomsDir {@code <data>/rooms}
     * @param tools    the ffmpeg/ffprobe to encode with
     */
    public OverlayCards(Path roomsDir, Tools tools) {
        this(roomsDir, (image, out, reference) -> CardEncoder.encode(tools, image, out, reference, null),
                Mp4Boxes::readAvcC);
        Objects.requireNonNull(tools, "tools");
    }

    OverlayCards(Path roomsDir, Encoder encoder, AvcCReader avcC) {
        this.roomsDir = Objects.requireNonNull(roomsDir, "roomsDir");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.avcC = Objects.requireNonNull(avcC, "avcC");
    }

    /** {@code rooms/<roomId>/cards}. */
    public Path cardsDir(String roomId) {
        Path dir = roomsDir.resolve(safe(roomId, "room id")).normalize();
        if (!dir.getParent().equals(roomsDir.normalize())) {
            throw new IllegalArgumentException("bad room id");
        }
        return dir.resolve(CARDS_DIR);
    }

    /** How many cards this instance encoded (tests). */
    synchronized int encodes() {
        return encodes;
    }

    /**
     * The card image with the overlay panel drawn at its position. The card must be the size of the
     * frame the overlay was rendered for.
     */
    public static BufferedImage composite(BufferedImage card, OverlayRenderer.Rendered overlay) throws IOException {
        OverlayRenderer.Spec spec = overlay.spec();
        if (card.getWidth() != spec.frameWidth() || card.getHeight() != spec.frameHeight()) {
            throw new IOException("the card is " + card.getWidth() + "x" + card.getHeight() + " but the overlay is for "
                    + spec.frameWidth() + "x" + spec.frameHeight());
        }
        BufferedImage out = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(card, 0, 0, null);
            g.drawImage(overlay.image(), overlay.x(), overlay.y(), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The room's overlaid filler card, encoded first if needed. */
    public ProgramMedia.CardFile fillerCard(String roomId, OverlayRenderer.Rendered overlay, CardRenderer.Filler filler,
            ReferenceCard reference) throws IOException, InterruptedException {
        Path file = cardsDir(roomId).resolve("filler-" + filler.id() + "-" + safe(overlay.hash(), "overlay hash") + "-"
                + safe(reference.encoding().id(), "encoding") + "-c" + CardEncoder.CARD_VERSION + ".mp4");
        String known = verified.get(file);
        if (known != null && Files.isRegularFile(file)) {
            return new ProgramMedia.CardFile(file, known);
        }
        BufferedImage image = composite(new CardRenderer(reference.encoding().profile()).renderFiller(filler), overlay);
        String key = ensure(file, image, reference);
        verified.put(file, key);
        return new ProgramMedia.CardFile(file, key);
    }

    /**
     * The room's overlaid card for an item, encoded first if needed. The picture is rendered from the
     * item's metadata and cover every time (cheap), so an edited title or cover gives a new card.
     */
    public ProgramMedia.CardFile itemCard(String roomId, OverlayRenderer.Rendered overlay, String itemId,
            int mediaVersion, CardRenderer.ItemCard item, ReferenceCard reference)
            throws IOException, InterruptedException {
        Path file = cardsDir(roomId).resolve("item-" + safe(itemId, "item id") + "-v" + mediaVersion + "-"
                + safe(overlay.hash(), "overlay hash") + "-" + safe(reference.encoding().id(), "encoding") + "-c"
                + CardEncoder.CARD_VERSION + ".mp4");
        BufferedImage image = composite(new CardRenderer(reference.encoding().profile()).renderItem(item), overlay);
        return new ProgramMedia.CardFile(file, ensure(file, image, reference));
    }

    /** Makes sure {@code file} holds {@code image} as a card for {@code reference}; returns its fingerprint. */
    private String ensure(Path file, BufferedImage image, ReferenceCard reference)
            throws IOException, InterruptedException {
        String key = FillerCards.fingerprint(image, reference);
        Path keyFile = file.resolveSibling(file.getFileName() + KEY_SUFFIX);
        synchronized (locks.computeIfAbsent(file, f -> new Object())) {
            if (current(file, keyFile, key, reference)) {
                return key;
            }
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(keyFile);
            try {
                encoder.encode(image, file, reference);
            } catch (ToolException e) {
                throw new IOException("can't encode " + file.getFileName() + ": " + e.getMessage(), e);
            } catch (ReferenceCard.AvcCMismatchException e) {
                throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
            }
            Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName() + "-", KEY_SUFFIX);
            try {
                Files.writeString(tmp, key + "\n", StandardCharsets.UTF_8);
                move(tmp, keyFile);
            } finally {
                Files.deleteIfExists(tmp);
            }
            synchronized (this) {
                encodes++;
            }
            LOG.log(System.Logger.Level.DEBUG, "Encoded overlay card " + file);
            return key;
        }
    }

    private boolean current(Path card, Path keyFile, String key, ReferenceCard reference) {
        if (!Files.isRegularFile(card) || !Files.isRegularFile(keyFile)) {
            return false;
        }
        try {
            return Files.readString(keyFile, StandardCharsets.UTF_8).strip().equals(key)
                    && reference.matches(avcC.read(card));
        } catch (IOException e) {
            return false;
        }
    }

    private static String safe(String s, String what) {
        if (s == null || !SAFE.matcher(s).matches() || s.contains("..")) {
            throw new IllegalArgumentException("bad " + what);
        }
        return s;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ one room's output

    /** A room's slug and settings. */
    public record RoomInfo(String slug, RoomSettings settings) {
    }

    /** What an item card shows, and the media version it is for. */
    public record ItemInfo(int mediaVersion, CardRenderer.ItemCard card) {
    }

    /** What the room cards need to know; every call reads the current state. */
    public interface Lookups {
        /** The server's {@code overlay.enabled}. */
        boolean serverEnabled() throws SQLException;

        /** The room, or null if there is no such room. */
        RoomInfo room(String roomId) throws SQLException;

        /** The entry's recorded overlay decision ({@link OverlayDecisions#decide}), null without such entry. */
        OverlayDecisions.Decision decide(String entryId) throws SQLException;

        /** The item's card content, or null if there is no such item. */
        ItemInfo item(String itemId) throws SQLException, IOException;
    }

    /**
     * The overlaid cards of one room's program output, for an output encoding with this reference
     * ({@link ProgramMedia.OverlayCards}).
     *
     * <ul>
     *   <li>Filler cards follow the room as it is now: the server's and the room's
     *       {@code overlay.enabled}.</li>
     *   <li>An entry's card follows its decision, recorded the first time it is asked
     *       ({@link OverlayDecisions}: as the entry becomes next or current): {@code cards} or
     *       {@code video} gives the overlay.</li>
     * </ul>
     *
     * @param overlays the rooms' overlay images (rendered for the output profile in use)
     */
    public ProgramMedia.OverlayCards forRoom(String roomId, ReferenceCard reference, Overlays overlays,
            Lookups lookups) {
        return forRoom(roomId, reference, overlays, lookups, null);
    }

    /**
     * {@link #forRoom(String, ReferenceCard, Overlays, Lookups)} plus the music-video re-encodes
     * ({@code docs/overlay.md#scheduling-and-caching}): entries played as video whose recorded
     * decision is {@code video} get the room's overlay re-encode, scheduled on {@code videoJobs}
     * (null: none).
     */
    public ProgramMedia.OverlayCards forRoom(String roomId, ReferenceCard reference, Overlays overlays,
            Lookups lookups, OverlayVideoJobs videoJobs) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(overlays, "overlays");
        Objects.requireNonNull(lookups, "lookups");
        cardsDir(roomId); // validates the id
        OverlayVideoJobs.Session video = videoJobs == null ? null : videoJobs.session(roomId,
                reference.encoding(), reference.avcC(), entry -> {
                    OverlayDecisions.Decision d = lookups.decide(entry.entryId());
                    if (d == null || !d.video()) {
                        return null;
                    }
                    RoomInfo room = lookups.room(roomId);
                    if (room == null) {
                        return null;
                    }
                    OverlayRenderer.Rendered overlay = overlays.current(roomId, room.slug(), room.settings());
                    return new OverlayVideoJobs.Plan(overlay.hash(), overlay.image(), overlay.x(), overlay.y());
                });
        return new ProgramMedia.OverlayCards() {
            @Override
            public Supplier<VideoOverlay> video(
                    ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
                if (video == null) {
                    return null;
                }
                VideoOverlay play = video.play(entry, itemDir, index);
                return () -> play;
            }

            @Override
            public void videoNext(ProgramCue.Entry entry, Path itemDir, SampleIndex index) {
                if (video != null) {
                    video.next(entry, itemDir, index);
                }
            }

            @Override
            public void videoWanted(Set<String> entryIds) {
                if (video != null) {
                    video.wanted(entryIds);
                }
            }

            @Override
            public Map<Filler, ProgramMedia.CardFile> fillers() throws IOException, InterruptedException {
                RoomInfo room;
                try {
                    if (!lookups.serverEnabled()) {
                        return null;
                    }
                    room = lookups.room(roomId);
                } catch (SQLException e) {
                    throw new IOException("can't read the overlay settings of room " + roomId + ": " + e.getMessage(), e);
                }
                if (room == null || !room.settings().overlayEnabled()) {
                    return null;
                }
                OverlayRenderer.Rendered overlay = overlays.current(roomId, room.slug(), room.settings());
                Map<Filler, ProgramMedia.CardFile> cards = new EnumMap<>(Filler.class);
                for (Filler f : Filler.values()) {
                    cards.put(f, fillerCard(roomId, overlay, f.serverCard(), reference));
                }
                return cards;
            }

            @Override
            public ProgramMedia.CardFile item(ProgramCue.Entry entry) throws IOException, InterruptedException {
                RoomInfo room;
                ItemInfo item;
                try {
                    OverlayDecisions.Decision d = lookups.decide(entry.entryId());
                    if (d == null || !d.cards()) {
                        return null;
                    }
                    room = lookups.room(roomId);
                    item = lookups.item(entry.itemId());
                } catch (SQLException e) {
                    throw new IOException("can't read the overlay card of entry " + entry.entryId() + ": "
                            + e.getMessage(), e);
                }
                if (room == null || item == null) {
                    return null;
                }
                OverlayRenderer.Rendered overlay = overlays.current(roomId, room.slug(), room.settings());
                return itemCard(roomId, overlay, entry.itemId(), item.mediaVersion(), item.card(), reference);
            }
        };
    }

    /** An item's card metadata as stored. */
    private record ItemRow(String title, String artist, String album, int version) {
    }

    /** {@link Lookups} from the database, the server settings and the media store. */
    public static Lookups lookupsFrom(Database db, DataDir dataDir, Settings settings, OverlayDecisions decisions) {
        Objects.requireNonNull(db);
        Objects.requireNonNull(dataDir);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(decisions);
        return new Lookups() {
            @Override
            public boolean serverEnabled() throws SQLException {
                return settings.overlayEnabled();
            }

            @Override
            public RoomInfo room(String roomId) throws SQLException {
                return db.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT slug, settings_json FROM rooms WHERE id = ?")) {
                        ps.setString(1, roomId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? new RoomInfo(rs.getString(1), RoomSettings.parse(rs.getString(2))) : null;
                        }
                    }
                });
            }

            @Override
            public OverlayDecisions.Decision decide(String entryId) throws SQLException {
                return decisions.decide(entryId);
            }

            @Override
            public ItemInfo item(String itemId) throws SQLException {
                ItemRow row = db.read(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT title, artist, album, media_version FROM items WHERE id = ?")) {
                        ps.setString(1, itemId);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? new ItemRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))
                                    : null;
                        }
                    }
                });
                if (row == null) {
                    return null;
                }
                Path cover = MediaVersions.versionDir(dataDir, itemId, row.version()).resolve(IngestPipeline.COVER);
                BufferedImage artwork = null;
                if (Files.isRegularFile(cover)) {
                    try {
                        artwork = CardRenderer.readImage(cover);
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.WARNING, "Overlay card of item " + itemId
                                + ": the cover can't be read (" + e.getMessage() + ")");
                    }
                }
                return new ItemInfo(row.version(),
                        new CardRenderer.ItemCard(row.title(), row.artist(), row.album(), artwork));
            }
        };
    }
}
