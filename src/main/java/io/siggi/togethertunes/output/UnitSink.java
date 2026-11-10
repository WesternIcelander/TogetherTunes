package io.siggi.togethertunes.output;

/**
 * A consumer of the packager's units. Called on the packager thread, so it must never block: a
 * consumer that can't keep up buffers ({@link ConsumerQueue}) and is dropped when the buffer is
 * full.
 */
public interface UnitSink {
    /**
     * Takes a unit. Returns false if this sink is done (closed or overflowed): the packager then
     * removes it and calls {@link #close()}.
     */
    boolean deliver(TsUnit unit);

    /** The packager dropped this sink or stopped. Must not block; may be called more than once. */
    void close();
}
