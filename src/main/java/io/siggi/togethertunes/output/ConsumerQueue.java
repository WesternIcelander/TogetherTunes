package io.siggi.togethertunes.output;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A consumer's bounded buffer of units ({@code docs/program-output.md#direct-mpeg-ts-ts}): the
 * packager thread puts units in without ever waiting; the consumer's own thread (e.g. the virtual
 * thread writing a {@code .ts} response) takes them out. When a unit arrives while the queue is
 * full, the consumer is too slow: the queue closes with {@link CloseReason#OVERFLOW}, drops what it
 * holds, and runs its close action (which should end the connection, so a writer blocked on a
 * stalled client is freed). The client usually reconnects.
 */
public final class ConsumerQueue implements UnitSink {
    /** About 8 s of output. */
    public static final int DEFAULT_CAPACITY = 4;

    /** Why a queue closed. */
    public enum CloseReason {
        /** The consumer closed it (it left). */
        CONSUMER,
        /** It was full when a unit arrived: the consumer is too slow. */
        OVERFLOW,
        /** The packager stopped. */
        STOPPED
    }

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final ArrayDeque<TsUnit> units;
    private CloseReason closed;
    private Runnable onClose;
    private Packager packager;

    ConsumerQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.units = new ArrayDeque<>(capacity);
    }

    void attach(Packager p) {
        this.packager = p;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Sets what to run when the packager closes this queue (overflow or stop), e.g. closing the
     * client's connection. Runs at once if that already happened. It runs on the packager thread
     * (or the thread stopping the packager), so it must not block.
     */
    public void onClose(Runnable action) {
        boolean runNow;
        lock.lock();
        try {
            onClose = action;
            runNow = closed != null && closed != CloseReason.CONSUMER;
        } finally {
            lock.unlock();
        }
        if (runNow && action != null) {
            action.run();
        }
    }

    @Override
    public boolean deliver(TsUnit unit) {
        lock.lock();
        try {
            if (closed != null) {
                return false;
            }
            if (units.size() >= capacity) {
                closed = CloseReason.OVERFLOW;
                units.clear();
                notEmpty.signalAll();
                return false;
            }
            units.addLast(unit);
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Enqueued by {@link Packager#subscribe} for {@code join=latest}; counts towards the capacity. */
    void prime(TsUnit unit) {
        deliver(unit);
    }

    /**
     * The next unit, waiting for it as long as needed; null once the queue is closed (then any units
     * still queued are dropped).
     */
    public TsUnit take() throws InterruptedException {
        lock.lock();
        try {
            while (units.isEmpty() && closed == null) {
                notEmpty.await();
            }
            return closed != null ? null : units.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    /** Like {@link #take()}, but gives null after {@code timeoutMs} without a unit, too. */
    public TsUnit poll(long timeoutMs) throws InterruptedException {
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        lock.lock();
        try {
            while (units.isEmpty() && closed == null) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return closed != null ? null : units.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    /** Units waiting. */
    public int size() {
        lock.lock();
        try {
            return units.size();
        } finally {
            lock.unlock();
        }
    }

    /** Null while open, otherwise why it closed. */
    public CloseReason closeReason() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closeReason() != null;
    }

    /** The consumer leaves: closes the queue and removes it from the packager. */
    public void leave() {
        closeWith(CloseReason.CONSUMER);
        Packager p = packager;
        if (p != null) {
            p.remove(this);
        }
    }

    /** Called by the packager when it drops this queue (overflow) or stops. */
    @Override
    public void close() {
        closeWith(CloseReason.STOPPED);
    }

    private void closeWith(CloseReason reason) {
        Runnable action = null;
        lock.lock();
        try {
            if (closed == null) {
                closed = reason;
                units.clear();
                notEmpty.signalAll();
            }
            if (onClose != null && closed != CloseReason.CONSUMER) {
                action = onClose;
                onClose = null;
            }
        } finally {
            lock.unlock();
        }
        if (action != null) {
            action.run();
        }
    }
}
