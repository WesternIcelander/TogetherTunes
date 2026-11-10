package io.siggi.togethertunes.library;

import io.siggi.togethertunes.DataDir;
import io.siggi.togethertunes.ingest.IngestPipeline;
import io.siggi.togethertunes.ingest.MediaVersions;

import java.nio.file.Files;
import java.util.Objects;

/**
 * Whether a media version has a {@code cover.jpg}. Not every item has one: an upload with no
 * embedded picture (and no cover uploaded with it) gets none, and every later version of it
 * (re-encode, trim, card-only, undo) carries over what the current one has. Views give a
 * {@code coverUrl} only when the file is there, so pages never ask for a cover that 404s.
 * A version's files don't change once published, so a check is one {@code stat}.
 */
public final class Covers {
    /** Assumes every version has a cover (tests without a data dir). */
    public static final Covers ALWAYS = new Covers(null);

    private final DataDir dataDir;

    public Covers(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    /** True if version {@code version} of the item has a {@code cover.jpg}. */
    public boolean exists(String itemId, int version) {
        if (dataDir == null) {
            return true;
        }
        try {
            return Files.isRegularFile(MediaVersions.versionDir(dataDir, Objects.requireNonNull(itemId), version)
                    .resolve(IngestPipeline.COVER));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
