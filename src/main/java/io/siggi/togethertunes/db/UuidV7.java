package io.siggi.togethertunes.db;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * Generates UUIDv7 IDs (RFC 9562), the primary keys of every table.
 *
 * <p>Layout: 48-bit Unix epoch milliseconds, version {@code 7}, 12 bits {@code rand_a}, variant
 * {@code 10}, 62 bits {@code rand_b}. IDs are stored as the canonical 36-character lowercase
 * string ({@link #next()}), which sorts the same way as the 128-bit value, so {@code ORDER BY id}
 * is creation order in both dialects.
 *
 * <p>IDs from one generator are strictly increasing, even within one millisecond and even when
 * the clock steps backwards (RFC 9562 section 6.2, method 2 "monotonic random"): a new
 * millisecond gets 74 fresh random bits; otherwise the previous ID's 74 bits are incremented by a
 * random amount between 1 and 2<sup>32</sup>, so a neighbouring ID still can't be guessed from
 * one that is known. If those bits would overflow, the timestamp moves on by one millisecond.
 * The embedded time therefore never goes backwards and is at most slightly ahead of the clock.
 *
 * <p>Thread-safe. Uses a {@link ReentrantLock} (not {@code synchronized}), so virtual threads
 * don't pin their carrier.
 */
public final class UuidV7 {
    private static final UuidV7 DEFAULT = new UuidV7(Clock.systemUTC(), new SecureRandom());

    /** Largest timestamp that fits in 48 bits (year 10889). */
    static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final long RAND_B_MASK = (1L << 62) - 1;
    private static final int RAND_A_MASK = (1 << 12) - 1;
    private static final long MAX_INCREMENT = 1L << 32;

    private final Clock clock;
    private final RandomGenerator random;
    private final ReentrantLock lock = new ReentrantLock();
    private long lastMs = -1;
    private int lastRandA;
    private long lastRandB;

    /**
     * A generator reading {@code clock} and drawing bits from {@code random}. Production code uses
     * {@link #newId()}; this constructor is for tests (fixed clocks, seeded randoms).
     */
    public UuidV7(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** A new ID from the shared generator (system clock, {@link SecureRandom}). */
    public static String newId() {
        return DEFAULT.next();
    }

    /** The next ID as its canonical lowercase string. */
    public String next() {
        return nextUuid().toString();
    }

    /** The next ID. */
    public UUID nextUuid() {
        long now = clock.millis();
        if (now < 0 || now > MAX_TIMESTAMP) {
            throw new IllegalStateException("Clock out of UUIDv7 range: " + now);
        }
        long ms;
        int randA;
        long randB;
        lock.lock();
        try {
            if (now > lastMs) {
                ms = now;
                randA = random.nextInt() & RAND_A_MASK;
                randB = random.nextLong() & RAND_B_MASK;
            } else {
                ms = lastMs;
                randA = lastRandA;
                randB = lastRandB + random.nextLong(1, MAX_INCREMENT + 1);
                if (randB > RAND_B_MASK) {
                    randB &= RAND_B_MASK;
                    randA++;
                    if (randA > RAND_A_MASK) {
                        // All 74 bits used up in this millisecond: borrow the next one.
                        ms++;
                        if (ms > MAX_TIMESTAMP) {
                            throw new IllegalStateException("UUIDv7 timestamp overflow");
                        }
                        randA = random.nextInt() & RAND_A_MASK;
                        randB = random.nextLong() & RAND_B_MASK;
                    }
                }
            }
            lastMs = ms;
            lastRandA = randA;
            lastRandB = randB;
        } finally {
            lock.unlock();
        }
        return build(ms, randA, randB);
    }

    static UUID build(long ms, int randA, long randB) {
        long msb = (ms << 16) | 0x7000L | (randA & RAND_A_MASK);
        long lsb = 0x8000_0000_0000_0000L | (randB & RAND_B_MASK);
        return new UUID(msb, lsb);
    }

    /**
     * Whether {@code id} is a canonical UUIDv7 string: 36 characters, lowercase hex, hyphens in
     * the usual places, version {@code 7}, RFC variant. Use it to check IDs from URLs and request
     * bodies before querying (an invalid ID is simply "not found").
     */
    public static boolean isValid(String id) {
        if (id == null || id.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = id.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        char variant = id.charAt(19);
        return id.charAt(14) == '7' && (variant == '8' || variant == '9' || variant == 'a' || variant == 'b');
    }

    /** The creation time (epoch ms) embedded in a UUIDv7. */
    public static long timestampMs(UUID id) {
        if (id.version() != 7 || id.variant() != 2) {
            throw new IllegalArgumentException("Not a UUIDv7: " + id);
        }
        return id.getMostSignificantBits() >>> 16;
    }

    /** The creation time (epoch ms) embedded in a canonical UUIDv7 string. */
    public static long timestampMs(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Not a canonical UUIDv7: " + id);
        }
        return timestampMs(UUID.fromString(id));
    }
}
