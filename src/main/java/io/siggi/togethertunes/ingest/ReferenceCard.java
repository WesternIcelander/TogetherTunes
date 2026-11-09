package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.media.EncodeProfile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The server's reference card for one output profile, and its avcC: the invariant reference
 * (invariant 1). Every encode that feeds the packager (mezzanines, cards, overlay re-encodes) must
 * produce exactly these avcC bytes; ingest and the overlay encoder compare with {@link #verify}
 * and fail the job on a difference ({@code docs/library-and-ingest.md#the-mezzanine-profile}).
 *
 * <p>The card is a black still at the profile's size, encoded like every card
 * ({@link #encodeCard}). It lives in
 * {@code <data>/cards/<profile>/reference.mp4}, with its avcC in {@code reference.avcC}.
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
    /** Time limit for encoding the reference card (a 2 s still; takes well under a second). */
    static final Duration ENCODE_TIMEOUT = Duration.ofMinutes(2);

    private final OutputProfile profile;
    private final Path card;
    private final byte[] avcC;
    private final byte[] currentAvcC;
    private final boolean created;

    private ReferenceCard(OutputProfile profile, Path card, byte[] avcC, byte[] currentAvcC, boolean created) {
        this.profile = profile;
        this.card = card;
        this.avcC = avcC;
        this.currentAvcC = currentAvcC;
        this.created = created;
    }

    /** The directory holding a profile's server-wide cards: {@code <cardsDir>/<profile id>}. */
    public static Path profileDir(Path cardsDir, OutputProfile profile) {
        return cardsDir.resolve(profile.id());
    }

    /**
     * Encodes a fresh reference card for {@code profile} and returns the reference: the stored one
     * if {@code <cardsDir>/<profile>/reference.avcC} exists, otherwise the fresh card, which is
     * then stored. Writes are atomic (temporary file, then rename).
     *
     * @param log job log for the ffmpeg/ffprobe output, or null
     * @throws ToolException if ffmpeg or ffprobe fail
     * @throws IOException   if the files can't be written or read, or the stored avcC is malformed
     */
    public static ReferenceCard ensure(Tools tools, Path cardsDir, OutputProfile profile, JobLog log)
            throws ToolException, IOException, InterruptedException {
        Path dir = profileDir(cardsDir, profile);
        Files.createDirectories(dir);
        Path card = dir.resolve(CARD_FILE);
        Path avcFile = dir.resolve(AVCC_FILE);

        Path png = Files.createTempFile(dir, ".reference-", ".png");
        Path mp4 = Files.createTempFile(dir, ".reference-", ".mp4");
        try {
            writeBlackPng(png, profile);
            encodeCard(tools, png, mp4, profile, log);
            byte[] fresh = probeAvcC(tools, mp4, log);

            if (Files.exists(avcFile)) {
                byte[] stored = Files.readAllBytes(avcFile);
                try {
                    checkAvcC(stored);
                } catch (IOException e) {
                    throw new IOException(avcFile + ": " + e.getMessage(), e);
                }
                if (!Files.exists(card) && Arrays.equals(stored, fresh)) {
                    move(mp4, card);
                }
                return new ReferenceCard(profile, card, stored, fresh, false);
            }
            Path avcTmp = Files.createTempFile(dir, ".reference-", ".avcC");
            try {
                Files.write(avcTmp, fresh);
                move(mp4, card);
                move(avcTmp, avcFile);
            } finally {
                Files.deleteIfExists(avcTmp);
            }
            return new ReferenceCard(profile, card, fresh, fresh, true);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** A black image at the profile's size, rendered with Java2D. */
    static void writeBlackPng(Path png, OutputProfile profile) throws IOException {
        BufferedImage image = new BufferedImage(profile.width(), profile.height(), BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, "png", png.toFile())) {
            throw new IOException("no PNG writer");
        }
    }

    /**
     * The card command from {@code docs/library-and-ingest.md#cards}:
     * {@code -loop 1 -framerate 30 -i card.png -t 2 -vf <scaleFilter> <profile args> -an}. The
     * filter matters: a PNG without a {@code pHYs} chunk (Java2D's ImageIO writes none) has an
     * unknown sample aspect ratio, and x264 then leaves the aspect ratio out of the SPS, so the
     * avcC would differ from the mezzanines' ({@code setsar=1}). The scale/pad part is a no-op for
     * an image at the profile size.
     */
    static void encodeCard(Tools tools, Path png, Path out, OutputProfile profile, JobLog log)
            throws ToolException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("-y", "-loop", "1", "-framerate", Integer.toString(profile.fps()),
                "-i", png.toString(), "-t", Integer.toString(EncodeProfile.GOP_MS / 1000),
                "-vf", EncodeProfile.scaleFilter(profile)));
        args.addAll(EncodeProfile.videoArgs(profile));
        args.addAll(List.of("-an", "-f", "mp4", out.toString()));
        ToolCall call = tools.ffmpeg(args).timeout(ENCODE_TIMEOUT);
        if (log != null) {
            call.log(log);
        }
        call.run();
    }

    /**
     * The first video stream's codec extradata as reported by ffprobe. For H.264 in MP4 that is
     * the {@code avcC} box payload (AVCDecoderConfigurationRecord).
     */
    static byte[] probeAvcC(Tools tools, Path mp4, JobLog log) throws ToolException, InterruptedException, IOException {
        ToolCall call = tools.ffprobe("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=extradata",
                "-show_data", "-of", "default=noprint_wrappers=1", mp4.toString()).stdoutToString(1024 * 1024);
        if (log != null) {
            call.log(log);
        }
        byte[] avcC = parseHexDump(call.run().stdoutString());
        checkAvcC(avcC);
        return avcC;
    }

    /**
     * Parses ffprobe's {@code extradata=} hex dump: lines {@code 00000010: 2dd8 0880 ...  ascii},
     * with the hex area in columns 10 to 48.
     */
    static byte[] parseHexDump(String out) throws IOException {
        int start = out.indexOf("extradata=");
        if (start < 0) {
            throw new IOException("ffprobe reported no extradata");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String[] lines = out.substring(start).split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (!line.matches("^[0-9a-f]{8}: .*")) {
                break;
            }
            if (Integer.parseInt(line.substring(0, 8), 16) != bytes.size()) {
                throw new IOException("unexpected extradata offset: " + line);
            }
            String hex = line.substring(10, Math.min(line.length(), 49)).replace(" ", "");
            try {
                bytes.writeBytes(HexFormat.of().parseHex(hex));
            } catch (IllegalArgumentException e) {
                throw new IOException("bad extradata line: " + line, e);
            }
        }
        return bytes.toByteArray();
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

    public OutputProfile profile() {
        return profile;
    }

    /** The encoded reference card ({@code reference.mp4}); may be missing if {@link #encoderChanged()}. */
    public Path card() {
        return card;
    }

    /** The reference avcC bytes (a copy). */
    public byte[] avcC() {
        return avcC.clone();
    }

    /** Whether this call created the reference (first start for this profile). */
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
            throw new AvcCMismatchException(what + ": avcC differs from the " + profile + " reference card (expected "
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
