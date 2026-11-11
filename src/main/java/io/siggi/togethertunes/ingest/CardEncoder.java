package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.X264Preset;
import io.siggi.togethertunes.media.AnnexB;
import io.siggi.togethertunes.media.EncodeProfile;
import io.siggi.togethertunes.media.Mp4Boxes;
import io.siggi.togethertunes.media.SampleIndex;
import io.siggi.togethertunes.media.SkipFrames;
import io.siggi.togethertunes.media.StillGopMp4;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Encodes a card: a still image as one closed 2 s GOP (one IDR, 59 P-frames) with the profile
 * constant, so the packager can splice it anywhere ({@code docs/library-and-ingest.md#cards}).
 *
 * <p><b>Skip-frame cards</b> ({@link #CARD_VERSION} 2 and up). x264 encodes only the IDR:
 * {@code ffmpeg -loop 1 -framerate 30 -i card.png -frames:v 1 -vf <scaleFilter> <profile args> -an
 * -video_track_timescale 15360 -f mp4 idr.mp4}, the same shared arguments as every encode, so the
 * SPS and PPS are the reference's (invariant 1). The other 59 frames are written by
 * {@link SkipFrames}: P slices in which every macroblock is skipped, a few bytes each, which decode
 * to exactly the IDR's picture. {@link StillGopMp4} appends them to ffmpeg's one-frame MP4 (frame
 * {@code n} at {@code n/30 s}), keeping ffmpeg's sample description and avcC byte for byte; no
 * ffmpeg remux is involved, because its timestamp options changed meaning between versions (an
 * {@code -bsf:v setts=…:time_base=1/30} remux put the frames at the wrong times on ffmpeg 5.1 and
 * on 2026 builds, found 2026-11-10). Before it is used, the card is decoded with ffmpeg and checked: 60
 * frames, all pixel-identical to the IDR (frame checksums), one IDR first, 30 fps. A full x264
 * encode of the still refines the picture for the whole GOP and costs about as much as video
 * (800+ kb/s for a detailed cover); a skip-frame card costs the IDR alone.
 *
 * <p><b>Fallback.</b> If anything in the skip-frame path fails, the card is encoded the old way,
 * the whole GOP with x264 ({@code -t 2} instead of {@code -frames:v 1}, written as MP4), with a
 * log line, so a card is never missing. Either way, every card is checked against the profile's
 * reference avcC (invariant 1) before it is moved into place; a card that doesn't match is never
 * published.
 */
public final class CardEncoder {
    private static final System.Logger LOG = System.getLogger(CardEncoder.class.getName());

    /** Time limit for one card encode (a 2 s still; normally well under a second). */
    public static final Duration ENCODE_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Version of the card format. 1: the whole GOP encoded by x264. 2: skip-frame cards (an x264
     * IDR plus 59 written P_Skip frames), 2026-11-10. 3: the same, with the MP4 written by
     * {@link StillGopMp4} instead of an ffmpeg remux (2026-11-10; with current ffmpeg builds the
     * remux failed the check and format-2 cards fell back to full x264 GOPs, which this rebuilds).
     * Filler cards include it in their fingerprint
     * and items record it ({@code items.card_version}), so cards of an older format are made again
     * (without re-encoding videos) when it changes.
     */
    public static final int CARD_VERSION = 3;

    /** The time base the card MP4 is written with (as an x264 encode at 30 fps gets it). */
    static final int MP4_TIMESCALE = 15360;

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
            encodeCard(tools, png, mp4, reference.encoding(), log);
            reference.verify(ReferenceCard.readAvcC(mp4), out.getFileName().toString());
            move(mp4, out);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(mp4);
        }
    }

    /**
     * Makes a card from a PNG at {@code out}: a skip-frame card ({@link #encodeSkipFrames}), or, if
     * that fails, the full x264 encode ({@link #encodePng}). No avcC check against a reference
     * (the caller does that, or the card defines the reference).
     *
     * @param log job log for the ffmpeg output and the fallback note, or null
     * @return true for a skip-frame card, false if it fell back to the full x264 encode
     * @throws ToolException if the full encode fails too
     */
    static boolean encodeCard(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log)
            throws ToolException, IOException, InterruptedException {
        return encodeCard(tools, png, out, encoding, log, CardEncoder::encodeSkipFrames);
    }

    /** How a skip-frame card is made ({@link #encodeSkipFrames}; tests make it fail to try the fallback). */
    @FunctionalInterface
    interface SkipFrameMaker {
        void make(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log) throws Exception;
    }

    /** {@link #encodeCard(Tools, Path, Path, OutputEncoding, JobLog)} with the skip-frame step {@code skipFrames}. */
    static boolean encodeCard(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log,
                              SkipFrameMaker skipFrames) throws ToolException, IOException, InterruptedException {
        try {
            skipFrames.make(tools, png, out, encoding, log);
            return true;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.log(System.Logger.Level.WARNING, "Skip-frame card failed for " + out.getFileName()
                    + "; encoding the whole GOP with x264 instead: " + why);
            if (log != null) {
                log.append("[card] skip-frame card failed (" + why + "); encoding the whole GOP with x264 instead");
            }
        }
        encodePng(tools, png, out, encoding, log);
        return false;
    }

    /**
     * The ffmpeg arguments that encode only the IDR of a card, as a one-frame MP4 in the time base
     * x264 encodes get ({@value #MP4_TIMESCALE}).
     */
    static List<String> idrCommand(Path png, Path out, OutputEncoding encoding) {
        List<String> args = new ArrayList<>(List.of("-y", "-loop", "1", "-framerate", Integer.toString(encoding.fps()),
                "-i", png.toString(), "-frames:v", "1", "-vf", EncodeProfile.scaleFilter(encoding)));
        args.addAll(EncodeProfile.videoArgs(encoding, X264Preset.REFERENCE));
        args.addAll(List.of("-an", "-video_track_timescale", Integer.toString(MP4_TIMESCALE), "-f", "mp4",
                out.toString()));
        return args;
    }

    /**
     * Makes a skip-frame card at {@code out} (see the class comment) and checks it by decoding.
     * Temporary files go next to {@code out} and are deleted.
     *
     * @throws IOException                          if the IDR encode can't be used or the check fails
     * @throws SkipFrames.UnsupportedStreamException if the SPS/PPS need something the writer lacks
     */
    static void encodeSkipFrames(Tools tools, Path png, Path out, OutputEncoding encoding, JobLog log)
            throws ToolException, IOException, InterruptedException, SkipFrames.UnsupportedStreamException {
        Path dir = out.toAbsolutePath().getParent();
        String base = "." + out.getFileName();
        Path idr = Files.createTempFile(dir, base + "-idr-", ".mp4");
        try {
            ToolCall call = tools.ffmpeg(idrCommand(png, idr, encoding)).timeout(ENCODE_TIMEOUT);
            if (log != null) {
                call.log(log);
            }
            call.run();
            Files.write(out, skipFrameCard(Files.readAllBytes(idr)));
            check(tools, out, encoding, log);
        } finally {
            Files.deleteIfExists(idr);
        }
    }

    /**
     * A whole card GOP as an MP4: ffmpeg's one-frame MP4 {@code idrMp4} (x264's IDR) with
     * {@value EncodeProfile#GOP_FRAMES}−1 skip frames made for its SPS and PPS appended, frame
     * {@code n} at {@code n} frame durations. The MP4 is written by {@link StillGopMp4}, which keeps
     * ffmpeg's sample description (and so its avcC) byte for byte.
     *
     * @throws IOException if the MP4 isn't one frame holding an IDR slice (and nothing else but SEI,
     *                     AUDs and parameter sets), or has a layout {@link StillGopMp4} refuses
     */
    static byte[] skipFrameCard(byte[] idrMp4) throws IOException, SkipFrames.UnsupportedStreamException {
        StillGopMp4.OneFrame frame = StillGopMp4.read(idrMp4);
        StillGopMp4.AvcConfig config = StillGopMp4.avcConfig(frame.avcC());
        int lengthSize = config.nalLengthSize();
        boolean idr = false;
        byte[] sample = frame.sample();
        for (int pos = 0; pos < sample.length; ) {
            if (sample.length - pos < lengthSize) {
                throw new IOException("the IDR sample is truncated");
            }
            int len = 0;
            for (int k = 0; k < lengthSize; k++) {
                len = len << 8 | sample[pos + k] & 0xFF;
            }
            pos += lengthSize;
            if (len <= 0 || len > sample.length - pos) {
                throw new IOException("the IDR sample has a bad NAL length " + len);
            }
            int type = sample[pos] & 0x1F;
            switch (type) {
                case AnnexB.NAL_IDR -> idr = true;
                case AnnexB.NAL_SEI, AnnexB.NAL_AUD, AnnexB.NAL_SPS, AnnexB.NAL_PPS -> {
                }
                default -> throw new IOException("the IDR sample has a NAL unit of type " + type);
            }
            pos += len;
        }
        if (!idr) {
            throw new IOException("the IDR sample has no IDR slice");
        }
        SkipFrames writer = new SkipFrames(config.sps(), config.pps());
        List<byte[]> more = new ArrayList<>(EncodeProfile.GOP_FRAMES - 1);
        for (int i = 1; i < EncodeProfile.GOP_FRAMES; i++) {
            byte[] nal = writer.frame(i);
            if (lengthSize < 4 && nal.length >= 1 << (8 * lengthSize)) {
                throw new IOException("a skip frame doesn't fit the NAL length size " + lengthSize);
            }
            byte[] s = new byte[lengthSize + nal.length];
            for (int k = 0; k < lengthSize; k++) {
                s[k] = (byte) (nal.length >>> (8 * (lengthSize - 1 - k)));
            }
            System.arraycopy(nal, 0, s, lengthSize, nal.length);
            more.add(s);
        }
        return StillGopMp4.extend(idrMp4, more);
    }

    /**
     * Checks a made card by decoding it: {@value EncodeProfile#GOP_FRAMES} frames, all with the
     * IDR's pixels (ffmpeg's per-frame MD5), and as packets an IDR first and only then, frame
     * {@code n} at {@code n/fps} in a {@code 1/}{@link #MP4_TIMESCALE} time base.
     *
     * @throws IOException if the card isn't like that
     */
    static void check(Tools tools, Path card, OutputEncoding encoding, JobLog log)
            throws ToolException, IOException, InterruptedException {
        List<String> hashes = new ArrayList<>();
        ToolCall decode = tools.ffmpeg("-v", "error", "-xerror", "-err_detect", "+explode", "-i", card.toString(), "-map", "0:v:0",
                "-fps_mode", "passthrough", "-f", "framemd5", "-").timeout(ENCODE_TIMEOUT).stdoutLines(line -> {
                    if (!line.startsWith("#") && !line.isBlank()) {
                        String[] f = line.split(",");
                        hashes.add(f[f.length - 1].trim());
                    }
                });
        if (log != null) {
            decode.log(log);
        }
        decode.run();
        if (hashes.size() != EncodeProfile.GOP_FRAMES) {
            throw new IOException("the card decodes to " + hashes.size() + " frames, not " + EncodeProfile.GOP_FRAMES);
        }
        if (new HashSet<>(hashes).size() != 1) {
            throw new IOException("the card's frames don't all decode to the IDR's picture");
        }
        SampleIndex.Track track = IndexBuilder.probeTrack(tools, card, SampleIndex.Kind.VIDEO, 0, log);
        if (track.sampleCount() != EncodeProfile.GOP_FRAMES) {
            throw new IOException("the card has " + track.sampleCount() + " packets, not " + EncodeProfile.GOP_FRAMES);
        }
        if (track.timescale() % encoding.fps() != 0) {
            throw new IOException("the card's time base 1/" + track.timescale() + " doesn't fit " + encoding.fps() + " fps");
        }
        long frameTicks = track.timescale() / encoding.fps();
        for (int i = 0; i < track.sampleCount(); i++) {
            if (track.isKeyframe(i) != (i == 0)) {
                throw new IOException("card frame " + i + (i == 0 ? " isn't" : " is") + " a keyframe");
            }
            if (track.pts(i) != i * frameTicks) {
                throw new IOException("card frame " + i + " is at " + track.pts(i) + "/" + track.timescale()
                        + " s, not " + i * frameTicks);
            }
        }
        if (Mp4Boxes.readAvcC(card).length == 0) {
            throw new IOException("the card has no avcC");
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
