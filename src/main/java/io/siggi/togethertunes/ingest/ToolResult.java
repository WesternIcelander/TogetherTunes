package io.siggi.togethertunes.ingest;

import java.nio.charset.StandardCharsets;

/**
 * The outcome of a tool run that finished in time.
 *
 * @param exitCode   the process exit code (0 unless {@link ToolCall#allowFailure()} was used)
 * @param stdout     captured stdout ({@link ToolCall#stdoutToString(int)}); empty otherwise
 * @param stderrTail the last lines of stderr (redacted), possibly empty
 * @param elapsedMs  wall-clock run time
 */
public record ToolResult(int exitCode, byte[] stdout, String stderrTail, long elapsedMs) {

    /** {@link #stdout()} decoded as UTF-8 (malformed input replaced). */
    public String stdoutString() {
        return new String(stdout, StandardCharsets.UTF_8);
    }
}
