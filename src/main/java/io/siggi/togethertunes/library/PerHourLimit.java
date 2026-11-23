package io.siggi.togethertunes.library;

import io.siggi.togethertunes.http.HttpError;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * A sliding one-hour limit per person (a user ID, or a guest's identity), for the YouTube searches
 * ({@link VideoSearch}: {@code mvfinder.maxPerHour}; {@link YoutubeSearch}:
 * {@code youtubeSearch.maxPerHour}). Over it, {@code 429 too_many_searches} with
 * {@code Retry-After}. In memory only (a restart forgets it); at most {@link #MAX_REMEMBERED} people
 * are remembered, the least recently seen forgotten first.
 */
final class PerHourLimit {
    private static final long HOUR_MS = 3_600_000L;
    static final int MAX_REMEMBERED = 10_000;

    private final IntSupplier maxPerHour;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    /** @param maxPerHour read for every call (at least 1 is used) */
    PerHourLimit(IntSupplier maxPerHour, Clock clock) {
        this.maxPerHour = Objects.requireNonNull(maxPerHour);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Counts one search for {@code who}, or throws {@code 429 too_many_searches}. */
    void take(String who) {
        int max = Math.max(1, maxPerHour.getAsInt());
        long now = clock.millis();
        synchronized (recent) {
            ArrayDeque<Long> times = recent.computeIfAbsent(who, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst() <= now - HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                long waitSec = Math.max(1, (times.peekFirst() + HOUR_MS - now + 999) / 1000);
                throw new HttpError(429, "too_many_searches", "Too many searches in the past hour; try again in "
                        + Math.max(1, (waitSec + 59) / 60) + " min", waitSec);
            }
            times.addLast(now);
        }
    }
}
