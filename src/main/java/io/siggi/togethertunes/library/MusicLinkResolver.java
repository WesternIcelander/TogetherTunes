package io.siggi.togethertunes.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.togethertunes.auth.Actor;
import io.siggi.togethertunes.http.HttpError;
import io.siggi.togethertunes.settings.LinkPreference;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a link from another music service ({@link MusicLinks}) to a YouTube video through
 * MVFinder ({@code docs/library-and-ingest.md#links-from-other-music-services}).
 *
 * <p>The call is {@code GET <url>/<canonical link without https://>?single=1&type=all} with the
 * configured header (only when it has a value), 20 s timeout. The pick follows
 * {@link LinkPreference}: {@code music_video} takes the music video (or the official lyric,
 * visualizer or live upload MVFinder returns in its place), else the YouTube Music song;
 * {@code song} the other way round; user uploads are never picked. A match whose {@code videoId}
 * isn't a valid YouTube ID is skipped.
 *
 * <p>Errors: {@code 422 not_a_track} (an album or playlist link), {@code 401 not_authenticated}
 * (a guest while {@code mvfinder.allowGuests} is off), {@code 429 too_many_lookups} with
 * {@code Retry-After} (more than {@code mvfinder.maxPerHour} resolutions by this actor in the past
 * hour), {@code 422 link_not_resolved} (nothing usable, or MVFinder answered 404 / another 4xx) and
 * {@code 503 resolver_unavailable} (5xx, timeout, unreachable, an answer that isn't the expected
 * JSON). Nothing here ever logs or returns the header value.
 */
public final class MusicLinkResolver {
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Largest answer read (a single track's answer is a few KiB). */
    static final int MAX_BYTES = 1024 * 1024;
    private static final long HOUR_MS = 3_600_000L;
    /** Actors whose recent resolutions are remembered before idle ones are dropped. */
    private static final int MAX_ACTORS = 10_000;
    private static final System.Logger LOG = System.getLogger(MusicLinkResolver.class.getName());

    /**
     * The settings a resolution uses, read for every call.
     *
     * @param url         MVFinder's base URL, or null when the feature is off
     * @param headerName  the header's name
     * @param headerValue the header's value; empty = no header (a secret)
     */
    public record Options(String url, String headerName, String headerValue, LinkPreference prefer,
                          boolean allowGuests, int maxPerHour) {
        @Override
        public String toString() {
            // never the header value
            return "Options[url=" + url + ", headerName=" + headerName + ", prefer=" + prefer
                    + ", allowGuests=" + allowGuests + ", maxPerHour=" + maxPerHour + "]";
        }
    }

    /** Where the options come from (the admin settings). */
    @FunctionalInterface
    public interface OptionsSource {
        Options get() throws Exception;
    }

    /**
     * The link's track as the music service has it ({@code source} in MVFinder's answer).
     *
     * @param artists the artists, cleaned, possibly empty
     */
    public record Track(MusicLinks.Service service, String id, String title, List<String> artists, String album,
                        String isrc) {
    }

    /**
     * One of MVFinder's video matches ({@code music_video}, {@code lyric_video},
     * {@code official_video}, {@code visualizer}) that wasn't imported: kept in the new item's
     * {@code metadata_json.link.videos} as a candidate for "Find a music video"
     * ({@code docs/library-and-ingest.md#linked-media}).
     *
     * @param durationMs null when MVFinder didn't say
     */
    public record VideoMatch(String videoId, String title, List<String> artists, Long durationMs, String type) {
        /** The JSON stored in {@code metadata_json.link.videos}. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("videoId", videoId);
            if (title != null) {
                o.addProperty("title", title);
            }
            JsonArray a = new JsonArray();
            artists.forEach(a::add);
            o.add("artists", a);
            if (durationMs != null) {
                o.addProperty("durationMs", durationMs);
            }
            o.addProperty("type", type);
            return o;
        }
    }

    /** Video match types kept as candidates ({@link VideoMatch}). */
    static final List<String> VIDEO_TYPES = List.of("music_video", "lyric_video", "official_video", "visualizer");
    /** At most this many video matches are kept. */
    static final int MAX_VIDEOS = 10;

    /**
     * A resolved link: the YouTube video to import, the link's track, and MVFinder's video matches
     * other than the imported one (in MVFinder's order, at most {@link #MAX_VIDEOS}).
     */
    public record Resolved(String videoId, Track track, List<VideoMatch> videos) {
        public Resolved(String videoId, Track track) {
            this(videoId, track, List.of());
        }
    }

    private final OptionsSource options;
    private final HttpClient http;
    private final Clock clock;
    private final Duration timeout;
    private final Map<String, ArrayDeque<Long>> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Long>> eldest) {
            return size() > MAX_ACTORS;
        }
    };

    public MusicLinkResolver(OptionsSource options, Clock clock) {
        this(options, clock, TIMEOUT);
    }

    /** With another timeout than the documented 20 s (tests). */
    MusicLinkResolver(OptionsSource options, Clock clock, Duration timeout) {
        this.options = Objects.requireNonNull(options);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The current options; a failure reading them counts as "off". */
    Options options() {
        try {
            return options.get();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Couldn't read the link resolver settings: " + e.getMessage());
            return null;
        }
    }

    /** Whether links are resolved at all ({@code mvfinder.url} set). */
    public boolean enabled() {
        Options o = options();
        return o != null && o.url() != null && !o.url().isBlank();
    }

    /**
     * Resolves {@code link} for {@code actor}; see the class comment.
     *
     * @throws HttpError {@code 400 invalid_youtube_url} when the feature is off, and the errors above
     */
    public Resolved resolve(Actor actor, MusicLinks.Link link) throws InterruptedException {
        Options o = options();
        if (o == null || o.url() == null || o.url().isBlank()) {
            throw new HttpError(400, "invalid_youtube_url", "That isn't a link to a YouTube video");
        }
        if (!link.isTrack()) {
            throw new HttpError(422, "not_a_track", "Only links to a single song work here");
        }
        String actorKey;
        if (actor instanceof Actor.User u) {
            actorKey = "u:" + u.userId();
        } else if (actor instanceof Actor.Guest g) {
            if (!o.allowGuests()) {
                throw new HttpError(401, "not_authenticated", "Log in to request links like this");
            }
            actorKey = "g:" + g.guestId();
        } else {
            throw new HttpError(401, "not_authenticated", "Log in to request links like this");
        }
        take(actorKey, o.maxPerHour());
        JsonObject answer = fetch(o, link.path() + "?single=1&type=all", link.path());
        return pick(answer, link, o.prefer());
    }

    /**
     * MVFinder's video matches for a recording ({@code GET <url>/isrc/<isrc>?type=all}, the same
     * header and timeout as {@link #resolve}): its {@code music_video}, {@code lyric_video},
     * {@code official_video} and {@code visualizer} matches ({@link #videos}), never user uploads.
     * Empty when the feature is off or the ISRC isn't a valid one. Counts nothing: the caller
     * ("Find a music video", {@link VideoSearch}) has its own per-person limit.
     *
     * @throws HttpError {@code 422 link_not_resolved} (MVFinder doesn't know it, 4xx),
     *                   {@code 503 resolver_unavailable}
     */
    public List<VideoMatch> isrcVideos(String isrc) throws InterruptedException {
        Options o = options();
        String code = SongDetails.isrc(isrc);
        if (o == null || o.url() == null || o.url().isBlank() || code == null) {
            return List.of();
        }
        String path = "isrc/" + code;
        JsonObject answer = fetch(o, path + "?type=all", path);
        JsonElement matches = answer.get("matches");
        if (matches == null || !matches.isJsonArray()) {
            return List.of();
        }
        return videos(matches.getAsJsonArray(), null);
    }

    /** Counts one resolution for {@code actorKey}, or throws {@code 429 too_many_lookups}. */
    private void take(String actorKey, int maxPerHour) {
        long now = clock.millis();
        synchronized (recent) {
            ArrayDeque<Long> times = recent.computeIfAbsent(actorKey, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst() <= now - HOUR_MS) {
                times.pollFirst();
            }
            if (times.size() >= maxPerHour) {
                long waitMs = times.peekFirst() + HOUR_MS - now;
                long waitSec = Math.max(1, (waitMs + 999) / 1000);
                throw new HttpError(429, "too_many_lookups", "Too many links like this in the past hour; try again in "
                        + Math.max(1, (waitSec + 59) / 60) + " min", waitSec);
            }
            times.addLast(now);
        }
    }

    /** {@code GET <url>/<pathAndQuery>}; {@code what} names it in the logs (never the header). */
    private JsonObject fetch(Options o, String pathAndQuery, String what) throws InterruptedException {
        URI uri;
        try {
            uri = URI.create(o.url().replaceAll("/+$", "") + "/" + pathAndQuery);
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", "application/json");
        if (o.headerValue() != null && !o.headerValue().isEmpty() && o.headerName() != null
                && !o.headerName().isEmpty()) {
            req.header(o.headerName(), o.headerValue());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            LOG.log(System.Logger.Level.INFO, "Link resolver didn't answer in time for " + what);
            throw unavailable();
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.INFO, "Link resolver unreachable for " + what + ": "
                    + e.getClass().getSimpleName());
            throw unavailable();
        }
        int status = response.statusCode();
        if (status == 422) {
            throw new HttpError(422, "not_a_track", "Only links to a single song work here");
        }
        if (status >= 400 && status < 500) {
            LOG.log(System.Logger.Level.INFO, "Link resolver answered " + status + " for " + what);
            throw notResolved();
        }
        if (status != 200) {
            LOG.log(System.Logger.Level.INFO, "Link resolver answered " + status + " for " + what);
            throw unavailable();
        }
        byte[] body = response.body();
        if (body == null || body.length > MAX_BYTES) {
            throw unavailable();
        }
        try {
            JsonElement e = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("not an object");
            }
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Link resolver gave an unreadable answer for " + what);
            throw unavailable();
        }
    }

    /** The match to import from MVFinder's answer, and the link's track. */
    static Resolved pick(JsonObject answer, MusicLinks.Link link, LinkPreference prefer) {
        JsonElement matchesEl = answer.get("matches");
        if (matchesEl == null || !matchesEl.isJsonArray()) {
            throw notResolved();
        }
        String song = null;
        String video = null;
        int videoRank = Integer.MAX_VALUE;
        for (JsonElement m : matchesEl.getAsJsonArray()) {
            if (!m.isJsonObject()) {
                continue;
            }
            String type = string(m.getAsJsonObject(), "type");
            String videoId = string(m.getAsJsonObject(), "videoId");
            if (type == null || !YoutubeIds.isValidId(videoId)) {
                continue;
            }
            int rank = switch (type) {
                case "music_video" -> 0;
                case "official_video" -> 1;
                case "lyric_video", "visualizer" -> 2;
                case "official_audio" -> 3;
                default -> -1;
            };
            if (type.equals("song")) {
                if (song == null) {
                    song = videoId;
                }
            } else if (rank >= 0 && rank < videoRank) {
                video = videoId;
                videoRank = rank;
            }
        }
        String chosen = prefer == LinkPreference.SONG ? (song != null ? song : video) : (video != null ? video : song);
        if (chosen == null) {
            throw notResolved();
        }
        return new Resolved(chosen, track(answer, link), videos(matchesEl.getAsJsonArray(), chosen));
    }

    /** MVFinder's video matches ({@link #VIDEO_TYPES}) except {@code chosen}, deduplicated by ID. */
    static List<VideoMatch> videos(JsonArray matches, String chosen) {
        List<VideoMatch> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(chosen);
        for (JsonElement m : matches) {
            if (!m.isJsonObject() || out.size() >= MAX_VIDEOS) {
                continue;
            }
            JsonObject o = m.getAsJsonObject();
            String type = string(o, "type");
            String videoId = string(o, "videoId");
            if (type == null || !VIDEO_TYPES.contains(type) || !YoutubeIds.isValidId(videoId) || !seen.add(videoId)) {
                continue;
            }
            List<String> artists = new ArrayList<>();
            JsonElement a = o.get("artists");
            if (a != null && a.isJsonArray()) {
                for (JsonElement n : (JsonArray) a) {
                    if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isString()) {
                        artists.add(n.getAsString());
                    }
                }
            }
            Long durationMs = null;
            JsonElement d = o.get("durationMs");
            if (d != null && d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
                double v = d.getAsDouble();
                if (v > 0 && v < 1e12) {
                    durationMs = (long) v;
                }
            }
            out.add(new VideoMatch(videoId, UploadMetadata.clean(string(o, "title")), SongDetails.cleanNames(artists),
                    durationMs, type));
        }
        return List.copyOf(out);
    }

    private static Track track(JsonObject answer, MusicLinks.Link link) {
        JsonElement s = answer.get("source");
        JsonObject source = s != null && s.isJsonObject() ? s.getAsJsonObject() : new JsonObject();
        List<String> artists = new ArrayList<>();
        JsonElement a = source.get("artists");
        if (a != null && a.isJsonArray()) {
            for (JsonElement n : (JsonArray) a) {
                if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isString()) {
                    artists.add(n.getAsString());
                }
            }
        }
        return new Track(link.service(), link.id(), UploadMetadata.clean(string(source, "title")),
                SongDetails.cleanNames(artists), UploadMetadata.clean(string(source, "album")),
                SongDetails.isrc(string(source, "isrc")));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static HttpError notResolved() {
        return new HttpError(422, "link_not_resolved", "Couldn't find this song");
    }

    private static HttpError unavailable() {
        return new HttpError(503, "resolver_unavailable", "Can't look up this link right now");
    }
}
