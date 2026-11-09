package io.siggi.togethertunes.ingest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * The log of one job (an ingest job, later also an overlay encode or the RTMP pusher): a bounded
 * tail of lines, kept in memory and written to the job's {@code log} column. Tool output (stderr
 * of ffmpeg, ffprobe and yt-dlp) goes here through {@link ToolCall#log(JobLog)}.
 *
 * <p>Holds at most {@code maxChars} characters (newlines included); the oldest lines are dropped
 * first, and the first line kept after a drop is preceded by a marker so readers can see that
 * lines are missing. Single lines longer than {@link #MAX_LINE_CHARS} are cut. Thread-safe: a
 * tool's stdout and stderr readers append concurrently.
 */
public final class JobLog {
    /** Default size of the tail: 64 Ki characters. */
    public static final int DEFAULT_MAX_CHARS = 64 * 1024;
    /** Longer lines are cut to this many characters (plus an ellipsis). */
    public static final int MAX_LINE_CHARS = 2000;
    /** Shown as the first line once older lines have been dropped. */
    public static final String TRUNCATED_MARKER = "[… earlier lines dropped …]";

    private final int maxChars;
    private final Deque<String> lines = new ArrayDeque<>();
    private final Consumer<String> listener;
    private int chars;
    private boolean dropped;

    public JobLog() {
        this(DEFAULT_MAX_CHARS, null);
    }

    /**
     * @param maxChars size of the tail, at least {@code MAX_LINE_CHARS + 1}
     * @param listener called with every line appended (after cutting), outside the lock; may be
     *                 {@code null}. E.g. to mirror a job's log to the server log while debugging.
     */
    public JobLog(int maxChars, Consumer<String> listener) {
        if (maxChars <= MAX_LINE_CHARS) {
            throw new IllegalArgumentException("maxChars must be more than " + MAX_LINE_CHARS);
        }
        this.maxChars = maxChars;
        this.listener = listener;
    }

    /** Appends one line (a {@code null} line is logged as {@code "null"}). Embedded line breaks split it. */
    public void append(String line) {
        String s = String.valueOf(line);
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            for (String part : s.split("\r\n|\r|\n", -1)) {
                appendOne(part);
            }
        } else {
            appendOne(s);
        }
    }

    private void appendOne(String line) {
        if (line.length() > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS) + "…";
        }
        synchronized (this) {
            lines.addLast(line);
            chars += line.length() + 1;
            while (chars > maxChars && lines.size() > 1) {
                chars -= lines.removeFirst().length() + 1;
                dropped = true;
            }
        }
        if (listener != null) {
            listener.accept(line);
        }
    }

    /** The tail as text, one line per {@code \n}-terminated line. Empty if nothing was logged. */
    public synchronized String tail() {
        StringBuilder sb = new StringBuilder(chars + (dropped ? TRUNCATED_MARKER.length() + 1 : 0));
        if (dropped) {
            sb.append(TRUNCATED_MARKER).append('\n');
        }
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Whether older lines have been dropped to keep within the size. */
    public synchronized boolean truncated() {
        return dropped;
    }

    @Override
    public String toString() {
        return tail();
    }
}
