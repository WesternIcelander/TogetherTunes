package io.siggi.togethertunes.rooms;

import io.siggi.togethertunes.db.Database;
import io.siggi.togethertunes.db.EpochMs;
import io.siggi.togethertunes.http.HttpError;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The single thread that owns one room's {@link RoomState} ({@code docs/architecture.md#process-and-threading}).
 * Every read and change of the room's live state is a {@link RoomMutation} run here, one at a
 * time, in submission order. For each mutation that changed something, the executor:
 * <ol>
 *   <li>runs the mutation (which changes the in-memory state and registers its writes),</li>
 *   <li>persists: the mutation's writes and the room row (new {@code version} and the timeline),
 *       in one transaction,</li>
 *   <li>bumps {@link RoomState#version()},</li>
 *   <li>broadcasts the {@link RoomChange} to the {@link RoomListener}s,</li>
 *   <li>notifies the {@link PackagerHook} (the program output).</li>
 * </ol>
 * If the mutation throws something other than an {@link HttpError} (or throws after registering a
 * write or event), or the transaction fails, the state is reloaded from the database, so memory
 * never runs ahead of what was committed; nothing is broadcast and the version stays.
 *
 * <p>The thread is a virtual thread. Timed work uses {@link #schedule}, so it also runs here.
 * The executor keeps one <b>advance task</b> scheduled for when the current entry ends
 * ({@link Playback#advanceIfDue}); it is rescheduled after every committed change that re-anchors
 * the timeline, after a reload, and when a mutation asks for it. Created, started
 * ({@link #start}: {@link Playback#resume}) and closed by {@link RoomRegistry}.
 *
 * <p>After every mutation and scheduled task the executor publishes the room's current and next
 * items to the registry's {@link LivePlayingItems} (the media access check), and withdraws them
 * when it closes.
 */
public final class RoomExecutor {
    private static final System.Logger LOG = System.getLogger(RoomExecutor.class.getName());
    private static final ThreadLocal<RoomExecutor> CURRENT = new ThreadLocal<>();

    private final String roomId;
    private final RoomStore store;
    private final Clock clock;
    private final PackagerHook packager;
    private final LivePlayingItems playing;
    private final Consumer<RoomExecutor> onClosed;
    private final ScheduledThreadPoolExecutor executor;
    private final List<RoomListener> listeners = new CopyOnWriteArrayList<>();
    /** Only touched on the executor thread. */
    private RoomState state;
    /** The pending advance task, or {@code null}. Only touched on the executor thread. */
    private ScheduledFuture<?> advanceTask;
    private volatile boolean closed;

    RoomExecutor(RoomState initial, RoomStore store, Clock clock, PackagerHook packager,
                 LivePlayingItems playing, Consumer<RoomExecutor> onClosed) {
        this.state = Objects.requireNonNull(initial);
        this.roomId = initial.id();
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.packager = Objects.requireNonNull(packager);
        this.playing = Objects.requireNonNull(playing);
        this.onClosed = Objects.requireNonNull(onClosed);
        this.executor = new ScheduledThreadPoolExecutor(1, Thread.ofVirtual().name("room-" + roomId).factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public String roomId() {
        return roomId;
    }

    /**
     * Queues the start-up work: {@link Playback#resume} (a playing room continues, or restarts its
     * entry if the server was down past its end) and the first advance task. Called once by the
     * registry right after creating the executor, before anything else is submitted.
     */
    CompletableFuture<Void> start() {
        return submit(tx -> {
            Playback.resume(tx);
            tx.refreshAutoplay(); // fill the autoplay section (selections may have changed meanwhile)
            return null;
        });
    }

    /** Whether this executor stopped taking work. */
    public boolean isClosed() {
        return closed;
    }

    /** Whether the calling thread is this executor's thread (inside a mutation or listener call). */
    public boolean onExecutorThread() {
        return CURRENT.get() == this;
    }

    public void addListener(RoomListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(RoomListener listener) {
        listeners.remove(listener);
    }

    /**
     * Queues a mutation. The future completes with its result, or exceptionally with what it
     * threw, or with {@link RoomClosedException} if the executor closed first.
     */
    public <T> CompletableFuture<T> submit(RoomMutation<T> mutation) {
        Objects.requireNonNull(mutation);
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new RoomClosedException(roomId));
            return future;
        }
        try {
            executor.execute(() -> {
                if (closed) {
                    future.completeExceptionally(new RoomClosedException(roomId));
                    return;
                }
                CURRENT.set(this);
                T value = null;
                Throwable failure = null;
                try {
                    value = run(mutation);
                } catch (Throwable t) {
                    failure = t;
                }
                try {
                    publishPlaying(); // before completing: the caller sees the set up to date
                } finally {
                    CURRENT.remove();
                }
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(value);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new RoomClosedException(roomId));
        }
        return future;
    }

    /**
     * Runs a mutation and waits for it. Rethrows what the mutation threw. If the waiting thread
     * is interrupted the mutation may still run.
     *
     * @throws RoomClosedException   if the executor closed before running it
     * @throws IllegalStateException when called on this executor's own thread (it would deadlock)
     */
    public <T> T call(RoomMutation<T> mutation) throws Exception {
        if (onExecutorThread()) {
            throw new IllegalStateException("call() on the room's own executor thread would deadlock");
        }
        try {
            return submit(mutation).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /**
     * Runs a mutation on this executor after {@code delay} (for example the advance task). Cancel
     * it with the returned future; a cancelled or not yet run task is dropped when the executor
     * closes. Failures are logged.
     *
     * @throws RoomClosedException if the executor is closed
     */
    public ScheduledFuture<?> schedule(Duration delay, RoomMutation<?> mutation) {
        Objects.requireNonNull(mutation);
        if (closed) {
            throw new RoomClosedException(roomId);
        }
        try {
            return executor.schedule(() -> {
                if (closed) {
                    return;
                }
                CURRENT.set(this);
                try {
                    run(mutation);
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING, "Scheduled task in room " + roomId + " failed", t);
                } finally {
                    publishPlaying();
                    CURRENT.remove();
                }
            }, Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            throw new RoomClosedException(roomId);
        }
    }

    /**
     * Closes the executor after the work already queued (the server is stopping). Listeners get
     * {@link RoomListener#onClosed} with {@code deleted = false}. Doesn't wait; see
     * {@link #awaitTermination}.
     */
    void close() {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> {
                if (!closed) {
                    CURRENT.set(this);
                    try {
                        closeNow(false);
                    } finally {
                        CURRENT.remove();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Already shut down.
        }
    }

    /** Waits for the executor thread to finish after {@link #close}. */
    boolean awaitTermination(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ on the executor thread

    private <T> T run(RoomMutation<T> mutation) throws Exception {
        RoomTx tx = new RoomTx(state, EpochMs.now(clock), store, packager);
        T value;
        try {
            value = mutation.apply(tx);
        } catch (Throwable t) {
            if (!(t instanceof HttpError) || tx.touched()) {
                reload();
            }
            throw t;
        }
        if (tx.closesRoom()) {
            closeNow(true);
            return value;
        }
        if (tx.changed() || tx.refreshesAutoplay()) {
            // keep the autoplay section filled (docs/rooms.md#autoplay), in the same commit
            Autoplay.maintain(tx, store.random());
        }
        if (!tx.changed()) {
            if (tx.reschedulesAdvance()) {
                rescheduleAdvance();
            }
            return value;
        }
        RoomState s = state;
        long version = s.version() + 1;
        boolean saved;
        try {
            saved = store.db().write(c -> {
                for (Database.SqlWork<?> write : tx.writes()) {
                    write.run(c);
                }
                return RoomStore.save(c, s, version);
            });
        } catch (Throwable t) {
            reload();
            throw t;
        }
        if (!saved) {
            closeNow(true); // the row is gone: deleted behind our back
            throw new HttpError(404, "room_not_found", "No such room");
        }
        s.version(version);
        RoomChange change = new RoomChange(roomId, version, tx.events(), s.timeline());
        for (RoomListener listener : listeners) {
            try {
                listener.onChange(s, change);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Room listener failed in room " + roomId, t);
            }
        }
        try {
            packager.roomChanged(s, change);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Packager hook failed in room " + roomId, t);
        }
        if (change.timelineChanged() || tx.reschedulesAdvance()) {
            rescheduleAdvance();
        }
        return value;
    }

    /**
     * Cancels the advance task and schedules a new one for when the current entry ends, if the
     * timeline is moving towards an end ({@link Playback#advanceDelayMs}).
     */
    private void rescheduleAdvance() {
        if (advanceTask != null) {
            advanceTask.cancel(false);
            advanceTask = null;
        }
        if (closed) {
            return;
        }
        OptionalLong delay = Playback.advanceDelayMs(state, EpochMs.now(clock));
        if (delay.isPresent()) {
            advanceTask = schedule(Duration.ofMillis(delay.getAsLong()), tx -> {
                Playback.advanceIfDue(tx);
                return null;
            });
        }
    }

    /** The delay until the pending advance task runs, in ms, or {@code -1} if there is none (tests). */
    long advanceTaskDelayMs() throws Exception {
        return call(tx -> {
            ScheduledFuture<?> f = advanceTask;
            return f == null || f.isDone() ? -1L : f.getDelay(TimeUnit.MILLISECONDS);
        });
    }

    /** Replaces the in-memory state with the committed one; closes the executor if that fails. */
    private void reload() {
        try {
            RoomState fresh = store.load(roomId);
            if (fresh == null) {
                closeNow(true);
            } else {
                fresh.adoptPresence(state); // the connections are still open
                state = fresh;
                rescheduleAdvance();
            }
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Could not reload room " + roomId + "; closing its executor", t);
            closeNow(false);
        }
    }

    /**
     * Publishes the room's current and next items ({@link LivePlayingItems#of}) for the media
     * access check, and its ranked wanted items ({@link LivePlayingItems#ranks(RoomState)}) for
     * the ingest claim order. Runs after every mutation and scheduled task (whatever its outcome: a failure
     * may have reloaded the state), on the executor thread; a no-op once closed.
     */
    private void publishPlaying() {
        if (closed) {
            return;
        }
        try {
            playing.update(roomId, this, LivePlayingItems.of(state), LivePlayingItems.ranks(state));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Could not publish the playing items of room " + roomId, t);
        }
    }

    private void closeNow(boolean deleted) {
        if (closed) {
            return;
        }
        closed = true;
        playing.remove(roomId, this);
        if (advanceTask != null) {
            advanceTask.cancel(false);
            advanceTask = null;
        }
        executor.shutdown(); // queued work fails with RoomClosedException; delayed tasks are dropped
        onClosed.accept(this);
        for (RoomListener listener : listeners) {
            try {
                listener.onClosed(roomId, deleted);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Room listener failed in room " + roomId, t);
            }
        }
        try {
            packager.roomClosed(roomId, deleted);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Packager hook failed in room " + roomId, t);
        }
    }
}
