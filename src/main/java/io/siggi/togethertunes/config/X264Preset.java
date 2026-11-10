package io.siggi.togethertunes.config;

/**
 * The x264 presets an admin may pick for ingest ({@code ingest.quickPreset} and
 * {@code ingest.qualityPreset}), with the one argument that has to differ per preset so that every
 * preset writes the same SPS and PPS (invariant 1; {@code docs/library-and-ingest.md#presets}).
 *
 * <p>Presets change two parameter-set fields: {@code num_ref_frames} (pinned with {@code ref=4} in
 * the shared arguments) and the PPS {@code chroma_qp_index_offset}: x264 lowers the configured
 * offset by 2 when psy-rd is active, which it is from subme 6 up, i.e. from preset {@code fast}.
 * So the faster two presets get {@code chroma-qp-offset=-2} and the others {@code 0}, and all seven
 * end up with an effective −2. Measured 2026-11-10 with ffmpeg 6.1.1 (720p30 and 1080p30, CBR,
 * a black still and {@code testsrc2}): byte-identical SPS and PPS for all seven. {@code ultrafast}
 * and {@code superfast} are not offered: they turn off CABAC and the 8×8 transform, which changes
 * the PPS.
 *
 * <p>{@link #REFERENCE} is the preset the reference card and every card are encoded with; the
 * others are checked against it at startup and when an admin picks one.
 */
public enum X264Preset {
    VERYFAST("veryfast", -2),
    FASTER("faster", -2),
    FAST("fast", 0),
    MEDIUM("medium", 0),
    SLOW("slow", 0),
    SLOWER("slower", 0),
    VERYSLOW("veryslow", 0);

    /** The preset of the reference card and of all cards. */
    public static final X264Preset REFERENCE = VERYFAST;
    /** Default of {@code ingest.quickPreset}. */
    public static final X264Preset DEFAULT_QUICK = VERYFAST;
    /** Default of {@code ingest.qualityPreset}. */
    public static final X264Preset DEFAULT_QUALITY = SLOW;

    private final String id;
    private final int chromaQpOffset;

    X264Preset(String id, int chromaQpOffset) {
        this.id = id;
        this.chromaQpOffset = chromaQpOffset;
    }

    /** x264's name, as in the config, the settings and {@code items.encode_preset}. */
    public String id() {
        return id;
    }

    /** The {@code chroma-qp-offset} x264 gets with this preset (see the class comment). */
    public int chromaQpOffset() {
        return chromaQpOffset;
    }

    /** The preset with this name, or null. */
    public static X264Preset byId(String id) {
        for (X264Preset p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** The allowed names, comma-separated, for messages. */
    public static String allowed() {
        StringBuilder sb = new StringBuilder();
        for (X264Preset p : values()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(p.id);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return id;
    }
}
