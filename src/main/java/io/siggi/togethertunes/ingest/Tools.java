package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.config.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The external media tools (ffmpeg, ffprobe, yt-dlp), resolved from the configuration
 * ({@code tools.*}; {@link io.siggi.togethertunes.config.ConfigLoader} already turned names into
 * absolute paths; a default that isn't on PATH stays a bare name, and running it then fails with
 * {@link ToolException.Kind#START_FAILED}). Each method returns a
 * {@link ToolCall} with default time limits that the caller can tighten or loosen.
 *
 * <p>Used by ingest, the overlay encoder and the v1 RTMP pusher. The packager never calls these.
 */
public final class Tools {
    /** Default overall limit for ffprobe. */
    public static final Duration FFPROBE_TIMEOUT = Duration.ofMinutes(5);
    /** Default overall limit for ffmpeg (a 30-minute 1080p source at veryfast takes far less). */
    public static final Duration FFMPEG_TIMEOUT = Duration.ofHours(4);
    /** Default overall limit for yt-dlp (metadata and downloads). */
    public static final Duration YTDLP_TIMEOUT = Duration.ofHours(2);
    /** Default idle limit for every tool: no output at all for this long means it hangs. */
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final String ffmpeg;
    private final String ffprobe;
    private final String ytdlp;

    /** Each argument is an executable path, or {@code null} if that tool isn't available. */
    public Tools(String ffmpeg, String ffprobe, String ytdlp) {
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.ytdlp = ytdlp;
    }

    public static Tools from(Config config) {
        return new Tools(config.ffmpeg(), config.ffprobe(), config.ytdlp());
    }

    /** Whether ffmpeg is available. */
    public boolean hasFfmpeg() {
        return ffmpeg != null;
    }

    /** Whether ffprobe is available. */
    public boolean hasFfprobe() {
        return ffprobe != null;
    }

    /** The ffmpeg executable, or null if it isn't available (e.g. for yt-dlp's {@code --ffmpeg-location}). */
    public String ffmpegExecutable() {
        return ffmpeg;
    }

    /** Whether yt-dlp is available. */
    public boolean hasYtdlp() {
        return ytdlp != null;
    }

    /** ffmpeg with {@code -hide_banner -nostdin} in front of {@code args}. */
    public ToolCall ffmpeg(List<String> args) {
        return new ToolCall("ffmpeg", ffmpeg, prepend(args, "-hide_banner", "-nostdin"))
                .timeout(FFMPEG_TIMEOUT).idleTimeout(IDLE_TIMEOUT);
    }

    public ToolCall ffmpeg(String... args) {
        return ffmpeg(Arrays.asList(args));
    }

    /** ffprobe with {@code -hide_banner} in front of {@code args}. */
    public ToolCall ffprobe(List<String> args) {
        return new ToolCall("ffprobe", ffprobe, prepend(args, "-hide_banner"))
                .timeout(FFPROBE_TIMEOUT).idleTimeout(IDLE_TIMEOUT);
    }

    public ToolCall ffprobe(String... args) {
        return ffprobe(Arrays.asList(args));
    }

    /** yt-dlp with {@code args} as given. */
    public ToolCall ytdlp(List<String> args) {
        return new ToolCall("yt-dlp", ytdlp, args).timeout(YTDLP_TIMEOUT).idleTimeout(IDLE_TIMEOUT);
    }

    public ToolCall ytdlp(String... args) {
        return ytdlp(Arrays.asList(args));
    }

    private static List<String> prepend(List<String> args, String... first) {
        List<String> all = new ArrayList<>(first.length + args.size());
        all.addAll(Arrays.asList(first));
        all.addAll(args);
        return all;
    }
}
