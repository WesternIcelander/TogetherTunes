package io.siggi.togethertunes.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The episode order and Next episode ({@link Episodes}, {@code docs/media-types.md#next-episode}). */
class EpisodesTest {
    private record Ep(String id, String show, Integer season, int episode) {
        Episodes.Place place() {
            return new Episodes.Place(season, episode);
        }
    }

    private static Ep next(List<Ep> all, Integer season, int episode) {
        return Episodes.next(all, new Episodes.Place(season, episode), Ep::place, Ep::id);
    }

    @Test
    void nextWithinASeasonThenAcrossSeasons() {
        List<Ep> all = List.of(new Ep("s2e1", "S", 2, 1), new Ep("s1e2", "S", 1, 2), new Ep("s1e1", "S", 1, 1),
                new Ep("s2e2", "S", 2, 2), new Ep("s0e1", "S", 0, 1));
        assertEquals("s1e2", next(all, 1, 1).id());
        assertEquals("s2e1", next(all, 1, 2).id()); // the lowest episode of the next season
        assertEquals("s2e2", next(all, 2, 1).id());
        assertEquals("s1e1", next(all, 0, 1).id()); // specials (season 0) come before season 1
    }

    @Test
    void gapsAreSkipped() {
        List<Ep> all = List.of(new Ep("s1e1", "S", 1, 1), new Ep("s1e4", "S", 1, 4), new Ep("s3e2", "S", 3, 2),
                new Ep("s3e7", "S", 3, 7));
        assertEquals("s1e4", next(all, 1, 1).id());
        assertEquals("s3e2", next(all, 1, 4).id()); // no season 2: the next season that exists
        assertEquals("s3e7", next(all, 3, 2).id());
        // an episode that isn't in the library itself still has a next one
        assertEquals("s1e4", next(all, 1, 2).id());
        assertEquals("s3e2", next(all, 2, 5).id());
    }

    @Test
    void missingSeasonNumbersComeFirst() {
        List<Ep> all = List.of(new Ep("e1", "S", null, 1), new Ep("e3", "S", null, 3), new Ep("s1e1", "S", 1, 1),
                new Ep("s0e5", "S", 0, 5));
        assertEquals("e3", next(all, null, 1).id());
        assertEquals("s0e5", next(all, null, 3).id()); // after the episodes without a season: season 0
        assertEquals("s1e1", next(all, 0, 5).id());
        // a show that never has season numbers
        List<Ep> flat = List.of(new Ep("a", "S", null, 10), new Ep("b", "S", null, 2));
        assertEquals("a", next(flat, null, 2).id());
        assertNull(next(flat, null, 10));
    }

    @Test
    void nothingNext() {
        List<Ep> all = List.of(new Ep("s1e1", "S", 1, 1), new Ep("s1e2", "S", 1, 2));
        assertNull(next(all, 1, 2));
        assertNull(next(all, 4, 0));
        assertNull(next(List.of(), 1, 1));
        // the episode itself, or a duplicate of it, is never next
        assertNull(next(List.of(new Ep("x", "S", 1, 2), new Ep("y", "S", 1, 2)), 1, 2));
    }

    @Test
    void duplicatesPickTheLowestId() {
        List<Ep> all = List.of(new Ep("b", "S", 1, 2), new Ep("a", "S", 1, 2));
        assertEquals("a", next(all, 1, 1).id());
    }

    @Test
    void groupsByShowNameSeasonAndEpisode() {
        List<Ep> all = List.of(new Ep("b2", "Beta", 2, 1), new Ep("a1", "alpha ", 1, 2), new Ep("b0", "Beta", null, 3),
                new Ep("a0", "Alpha", 1, 1), new Ep("b1", "beta", 1, 1));
        List<Episodes.Show<Ep>> shows = Episodes.group(all, Ep::show, Ep::place, Ep::id);
        assertEquals(2, shows.size());
        assertEquals("Alpha", shows.get(0).name()); // spelled as the first episode in order
        assertEquals(1, shows.get(0).seasons().size());
        assertEquals(List.of("a0", "a1"), shows.get(0).seasons().get(0).episodes().stream().map(Ep::id).toList());
        Episodes.Show<Ep> beta = shows.get(1);
        assertEquals("Beta", beta.name());
        assertEquals(3, beta.seasons().size());
        assertNull(beta.seasons().get(0).season());
        assertEquals(1, beta.seasons().get(1).season());
        assertEquals(List.of("b1"), beta.seasons().get(1).episodes().stream().map(Ep::id).toList());
        assertEquals(2, beta.seasons().get(2).season());
        assertEquals(" alpha ".strip().toLowerCase(), Episodes.showKey("  ALPHA "));
    }
}
