package io.siggi.togethertunes.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AutoplayPicker} with a seeded random: the weight ramp, the distribution in one pool and
 * with recently played songs, taking turns, and empty or used-up pools.
 */
class AutoplayPickerTest {
    private static AutoplayPicker.Pool pool(AutoplayPicker.Person... people) {
        return new AutoplayPicker.Pool(List.of(people));
    }

    private static AutoplayPicker.Person person(String user, String... items) {
        return new AutoplayPicker.Person(user, Set.of(items));
    }

    @Test
    void theRampIsLinearFromZeroToFull() {
        // pool of 40 at 50 %: back to full after 20 songs
        double x = AutoplayPicker.rampLength(50, 40);
        assertEquals(20, x, 1e-9);
        assertEquals(0, AutoplayPicker.weight(0, x), 1e-9, "weight 0 right after it played");
        assertEquals(0.05, AutoplayPicker.weight(1, x), 1e-9);
        assertEquals(0.5, AutoplayPicker.weight(10, x), 1e-9);
        assertEquals(1, AutoplayPicker.weight(20, x), 1e-9);
        assertEquals(1, AutoplayPicker.weight(500, x), 1e-9);
        assertEquals(1, AutoplayPicker.weight(null, x), 1e-9, "never played here: full weight");
        assertEquals(1, AutoplayPicker.weight(0, AutoplayPicker.rampLength(0, 40)), 1e-9, "recentPct 0: no ramp");
        assertEquals(0.5, AutoplayPicker.weight(1, AutoplayPicker.rampLength(100, 2)), 1e-9);
    }

    @Test
    void oneSongAtATimeIsUniformWithoutHistory() {
        AutoplayPicker.Pool p = pool(person("alice", "a", "b"), person("bob", "c", "d"));
        Random random = new Random(1);
        Map<String, Integer> counts = new HashMap<>();
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            List<AutoplayPicker.Pick> picks = AutoplayPicker.pick(p, Map.of(), Set.of(), 1, false, 50, null, random);
            assertEquals(1, picks.size());
            counts.merge(picks.get(0).itemId(), 1, Integer::sum);
        }
        for (String item : List.of("a", "b", "c", "d")) {
            double share = counts.getOrDefault(item, 0) / (double) n;
            assertEquals(0.25, share, 0.015, item + ": " + share);
        }
    }

    @Test
    void recentlyPlayedSongsAreWeightedByTheRamp() {
        // pool of 4 at 100 %: X = 4. a just played (0), b two songs ago (0.5), c and d full (1).
        AutoplayPicker.Pool p = pool(person("alice", "a", "b", "c", "d"));
        Map<String, Integer> ages = Map.of("a", 0, "b", 2, "d", 7);
        Random random = new Random(2);
        Map<String, Integer> counts = new HashMap<>();
        int n = 35_000;
        for (int i = 0; i < n; i++) {
            counts.merge(AutoplayPicker.pick(p, ages, Set.of(), 1, false, 100, null, random).get(0).itemId(), 1,
                    Integer::sum);
        }
        assertEquals(0, counts.getOrDefault("a", 0), "weight 0 right after it played");
        assertEquals(0.5 / 2.5, counts.get("b") / (double) n, 0.015);
        assertEquals(1 / 2.5, counts.get("c") / (double) n, 0.015);
        assertEquals(1 / 2.5, counts.get("d") / (double) n, 0.015);
    }

    @Test
    void aFullSectionUsesEverySongOnceAndNeverAWaitingOne() {
        AutoplayPicker.Pool p = pool(person("alice", "a", "b", "c", "d", "e"));
        List<AutoplayPicker.Pick> picks = AutoplayPicker.pick(p, Map.of(), Set.of("c"), 10, false, 50, null,
                new Random(3));
        assertEquals(4, picks.size(), "only the songs that aren't waiting");
        assertEquals(Set.of("a", "b", "d", "e"), Set.copyOf(picks.stream().map(AutoplayPicker.Pick::itemId).toList()));
    }

    @Test
    void whenEverySongJustPlayedOneIsPickedAnyway() {
        AutoplayPicker.Pool p = pool(person("alice", "a"));
        List<AutoplayPicker.Pick> picks = AutoplayPicker.pick(p, Map.of("a", 0), Set.of(), 1, false, 50, null,
                new Random(4));
        assertEquals(List.of(new AutoplayPicker.Pick("a", "alice")), picks, "a one-song pool repeats");
    }

    @Test
    void emptyPoolsPickNothing() {
        assertEquals(List.of(), AutoplayPicker.pick(pool(), Map.of(), Set.of(), 5, false, 50, null, new Random(5)));
        assertEquals(List.of(), AutoplayPicker.pick(pool(person("alice")), Map.of(), Set.of(), 5, true, 50, null,
                new Random(5)));
        assertEquals(List.of(), AutoplayPicker.pick(pool(person("alice", "a")), Map.of(), Set.of("a"), 5, true, 50,
                null, new Random(5)), "everything is waiting already");
        assertEquals(List.of(), AutoplayPicker.pick(pool(person("alice", "a")), Map.of(), Set.of(), 0, false, 50,
                null, new Random(5)));
    }

    @Test
    void oneSongSelectedByTwoPeopleIsAddedForTheFirstOfThem() {
        AutoplayPicker.Pool p = pool(person("alice", "a"), person("bob", "a"));
        assertEquals(List.of(new AutoplayPicker.Pick("a", "alice")),
                AutoplayPicker.pick(p, Map.of(), Set.of(), 3, false, 50, null, new Random(6)));
    }

    @Test
    void peopleTakeTurns() {
        AutoplayPicker.Pool p = pool(person("alice", "a1", "a2", "a3", "a4"), person("bob", "b1", "b2"),
                person("carol", "c1", "c2", "c3"));
        List<String> users = AutoplayPicker.pick(p, Map.of(), Set.of(), 9, true, 50, null, new Random(7)).stream()
                .map(AutoplayPicker.Pick::userId).toList();
        assertEquals(List.of("alice", "bob", "carol", "alice", "bob", "carol", "alice", "carol", "alice"), users,
                "round and round; someone with no songs left is passed over");
        List<String> after = AutoplayPicker.pick(p, Map.of(), Set.of(), 3, true, 50, "bob", new Random(7)).stream()
                .map(AutoplayPicker.Pick::userId).toList();
        assertEquals(List.of("carol", "alice", "bob"), after, "the turn after the last one");
        List<String> gone = AutoplayPicker.pick(p, Map.of(), Set.of(), 2, true, 50, "dave", new Random(7)).stream()
                .map(AutoplayPicker.Pick::userId).toList();
        assertEquals(List.of("alice", "bob"), gone, "the last one isn't selecting any more: from the first");
        List<String> skipped = AutoplayPicker.pick(p, Map.of(), Set.of("b1", "b2"), 3, true, 50, "alice",
                new Random(7)).stream().map(AutoplayPicker.Pick::userId).toList();
        assertEquals(List.of("carol", "alice", "carol"), skipped, "bob's songs are all waiting already");
    }

    @Test
    void inTurnsTheRampUsesThatPersonsPool() {
        // bob has 2 songs at 100 %: X = 2, so b1 one song ago has weight 0.5 and b2 full weight
        AutoplayPicker.Pool p = pool(person("alice", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"),
                person("bob", "b1", "b2"));
        Random random = new Random(8);
        int b1 = 0;
        int n = 30_000;
        for (int i = 0; i < n; i++) {
            AutoplayPicker.Pick pick = AutoplayPicker.pick(p, Map.of("b1", 1), Set.of(), 1, true, 100, "alice",
                    random).get(0);
            assertEquals("bob", pick.userId());
            if (pick.itemId().equals("b1")) {
                b1++;
            }
        }
        assertEquals(0.5 / 1.5, b1 / (double) n, 0.015);
    }

    @Test
    void theSameSeedPicksTheSameSongs() {
        AutoplayPicker.Pool p = pool(person("alice", "a", "b", "c", "d", "e", "f"), person("bob", "g", "h"));
        Map<String, Integer> ages = Map.of("a", 0, "c", 1);
        List<AutoplayPicker.Pick> one = AutoplayPicker.pick(p, ages, Set.of(), 5, false, 50, null, new Random(9));
        List<AutoplayPicker.Pick> two = AutoplayPicker.pick(p, ages, Set.of(), 5, false, 50, null, new Random(9));
        assertEquals(one, two);
        assertTrue(one.stream().noneMatch(pick -> pick.itemId().equals("a")), "weight 0 while others have some");
    }
}
