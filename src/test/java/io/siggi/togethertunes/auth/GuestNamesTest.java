package io.siggi.togethertunes.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class GuestNamesTest {
    @Test
    void bundledListsHaveAboutThreeHundredTitleCasedWords() {
        GuestNames names = GuestNames.bundled();
        for (List<String> list : List.of(names.adjectives(), names.nouns())) {
            assertTrue(list.size() >= 280 && list.size() <= 340, "size " + list.size());
            assertEquals(list.size(), new HashSet<>(list).size());
            for (String word : list) {
                assertTrue(word.matches("[A-Z][a-z]+"), word);
            }
        }
        Set<String> both = new HashSet<>(names.adjectives());
        both.retainAll(names.nouns());
        assertEquals(Set.of(), both, "words in both lists could make names like \"Swift Swift\"");
    }

    @Test
    void drawsAdjectiveSpaceNoun() {
        GuestNames names = GuestNames.bundled(RandomGenerator.of("L64X128MixRandom"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String name = names.get();
            String[] parts = name.split(" ");
            assertEquals(2, parts.length, name);
            assertTrue(names.adjectives().contains(parts[0]), name);
            assertTrue(names.nouns().contains(parts[1]), name);
            assertTrue(name.length() <= Guests.NAME_MAX, name);
            seen.add(name);
        }
        assertTrue(seen.size() > 150, "draws should rarely repeat: " + seen.size());
    }

    @Test
    void fixedDrawsComeFromTheLists() {
        GuestNames names = new GuestNames(List.of("Quiet"), List.of("Walrus"), RandomGenerator.of("L64X128MixRandom"));
        assertEquals("Quiet Walrus", names.get());
    }
}
