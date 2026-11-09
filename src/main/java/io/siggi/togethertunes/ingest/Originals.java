package io.siggi.togethertunes.ingest;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The original file of an audio upload ({@code docs/library-and-ingest.md#originals-audio-uploads}):
 * kept as {@code media/<itemId>/original.<ext>} so the web client can play it instead of
 * {@code audio.m4a}. Originals of video uploads and YouTube downloads are not kept.
 *
 * <ul>
 *   <li>{@code original_codec} is ffprobe's audio codec and container, {@code <codec_name>/<format_name>},
 *       e.g. {@code flac/flac}, {@code aac/mov,mp4,m4a,3gp,3g2,mj2}.</li>
 *   <li>{@code original_web} is set only for the browser-playable combinations (MP3, AAC in MP4/M4A
 *       or ADTS, FLAC, Opus in Ogg or WebM, PCM WAV) whose duration is within
 *       {@link #MAX_DURATION_DIFF_US} of {@code audio.m4a}.</li>
 *   <li>{@code original_gain_db = min(−14 − I, −1 − TP)} from loudnorm pass 1, so the client plays
 *       the original as loud as {@code audio.m4a} without clipping; null if the loudness couldn't
 *       be measured (silence).</li>
 * </ul>
 */
public final class Originals {
    /** Base name of the kept original in the item directory. */
    public static final String BASE_NAME = "original";

    /** Largest allowed difference between the original's and {@code audio.m4a}'s duration for {@code original_web}. */
    public static final long MAX_DURATION_DIFF_US = 50_000;

    /** PCM sample formats browsers play from a WAV file. */
    static final Set<String> WEB_PCM = Set.of("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le");

    private static final Pattern SAFE_EXT = Pattern.compile("[a-z0-9]{1,10}");

    private Originals() {
    }

    /**
     * What is recorded about a kept original.
     *
     * @param file   the file name in the item directory, e.g. {@code original.flac}
     * @param codec  {@code <codec_name>/<format_name>}
     * @param web    whether browsers can play it
     * @param gainDb the gain the web client applies, or null if unknown
     */
    public record Original(String file, String codec, boolean web, Double gainDb) {
    }

    /** {@code <codec_name>/<format_name>} of the probed file's audio stream. */
    static String codec(MediaProbe probe) {
        return probe.audio().codec().toLowerCase(Locale.ROOT) + "/" + probe.formatName().toLowerCase(Locale.ROOT);
    }

    /**
     * The extension of the kept original. Derived from the container ffprobe found, so a wrong
     * or missing extension on the upload doesn't matter; for containers with several usual
     * extensions (Matroska/WebM) or ones not listed, the upload's own extension is used if it is
     * plain (lowercase letters and digits), else {@code bin}.
     *
     * @param uploadedName the staged file name ({@code source} or {@code source.<ext>})
     */
    static String extension(MediaProbe probe, String uploadedName) {
        String given = extensionOf(uploadedName);
        String format = probe.formatName().toLowerCase(Locale.ROOT);
        String codec = probe.audio().codec().toLowerCase(Locale.ROOT);
        switch (format) {
            case "mp3":
                return "mp3";
            case "flac":
                return "flac";
            case "wav":
                return "wav";
            case "aac":
                return "aac";
            case "ogg":
                return codec.equals("opus") ? "opus" : "ogg";
            default:
                break;
        }
        if (format.contains("mp4") || format.contains("m4a") || format.startsWith("mov,")) {
            return "m4a";
        }
        if (format.contains("matroska") || format.contains("webm")) {
            return given != null && (given.equals("webm") || given.equals("mka") || given.equals("mkv")) ? given : "mka";
        }
        return given != null ? given : "bin";
    }

    /** The lowercase extension of a file name if it is a plain one, else null. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXT.matcher(ext).matches() ? ext : null;
    }

    /**
     * Whether the codec/container combination is one browsers play (MP3, AAC in MP4/M4A or ADTS,
     * FLAC, Opus in Ogg or WebM, PCM WAV). Opus in Matroska counts only as a {@code .webm} file,
     * since that is what browsers accept.
     */
    static boolean webCodec(MediaProbe probe, String extension) {
        String format = probe.formatName().toLowerCase(Locale.ROOT);
        String codec = probe.audio().codec().toLowerCase(Locale.ROOT);
        return switch (codec) {
            case "mp3" -> format.equals("mp3");
            case "aac" -> format.equals("aac") || format.contains("mp4") || format.contains("m4a");
            case "flac" -> format.equals("flac");
            case "opus" -> format.equals("ogg") || (format.contains("webm") && extension.equals("webm"));
            default -> format.equals("wav") && WEB_PCM.contains(codec);
        };
    }

    /** Whether the original's duration is within {@link #MAX_DURATION_DIFF_US} of {@code audio.m4a}'s. */
    static boolean durationMatches(long originalUs, long audioM4aUs) {
        return originalUs > 0 && audioM4aUs > 0 && Math.abs(originalUs - audioM4aUs) <= MAX_DURATION_DIFF_US;
    }

    /**
     * {@code min(−14 − I, −1 − TP)}, rounded to 0.01 dB; null if the measurement isn't usable.
     */
    static Double gainDb(Loudness.Measurement m) {
        if (!m.usable()) {
            return null;
        }
        double gain = Math.min(Loudness.TARGET_I - m.inputI(), Loudness.TARGET_TP - m.inputTp());
        return Math.round(gain * 100) / 100.0;
    }

    /**
     * Everything recorded about the original of an audio upload.
     *
     * @param originalUs the original's duration as a player sees it (the container duration, or
     *                   where its audio ends if the container has none)
     * @param audioM4aUs the duration of {@code audio.m4a}
     */
    static Original describe(MediaProbe probe, String uploadedName, Loudness.Measurement loudness,
                             long originalUs, long audioM4aUs) {
        String ext = extension(probe, uploadedName);
        boolean web = webCodec(probe, ext) && durationMatches(originalUs, audioM4aUs);
        return new Original(BASE_NAME + "." + ext, codec(probe), web, gainDb(loudness));
    }
}
