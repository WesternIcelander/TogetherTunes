package io.siggi.togethertunes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move, safe to read from server threads. */
public final class TestClock extends Clock {
    private volatile long ms;

    public TestClock(long ms) {
        this.ms = ms;
    }

    public void set(long ms) {
        this.ms = ms;
    }

    public void advance(long deltaMs) {
        this.ms += deltaMs;
    }

    @Override
    public long millis() {
        return ms;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(ms);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
