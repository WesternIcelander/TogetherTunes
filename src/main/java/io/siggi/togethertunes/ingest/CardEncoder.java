package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.media.EncodeProfile;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Encodes a card: a still image as one closed 2 s GOP (one IDR, 59 P-frames) with the profile
 * constant, so the packager can splice it anywhere ({@code docs/library-and-ingest.md#cards}).
 *
 * <p>The command is {@code ffmpeg -loop 1 -framerate 30 -i card.png -t 2 -vf <scaleFilter>
 * <profile args> -an card.mp4}. Every card is checked against the profile's reference avcC
 * (invariant 1) before it is moved into place; a card that doesn't match is never published.
 */
public final class CardEncoder {
    /** Time limit for one card encode (a 2 s still; normally well under a second). */
    public static final Duration ENCODE_TIMEOUT = Duration.ofMinutes(2);

    private CardEncoder() {
    }

    /**
     * Runs the card command on a PNG. The filter matters: a PNG without a {@code pHYs} chunk
     * (Java2D's ImageIO writes none) has an unknown sample aspect ratio, and x264 then leaves the
     * aspect ratio out of the SPS, so the avcC would differ from the mezzanines' ({@code setsar=1}).
     * The scale/pad part is a no-op for an image at the profile size and letterboxes any other
     * size. No avcC check: see {@link #encode} for that.
     *
     * @param log job log for the ffmpeg output, or null
     */
    public static void encodePng(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log)
            throws ToolException, InterruptedException {
        encodePng(tools, png, out, encoding, X264Preset.REFERENCE, log);
    }

    /**
     * {@link #encodePng(Tools, Path, Path, OutputEncoding, JobLog)} with another x264 preset (the
     * startup and admin preset check, {@link ReferenceCard#presetMatches}). Cards themselves always
     * use {@link X264Preset#REFERENCE}.
     */
    public static void encodePng(Tools tools, Path png, Path out, OutputEncoding encoding, X264Preset preset,
                                 JobLog log) throws ToolException, InterruptedException {
        ToolCall call = tools.ffmpeg(command(png, out, encoding, preset)).timeout(ENCODE_TIMEOUT);
        if (log != null) {
            call.log(log);
        }
        call.run();
    }

    /** The ffmpeg arguments of the card command (without the {@code -hide_banner -nostdin} prefix). */
    static List<String> command(Path png, Path out, OutputEncoding encoding) {
        return command(png, out, encoding, X264Preset.REFERENCE);
    }

    static List<String> command(Path png, Path out, OutputEncoding encoding, X264Preset preset) {
        List<String> args = new ArrayList<>(List.of("-y", "-loop", "1", "-framerate", Integer.toString(encoding.fps()),
                "-i", png.toString(), "-t", Integer.toString(EncodeProfile.GOP_MS / 1000),
                "-vf", EncodeProfile.scaleFilter(encoding)));
        args.addAll(EncodeProfile.videoArgs(encoding, preset));
        args.addAll(List.of("-an", "-f", "mp4", out.toString()));
        return args;
    }

    /**
     * Encodes {@code image} as a card for the reference's profile and publishes it at {@code out}:
     * the PNG and the encode go to temporary files next to {@code out}, the encode's avcC is
     * verified against {@code reference}, and only then is it renamed over {@code out} (atomic
     * where the file system allows). On any failure {@code out} is left as it was.
     *
     * @param log job log for the ffmpeg output, or null
     * @throws ReferenceCard.AvcCMismatchException if the encode's avcC differs from the reference
     */
    public static void encode(Tools tools, BufferedImage image, Path out, ReferenceCard reference, JobLog log)
            throws ToolException, IOException, InterruptedException, ReferenceCard.AvcCMismatchException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(reference, "reference");
        Path dir = out.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        String base = "." + out.getFileName();
        Path png = Files.createTempFile(dir, base + "-", ".png");
        Path mp4 = Files.createTempFile(dir, base + "-", ".mp4");
        try {
            CardRenderer.writePng(image, png);
            encodePng(tools, png, mp4, reference.encoding(), log);
            reference.verify(ReferenceCard.readAvcC(mp4), out.getFileName().toString());
            move(mp4, out);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
        }
    }

    /** Renames {@code from} over {@code to}, atomically if the file system supports it. */
    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
