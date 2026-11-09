package io.siggi.togethertunes.ingest;

import java.util.Locale;

/**
 * One row of {@code ingest_jobs}: a unit of ingest work for one item
 * ({@code docs/library-and-ingest.md#ingest-job-states}).
 *
 * @param id         UUIDv7
 * @param itemId     the item the job produces files for
 * @param kind       what the job does
 * @param state      {@code queued → running → done | failed}
 * @param attempts   how many times a worker has started the job (a job interrupted by a server
 *                   crash is started again after the restart)
 * @param progress   0..1, from ffmpeg {@code -progress pipe:1}
 * @param createdAt  epoch ms
 * @param startedAt  epoch ms of the latest start, or null while it never ran
 * @param finishedAt epoch ms, set for {@code done} and {@code failed}
 * @param log        tail of the job log ({@link JobLog}), or null
 */
public record IngestJob(String id, String itemId, Kind kind, State state, int attempts, double progress,
                        long createdAt, Long startedAt, Long finishedAt, String log) {

    /** {@code ingest_jobs.kind}. */
    public enum Kind {
        /** A file upload: ingest from the uploaded original. */
        UPLOAD,
        /** A YouTube import: download with yt-dlp, then ingest. */
        YOUTUBE,
        /**
         * Re-encode a ready item for a new output profile. The item stays {@code ready} (playing
         * its current files) while this runs, and isn't marked failed if it fails.
         */
        REENCODE,
        /** Fetch a purged YouTube item again into the same row. */
        REFETCH;

        /** The value stored in the database. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromWire(String s) {
            for (Kind k : values()) {
                if (k.wire().equals(s)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("Unknown ingest job kind: " + s);
        }

        /** Whether this kind drives the item's status ({@code processing → ready | failed}). */
        public boolean ownsItemStatus() {
            return this != REENCODE;
        }
    }

    /** {@code ingest_jobs.state}. */
    public enum State {
        QUEUED, RUNNING, DONE, FAILED;

        /** The value stored in the database. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static State fromWire(String s) {
            for (State st : values()) {
                if (st.wire().equals(s)) {
                    return st;
                }
            }
            throw new IllegalArgumentException("Unknown ingest job state: " + s);
        }

        /** Whether the job has ended ({@code done} or {@code failed}). */
        public boolean finished() {
            return this == DONE || this == FAILED;
        }
    }
}
