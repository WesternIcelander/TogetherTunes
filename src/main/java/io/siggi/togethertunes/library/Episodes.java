package io.siggi.togethertunes.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The order of a show's episodes ({@code docs/media-types.md#next-episode}), without any database:
 * by season, then episode. Episodes without a season number come before season 0 (they form their
 * own group); equal (season, episode) pairs are ordered by item id so the order is stable.
 *
 * <p>Show names match case-insensitively and ignoring surrounding whitespace ({@link #showKey}).
 */
public final class Episodes {
    private Episodes() {
    }

    /** Where an episode sits: its season ({@code null}: none) and episode number. */
    public record Place(Integer season, int episode) {
    }

    /** Seasons first (no season before 0, 1, ...), then episodes. */
    public static final Comparator<Place> ORDER = Comparator
            .comparing(Place::season, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
            .thenComparingInt(Place::episode);

    /** The key show names are grouped and matched by: trimmed, lower case. */
    public static String showKey(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The episode after {@code current} among {@code candidates}: the lowest higher episode number
     * in the same season (gaps are skipped), otherwise the lowest episode of the next season that
     * exists. Candidates at {@code current}'s own place (the episode itself, or a duplicate) are
     * never next.
     *
     * @param place where a candidate sits
     * @param id    a candidate's tie-breaker among equal places (e.g. its item id)
     * @return the next candidate, or {@code null} when there's none
     */
    public static <T> T next(List<T> candidates, Place current, Function<T, Place> place, Function<T, String> id) {
        Objects.requireNonNull(current);
        T best = null;
        Place bestPlace = null;
        for (T c : candidates) {
            Place p = place.apply(c);
            if (ORDER.compare(p, current) <= 0) {
                continue;
            }
            int cmp = bestPlace == null ? -1 : ORDER.compare(p, bestPlace);
            if (cmp < 0 || (cmp == 0 && id.apply(c).compareTo(id.apply(best)) < 0)) {
                best = c;
                bestPlace = p;
            }
        }
        return best;
    }

    /** One season of a show: its number ({@code null}: none) and its episodes in order. */
    public record Season<T>(Integer season, List<T> episodes) {
    }

    /** One show: its name (as the first episode in order spells it) and its seasons in order. */
    public record Show<T>(String name, List<Season<T>> seasons) {
    }

    /**
     * Groups {@code episodes} by show name ({@link #showKey}), then season, then episode. Shows are
     * sorted by name (case-insensitively), seasons and episodes by {@link #ORDER}.
     */
    public static <T> List<Show<T>> group(List<T> episodes, Function<T, String> showName, Function<T, Place> place,
                                          Function<T, String> id) {
        Comparator<T> byPlace = Comparator.<T, Place>comparing(place, ORDER).thenComparing(id);
        List<T> sorted = new ArrayList<>(episodes);
        sorted.sort(Comparator.<T, String>comparing(e -> showKey(showName.apply(e))).thenComparing(byPlace));
        Map<String, List<T>> byShow = new LinkedHashMap<>();
        for (T e : sorted) {
            byShow.computeIfAbsent(showKey(showName.apply(e)), k -> new ArrayList<>()).add(e);
        }
        List<Show<T>> out = new ArrayList<>();
        for (List<T> show : byShow.values()) {
            List<Season<T>> seasons = new ArrayList<>();
            List<T> current = null;
            Integer currentSeason = null;
            for (T e : show) {
                Integer s = place.apply(e).season();
                if (current == null || !Objects.equals(s, currentSeason)) {
                    current = new ArrayList<>();
                    currentSeason = s;
                    seasons.add(new Season<>(s, current));
                }
                current.add(e);
            }
            out.add(new Show<>(showName.apply(show.get(0)).strip(),
                    seasons.stream().map(s -> new Season<>(s.season(), List.copyOf(s.episodes()))).toList()));
        }
        return List.copyOf(out);
    }
}
