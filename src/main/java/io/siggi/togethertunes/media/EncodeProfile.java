package io.siggi.togethertunes.media;

import io.siggi.togethertunes.config.OutputProfile;
import java.util.List;

/**
 * The x264 arguments shared by every encode that feeds the packager: mezzanines, cards and
 * overlay re-encodes (invariant 1). Their H.264 extradata must be byte-identical, and the x264
 * preset changes SPS fields, so these lists are the <em>only</em> place the encoder settings
 * live. Never add, drop or reorder encoder arguments next to them at a call site.
 *
 * <p>Every list gives: libx264, preset veryfast, High profile, yuv420p, no B-frames (so PTS =
 * DTS), closed 60-frame GOPs with an IDR forced every 2 s from t = 0 and no scene-cut keyframes
 * (invariant 2), CRF 20 with a VBV cap. They contain only output (encoder) options, so a call
 * site puts them after its inputs and filters, e.g.
 * {@code ffmpeg -loop 1 -framerate 30 -i card.png -t 2 <videoArgs> -an card.mp4}.
 *
 * <p>The frame size and rate are not set here: the input has to arrive at the profile's size and
 * 30 fps already, from {@link #scaleFilter(OutputProfile)} (mezzanines), a card image rendered at
 * the profile size with {@code -framerate 30}, or the mezzanine itself (overlay re-encodes).
 * See {@code docs/library-and-ingest.md#the-mezzanine-profile}.
 */
public final class EncodeProfile {
    /** Frames per GOP: one IDR followed by 59 P-frames. */
    public static final int GOP_FRAMES = 60;
    /** GOP length in milliseconds; every GOP starts with an IDR exactly this far apart. */
    public static final int GOP_MS = 2000;

    /** x264 arguments for {@link OutputProfile#P720_30}. */
    public static final List<String> VIDEO_ARGS_720P30 = videoArgs("4.0", "3M", "6M");
    /** x264 arguments for {@link OutputProfile#P1080_30}. */
    public static final List<String> VIDEO_ARGS_1080P30 = videoArgs("4.1", "6M", "12M");

    private EncodeProfile() {
    }

    private static List<String> videoArgs(String level, String maxrate, String bufsize) {
        return List.of(
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-profile:v", "high",
                "-level:v", level,
                "-pix_fmt", "yuv420p",
                "-bf", "0",
                "-g", Integer.toString(GOP_FRAMES),
                "-keyint_min", Integer.toString(GOP_FRAMES),
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + (GOP_MS / 1000) + ")",
                "-x264-params", "open-gop=0",
                "-crf", "20",
                "-maxrate", maxrate,
                "-bufsize", bufsize);
    }

    /** The x264 argument list for {@code profile} (immutable; the same instance every call). */
    public static List<String> videoArgs(OutputProfile profile) {
        return switch (profile) {
            case P720_30 -> VIDEO_ARGS_720P30;
            case P1080_30 -> VIDEO_ARGS_1080P30;
        };
    }

    /**
     * The {@code -vf} chain that brings any source video to {@code profile}: scale to fit, pad to
     * the exact size with black (centred), square pixels, constant frame rate. The scaled
     * picture keeps even dimensions so the 4:2:0 padding stays centred on whole chroma samples.
     */
    public static String scaleFilter(OutputProfile profile) {
        int w = profile.width();
        int h = profile.height();
        return "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:force_divisible_by=2,"
                + "pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2,"
                + "setsar=1,"
                + "fps=" + profile.fps();
    }
}
