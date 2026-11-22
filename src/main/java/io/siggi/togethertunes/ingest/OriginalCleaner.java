package io.siggi.togethertunes.ingest;

import java.nio.file.Path;
import java.util.Set;

/**
 * Removes personal data from an audio upload's original before it is kept
 * ({@code docs/library-and-ingest.md#looking-details-up-online}, "Removing personal data from
 * uploads"): files bought from a music store can name the buyer (in {@code .m4a}: the
 * {@code apID}/{@code ownr} tags and the {@code pinf}/{@code sinf} purchase-info box), and an
 * audio upload's original is served to other listeners. The implementation runs
 * {@code tracksplitter metadata --no-lookup --in-place} ({@code library.MetadataLookup}).
 *
 * <p>{@code items.original_cleaned} records the outcome: 1 cleaned, 0 the cleaning failed (the
 * original is then never served), null not cleaned (any other format, or made before cleaning
 * existed or while TrackSplitter wasn't installed; the one-time pass {@link OriginalCleaning} cleans
 * those once it is). See {@link #served} for what that means for serving.
 */
public interface OriginalCleaner {
    /** The extensions of the originals that are cleaned (the formats TrackSplitter edits). */
    Set<String> EXTENSIONS = Set.of("m4a", "mp3", "flac");

    /** Whether TrackSplitter is installed; without it nothing is cleaned. */
    boolean installed();

    /**
     * Cleans {@code file} in place (a scratch copy; its name ends in one of {@link #EXTENSIONS}).
     * Waits its turn behind the other TrackSplitter processes (one at a time, server-wide).
     *
     * @param log the job's log, or null (the one-time pass)
     * @return null when the file is clean afterwards; else why it isn't (for the log)
     */
    String clean(Path file, JobLog log) throws InterruptedException;

    /** The extension of an {@code original.<ext>} name, or null. */
    static String extension(String originalFile) {
        if (originalFile == null) {
            return null;
        }
        int dot = originalFile.lastIndexOf('.');
        return dot < 0 ? null : originalFile.substring(dot + 1);
    }

    /** Whether an original of this name is one that gets cleaned. */
    static boolean cleanable(String originalFile) {
        return EXTENSIONS.contains(extension(originalFile));
    }

    /**
     * Whether an audio upload's original may be served (played by the web client, or fetched from
     * {@code /media/{id}/original}) as far as personal data goes: never after a failed cleaning
     * ({@code cleaned} 0), and an {@code .m4a} (the format that carries purchase data) only once it
     * was cleaned. Other originals that weren't cleaned (null) are served as before.
     *
     * @param cleaned {@code items.original_cleaned}: 1, 0 or null
     */
    static boolean served(String originalFile, Integer cleaned) {
        if (cleaned != null) {
            return cleaned != 0;
        }
        return !"m4a".equals(extension(originalFile));
    }
}
