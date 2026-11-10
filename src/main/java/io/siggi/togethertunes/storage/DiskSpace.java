package io.siggi.togethertunes.storage;

import io.siggi.togethertunes.http.HttpError;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Free space on the data volume against the admin's minimum ({@code storage.minFreeBytes};
 * {@code docs/library-and-ingest.md#free-disk-space}).
 *
 * <p>While the free space is below the minimum, adding media is refused with
 * {@code 507 insufficient_storage} ({@link #requireRoomFor}): starting an upload whose declared
 * size would take the free space below the minimum, a chunk of an upload already started, and a
 * new YouTube fetch. Queued ingest jobs and re-encodes wait ({@link #low()}) instead of failing.
 * A minimum of 0 turns all of this off.
 *
 * <p>The free space ({@code FileStore.getUsableSpace()}) is measured fresh for each decision, cached
 * for at most {@code cacheFor}; the minimum is read on each decision, so an admin's change applies
 * at once. Crossing below the minimum is logged once as a warning, going back above as info. If
 * the space can't be measured, nothing is blocked (and that is logged once).
 */
public final class DiskSpace {
    private static final System.Logger LOG = System.getLogger(DiskSpace.class.getName());

    /** The error code of a refusal. */
    public static final String ERROR_CODE = "insufficient_storage";
    /** The message of a refusal, shown to the person adding media. */
    public static final String ERROR_MESSAGE = "The server is low on disk space; adding media is paused.";
    /** How long a measurement is reused. */
    public static final Duration DEFAULT_CACHE = Duration.ofSeconds(2);

    /** Free and total bytes of a volume. */
    public record Usage(long usableBytes, long totalBytes) {
    }

    /** Measures the volume. */
    @FunctionalInterface
    public interface Source {
        Usage measure() throws IOException;
    }

    /** The configured minimum in bytes ({@code storage.minFreeBytes}); 0 disables the check. */
    @FunctionalInterface
    public interface Minimum {
        long minFreeBytes() throws SQLException;
    }

    /**
     * What {@link #status()} found.
     *
     * @param freeBytes    usable bytes on the data volume, or -1 if it couldn't be measured
     * @param totalBytes   the volume's size, or -1 if it couldn't be measured
     * @param minFreeBytes the minimum (0 = no check)
     * @param low          whether adding media is paused (free space below a non-zero minimum)
     */
    public record Status(long freeBytes, long totalBytes, long minFreeBytes, boolean low) {
    }

    private final Source source;
    private final Minimum minimum;
    private final long cacheNanos;
    private final LongSupplier nanoTime;

    private final Object lock = new Object();
    private Usage cached;
    private long cachedAt;
    private boolean lastLow;
    private boolean measureFailureLogged;
    private long lastMinimum;

    /**
     * @param cacheFor how long a measurement may be reused (zero: measure every time)
     * @param nanoTime the clock of the cache ({@code System::nanoTime})
     */
    public DiskSpace(Source source, Minimum minimum, Duration cacheFor, LongSupplier nanoTime) {
        this.source = Objects.requireNonNull(source);
        this.minimum = Objects.requireNonNull(minimum);
        this.cacheNanos = cacheFor.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    /** The data volume of {@code dir}, measured with {@link #DEFAULT_CACHE}. */
    public static DiskSpace of(Path dir, Minimum minimum) {
        return new DiskSpace(fileStore(dir), minimum, DEFAULT_CACHE, System::nanoTime);
    }

    /** Never low: for callers (and tests) without a minimum. */
    public static DiskSpace unlimited() {
        return new DiskSpace(() -> new Usage(Long.MAX_VALUE, Long.MAX_VALUE), () -> 0, Duration.ZERO, System::nanoTime);
    }

    /** The file system that holds {@code dir}. */
    public static Source fileStore(Path dir) {
        Objects.requireNonNull(dir);
        return () -> {
            FileStore store = Files.getFileStore(dir);
            return new Usage(store.getUsableSpace(), store.getTotalSpace());
        };
    }

    /** The free space, the minimum and whether adding media is paused, measured now (or cached). */
    public Status status() {
        long min = minimum();
        Usage usage = usage();
        long free = usage == null ? -1 : usage.usableBytes();
        long total = usage == null ? -1 : usage.totalBytes();
        boolean low = min > 0 && usage != null && free < min;
        noteCrossing(low, free, min);
        return new Status(free, total, min, low);
    }

    /** Whether the free space is below the minimum (queued ingest jobs wait while it is). */
    public boolean low() {
        return status().low();
    }

    /**
     * Refuses with {@code 507 insufficient_storage} if writing {@code bytes} more would leave less
     * than the minimum free. {@code bytes} 0 checks only that the free space isn't below it.
     */
    public void requireRoomFor(long bytes) {
        Status s = status();
        if (s.minFreeBytes() <= 0 || s.freeBytes() < 0) {
            return;
        }
        if (s.low() || s.freeBytes() - Math.max(0, bytes) < s.minFreeBytes()) {
            throw insufficient();
        }
    }

    /** {@code 507 insufficient_storage} with the message for the user. */
    public static HttpError insufficient() {
        return new HttpError(507, ERROR_CODE, ERROR_MESSAGE);
    }

    private long minimum() {
        try {
            long min = Math.max(0, minimum.minFreeBytes());
            synchronized (lock) {
                lastMinimum = min;
            }
            return min;
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read storage.minFreeBytes; using the last value: " + e);
            synchronized (lock) {
                return lastMinimum;
            }
        }
    }

    private Usage usage() {
        synchronized (lock) {
            long now = nanoTime.getAsLong();
            if (cached != null && cacheNanos > 0 && now - cachedAt < cacheNanos) {
                return cached;
            }
            try {
                cached = source.measure();
                cachedAt = now;
                measureFailureLogged = false;
                return cached;
            } catch (IOException | RuntimeException e) {
                if (!measureFailureLogged) {
                    measureFailureLogged = true;
                    LOG.log(System.Logger.Level.WARNING, "Could not measure the free space of the data volume;"
                            + " not limiting adding media: " + e);
                }
                cached = null;
                return null;
            }
        }
    }

    private void noteCrossing(boolean low, long free, long min) {
        boolean was;
        synchronized (lock) {
            was = lastLow;
            lastLow = low;
        }
        if (low && !was) {
            LOG.log(System.Logger.Level.WARNING, "Free disk space on the data volume (" + gib(free)
                    + ") is below the minimum (" + gib(min) + "); adding media is paused and queued ingest jobs wait");
        } else if (!low && was) {
            LOG.log(System.Logger.Level.INFO, "Free disk space on the data volume is back above the minimum"
                    + (free >= 0 ? " (" + gib(free) + " free" + (min > 0 ? ", minimum " + gib(min) : "") + ")" : "")
                    + "; adding media and ingest continue");
        }
    }

    private static String gib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }
}
