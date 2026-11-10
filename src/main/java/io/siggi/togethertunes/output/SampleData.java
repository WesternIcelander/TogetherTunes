package io.siggi.togethertunes.output;

import java.io.IOException;

/**
 * Reads sample bytes for a {@link io.siggi.togethertunes.media.SampleIndex}: {@code size} bytes at
 * {@code offset} in the index's file {@code fileRef}. {@link FileSampleData} reads an item
 * directory with positional reads; tests use in-memory data.
 */
@FunctionalInterface
public interface SampleData {
    /**
     * @throws IOException if the bytes can't be read in full
     */
    byte[] read(int fileRef, long offset, int size) throws IOException;
}
