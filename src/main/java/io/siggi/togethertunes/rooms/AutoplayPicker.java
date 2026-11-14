package io.siggi.togethertunes.rooms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks the songs autoplay adds ({@code docs/rooms.md#autoplay}): always shuffled, from one pool
 * ({@code autoplay.mix} {@code random}) or taking turns between the people who selected
 * playlists ({@code turns}), each song weighted by how recently it played in the room. Pure: no
 * database, no room state; the randomness comes from the caller's {@link Random}, so a seeded one
 * gives the same picks every time.
 *
 * <h2>Recently played songs</h2>
 * A song's <b>age</b> is how many songs started playing in the room since it last started: 0 for
 * the song that started most recently (usually the one playing now), 1 for the one before, and so
 * on; a song that never played here (or longer ago than the history read) has no age. With
 * {@code X = recentPct / 100 × N}, where {@code N} is the number of different songs in the pool
 * being picked from (the whole pool in {@code random} mode, that person's songs in {@code turns}
 * mode), the weight is linear in the age:
 *
 * <pre>
 *   weight(age) = min(1, age / X)      (X &gt; 0)
 *   weight      = 1                    (no age, or X = 0: recentPct 0 switches the ramp off)
 * </pre>
 *
 * So a song has weight 0 right after it played and is back to full weight once X songs have
 * played after it (pool of 40 at 50 %: X = 20, weight 0.5 after 10 songs). A song is picked with
 * probability weight / total weight among the candidates. When every candidate has weight 0
 * (e.g. a one-song pool whose song just played), one of them is picked uniformly, so a small pool
 * still repeats instead of stopping.
 *
 * <h2>Candidates</h2>
 * Songs already waiting in the queue ({@code excluded}) and songs picked earlier in the same call
 * are never candidates, so the same song is never queued twice by autoplay.
 *
 * <h2>Turns</h2>
 * People take turns in their order in the {@link Pool} (the room orders them by when they first
 * selected a playlist there); the first turn goes to the person after {@code lastTurnUserId} (the
 * person autoplay added an entry for last), or to the first person. A person with no candidate
 * left is passed over for that turn.
 */
final class AutoplayPicker {
    private AutoplayPicker() {
    }

    /** One person who selected playlists, with the different songs (item ids) in them. */
    record Person(String userId, Set<String> itemIds) {
        Person {
            Objects.requireNonNull(userId, "userId");
            itemIds = new TreeSet<>(itemIds); // a stable order for the seeded random
        }
    }

    /** Everyone's selected songs; people in turn order. */
    record Pool(List<Person> people) {
        Pool {
            people = List.copyOf(people);
        }

        boolean isEmpty() {
            for (Person p : people) {
                if (!p.itemIds().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** The different songs of everyone, each with the first person (in turn order) who selected it. */
        Map<String, String> union() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Person p : people) {
                for (String item : p.itemIds()) {
                    out.putIfAbsent(item, p.userId());
                }
            }
            return out;
        }

        /** The largest pool picked from in {@code turns} or {@code random} mode (how much history to read). */
        int largest(boolean turns) {
            if (!turns) {
                return union().size();
            }
            int max = 0;
            for (Person p : people) {
                max = Math.max(max, p.itemIds().size());
            }
            return max;
        }
    }

    /** A picked song and the person it is added for (whose playlist it came from). */
    record Pick(String itemId, String userId) {
    }

    /**
     * The ramp's length: {@code recentPct} % of a pool of {@code poolSize} songs.
     */
    static double rampLength(int recentPct, int poolSize) {
        return recentPct / 100.0 * poolSize;
    }

    /**
     * A song's weight: {@code min(1, age / x)}; 1 with no age ({@code null}) or {@code x <= 0}.
     */
    static double weight(Integer age, double x) {
        if (age == null || x <= 0) {
            return 1;
        }
        return Math.min(1, Math.max(0, age) / x);
    }

    /**
     * Picks up to {@code count} songs.
     *
     * @param ages           item id -> age (see the class comment); items missing have none
     * @param excluded       items never to pick (already waiting in the queue)
     * @param turns          take turns between the people ({@code autoplay.mix turns}) instead of one pool
     * @param lastTurnUserId the person autoplay added an entry for last, or {@code null}
     * @return the picks in order; fewer than {@code count} when the candidates ran out
     */
    static List<Pick> pick(Pool pool, Map<String, Integer> ages, Set<String> excluded, int count, boolean turns,
                           int recentPct, String lastTurnUserId, Random random) {
        List<Pick> out = new ArrayList<>();
        Set<String> taken = new HashSet<>(excluded);
        if (count <= 0 || pool.isEmpty()) {
            return out;
        }
        if (!turns) {
            Map<String, String> union = pool.union();
            double x = rampLength(recentPct, union.size());
            while (out.size() < count) {
                List<String> candidates = new ArrayList<>();
                for (String item : union.keySet()) {
                    if (!taken.contains(item)) {
                        candidates.add(item);
                    }
                }
                if (candidates.isEmpty()) {
                    break;
                }
                String item = weighted(candidates, ages, x, random);
                taken.add(item);
                out.add(new Pick(item, union.get(item)));
            }
            return out;
        }
        List<Person> people = pool.people();
        int next = 0;
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).userId().equals(lastTurnUserId)) {
                next = (i + 1) % people.size();
                break;
            }
        }
        while (out.size() < count) {
            boolean found = false;
            for (int k = 0; k < people.size() && !found; k++) {
                int index = (next + k) % people.size();
                Person p = people.get(index);
                List<String> candidates = new ArrayList<>();
                for (String item : p.itemIds()) {
                    if (!taken.contains(item)) {
                        candidates.add(item);
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                String item = weighted(candidates, ages, rampLength(recentPct, p.itemIds().size()), random);
                taken.add(item);
                out.add(new Pick(item, p.userId()));
                next = (index + 1) % people.size();
                found = true;
            }
            if (!found) {
                break;
            }
        }
        return out;
    }

    /** One of {@code candidates} (not empty), by weight; uniformly when every weight is 0. */
    private static String weighted(List<String> candidates, Map<String, Integer> ages, double x, Random random) {
        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = weight(ages.get(candidates.get(i)), x);
            total += weights[i];
        }
        if (total <= 0) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0 && weights[i] > 0) {
                return candidates.get(i);
            }
        }
        // rounding: the last candidate with any weight
        for (int i = weights.length - 1; i >= 0; i--) {
            if (weights[i] > 0) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
