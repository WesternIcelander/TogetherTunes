package io.siggi.togethertunes.library;

import java.sql.SQLException;

/**
 * Told when an item is added to a user's library, inside the transaction that adds it (the item
 * may have been in that library already). The server queues the item's quality re-encode here
 * ({@code ingest.IngestJobs#enqueueQualityIfNeeded}, {@code docs/library-and-ingest.md#presets}).
 */
@FunctionalInterface
public interface LibraryAddListener {
    void addedToLibrary(String itemId) throws SQLException;

    /** Does nothing (tests, and until the server wires the ingest queue). */
    LibraryAddListener NONE = itemId -> { };
}
