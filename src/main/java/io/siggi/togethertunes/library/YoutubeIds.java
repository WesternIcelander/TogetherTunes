package io.siggi.togethertunes.library;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a pasted YouTube link into its 11-character video ID, the cache key of YouTube items
 * ({@code docs/library-and-ingest.md#youtube-caching}).
 *
 * <p>Accepted, with or without {@code https://} / {@code http://}, on {@code youtube.com},
 * {@code www.}, {@code m.} and {@code music.} hosts (and {@code youtube-nocookie.com} for embeds):
 * <ul>
 *   <li>{@code /watch?v=<id>} (other parameters such as {@code t=}, {@code list=}, {@code index=},
 *       {@code si=}, {@code feature=} and {@code pp=} are ignored, in any order);</li>
 *   <li>{@code /shorts/<id>}, {@code /embed/<id>}, {@code /live/<id>}, {@code /v/<id>} (anything
 *       after the ID, such as a query, a fragment or a trailing slash, is ignored);</li>
 *   <li>{@code youtu.be/<id>} (with {@code ?t=}, {@code ?si=}, …).</li>
 * </ul>
 * Anything else yields nothing: other hosts, playlists without a video, channel pages, malformed
 * IDs, a bare ID without a link. The ID is case-sensitive and returned as given.
 */
public final class YoutubeIds {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com");
    private static final Set<String> NOCOOKIE_HOSTS = Set.of("youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> SHORT_HOSTS = Set.of("youtu.be", "www.youtu.be");
    /** Path prefixes followed by the ID. */
    private static final Set<String> ID_PATHS = Set.of("shorts", "embed", "live", "v");
    /** Longest link considered (a real one is far shorter; this bounds the work on junk input). */
    static final int MAX_LENGTH = 2048;

    private YoutubeIds() {
    }

    /** Whether {@code id} has the form of a video ID. */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The URL every download and metadata request uses: {@code https://www.youtube.com/watch?v=<id>}. */
    public static String canonicalUrl(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Not a YouTube video ID: " + id);
        }
        return "https://www.youtube.com/watch?v=" + id;
    }

    /** The video ID in {@code link}, or empty if it isn't a YouTube video link. */
    public static Optional<String> parse(String link) {
        if (link == null) {
            return Optional.empty();
        }
        String s = link.strip();
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!SCHEME.matcher(s).find()) {
            if (s.startsWith("//")) {
                s = "https:" + s;
            } else {
                s = "https://" + s;
            }
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null) {
            return Optional.empty();
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String[] segments = path.replaceFirst("^/+", "").split("/", -1);
        String candidate = null;
        if (SHORT_HOSTS.contains(host)) {
            candidate = segments[0];
        } else if (YOUTUBE_HOSTS.contains(host)) {
            if (segments[0].equals("watch") && (segments.length == 1 || segments[1].isEmpty())) {
                candidate = queryParam(uri.getRawQuery(), "v");
            } else if (ID_PATHS.contains(segments[0]) && segments.length >= 2) {
                candidate = segments[1];
            }
        } else if (NOCOOKIE_HOSTS.contains(host)) {
            if (segments[0].equals("embed") && segments.length >= 2) {
                candidate = segments[1];
            }
        }
        return isValidId(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /** The first value of {@code name} in a raw query string, URL-decoded; null if absent. */
    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("[&;]")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (decode(key).equals(name)) {
                return eq < 0 ? "" : decode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
