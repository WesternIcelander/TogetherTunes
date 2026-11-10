package io.siggi.togethertunes.media;

import io.siggi.togethertunes.config.OutputEncoding;
import io.siggi.togethertunes.config.OutputProfile;
import io.siggi.togethertunes.config.X264Preset;
import java.util.List;

/**
 * The x264 arguments shared by every encode that feeds the packager: mezzanines, cards and
 * overlay re-encodes (invariant 1). Their H.264 extradata must be byte-identical, so these lists
 * plus the per-preset table ({@link X264Preset}) are the <em>only</em> place the encoder settings
 * live. Never add, drop or reorder encoder arguments next to them at a call site.
 *
 * <p>Every list gives: libx264, the chosen preset (with its {@code chroma-qp-offset} and
 * {@code ref=4} pinned, so all presets write the same SPS and PPS), High profile, yuv420p, no B-frames (so PTS =
 * DTS), closed 60-frame GOPs with an IDR forced every 2 s from t = 0 and no scene-cut keyframes
 * (invariant 2), constant bitrate at the configured rate with filler NAL padding (so cards and
 * mezzanines alike are exactly CBR; {@code docs/library-and-ingest.md#the-mezzanine-profile}).
 * They contain only output (encoder) options, so a call
 * site puts them after its inputs and filters, e.g.
 * {@code ffmpeg -loop 1 -framerate 30 -i card.png -t 2 -vf <scaleFilter> <videoArgs> -an card.mp4}.
 *
 * <p>The frame size and rate are not set here: the input has to arrive at the profile's size and
 * 30 fps already, through {@link #scaleFilter(OutputProfile)} (mezzanines and cards), or as the
 * mezzanine itself (overlay re-encodes). The filter also sets SAR 1:1, which ends up in the SPS:
 * without it, an image with no aspect ratio (a PNG without {@code pHYs}) gives a different avcC.
 *
 * <p>The colour description is fixed too (limited range, BT.709 primaries/transfer/matrix,
 * left-sited chroma): x264 copies the source's colour tags and chroma location into the SPS's
 * VUI, so without this a BT.709-tagged video (any YouTube download), a full-range or BT.601 source
 * or Motion JPEG (centre-sited chroma) would each get a different avcC. The scale filter converts
 * the pixels to match ({@code out_color_matrix=bt709:out_range=tv}). HDR sources are not tone-mapped.
 * See {@code docs/library-and-ingest.md#the-mezzanine-profile}.
 */
public final class EncodeProfile {
    /** Frames per GOP: one IDR followed by 59 P-frames. */
    public static final int GOP_FRAMES = 60;
    /** GOP length in milliseconds; every GOP starts with an IDR exactly this far apart. */
    public static final int GOP_MS = 2000;
    /**
     * x264's VBV buffer ({@code -bufsize}) in milliseconds of video at the constant rate, started
     * full ({@code vbv-init=1}). Measured 2026-11-10 with ffmpeg 6.1.1 at 720p30, 3 Mb/s, bytes per
     * 2 s GOP against {@code R × 2 s / 8} = 750 000: a 2 s still card +0.1 % (−2.4 % without
     * {@code vbv-init=1}, which starts the buffer 90 % full), {@code testsrc2} within ±0.01 %, a
     * noisy fractal source −1…+2 %. With a 1 s buffer the noisy source spread −2…+6 % and
     * {@code testsrc2} −5…+10 %; with 2 s, −10…+18 %. Half a second still leaves an IDR frame
     * up to half a second's worth of bits (~187 KB at 3 Mb/s).
     */
    public static final int BUFSIZE_MS = 500;

    /** AAC sample rate of every encode; one AAC frame is 1024 samples = 1920 ticks at 90 kHz. */
    public static final int AUDIO_SAMPLE_RATE = 48000;
    /** Audio channel count of every encode (stereo). */
    public static final int AUDIO_CHANNELS = 2;
    /** Samples per AAC-LC frame. */
    public static final int AAC_FRAME_SAMPLES = 1024;

    /**
     * The AAC arguments shared by every audio encode (mezzanine audio, audio items, the silence
     * frame): AAC-LC, 48 kHz, stereo, 192 kb/s. Output options only, like the video lists. The
     * packager's ADTS header is fixed to this configuration ({@code docs/program-output.md}).
     */
    public static final List<String> AUDIO_ARGS = List.of(
            "-c:a", "aac",
            "-b:a", "192k",
            "-ar", Integer.toString(AUDIO_SAMPLE_RATE),
            "-ac", Integer.toString(AUDIO_CHANNELS));

    private EncodeProfile() {
    }

    /** Reference frames, pinned for every preset (they change the SPS); fits level 4.1's DPB at 1080p. */
    public static final int REF_FRAMES = 4;

    /** {@link #videoArgs(OutputEncoding, X264Preset)} with the {@link X264Preset#REFERENCE reference preset} (cards). */
    public static List<String> videoArgs(OutputEncoding encoding) {
        return videoArgs(encoding, X264Preset.REFERENCE);
    }

    /**
     * The x264 argument list for {@code encoding} and {@code preset} (immutable): the preset with
     * {@code ref=4} and the preset's {@code chroma-qp-offset} (so every preset gives the same avcC),
     * the profile's H.264 level and
     * constant bitrate {@code R = encoding.videoBitrateKbps()}: {@code -b:v R -minrate R -maxrate R
     * -bufsize R/2} with {@code nal-hrd=cbr:vbv-init=1}, so x264 signals CBR in the SPS (VUI HRD
     * parameters, which carry the rate and buffer size: another bitrate is another avcC) and pads
     * every frame up to the rate with filler data NAL units (type 12). The VBV buffer is
     * {@link #BUFSIZE_MS} of video, started full. Equal encodings and presets give equal lists.
     */
    public static List<String> videoArgs(OutputEncoding encoding, X264Preset preset) {
        String level = switch (encoding.profile()) {
            case P720_30 -> "4.0";
            case P1080_30 -> "4.1";
        };
        String rate = encoding.videoBitrateKbps() + "k";
        String bufsize = (encoding.videoBitrateKbps() * BUFSIZE_MS / 1000) + "k";
        return List.of(
                "-c:v", "libx264",
                "-preset", preset.id(),
                "-profile:v", "high",
                "-level:v", level,
                "-pix_fmt", "yuv420p",
                "-color_range", "tv",
                "-colorspace", "bt709",
                "-color_primaries", "bt709",
                "-color_trc", "bt709",
                "-chroma_sample_location", "left",
                "-bf", "0",
                "-g", Integer.toString(GOP_FRAMES),
                "-keyint_min", Integer.toString(GOP_FRAMES),
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + (GOP_MS / 1000) + ")",
                "-x264-params", "open-gop=0:nal-hrd=cbr:vbv-init=1:ref=" + REF_FRAMES
                        + ":chroma-qp-offset=" + preset.chromaQpOffset(),
                "-b:v", rate,
                "-minrate", rate,
                "-maxrate", rate,
                "-bufsize", bufsize);
    }

    /** {@link #scaleFilter(OutputProfile)} for the encoding's profile. */
    public static String scaleFilter(OutputEncoding encoding) {
        return scaleFilter(encoding.profile());
    }

    /**
     * The {@code -vf} chain that brings any source video or card image to {@code profile}: scale to fit, pad to
     * the exact size with black (centred), square pixels, constant frame rate, pixels converted to
     * limited-range BT.709 (matching the colour description in the video args). The scaled
     * picture keeps even dimensions so the 4:2:0 padding stays centred on whole chroma samples.
     */
    public static String scaleFilter(OutputProfile profile) {
        int w = profile.width();
        int h = profile.height();
        return "scale=" + w + ":" + h + ":force_original_aspect_ratio=decrease:force_divisible_by=2"
                + ":out_color_matrix=bt709:out_range=tv,"
                + "pad=" + w + ":" + h + ":(ow-iw)/2:(oh-ih)/2,"
                + "setsar=1,"
                + "fps=" + profile.fps();
    }
}
