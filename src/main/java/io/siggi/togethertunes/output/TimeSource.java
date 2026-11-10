package io.siggi.togethertunes.output;

import java.time.Clock;
import java.util.concurrent.locks.LockSupport;

/**
 * Time as the packager's pacing sees it: a monotonic clock to pace by, the server clock (epoch ms,
 * the clock the room timeline uses) to map the output clock to, and a way to sleep. Tests supply a
 * fake one.
 */
public interface TimeSource {
    /** Monotonic nanoseconds ({@link System#nanoTime()}). */
    long nanoTime();

    /** Server time, epoch ms. */
    long millis();

    /** Sleeps for about {@code nanos} (returns at once if not positive). */
    void sleepNanos(long nanos) throws InterruptedException;

    /** Real time, with {@code clock} as the server clock. */
    static TimeSource system(Clock clock) {
        return new TimeSource() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public long millis() {
                return clock.millis();
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                if (nanos <= 0) {
                    return;
                }
                long until = System.nanoTime() + nanos;
                long left = nanos;
                while (left > 0) {
                    LockSupport.parkNanos(left);
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    left = until - System.nanoTime();
                }
            }
        };
    }
}
