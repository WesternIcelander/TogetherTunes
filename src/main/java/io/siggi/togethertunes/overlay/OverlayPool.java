package io.siggi.togethertunes.overlay;

import io.siggi.togethertunes.ingest.ToolCall;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The overlay encoder pool ({@code docs/architecture.md#threading}, {@code docs/overlay.md}): a
 * bounded number of workers ({@code overlay.workers}, default 1) that run overlay re-encodes of
 * music videos. Separate from the ingest pool, so ingest never delays an overlay encode (and the
 * other way round).
 *
 * <ul>
 *   <li><b>Not persisted:</b> jobs live in memory only. {@link #close()} drops the queued ones and
 *       interrupts the running ones; after a restart they are recreated from room state, and
 *       partial output is the task's (and the cache's) business, not the pool's.</li>
 *   <li><b>Priorities:</b> {@link Priority#HIGH} (the entry playing now) before
 *       {@link Priority#NORMAL} (the next entry), first come first served within a priority. A
 *       running job is never interrupted for a more urgent one: the scheduler checks
 *       {@link #busy()} and lets the item play without the overlay instead of waiting.</li>
 *   <li><b>One job per key</b> (e.g. room + item + overlay hash): submitting a key that is
 *       queued or running returns that job, raising a queued job's priority if the new one is
 *       higher.</li>
 *   <li><b>Cancelling</b> ({@link #cancel}): a queued job is dropped; a running job's worker is
 *       interrupted (tools started with {@link ToolCall} stop on interrupt) and the task should
 *       also check {@link Job#cancelled()}. Its {@link Job#progress} lets the scheduler keep a job
 *       that is more than half done.</li>
 *   <li><b>Resizing</b> ({@link #resize}): growing starts workers at once; shrinking lets running
 *       jobs finish and extra workers stop before their next job.</li>
 * </ul>
 */
public final class OverlayPool implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(OverlayPool.class.getName());

    /** How long {@link #close()} waits for running jobs to stop. */
    static final Duration CLOSE_TIMEOUT = ToolCall.KILL_GRACE.plusSeconds(7);

    /** How urgent a job is. */
    public enum Priority {
        /** The entry playing now (catch-up after a skip or seek). */
        HIGH,
        /** The next entry, scheduled ahead. */
        NORMAL
    }

    /** Where a job is. */
    public enum State {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED;

        /** Whether the job has ended. */
        public boolean ended() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    /** The work of one job. Runs on a worker (virtual) thread and may block on tools. */
    @FunctionalInterface
    public interface Task {
        /**
         * Runs the job. Return normally when it succeeded; throw to fail it. Must stop promptly
         * when the thread is interrupted or {@link Job#cancelled()} becomes true.
         */
        void run(Job job) throws Exception;
    }

    /** A job in the pool. Thread-safe. */
    public final class Job {
        private final String key;
        private final Task task;
        private final CompletableFuture<State> end = new CompletableFuture<>();
        // guarded by the pool's lock
        private Priority priority;
        private State state = State.QUEUED;
        private Thread thread;
        private volatile boolean cancelled;
        private volatile double progress;
        private volatile Throwable failure;

        private Job(String key, Priority priority, Task task) {
            this.key = key;
            this.priority = priority;
            this.task = task;
        }

        /** The key it was submitted under. */
        public String key() {
            return key;
        }

        /** Its priority now (a resubmit may have raised it). */
        public Priority priority() {
            lock.lock();
            try {
                return priority;
            } finally {
                lock.unlock();
            }
        }

        /** Where it is now. */
        public State state() {
            lock.lock();
            try {
                return state;
            } finally {
                lock.unlock();
            }
        }

        /** Whether it was cancelled ({@link OverlayPool#cancel}, or the pool closed). Tasks check this. */
        public boolean cancelled() {
            return cancelled;
        }

        /** Reports progress (0..1, clamped); the scheduler reads it to decide whether to keep the job. */
        public void progress(double fraction) {
            progress = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
        }

        /** The last progress reported (1 once it is done). */
        public double currentProgress() {
            return progress;
        }

        /** Why it failed; null unless {@link State#FAILED}. */
        public Throwable failure() {
            return failure;
        }

        /** Completes with the final state ({@code DONE}, {@code FAILED} or {@code CANCELLED}). */
        public CompletableFuture<State> end() {
            return end.copy();
        }

        @Override
        public String toString() {
            return "overlay job " + key;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signal = lock.newCondition();
    // guarded by lock
    private int workerCount;
    private final List<Thread> workers = new ArrayList<>();
    private final ArrayDeque<Job> high = new ArrayDeque<>();
    private final ArrayDeque<Job> normal = new ArrayDeque<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private int runningCount;
    private boolean started;
    private boolean closing;
    private int nextWorkerNumber = 1;

    /**
     * @param workers the number of workers ({@code overlay.workers}, at least 1)
     */
    public OverlayPool(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        this.workerCount = workers;
    }

    /** Starts the workers (virtual threads). Call once. */
    public void start() {
        lock.lock();
        try {
            if (started || closing) {
                throw new IllegalStateException("already started or closed");
            }
            started = true;
            while (workers.size() < workerCount) {
                workers.add(startWorker());
            }
        } finally {
            lock.unlock();
        }
    }

    /** The number of workers wanted ({@code overlay.workers}). */
    public int workers() {
        lock.lock();
        try {
            return workerCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Changes the number of workers ({@code overlay.workers} changed in the admin settings).
     * Growing starts workers at once (before {@link #start}, only the count changes); shrinking
     * lets running jobs finish, and the extra workers stop before their next job.
     *
     * @throws IllegalArgumentException if {@code count} is below 1
     */
    public void resize(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        lock.lock();
        try {
            int before = workerCount;
            workerCount = count;
            if (started && !closing) {
                while (workers.size() < count) {
                    workers.add(startWorker());
                }
            }
            if (before != count) {
                LOG.log(System.Logger.Level.INFO, "Overlay workers: " + before + " -> " + count);
            }
            signal.signalAll(); // idle extra workers stop now
        } finally {
            lock.unlock();
        }
    }

    /** Jobs running now. */
    public int running() {
        lock.lock();
        try {
            return runningCount;
        } finally {
            lock.unlock();
        }
    }

    /** Jobs waiting for a worker. */
    public int queued() {
        lock.lock();
        try {
            return high.size() + normal.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether a new job would have to wait: every worker is busy or already promised to a queued
     * job. The scheduler then plays the item without the overlay ({@code docs/overlay.md}).
     */
    public boolean busy() {
        lock.lock();
        try {
            return runningCount + high.size() + normal.size() >= workerCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Queues a job, or returns the one already queued or running under {@code key} (a queued one
     * moves up to {@code priority} if that is higher; its task stays the first one).
     *
     * @throws IllegalStateException if the pool is closed
     */
    public Job submit(String key, Priority priority, Task task) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(task);
        lock.lock();
        try {
            if (closing) {
                throw new IllegalStateException("the overlay pool is closed");
            }
            Job existing = jobs.get(key);
            if (existing != null) {
                if (existing.state == State.QUEUED && priority.compareTo(existing.priority) < 0) {
                    queueOf(existing.priority).remove(existing);
                    existing.priority = priority;
                    queueOf(priority).addLast(existing);
                }
                return existing;
            }
            Job job = new Job(key, priority, task);
            jobs.put(key, job);
            queueOf(priority).addLast(job);
            signal.signal();
            return job;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Moves the job queued under {@code key} up to {@code priority} if that is higher (like a
     * resubmit, but never creates a job).
     *
     * @return whether a queued job was raised
     */
    public boolean raise(String key, Priority priority) {
        Objects.requireNonNull(priority);
        lock.lock();
        try {
            Job existing = jobs.get(key);
            if (existing == null || existing.state != State.QUEUED || priority.compareTo(existing.priority) >= 0) {
                return false;
            }
            queueOf(existing.priority).remove(existing);
            existing.priority = priority;
            queueOf(priority).addLast(existing);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The job queued or running under {@code key}; ended jobs aren't kept. */
    public Optional<Job> job(String key) {
        lock.lock();
        try {
            return Optional.ofNullable(jobs.get(key));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancels the job under {@code key}: a queued one is dropped at once, a running one is
     * interrupted and ends {@code CANCELLED} when its task returns.
     *
     * @return whether there was such a job
     */
    public boolean cancel(String key) {
        Job job;
        lock.lock();
        try {
            job = jobs.get(key);
            if (job == null) {
                return false;
            }
            cancelLocked(job);
        } finally {
            lock.unlock();
        }
        return true;
    }

    private void cancelLocked(Job job) {
        job.cancelled = true;
        if (job.state == State.QUEUED) {
            queueOf(job.priority).remove(job);
            jobs.remove(job.key, job);
            job.state = State.CANCELLED;
            job.end.complete(State.CANCELLED);
        } else if (job.state == State.RUNNING && job.thread != null) {
            job.thread.interrupt();
        }
    }

    private ArrayDeque<Job> queueOf(Priority p) {
        return p == Priority.HIGH ? high : normal;
    }

    private Thread startWorker() {
        return Thread.ofVirtual().name("overlay-worker-" + nextWorkerNumber++).start(this::workerLoop);
    }

    private void workerLoop() {
        while (true) {
            Job job;
            lock.lock();
            try {
                while (true) {
                    if (closing || workers.size() > workerCount) {
                        workers.remove(Thread.currentThread());
                        return;
                    }
                    job = high.pollFirst();
                    if (job == null) {
                        job = normal.pollFirst();
                    }
                    if (job != null) {
                        break;
                    }
                    signal.awaitUninterruptibly();
                }
                job.state = State.RUNNING;
                job.thread = Thread.currentThread();
                runningCount++;
            } finally {
                lock.unlock();
            }
            State result;
            Throwable failure = null;
            try {
                job.task.run(job);
                result = job.cancelled ? State.CANCELLED : State.DONE;
            } catch (Throwable t) {
                if (job.cancelled) {
                    result = State.CANCELLED;
                } else {
                    result = State.FAILED;
                    failure = t;
                    LOG.log(System.Logger.Level.WARNING, "Overlay job " + job.key + " failed", t);
                }
            }
            Thread.interrupted(); // a cancel's interrupt must not reach the next job
            if (result == State.DONE) {
                job.progress(1);
            }
            job.failure = failure;
            lock.lock();
            try {
                job.state = result;
                job.thread = null;
                runningCount--;
                jobs.remove(job.key, job);
            } finally {
                lock.unlock();
            }
            job.end.complete(result);
        }
    }

    /** Workers alive now (busy retiring ones included); for tests. */
    int liveWorkers() {
        lock.lock();
        try {
            int n = 0;
            for (Thread t : workers) {
                if (t.isAlive()) {
                    n++;
                }
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops the pool: queued jobs are dropped, running ones interrupted (all end
     * {@code CANCELLED}); waits up to {@link #CLOSE_TIMEOUT} for the workers. Nothing is kept for
     * the next start.
     */
    @Override
    public void close() {
        List<Thread> toJoin;
        lock.lock();
        try {
            if (closing) {
                return;
            }
            closing = true;
            for (Job job : new ArrayList<>(jobs.values())) {
                cancelLocked(job);
            }
            signal.signalAll();
            toJoin = new ArrayList<>(workers);
        } finally {
            lock.unlock();
        }
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        for (Thread t : toJoin) {
            long left = deadline - System.nanoTime();
            try {
                if (left <= 0 || !t.join(Duration.ofNanos(left))) {
                    LOG.log(System.Logger.Level.WARNING, "Overlay worker " + t.getName() + " did not stop in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
