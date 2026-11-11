package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.media.AvcCFields;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * The server's reference card for one output encoding (profile and video bitrate), and its avcC: the invariant reference
 * (invariant 1). Every encode that feeds the packager (mezzanines, cards, overlay re-encodes) must
 * produce exactly these avcC bytes; ingest and the overlay encoder compare with {@link #verify}
 * and fail the job on a difference ({@code docs/library-and-ingest.md#the-mezzanine-profile}).
 *
 * <p>The card is a black still at the profile's size, encoded like every card
 * ({@link #encodeCard}). It lives in
 * {@code <data>/cards/<encoding>/reference.mp4} (e.g. {@code cards/720p30-3000k-v3/}), with its avcC in
 * {@code reference.avcC}. Each encoding (profile and bitrate cap) has its own reference, like its
 * own cards; the bitrate is no longer in the SPS (no HRD), so references of one profile at
 * different caps hold the same avcC.
 *
 * <p>{@link #ensure} runs at startup. The first time, it creates both files. Afterwards the stored
 * avcC stays the reference, because the library was encoded against it, but a fresh card is still
 * encoded and compared: if the installed ffmpeg/x264 now writes different bytes (e.g. after an
 * upgrade), {@link #encoderChanged()} is true, a warning is due, and new encodes will fail the
 * check until the stored reference is replaced and the library re-encoded.
 *
 * <p>{@link #ensure} also checks the video path ({@link #checkVideoPath}): a few frames of a
 * BT.709-tagged, an untagged and a full-range BT.601 clip, encoded like a mezzanine, must give the
 * reference avcC too. If one doesn't (an ffmpeg whose colour handling the encode profile doesn't
 * pin yet), {@link #spliceProblem()} names the differing SPS fields, it is logged once as an error,
 * and ingest refuses video encodes up front ({@link #requireSpliceableVideo()}) instead of failing
 * every job after the encode.
 */
public final class ReferenceCard {
    private static final System.Logger LOG = System.getLogger(ReferenceCard.class.getName());

    /** File name of the encoded reference card in the profile's cards directory. */
    public static final String CARD_FILE = "reference.mp4";
    /** File name of the reference avcC (raw bytes of the {@code avcC} box payload). */
    public static final String AVCC_FILE = "reference.avcC";

    private final OutputEncoding encoding;
    private final Path card;
    private final byte[] avcC;
    private final byte[] currentAvcC;
    private final boolean created;
    private final String spliceProblem;

    private ReferenceCard(OutputEncoding encoding, Path card, byte[] avcC, byte[] currentAvcC, boolean created,
                          String spliceProblem) {
        this.encoding = encoding;
        this.card = card;
        this.avcC = avcC;
        this.currentAvcC = currentAvcC;
        this.created = created;
        this.spliceProblem = spliceProblem;
    }

    /** A reference made up for tests (no file, no encode). */
    static ReferenceCard forTest(OutputEncoding encoding, Path card, byte[] avcC) {
        return forTest(encoding, card, avcC, null);
    }

    /** {@link #forTest(OutputEncoding, Path, byte[])} with a {@link #spliceProblem()}. */
    static ReferenceCard forTest(OutputEncoding encoding, Path card, byte[] avcC, String spliceProblem) {
        return new ReferenceCard(encoding, card, avcC.clone(), avcC.clone(), false, spliceProblem);
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
            if (!encodeCard(tools, png, mp4, encoding, log)) {
                warnNoSkipFrameCards(encoding);
            }
            byte[] fresh = readAvcC(mp4);

            if (Files.exists(avcFile)) {
                byte[] stored = Files.readAllBytes(avcFile);
                try {
                    checkAvcC(stored);
                } catch (IOException e) {
                    throw new IOException(avcFile + ": " + e.getMessage(), e);
                }
                String problem = null;
                if (Arrays.equals(stored, fresh)) {
                    // a fresh card in the current card format (CardEncoder.CARD_VERSION) replaces the stored one
                    CardEncoder.move(mp4, card);
                    // (when the encoder changed, every encode fails the check anyway, with its own warning)
                    problem = checkVideoPath(tools, dir, encoding, stored, log);
                }
                return new ReferenceCard(encoding, card, stored, fresh, false, problem);
            }
            Path avcTmp = Files.createTempFile(dir, ".reference-", ".avcC");
            try {
                Files.write(avcTmp, fresh);
                CardEncoder.move(mp4, card);
                CardEncoder.move(avcTmp, avcFile);
            } finally {
                Files.deleteIfExists(avcTmp);
            }
            return new ReferenceCard(encoding, card, fresh, fresh, true, checkVideoPath(tools, dir, encoding, fresh, log));
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
        }
    }

    /** A test clip of the video path check: what it stands for, and its lavfi source. */
    record TestClip(String what, String source) {
    }

    /**
     * The clips {@link #checkVideoPath} encodes: frames tagged like a typical download (BT.709),
     * untagged frames (like most uploads and every card image) and full-range BT.601 frames. Each
     * is a lavfi source, so its frames carry exactly these colour properties into the filter chain,
     * as a decoder's would.
     */
    static final List<TestClip> TEST_CLIPS = List.of(
            new TestClip("a BT.709-tagged video", "testsrc2=size=320x180:rate=30,format=yuv420p,"
                    + "setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709"),
            new TestClip("an untagged video", "testsrc2=size=320x180:rate=30,format=yuv420p"),
            new TestClip("a full-range BT.601 video", "testsrc2=size=320x180:rate=30,format=yuv420p,"
                    + "setparams=range=pc:color_primaries=smpte170m:color_trc=smpte170m:colorspace=smpte170m"));

    /** Frames per test clip: enough for an IDR and a P-frame. */
    static final int TEST_CLIP_FRAMES = 2;

    /** The ffmpeg arguments that encode {@code clip} like a mezzanine's video (reference preset). */
    static List<String> testClipCommand(TestClip clip, Path out, OutputEncoding encoding) {
        List<String> args = new ArrayList<>(List.of("-y", "-f", "lavfi", "-i", clip.source(),
                "-frames:v", Integer.toString(TEST_CLIP_FRAMES), "-vf", EncodeProfile.scaleFilter(encoding)));
        args.addAll(EncodeProfile.videoArgs(encoding, X264Preset.REFERENCE));
        args.addAll(List.of("-an", "-f", "mp4", out.toString()));
        return args;
    }

    /** Whether {@link #logSpliceProblem} has logged already (once per process is enough). */
    private static final AtomicBoolean LOGGED_SPLICE_PROBLEM = new AtomicBoolean();

    /**
     * The startup self-check of the video path: encodes each of {@link #TEST_CLIPS} like a
     * mezzanine and compares its avcC with {@code reference}. Returns null when all match, or a
     * description of the first mismatch naming the differing SPS fields
     * ({@link AvcCFields#differences}), which is also logged once as an error. If ffmpeg can't
     * encode a clip, that is logged as a warning and the check passes (the real encodes then report
     * their own errors).
     *
     * @param dir the encoding's cards directory (temporary files only)
     */
    static String checkVideoPath(Tools tools, Path dir, OutputEncoding encoding, byte[] reference, JobLog log)
            throws IOException, InterruptedException {
        for (TestClip clip : TEST_CLIPS) {
            Path mp4 = Files.createTempFile(dir, ".check-", ".mp4");
            try {
                byte[] avcC;
                try {
                    tools.ffmpeg(testClipCommand(clip, mp4, encoding)).timeout(CardEncoder.ENCODE_TIMEOUT).log(log).run();
                    avcC = Mp4Boxes.readAvcC(mp4);
                } catch (ToolException | IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "Could not check the video path for " + encoding.id()
                            + " with " + clip.what() + ": " + e.getMessage());
                    continue;
                }
                if (!Arrays.equals(reference, avcC)) {
                    String problem = clip.what() + " is encoded with a different SPS than the " + encoding.id()
                            + " reference card: " + AvcCFields.differences(reference, avcC);
                    logSpliceProblem(problem);
                    return problem;
                }
            } finally {
                Files.deleteIfExists(mp4);
            }
        }
        return null;
    }

    /** The error logged once when {@link #checkVideoPath} finds a mismatch. */
    static String spliceProblemMessage(String problem) {
        return "This ffmpeg produces encodes that can't be spliced: " + problem
                + ". Video ingest is refused until ffmpeg is changed (see the deployment notes for tested versions).";
    }

    private static void logSpliceProblem(String problem) {
        if (LOGGED_SPLICE_PROBLEM.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.ERROR, spliceProblemMessage(problem));
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

    /**
     * The card encode ({@link CardEncoder#encodeCard}: a skip-frame card, or the full x264 encode if
     * that fails); no avcC check, this card defines it.
     */
    static boolean encodeCard(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log)
            throws ToolException, IOException, InterruptedException {
        return CardEncoder.encodeCard(tools, png, out, encoding, log);
    }

    /** Whether {@link #warnNoSkipFrameCards} has logged already (once per process is enough). */
    private static final AtomicBoolean WARNED_NO_SKIP_FRAMES = new AtomicBoolean();

    /** The message logged once when the reference card couldn't be made as a skip-frame card. */
    static String noSkipFrameCardsMessage(OutputEncoding encoding) {
        return "This ffmpeg can't make skip-frame cards (the reference card for " + encoding.id()
                + " fell back to a full x264 encode; the reason is in the warning above). Every card will be"
                + " encoded as a whole x264 GOP instead, which costs about as much as video: audio-only rooms'"
                + " cards then run over output.audioOnlyBitrateKbps, so their streams aren't constant-rate."
                + " Use a supported ffmpeg (see the deployment notes) to fix this.";
    }

    /** Logs {@link #noSkipFrameCardsMessage} once per process. */
    static void warnNoSkipFrameCards(OutputEncoding encoding) {
        if (WARNED_NO_SKIP_FRAMES.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, noSkipFrameCardsMessage(encoding));
        }
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

    /**
     * Whether an encode with {@code preset} gives exactly the reference avcC: encodes the
     * reference's black card with that preset (in the encoding's cards directory, as temporary
     * files) and compares. {@link X264Preset#REFERENCE} is not encoded again: it made the
     * reference, so it matches unless {@link #encoderChanged()}.
     * ({@code docs/library-and-ingest.md#presets}; the presets are built to match, but a different
     * x264 build may not.)
     *
     * @param cardsDir the server's cards directory ({@code <data>/cards})
     */
    public boolean presetMatches(Tools tools, Path cardsDir, X264Preset preset)
            throws ToolException, IOException, InterruptedException {
        if (preset == X264Preset.REFERENCE) {
            return !encoderChanged();
        }
        return matches(presetAvcC(tools, cardsDir, preset));
    }

    /** The avcC of the reference's black card encoded with {@code preset}. */
    byte[] presetAvcC(Tools tools, Path cardsDir, X264Preset preset)
            throws ToolException, IOException, InterruptedException {
        Path dir = encodingDir(cardsDir, encoding);
        Files.createDirectories(dir);
        Path png = Files.createTempFile(dir, ".preset-", ".png");
        Path mp4 = Files.createTempFile(dir, ".preset-", ".mp4");
        try {
            writeBlackPng(png, encoding);
            CardEncoder.encodePng(tools, png, mp4, encoding, preset, null);
            return Mp4Boxes.readAvcC(mp4);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
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
     * Why video encodes with this ffmpeg wouldn't match the reference avcC (from the startup
     * self-check, {@link #checkVideoPath}), or null if they do.
     */
    public String spliceProblem() {
        return spliceProblem;
    }

    /**
     * Fails a job that would encode video when {@link #spliceProblem()} is set, before any work is
     * done, with "This ffmpeg produces encodes that can't be spliced: …".
     */
    public void requireSpliceableVideo() throws IngestFailure {
        if (spliceProblem != null) {
            throw new IngestFailure("This ffmpeg produces encodes that can't be spliced: " + spliceProblem);
        }
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
