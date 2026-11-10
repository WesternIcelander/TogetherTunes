package io.siggi.togethertunes.config;

import java.util.Objects;

/**
 * What every encode that feeds the packager is made for: the output profile (size and frame rate)
 * plus the video bitrate cap ({@code output.profile} and {@code output.videoBitrateKbps}). The
 * profile is in the H.264 SPS; the bitrate is not (no HRD parameters since {@link #ARGS_VERSION} 3),
 * but it caps every encode, and it is also the program output's default constant stream rate (the
 * packager pads up to it). Items, cards and the reference card are made per encoding, and changing
 * either one re-encodes the library (a higher cap needs new encodes to bring more quality).
 *
 * <p>{@link #id()} names the encoding where it is stored: {@code items.output_profile}, the cards
 * directory {@code <data>/cards/<id>/}, and log lines, e.g. {@code 720p30-3000k-v3}. The
 * {@code -v<n>} suffix is {@link #ARGS_VERSION}: it changes whenever the shared encoder arguments
 * change the avcC for the same profile and bitrate, so that every item gets re-encoded and a new
 * reference card is made.
 */
public record OutputEncoding(OutputProfile profile, int videoBitrateKbps) {
    /** Lowest allowed {@code output.videoBitrateKbps}. */
    public static final int MIN_VIDEO_BITRATE_KBPS = 500;
    /**
     * Highest allowed {@code output.videoBitrateKbps}: H.264 level 4.0's MaxBR for High profile
     * (25 Mb/s; the 720p30 encodes are level 4.0).
     */
    public static final int MAX_VIDEO_BITRATE_KBPS = 25000;
    /**
     * Default constant stream rate of audio-only rooms ({@code output.audioOnlyBitrateKbps}): their
     * program output shows only cards, which need far less than the cap; the packager pads to this.
     * Same allowed range as the video bitrate.
     */
    public static final int DEFAULT_AUDIO_ONLY_BITRATE_KBPS = 800;

    /**
     * Version of the shared encoder arguments ({@code media.EncodeProfile}) as far as they shape the
     * avcC. 1 (no suffix): CBR, 2026-11-10. 2: {@code ref=4} and the per-preset
     * {@code chroma-qp-offset}, so every x264 preset writes the same SPS/PPS (2026-11-10). 3: no
     * {@code nal-hrd=cbr} (capped rate, no HRD in the SPS, no filler NALs on disk; the packager
     * pads the output, 2026-11-10).
     */
    public static final int ARGS_VERSION = 3;

    /** The default encoding: the default profile at its default bitrate. */
    public static final OutputEncoding DEFAULT = defaultFor(OutputProfile.DEFAULT);

    public OutputEncoding {
        Objects.requireNonNull(profile, "profile");
        if (videoBitrateKbps < MIN_VIDEO_BITRATE_KBPS || videoBitrateKbps > MAX_VIDEO_BITRATE_KBPS) {
            throw new IllegalArgumentException("video bitrate " + videoBitrateKbps + " kb/s is outside "
                    + MIN_VIDEO_BITRATE_KBPS + "…" + MAX_VIDEO_BITRATE_KBPS);
        }
    }

    /** The bitrate used when {@code output.videoBitrateKbps} is not set: 3000 at 720p30, 6000 at 1080p30. */
    public static int defaultVideoBitrateKbps(OutputProfile profile) {
        return switch (profile) {
            case P720_30 -> 3000;
            case P1080_30 -> 6000;
        };
    }

    /** {@code profile} at its default bitrate. */
    public static OutputEncoding defaultFor(OutputProfile profile) {
        return new OutputEncoding(profile, defaultVideoBitrateKbps(profile));
    }

    /** The stored name, {@code <profile id>-<kbps>k-v<args version>}, e.g. {@code 720p30-3000k-v3}. */
    public String id() {
        return profile.id() + "-" + videoBitrateKbps + "k-v" + ARGS_VERSION;
    }

    /** The video bitrate in bits per second. */
    public long videoBitrateBps() {
        return videoBitrateKbps * 1000L;
    }

    public int width() {
        return profile.width();
    }

    public int height() {
        return profile.height();
    }

    public int fps() {
        return profile.fps();
    }

    @Override
    public String toString() {
        return id();
    }
}
