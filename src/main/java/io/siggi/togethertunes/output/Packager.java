package io.siggi.togethertunes.output;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs one program output: a platform thread that builds units from a {@link UnitProducer}, muxes
 * them once, and hands the same bytes to every consumer, paced to real time
 * ({@code docs/program-output.md#pacing}):
 *
 * <pre>
 * for each unit: wait until now ≥ due(unit.startPts) − LEAD, then build, mux and emit it
 * </pre>
 *
 * <p>The wait comes <em>before</em> the unit is built, so timeline changes are applied as late as
 * possible (a seek shows up about one unit plus {@link #LEAD_MS} later). The output is never more
 * than {@code LEAD} (plus at most one audio frame) ahead of real time.
 *
 * <p>Consumers never slow the packager down: each one is a {@link UnitSink} that must not block;
 * {@code .ts} clients and the RTMP pusher get a {@link ConsumerQueue} (bounded, 4 units), and a
 * consumer whose queue overflows is disconnected.
 *
 * <p>If the thread falls more than {@link #MAX_LAG_MS} behind (a stall, e.g. a slow disk), the
 * {@link PacingClock} is moved forward by the lag rather than bursting the missed output out.
 *
 * <p>If building or muxing a unit fails, the packager logs it and stops (closing every consumer).
 */
public final class Packager implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Packager.class.getName());

    /** How far ahead of real time a unit is emitted. */
    public static final long LEAD_MS = 1000;
    /** How far the thread may fall behind its schedule before the pacing is moved forward. */
    public static final long MAX_LAG_MS = 4000;

    /** Muxes a unit ({@link TsMuxer#mux} in production). */
    @FunctionalInterface
    public interface Muxer {
        byte[] mux(Unit unit) throws IOException;
    }

    private final String name;
    private final UnitProducer producer;
    private final Muxer muxer;
    private final PacingClock clock;
    private final TimeSource time;
    private final long leadNanos;
    private final long maxLagNanos;
    private final Thread thread;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<UnitSink> sinks = new CopyOnWriteArrayList<>();

    private TsUnit latest;
    private long sequence;
    private boolean started;
    private boolean closeCalled;
    private volatile boolean stopped;
    private volatile Throwable failure;

    /**
     * @param name     for the thread name and logs (e.g. the room id; never a stream token)
     * @param producer the units to play (closed by the packager when it stops)
     * @param muxer    turns units into TS bytes
     * @param clock    the pacing, the same one the producer maps the output clock to server time with
     * @param time     the time source {@code clock} was made from
     */
    public Packager(String name, UnitProducer producer, Muxer muxer, PacingClock clock, TimeSource time) {
        this(name, producer, muxer, clock, time, LEAD_MS, MAX_LAG_MS);
    }

    Packager(String name, UnitProducer producer, Muxer muxer, PacingClock clock, TimeSource time,
            long leadMs, long maxLagMs) {
        if (name == null || producer == null || muxer == null || clock == null || time == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        this.name = name;
        this.producer = producer;
        this.muxer = muxer;
        this.clock = clock;
        this.time = time;
        this.leadNanos = TimeUnit.MILLISECONDS.toNanos(leadMs);
        this.maxLagNanos = TimeUnit.MILLISECONDS.toNanos(maxLagMs);
        this.thread = Thread.ofPlatform().name("packager-" + name).daemon(true).unstarted(this::run);
    }

    /** Starts the packager thread (once). */
    public void start() {
        synchronized (lock) {
            if (started || stopped) {
                throw new IllegalStateException(stopped ? "packager stopped" : "packager already started");
            }
            started = true;
        }
        thread.start();
    }

    public String name() {
        return name;
    }

    public PacingClock clock() {
        return clock;
    }

    /** True until the packager stopped (closed or failed). */
    public boolean isRunning() {
        return !stopped;
    }

    /** Why the packager stopped by itself, or null. */
    public Throwable failure() {
        return failure;
    }

    /** The most recent unit emitted, or null before the first one. */
    public TsUnit latest() {
        synchronized (lock) {
            return latest;
        }
    }

    /**
     * Adds a consumer with a bounded queue. With {@code joinLatest} false ({@code join=next}) the
     * first unit it gets is the next one emitted; with true ({@code join=latest}) the most recent unit
     * is queued at once (if there is one), then every following one. If the packager has stopped, the
     * queue comes back closed.
     */
    public ConsumerQueue subscribe(int capacity, boolean joinLatest) {
        ConsumerQueue q = new ConsumerQueue(capacity);
        q.attach(this);
        synchronized (lock) {
            if (!stopped) {
                if (joinLatest && latest != null) {
                    q.prime(latest);
                }
                sinks.add(q);
                return q;
            }
        }
        q.close();
        return q;
    }

    /** A consumer with the default 4-unit queue. */
    public ConsumerQueue subscribe(boolean joinLatest) {
        return subscribe(ConsumerQueue.DEFAULT_CAPACITY, joinLatest);
    }

    /** Adds a consumer that handles units itself (e.g. the HLS segment ring); it gets the next unit on. */
    public void addSink(UnitSink sink) {
        synchronized (lock) {
            if (!stopped) {
                sinks.add(sink);
                return;
            }
        }
        sink.close();
    }

    /** Removes a consumer without closing it. */
    public void remove(UnitSink sink) {
        sinks.remove(sink);
    }

    /** Consumers attached now. */
    public int consumerCount() {
        return sinks.size();
    }

    private void run() {
        try {
            while (!stopped) {
                long due = clock.nanoTimeAt(producer.nextUnitStart()) - leadNanos;
                long wait = due - time.nanoTime();
                if (wait > 0) {
                    time.sleepNanos(wait);
                    if (stopped) {
                        break;
                    }
                }
                long late = time.nanoTime() - due;
                if (late > maxLagNanos) {
                    clock.rebase(late);
                    LOG.log(System.Logger.Level.WARNING, "Packager {0} fell {1} ms behind; pacing moved forward",
                            name, TimeUnit.NANOSECONDS.toMillis(late));
                }
                Unit u = producer.nextUnit();
                byte[] ts = muxer.mux(u);
                publish(u, ts);
            }
        } catch (InterruptedException e) {
            // stopping
        } catch (Throwable t) {
            if (!stopped) {
                failure = t;
                LOG.log(System.Logger.Level.ERROR, "Packager " + name + " failed; output stopped", t);
            }
        } finally {
            stopped = true;
            closeSinks();
            try {
                producer.close();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Closing the packager's producer failed", e);
            }
        }
    }

    private void publish(Unit u, byte[] ts) {
        TsUnit unit;
        synchronized (lock) {
            unit = new TsUnit(sequence++, u.startPts(), u.boundaryPts(), u.audioEndPts(), ts,
                    u.source() instanceof ItemSource is ? is.itemId() : null);
            latest = unit;
        }
        // a sink added after the snapshot already got this unit as "latest" (or wanted the next one)
        for (UnitSink s : sinks) {
            boolean keep;
            try {
                keep = s.deliver(unit);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "A packager consumer failed; dropped", e);
                keep = false;
            }
            if (!keep) {
                sinks.remove(s);
                closeQuietly(s);
            }
        }
    }

    private void closeSinks() {
        List<UnitSink> all;
        synchronized (lock) {
            all = new ArrayList<>(sinks);
            sinks.clear();
        }
        for (UnitSink s : all) {
            closeQuietly(s);
        }
    }

    private static void closeQuietly(UnitSink s) {
        try {
            s.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Closing a packager consumer failed", e);
        }
    }

    /**
     * Stops the packager: ends the thread (waiting for it up to 5 s), closes every consumer and the
     * producer. Safe to call more than once, from any thread.
     */
    @Override
    public void close() {
        boolean wasStarted;
        synchronized (lock) {
            if (closeCalled) {
                return;
            }
            closeCalled = true;
            stopped = true;
            wasStarted = started;
            started = true; // no start after close
        }
        if (!wasStarted) {
            closeSinks();
            producer.close();
            return;
        }
        if (Thread.currentThread() != thread) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeSinks();
    }
}
