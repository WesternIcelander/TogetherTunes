package io.siggi.togethertunes.library;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises links to other music services that can be resolved to a YouTube video through
 * MVFinder ({@code docs/library-and-ingest.md#links-from-other-music-services}). Only the hosts
 * below are recognised, so nothing else is ever sent to MVFinder; the request uses the canonical
 * path ({@link Link#path}), never the pasted text.
 *
 * <p>Spotify, on {@code open.spotify.com}: {@code /track/<id>}, also after a language segment
 * ({@code /intl-de/}) and as {@code /embed/track/<id>}; share parameters ({@code ?si=…}) are
 * ignored. The ID is the 22-character base-62 one.
 *
 * <p>TIDAL, on {@code tidal.com}, {@code listen.tidal.com} and {@code www.tidal.com}:
 * {@code /track/<id>}, {@code /browse/track/<id>}, share links ending in {@code /u} or {@code ?u},
 * and a track within an album ({@code /album/<id>/track/<id>}). The ID is numeric.
 *
 * <p>Album and playlist links on those hosts are recognised as such ({@link Kind#ALBUM},
 * {@link Kind#PLAYLIST}), so the caller can say that only single songs work
 * ({@code 422 not_a_track}). Anything else gives nothing.
 */
public final class MusicLinks {
    /** A music service whose links MVFinder resolves. */
    public enum Service {
        SPOTIFY("spotify", "open.spotify.com"),
        TIDAL("tidal", "tidal.com");

        private final String wire;
        private final String host;

        Service(String wire, String host) {
            this.wire = wire;
            this.host = host;
        }

        /** The name stored in {@code items.metadata_json.link.service}. */
        public String wire() {
            return wire;
        }

        /** The host of the canonical link. */
        String host() {
            return host;
        }
    }

    /** What a link points to. */
    public enum Kind {
        TRACK, ALBUM, PLAYLIST;

        String segment() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A recognised link: a service, what it points to and its ID. */
    public record Link(Service service, Kind kind, String id) {
        /** Whether it is a single song. */
        public boolean isTrack() {
            return kind == Kind.TRACK;
        }

        /** The canonical link without {@code https://}, e.g. {@code open.spotify.com/track/<id>}: MVFinder's path. */
        public String path() {
            return service.host() + "/" + kind.segment() + "/" + id;
        }
    }

    private static final Pattern SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://");
    private static final Pattern SPOTIFY_ID = Pattern.compile("[A-Za-z0-9]{22}");
    private static final Pattern TIDAL_ID = Pattern.compile("[0-9]{1,15}");
    private static final Pattern TIDAL_PLAYLIST_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern INTL = Pattern.compile("intl-[a-z]{2}(?:[-_][a-z0-9]{2,4})?");
    private static final Set<String> SPOTIFY_HOSTS = Set.of("open.spotify.com");
    private static final Set<String> TIDAL_HOSTS = Set.of("tidal.com", "listen.tidal.com", "www.tidal.com");
    /** Longest link considered (a real one is far shorter). */
    static final int MAX_LENGTH = 2048;

    private MusicLinks() {
    }

    /** The link in {@code text}, or empty if it isn't one of the recognised music-service links. */
    public static Optional<Link> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip();
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        if (!SCHEME.matcher(s).find()) {
            s = s.startsWith("//") ? "https:" + s : "https://" + s;
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
        if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) {
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
        List<String> segments = new ArrayList<>();
        for (String seg : (uri.getRawPath() == null ? "" : uri.getRawPath()).split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        if (SPOTIFY_HOSTS.contains(host)) {
            return spotify(segments);
        }
        if (TIDAL_HOSTS.contains(host)) {
            return tidal(segments);
        }
        return Optional.empty();
    }

    private static Optional<Link> spotify(List<String> segments) {
        int i = 0;
        if (i < segments.size() && INTL.matcher(segments.get(i).toLowerCase(Locale.ROOT)).matches()) {
            i++;
        }
        if (i < segments.size() && segments.get(i).equals("embed")) {
            i++;
        }
        if (segments.size() - i != 2) {
            return Optional.empty();
        }
        Kind kind = kind(segments.get(i));
        String id = segments.get(i + 1);
        if (kind == null || !SPOTIFY_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Link(Service.SPOTIFY, kind, id));
    }

    private static Optional<Link> tidal(List<String> segments) {
        List<String> segs = new ArrayList<>(segments);
        if (!segs.isEmpty() && segs.get(0).equals("browse")) {
            segs.remove(0);
        }
        if (!segs.isEmpty() && segs.get(segs.size() - 1).equals("u")) {
            segs.remove(segs.size() - 1); // share link suffix
        }
        if (segs.size() == 2) {
            Kind kind = kind(segs.get(0));
            String id = segs.get(1);
            if (kind == Kind.TRACK || kind == Kind.ALBUM) {
                return TIDAL_ID.matcher(id).matches() ? Optional.of(new Link(Service.TIDAL, kind, id)) : Optional.empty();
            }
            if (kind == Kind.PLAYLIST) {
                return TIDAL_PLAYLIST_ID.matcher(id).matches()
                        ? Optional.of(new Link(Service.TIDAL, kind, id.toLowerCase(Locale.ROOT))) : Optional.empty();
            }
            return Optional.empty();
        }
        if (segs.size() == 3 && segs.get(0).equals("album") && TIDAL_ID.matcher(segs.get(2)).matches()) {
            return Optional.of(new Link(Service.TIDAL, Kind.ALBUM, segs.get(2))); // /album/<name>/<id>
        }
        if (segs.size() == 4 && segs.get(0).equals("album") && TIDAL_ID.matcher(segs.get(1)).matches()
                && segs.get(2).equals("track") && TIDAL_ID.matcher(segs.get(3)).matches()) {
            return Optional.of(new Link(Service.TIDAL, Kind.TRACK, segs.get(3))); // a track within an album
        }
        return Optional.empty();
    }

    private static Kind kind(String segment) {
        return switch (segment) {
            case "track" -> Kind.TRACK;
            case "album" -> Kind.ALBUM;
            case "playlist" -> Kind.PLAYLIST;
            default -> null;
        };
    }
}
