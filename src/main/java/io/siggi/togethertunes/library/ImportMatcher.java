package io.siggi.togethertunes.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the files of a library import's tracks in the source
 * ({@code docs/library-and-ingest.md#library-import}, "Matching files"). A track's path is the
 * full path on the exporting computer; the source holds the same files under another root. Paths
 * are compared NFC-normalised and case-insensitively, part by part. The prefix to cut off is the
 * one most of the first matching tracks agree on (each track's longest path suffix that is a whole
 * path in the source); a track it doesn't work for falls back to the unique file with the longest
 * common path suffix, and is missing ({@code ambiguous}) when several files share that suffix.
 */
public final class ImportMatcher {
    /** How many matching tracks vote on the prefix. */
    static final int VOTERS = 50;

    /** Why a track has no file. */
    public static final String NO_FILE = "no_file";
    public static final String NOT_FOUND = "not_found";
    public static final String AMBIGUOUS = "ambiguous";

    /**
     * A track's file.
     *
     * @param file   its path inside the source, or null
     * @param reason why there is none ({@link #NO_FILE}, {@link #NOT_FOUND}, {@link #AMBIGUOUS}), or null
     */
    public record Match(String file, String reason) {
    }

    /** The matches, by the tracks' index, and the prefix that was cut off (normalised; null if none was found). */
    public record Result(List<Match> matches, String prefix) {
        /** The number of tracks with a file. */
        public int matched() {
            int n = 0;
            for (Match m : matches) {
                if (m.file() != null) {
                    n++;
                }
            }
            return n;
        }
    }

    private ImportMatcher() {
    }

    /** NFC, lower case. */
    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    static List<String> parts(String path) {
        List<String> out = new ArrayList<>();
        for (String p : norm(path).split("/")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String join(List<String> parts, int from, int to) {
        return String.join("/", parts.subList(from, to));
    }

    /**
     * Matches {@code trackPaths} (each may be null: no file) against {@code files} (paths inside
     * the source).
     */
    public static Result match(List<String> trackPaths, Collection<String> files) {
        Map<String, List<String>> whole = new HashMap<>();
        Map<String, List<String>> suffixes = new HashMap<>();
        for (String f : files) {
            List<String> p = parts(f);
            whole.computeIfAbsent(join(p, 0, p.size()), k -> new ArrayList<>()).add(f);
            for (int k = 0; k < p.size(); k++) {
                suffixes.computeIfAbsent(join(p, k, p.size()), x -> new ArrayList<>()).add(f);
            }
        }
        List<List<String>> tracks = new ArrayList<>();
        for (String t : trackPaths) {
            tracks.add(t == null ? null : parts(t));
        }

        // The prefix: what most of the first matching tracks agree on.
        Map<String, Integer> votes = new LinkedHashMap<>();
        int voters = 0;
        for (List<String> p : tracks) {
            if (p == null || voters >= VOTERS) {
                continue;
            }
            for (int cut = 0; cut < p.size(); cut++) {
                if (whole.containsKey(join(p, cut, p.size()))) {
                    votes.merge(join(p, 0, cut), 1, Integer::sum);
                    voters++;
                    break;
                }
            }
        }
        String prefix = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                prefix = e.getKey();
            }
        }
        List<String> prefixParts = prefix == null ? null : parts(prefix);

        List<Match> out = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            List<String> p = tracks.get(i);
            if (p == null || p.isEmpty()) {
                out.add(new Match(null, NO_FILE));
                continue;
            }
            Match m = null;
            if (prefixParts != null && p.size() > prefixParts.size()
                    && p.subList(0, prefixParts.size()).equals(prefixParts)) {
                List<String> found = whole.get(join(p, prefixParts.size(), p.size()));
                if (found != null) {
                    m = pick(found, trackPaths.get(i));
                }
            }
            if (m == null) {
                m = new Match(null, NOT_FOUND);
                for (int cut = 0; cut < p.size(); cut++) {
                    List<String> found = suffixes.get(join(p, cut, p.size()));
                    if (found != null) {
                        m = found.size() == 1 ? new Match(found.get(0), null) : pick(found, trackPaths.get(i));
                        break;
                    }
                }
            }
            out.add(m);
        }
        return new Result(List.copyOf(out), prefix);
    }

    /** One of several files that match equally well: the one whose path ends exactly (NFC, same case) like the track's, if only one does. */
    private static Match pick(List<String> found, String trackPath) {
        if (found.size() == 1) {
            return new Match(found.get(0), null);
        }
        String t = Normalizer.normalize(trackPath, Normalizer.Form.NFC);
        String only = null;
        for (String f : found) {
            String n = Normalizer.normalize(f, Normalizer.Form.NFC);
            if (t.endsWith("/" + n) || t.equals(n)) {
                if (only != null) {
                    return new Match(null, AMBIGUOUS);
                }
                only = f;
            }
        }
        return only != null ? new Match(only, null) : new Match(null, AMBIGUOUS);
    }
}
