package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.media.Mp4Boxes;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * The server's reference card for one output encoding (profile and video bitrate), and its avcC: the invariant reference
 * (invariant 1). Every encode that feeds the packager (mezzanines, cards, overlay re-encodes) must
 * produce exactly these avcC bytes; ingest and the overlay encoder compare with {@link #verify}
 * and fail the job on a difference ({@code docs/library-and-ingest.md#the-mezzanine-profile}).
 *
 * <p>The card is a black still at the profile's size, encoded like every card
 * ({@link #encodeCard}). It lives in
 * {@code <data>/cards/<encoding>/reference.mp4} (e.g. {@code cards/720p30-3000k/}), with its avcC in
 * {@code reference.avcC}. The bitrate is part of the SPS (CBR HRD parameters), so each bitrate
 * has its own reference.
 *
 * <p>{@link #ensure} runs at startup. The first time, it creates both files. Afterwards the stored
 * avcC stays the reference, because the library was encoded against it, but a fresh card is still
 * encoded and compared: if the installed ffmpeg/x264 now writes different bytes (e.g. after an
 * upgrade), {@link #encoderChanged()} is true, a warning is due, and new encodes will fail the
 * check until the stored reference is replaced and the library re-encoded.
 */
public final class ReferenceCard {
    /** File name of the encoded reference card in the profile's cards directory. */
    public static final String CARD_FILE = "reference.mp4";
    /** File name of the reference avcC (raw bytes of the {@code avcC} box payload). */
    public static final String AVCC_FILE = "reference.avcC";

    private final OutputEncoding encoding;
    private final Path card;
    private final byte[] avcC;
    private final byte[] currentAvcC;
    private final boolean created;

    private ReferenceCard(OutputEncoding encoding, Path card, byte[] avcC, byte[] currentAvcC, boolean created) {
        this.encoding = encoding;
        this.card = card;
        this.avcC = avcC;
        this.currentAvcC = currentAvcC;
        this.created = created;
    }

    /** The directory holding an encoding's server-wide cards: {@code <cardsDir>/<encoding id>}. */
    public static Path encodingDir(Path cardsDir, OutputEncoding encoding) {
        return cardsDir.resolve(encoding.id());
    }

    /**
     * Encodes a fresh reference card for {@code encoding} and returns the reference: the stored one
     * if {@code <cardsDir>/<encoding>/reference.avcC} exists, otherwise the fresh card, which is
     * then stored. Writes are atomic (temporary file, then rename).
     *
     * @param log job log for the ffmpeg output, or null
     * @throws ToolException if ffmpeg fails
     * @throws IOException   if the files can't be written or read, or the stored avcC is malformed
     */
    public static ReferenceCard ensure(Tools tools, Path cardsDir, OutputEncoding encoding, JobLog log)
            throws ToolException, IOException, InterruptedException {
        Path dir = encodingDir(cardsDir, encoding);
        Files.createDirectories(dir);
        deleteTempFiles(dir);
        Path card = dir.resolve(CARD_FILE);
        Path avcFile = dir.resolve(AVCC_FILE);

        Path png = Files.createTempFile(dir, ".reference-", ".png");
        Path mp4 = Files.createTempFile(dir, ".reference-", ".mp4");
        try {
            writeBlackPng(png, encoding);
            encodeCard(tools, png, mp4, encoding, log);
            byte[] fresh = readAvcC(mp4);

            if (Files.exists(avcFile)) {
                byte[] stored = Files.readAllBytes(avcFile);
                try {
                    checkAvcC(stored);
                } catch (IOException e) {
                    throw new IOException(avcFile + ": " + e.getMessage(), e);
                }
                if (!Files.exists(card) && Arrays.equals(stored, fresh)) {
                    CardEncoder.move(mp4, card);
                }
                return new ReferenceCard(encoding, card, stored, fresh, false);
            }
            Path avcTmp = Files.createTempFile(dir, ".reference-", ".avcC");
            try {
                Files.write(avcTmp, fresh);
                CardEncoder.move(mp4, card);
                CardEncoder.move(avcTmp, avcFile);
            } finally {
                Files.deleteIfExists(avcTmp);
            }
            return new ReferenceCard(encoding, card, fresh, fresh, true);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
        }
    }

    /**
     * Deletes temporary files (names starting with a dot) left in a profile's cards directory by
     * an encode that was killed, e.g. when the server stopped during startup. Only called from
     * {@link #ensure} at startup, before anything else writes there.
     */
    static void deleteTempFiles(Path dir) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, ".*")) {
            for (Path file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** A black image at the profile's size, rendered with Java2D. */
    static void writeBlackPng(Path png, OutputEncoding encoding) throws IOException {
        BufferedImage image = new BufferedImage(encoding.width(), encoding.height(), BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, "png", png.toFile())) {
            throw new IOException("no PNG writer");
        }
    }

    /** The card command ({@link CardEncoder#encodePng}); no avcC check, this card defines it. */
    static void encodeCard(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log)
            throws ToolException, InterruptedException {
        CardEncoder.encodePng(tools, png, out, encoding, log);
    }

    /**
     * The avcC box payload (AVCDecoderConfigurationRecord) of an encode, read with the MP4 box
     * walker and sanity-checked with {@link #checkAvcC}.
     */
    static byte[] readAvcC(Path mp4) throws IOException {
        byte[] avcC = Mp4Boxes.readAvcC(mp4);
        checkAvcC(avcC);
        return avcC;
    }

    /** Sanity checks on an AVCDecoderConfigurationRecord: version 1, High profile, one SPS. */
    static void checkAvcC(byte[] avcC) throws IOException {
        if (avcC.length < 7) {
            throw new IOException("avcC too short (" + avcC.length + " bytes)");
        }
        if (avcC[0] != 1) {
            throw new IOException("not an avcC record (configurationVersion " + (avcC[0] & 0xff) + ")");
        }
        if ((avcC[1] & 0xff) != 100) {
            throw new IOException("avcC is not H.264 High profile (profile_idc " + (avcC[1] & 0xff) + ")");
        }
        if ((avcC[5] & 0x1f) == 0) {
            throw new IOException("avcC has no SPS");
        }
    }

    /** The output profile and video bitrate this reference is for. */
    public OutputEncoding encoding() {
        return encoding;
    }

    /** The encoded reference card ({@code reference.mp4}); may be missing if {@link #encoderChanged()}. */
    public Path card() {
        return card;
    }

    /** The reference avcC bytes (a copy). */
    public byte[] avcC() {
        return avcC.clone();
    }

    /** Whether this call created the reference (first start for this encoding). */
    public boolean created() {
        return created;
    }

    /**
     * Whether the installed encoder no longer reproduces the stored reference: a card encoded
     * now has a different avcC. New encodes will then fail {@link #verify}.
     */
    public boolean encoderChanged() {
        return !Arrays.equals(avcC, currentAvcC);
    }

    /** Whether {@code candidate} is byte-identical to the reference avcC. */
    public boolean matches(byte[] candidate) {
        return Arrays.equals(avcC, candidate);
    }

    /**
     * Checks an encode's avcC against the reference.
     *
     * @param what names the encode in the error message, e.g. {@code "mezz.mp4"}
     * @throws AvcCMismatchException if the bytes differ
     */
    public void verify(byte[] candidate, String what) throws AvcCMismatchException {
        if (!matches(candidate)) {
            throw new AvcCMismatchException(what + ": avcC differs from the " + encoding + " reference card (expected "
                    + HexFormat.of().formatHex(avcC) + ", got "
                    + (candidate == null ? "none" : HexFormat.of().formatHex(candidate)) + ")");
        }
    }

    /** An encode's avcC isn't byte-identical to the reference card's (invariant 1). */
    public static final class AvcCMismatchException extends Exception {
        public AvcCMismatchException(String message) {
            super(message);
        }
    }
}
