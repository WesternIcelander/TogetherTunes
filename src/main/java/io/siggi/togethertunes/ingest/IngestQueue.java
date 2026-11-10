package io.siggi.togethertunes.ingest;

import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.ingest.IngestJob.Kind;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * The ingest worker pool ({@code docs/architecture.md#threading}): a fixed number of workers
 * ({@code ingest.workers}) that take jobs from the DB-backed queue ({@link IngestJobs}) and run
 * them with a {@link Handler}. Separate from the overlay encoder pool (M5), which ingest must
 * never delay.
 *
 * <ul>
 *   <li><b>Restarts:</b> {@link #start()} first requeues the jobs a crash left {@code running}
 *       ({@link IngestJobs#recoverInterrupted}). {@link #close()} interrupts running jobs (their
 *       tools are stopped by {@link ToolCall}'s interrupt handling) and puts them back in the
 *       queue without counting the attempt, so they run again after the restart.</li>
 *   <li><b>Waking:</b> {@link #enqueue} wakes an idle worker at once; workers also look at the
 *       table every {@code pollInterval}, so jobs queued another way are picked up too.</li>
 *   <li><b>Progress and log:</b> the handler reports progress ({@link Context#progress}) and
 *       writes tool output into {@link Context#log()}; both are stored at most every
 *       {@code progressInterval}, and the final log when the job ends.</li>
 *   <li><b>Outcome:</b> the handler returning means {@code done}; throwing means {@code failed},
 *       with the exception's message as the item's error.</li>
 *   <li><b>Re-encodes give way</b> ({@code docs/library-and-ingest.md#presets}): when a job that
 *       someone waits for (an upload, import or refetch) is queued and every worker is busy, one
 *       running re-encode ({@code reencode} or {@code quality}) is interrupted and put back in the
 *       queue without counting the attempt, like at a shutdown; it starts again from the
 *       beginning once nothing more urgent is queued. A worker that has just claimed a re-encode
 *       while such a job is waiting puts it back at once.</li>
 *   <li><b>Low disk space</b> ({@code docs/library-and-ingest.md#free-disk-space}): while
 *       {@link #pauseWhile} says so (the data volume is below {@code storage.minFreeBytes}), no
 *       worker takes a new job; queued jobs (and re-encodes) stay queued and start once it no
 *       longer does, checked at every wake and poll. Jobs already running carry on.</li>
 * </ul>
 */
public final class IngestQueue implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(IngestQueue.class.getName());

    /** Default time between looks at the table when no job was signalled. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);
    /** Default minimum time between progress/log writes of one job. */
    public static final Duration DEFAULT_PROGRESS_INTERVAL = Duration.ofSeconds(1);
    /** How long {@link #close()} waits for running jobs to stop. */
    static final Duration CLOSE_TIMEOUT = ToolCall.KILL_GRACE.plusSeconds(7);

    /** Does the work of one job. Runs on a worker thread; may block (tools, downloads). */
    @FunctionalInterface
    public interface Handler {
        /**
         * Runs the job. Return normally when it succeeded; throw to fail it. Must stop promptly
         * when the thread is interrupted (the server is stopping).
         */
        void run(Context job) throws Exception;
    }

    /** What a {@link Handler} gets: the job, its log, and a way to report progress. */
    public final class Context {
        private final IngestJob job;
        private final JobLog log;
        private long lastSaveNanos;
        private boolean saved;
        private double progress;

        private Context(IngestJob job, JobLog log) {
            this.job = job;
            this.log = log;
        }

        /** The job as claimed ({@code running}, attempt counted). */
        public IngestJob job() {
            return job;
        }

        /** The job's log; pass it to tools with {@link ToolCall#log(JobLog)}. */
        public JobLog log() {
            return log;
        }

        /**
         * Reports progress (0..1, clamped). Stored with the log tail at most every
         * {@code progressInterval}. Thread-safe (ffmpeg progress arrives on a reader thread).
         * A failed write is logged, not thrown: progress is informational.
         */
        public void progress(double fraction) {
            long now = System.nanoTime();
            synchronized (this) {
                progress = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
                if (saved && now - lastSaveNanos < progressInterval.toNanos()) {
                    return;
                }
                saved = true;
                lastSaveNanos = now;
            }
            flush();
        }

        /** The last progress reported. */
        public synchronized double currentProgress() {
            return progress;
        }

        /** Stores the current progress and log tail now. */
        public void flush() {
            double p;
            synchronized (this) {
                p = progress;
            }
            try {
                jobs.saveProgress(job.id(), p, log.tail());
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not store the progress of ingest job " + job.id(), e);
            }
        }
    }

    private final IngestJobs jobs;
    private final int workerCount;
    private final Handler handler;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration progressInterval;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signal = lock.newCondition();
    private long signals;
    private volatile boolean closing;
    private volatile BooleanSupplier paused = () -> false;
    private volatile boolean pauseLogged;
    private final List<Thread> workers = new ArrayList<>();
    /** The jobs running now, by job id. */
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /** A running job and its worker; {@link #finished} and {@link #preempted} change under its lock. */
    private static final class Running {
        final IngestJob job;
        final Thread thread;
        final long startedNanos = System.nanoTime();
        boolean finished;
        volatile boolean preempted;

        Running(IngestJob job, Thread thread) {
            this.job = job;
            this.thread = thread;
        }
    }

    public IngestQueue(IngestJobs jobs, int workers, Handler handler) {
        this(jobs, workers, handler, Clock.systemUTC(), DEFAULT_POLL_INTERVAL, DEFAULT_PROGRESS_INTERVAL);
    }

    public IngestQueue(IngestJobs jobs, int workers, Handler handler, Clock clock, Duration pollInterval,
                       Duration progressInterval) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        this.jobs = Objects.requireNonNull(jobs);
        this.workerCount = workers;
        this.handler = Objects.requireNonNull(handler);
        this.clock = Objects.requireNonNull(clock);
        this.pollInterval = Objects.requireNonNull(pollInterval);
        this.progressInterval = Objects.requireNonNull(progressInterval);
    }

    /**
     * Workers take no new job while {@code paused} is true (low disk space); checked before each
     * claim. A supplier that throws counts as not paused.
     */
    public void pauseWhile(BooleanSupplier paused) {
        this.paused = Objects.requireNonNull(paused);
    }

    /** Whether workers are holding back queued jobs now. */
    public boolean paused() {
        try {
            return paused.getAsBoolean();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not check whether ingest should wait", e);
            return false;
        }
    }

    /** The table this queue runs. */
    public IngestJobs jobs() {
        return jobs;
    }

    /** Number of workers ({@code ingest.workers}). */
    public int workers() {
        return workerCount;
    }

    /**
     * Requeues jobs interrupted by a crash, then starts the workers (virtual threads). Call once.
     */
    public synchronized IngestJobs.Recovered start() throws SQLException {
        if (!workers.isEmpty() || closing) {
            throw new IllegalStateException("already started or closed");
        }
        jobs.enqueueListener(this::wake);
        IngestJobs.Recovered recovered = jobs.recoverInterrupted();
        if (recovered.requeued() > 0 || recovered.failed() > 0) {
            LOG.log(System.Logger.Level.INFO, "Ingest jobs interrupted by the last shutdown: " + recovered.requeued()
                    + " queued again, " + recovered.failed() + " failed (too many attempts)");
        }
        for (int i = 0; i < workerCount; i++) {
            workers.add(Thread.ofVirtual().name("ingest-worker-" + (i + 1)).start(this::workerLoop));
        }
        return recovered;
    }

    /** Queues a job ({@link IngestJobs#enqueue}) and wakes a worker. */
    public IngestJob enqueue(String itemId, Kind kind) throws SQLException {
        IngestJob job = jobs.enqueue(itemId, kind);
        wake();
        return job;
    }

    /**
     * Wakes the workers to look for queued jobs (e.g. after queuing inside a larger transaction),
     * and, if every worker is busy, makes a running re-encode give way to a waiting new job.
     */
    public void wake() {
        lock.lock();
        try {
            signals++;
            signal.signalAll();
        } finally {
            lock.unlock();
        }
        if (!closing && running.size() >= workerCount && hasPreemptible()) {
            Thread.ofVirtual().name("ingest-preempt").start(this::preemptIfNeeded);
        }
    }

    private boolean hasPreemptible() {
        for (Running r : running.values()) {
            if (r.job.kind().replacesReadyFiles() && !r.preempted) {
                return true;
            }
        }
        return false;
    }

    /**
     * If every worker is busy and a new-item job is queued, interrupts the most recently started
     * running re-encode; its worker puts it back in the queue and takes the new job.
     *
     * @return whether a re-encode was interrupted
     */
    boolean preemptIfNeeded() {
        if (closing || running.size() < workerCount || paused()) {
            return false; // while paused, the new job couldn't start anyway
        }
        try {
            if (!jobs.hasQueuedNewItemJob()) {
                return false;
            }
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not look for waiting ingest jobs", e);
            return false;
        }
        Running victim = null;
        for (Running r : running.values()) {
            if (r.job.kind().replacesReadyFiles() && !r.preempted
                    && (victim == null || r.startedNanos > victim.startedNanos)) {
                victim = r;
            }
        }
        if (victim == null) {
            return false;
        }
        synchronized (victim) {
            if (victim.finished || victim.preempted) {
                return false;
            }
            victim.preempted = true;
            victim.thread.interrupt();
        }
        LOG.log(System.Logger.Level.INFO, "Ingest: " + victim.job.kind().wire() + " job " + victim.job.id()
                + " gives way to a new job; it runs again later");
        return true;
    }

    private void workerLoop() {
        while (!closing) {
            long seen;
            lock.lock();
            try {
                seen = signals;
            } finally {
                lock.unlock();
            }
            IngestJob job;
            try {
                if (paused()) {
                    if (!pauseLogged) {
                        pauseLogged = true;
                        LOG.log(System.Logger.Level.INFO, "Ingest: queued jobs wait until there is free disk space");
                    }
                    job = null;
                } else {
                    if (pauseLogged) {
                        pauseLogged = false;
                        LOG.log(System.Logger.Level.INFO, "Ingest: free disk space again, queued jobs continue");
                    }
                    job = jobs.claimNext();
                }
            } catch (SQLException | RuntimeException e) {
                if (closing) {
                    return;
                }
                LOG.log(System.Logger.Level.WARNING, "Could not take an ingest job from the queue", e);
                job = null;
            }
            if (job != null) {
                if (job.kind().replacesReadyFiles() && newItemJobWaiting()) {
                    // Claimed a re-encode just as a new job came in: let the new job go first.
                    try {
                        jobs.release(job.id(), job.log());
                    } catch (SQLException | RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "Could not put ingest job " + job.id() + " back", e);
                    }
                    continue;
                }
                run(job);
                continue;
            }
            lock.lock();
            try {
                long deadline = System.nanoTime() + pollInterval.toNanos();
                while (signals == seen && !closing) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    signal.awaitNanos(left);
                }
            } catch (InterruptedException e) {
                if (closing) {
                    return;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** Whether a new-item job is queued (a database error counts as no). */
    private boolean newItemJobWaiting() {
        try {
            return jobs.hasQueuedNewItemJob();
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    private void run(IngestJob job) {
        Running r = new Running(job, Thread.currentThread());
        running.put(job.id(), r);
        try {
            run(job, r);
        } finally {
            running.remove(job.id());
        }
    }

    private void run(IngestJob job, Running r) {
        JobLog log = JobLog.resume(job.log());
        long startNanos = System.nanoTime();
        log.append("[ingest] " + job.kind().wire() + " job started (attempt " + job.attempts() + ") at "
                + EpochMs.format(EpochMs.now(clock)));
        Context context = new Context(job, log);
        Throwable failure = null;
        try {
            handler.run(context);
        } catch (Throwable t) {
            failure = t;
        }
        boolean preempted;
        synchronized (r) {
            r.finished = true;
            preempted = r.preempted;
        }
        boolean stopping = closing;
        // A shutdown (or give-way) interrupt must not break the final database writes. A job that
        // returned normally is done even when the interrupt came in at the end.
        Thread.interrupted();
        String took = String.format(Locale.ROOT, "%.1f s", (System.nanoTime() - startNanos) / 1e9);
        try {
            if (stopping && failure != null) {
                log.append("[ingest] Stopped by a server shutdown after " + took + "; it runs again after the restart");
                jobs.release(job.id(), log.tail());
            } else if (preempted && failure != null) {
                log.append("[ingest] Stopped after " + took + " to make way for a new job; it runs again later");
                jobs.release(job.id(), log.tail());
            } else if (failure == null) {
                log.append("[ingest] Done after " + took);
                jobs.complete(job.id(), log.tail());
            } else {
                String message = message(failure);
                log.append("[ingest] Failed after " + took + ": " + message);
                if (expected(failure)) {
                    LOG.log(System.Logger.Level.WARNING, "Ingest job " + job.id() + " (" + job.kind().wire()
                            + ", item " + job.itemId() + ") failed: " + message);
                } else {
                    LOG.log(System.Logger.Level.WARNING, "Ingest job " + job.id() + " (" + job.kind().wire()
                            + ", item " + job.itemId() + ") failed", failure);
                }
                jobs.fail(job.id(), message, log.tail());
            }
        } catch (SQLException | RuntimeException e) {
            // The row stays 'running'; the next start requeues it.
            LOG.log(System.Logger.Level.WARNING, "Could not store the outcome of ingest job " + job.id(), e);
        }
        if (failure instanceof VirtualMachineError vmError) {
            throw vmError;
        }
    }

    /** Failures with a message meant for people (tools, files, checks); others get a stack trace in the server log. */
    private static boolean expected(Throwable t) {
        return t instanceof ToolException || t instanceof IOException || t instanceof ReferenceCard.AvcCMismatchException
                || t instanceof IngestFailure;
    }

    static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /**
     * Stops the workers: running jobs are interrupted and put back in the queue (see
     * {@link IngestJobs#release}). Waits up to {@link #CLOSE_TIMEOUT} for them; a job still
     * running after that stays {@code running} in the table and is requeued at the next start.
     */
    @Override
    public void close() {
        List<Thread> threads;
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
            threads = List.copyOf(workers);
        }
        wake();
        for (Thread t : threads) {
            t.interrupt();
        }
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        for (Thread t : threads) {
            long left = deadline - System.nanoTime();
            try {
                if (left <= 0 || !t.join(Duration.ofNanos(left))) {
                    LOG.log(System.Logger.Level.WARNING, "Ingest worker " + t.getName() + " did not stop in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Whether {@link #close()} has been called. */
    public boolean closed() {
        return closing;
    }
}
