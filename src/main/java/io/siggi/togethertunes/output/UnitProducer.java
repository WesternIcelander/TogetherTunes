package io.siggi.togethertunes.output;

import java.io.IOException;

/**
 * Where the {@link Packager} gets its units from ({@link TimelineFollower} in production). Used on
 * the packager thread only, and closed there when the packager stops.
 */
public interface UnitProducer extends AutoCloseable {
    /**
     * Where the next unit will start (unwrapped output clock) if nothing changes; the unit
     * {@link #nextUnit} then builds may start up to one audio frame (1919 ticks) later after a
     * source switch.
     */
    long nextUnitStart();

    /** Builds the next unit; never null. */
    Unit nextUnit() throws IOException;

    @Override
    void close();
}
