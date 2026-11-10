package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.ingest.CardRenderer.Filler;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * The server-wide filler cards (Paused, Queue is empty, Loading…) for one output encoding, encoded
 * once and kept in {@code <data>/cards/<encoding>/<id>.mp4} ({@code paused.mp4}, {@code idle.mp4},
 * {@code loading.mp4}). The packager pairs them with the silence frame when a room is paused, idle
 * or loading ({@code docs/program-output.md}); rooms with the QR overlay get their own overlaid
 * copies instead ({@code docs/overlay.md}).
 *
 * <p>{@link #ensure} runs at startup. Each card is rendered (cheap) and fingerprinted together with
 * the encoder arguments and the reference avcC; the fingerprint is stored next to the card in
 * {@code <id>.key}. A card whose fingerprint still matches and whose avcC matches the reference is
 * kept; otherwise (first start, new renderer or fonts, new encoder arguments or bitrate, missing or damaged
 * file) it is re-encoded with {@link CardEncoder#encode}, which checks the avcC before publishing.
 */
public final class FillerCards {
    /** Suffix of the fingerprint file stored next to each card. */
    static final String KEY_SUFFIX = ".key";

    private final OutputEncoding encoding;
    private final Map<Filler, Path> cards;
    private final int encoded;

    private FillerCards(OutputEncoding encoding, Map<Filler, Path> cards, int encoded) {
        this.encoding = encoding;
        this.cards = Collections.unmodifiableMap(cards);
        this.encoded = encoded;
    }

    /** Where a filler card lives: {@code <cardsDir>/<encoding>/<id>.mp4}. */
    public static Path cardFile(Path cardsDir, OutputEncoding encoding, Filler filler) {
        return ReferenceCard.encodingDir(cardsDir, encoding).resolve(filler.id() + ".mp4");
    }

    /**
     * Makes sure every filler card exists and is current for {@code reference}'s encoding,
     * re-encoding the ones that aren't.
     *
     * @param log job log for the ffmpeg output, or null
     * @throws ReferenceCard.AvcCMismatchException if a card has to be encoded but the installed
     *                                             encoder no longer reproduces the reference avcC
     */
    public static FillerCards ensure(Tools tools, Path cardsDir, ReferenceCard reference, JobLog log)
            throws ToolException, IOException, InterruptedException, ReferenceCard.AvcCMismatchException {
        Objects.requireNonNull(reference, "reference");
        OutputEncoding encoding = reference.encoding();
        CardRenderer renderer = new CardRenderer(encoding.profile());
        Map<Filler, Path> cards = new EnumMap<>(Filler.class);
        int encoded = 0;
        for (Filler filler : Filler.values()) {
            Path card = cardFile(cardsDir, encoding, filler);
            Path keyFile = card.resolveSibling(filler.id() + KEY_SUFFIX);
            BufferedImage image = renderer.renderFiller(filler);
            String key = fingerprint(image, reference);
            if (!current(card, keyFile, key, reference)) {
                Files.deleteIfExists(keyFile);
                CardEncoder.encode(tools, image, card, reference, log);
                Path keyTmp = Files.createTempFile(card.getParent(), "." + filler.id() + "-", KEY_SUFFIX);
                try {
                    Files.writeString(keyTmp, key + "\n", StandardCharsets.UTF_8);
                    CardEncoder.move(keyTmp, keyFile);
                } finally {
                    Files.deleteIfExists(keyTmp);
                }
                encoded++;
            }
            cards.put(filler, card);
        }
        return new FillerCards(encoding, cards, encoded);
    }

    /** Whether the stored card was made from the same image and arguments and has the reference avcC. */
    private static boolean current(Path card, Path keyFile, String key, ReferenceCard reference) {
        if (!Files.isRegularFile(card) || !Files.isRegularFile(keyFile)) {
            return false;
        }
        try {
            if (!Files.readString(keyFile, StandardCharsets.UTF_8).strip().equals(key)) {
                return false;
            }
            return reference.matches(Mp4Boxes.readAvcC(card));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * SHA-256 over everything a card's bytes depend on: the encoding, the encoder arguments (incl. the bitrate) and
     * filter, the reference avcC, and the rendered pixels.
     */
    static String fingerprint(BufferedImage image, ReferenceCard reference) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        OutputEncoding encoding = reference.encoding();
        sha.update(("card v1\n" + encoding.id() + "\n" + EncodeProfile.scaleFilter(encoding) + "\n"
                + String.join(" ", EncodeProfile.videoArgs(encoding)) + "\n").getBytes(StandardCharsets.UTF_8));
        sha.update(reference.avcC());
        ByteBuffer header = ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight());
        sha.update(header.array());
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        ByteBuffer row = ByteBuffer.allocate(image.getWidth() * 4);
        for (int y = 0; y < image.getHeight(); y++) {
            row.clear();
            row.asIntBuffer().put(pixels, y * image.getWidth(), image.getWidth());
            sha.update(row.array());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    public OutputEncoding encoding() {
        return encoding;
    }

    /** The encoded card for {@code filler}. */
    public Path card(Filler filler) {
        return cards.get(Objects.requireNonNull(filler));
    }

    /** How many cards {@link #ensure} had to encode (0 when all were current). */
    public int encoded() {
        return encoded;
    }
}
