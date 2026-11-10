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
 * @param tier       the preset tier asked for when the job was queued, or null (decided when it
 *                   starts; {@code docs/library-and-ingest.md#presets})
 */
public record IngestJob(String id, String itemId, Kind kind, State state, int attempts, double progress,
                        long createdAt, Long startedAt, Long finishedAt, String log, Tier tier) {

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
        REFETCH,
        /**
         * Re-encode a ready item that was made with the quick preset with the quality preset, once
         * it is in a library. Like {@link #REENCODE} the item stays {@code ready} meanwhile. The
         * lowest kind in the claim order, and gives way to a more urgent job (for example one of an
         * item a room is waiting on; {@code docs/library-and-ingest.md#presets}).
         */
        QUALITY;

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
            return this != REENCODE && this != QUALITY;
        }

        /**
         * Whether this kind replaces the files of a ready item (re-encodes): it gives way to a new
         * job when every worker is busy ({@link IngestQueue}), and waits to publish while the item
         * is the current or next entry of a room.
         */
        public boolean replacesReadyFiles() {
            return !ownsItemStatus();
        }
    }

    /** {@code ingest_jobs.tier}: which x264 preset a job should use ({@code docs/library-and-ingest.md#presets}). */
    public enum Tier {
        /** {@code ingest.quickPreset}: the item is wanted in a room now. */
        QUICK,
        /** {@code ingest.qualityPreset}: the item is for a library. */
        QUALITY;

        /** The value stored in the database. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The tier with this stored value; null for null or an unknown value. */
        public static Tier fromWire(String s) {
            for (Tier t : values()) {
                if (t.wire().equals(s)) {
                    return t;
                }
            }
            return null;
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
