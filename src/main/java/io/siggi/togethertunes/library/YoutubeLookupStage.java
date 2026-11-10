package io.siggi.togethertunes.library;

import java.util.Locale;
import java.util.Objects;

/**
 * The stages of a YouTube lookup ({@code yt-dlp -J}), for the live feedback while it runs
 * ({@code docs/rooms.md#adding-a-youtube-link}, {@code docs/api.md}): read from yt-dlp's
 * progress lines on stderr ({@link #of(String)}) and reported in order through a
 * {@link Listener}. Parsing is lenient on purpose: an unknown line is ignored, so a wording
 * change in yt-dlp only means fewer stages shown, never a failed lookup.
 */
public enum YoutubeLookupStage {
    /** {@code [youtube] Extracting URL: …} */
    CONTACTING("contacting", "Contacting YouTube…"),
    /** {@code [youtube] <id>: Downloading webpage} */
    PAGE("page", "Reading the video page…"),
    /** {@code [youtube] <id>: Downloading … player API JSON}, player and client config downloads */
    DETAILS("details", "Getting the video details…"),
    /** {@code [youtube] <id>: Downloading m3u8 information}, {@code [info] <id>: Downloading 1 format(s): …} */
    FORMATS("formats", "Checking formats…");

    private final String wire;
    private final String text;

    YoutubeLookupStage(String wire, String text) {
        this.wire = wire;
        this.text = text;
    }

    /** The {@code stage} value in messages. */
    public String wire() {
        return wire;
    }

    /** The text people see. */
    public String text() {
        return text;
    }

    /** Told about each new stage of one lookup, in order, on the thread that reads yt-dlp's output. */
    @FunctionalInterface
    public interface Listener {
        Listener NONE = stage -> {
        };

        /** Must return quickly; whatever it throws is ignored. */
        void stage(YoutubeLookupStage stage);
    }

    /**
     * The stage a yt-dlp stderr line shows, or null for any other line (debug output, warnings,
     * errors, anything unknown).
     */
    public static YoutubeLookupStage of(String line) {
        if (line == null) {
            return null;
        }
        String l = line.strip();
        // only "[extractor] …" progress lines; "[debug] …" is verbose output
        if (!l.startsWith("[") || l.startsWith("[debug]")) {
            return null;
        }
        String lower = l.toLowerCase(Locale.ROOT);
        if (lower.contains("extracting url")) {
            return CONTACTING;
        }
        if (!lower.contains("downloading")) {
            return null;
        }
        if (lower.contains("webpage")) {
            return PAGE;
        }
        if (lower.contains("m3u8") || lower.contains("mpd") || lower.contains("manifest")
                || lower.contains("format(s)") || lower.contains("formats")) {
            return FORMATS;
        }
        if (lower.contains("json") || lower.contains("player") || lower.contains("config")
                || lower.contains("api")) {
            return DETAILS;
        }
        return null;
    }

    /**
     * Follows one lookup's stderr: reports a stage to {@code listener} only when it is later than
     * every stage reported so far (each at most once, never backwards). Thread-safe.
     */
    static final class Tracker {
        private final Listener listener;
        private YoutubeLookupStage last;

        Tracker(Listener listener) {
            this.listener = Objects.requireNonNull(listener);
        }

        /** A stderr line of the lookup. */
        void line(String line) {
            YoutubeLookupStage s = of(line);
            if (s == null) {
                return;
            }
            synchronized (this) {
                if (last != null && s.ordinal() <= last.ordinal()) {
                    return;
                }
                last = s;
            }
            try {
                listener.stage(s);
            } catch (RuntimeException ignored) {
                // feedback only
            }
        }
    }
}
