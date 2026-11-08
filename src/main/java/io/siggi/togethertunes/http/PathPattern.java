package io.siggi.togethertunes.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A route path such as {@code /api/rooms/{id}/members/{userId}} or {@code /web/{path...}}.
 *
 * <ul>
 *   <li>A literal segment matches itself exactly (case-sensitive).</li>
 *   <li>{@code {name}} matches one non-empty segment.</li>
 *   <li>{@code {name...}}, only as the last segment, matches the rest of the path, slashes
 *       included. It may be empty, but the slash before it must be there: {@code /web/{path...}}
 *       matches {@code /web/} and {@code /web/js/main.js}, not {@code /web}.</li>
 * </ul>
 *
 * <p>Paths are matched after SiggiHTTP has percent-decoded them (it rejects {@code %2F}), so
 * parameter values are already decoded. A trailing slash is significant: {@code /a/} is not
 * {@code /a}.
 */
public final class PathPattern implements Comparable<PathPattern> {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private enum Kind { LITERAL, PARAM, REST }

    private record Segment(Kind kind, String text) {
    }

    private final String pattern;
    private final List<Segment> segments;

    private PathPattern(String pattern, List<Segment> segments) {
        this.pattern = pattern;
        this.segments = segments;
    }

    /**
     * Parses a pattern.
     *
     * @throws IllegalArgumentException if it is malformed
     */
    public static PathPattern parse(String pattern) {
        if (pattern == null || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("Route pattern must start with '/': " + pattern);
        }
        String[] parts = pattern.substring(1).split("/", -1);
        List<Segment> segments = new ArrayList<>(parts.length);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("{") && part.endsWith("}")) {
                String name = part.substring(1, part.length() - 1);
                Kind kind = Kind.PARAM;
                if (name.endsWith("...")) {
                    if (i != parts.length - 1) {
                        throw new IllegalArgumentException("{" + name + "} must be the last segment: " + pattern);
                    }
                    name = name.substring(0, name.length() - 3);
                    kind = Kind.REST;
                }
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Bad parameter name '" + name + "' in " + pattern);
                }
                if (!names.add(name)) {
                    throw new IllegalArgumentException("Duplicate parameter '" + name + "' in " + pattern);
                }
                segments.add(new Segment(kind, name));
            } else {
                if (part.contains("{") || part.contains("}")) {
                    throw new IllegalArgumentException("Parameters must fill a whole segment: " + pattern);
                }
                if (part.isEmpty() && i != parts.length - 1) {
                    throw new IllegalArgumentException("Empty segment in " + pattern);
                }
                segments.add(new Segment(Kind.LITERAL, part));
            }
        }
        return new PathPattern(pattern, List.copyOf(segments));
    }

    /**
     * Matches a request path.
     *
     * @return the parameter values by name, or {@code null} if the path doesn't match
     */
    public Map<String, String> match(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        String[] parts = path.substring(1).split("/", -1);
        Map<String, String> params = new LinkedHashMap<>();
        int n = segments.size();
        for (int i = 0; i < n; i++) {
            Segment segment = segments.get(i);
            if (segment.kind == Kind.REST) {
                // Everything from here on; "" for a path that ends in the slash before it.
                if (i >= parts.length) {
                    return null;
                }
                params.put(segment.text, String.join("/", List.of(parts).subList(i, parts.length)));
                return Collections.unmodifiableMap(params);
            }
            if (i >= parts.length) {
                return null;
            }
            String part = parts[i];
            if (segment.kind == Kind.LITERAL) {
                if (!segment.text.equals(part)) {
                    return null;
                }
            } else {
                if (part.isEmpty()) {
                    return null;
                }
                params.put(segment.text, part);
            }
        }
        return parts.length == n ? Collections.unmodifiableMap(params) : null;
    }

    /**
     * Orders patterns from most to least specific: segment by segment, a literal beats a parameter,
     * which beats a rest parameter; then the longer pattern wins. Two patterns that compare equal
     * match exactly the same paths.
     */
    @Override
    public int compareTo(PathPattern other) {
        int n = Math.min(segments.size(), other.segments.size());
        for (int i = 0; i < n; i++) {
            int c = segments.get(i).kind.compareTo(other.segments.get(i).kind);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(other.segments.size(), segments.size());
    }

    /** Whether both patterns match exactly the same paths. */
    boolean sameShape(PathPattern other) {
        if (segments.size() != other.segments.size()) {
            return false;
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment a = segments.get(i);
            Segment b = other.segments.get(i);
            if (a.kind != b.kind || (a.kind == Kind.LITERAL && !a.text.equals(b.text))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return pattern;
    }
}
