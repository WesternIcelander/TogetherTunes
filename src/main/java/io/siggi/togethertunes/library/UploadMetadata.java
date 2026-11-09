package io.siggi.togethertunes.library;

import io.siggi.togethertunes.ingest.MediaProbe;
import java.util.Locale;

/**
 * Title, artist and album of an uploaded file ({@code docs/library-and-ingest.md#upload}): what
 * the uploader typed in {@code complete}, else the container tags ffprobe reports, else (title
 * only) the file name without its extension.
 *
 * <p>Values are cleaned for display: control characters and line breaks become spaces, runs of
 * whitespace collapse to one, and the result is trimmed and cut to {@link #MAX_CHARS} code points.
 * A value that is blank after cleaning counts as missing.
 *
 * @param title  never blank ({@code "Untitled"} as the last resort)
 * @param artist null if unknown
 * @param album  null if unknown
 */
public record UploadMetadata(String title, String artist, String album) {
    /** Longest title, artist or album kept, in code points. */
    public static final int MAX_CHARS = 300;

    /** Tags tried for the artist, in order. */
    private static final String[] ARTIST_TAGS = {"artist", "album_artist", "albumartist", "album artist", "author"};

    /**
     * @param probe          the probed upload (its {@link MediaProbe#tags()})
     * @param filename       the name the client gave the upload
     * @param titleOverride  the {@code complete} body's {@code title}, or null
     * @param artistOverride the {@code complete} body's {@code artist}, or null
     */
    public static UploadMetadata of(MediaProbe probe, String filename, String titleOverride, String artistOverride) {
        String title = clean(titleOverride);
        if (title == null) {
            title = clean(probe.tag("title"));
        }
        if (title == null) {
            title = clean(stem(filename));
        }
        if (title == null) {
            title = "Untitled";
        }
        String artist = clean(artistOverride);
        for (int i = 0; artist == null && i < ARTIST_TAGS.length; i++) {
            artist = clean(probe.tag(ARTIST_TAGS[i]));
        }
        return new UploadMetadata(title, artist, clean(probe.tag("album")));
    }

    /**
     * The file name without directories (either separator) and without its last extension;
     * underscores become spaces ({@code My_Song.mp3} → {@code My Song}). A name that is only an
     * extension ({@code .mp3}) is kept as is.
     */
    static String stem(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('_', ' ');
    }

    /** Cleaned for display (see the class comment), or null if nothing is left. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_CHARS * 2));
        boolean space = false;
        int count = 0;
        for (int i = 0; i < value.length() && count < MAX_CHARS; ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isISOControl(cp) || Character.isSpaceChar(cp)
                    || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                space = out.length() > 0;
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT && cp != 0x200D) { // keep ZWJ (emoji sequences)
                continue;
            }
            if (space) {
                out.append(' ');
                count++;
                space = false;
                if (count >= MAX_CHARS) {
                    break;
                }
            }
            out.appendCodePoint(cp);
            count++;
        }
        String s = out.toString().strip();
        return s.isEmpty() ? null : s;
    }

    /** The lower-case extension of an upload's name if it is a plain one ({@code [a-z0-9]{1,10}}), else null. */
    static String extension(String filename) {
        if (filename == null) {
            return null;
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = filename.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : null;
    }
}
