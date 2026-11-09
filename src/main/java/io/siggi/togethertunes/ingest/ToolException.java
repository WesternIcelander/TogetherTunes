package io.siggi.togethertunes.ingest;

/**
 * A tool run (ffmpeg, ffprobe, yt-dlp) that did not succeed. The message names the tool and the
 * reason and ends with the last lines of the tool's stderr, so it can go straight into a job's
 * error state. Secrets registered with {@link ToolCall#secret(String)} are already redacted.
 */
public final class ToolException extends Exception {
    /** Why a tool run failed. */
    public enum Kind {
        /** The tool isn't configured or wasn't found on PATH. */
        NOT_AVAILABLE,
        /** The process could not be started. */
        START_FAILED,
        /** The tool exited with a non-zero exit code. */
        EXIT_CODE,
        /** The overall time limit passed; the tool was killed. */
        TIMEOUT,
        /** The tool wrote nothing for longer than the idle limit; it was killed. */
        IDLE_TIMEOUT,
        /** The tool wrote more stdout than allowed; it was killed. */
        OUTPUT_TOO_LARGE,
        /** Reading or handling the tool's output failed (I/O error, or the consumer threw); it was killed. */
        OUTPUT_FAILED
    }

    private final String tool;
    private final Kind kind;
    private final String reason;
    private final int exitCode;
    private final String stderrTail;

    ToolException(String tool, Kind kind, String reason, int exitCode, String stderrTail, Throwable cause) {
        super(message(tool, reason, stderrTail), cause);
        this.tool = tool;
        this.kind = kind;
        this.reason = reason;
        this.exitCode = exitCode;
        this.stderrTail = stderrTail == null ? "" : stderrTail;
    }

    private static String message(String tool, String reason, String stderrTail) {
        String m = tool + ": " + reason;
        if (stderrTail != null && !stderrTail.isBlank()) {
            m += "\n" + stderrTail.stripTrailing();
        }
        return m;
    }

    /** The tool's name, e.g. {@code ffmpeg}. */
    public String tool() {
        return tool;
    }

    public Kind kind() {
        return kind;
    }

    /** The reason without the tool name and stderr, e.g. {@code exit code 1}. */
    public String reason() {
        return reason;
    }

    /** The exit code for {@link Kind#EXIT_CODE}; -1 otherwise. */
    public int exitCode() {
        return exitCode;
    }

    /** The last lines of stderr (redacted), possibly empty. */
    public String stderrTail() {
        return stderrTail;
    }
}
